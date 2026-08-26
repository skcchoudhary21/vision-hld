package com.visionbank.approval.web;

import com.visionbank.approval.domain.ApprovalDecision;
import com.visionbank.approval.domain.ApprovalRequest;
import com.visionbank.approval.domain.AuditLog;
import com.visionbank.approval.repository.ApprovalDecisionRepository;
import com.visionbank.approval.repository.ApprovalRequestRepository;
import com.visionbank.approval.repository.AuditLogRepository;
import com.visionbank.approval.service.*;
import com.visionbank.approval.web.dto.*;
import com.visionbank.approval.workflow.Transition;
import com.visionbank.approval.workflow.WorkflowDefinition;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalCommandService service;
    private final ApprovalRequestRepository requests;
    private final AuditLogRepository audits;
    private final ApprovalDecisionRepository decisions;

    public ApprovalController(ApprovalCommandService service, ApprovalRequestRepository requests,
                               AuditLogRepository audits, ApprovalDecisionRepository decisions) {
        this.service = service;
        this.requests = requests;
        this.audits = audits;
        this.decisions = decisions;
    }

    @PostMapping
    public ApprovalResponseDto create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody CreateApprovalRequestDto dto) {
        CreateApprovalRequest cmd = new CreateApprovalRequest(dto.requestId(), dto.requestType(), dto.makerId(),
                dto.workflowId(), dto.workflowVersion(), dto.policyVersion(), dto.payloadJson(), dto.expiresAt());
        ApprovalRequestView view = service.create(cmd, idempotencyKey);
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/approve")
    public ApprovalResponseDto approve(@PathVariable String id, @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.approve(id, dto.actorId(), dto.actorRole());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/reject")
    public ApprovalResponseDto reject(@PathVariable String id, @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.reject(id, dto.actorId(), dto.actorRole());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @PostMapping("/{id}/cancel")
    public ApprovalResponseDto cancel(@PathVariable String id, @Valid @RequestBody ActorCommandDto dto) {
        ApprovalRequestView view = service.cancel(id, dto.actorId());
        return new ApprovalResponseDto(view.requestId(), view.state(), view.version());
    }

    @GetMapping("/{id}")
    public ApprovalResponseDto get(@PathVariable String id) {
        var request = requests.findByRequestId(id)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        return new ApprovalResponseDto(request.getRequestId(), request.getState(), request.getVersion());
    }

    @GetMapping("/{id}/audit")
    public List<AuditLogEntryDto> audit(@PathVariable String id) {
        return audits.findByRequestIdOrderByCreatedAtAsc(id).stream()
                .map(a -> new AuditLogEntryDto(a.getAction(), a.getPreviousState(), a.getNewState(),
                        a.getActorId(), a.getActorRole(), a.getCreatedAt()))
                .toList();
    }

    @GetMapping("/{id}/workflow-view")
    public WorkflowViewDto workflowView(@PathVariable String id) {
        ApprovalRequest request = requests.findByRequestId(id)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        WorkflowDefinition workflow = request.getPolicySnapshot().workflow();
        String currentState = request.getState();
        List<AuditLog> auditLog = audits.findByRequestIdOrderByCreatedAtAsc(id);

        List<StageViewDto> stages = workflow.states().stream()
                .map(s -> buildStageView(workflow, request, s, currentState, auditLog))
                .toList();

        List<AvailableActionDto> availableActions = workflow.transitionsFrom(currentState).stream()
                .map(t -> new AvailableActionDto(t.name(), t.allowedRoles(), t.requiredApprovals(),
                        t.requiredApprovals() == null ? null
                                : (int) decisions.countByRequestIdAndDecisionAndState(
                                        request.getRequestId(), ApprovalDecision.DecisionType.APPROVE, currentState)))
                .toList();

        return new WorkflowViewDto(workflow.name(), workflow.version(), currentState,
                new java.util.ArrayList<>(workflow.terminalStates()), stages, availableActions);
    }

    private StageViewDto buildStageView(WorkflowDefinition workflow, ApprovalRequest request,
                                         WorkflowDefinition.StateDef stateDef, String currentState,
                                         List<AuditLog> auditLog) {
        String id = stateDef.id();
        String status;
        if (id.equals(currentState) && workflow.isTerminal(id)) {
            status = isSuccessTerminal(workflow, id) ? "COMPLETED" : "FAILED";
        } else if (id.equals(currentState)) {
            status = "IN_PROGRESS";
        } else {
            status = hasEverReached(auditLog, id) ? "COMPLETED" : "PENDING";
        }

        Transition approveFromHere = workflow.transitionsFrom(id).stream()
                .filter(t -> t.name().equals("approve") && t.requiredApprovals() != null)
                .findFirst()
                .orElse(null);
        if (approveFromHere == null) {
            return new StageViewDto(id, stateDef.label(), status, null, null, List.of());
        }

        List<DecisionViewDto> approvals = decisions.findByRequestIdAndState(request.getRequestId(), id).stream()
                .map(d -> new DecisionViewDto(d.getActorId(), d.getActorRole(), d.getDecision().name(), d.getCreatedAt()))
                .toList();
        long completed = approvals.stream().filter(a -> a.decision().equals("APPROVE")).count();

        return new StageViewDto(id, stateDef.label(), status, approveFromHere.requiredApprovals(), (int) completed, approvals);
    }

    private boolean hasEverReached(List<AuditLog> auditLog, String stateId) {
        return auditLog.stream().anyMatch(a -> a.getNewState().equals(stateId) || a.getPreviousState().equals(stateId));
    }

    private boolean isSuccessTerminal(WorkflowDefinition workflow, String state) {
        return workflow.transitions().stream()
                .anyMatch(t -> t.to().equals(state) && t.name().toLowerCase().contains("approve"));
    }
}
