package com.visionbank.banking.web;

import com.visionbank.banking.approval.ApprovalEventListener;
import com.visionbank.banking.approval.IncomingEvent;
import com.visionbank.banking.web.dto.IncomingEventDto;
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
