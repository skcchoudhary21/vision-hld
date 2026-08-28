# Reviewer Demo Guide — Approval Console UI

A walkthrough for reviewing the maker-checker transfer approval system through
its UI, no API calls required. For design rationale, see `docs/hld.md`,
`docs/lld.md`, and `docs/transfer-approval-design.md`.

## 1. Start it up

```bash
docker compose up --build
```

If you have a Postgres volume from a previous run of this repo, run
`docker compose down -v` first — schema changes won't apply cleanly to
existing data under `ddl-auto: update`.

This starts four containers: Postgres (`5432`), Redis (`6379`),
`approval-engine` (`8081`), `banking-service` (`8080`), and the console UI
(`3000`).

**Open http://localhost:3000 — this is the only URL you need.**

There's no login. Identity is a demo actor picker in the top nav — switching
it changes which maker or checker you're acting as, and the UI's routes
adjust automatically (maker screens vs. checker screens).

## 2. Who's who

| Actor picker name | Role | Use for |
|---|---|---|
| Suresh | MAKER | submitting transfers |
| Neha Kapoor | MAKER | a second maker, if you want to show two makers' requests are kept separate |
| Vikram Rao / Meera Iyer | TRANSFER_CHECKER | approving standard-tier transfers (need 2 identities to demo 2-approver quorum) |
| Aisha Khan / Daniel Ford | SECURITY_CHECKER | first stage of high-value (≥ AED 100,000) requests |
| Amit Verma | MANAGER_CHECKER | second stage of high-value requests |
| Priya Nair | COMPLIANCE_CHECKER | third/final stage of high-value requests |

There's no real IAM behind this list — it's read-only reference on the
"Identity & Roles" screen (checker view only), there deliberately to make
that explicit.

## 3. The core flow to demo

Amount decides how much approval a transfer needs. All four tiers share the
same engine and the same UI — only the number of stages/approvers changes:

| Amount (AED) | What happens |
|---|---|
| < 5,000 | Auto-released, no checker involved |
| 5,000 – 49,999.99 | 1 TRANSFER_CHECKER approval |
| 50,000 – 99,999.99 | 2 TRANSFER_CHECKER approvals (quorum) |
| ≥ 100,000 | Escalates to a 3-stage review: 2× SECURITY_CHECKER → 1× MANAGER_CHECKER → 1× COMPLIANCE_CHECKER |

**Suggested script — single-checker tier:**
1. Switch to **Suresh** (maker). Go to **My Account** → **New Request**.
2. Submit a transfer of e.g. AED 10,000 to any destination account.
3. You land on the request detail page. It briefly shows "processing" while
   the transfer is routed to a workflow (this is real async plumbing over
   Redis, not a UI mock — see the "async gap" note below) — it resolves to
   `PENDING_APPROVAL` within a second or two.
4. Switch to **Vikram Rao** (checker). Open **Approval Workspace** → "Needs
   My Action" tab → open the request.
5. Approve it. Switch back to Suresh's My Account view (or refresh the
   request) to show the transfer is now `RELEASED`.

**Suggested script — quorum tier (to show multi-approver behavior):**
1. As Suresh, submit AED 60,000 (lands in the 2-checker tier).
2. As Vikram Rao, approve the request from the Approval Workspace. Note
   that once you approve, your own approve/reject buttons disappear and an
   info banner shows "1 / 2 approvals" — the UI is reflecting a real 409
   the server would return if the same actor tried to approve twice.
3. Switch to **Meera Iyer** and approve the same request to complete quorum
   → transfer reaches `RELEASED`.

**Suggested script — high-value escalation (to show workflow versioning):**
1. As Suresh, submit AED 150,000.
2. Walk the request through all three stages, switching actor identity at
   each stage: Aisha Khan or Daniel Ford (2 needed) → Amit Verma → Priya Nair.
3. Worth calling out: this is a structurally different workflow
   (`privileged-access`, currently version 2) running through the exact same
   engine and UI as the simple transfer tiers — no code branching, just a
   different workflow definition. Viewable under **Configuration →
   Workflow Catalog**.

**Other things worth showing, time permitting:**
- **Cancel**: as the maker, cancel a request that's still pending approval.
- **Reject**: as a checker, reject instead of approve — the transfer moves
  to `REJECTED` and the maker's detail view reflects it.
- **Expiry**: the approval SLA is set to 5 minutes for demo purposes (not
  the 24-hour default a real deployment would use) — submit a request, let
  it sit unactioned, and refresh after ~5–6 minutes to show it move to
  `EXPIRED`.
- **Configuration** screen: read-only view of the workflow catalog (with
  versions) and the amount-to-workflow policy table backing the tiers above.

## 4. Things that look like bugs but aren't

- **A short "processing" state right after submission.** `POST /transfers`
  returns immediately; the transfer is linked to its approval workflow
  asynchronously (banking-service → Redis → approval-engine). The detail
  page polls until it resolves — normally within a couple of seconds.
- **A request stuck on "this request never reached an approval workflow."**
  This means workflow creation itself failed and the transfer is in a
  terminal `FAILED` state — distinct from the transient processing state
  above, and there's nothing further to action.
- **Approve/reject buttons vanishing after you approve a multi-approver
  stage.** Intentional — the server rejects a second decision from the same
  actor on the same stage; switch to a different checker identity to cast
  the next required vote.
