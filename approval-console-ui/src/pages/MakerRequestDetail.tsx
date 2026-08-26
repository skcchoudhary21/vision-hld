import { Box, Typography, Paper, Divider, Button, Stack, TextField } from '@mui/material';
import { actorLabel } from '../state/actors';
import type { Actor } from '../state/actors';
import type { AuditEntry, AvailableAction, TransferDetail } from '../api/types';
import { StatusChip } from '../components/StatusChip';
import { formatMoney, formatDateTime } from '../utils/format';

interface Props {
  transfer: TransferDetail | null;
  audit: AuditEntry[];
  actor: Actor;
  myActions: AvailableAction[];
  acting: string | null;
  onAct: (name: 'approve' | 'reject' | 'cancel') => void;
}

// The maker's status is core banking's, full stop -- their transfer is either
// still moving through the pipeline or it has (RELEASED) / hasn't (REJECTED/
// CANCELLED/EXPIRED) actually moved money. Approval-engine's own state
// ("APPROVED") is a decision, not a settlement, and isn't shown here at all.
export function MakerRequestDetail({ transfer, audit, actor, myActions, acting, onAct }: Props) {
  const NON_TERMINAL = new Set(['CREATED', 'PENDING_APPROVAL', 'RELEASE_PENDING']);
  const status = transfer?.state ?? 'PENDING_APPROVAL';
  const isTerminal = transfer ? !NON_TERMINAL.has(transfer.state) : false;

  return (
    <Paper sx={{ p: 3, boxShadow: '0 1px 3px rgba(0,0,0,0.08)' }}>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        {transfer ? (
          <Box>
            <Typography sx={{ fontSize: 26, fontWeight: 700 }}>
              {formatMoney(transfer.amountMinorUnits, transfer.currency)}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {transfer.fromAccount} &rarr; {transfer.toAccount}
            </Typography>
          </Box>
        ) : (
          <Typography sx={{ fontSize: 20, fontWeight: 700 }}>Privileged Access Request</Typography>
        )}
        <StatusChip label={status} />
      </Box>

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

      {!isTerminal && myActions.length > 0 && (
        <>
          <Divider sx={{ my: 2.5 }} />
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <TextField label="Acting as" value={`${actor.name} (${actor.role})`} size="small" disabled sx={{ minWidth: 220 }} />
            {myActions.map((a) => (
              <Button
                key={a.name}
                variant="contained"
                color="inherit"
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
    </Paper>
  );
}
