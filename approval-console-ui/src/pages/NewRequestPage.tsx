import { useState } from 'react';
import { Box, Typography, Paper, TextField, Button, Alert, Stack } from '@mui/material';
import { useNavigate, Link } from 'react-router-dom';
import { useActor } from '../state/ActorContext';
import { transfersApi } from '../api/client';

export function NewRequestPage() {
  const { actor } = useActor();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const [fromAccount, setFromAccount] = useState('ACC-FUNDED');
  const [toAccount, setToAccount] = useState('ACC-DEST');
  const [amount, setAmount] = useState('10000');
  const [currency, setCurrency] = useState('AED');

  async function submitTransfer() {
    setSubmitting(true);
    setError(null);
    try {
      const amountMinorUnits = Math.round(parseFloat(amount) * 100);
      const { transferId } = await transfersApi.submit({
        makerId: actor.id, fromAccount, toAccount, amountMinorUnits, currency,
      });
      navigate(`/requests/${transferId}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box sx={{ maxWidth: 560, mx: 'auto', p: 3 }}>
      <Typography component={Link} to="/my-account" sx={{ fontSize: 12.5, color: 'secondary.dark', display: 'block', mb: 2, textDecoration: 'none' }}>
        &larr; My Account
      </Typography>
      <Typography variant="h1" sx={{ mb: 2 }}>New Transfer Request</Typography>
      <Paper sx={{ boxShadow: '0 1px 3px rgba(0,0,0,0.08)', p: 3 }}>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Stack spacing={2}>
          <TextField label="From Account" value={fromAccount} onChange={(e) => setFromAccount(e.target.value)} size="small" />
          <TextField label="To Account" value={toAccount} onChange={(e) => setToAccount(e.target.value)} size="small" />
          <TextField label="Amount" type="number" value={amount} onChange={(e) => setAmount(e.target.value)} size="small" />
          <TextField label="Currency" value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} size="small" sx={{ width: 120 }} />
          <Typography variant="body2" color="text.secondary">
            No workflow picker here — the policy resolver assigns the review path from the amount.
          </Typography>
          <Button variant="contained" color="secondary" onClick={submitTransfer} disabled={submitting} sx={{ alignSelf: 'flex-start' }}>
            Submit Transfer
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}
