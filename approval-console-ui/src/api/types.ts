export type TransferState =
  | 'CREATED' | 'PENDING_APPROVAL' | 'RELEASE_PENDING' | 'RELEASED'
  | 'REJECTED' | 'CANCELLED' | 'EXPIRED' | 'FAILED';

export interface TransferDetail {
  transferId: string;
  makerId: string;
  fromAccount: string;
  toAccount: string;
  amountMinorUnits: number;
  currency: string;
  state: TransferState;
  approvalRequestId: string | null;
  createdAt: string;
}

export interface ApprovalSummary {
  requestId: string;
  makerId: string;
  workflowId: string;
  workflowVersion: number;
  currentState: string;
  currentStageLabel: string;
  terminal: boolean;
  requiredApprovals: number | null;
  currentApprovals: number | null;
  eligibleRoles: string[];
  createdAt: string;
}

export interface AuditEntry {
  action: string;
  previousState: string;
  newState: string;
  actorId: string | null;
  actorRole: string | null;
  createdAt: string;
}

export interface DecisionView {
  actorId: string;
  actorRole: string;
  decision: string;
  createdAt: string;
}

export interface StageView {
  id: string;
  label: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
  requiredApprovals: number | null;
  completedApprovals: number | null;
  approvals: DecisionView[];
}

export interface AvailableAction {
  name: string;
  allowedRoles: string[];
  requiredApprovals: number | null;
  currentApprovals: number | null;
}

export interface WorkflowView {
  workflowId: string;
  workflowVersion: number;
  currentState: string;
  terminalStates: string[];
  stages: StageView[];
  availableActions: AvailableAction[];
}

export interface WorkflowSummary {
  workflowId: string;
  version: number;
  stateCount: number;
}

export interface WorkflowStateDef {
  id: string;
  label: string;
}

export interface WorkflowTransitionDef {
  name: string;
  from: string;
  to: string;
  guards: string[];
  allowedRoles: string[];
  requiredApprovals: number | null;
}

export interface WorkflowDefinition {
  workflowId: string;
  version: number;
  initialState: string;
  terminalStates: string[];
  states: WorkflowStateDef[];
  transitions: WorkflowTransitionDef[];
}

export interface PolicyRule {
  id: number | null;
  minAmountMinorUnits: number;
  maxAmountMinorUnits: number | null;
  workflowId: string;
  workflowVersion: number;
}
