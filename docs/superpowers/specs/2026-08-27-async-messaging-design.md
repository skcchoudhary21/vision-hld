# Async Messaging — Design

> Two independent seams, both stubbed with the same `MessagePublisher`/
> `MessageConsumer`/`MessageHandler` shape (one copy per service — no
> shared module exists between `approval-engine` and `banking-service`):
>
> 1. **Notification** (`approval-engine` → `banking-service`): already
>    fire-and-forget today via the outbox+webhook mechanism. This seam
>    is a pure refactor — zero behavior change, zero risk.
> 2. **Submission** (`banking-service` → `approval-engine`): today
>    synchronous (`TransferSubmissionService` waits for `POST /approvals`
>    before returning). Making this async is a real behavior change,
>    not a transport swap — covered in full below since it's the harder
>    and riskier of the two.

## 1. Notification seam (`approval-engine`, low risk)

Already traced against the live code: `OutboxRelay` polls
`OutboxClaimService` every 2s and does a direct `RestClient` POST to
`banking-service.webhook-url`. This seam replaces that direct call with
a broker interface, preserving the exact same wire behavior.

**New package `com.visionbank.approval.messaging`:**
```java
public record ApprovalEvent(String eventId, String eventType, String requestId, String payload) {
    public static final String TOPIC = "approval.events";
}

public interface MessagePublisher {
    void publish(String topic, ApprovalEvent event);
}

public interface MessageConsumer {
    void subscribe(String topic, MessageHandler handler);
}

@FunctionalInterface
public interface MessageHandler {
    void handle(ApprovalEvent event) throws Exception;
}
```

`InMemoryMessageBroker implements MessagePublisher, MessageConsumer`:
```java
@Component
public class InMemoryMessageBroker implements MessagePublisher, MessageConsumer {
    private final Map<String, List<MessageHandler>> handlers = new ConcurrentHashMap<>();

    @Override
    public void publish(String topic, ApprovalEvent event) {
        for (MessageHandler handler : handlers.getOrDefault(topic, List.of())) {
            try {
                handler.handle(event);
            } catch (Exception e) {
                throw new RuntimeException("Handler failed for topic " + topic, e);
            }
        }
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        handlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }
}
```
Deliberately **synchronous** here — unlike the submission seam below,
nothing is waiting on `OutboxRelay.publish()`'s caller for a fast
response; `relayOnce()` already runs on its own `@Scheduled` thread
every 2s, so a handler taking a moment to do its HTTP call doesn't
block anything else. Propagating the handler's exception (wrapped) is
what lets `OutboxRelay` keep its existing retry semantics.

**`WebhookRelayHandler implements MessageHandler`** — the exact HTTP-POST
logic that lives in `OutboxRelay` today, moved verbatim:
```java
@Component
public class WebhookRelayHandler implements MessageHandler {
    private final RestClient restClient;
    private final String webhookUrl;

    public WebhookRelayHandler(MessageConsumer consumer, @Value("${banking-service.webhook-url}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        consumer.subscribe(ApprovalEvent.TOPIC, this::handle);
    }

    public void handle(ApprovalEvent event) {
        HttpStatusCode status = restClient.post()
                .uri(webhookUrl)
                .header("X-Event-Id", event.eventId())
                .header("X-Event-Type", event.eventType())
                .contentType(MediaType.APPLICATION_JSON)
                .body(event.payload())
                .retrieve()
                .toBodilessEntity()
                .getStatusCode();
        if (!status.is2xxSuccessful()) {
            throw new IllegalStateException("Webhook delivery failed: " + status);
        }
    }
}
```

**`OutboxRelay` changes**: drops `RestClient`/`webhookUrl`/`HttpClient`
entirely, takes `MessagePublisher` instead:
```java
private boolean publish(OutboxEvent event) {
    try {
        messagePublisher.publish(ApprovalEvent.TOPIC,
                new ApprovalEvent(event.getEventId(), event.getEventType(), event.getRequestId(), event.getPayload()));
        return true;
    } catch (Exception e) {
        log.warn("Failed to relay event {} ({}): {}", event.getEventId(), event.getEventType(), e.getMessage());
        return false;
    }
}
```

**Untouched**: `OutboxClaimService`, `OutboxRelayTest` (still stubs the
same WireMock endpoint — the HTTP call still physically happens, one
hop further inside `WebhookRelayHandler`), `application.yml`'s
`webhook-url` key (same value, different reader), everything in
`banking-service`.

**New test**: `InMemoryMessageBrokerTest` — publish/subscribe basics,
multiple handlers on one topic, an unsubscribed topic is a no-op, a
handler's exception propagates out of `publish()`.

## 2. Submission seam (`banking-service`, real behavior change)

### 2.1 Current flow, traced against the live code

```
TransferController.submit()
  → TransferSubmissionService.submit(cmd, idempotencyKey)
      → coreBanking.validate(...)                          [sync, external]
      → persistenceService.persistCreated(...)              → Transfer{CREATED}
      → completeWorkflowCreation(transfer, cmd):
          → policyResolver.resolve(amount)                  [sync GET /policy-rules/resolve]
          → approvalEngineClient.createWorkflow(...)         [sync POST /approvals]
          → persistenceService.markPendingApproval(...)      → Transfer{PENDING_APPROVAL}
      → return TransferView{PENDING_APPROVAL}
```
`completeWorkflowCreation` is also the **resume path**: if `submit()` is
replayed with an idempotency key whose `Transfer` row exists but has no
`approvalRequestId` yet (a crash between `persistCreated` and
`markPendingApproval`), it re-runs the same steps against the *same*
`transfer.getExpiresAt()` — never recomputed — and the *same*
`transferId` as the idempotency key sent to `approvalEngineClient`,
which is how retries/resumes never double-create on approval-engine's
side.

### 2.2 New flow

```
TransferController.submit()
  → TransferSubmissionService.submit(cmd, idempotencyKey)
      → coreBanking.validate(...)                          [unchanged, sync]
      → persistenceService.persistCreated(...)              → Transfer{CREATED}
      → messagePublisher.publish(CreateTransferApprovalCommand.TOPIC, command)
      → return TransferView{CREATED}                        [returns immediately]

  [asynchronously, on a virtual thread spawned by the broker]
  CreateTransferApprovalHandler.handle(command):
      → policyResolver.resolve(command.amountMinorUnits())  [sync GET, same call as before]
      → approvalEngineClient.createWorkflow(...)             [sync POST, same call as before]
      → persistenceService.markPendingApproval(...)          → Transfer{PENDING_APPROVAL}
      on failure: retry with backoff (§2.4); exhausted → Transfer{FAILED}
```

### 2.3 New package `com.visionbank.banking.messaging`

Same three interfaces as §1, banking-service's own copy (no shared
module to put them in):
```java
public record CreateTransferApprovalCommand(String transferId, String makerId,
                                              long amountMinorUnits, java.time.Instant expiresAt) {
    public static final String TOPIC = "transfer.approval.create";
}

public interface MessagePublisher {
    void publish(String topic, CreateTransferApprovalCommand command);
}

public interface MessageConsumer {
    void subscribe(String topic, MessageHandler handler);
}

@FunctionalInterface
public interface MessageHandler {
    void handle(CreateTransferApprovalCommand command) throws Exception;
}
```
`InMemoryMessageBroker implements MessagePublisher, MessageConsumer` —
**this copy dispatches asynchronously**, unlike approval-engine's:
```java
@Component
public class InMemoryMessageBroker implements MessagePublisher, MessageConsumer {
    private final Map<String, List<MessageHandler>> handlers = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageBroker.class);

    @Override
    public void publish(String topic, CreateTransferApprovalCommand command) {
        for (MessageHandler handler : handlers.getOrDefault(topic, List.of())) {
            Thread.ofVirtual().start(() -> {
                try {
                    handler.handle(command);
                } catch (Exception e) {
                    log.error("Handler failed for topic {}: {}", topic, e.getMessage(), e);
                }
            });
        }
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        handlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }
}
```
`publish()` here can't propagate a handler's exception the way
approval-engine's does — the caller (`TransferSubmissionService`) has
already returned by the time the handler runs. This is exactly the
point of making it async: the handler is now solely responsible for
its own success/failure handling (§2.4), with nothing upstream able to
react to it synchronously anymore. Logged, not swallowed silently.

### 2.4 `CreateTransferApprovalHandler` — retry with backoff, then `FAILED`

```java
@Component
public class CreateTransferApprovalHandler {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration[] BACKOFF = { Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4) };
    private static final Logger log = LoggerFactory.getLogger(CreateTransferApprovalHandler.class);

    private final PolicyResolver policyResolver;
    private final ApprovalEngineClient approvalEngineClient;
    private final TransferPersistenceService persistenceService;

    public CreateTransferApprovalHandler(MessageConsumer consumer, PolicyResolver policyResolver,
                                          ApprovalEngineClient approvalEngineClient,
                                          TransferPersistenceService persistenceService) {
        this.policyResolver = policyResolver;
        this.approvalEngineClient = approvalEngineClient;
        this.persistenceService = persistenceService;
        consumer.subscribe(CreateTransferApprovalCommand.TOPIC, this::handle);
    }

    public void handle(CreateTransferApprovalCommand command) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                WorkflowSelection selection = policyResolver.resolve(command.amountMinorUnits());
                CreateWorkflowRequest req = new CreateWorkflowRequest(
                        command.transferId(), "TRANSFER_APPROVAL", command.makerId(), selection,
                        "{\"transferId\":\"" + command.transferId() + "\",\"amount\":" + command.amountMinorUnits() + "}",
                        command.expiresAt());
                WorkflowResponse response = approvalEngineClient.createWorkflow(req, command.transferId());
                persistenceService.markPendingApproval(command.transferId(), response.requestId());
                return;
            } catch (Exception e) {
                log.warn("Attempt {}/{} to create approval request for transfer {} failed: {}",
                        attempt + 1, MAX_ATTEMPTS, command.transferId(), e.getMessage());
                if (attempt == MAX_ATTEMPTS - 1) {
                    persistenceService.markFailed(command.transferId());
                    log.error("Giving up creating approval request for transfer {} after {} attempts",
                            command.transferId(), MAX_ATTEMPTS);
                    return;
                }
                Thread.sleep(BACKOFF[attempt].toMillis());
            }
        }
    }
}
```
Retries reuse `command.transferId()` as the idempotency key on every
attempt, exactly like today's resume path — approval-engine's own
idempotency mechanism means a retry after a *partial* success (engine
created the request but the response was lost before banking-service
recorded it) replays safely rather than double-creating.

**Resume path**: `TransferSubmissionService.submit()`'s existing branch
for "row exists, no `approvalRequestId` yet" now re-publishes the
command instead of re-running `completeWorkflowCreation` inline —
same effect (another attempt at creation), same idempotency-key reuse,
now just also async.

### 2.5 New terminal state: `TransferState.FAILED`

```java
public enum TransferState {
    CREATED, PENDING_APPROVAL, RELEASE_PENDING, RELEASED, REJECTED, CANCELLED, EXPIRED, FAILED
}
```
Distinct from `REJECTED`/`CANCELLED`/`EXPIRED` — those are *approval
workflow outcomes* (the request was created and someone/something
decided against it); `FAILED` means the request was never successfully
created in the first place. `TransferPersistenceService` gains:
```java
@Transactional
public Transfer markFailed(String transferId) {
    Transfer transfer = transfers.findById(transferId).orElseThrow();
    transfer.setState(TransferState.FAILED);
    return transfers.save(transfer);
}
```

### 2.6 `TransferSubmissionService` changes

```java
public TransferView submit(SubmitTransferCommand cmd, String idempotencyKey) {
    Optional<Transfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
        Transfer t = existing.get();
        if (t.getApprovalRequestId() != null) {
            return new TransferView(t.getTransferId(), t.getState());
        }
        publishCreationCommand(t, cmd); // deliberately re-attempts even if t.getState() is FAILED --
                                         // a replayed idempotency key means "try this again", and a
                                         // prior permanent failure (engine was down, say) may no longer
                                         // apply; same transferId either way, so this never double-creates
        return new TransferView(t.getTransferId(), t.getState());
    }

    ValidationResult validation = coreBanking.validate(cmd.fromAccount(), cmd.amountMinorUnits(), idempotencyKey);
    if (!validation.isValid()) {
        throw new ValidationFailedException(/* unchanged */);
    }

    String transferId = UUID.randomUUID().toString();
    Instant expiresAt = Instant.now().plusSeconds(approvalSlaSeconds);
    Transfer created = persistenceService.persistCreated(transferId, cmd, idempotencyKey, expiresAt);

    publishCreationCommand(created, cmd);
    return new TransferView(created.getTransferId(), created.getState()); // CREATED
}

private void publishCreationCommand(Transfer transfer, SubmitTransferCommand cmd) {
    messagePublisher.publish(CreateTransferApprovalCommand.TOPIC,
            new CreateTransferApprovalCommand(transfer.getTransferId(), cmd.makerId(), cmd.amountMinorUnits(), transfer.getExpiresAt()));
}
```
`completeWorkflowCreation` is deleted — its body moved into
`CreateTransferApprovalHandler.handle()` (§2.4). `TransferSubmissionService`
no longer depends on `ApprovalEngineClient`/`PolicyResolver`/`CreateWorkflowRequest`/
`WorkflowResponse` at all — only `MessagePublisher`.

### 2.7 Test changes — poll-based, per the resolved question

`TransferSubmissionServiceTest`'s four affected tests
(`engineReturningApprovedStillLeavesTransferPendingApproval`,
`engineReturningPendingApprovalLeavesTransferPendingApproval`,
`replayingSameIdempotencyKeyReturnsSameTransferWithoutSecondEngineCall`,
`resumingAfterCrashReusesThePersistedTransferIdAndExpiresAtWithoutReValidating`)
change from asserting immediately after `submit()` returns to polling.
No new test dependency (Awaitility isn't used anywhere in this codebase
today) — a small manual poll helper, matching the plain-JUnit style
already everywhere else:
```java
private Transfer awaitState(String transferId, TransferState expected) throws InterruptedException {
    for (int i = 0; i < 50; i++) { // up to 5s
        Transfer t = transfers.findById(transferId).orElseThrow();
        if (t.getState() == expected) return t;
        Thread.sleep(100);
    }
    throw new AssertionError("Transfer " + transferId + " never reached " + expected
            + "; last state was " + transfers.findById(transferId).map(Transfer::getState).orElse(null));
}
```
`engineReturningApprovedStillLeavesTransferPendingApproval` becomes:
```java
@Test
void engineReturningApprovedStillLeavesTransferPendingApproval() throws InterruptedException {
    engineStub.stubFor(post(urlEqualTo("/approvals"))
            .willReturn(okJson("{\"requestId\":\"whatever\",\"state\":\"APPROVED\",\"version\":1}")));

    TransferView view = service.submit(smallTransfer(), UUID.randomUUID().toString());
    assertThat(view.state()).isEqualTo(TransferState.CREATED); // immediate return, per the new contract

    awaitState(view.transferId(), TransferState.PENDING_APPROVAL);
}
```
Same shape for the other three (`replayingSameIdempotencyKeyReturnsSameTransferWithoutSecondEngineCall`
still calls `submit()` twice with the same key and asserts the same
`transferId` back both times — that part is unaffected by async timing
— then awaits `PENDING_APPROVAL` once before asserting the WireMock
call count is still 1, so the assertion doesn't race the async handler).

**New tests**, covering what's genuinely new behavior:
- `CreateTransferApprovalHandlerTest`: engine returns 5xx every attempt
  → `Transfer` ends in `FAILED` after exactly `MAX_ATTEMPTS` calls
  (`engineStub.verify(3, postRequestedFor(...))`); engine fails twice
  then succeeds → `Transfer` ends in `PENDING_APPROVAL`, 3 calls made.
- `InMemoryMessageBrokerTest` (banking-service's copy): `publish()`
  returns before a slow handler finishes (asserts elapsed wall-clock
  time is small even though the handler sleeps) — this is the test that
  actually proves the async contract holds, not just that the plumbing
  compiles.

### 2.8 `TransferControllerTest` impact

`submitReturnsPendingApproval` (currently asserts `$.state` is
`"PENDING_APPROVAL"` immediately after `POST /transfers`) must become
`"CREATED"` — this is the one place the *public API contract* visibly
changes. `getReturnsFullTransferDetails` and the two `list*` tests
already fetch state via a *separate* `GET` after submission, but do so
immediately — they need the same `awaitState`-style wait before
asserting `approvalRequestId` is present, since that field is now only
populated once the async handler completes.

## 3. What does NOT change

- `banking-service`'s receiving side (`EventWebhookController`,
  `ApprovalEventListener`, `ProcessedEventRepository`) — untouched by
  either seam. Notification delivery to banking-service is still a
  plain HTTP POST arriving at a REST controller; only the *sending*
  side (inside approval-engine) is now broker-shaped.
- `approval-engine`'s own `/approvals` endpoint, `/policy-rules/resolve`,
  idempotency-key mechanism — all called exactly as today, just from a
  handler instead of inline.
- `CoreBankingClient.validate()` — stays synchronous, still runs before
  `persistCreated`, still the thing that can reject a submission
  immediately (`422`) before any `Transfer` row or message exists.

## 4. Explicitly out of scope (YAGNI, flagged so it isn't silently assumed)

- Any real broker (Kafka/SQS/RabbitMQ) — both `InMemoryMessageBroker`s
  are single-JVM stubs; the seam is what makes swapping one in later a
  contained change, not something built now.
- Cross-process delivery for the submission command — it's dispatched
  and consumed within `banking-service`'s own JVM; nothing here changes
  how `banking-service` talks to `approval-engine` over the wire (still
  plain HTTP, same as today, just called from a virtual thread instead
  of the request thread).
- Surfacing `FAILED` transfers to the maker proactively (a notification,
  a dashboard alert) — out of scope; `GET /transfers/{id}` already
  reflects the state, that's the extent of it for now.
- Configurable retry count/backoff — hardcoded constants
  (`MAX_ATTEMPTS = 3`, `1s/2s/4s`), not externalized to `application.yml`;
  trivial to add later, not needed to prove the pattern.
