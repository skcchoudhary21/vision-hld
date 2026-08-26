package com.visionbank.banking.ui;

import java.util.List;

public record WorkflowDefinitionDto(String workflowId, int version, String initialState,
                                     List<String> terminalStates, List<WorkflowStateDto> states,
                                     List<WorkflowTransitionDto> transitions) {}
