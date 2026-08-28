import { Box, Typography, Paper, Divider, Button, Stack, TextField, Alert } from '@mui/material';
import { actorLabel } from '../state/actors';
import type { Actor } from '../state/actors';
import type { AuditEntry, AvailableAction, TransferDetail, WorkflowView } from '../api/types';
import { StatusChip } from '../components/StatusChip';
import { Pipeline } from '../components/Pipeline';
import { formatMoney, formatDateTime } from '../utils/format';

interface Props {
  view: WorkflowView;
  audit: AuditEntry[];
  transfer: TransferDetail | null;
  actor: Actor;
  myActions: AvailableAction[];
  humanActionNames: Set<string>;
  acting: string | null;
  onAct: (name: 'approve' | 'reject' | 'cancel') => void;
}

// The checker's status is approval-engine's, full stop -- their job is done
// the moment a decision is reached (APPROVED/REJECTED/etc.), regardless of
// whether core banking has released the money yet. Unlike the maker's view,
// this never reads from the Transfer record for its headline status.
export function CheckerRequestDetail({ view, audit, transfer, actor, myActions, humanActionNames, acting, onAct }: Props) {
  const activeStage = view.stages.find((s) => s.status === 'IN_PROGRESS');
  const isTerminal = view.terminalStates.includes(view.currentState);
  // approval_decision's UNIQUE(request_id, actor_id, state) permits exactly one decision
  // per actor per stage: a repeat approve() from the same actor is a harmless idempotent
  // replay server-side, but reject() after an existing approve (or vice versa) hits that
  // constraint as a genuine conflict (409 IDEMPOTENCY_CONFLICT). Since activeStage.approvals
  // only ever holds APPROVE decisions -- every reject transition here moves straight to a
  // terminal state, so this stage couldn't still be IN_PROGRESS if this actor had rejected --
  // any entry for this actor means "already decided," full stop; hide both actions rather
  // than let the click round-trip into that error.
  const alreadyApprovedByMe = activeStage?.approvals.some((d) => d.actorId === actor.id) ?? false;
  const visibleActions = alreadyApprovedByMe ? [] : myActions;
  const successTerminal = isTerminal && view.stages.some((s) => s.id === view.currentState && s.status === 'COMPLETED');
  const failTerminal = isTerminal && view.stages.some((s) => s.id === view.currentState && s.status === 'FAILED');

  return (
    <Paper sx={{ p: 3, boxShadow: '0 1px 3px rgba(0,0,0,0.08)' }}>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <Box>
          {transfer ? (
            <>
              <Typography sx={{ fontSize: 26, fontWeight: 700 }}>
                {formatMoney(transfer.amountMinorUnits, transfer.currency)}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {transfer.fromAccount} &rarr; {transfer.toAccount}
              </Typography>
            </>
          ) : (
            <Typography sx={{ fontSize: 20, fontWeight: 700 }}>Privileged Access Request</Typography>
          )}
          <Typography variant="body2" sx={{ mt: 0.5 }}>
            Workflow <Box component="code" sx={{ bgcolor: 'grey.100', px: 0.75, py: 0.25, borderRadius: 0.5 }}>
              {view.workflowId}:{view.workflowVersion}
            </Box>
          </Typography>
        </Box>
        {isTerminal && <StatusChip label={view.currentState} />}
      </Box>

      <Divider sx={{ my: 2.5 }} />
      <Pipeline stages={view.stages} />

      {activeStage && (
        <Paper variant="outlined" sx={{ p: 2, bgcolor: '#eff6ff', borderColor: '#bfdbfe' }}>
          <Typography sx={{ fontWeight: 700, fontSize: 11, textTransform: 'uppercase', color: '#8a94a6', letterSpacing: '.03em' }}>
            Current Stage
          </Typography>
          <Typography sx={{ fontWeight: 700, fontSize: 15, color: '#1d4ed8', mt: 0.25 }}>
            {activeStage.label}
          </Typography>
          {activeStage.requiredApprovals != null && (
            <Typography variant="body2" sx={{ mt: 0.5 }}>
              Waiting for approval &middot; {activeStage.completedApprovals} / {activeStage.requiredApprovals} approvals
            </Typography>
          )}
        </Paper>
      )}
      {successTerminal && (
        <Paper variant="outlined" sx={{ p: 2, bgcolor: '#f0fdf4', borderColor: '#86efac' }}>
          <Typography sx={{ fontWeight: 700, fontSize: 13, textTransform: 'uppercase', color: '#166534' }}>
            {view.currentState} &mdash; complete
          </Typography>
        </Paper>
      )}
      {failTerminal && (
        <Paper variant="outlined" sx={{ p: 2, bgcolor: '#fef2f2', borderColor: '#fecaca' }}>
          <Typography sx={{ fontWeight: 700, fontSize: 13, textTransform: 'uppercase', color: '#991b1b' }}>
            {view.currentState}
          </Typography>
        </Paper>
      )}

      <Divider sx={{ my: 2.5 }} />
      <Typography sx={{ fontSize: 13, textTransform: 'uppercase', color: 'text.secondary', fontWeight: 700, mb: 1.5 }}>
        Approval Timeline
      </Typography>
      <Stack spacing={1.25}>
        {audit.length === 0 && <Typography variant="body2" color="text.secondary">No activity yet.</Typography>}
        {audit.slice().reverse().map((a, i) => (
          <Box key={i} sx={{ borderLeft: '2px solid #d1d5db', pl: 1.5, py: 0.25 }}>
            <Typography variant="body2">
              <strong>{a.action}</strong>: {a.previousState} &rarr; {a.newState}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {formatDateTime(a.createdAt)}{a.actorId ? ` · ${actorLabel(a.actorId)}${a.actorRole ? ` (${a.actorRole})` : ''}` : ''}
            </Typography>
          </Box>
        ))}
      </Stack>

      {!isTerminal && visibleActions.length > 0 && (
        <>
          <Divider sx={{ my: 2.5 }} />
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <TextField label="Acting as" value={`${actor.name} (${actor.role})`} size="small" disabled sx={{ minWidth: 220 }} />
            {visibleActions.map((a) => (
              <Button
                key={a.name}
                variant="contained"
                color={a.name === 'approve' ? 'secondary' : a.name === 'reject' ? 'error' : 'inherit'}
                disabled={acting !== null}
                onClick={() => onAct(a.name as 'approve' | 'reject' | 'cancel')}
                sx={{ textTransform: 'capitalize' }}
              >
                {a.name}
              </Button>
            ))}
          </Stack>
        </>
      )}
      {!isTerminal && visibleActions.length === 0 && alreadyApprovedByMe && activeStage && (
        <>
          <Divider sx={{ my: 2.5 }} />
          <Alert severity="info">
            You ({actor.name}) already approved this stage &mdash; waiting on{' '}
            {(activeStage.requiredApprovals ?? 1) - (activeStage.completedApprovals ?? 0)} more {actor.role} approval(s).
          </Alert>
        </>
      )}
      {!isTerminal && visibleActions.length === 0 && !alreadyApprovedByMe && (
        <>
          <Divider sx={{ my: 2.5 }} />
          <Alert severity="info">
            {actor.name} ({actor.role}) cannot act on this request's current stage
            {activeStage ? ` — eligible role(s): ${[...new Set(view.availableActions.filter((a) => humanActionNames.has(a.name)).flatMap((a) => a.allowedRoles))].join(', ') || 'none'}` : ''}.
          </Alert>
        </>
      )}
    </Paper>
  );
}
