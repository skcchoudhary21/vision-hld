package com.visionbank.transfer.web;

import com.visionbank.transfer.approval.ApprovalEventListener;
import com.visionbank.transfer.approval.IncomingEvent;
import com.visionbank.transfer.web.dto.IncomingEventDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/events")
public class EventWebhookController {

    private final ApprovalEventListener listener;

    public EventWebhookController(ApprovalEventListener listener) {
        this.listener = listener;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-Event-Id") String eventId,
                                         @RequestHeader("X-Event-Type") String eventType,
                                         @RequestBody IncomingEventDto body) {
        listener.handle(new IncomingEvent(eventId, eventType, body.requestId()));
        return ResponseEntity.ok().build();
    }
}
