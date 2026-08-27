import { useCallback, useEffect, useState } from 'react';
import { Box, Typography, CircularProgress, Alert, Snackbar } from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { useActor } from '../state/ActorContext';
import { isMaker } from '../state/actors';
import { approvalsApi, transfersApi } from '../api/client';
import type { AuditEntry, TransferDetail, WorkflowView } from '../api/types';
import { MakerRequestDetail } from './MakerRequestDetail';
import { CheckerRequestDetail } from './CheckerRequestDetail';

export function RequestDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { actor } = useActor();
  const [view, setView] = useState<WorkflowView | null>(null);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [transfer, setTransfer] = useState<TransferDetail | null>(null);
  const [acting, setActing] = useState<string | null>(null);
  const [snack, setSnack] = useState<{ severity: 'success' | 'error'; message: string } | null>(null);

  const load = useCallback(async () => {
    try {
      const [v, a] = await Promise.all([
        approvalsApi.workflowView(id),
        approvalsApi.audit(id),
      ]);
      setView(v);
      setAudit(a);
    } catch {
      // The approval workflow doesn't exist yet. Submission is asynchronous
      // (banking-service publishes to Redis; approval-engine's consumer creates
      // the workflow moments later) -- landing here before that consumer has
      // run is expected, not an error. The polling effect below retries until
      // it appears.
    }
    // Whether a Transfer record exists is a question for banking-service, not
    // something inferable from the workflow's name: policy config can route a
    // transfer's amount to ANY workflow (including a non-"transfer-*"-named
    // one, e.g. privileged-access), so a naming heuristic here would silently
    // hide real amount data the moment someone reconfigures the policy table.
    try {
      setTransfer(await transfersApi.get(id));
    } catch {
      setTransfer(null);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  // Keep polling while there's nothing to show yet, or the workflow hasn't
  // reached a terminal state -- there is no push mechanism (SSE exists in
  // UiController but nothing in this app subscribes to it: EventSource can't
  // carry the X-Actor-Id/X-Actor-Role headers every other endpoint requires,
  // so plain polling reuses the same authenticated request() helper instead
  // of carving out a header-auth exception for one endpoint).
  useEffect(() => {
    if (view && view.terminalStates.includes(view.currentState)) return;
    const interval = setInterval(load, 1500);
    return () => clearInterval(interval);
  }, [load, view]);

  async function act(actionName: 'approve' | 'reject' | 'cancel') {
    setActing(actionName);
    try {
      const result = await approvalsApi.decide(id, actionName, actor.id, actor.role);
      setSnack({ severity: 'success', message: `${actionName[0].toUpperCase()}${actionName.slice(1)}d — now ${result.state}.` });
      await load();
    } catch (e) {
      setSnack({ severity: 'error', message: e instanceof Error ? e.message : String(e) });
    } finally {
      setActing(null);
    }
  }

  if (!view) {
    return (
      <Box sx={{ p: 6, textAlign: 'center' }}>
        <CircularProgress />
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
          Processing your request...
        </Typography>
      </Box>
    );
  }

  // Only human-initiated decisions get a button. "expire" fires on its own
  // via the sla_expired guard, not a role check -- it's not something an
  // actor clicks, the same reason the original console never rendered it.
  // "cancel" is maker-gated server-side via the actor_is_maker guard rather
  // than allowedRoles, so for transfers (where we know the maker) restrict
  // the button to the actual maker too.
  const HUMAN_ACTIONS = new Set(['approve', 'reject', 'cancel']);
  const myActions = view.availableActions.filter((a) => {
    if (!HUMAN_ACTIONS.has(a.name)) return false;
    if (a.allowedRoles.length > 0 && !a.allowedRoles.includes(actor.role)) return false;
    if (a.name === 'cancel' && transfer && transfer.makerId !== actor.id) return false;
    return true;
  });

  return (
    <Box sx={{ maxWidth: 720, mx: 'auto', p: 3 }}>
      <Typography onClick={() => navigate(-1)} sx={{ fontSize: 12.5, color: 'secondary.dark', display: 'block', mb: 2, cursor: 'pointer' }}>
        &larr; Back
      </Typography>
      {isMaker(actor.role) ? (
        <MakerRequestDetail
          transfer={transfer}
          audit={audit}
          actor={actor}
          myActions={myActions}
          acting={acting}
          onAct={act}
        />
      ) : (
        <CheckerRequestDetail
          view={view}
          audit={audit}
          transfer={transfer}
          actor={actor}
          myActions={myActions}
          humanActionNames={HUMAN_ACTIONS}
          acting={acting}
          onAct={act}
        />
      )}
      <Snackbar open={snack !== null} autoHideDuration={5000} onClose={() => setSnack(null)}>
        {snack ? <Alert severity={snack.severity} onClose={() => setSnack(null)}>{snack.message}</Alert> : undefined}
      </Snackbar>
    </Box>
  );
}
