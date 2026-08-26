const ROLES = ['MAKER', 'TRANSFER_CHECKER', 'RISK_CHECKER', 'TRANSFER_MANAGER', 'COMPLIANCE_OFFICER', 'SECURITY', 'MANAGER', 'COMPLIANCE'];

function currentRole() {
  return localStorage.getItem('console-actor-role') || ROLES[0];
}
function setRole(role) {
  localStorage.setItem('console-actor-role', role);
}

function el(html) {
  const div = document.createElement('div');
  div.innerHTML = html.trim();
  return div.firstChild;
}

function renderNav(active) {
  const nav = document.getElementById('nav');
  nav.innerHTML = '';
  [['#requests', 'Requests'], ['#workflows', 'Workflows']].forEach(([hash, label]) => {
    const a = document.createElement('a');
    a.href = hash;
    a.textContent = label;
    if (active === label.toLowerCase()) a.className = 'active';
    nav.appendChild(a);
  });
}

function renderRoleSelect() {
  const select = document.getElementById('actor-role');
  select.innerHTML = ROLES.map(r => '<option value="' + r + '">' + r + '</option>').join('');
  select.value = currentRole();
  select.onchange = () => { setRole(select.value); route(); };
}

async function requestsPage(status) {
  status = status || 'all';
  renderNav('requests');
  const content = document.getElementById('content');
  content.innerHTML = '<div class="tabs">' +
    ['all', 'pending', 'completed'].map(s =>
      '<a href="#requests/' + s + '" class="' + (s === status ? 'active' : '') + '">' + s + '</a>').join('') +
    '</div><table><thead><tr><th>Request</th><th>Workflow</th><th>Current Stage</th><th>Approval</th><th>Status</th></tr></thead><tbody id="requests-body"></tbody></table>';

  const rows = await API.listApprovals(status);
  const body = document.getElementById('requests-body');
  body.innerHTML = '';
  rows.forEach(r => {
    const tr = el('<tr class="row-link"><td>' + r.requestId + '</td><td>' + r.workflowId + ':' + r.workflowVersion + '</td><td>' +
      r.currentStageLabel + '</td><td>' + (r.requiredApprovals != null ? r.currentApprovals + ' / ' + r.requiredApprovals : '—') +
      '</td><td>' + (r.terminal ? r.currentState : 'PENDING') + '</td></tr>');
    tr.onclick = () => { location.hash = '#requests/detail/' + r.requestId; };
    body.appendChild(tr);
  });
}

function pipelineHtml(view) {
  return '<div class="pipeline">' + view.stages.map((s, i) => {
    const cls = s.status === 'IN_PROGRESS' ? 'active' : s.status === 'COMPLETED' ? 'success' : s.status === 'FAILED' ? 'failed' : 'pending';
    const progress = s.requiredApprovals != null ? '<div>' + s.completedApprovals + ' / ' + s.requiredApprovals + '</div>' : '';
    const arrow = i < view.stages.length - 1 ? '<span class="pipe-arrow">&rarr;</span>' : '';
    return '<div class="pipe-stage ' + cls + '"><div>' + s.label + '</div>' + progress + '</div>' + arrow;
  }).join('') + '</div>';
}

function updateActionButtons(view) {
  const role = currentRole();
  const actions = view.availableActions || [];
  ['approve', 'reject', 'cancel'].forEach(name => {
    const btn = document.getElementById('btn-' + name);
    if (!btn) return;
    const action = actions.find(a => a.name === name);
    const allowed = !!action && (action.allowedRoles.length === 0 || action.allowedRoles.includes(role));
    btn.style.display = allowed ? '' : 'none';
  });
}

async function requestDetailPage(id) {
  renderNav('requests');
  const content = document.getElementById('content');
  content.innerHTML = '<div id="detail-body">Loading…</div>';

  async function refresh() {
    const view = await API.getWorkflowView(id);
    const audit = await API.getAudit(id);
    const detail = document.getElementById('detail-body');
    detail.innerHTML =
      '<h2>' + id + ' <span style="font-weight:400;color:#666;font-size:14px">' + view.workflowId + ':' + view.workflowVersion + '</span></h2>' +
      pipelineHtml(view) +
      '<div class="actions">' +
      '<button id="btn-approve">Approve</button>' +
      '<button id="btn-reject">Reject</button>' +
      '<button id="btn-cancel">Cancel</button>' +
      '</div>' +
      '<h3>Workflow History</h3>' +
      '<div id="timeline"></div>';

    const timeline = document.getElementById('timeline');
    audit.slice().reverse().forEach(a => {
      timeline.appendChild(el('<div class="timeline-entry"><div class="action">' + a.action + ': ' +
        a.previousState + ' &rarr; ' + a.newState + '</div><div class="meta">' + a.createdAt +
        (a.actorId ? ' · ' + a.actorId + (a.actorRole ? ' (' + a.actorRole + ')' : '') : '') + '</div></div>'));
    });

    updateActionButtons(view);
    ['approve', 'reject', 'cancel'].forEach(name => {
      const btn = document.getElementById('btn-' + name);
      if (btn) btn.onclick = async () => {
        const actorId = prompt('actorId:');
        if (!actorId) return;
        try {
          await API.decide(id, name, actorId, currentRole());
          await refresh();
        } catch (e) {
          alert(e.message);
        }
      };
    });
  }

  await refresh();
}

async function workflowsPage() {
  renderNav('workflows');
  const content = document.getElementById('content');
  content.innerHTML = '<table><thead><tr><th>Workflow</th><th>Version</th><th>States</th></tr></thead><tbody id="workflows-body"></tbody></table>';
  const rows = await API.listWorkflows();
  const body = document.getElementById('workflows-body');
  rows.forEach(w => {
    const tr = el('<tr class="row-link"><td>' + w.workflowId + '</td><td>' + w.version + '</td><td>' + w.stateCount + '</td></tr>');
    tr.onclick = () => { location.hash = '#workflows/detail/' + w.workflowId + '/' + w.version; };
    body.appendChild(tr);
  });
}

async function workflowDetailPage(id, version) {
  renderNav('workflows');
  const content = document.getElementById('content');
  const def = await API.getWorkflow(id, version);
  content.innerHTML =
    '<h2>' + def.workflowId + ':' + def.version + '</h2>' +
    '<div class="state-chain">' + def.states.map(s => s.id).join(' &rarr; ') + '</div>' +
    def.states.map(s => {
      const from = def.transitions.filter(t => t.from === s.id);
      if (from.length === 0) return '';
      return '<h4>' + s.id + '</h4>' + from.map(t =>
        '<div class="transition-row">' + t.name + ' &rarr; ' + t.to +
        (t.allowedRoles.length ? ' · allowed: ' + t.allowedRoles.join(', ') : '') +
        (t.requiredApprovals != null ? ' · approvals: ' + t.requiredApprovals : '') +
        (t.guards.length ? ' · guards: ' + t.guards.join(', ') : '') + '</div>').join('');
    }).join('');
}

function route() {
  const hash = location.hash.replace(/^#/, '');
  const parts = hash.split('/');
  if (parts[0] === 'requests' && parts[1] === 'detail' && parts[2]) {
    requestDetailPage(parts[2]);
  } else if (parts[0] === 'requests') {
    requestsPage(parts[1]);
  } else if (parts[0] === 'workflows' && parts[1] === 'detail' && parts[2] && parts[3]) {
    workflowDetailPage(parts[2], parts[3]);
  } else if (parts[0] === 'workflows') {
    workflowsPage();
  } else {
    location.hash = '#requests';
  }
}

window.addEventListener('hashchange', route);
window.addEventListener('DOMContentLoaded', () => {
  renderRoleSelect();
  route();
});
