import { useEffect, useState } from 'react';
import {
  Box, Typography, Paper, Table, TableHead, TableBody, TableRow, TableCell, Tabs, Tab, CircularProgress,
} from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { useActor } from '../state/ActorContext';
import { actorLabel } from '../state/actors';
import { approvalsApi, transfersApi } from '../api/client';
import type { ApprovalSummary } from '../api/types';
import { StatusChip } from '../components/StatusChip';
import { formatMoney } from '../utils/format';

// Two tabs, not three: "needs my action" is the only thing scoped to the
// current role (eligibleRoles reflects the CURRENT stage only, so once a
// request is terminal there's no way to tell after the fact who could have
// acted on it -- History is intentionally unscoped, showing everyone's).
type Filter = 'needs-action' | 'history';

interface Row extends ApprovalSummary {
  amountLabel: string;
}

export function ApprovalWorkspacePage() {
  const { actor } = useActor();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<Filter>('needs-action');
  const [rows, setRows] = useState<Row[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    setRows(null);
    // mine=true does the role-scoping server-side now (approval-engine's own
    // eligibleRoles filter) -- no client-side .filter() by role anymore.
    approvalsApi.list(filter === 'history' ? 'completed' : 'pending', filter === 'needs-action').then(async (data) => {
      // Amount isn't part of the approval summary (approval-engine doesn't
      // know about banking-domain fields) -- one bulk lookup, for every row,
      // resolves it in one round trip. Don't pre-filter by workflow name:
      // policy config can route a transfer's amount to ANY workflow, so
      // whether a row actually has a Transfer record is something only
      // banking-service can answer, not something to guess from the name.
      const transfers = await transfersApi.getMany(data.map((r) => r.requestId));
      const amountById = new Map(transfers.map((t) => [t.transferId, formatMoney(t.amountMinorUnits, t.currency)]));
      const withAmounts = data.map((r) => ({ ...r, amountLabel: amountById.get(r.requestId) ?? '—' }));
      if (!cancelled) setRows(withAmounts);
    });
    return () => { cancelled = true; };
  }, [filter, actor.role]);

  return (
    <Box sx={{ maxWidth: 1100, mx: 'auto', p: 3 }}>
      <Typography variant="h1" sx={{ mb: 0.5 }}>Approval Workspace</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Acting as <strong>{actor.name}</strong> &middot; {actor.role}
      </Typography>
      <Paper sx={{ boxShadow: '0 1px 3px rgba(0,0,0,0.08)' }}>
        <Tabs value={filter} onChange={(_, v) => setFilter(v)} sx={{ borderBottom: 1, borderColor: 'divider', px: 2 }}>
          <Tab value="needs-action" label="Needs My Action" />
          <Tab value="history" label="History" />
        </Tabs>
        {rows === null ? (
          <Box sx={{ p: 5, textAlign: 'center' }}><CircularProgress size={28} /></Box>
        ) : rows.length === 0 ? (
          <Box sx={{ p: 5, textAlign: 'center', color: 'text.secondary' }}>
            <Typography>
              {filter === 'needs-action' ? `Nothing for ${actor.role} to act on right now.` : 'No completed requests yet.'}
            </Typography>
          </Box>
        ) : (
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Request</TableCell>
                <TableCell>Amount</TableCell>
                <TableCell>Maker</TableCell>
                <TableCell>Workflow</TableCell>
                <TableCell>Current Stage</TableCell>
                {filter === 'needs-action' && <TableCell>Progress</TableCell>}
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((r) => (
                <TableRow
                  key={r.requestId}
                  hover
                  onClick={() => navigate(`/requests/${r.requestId}`)}
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>{r.requestId.slice(0, 8)}</TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>{r.amountLabel}</TableCell>
                  <TableCell>{actorLabel(r.makerId)}</TableCell>
                  <TableCell>{r.workflowId}:{r.workflowVersion}</TableCell>
                  <TableCell>{r.currentStageLabel}</TableCell>
                  {filter === 'needs-action' && (
                    <TableCell>{r.requiredApprovals != null ? `${r.currentApprovals} / ${r.requiredApprovals}` : '—'}</TableCell>
                  )}
                  <TableCell><StatusChip label={r.currentState} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Paper>
    </Box>
  );
}
