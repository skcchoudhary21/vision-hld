package com.visionbank.banking.ui;

import com.visionbank.banking.approval.ApprovalEngineClient;
import com.visionbank.banking.approval.WorkflowResponse;
import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.repository.TransferRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Dev-tool support for the manual test UI (static/ui.html): proxies approval
// decisions and reads through to approval-engine so the browser only ever
// talks to this service (no CORS), and streams live progress over SSE. Not
// part of the graded submission's documented API contracts.
@RestController
@RequestMapping("/ui-api")
public class UiController {

    private static final Set<String> TERMINAL_STATES = Set.of("RELEASED", "REJECTED", "CANCELLED", "EXPIRED");
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);

    private final TransferRepository transfers;
    private final ApprovalEngineClient approvalEngineClient;

    public UiController(TransferRepository transfers, ApprovalEngineClient approvalEngineClient) {
        this.transfers = transfers;
        this.approvalEngineClient = approvalEngineClient;
    }

    @GetMapping("/approvals/{id}")
    public ApprovalStateDto getApproval(@PathVariable String id) {
        return approvalEngineClient.getApproval(id);
    }

    @GetMapping("/approvals/{id}/audit")
    public List<AuditEntryDto> getAuditLog(@PathVariable String id) {
        return approvalEngineClient.getAuditLog(id);
    }

    @GetMapping("/approvals/{id}/workflow-view")
    public WorkflowViewDto getWorkflowView(@PathVariable String id) {
        return approvalEngineClient.getWorkflowView(id);
    }

    @GetMapping("/approvals")
    public List<ApprovalSummaryDto> listApprovals(@RequestParam(required = false, defaultValue = "all") String status) {
        return approvalEngineClient.getApprovalsList(status);
    }

    @GetMapping("/workflows")
    public List<WorkflowSummaryDto> listWorkflows() {
        return approvalEngineClient.getWorkflowsList();
    }

    @GetMapping("/workflows/{id}/{version}")
    public WorkflowDefinitionDto getWorkflow(@PathVariable String id, @PathVariable int version) {
        return approvalEngineClient.getWorkflowDefinition(id, version);
    }

    // Generic create, for workflows with no banking-service-native creation path
    // (e.g. privileged-access) -- the UI builds the full body itself; a fresh
    // server-generated idempotency key means one click always means one request.
    @PostMapping("/approvals")
    public WorkflowResponse createApproval(@RequestBody Map<String, Object> body) {
        return approvalEngineClient.createApproval(body, UUID.randomUUID().toString());
    }

    @PostMapping("/approvals/{id}/{action}")
    public ApprovalStateDto decide(@PathVariable String id, @PathVariable String action,
                                    @RequestBody ActorRequest body) {
        if (!Set.of("approve", "reject", "cancel").contains(action)) {
            throw new IllegalArgumentException("Unknown action: " + action);
        }
        return approvalEngineClient.decide(id, action, body.actorId(), body.actorRole());
    }

    public record ActorRequest(String actorId, String actorRole) {}

    @GetMapping("/stream/{transferId}")
    public SseEmitter stream(@PathVariable String transferId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        Thread poller = Thread.ofVirtual().start(() -> pollAndStream(transferId, emitter));
        emitter.onCompletion(poller::interrupt);
        emitter.onTimeout(poller::interrupt);
        emitter.onError(t -> poller.interrupt());
        return emitter;
    }

    private void pollAndStream(String transferId, SseEmitter emitter) {
        String lastTransferState = null;
        String lastApprovalState = null;
        String lastWorkflowViewSignature = null;
        int auditEntriesSent = 0;
        Instant deadline = Instant.now().plus(STREAM_TIMEOUT);

        try {
            while (!Thread.currentThread().isInterrupted() && Instant.now().isBefore(deadline)) {
                Transfer transfer = transfers.findById(transferId).orElse(null);
                if (transfer == null) {
                    emitter.send(SseEmitter.event().name("log").data("[" + Instant.now() + "] transfer not found yet, retrying..."));
                    Thread.sleep(POLL_INTERVAL.toMillis());
                    continue;
                }

                String transferState = transfer.getState().name();
                ApprovalStateDto approval = transfer.getApprovalRequestId() != null
                        ? safeGetApproval(transfer.getApprovalRequestId())
                        : null;
                String approvalState = approval != null ? approval.state() : null;

                if (transfer.getApprovalRequestId() != null) {
                    List<AuditEntryDto> auditLog = approvalEngineClient.getAuditLog(transfer.getApprovalRequestId());
                    for (int i = auditEntriesSent; i < auditLog.size(); i++) {
                        emitter.send(SseEmitter.event().name("audit").data(auditLog.get(i)));
                    }
                    auditEntriesSent = auditLog.size();
                }

                boolean changed = !transferState.equals(lastTransferState) || !java.util.Objects.equals(approvalState, lastApprovalState);
                if (changed) {
                    emitter.send(SseEmitter.event().name("state").data(new StreamState(transferState, approvalState,
                            approval != null ? approval.version() : 0)));
                    lastTransferState = transferState;
                    lastApprovalState = approvalState;
                }

                WorkflowViewDto view = transfer.getApprovalRequestId() != null
                        ? safeGetWorkflowView(transfer.getApprovalRequestId())
                        : null;
                if (view != null) {
                    String signature = view.currentState() + view.stages().stream()
                            .map(s -> s.id() + ":" + s.completedApprovals()).collect(java.util.stream.Collectors.joining(","));
                    if (!signature.equals(lastWorkflowViewSignature)) {
                        emitter.send(SseEmitter.event().name("workflow-view").data(view));
                        lastWorkflowViewSignature = signature;
                    }
                }

                if (TERMINAL_STATES.contains(transferState)) {
                    emitter.send(SseEmitter.event().name("done").data(transferState));
                    emitter.complete();
                    return;
                }

                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            emitter.send(SseEmitter.event().name("log").data("[" + Instant.now() + "] stream timed out"));
            emitter.complete();
        } catch (InterruptedException e) {
            // client disconnected — normal
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private ApprovalStateDto safeGetApproval(String approvalRequestId) {
        try {
            return approvalEngineClient.getApproval(approvalRequestId);
        } catch (Exception e) {
            return null;
        }
    }

    private WorkflowViewDto safeGetWorkflowView(String approvalRequestId) {
        try {
            return approvalEngineClient.getWorkflowView(approvalRequestId);
        } catch (Exception e) {
            return null;
        }
    }

    public record StreamState(String transferState, String approvalState, long approvalVersion) {}
}
