import { useEffect, useState } from 'react';
import {
  Box, Table, TableHead, TableBody, TableRow, TableCell, TextField, MenuItem, Button,
  IconButton, CircularProgress, Snackbar, Alert, Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/Delete';
import { policyApi, workflowsApi } from '../api/client';
import type { WorkflowSummary } from '../api/types';

interface Row {
  clientId: string;
  id: number | null;
  minAmount: string;
  maxAmount: string;
  workflowKey: string;
}

function toWorkflowKey(workflowId: string, workflowVersion: number) {
  return `${workflowId}:${workflowVersion}`;
}

export function ApprovalPoliciesTable() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([]);
  const [saving, setSaving] = useState(false);
  const [snack, setSnack] = useState<{ severity: 'success' | 'error'; message: string } | null>(null);

  function load() {
    setRows(null);
    Promise.all([policyApi.list(), workflowsApi.list()]).then(([rules, wfs]) => {
      setWorkflows(wfs);
      setRows(rules.map((r) => ({
        clientId: crypto.randomUUID(),
        id: r.id,
        minAmount: String(r.minAmountMinorUnits / 100),
        maxAmount: r.maxAmountMinorUnits == null ? '' : String(r.maxAmountMinorUnits / 100),
        workflowKey: toWorkflowKey(r.workflowId, r.workflowVersion),
      })));
    });
  }

  useEffect(load, []);

  function updateRow(clientId: string, patch: Partial<Row>) {
    setRows((prev) => prev && prev.map((r) => (r.clientId === clientId ? { ...r, ...patch } : r)));
  }

  function addRow() {
    if (!rows) return;
    const defaultWorkflow = workflows[0] ? toWorkflowKey(workflows[0].workflowId, workflows[0].version) : '';
    setRows([...rows, { clientId: crypto.randomUUID(), id: null, minAmount: '0', maxAmount: '', workflowKey: defaultWorkflow }]);
  }

  function removeRow(clientId: string) {
    setRows((prev) => prev && prev.filter((r) => r.clientId !== clientId));
  }

  async function save() {
    if (!rows) return;
    setSaving(true);
    try {
      await policyApi.replaceAll(rows.map((r) => {
        const [workflowId, versionStr] = r.workflowKey.split(':');
        return {
          id: r.id,
          minAmountMinorUnits: Math.round(parseFloat(r.minAmount || '0') * 100),
          maxAmountMinorUnits: r.maxAmount.trim() === '' ? null : Math.round(parseFloat(r.maxAmount) * 100),
          workflowId,
          workflowVersion: parseInt(versionStr, 10),
        };
      }));
      setSnack({ severity: 'success', message: 'Approval policy saved.' });
      load();
    } catch (e) {
      setSnack({ severity: 'error', message: e instanceof Error ? e.message : String(e) });
    } finally {
      setSaving(false);
    }
  }

  if (rows === null) return <Box sx={{ p: 5, textAlign: 'center' }}><CircularProgress size={28} /></Box>;

  return (
    <Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Which workflow a transfer routes through, by amount. Ranges are checked top to bottom — the first matching row wins.
      </Typography>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Min Amount</TableCell>
            <TableCell>Max Amount</TableCell>
            <TableCell>Workflow</TableCell>
            <TableCell />
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={r.clientId}>
              <TableCell>
                <TextField
                  size="small" type="number" value={r.minAmount}
                  onChange={(e) => updateRow(r.clientId, { minAmount: e.target.value })}
                  sx={{ width: 140 }}
                />
              </TableCell>
              <TableCell>
                <TextField
                  size="small" type="number" placeholder="No limit" value={r.maxAmount}
                  onChange={(e) => updateRow(r.clientId, { maxAmount: e.target.value })}
                  sx={{ width: 140 }}
                />
              </TableCell>
              <TableCell>
                <TextField
                  select size="small" value={r.workflowKey}
                  onChange={(e) => updateRow(r.clientId, { workflowKey: e.target.value })}
                  sx={{ width: 220 }}
                >
                  {workflows.map((w) => (
                    <MenuItem key={toWorkflowKey(w.workflowId, w.version)} value={toWorkflowKey(w.workflowId, w.version)}>
                      {w.workflowId}:v{w.version}
                    </MenuItem>
                  ))}
                </TextField>
              </TableCell>
              <TableCell>
                <IconButton size="small" onClick={() => removeRow(r.clientId)}><DeleteOutlineIcon fontSize="small" /></IconButton>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 2 }}>
        <Button startIcon={<AddIcon />} onClick={addRow}>Add Rule</Button>
        <Button variant="contained" color="secondary" onClick={save} disabled={saving}>Save Policy</Button>
      </Box>
      <Snackbar open={snack !== null} autoHideDuration={4000} onClose={() => setSnack(null)}>
        {snack ? <Alert severity={snack.severity} onClose={() => setSnack(null)}>{snack.message}</Alert> : undefined}
      </Snackbar>
    </Box>
  );
}
