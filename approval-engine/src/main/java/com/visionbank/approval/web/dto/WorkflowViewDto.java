package com.visionbank.approval.web.dto;

import java.util.List;

public record WorkflowViewDto(String workflowId, int workflowVersion, String currentState,
                               List<String> terminalStates, List<StageViewDto> stages,
                               List<AvailableActionDto> availableActions) {}
