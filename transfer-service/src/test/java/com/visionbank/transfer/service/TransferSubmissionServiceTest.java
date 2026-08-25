package com.visionbank.transfer.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class TransferSubmissionServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer engineStub = new WireMockServer(9092);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("approval-engine.base-url", () -> "http://localhost:9092");
    }

    @Autowired TransferSubmissionService service;
    @Autowired TransferPersistenceService persistenceService;
    @Autowired TransferRepository transfers;

    @BeforeEach
    void startStub() {
        engineStub.start();
    }

    @AfterEach
    void stopStub() {
        engineStub.resetAll();
        engineStub.stop();
    }

    private SubmitTransferCommand smallTransfer() {
        return new SubmitTransferCommand("maker-1", "ACC-FUNDED", "ACC-DEST", 1000_00L, "AED");
    }

    @Test
    void engineReturningApprovedStillLeavesTransferWaitingForApproval() {
        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"whatever\",\"state\":\"APPROVED\",\"version\":1}")));

        TransferView view = service.submit(smallTransfer(), UUID.randomUUID().toString());

        assertThat(transfers.findById(view.transferId()).get().getState()).isEqualTo(TransferState.WAITING_FOR_APPROVAL);
    }

    @Test
    void engineReturningPendingApprovalLeavesTransferWaitingForApproval() {
        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"whatever\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));

        TransferView view = service.submit(smallTransfer(), UUID.randomUUID().toString());

        assertThat(transfers.findById(view.transferId()).get().getState()).isEqualTo(TransferState.WAITING_FOR_APPROVAL);
    }

    @Test
    void insufficientBalanceFailsValidationBeforeCallingEngine() {
        SubmitTransferCommand huge = new SubmitTransferCommand("maker-1", "ACC-FUNDED", "ACC-DEST", 999_999_999_00L, "AED");

        assertThatThrownBy(() -> service.submit(huge, UUID.randomUUID().toString()))
                .isInstanceOf(ValidationFailedException.class);

        engineStub.verify(0, postRequestedFor(urlEqualTo("/approvals")));
    }

    @Test
    void replayingSameIdempotencyKeyReturnsSameTransferWithoutSecondEngineCall() {
        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"whatever\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));
        String key = UUID.randomUUID().toString();

        TransferView first = service.submit(smallTransfer(), key);
        TransferView second = service.submit(smallTransfer(), key);

        assertThat(second.transferId()).isEqualTo(first.transferId());
        engineStub.verify(1, postRequestedFor(urlEqualTo("/approvals")));
    }

    @Test
    void resumingAfterCrashReusesThePersistedTransferIdAndExpiresAtWithoutReValidating() {
        // Simulates a crash after persistCreated() committed but before the
        // engine call/markWaitingForApproval completed: pre-create the CREATED
        // row directly via the persistence service, with a fixed expiresAt.
        Instant fixedExpiresAt = Instant.parse("2030-01-01T00:00:00Z");
        Transfer created = persistenceService.persistCreated("resume-1", smallTransfer(), "resume-key", fixedExpiresAt);
        assertThat(created.getState()).isEqualTo(TransferState.CREATED);

        engineStub.stubFor(post(urlEqualTo("/approvals"))
                .willReturn(okJson("{\"requestId\":\"resume-1\",\"state\":\"PENDING_APPROVAL\",\"version\":1}")));

        TransferView view = service.submit(smallTransfer(), "resume-key");

        assertThat(view.transferId()).isEqualTo("resume-1");
        assertThat(transfers.findById("resume-1").get().getState()).isEqualTo(TransferState.WAITING_FOR_APPROVAL);
        engineStub.verify(1, postRequestedFor(urlEqualTo("/approvals"))
                .withRequestBody(containing("2030-01-01T00:00:00Z")));
    }
}
