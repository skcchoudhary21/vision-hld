import type { ReactNode } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { Box } from '@mui/material';
import { TopNav } from './components/TopNav';
import { MyAccountPage } from './pages/MyAccountPage';
import { NewRequestPage } from './pages/NewRequestPage';
import { RequestDetailPage } from './pages/RequestDetailPage';
import { ApprovalWorkspacePage } from './pages/ApprovalWorkspacePage';
import { IdentityRolesPage } from './pages/IdentityRolesPage';
import { ConfigurationPage } from './pages/ConfigurationPage';
import { WorkflowCatalogDetailPage } from './pages/WorkflowCatalogDetailPage';
import { useActor } from './state/ActorContext';
import { isMaker } from './state/actors';

function Home() {
  const { actor } = useActor();
  return <Navigate to={isMaker(actor.role) ? '/my-account' : '/workspace'} replace />;
}

// Route guards, not just hidden nav links: switching roles must not leave you
// able to sit on a page that belongs to the other persona just because the
// URL didn't change. Each only redirects when the CURRENT route actually
// doesn't belong to the CURRENT role -- it never forces you off a page your
// role is already allowed to see.
function RequireMaker({ children }: { children: ReactNode }) {
  const { actor } = useActor();
  return isMaker(actor.role) ? <>{children}</> : <Navigate to="/workspace" replace />;
}

function RequireChecker({ children }: { children: ReactNode }) {
  const { actor } = useActor();
  return isMaker(actor.role) ? <Navigate to="/my-account" replace /> : <>{children}</>;
}

export default function App() {
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default' }}>
      <TopNav />
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/my-account" element={<RequireMaker><MyAccountPage /></RequireMaker>} />
        <Route path="/new-request" element={<RequireMaker><NewRequestPage /></RequireMaker>} />
        <Route path="/requests/:id" element={<RequestDetailPage />} />
        <Route path="/workspace" element={<RequireChecker><ApprovalWorkspacePage /></RequireChecker>} />
        <Route path="/identity" element={<RequireChecker><IdentityRolesPage /></RequireChecker>} />
        <Route path="/configuration" element={<RequireChecker><ConfigurationPage /></RequireChecker>} />
        <Route path="/catalog/:id/:version" element={<RequireChecker><WorkflowCatalogDetailPage /></RequireChecker>} />
      </Routes>
    </Box>
  );
}
