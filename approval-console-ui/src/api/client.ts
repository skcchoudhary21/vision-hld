import type {
  ApprovalSummary, AuditEntry, PolicyRule, TransferDetail, WorkflowDefinition, WorkflowSummary, WorkflowView,
} from './types';
import { currentActor } from '../state/actors';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // Every request identifies who's asking -- the backend requires these on
  // /ui-api/** and /transfers/** (ActorHeaderInterceptor, banking-service).
  // Read directly from storage rather than via React context: this module
  // has no component tree to pull a hook from.
  const actor = currentActor();
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Actor-Id': actor.id,
      'X-Actor-Role': actor.role,
      ...init?.headers,
    },
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`${init?.method ?? 'GET'} ${path} -> ${res.status}${body ? `: ${body}` : ''}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export interface SubmitTransferInput {
  makerId: string;
  fromAccount: string;
  toAccount: string;
  amountMinorUnits: number;
  currency: string;
}

export const transfersApi = {
  submit: (input: SubmitTransferInput) =>
    request<{ transferId: string; state: string }>('/transfers', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(input),
    }),
  get: (transferId: string) => request<TransferDetail>(`/transfers/${transferId}`),
  listByMaker: (makerId: string) =>
    request<TransferDetail[]>(`/transfers?makerId=${encodeURIComponent(makerId)}`),
  // One bulk round trip instead of one call per row -- for resolving amounts
  // on a list of otherwise-unrelated requests (e.g. the Approval Workspace).
  getMany: (transferIds: string[]) =>
    transferIds.length === 0
      ? Promise.resolve<TransferDetail[]>([])
      : request<TransferDetail[]>(`/transfers?ids=${transferIds.map(encodeURIComponent).join(',')}`),
};

export const approvalsApi = {
  // mine=true scopes to the current actor's role server-side (approval-engine's
  // own eligibleRoles filter) -- the browser no longer downloads every request
  // in the system just to throw most of it away locally.
  list: (status: 'all' | 'pending' | 'completed' = 'all', mine = false) =>
    request<ApprovalSummary[]>(`/ui-api/approvals?status=${status}&mine=${mine}`),
  workflowView: (requestId: string) =>
    request<WorkflowView>(`/ui-api/approvals/${requestId}/workflow-view`),
  audit: (requestId: string) => request<AuditEntry[]>(`/ui-api/approvals/${requestId}/audit`),
  decide: (requestId: string, action: 'approve' | 'reject' | 'cancel', actorId: string, actorRole: string) =>
    request<{ requestId: string; state: string; version: number }>(
      `/ui-api/approvals/${requestId}/${action}`,
      { method: 'POST', body: JSON.stringify({ actorId, actorRole }) },
    ),
};

export const workflowsApi = {
  list: () => request<WorkflowSummary[]>('/ui-api/workflows'),
  get: (workflowId: string, version: number) =>
    request<WorkflowDefinition>(`/ui-api/workflows/${workflowId}/${version}`),
};

export const policyApi = {
  list: () => request<PolicyRule[]>('/ui-api/policy-rules'),
  replaceAll: (rules: PolicyRule[]) =>
    request<PolicyRule[]>('/ui-api/policy-rules', { method: 'PUT', body: JSON.stringify(rules) }),
};
