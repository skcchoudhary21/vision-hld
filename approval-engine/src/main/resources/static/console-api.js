const API = (() => {
  async function get(path) {
    const res = await fetch(path);
    if (!res.ok) throw new Error(path + ' -> ' + res.status);
    return res.json();
  }
  async function post(path, body) {
    const res = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error((err.code || res.status) + ': ' + path);
    }
    return res.json();
  }

  return {
    listApprovals: (status) => get('/approvals?status=' + (status || 'all')),
    getWorkflowView: (id) => get('/approvals/' + id + '/workflow-view'),
    getAudit: (id) => get('/approvals/' + id + '/audit'),
    decide: (id, action, actorId, actorRole) =>
      post('/approvals/' + id + '/' + action, { actorId, actorRole }),
    listWorkflows: () => get('/workflows'),
    getWorkflow: (id, version) => get('/workflows/' + id + '/' + version)
  };
})();
