import { useEffect, useState } from 'react';
import { Box, Typography, Button, Paper, Table, TableHead, TableBody, TableRow, TableCell, CircularProgress } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { Link, useNavigate } from 'react-router-dom';
import { useActor } from '../state/ActorContext';
import { transfersApi } from '../api/client';
import type { TransferDetail } from '../api/types';
import { StatusChip } from '../components/StatusChip';
import { formatMoney, formatDateTime } from '../utils/format';

export function MyAccountPage() {
  const { actor } = useActor();
  const navigate = useNavigate();
  const [transfers, setTransfers] = useState<TransferDetail[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    setTransfers(null);
    // A single call: this maker's own transfers, in the shape the account
    // page needs (amount, accounts, state) with no second request to merge in.
    transfersApi.listByMaker(actor.id).then((data) => {
      if (!cancelled) setTransfers(data);
    });
    return () => { cancelled = true; };
  }, [actor.id]);

  // Balance is computed, not fabricated: an opening balance (the only thing
  // that can't come from the backend -- nothing here tracks a real ledger)
  // minus every transfer that actually moved money. Pending/rejected/
  // cancelled transfers never debit.
  const balanceMinorUnits = transfers && actor.openingBalanceMinorUnits != null
    ? actor.openingBalanceMinorUnits - transfers.filter((t) => t.state === 'RELEASED').reduce((sum, t) => sum + t.amountMinorUnits, 0)
    : null;

  return (
    <Box sx={{ maxWidth: 1000, mx: 'auto', p: 3 }}>
      <Paper sx={{ p: 3, mb: 3, boxShadow: '0 1px 3px rgba(0,0,0,0.08)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Box>
          <Typography sx={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.04em', color: 'text.secondary', fontWeight: 700 }}>
            Account
          </Typography>
          <Typography sx={{ fontSize: 15, fontWeight: 700, mt: 0.25 }}>{actor.accountNumber ?? '—'}</Typography>
        </Box>
        <Box sx={{ textAlign: 'right' }}>
          <Typography sx={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.04em', color: 'text.secondary', fontWeight: 700 }}>
            Balance
          </Typography>
          <Typography sx={{ fontSize: 22, fontWeight: 700, mt: 0.25 }}>
            {balanceMinorUnits != null ? formatMoney(balanceMinorUnits, 'AED') : '—'}
          </Typography>
        </Box>
      </Paper>

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h1">Transfer Requests</Typography>
        <Button component={Link} to="/new-request" variant="contained" color="secondary" startIcon={<AddIcon />}>
          New Request
        </Button>
      </Box>
      <Paper sx={{ boxShadow: '0 1px 3px rgba(0,0,0,0.08)' }}>
        {transfers === null ? (
          <Box sx={{ p: 5, textAlign: 'center' }}><CircularProgress size={28} /></Box>
        ) : transfers.length === 0 ? (
          <Box sx={{ p: 5, textAlign: 'center', color: 'text.secondary' }}>
            <Typography>No requests submitted by {actor.name} ({actor.role}) yet.</Typography>
            <Typography variant="body2" sx={{ mt: 0.5 }}>Switch to a maker actor, or submit a new request.</Typography>
          </Box>
        ) : (
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Request</TableCell>
                <TableCell>Details</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Submitted</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {transfers.filter((t) => t.approvalRequestId).map((t) => (
                <TableRow
                  key={t.transferId}
                  hover
                  onClick={() => navigate(`/requests/${t.approvalRequestId}`)}
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>{t.transferId.slice(0, 8)}</TableCell>
                  <TableCell>
                    <Typography sx={{ fontWeight: 600, fontSize: 13.5 }}>{formatMoney(t.amountMinorUnits, t.currency)}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ fontSize: 12 }}>{t.fromAccount} → {t.toAccount}</Typography>
                  </TableCell>
                  <TableCell><StatusChip label={t.state} /></TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: 12 }}>{formatDateTime(t.createdAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Paper>
    </Box>
  );
}
