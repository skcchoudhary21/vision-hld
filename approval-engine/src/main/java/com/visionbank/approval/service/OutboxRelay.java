package com.visionbank.approval.service;

import com.visionbank.approval.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxClaimService claimService;
    private final RestClient restClient;
    private final String webhookUrl;

    public OutboxRelay(OutboxClaimService claimService,
                        @Value("${transfer-service.webhook-url}") String webhookUrl) {
        this.claimService = claimService;
        this.webhookUrl = webhookUrl;
        this.restClient = RestClient.create();
    }

    @Scheduled(fixedDelay = 2000)
    public int relayOnce() {
        List<OutboxEvent> claimed = claimService.claimBatch();
        int published = 0;
        for (OutboxEvent event : claimed) {
            if (publish(event)) {
                claimService.markPublished(event.getEventId());
                published++;
            }
            // On failure, claimedAt stays set — it becomes reclaimable once
            // it's older than the claim service's stale-claim window, so a
            // crash mid-publish doesn't strand the event forever.
        }
        return published;
    }

    private boolean publish(OutboxEvent event) {
        try {
            HttpStatusCode status = restClient.post()
                    .uri(webhookUrl)
                    .header("X-Event-Id", event.getEventId())
                    .header("X-Event-Type", event.getEventType())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event.getPayload())
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return status.is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Failed to relay event {} ({}): {}", event.getEventId(), event.getEventType(), e.getMessage());
            return false;
        }
    }
}
