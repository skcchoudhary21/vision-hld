export interface Actor {
  id: string;
  name: string;
  role: string;
  accountNumber?: string;
  /**
   * Demo-only opening balance -- nothing in the backend tracks a real ledger.
   * The displayed balance is this minus every RELEASED transfer's amount
   * (computed from real transfer data), not a frozen number.
   */
  openingBalanceMinorUnits?: number;
}

export const MAKER_ROLE = 'MAKER';

// Shared with client.ts, which reads this directly (no React context there)
// to attach X-Actor-Id/X-Actor-Role headers to every API call.
export const ACTOR_STORAGE_KEY = 'approval-console.actorId';

export function isMaker(role: string): boolean {
  return role === MAKER_ROLE;
}

export function actorLabel(actorId: string): string {
  return ACTORS.find((a) => a.id === actorId)?.name ?? actorId;
}

export function currentActor(): Actor {
  const actorId = localStorage.getItem(ACTOR_STORAGE_KEY) ?? ACTORS[0].id;
  return ACTORS.find((a) => a.id === actorId) ?? ACTORS[0];
}

// Demo identity list only -- there is no real user/IAM backend behind this.
// `id` doubles as the makerId used when submitting a transfer, so "My
// Account" can filter the real backend data by the selected actor.
export const ACTORS: Actor[] = [
  { id: 'maker-1', name: 'Suresh', role: 'MAKER', accountNumber: 'ACC-FUNDED', openingBalanceMinorUnits: 1_250_000_00 },
  { id: 'maker-2', name: 'Neha Kapoor', role: 'MAKER', accountNumber: 'ACC-NEHA-01', openingBalanceMinorUnits: 430_500_00 },
  { id: 'checker-1', name: 'Vikram Rao', role: 'TRANSFER_CHECKER' },
  { id: 'checker-2', name: 'Meera Iyer', role: 'TRANSFER_CHECKER' },
  { id: 'security-1', name: 'Aisha Khan', role: 'SECURITY_CHECKER' },
  { id: 'security-2', name: 'Daniel Ford', role: 'SECURITY_CHECKER' },
  { id: 'manager-1', name: 'Amit Verma', role: 'MANAGER_CHECKER' },
  { id: 'compliance-1', name: 'Priya Nair', role: 'COMPLIANCE_CHECKER' },
];
