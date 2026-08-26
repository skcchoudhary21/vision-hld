import { useState } from 'react';
import {
  Box, Typography, Paper, Table, TableHead, TableBody, TableRow, TableCell, Button, Snackbar, Alert,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { ACTORS } from '../state/actors';
import { StatusChip } from '../components/StatusChip';

export function IdentityRolesPage() {
  const [snackOpen, setSnackOpen] = useState(false);

  return (
    <Box sx={{ maxWidth: 800, mx: 'auto', p: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Box>
          <Typography variant="h1">Identity &amp; Roles</Typography>
          <Typography variant="body2" color="text.secondary">
            Demo identity list — switch actors from the top-right selector. Not a real IAM system.
          </Typography>
        </Box>
        <Button variant="contained" color="secondary" startIcon={<AddIcon />} onClick={() => setSnackOpen(true)}>
          Add User
        </Button>
      </Box>
      <Paper sx={{ boxShadow: '0 1px 3px rgba(0,0,0,0.08)' }}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>User</TableCell>
              <TableCell>Role</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {ACTORS.map((a) => (
              <TableRow key={a.id}>
                <TableCell>{a.name}</TableCell>
                <TableCell>{a.role}</TableCell>
                <TableCell><StatusChip label="Active" /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
      <Snackbar open={snackOpen} autoHideDuration={3000} onClose={() => setSnackOpen(false)}>
        <Alert severity="info" onClose={() => setSnackOpen(false)}>
          Demo identity list only — not wired to a real user store.
        </Alert>
      </Snackbar>
    </Box>
  );
}
