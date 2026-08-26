import { useEffect, useState } from 'react';
import { Box, Table, TableHead, TableBody, TableRow, TableCell, CircularProgress } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { workflowsApi } from '../api/client';
import type { WorkflowSummary } from '../api/types';
import { StatusChip } from '../components/StatusChip';

export function WorkflowCatalogTable() {
  const [rows, setRows] = useState<WorkflowSummary[] | null>(null);
  const navigate = useNavigate();

  useEffect(() => { workflowsApi.list().then(setRows); }, []);

  if (rows === null) return <Box sx={{ p: 5, textAlign: 'center' }}><CircularProgress size={28} /></Box>;

  return (
    <Table>
      <TableHead>
        <TableRow>
          <TableCell>Workflow</TableCell>
          <TableCell>Version</TableCell>
          <TableCell>States</TableCell>
          <TableCell>Status</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((w) => (
          <TableRow
            key={`${w.workflowId}:${w.version}`}
            hover
            onClick={() => navigate(`/catalog/${w.workflowId}/${w.version}`)}
            sx={{ cursor: 'pointer' }}
          >
            <TableCell>{w.workflowId}</TableCell>
            <TableCell>v{w.version}</TableCell>
            <TableCell>{w.stateCount}</TableCell>
            <TableCell><StatusChip label="Active" /></TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
