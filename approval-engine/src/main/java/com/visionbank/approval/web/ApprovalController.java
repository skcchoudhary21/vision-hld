package com.visionbank.approval.web;

import com.visionbank.approval.domain.PolicySnapshot;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.service.*;
import com.visionbank.approval.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalCommandService service;
    private final ApprovalRequestRepository requests;

    public ApprovalController(ApprovalCommandService service, ApprovalRequestRepository requests) {
        this.service = service;
        this.requests = requests;
    }

    @PostMapping
    public ApprovalResponseDto create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody CreateApprovalRequestDto dto) {
        PolicySnapshot policy = new PolicySnapshot("v1", dto.requiredApprovals(), dto.eligibleRoles(), dto.makerCanApprove());
        CreateApprovalRequest cmd = new CreateApprovalRequest(dto.requestId(), dto.requestType(), dto.makerId(),
                policy, dto.payloadJson(), dto.expiresAt());
        ApprovalRequestView view = service.create(cmd, idempotencyKey);
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    // No Idempotency-Key header on approve/reject/cancel — decisions are
    // naturally idempotent per (request_id, actor_id) via Task 3's unique
    // constraint (spec §11). Only create() originates a request and needs
    // client-supplied replay protection.

    @PostMapping("/{id}/approve")
    public ApprovalResponseDto approve(@PathVariable String id,
                                        @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.approve(id, dto.actorId(), dto.actorRole());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/reject")
    public ApprovalResponseDto reject(@PathVariable String id,
                                       @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.reject(id, dto.actorId(), dto.actorRole());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/cancel")
    public ApprovalResponseDto cancel(@PathVariable String id,
                                       @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.cancel(id, dto.actorId());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    // Not in the brief's Step 3 listing, but the brief's own "Produces" line
    // names GET /approvals/{id} as part of the public contract Transfer
    // Service's ApprovalEngineClient (Task 15) is written against, and this
    // is approval-engine's last task — no later task adds it. Implemented
    // directly against the repository since ApprovalCommandService has no
    // read method; 404 mapping reuses the existing ApprovalRequestNotFoundException.
    @GetMapping("/{id}")
    public ApprovalResponseDto get(@PathVariable String id) {
        var request = requests.findByRequestId(id)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        return new ApprovalResponseDto(request.getRequestId(), request.getState(), request.getVersion());
    }
}
