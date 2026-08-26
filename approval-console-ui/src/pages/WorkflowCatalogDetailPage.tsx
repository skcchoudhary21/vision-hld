import { useEffect, useState } from 'react';
import {
  Box, Typography, Paper, Divider, CircularProgress, Chip, Stack,
  Table, TableHead, TableBody, TableRow, TableCell,
} from '@mui/material';
import { Link, useParams } from 'react-router-dom';
import { workflowsApi } from '../api/client';
import type { WorkflowDefinition } from '../api/types';

const INITIAL_COLOR = '#1e40af';
const TERMINAL_COLOR = '#166534';

function StateLabel({ stateId, def }: { stateId: string; def: WorkflowDefinition }) {
  const isInitial = stateId === def.initialState;
  const isTerminal = def.terminalStates.includes(stateId);
  const color = isInitial ? INITIAL_COLOR : isTerminal ? TERMINAL_COLOR : undefined;
  return (
    <Box component="span" sx={{ fontFamily: 'monospace', fontSize: 12, fontWeight: color ? 700 : 400, color }}>
      {stateId}
    </Box>
  );
}

export function WorkflowCatalogDetailPage() {
  const { id = '', version = '' } = useParams();
  const [def, setDef] = useState<WorkflowDefinition | null>(null);

  useEffect(() => { workflowsApi.get(id, Number(version)).then(setDef); }, [id, version]);

  if (!def) return <Box sx={{ p: 6, textAlign: 'center' }}><CircularProgress /></Box>;

  return (
    <Box sx={{ maxWidth: 1040, mx: 'auto', p: 3 }}>
      <Typography component={Link} to="/configuration" sx={{ fontSize: 12.5, color: 'secondary.dark', display: 'block', mb: 2, textDecoration: 'none' }}>
        &larr; Configuration
      </Typography>
      <Paper sx={{ p: 3, boxShadow: '0 1px 3px rgba(0,0,0,0.08)' }}>
        <Typography variant="h1">{def.workflowId}:v{def.version}</Typography>
        <Typography sx={{ my: 2, color: '#374151' }}>
          {def.states.map((s) => s.id).join('  →  ')}
        </Typography>
        <Divider sx={{ my: 2 }} />
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
          <Typography sx={{ fontSize: 13, textTransform: 'uppercase', color: 'text.secondary', fontWeight: 700 }}>
            Transitions
          </Typography>
          <Stack direction="row" spacing={2}>
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
              <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: INITIAL_COLOR }} />
              <Typography variant="caption" color="text.secondary">initial state</Typography>
            </Stack>
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
              <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: TERMINAL_COLOR }} />
              <Typography variant="caption" color="text.secondary">terminal state</Typography>
            </Stack>
          </Stack>
        </Box>
        {/* Wide content scrolls inside its own container -- the page itself
            never scrolls horizontally. Auto (not fixed) table layout so a
            single long chip (e.g. "TRANSFER_CHECKER") can size to its content
            instead of being truncated by a hard column width. */}
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small" sx={{ minWidth: 800 }}>
            <TableHead>
              <TableRow>
                <TableCell>From</TableCell>
                <TableCell>Action</TableCell>
                <TableCell>To</TableCell>
                <TableCell>Allowed Roles</TableCell>
                <TableCell>Required Approvals</TableCell>
                <TableCell>Guards</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {def.transitions.map((t, i) => (
                <TableRow key={`${t.from}-${t.name}-${i}`}>
                  <TableCell><StateLabel stateId={t.from} def={def} /></TableCell>
                  <TableCell sx={{ textTransform: 'capitalize', fontWeight: 600, whiteSpace: 'nowrap' }}>{t.name}</TableCell>
                  <TableCell><StateLabel stateId={t.to} def={def} /></TableCell>
                  <TableCell>
                    {t.allowedRoles.length > 0 ? (
                      <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', rowGap: 0.5 }}>
                        {t.allowedRoles.map((r) => <Chip key={r} label={r} size="small" />)}
                      </Stack>
                    ) : '—'}
                  </TableCell>
                  <TableCell>{t.requiredApprovals ?? '—'}</TableCell>
                  <TableCell>
                    {t.guards.length > 0 ? (
                      <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', rowGap: 0.5 }}>
                        {t.guards.map((g) => <Chip key={g} label={g} size="small" variant="outlined" />)}
                      </Stack>
                    ) : '—'}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      </Paper>
    </Box>
  );
}
