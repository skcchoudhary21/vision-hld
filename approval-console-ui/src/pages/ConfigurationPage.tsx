import { useState } from 'react';
import { Box, Typography, Paper, Tabs, Tab } from '@mui/material';
import { WorkflowCatalogTable } from './WorkflowCatalogTable';
import { ApprovalPoliciesTable } from './ApprovalPoliciesTable';

export function ConfigurationPage() {
  const [tab, setTab] = useState<'catalog' | 'policies'>('catalog');

  return (
    <Box sx={{ maxWidth: 900, mx: 'auto', p: 3 }}>
      <Typography variant="h1" sx={{ mb: 2 }}>Configuration</Typography>
      <Paper sx={{ boxShadow: '0 1px 3px rgba(0,0,0,0.08)' }}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ borderBottom: 1, borderColor: 'divider', px: 2 }}>
          <Tab value="catalog" label="Workflow Catalog" />
          <Tab value="policies" label="Approval Policies" />
        </Tabs>
        <Box sx={{ p: tab === 'catalog' ? 0 : 3 }}>
          {tab === 'catalog' ? <WorkflowCatalogTable /> : <ApprovalPoliciesTable />}
        </Box>
      </Paper>
    </Box>
  );
}
