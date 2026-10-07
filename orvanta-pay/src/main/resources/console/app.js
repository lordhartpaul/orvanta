// Orvanta Console. Plain JavaScript, no build step. All text reaches the page through
// text nodes (function h), never through innerHTML, so payment data cannot inject markup.
'use strict';

// The session token is in a cookie that scripts cannot read (HttpOnly); this page only knows whether it is signed in.
const session = {
  signedIn: null,
  user: null,
};

// ---------- helpers ----------

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.substring(2), v);
    else if (k === 'value') el.value = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  // what may scroll sideways can be reached and scrolled from the keyboard
  if (tag === 'pre' || el.classList.contains('tablewrap') || el.classList.contains('dt-wrap')) {
    el.tabIndex = 0;
    if (tag !== 'pre') { el.setAttribute('role', 'region'); el.setAttribute('aria-label', 'Table; scrolls sideways when it is wider than the page'); }
  }
  return el;
}

// A label written in front of a field belongs to it: screen readers are told so, and a click on the label reaches the field.
let labelled = 0;
function tieLabels(root) {
  if (!root.querySelectorAll) return;
  for (const label of root.querySelectorAll('label:not([for])')) {
    const field = label.nextElementSibling;
    if (field && field.matches('input, select, textarea') && !label.contains(field)) {
      if (!field.id) field.id = 'field-' + (++labelled);
      label.htmlFor = field.id;
    }
  }
}
new MutationObserver(changes => { for (const c of changes) for (const node of c.addedNodes) { if (node.parentElement) tieLabels(node.parentElement); } })
  .observe(document.documentElement, { childList: true, subtree: true });

async function api(method, path, body, rawBody) {
  // the server takes a change from a cookie session only with this header, which no other site can add
  const headers = { 'X-Orvanta-Console': '1' };
  let payload;
  if (rawBody !== undefined) {
    payload = rawBody;
    headers['Content-Type'] = 'text/plain';
  } else if (body !== undefined) {
    payload = JSON.stringify(body);
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(path, { method, headers, body: payload });
  let data = null;
  try { data = await res.json(); } catch (e) { /* empty body */ }
  if (res.status === 401 && path !== '/api/auth/login') {
    signOut();
    throw new Error('Your session has ended. Sign in again.');
  }
  if (!res.ok && res.status !== 422) {
    const failure = new Error((data && data.error) || ('Request failed with HTTP ' + res.status));
    failure.data = data;
    throw failure;
  }
  return data;
}

function can(permission) {
  const p = (session.user && session.user.permissions) || [];
  return p.includes('*') || p.includes(permission);
}

// ---------- live updates ----------

// The server tells this page when something changed, and the page shown fetches its data again.
// A page that wants this sets live.handler = { kinds, run }. Without a connection the page falls back to asking every 15 seconds.
const live = { handler: null, controller: null, connected: false, pending: false, timer: null, lastRun: 0, fallback: null };

function liveShow() {
  const el = document.getElementById('live');
  if (!el) return;
  el.className = 'live ' + (live.connected ? 'on' : 'off');
  el.textContent = live.connected ? 'Live' : 'Reconnecting…';
  el.title = live.connected ? 'This page is told when something changes.' : 'The connection for live updates is down. Pages refresh every 15 seconds until it is back.';
}

function liveRun() {
  clearTimeout(live.timer);
  if (!live.handler) return;
  if (document.hidden) { live.pending = true; return; }       // caught up on when the page is looked at again
  const wait = 1500 - (Date.now() - live.lastRun);            // at most one refresh every second and a half
  if (wait > 0) { live.timer = setTimeout(liveRun, wait); return; }
  live.lastRun = Date.now();
  live.pending = false;
  Promise.resolve(live.handler.run()).catch(() => {});
}

function liveChanged(kinds) {
  if (live.handler && kinds.some(k => live.handler.kinds.includes(k))) liveRun();
}

async function liveConnect() {
  if (!session.signedIn || live.controller) return;
  const controller = new AbortController();
  live.controller = controller;
  try {
    const response = await fetch('/api/stream', { headers: { Accept: 'text/event-stream' }, signal: controller.signal });
    if (!response.ok || !response.body) throw new Error('no stream');
    live.connected = true;
    liveShow();
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let end;
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.substring(0, end);
        buffer = buffer.substring(end + 2);
        const event = (/^event: ?(.*)$/m.exec(block) || [])[1];
        const data = (/^data: ?(.*)$/m.exec(block) || [])[1];
        if (event === 'changed' && data) { try { liveChanged(JSON.parse(data).kinds || []); } catch (e) { /* a notice that cannot be read is skipped */ } }
      }
    }
  } catch (e) { /* tried again below */ }
  if (live.controller === controller) live.controller = null;
  live.connected = false;
  liveShow();
  if (session.signedIn && !controller.signal.aborted) setTimeout(liveConnect, 5000);
}

function liveStop() {
  if (live.controller) live.controller.abort();
  live.controller = null;
  live.connected = false;
  live.handler = null;
}

document.addEventListener('visibilitychange', () => { if (!document.hidden && live.pending) liveRun(); });
live.fallback = setInterval(() => { if (!live.connected && live.handler && !document.hidden) liveRun(); }, 15000);

function scopeText(scope) {
  if (!scope) return '';
  return [(scope.channels || []).length ? 'channels ' + scope.channels.map(c => c.split('.').pop()).join(', ') : null,
    (scope.debtorAccounts || []).length ? 'debtor accounts ' + scope.debtorAccounts.join(', ') : null,
    (scope.currencies || []).length ? 'currencies ' + scope.currencies.join(', ') : null,
    scope.maxAmount ? 'amounts up to ' + scope.maxAmount : null].filter(Boolean).join(' and ');
}

function signOut() {
  // ends the session on the server too, so the token is useless even if someone copied it, and takes the cookie away
  if (session.signedIn) fetch('/api/auth/logout', { method: 'POST', headers: { 'X-Orvanta-Console': '1' } }).catch(() => {});
  session.signedIn = false;
  session.user = null;
  liveStop();
  render();
}

const GOOD = ['CHARGE_CLAIM_PAID', 'CHARGE_CLAIMED', 'PAID', 'NOTIFIED', 'CREDITED', 'DEBITED', 'LOGIN_OK', 'LOGOUT', 'RECONCILED', 'MATCHED', 'OK', 'RELEASED', 'ANSWER_RECEIVED', 'POSTING_REVERSED', 'ACCEPTED', 'SENT', 'DEBULKED', 'PROCESSED', 'APPROVED', 'ACKNOWLEDGED', 'ACTIVE', 'ROUTED', 'BULKED'];
const BAD = ['OVERDUE', 'CHARGE_CLAIM_FAILED', 'NOTIFICATION_FAILED', 'RECALL_REFUSED', 'LOGIN_FAILED', 'DENIED', 'USER_LOCKED', 'LOGIN_THROTTLED', 'MISMATCH', 'RECONCILIATION_MISMATCH', 'REJECTED', 'REJECTED_BY_APPLICATION', 'REJECTED_BY_EXTERNAL', 'FAILED', 'DECLINED', 'LOCKED', 'DISABLED', 'RETURNED',
  'CANCELLATION_REFUSED', 'STATUS_REPORT_FAILED'];
const WARN = ['ANSWER_OVERDUE', 'NOT_NOTIFIED', 'REQUESTED', 'RECALL_REQUESTED', 'REPAIR', 'PENDING', 'CANCELLED', 'CANCELLATION_REQUESTED', 'RECOVERED', 'OPEN', 'HELD', 'WAITING', 'WAREHOUSED', 'REVERSAL_FAILED', 'UNMATCHED', 'NOT_CHECKED'];

function badge(status) {
  const cls = GOOD.includes(status) ? 'good' : BAD.includes(status) ? 'bad' : WARN.includes(status) ? 'warn' : '';
  return h('span', { class: 'badge ' + cls }, String(status || '').replaceAll('_', ' '));
}

function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString();
}

function money(amount, currency) {
  if (amount === null || amount === undefined) return '';
  const n = Number(amount);
  return (isNaN(n) ? amount : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })) + (currency ? ' ' + currency : '');
}

function table(columns, rows, onRow) {
  if (!rows.length) return h('div', { class: 'tablewrap' }, h('div', { class: 'empty' }, 'Nothing to show.'));
  return h('div', { class: 'tablewrap' }, h('table', {},
    h('thead', {}, h('tr', {}, columns.map(c => h('th', { class: c.num ? 'num' : '' }, c.label)))),
    h('tbody', {}, rows.map((r, i) => h('tr', { class: onRow ? 'click' : '', onclick: onRow ? () => onRow(r) : null },
      columns.map(c => h('td', { class: (c.num ? 'num ' : '') + (c.mono ? 'mono' : '') }, c.get(r, i))))))));
}

function kv(pairs) {
  return h('dl', { class: 'kv' }, pairs.filter(p => p[1] !== null && p[1] !== undefined && p[1] !== '')
    .flatMap(p => [h('dt', {}, p[0]), h('dd', {}, p[1])]));
}

function timeline(events) {
  return h('ul', { class: 'timeline' }, (events || []).map(e => h('li', {},
    h('div', {}, badge(e.type), ' ', e.text || ''),
    h('div', { class: 'at' }, when(e.at) + ' by ' + e.actor),
    e.detail && e.detail.trace ? trace(e.detail.trace) : null)));
}

function trace(steps) {
  const out = [];
  steps.forEach((s, i) => {
    if (i) out.push(h('span', { class: 'arrow' }, '→'));
    out.push(h('div', { class: 'step' + (s.skipped ? ' skipped' : '') + (s.note && s.note.includes('ended') ? ' ended' : '') },
      h('b', {}, s.step), s.type + (s.skipped ? ' (skipped)' : ''), s.note ? h('div', {}, s.note) : null));
  });
  return h('div', { class: 'steps' }, out);
}

function go(hash) { location.hash = hash; }

function page(title, sub, ...content) {
  return [h('h1', {}, title), h('p', { class: 'sub' }, sub || ''), ...content];
}

// ---------- lists ----------

/**
 * A list page whose search, filters, order and pages are worked out by the server.
 * config: title, sub, hash, api, fixed (query values always sent), search {label, placeholder},
 * selects [{key, label, options: [[value, text]]}], more [{key, label, type, attrs, chip}], hidden [{key, chip}],
 * columns [{label, get, sort, num, mono, nw}], open (row to address), sort [field, direction],
 * empty (text when nothing matches), actions (elements in front of the toolbar).
 */
async function listView(config, params) {
  const selects = config.selects || [], more = config.more || [], hidden = config.hidden || [];
  const keys = [config.search ? 'q' : null, ...selects.map(f => f.key), ...more.map(f => f.key), ...hidden.map(f => f.key)].filter(Boolean);
  const [sortField, sortDir] = config.sort || ['id', 'desc'];
  const state = { sort: params.get('sort') || sortField, dir: params.get('dir') || sortDir, offset: Number(params.get('offset')) || 0, limit: Number(params.get('limit')) || 50 };
  for (const k of keys) state[k] = params.get(k) || '';
  const results = h('div', { class: 'results', 'aria-live': 'polite' });
  const chips = h('div', { class: 'chips' });
  const controls = {};
  let latest = 0;

  const query = () => {
    const q = new URLSearchParams();
    for (const k of keys) if (state[k]) q.set(k, state[k]);
    if (state.sort !== sortField || state.dir !== sortDir) { q.set('sort', state.sort); q.set('dir', state.dir); }
    if (state.offset) q.set('offset', state.offset);
    if (state.limit !== 50) q.set('limit', state.limit);
    return q;
  };
  const sortBy = (field) => {
    if (state.sort === field) state.dir = state.dir === 'asc' ? 'desc' : 'asc';
    else { state.sort = field; state.dir = 'desc'; }
    state.offset = 0;
    load();
  };
  const sync = () => { for (const k of Object.keys(controls)) controls[k].value = state[k]; };

  const draw = (r) => {
    const head = h('tr', {}, config.columns.map(c => {
      const on = c.sort && state.sort === c.sort;
      return h('th', { class: c.num ? 'num' : '', 'aria-sort': on ? (state.dir === 'asc' ? 'ascending' : 'descending') : null },
        c.sort ? h('button', { type: 'button', class: 'sort' + (on ? ' on' : ''), title: 'Sort by ' + c.label.toLowerCase(), onclick: () => sortBy(c.sort) },
          c.label, h('span', { 'aria-hidden': 'true' }, on ? (state.dir === 'asc' ? ' ▲' : ' ▼') : '')) : c.label);
    }));
    const body = r.items.map(row => {
      const cells = config.columns.map(c => h('td', { class: (c.num ? 'num ' : '') + (c.mono ? 'mono ' : '') + (c.nw ? 'nw' : '') }, c.get(row)));
      if (!config.open && !config.onRow) return h('tr', {}, cells);
      const choose = () => { if (config.onRow) config.onRow(row); else go(config.open(row)); };
      const tr = h('tr', { class: 'click', tabindex: '0', onclick: choose }, cells);
      tr.addEventListener('keydown', e => { if (e.key === 'Enter' && e.target === tr) choose(); });
      return tr;
    });
    const first = r.total ? r.offset + 1 : 0;
    const last = r.offset + r.items.length;
    const size = h('select', { 'aria-label': 'Rows per page', onchange: (e) => { state.limit = Number(e.target.value); state.offset = 0; load(); } },
      [25, 50, 100, 250].map(n => h('option', { value: n, selected: n === state.limit }, n + ' per page')));
    const pager = h('div', { class: 'pager' },
      h('span', { class: 'count' }, r.total ? first.toLocaleString() + '–' + last.toLocaleString() + ' of ' + r.total.toLocaleString() : 'Nothing matches'),
      h('span', { class: 'spacer' }), size,
      h('button', { type: 'button', disabled: r.offset === 0, onclick: () => { state.offset = Math.max(0, state.offset - state.limit); load(); } }, 'Previous'),
      h('button', { type: 'button', disabled: last >= r.total, onclick: () => { state.offset += state.limit; load(); } }, 'Next'));
    results.replaceChildren(
      r.items.length ? h('div', { class: 'tablewrap' }, h('table', { class: 'sticky' }, h('thead', {}, head), h('tbody', {}, body)))
        : h('div', { class: 'tablewrap' }, h('div', { class: 'empty' }, keys.some(k => state[k]) ? (config.empty || 'Nothing matches these filters.') : 'Nothing to show.')),
      pager);
  };

  const chipNames = { q: 'Text' };
  const chipValues = {};
  for (const f of selects) { chipNames[f.key] = f.chip || f.label.replace(/^All /, '').replace(/s$/, '').replace(/^./, c => c.toUpperCase()); chipValues[f.key] = Object.fromEntries(f.options); }
  for (const f of [...more, ...hidden]) chipNames[f.key] = f.chip || f.label;
  const drawChips = () => {
    const active = keys.filter(k => state[k]);
    chips.replaceChildren(...[...active.map(k => h('span', { class: 'chip' }, chipNames[k] + ': ' + ((chipValues[k] || {})[state[k]] || state[k]),
      h('button', { type: 'button', 'aria-label': 'Remove the filter ' + chipNames[k], onclick: () => { state[k] = ''; state.offset = 0; sync(); load(); } }, '×'))),
      active.length > 1 ? h('button', { type: 'button', class: 'linklike', onclick: () => { keys.forEach(k => { state[k] = ''; }); state.offset = 0; sync(); load(); } }, 'Clear all') : null].filter(Boolean));
  };

  const load = async () => {
    const mine = ++latest;
    const q = query();
    // the address keeps the filters, so the page can be bookmarked and the back button returns to it
    history.replaceState(null, '', config.hash + (q.toString() ? '?' + q : ''));
    drawChips();
    results.classList.add('busy');
    try {
      q.set('limit', state.limit);
      for (const [k, v] of Object.entries(config.fixed || {})) if (!q.has(k)) q.set(k, v);
      const r = await api('GET', config.api + '?' + q);
      if (mine !== latest) return;          // a newer search has been started meanwhile
      // the same rows, with the same filters and order, as a file; the page size does not apply
      const file = new URLSearchParams(q);
      file.delete('limit');
      file.delete('offset');
      file.set('format', 'csv');
      exportLink.href = config.api + '?' + file;
      exportLink.hidden = !r.items.length;
      draw(r);
    } catch (e) {
      if (mine === latest) results.replaceChildren(h('div', { class: 'note bad' }, e.message));
    } finally { if (mine === latest) results.classList.remove('busy'); }
  };

  const text = (key, label, attrs) => {
    const el = h('input', Object.assign({ 'aria-label': label, value: state[key] }, attrs));
    let timer = null;
    const apply = () => { clearTimeout(timer); if (attrs.upper) el.value = el.value.toUpperCase(); state[key] = el.value.trim(); state.offset = 0; load(); };
    el.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(apply, 300); });
    el.addEventListener('keydown', e => { if (e.key === 'Enter') apply(); });
    controls[key] = el;
    return el;
  };
  const choice = (f) => {
    const el = h('select', { 'aria-label': f.label, onchange: (e) => { state[f.key] = e.target.value; state.offset = 0; load(); } },
      h('option', { value: '' }, f.label), f.options.map(([value, label]) => h('option', { value, selected: value === state[f.key] }, label)));
    controls[f.key] = el;
    return el;
  };
  const moreBox = more.length ? h('details', { class: 'more' }, h('summary', {}, 'More filters'),
    h('div', { class: 'row' }, more.map(f => h('label', { class: 'inline' }, f.short || f.label, text(f.key, f.label, Object.assign({ type: f.type || 'text' }, f.attrs || {})))))) : null;
  if (moreBox && more.some(f => state[f.key])) moreBox.open = true;

  const exportLink = h('a', { class: 'linklike', href: '#', download: '', hidden: '' }, 'Export CSV');
  if (config.live !== false) live.handler = { kinds: config.live || ['payments'], run: load };
  await load();
  return page(config.title, config.sub,
    h('div', { class: 'toolbar' }, config.actions || null,
      config.search ? text('q', config.search.label, { type: 'search', placeholder: config.search.placeholder, class: 'search' }) : null,
      selects.map(choice),
      h('button', { type: 'button', onclick: load }, 'Refresh'), exportLink),
    moreBox, chips, results);
}

const DATE_FILTERS = [
  { key: 'from', label: 'From', short: 'From', type: 'date', chip: 'From' },
  { key: 'to', label: 'To', short: 'to', type: 'date', chip: 'To' },
];
const options = (values) => values.map(v => [v, v.replaceAll('_', ' ')]);

// ---------- shell ----------

const NAV = [
  { group: 'Payments' },
  { hash: '#/dashboard', label: 'Dashboard', need: 'payments.view' },
  { hash: '#/instructions', label: 'Instructions', need: 'payments.view' },
  { hash: '#/initiate', label: 'New payment', need: 'payments.submit' },
  { hash: '#/transactions', label: 'Transactions', need: 'payments.view' },
  { hash: '#/review', label: 'Review queue', need: 'payments.view' },
  { hash: '#/data', label: 'Customer instructions', need: 'payments.view', all: true },
  { hash: '#/claims', label: 'Charge claims', need: 'payments.view' },
  { hash: '#/ledger', label: 'Accounts', need: 'payments.view', all: true },
  { hash: '#/outbound', label: 'Outbound files', need: 'payments.view', all: true },
  { hash: '#/responses', label: 'Responses and requests', need: 'payments.view' },
  { hash: '#/statements', label: 'Statements', need: 'payments.view', all: true },
  { hash: '#/daily', label: 'Daily report', need: 'payments.view', all: true },
  { hash: '#/schedules', label: 'Schedules', need: 'payments.view', all: true },
  { hash: '#/deadletters', label: 'Failed events', need: 'payments.repair', all: true },
  { group: 'Build' },
  { hash: '#/studio', label: 'Studio', need: 'studio.view' },
  { hash: '#/deployments', label: 'Deployments', need: 'studio.view' },
  { hash: '#/checks', label: 'Message checks', need: 'studio.view' },
  { hash: '#/approvals', label: 'Approvals' },
  { group: 'Administration' },
  { hash: '#/users', label: 'Users and roles', need: 'admin.view' },
  { hash: '#/security', label: 'Security log', need: 'admin.view' },
];

let refreshTimer = null;

async function render() {
  clearInterval(refreshTimer);
  const root = document.getElementById('app');
  if (session.signedIn === null) {
    // a page that was reloaded finds out from the server whether its cookie still stands for a session
    try { const s = await (await fetch('/api/auth/session')).json(); session.signedIn = s.signedIn === true; session.sso = s.sso === true ? (s.ssoLabel || 'Sign in with single sign-on') : null; } catch (e) { session.signedIn = false; }
  }
  if (!session.signedIn) { root.replaceChildren(loginView()); return; }
  if (!session.user) {
    try { session.user = await api('GET', '/api/me'); } catch (e) { return; }
  }
  const hash = location.hash || (can('payments.view') ? '#/dashboard' : can('studio.view') ? '#/studio' : '#/approvals');
  const main = h('div', { class: 'main' }, h('div', { class: 'empty' }, 'Loading…'));
  const side = h('div', { class: 'side' },
    h('div', { class: 'brand' }, 'Orvanta', h('small', {}, 'Payments console')),
    NAV.filter(n => (!n.need || can(n.need)) && !(n.all && session.user.scope)).map(n => n.group
      ? h('div', { class: 'group' }, n.group)
      : h('a', { class: 'nav' + (hash.startsWith(n.hash) ? ' on' : ''), href: n.hash, onclick: () => { if (location.hash === n.hash) render(); } }, n.label)),
    h('div', { class: 'who' }, h('b', {}, session.user.displayName || session.user.username),
      session.user.roles.join(', '), session.user.scope ? h('div', { title: scopeText(session.user.scope) }, 'Limited access') : null,
      h('div', { id: 'live', class: 'live off', role: 'status' }, ''), h('div', {}, h('a', { href: '#/account' }, 'My account'), ' · ', h('button', { type: 'button', class: 'linklike', onclick: signOut }, 'Sign out'))));
  root.replaceChildren(h('div', { class: 'shell' }, side, main));
  live.handler = null;
  liveShow();
  liveConnect();
  try {
    const [path, query] = hash.substring(2).split('?');
    const parts = path.split('/');
    const params = new URLSearchParams(query || '');
    const view = VIEWS[parts[0]] || VIEWS.dashboard;
    const content = await view(parts[1] ? decodeURIComponent(parts[1]) : null, params);
    main.replaceChildren(...content.flat(3).filter(c => c !== null && c !== undefined && c !== false));
  } catch (e) {
    main.replaceChildren(h('div', { class: 'note bad' }, e.message));
  }
}

/** Fetches the current page again and swaps only its content: the menu, the scroll position and the focus outside it stay. */
async function refreshMain() {
  const main = document.querySelector('.main');
  if (!main || !session.user) return;
  clearInterval(refreshTimer);
  const hash = location.hash || '#/dashboard';
  const before = live.handler;
  try {
    const [path, query] = hash.substring(2).split('?');
    const parts = path.split('/');
    const view = VIEWS[parts[0]] || VIEWS.dashboard;
    const content = await view(parts[1] ? decodeURIComponent(parts[1]) : null, new URLSearchParams(query || ''));
    if ((location.hash || '#/dashboard') === hash) main.replaceChildren(...content.flat(3).filter(c => c !== null && c !== undefined && c !== false));
  } catch (e) { live.handler = before; /* the next refresh tries again; a failed background refresh must not wipe what is shown */ }
}

function loginView() {
  const user = h('input', { autocomplete: 'username', id: 'username' });
  const pass = h('input', { type: 'password', autocomplete: 'current-password', id: 'password' });
  const problem = h('div', {});
  // shown only to a user who has the second step: after the password, the code from the authenticator app
  const code = h('input', { autocomplete: 'one-time-code', maxlength: '9', id: 'code', spellcheck: 'false' });
  const second = h('div', { hidden: '' }, h('label', { for: 'code' }, 'Code from your authenticator app, or a recovery code'), code);
  const submit = async (ev) => {
    ev.preventDefault();
    problem.replaceChildren();
    try {
      const s = await api('POST', '/api/auth/login', { username: user.value.trim(), password: pass.value, session: 'cookie',
        code: second.hidden ? undefined : code.value.trim() });
      session.signedIn = true;
      session.user = s.user;
      if (s.recoveryCodeUsed) alert('You signed in with a recovery code; ' + s.recoveryCodesLeft + ' left. Set up your authenticator again or get new codes under My account.');
      render();
    } catch (e) {
      if (e.data && e.data.mfaRequired) {
        second.hidden = false;
        code.focus();
        problem.replaceChildren(h('div', { class: 'note' }, e.message.charAt(0).toUpperCase() + e.message.slice(1) + '.'));
      } else {
        problem.replaceChildren(h('div', { class: 'note bad' }, e.message));
      }
    }
  };
  // back from the identity provider without a session: say why, in the user's terms
  const ssoOutcome = new URLSearchParams(location.search).get('sso');
  if (ssoOutcome) {
    problem.replaceChildren(h('div', { class: 'note bad' }, ssoOutcome === 'unknown' ? 'You signed in with the provider, but there is no active Orvanta user with your name. Ask an administrator.'
      : ssoOutcome === 'refused' ? 'The provider did not sign you in.' : 'The sign-in with the provider could not be completed. Try again.'));
    history.replaceState(null, '', location.pathname + location.hash);
  }
  return h('form', { class: 'login panel', onsubmit: submit },
    h('h1', {}, 'Orvanta Console'), h('p', { class: 'sub' }, 'Sign in to continue.'), problem,
    session.sso ? h('p', {}, h('a', { class: 'btn', href: '/api/auth/sso/start' }, session.sso)) : null,
    session.sso ? h('p', { class: 'sub' }, 'Or with an Orvanta password:') : null,
    h('label', { for: 'username' }, 'User name'), user,
    h('label', { for: 'password' }, 'Password'), pass, second,
    h('button', { class: 'primary', type: 'submit' }, 'Sign in'));
}

// ---------- views ----------

const VIEWS = {};

VIEWS.dashboard = async () => {
  const d = await api('GET', '/api/dashboard');
  const listening = d.scoped ? [] : Object.entries((await api('GET', '/api/transports')).listening);
  // told when payments or approvals change; only the figures are fetched again, so the page does not jump while it is being read
  const incidents = d.scoped ? [] : (await api('GET', '/api/incidents')).items;
  live.handler = { kinds: ['payments', 'approvals', 'incidents'], run: refreshMain };
  const tile = (n, label, hash, tone) => h('a', { class: 'tile' + (tone && n ? ' ' + tone : ''), href: hash },
    h('div', { class: 'n' }, Number(n || 0).toLocaleString()), h('div', { class: 'l' }, label));
  const tx = d.transactions;
  const total = Object.values(tx).reduce((a, b) => a + b, 0);
  const attention = (tx.REPAIR || 0) + (tx.HELD || 0);

  // the last seven days: one bar per day, split into accepted, rejected and still on the way
  const days = d.days || [];
  const top = Math.max(1, ...days.map(x => x.received));
  const dayName = (iso) => new Date(iso + 'T12:00:00Z').toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' });
  const chart = h('div', { class: 'chart', role: 'img', 'aria-label': 'Payments received per day over the last seven days: '
      + days.map(x => dayName(x.date) + ' ' + x.received).join(', ') },
    days.map(x => {
      const rejected = x.rejected + x.rejectedOutside;
      const open = Math.max(0, x.received - x.accepted - rejected);
      const part = (n, cls) => n ? h('div', { class: 'seg ' + cls, style: 'height:' + (n / top * 100) + '%' }) : null;
      return h('div', { class: 'day', title: dayName(x.date) + ': ' + x.received + ' received, ' + x.accepted + ' accepted, ' + rejected + ' rejected, ' + open + ' other' },
        h('div', { class: 'count' }, x.received ? x.received.toLocaleString() : ''),
        h('div', { class: 'bar' }, part(open, 'open'), part(rejected, 'bad'), part(x.accepted, 'good')),
        h('div', { class: 'label' }, dayName(x.date)));
    }));
  const legend = h('div', { class: 'legend' }, [['good', 'Accepted'], ['bad', 'Rejected'], ['open', 'Other: on the way, held, cancelled, returned']]
    .map(([cls, text]) => h('span', {}, h('i', { class: 'seg ' + cls }), text)));

  // where the payments are now, largest first
  const tone = s => GOOD.includes(s) ? 'good' : BAD.includes(s) ? 'bad' : WARN.includes(s) ? 'warn' : 'open';
  const present = Object.entries(tx).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1]);
  const biggest = Math.max(1, ...present.map(p => p[1]));
  const byStatus = present.length ? h('div', { class: 'hbars' }, present.map(([s, n]) =>
    h('a', { class: 'hbar', href: '#/transactions?status=' + s },
      h('span', { class: 'name' }, s.replaceAll('_', ' ')),
      h('span', { class: 'track' }, h('span', { class: 'fill seg ' + tone(s), style: 'width:' + Math.max(1.5, n / biggest * 100) + '%' })),
      h('span', { class: 'value' }, n.toLocaleString()))))
    : h('div', { class: 'empty' }, 'No payments yet.');

  const incidentPanel = incidents.length ? h('div', { class: 'note bad', role: 'alert' }, h('b', {}, incidents.length === 1 ? 'One incident needs attention: ' : incidents.length + ' incidents need attention: '),
    incidents.map((x, n) => [n ? '; ' : '', x.text + ' (' + x.count + ', since ' + when(x.openedAt) + ')'])) : null;
  return page('Dashboard', (d.scoped ? 'Your access is limited to ' + scopeText(session.user.scope) + '. The figures cover those payments only. ' : '')
      + 'Deployment ' + d.deployment + ' (version ' + d.version + ') is active. The figures follow what happens.',
    incidentPanel,
    h('div', { class: 'tiles' },
      tile(total, 'Payments in total', '#/transactions'),
      tile(tx.ACCEPTED, 'Accepted', '#/transactions?status=ACCEPTED'),
      tile(attention, 'Waiting for a person', '#/review', 'warn'),
      tile(tx.REPAIR, 'In repair', '#/transactions?status=REPAIR', 'bad'),
      tile(d.instructions, 'Instructions received', '#/instructions'),
      d.scoped ? null : [
        tile(d.instructionsRejected, 'Messages rejected', '#/instructions?status=REJECTED', 'bad'),
        tile(d.deadLetters, 'Failed events', '#/deadletters', 'bad'),
        tile(d.pendingApprovals, 'Approvals pending', '#/approvals', 'warn')]),
    h('div', { class: 'cols wide' },
      h('div', { class: 'panel' }, h('h2', { class: 'first' }, 'Received in the last seven days'), chart, legend),
      h('div', { class: 'panel' }, h('h2', { class: 'first' }, 'Payments by status'), byStatus)),
    d.scoped ? null : [h('h2', {}, 'Inbound transports listening'),
      listening.length ? table([{ label: 'Channel', get: l => l[0].replace('channels.', '') }, { label: 'Transport', mono: true, get: l => l[1] }], listening)
        : h('p', { class: 'sub' }, 'None besides the default inbound folder and the upload API.')]);
};

function messageColumns() {
  return [
    { label: 'Id', mono: true, get: m => m.id },
    { label: 'Type', get: m => m.messageType || '' },
    { label: 'Channel', get: m => (m.channel || '').replace('channels.', '') },
    { label: 'Status', get: m => badge(m.status) },
    { label: 'Message id', get: m => m.msgId || m.fileName || '' },
    { label: 'Received', get: m => when(m.receivedAt) },
  ];
}

VIEWS.instructions = async (id, params) => {
  if (id) return messageDetail(id);
  return listView({
    title: 'Instructions', hash: '#/instructions', api: '/api/messages',
    sub: 'Payment files received from customers, and files that were refused when they arrived. Newest first.',
    fixed: { purpose: 'instruction,unknown' },
    search: { label: 'Search instructions', placeholder: 'Search id, message id, file name, type, reason…' },
    selects: [{ key: 'status', label: 'All statuses', chip: 'Status', options: options(['RECEIVED', 'DEBULKED', 'PROCESSED', 'REJECTED']) }],
    more: DATE_FILTERS,
    sort: ['receivedAt', 'desc'],
    empty: 'No instructions match these filters.',
    actions: can('payments.submit') ? h('button', { class: 'primary', onclick: () => go('#/upload') }, 'Submit an instruction') : null,
    open: m => '#/instructions/' + m.id,
    columns: [
      { label: 'Id', mono: true, nw: true, sort: 'id', get: m => m.id },
      { label: 'Type', nw: true, sort: 'messageType', get: m => m.messageType || '' },
      { label: 'Channel', get: m => (m.channel || '').replace('channels.', '') },
      { label: 'Status', sort: 'status', get: m => badge(m.status) },
      { label: 'Message id', get: m => m.msgId || m.fileName || '' },
      { label: 'Transactions', num: true, sort: 'transactionCount', get: m => m.transactionCount ?? '' },
      { label: 'Total', num: true, nw: true, sort: 'totalAmount', get: m => money(m.totalAmount) },
      { label: 'Reason', get: m => m.reasonCode ? m.reasonCode + ' ' + (m.reasonText || '') : '' },
      { label: 'Received', nw: true, sort: 'receivedAt', get: m => when(m.receivedAt) },
    ],
  }, params);
};

async function messageDetail(id) {
  const m = await api('GET', '/api/messages/' + id);
  const out = h('div', {});
  const replay = async () => {
    const comment = prompt('Why should this message be received again?');
    if (comment === null) return;
    try {
      const r = await api('POST', '/api/messages/' + id + '/replay', { comment });
      out.replaceChildren(h('div', { class: 'note good' }, 'Request ', h('a', { href: '#/approvals/' + r.id }, r.id),
        ' was created. The message is received again when another user approves it.'));
    } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  return page(m.id, (m.messageType || 'Unrecognised message') + (m.channel ? ' on ' + m.channel : ''),
    m.reasonCode ? h('div', { class: 'note bad' }, m.reasonCode + ': ' + m.reasonText) : null,
    m.status === 'REJECTED' && !m.replayedAs && can('payments.repair')
      ? h('div', { class: 'row', style: 'margin-bottom:12px' }, h('button', { onclick: replay }, 'Request replay')) : null,
    out,
    h('div', { class: 'panel' }, kv([
      ['Status', badge(m.status)], ['Message id', m.msgId],
      ['Replayed as', m.replayedAs ? h('a', { href: '#/instructions/' + m.replayedAs }, m.replayedAs) : null],
      ['Replay of', m.replayOf ? h('a', { href: '#/instructions/' + m.replayOf }, m.replayOf) : null], ['Original message', m.originalMsgId], ['Initiating party', m.initiatingParty],
      ['File name', m.fileName], ['Received', when(m.receivedAt) + ' by ' + m.receivedBy],
      ['Batches', m.batchCount], ['Transactions', m.transactionCount], ['Total amount', money(m.totalAmount)],
      ['Accepted', m.acceptedCount], ['Rejected', m.rejectedCount], ['Returned', m.returnedCount],
      ['Cancelled', m.cancelledCount], ['Forwarded to the receiving side', m.forwardedCount], ['Refused', m.refusedCount],
      ['Customer status report', m.reportState],
      ['Result per entry', m.results && m.results.length ? h('pre', {}, JSON.stringify(m.results, null, 2)) : null],
      ['Unmatched entries', m.unmatched && m.unmatched.length ? JSON.stringify(m.unmatched) : null],
    ])),
    m.violations && m.violations.length ? [h('h2', {}, 'Validation'), violations(m.violations)] : null,
    m.purpose === 'instruction' && m.transactionCount
      ? h('p', {}, h('a', { href: '#/transactions?instructionId=' + m.id }, 'Show the ' + m.transactionCount + ' transactions of this instruction')) : null,
    h('h2', {}, 'History'), h('div', { class: 'panel' }, timeline(m.events)),
    h('h2', {}, 'Message as received'), h('pre', {}, m.raw || ''));
}

function violations(list) {
  return table([
    { label: 'Code', mono: true, get: v => v.code },
    { label: 'Rule', get: v => v.rule },
    { label: 'Message', get: v => v.message },
    { label: 'Severity', get: v => v.severity },
  ], list);
}

VIEWS.upload = async () => {
  const file = h('input', { type: 'file', 'aria-label': 'File to submit' });
  const text = h('textarea', { rows: 16, placeholder: 'Or paste an ISO 20022 XML message or a SWIFT MT message here' });
  const out = h('div', {});
  const send = async () => {
    out.replaceChildren();
    try {
      let raw = text.value;
      let name = 'pasted';
      if (file.files.length) { raw = await file.files[0].text(); name = file.files[0].name; }
      if (!raw.trim()) throw new Error('Choose a file or paste a message first.');
      const r = await api('POST', '/api/inbound?fileName=' + encodeURIComponent(name), undefined, raw);
      out.replaceChildren(h('div', { class: 'note' }, 'Received ' + r.messages.length + ' message(s).'),
        table(messageColumns().slice(0, 4), r.messages, m => go('#/instructions/' + m.id)),
        r.messages.some(m => m.reasonCode) ? h('div', { class: 'note bad', style: 'margin-top:10px' },
          r.messages.filter(m => m.reasonCode).map(m => m.id + ' ' + m.reasonCode + ': ' + m.reasonText).join(' | ')) : null);
    } catch (e) {
      out.replaceChildren(h('div', { class: 'note bad' }, e.message));
    }
  };
  return page('Submit an instruction', 'Format and message type are recognised automatically: ISO 20022 (for example pain.001) or SWIFT MT (for example MT101).',
    h('div', { class: 'panel' }, h('label', {}, 'File'), file, h('label', {}, 'Message text'), text,
      h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'primary', onclick: send }, 'Submit'))), out);
};

const TXN_STATUSES = ['CREATED', 'PROCESSING', 'HELD', 'WAITING', 'WAREHOUSED', 'ROUTED', 'BULKED', 'SENT', 'ACCEPTED', 'CREDITED', 'DEBITED', 'REJECTED_BY_APPLICATION', 'REJECTED_BY_EXTERNAL', 'CANCELLED', 'RETURNED', 'REPAIR'];
VIEWS.transactions = async (id, params) => {
  if (id) return transactionDetail(id);
  return listView({
    title: 'Transactions', hash: '#/transactions', api: '/api/transactions',
    sub: 'Every payment, newest first unless you sort. Search finds whole words in the id, the end-to-end id, names, accounts and the remittance text.',
    search: { label: 'Search payments', placeholder: 'Search id, name, account, remittance…' },
    selects: [{ key: 'status', label: 'All statuses', chip: 'Status', options: options(TXN_STATUSES) },
      { key: 'scheme', label: 'All routes', chip: 'Route', options: options(['ZA-RTC', 'SEPA-SCT', 'SEPA-INST', 'SEPA-SDD', 'SWIFT']) }],
    more: [{ key: 'from', label: 'Received from', short: 'Received from', type: 'date', chip: 'From' }, { key: 'to', label: 'Received to', short: 'to', type: 'date', chip: 'To' },
      { key: 'minAmount', label: 'Amount from', type: 'number', chip: 'At least', attrs: { min: '0', step: 'any', style: 'width:120px' } },
      { key: 'maxAmount', label: 'Amount to', short: 'to', type: 'number', chip: 'At most', attrs: { min: '0', step: 'any', style: 'width:120px' } },
      { key: 'currency', label: 'Currency', attrs: { maxlength: '3', style: 'width:70px', upper: true } }],
    hidden: [{ key: 'instructionId', chip: 'Instruction' }, { key: 'outboundId', chip: 'Outbound file' }],
    empty: 'No payments match these filters.',
    open: t => '#/transactions/' + t.id,
    columns: [
      { label: 'Id', mono: true, nw: true, sort: 'id', get: t => t.id },
      { label: 'End-to-end id', nw: true, sort: 'endToEndId', get: t => t.endToEndId || '' },
      { label: 'Counterparty', get: t => t.paymentType === 'DD' || t.paymentType === 'IN' ? 'from ' + ((t.debtor && t.debtor.name) || '')
        : t.paymentType === 'DD_IN' ? 'collected by ' + ((t.creditor && t.creditor.name) || '') : (t.creditor && t.creditor.name) || '' },
      { label: 'Amount', num: true, nw: true, sort: 'amount', get: t => money(t.amount, t.currency) },
      { label: 'Status', sort: 'status', get: t => badge(t.status) },
      { label: 'Reason', get: t => t.reasonCode ? t.reasonCode + ' ' + (t.reasonText || '') : '' },
      { label: 'Route', nw: true, get: t => (t.route && t.route.scheme) || '' },
      { label: 'Updated', nw: true, sort: 'updatedAt', get: t => when(t.updatedAt) },
    ],
  }, params);
};

function transactionProgress(t) {
  const s = t.status;
  if (t.paymentType === 'IN' || t.paymentType === 'DD_IN') {
    // an incoming payment or collection is received, checked, and then booked on the account or sent back
    const back = !!t.return;
    const stages = ['Received', 'Checks', back ? 'Sent back' : t.paymentType === 'DD_IN' ? 'Debited' : 'Credited'];
    const done = s === 'CREDITED' || s === 'DEBITED' || s === 'RETURNED' ? 3 : ['ROUTED', 'BULKED'].includes(s) ? 2 : 1;
    const state = ['HELD', 'WAITING', 'REPAIR'].includes(s) ? 'warn' : 'active';
    const words = { active: 'in progress', warn: 'waiting' };
    return h('ol', { class: 'progress', 'aria-label': 'Progress of the payment' }, stages.map((name, i) => {
      const cls = i < done ? (i === 2 && back ? 'bad' : 'done') : i === done ? state : 'todo';
      return h('li', { class: cls }, h('span', { class: 'dot', 'aria-hidden': 'true' }, cls === 'done' ? '✓' : cls === 'bad' ? '↩' : String(i + 1)),
        h('span', { class: 'name' }, name), h('span', { class: 'sr' }, cls === 'done' || cls === 'bad' ? ' (done)' : cls === 'todo' ? ' (not yet)' : ' (' + words[state] + ')'));
    }));
  }
  let done = 1, state = 'active';
  if (['HELD', 'WAITING', 'WAREHOUSED', 'REPAIR'].includes(s)) state = 'warn';
  else if (s === 'REJECTED_BY_APPLICATION') state = 'bad';
  else if (['ROUTED', 'BULKED'].includes(s)) done = 2;
  else if (s === 'SENT') done = 3;
  else if (s === 'REJECTED_BY_EXTERNAL') { done = 3; state = 'bad'; }
  else if (s === 'ACCEPTED') { done = 4; state = 'good'; }
  else if (s === 'CANCELLED') { done = t.outboundId ? 3 : 1; state = 'bad'; }
  else if (s === 'RETURNED') { done = 4; state = 'bad'; }
  const stages = ['Received', 'Checks', 'Sent', 'Answer'];
  if (s === 'CANCELLED') stages.splice(done, stages.length - done, 'Cancelled');
  if (s === 'RETURNED') stages.push('Returned');
  const current = s === 'ACCEPTED' ? -1 : Math.min(done, stages.length - 1);
  const words = { active: 'in progress', warn: 'waiting', bad: 'stopped here', good: 'done' };
  return h('ol', { class: 'progress', 'aria-label': 'Progress of the payment' }, stages.map((name, i) => {
    const cls = i < done && i !== current ? 'done' : i === current ? state : 'todo';
    return h('li', { class: cls }, h('span', { class: 'dot', 'aria-hidden': 'true' }, cls === 'done' ? '✓' : cls === 'bad' ? '×' : String(i + 1)),
      h('span', { class: 'name' }, name), h('span', { class: 'sr' }, cls === 'done' ? ' (done)' : cls === 'todo' ? ' (not yet)' : ' (' + words[state] + ')'));
  }));
}

async function transactionDetail(id) {
  const t = await api('GET', '/api/transactions/' + id);
  const party = p => p ? [p.name, p.account, p.agentBic].filter(Boolean).join(' / ') : null;
  const out = h('div', {});
  const request = async (what, body) => {
    try {
      const r = await api('POST', '/api/transactions/' + id + '/' + what, body);
      out.replaceChildren(h('div', { class: 'note good' }, 'Request ', h('a', { href: '#/approvals/' + r.id }, r.id),
        ' was created. It takes effect when another user approves it.'));
    } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  // a repair: the fields an operator may correct, shown with their values; what is changed goes with the request
  const resubmit = () => {
    const fields = [['creditor.name', 'Creditor name'], ['creditor.account', 'Creditor account'], ['creditor.agentBic', 'Creditor bank (BIC)'], ['debtor.name', 'Debtor name'],
      ['remittance', 'Remittance information'], ['requestedDate', 'Requested date'], ['amount', 'Amount'], ['currency', 'Currency'], ['purposeCode', 'Purpose code'], ['chargeBearer', 'Charge bearer']];
    const value = (path) => path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), t);
    const inputs = fields.map(([path, label]) => { const el = h('input', { type: 'text', value: value(path) == null ? '' : String(value(path)) }); el.setAttribute('aria-label', label); return [path, el]; });
    const note = h('textarea', { rows: '2' }); note.setAttribute('aria-label', 'Why (for the approver)');
    const panel = h('div', { class: 'panel' }, h('h3', {}, 'Repair and resubmit'),
      h('p', { class: 'sub' }, 'Correct what was wrong, or leave everything as it is to resubmit unchanged. Only what you change is sent for approval.'),
      h('div', { class: 'formgrid' }, inputs.map(([path, el]) => h('div', {}, h('label', {}, fields.find(f => f[0] === path)[1]), el))),
      h('div', {}, h('label', {}, 'Why (for the approver)'), note),
      h('div', { class: 'row', style: 'margin-top:12px' },
        h('button', { type: 'button', class: 'primary', onclick: () => {
          const changes = {};
          for (const [path, el] of inputs) { const now = value(path) == null ? '' : String(value(path)); if (el.value.trim() !== now) changes[path] = el.value.trim(); }
          request('resubmit', { changes, comment: note.value.trim() });
          panel.remove();
        } }, 'Submit for approval'),
        h('button', { type: 'button', onclick: () => panel.remove() }, 'Cancel')));
    out.replaceChildren(panel);
    inputs[0][1].focus();
  };
  const addNote = async () => {
    const text = prompt('Your note on this payment (kept with it, visible to everyone who may see the payment):');
    if (!text || !text.trim()) return;
    try { await api('POST', '/api/transactions/' + id + '/notes', { text: text.trim() }); refreshMain(); } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const releaseNow = () => request('release-now', { comment: prompt('Why does this payment go now instead of at its time?') || '' });
  const cancel = () => {
    const reason = prompt('Reason for the cancellation (shown to the customer and the receiving side):');
    if (reason !== null) request('cancel', { reasonCode: 'CUST', reasonText: reason || 'Cancelled by the bank', comment: reason });
  };
  const collection = t.paymentType === 'DD_IN';
  const incoming = t.paymentType === 'IN' || collection;
  const refund = () => {
    const reason = prompt(collection ? 'Why is this collection given back to ' + ((t.debtor && t.debtor.name) || 'the customer') + '? The debit of the account is reversed first, then ' + ((t.creditor && t.creditor.name) || 'the creditor') + ' is told.'
      : 'Why is this payment sent back to ' + ((t.debtor && t.debtor.name) || 'the sender') + '? The credit to the account is reversed first.');
    if (reason !== null) request('refund', { reasonText: reason || (collection ? 'Refund requested by the customer' : 'Returned at the request of the customer'), comment: reason });
  };
  const recall = t.recall;
  const recallOpen = recall && recall.status === 'OPEN';
  const answerCase = (c) => {
    const text = prompt(c.kind === 'RFI' ? 'What is the answer? It goes to the other bank as written.' : 'A note for the approver (optional):');
    if (text === null) return;
    let confirmation;
    if (c.kind !== 'RFI') {
      confirmation = prompt('The confirmation code for the other bank: IPAY (the payment was made), MODI (modified), RJCR (refused), RJNR (no such payment)', c.kind === 'MODIFY' ? 'RJCR' : 'IPAY');
      if (confirmation === null) return;
    }
    request('investigations/' + encodeURIComponent(c.caseId) + '/answer', { text, confirmation: confirmation ? confirmation.trim().toUpperCase() : undefined, comment: text });
  };
  const decideRecall = (decision) => {
    const reason = prompt(decision === 'accept' ? 'The payment is taken back from the account and sent back to the sender. Note for the approver:'
      : 'Why is the recall refused? The sender\'s bank is told this reason (for example: the beneficiary does not agree).');
    if (reason === null) return;
    request('recall/' + decision, decision === 'accept' ? { comment: reason } : { reasonCode: 'CUST', reasonText: reason || 'The beneficiary does not agree', comment: reason });
  };
  const review = (what) => {
    const comment = prompt(what === 'release' ? 'Why may this payment go on?' : incoming ? 'Why is this payment sent back?' : 'Why is this payment rejected?');
    if (comment !== null) request(what, { comment });
  };
  const checks = t.checks || {};
  const check = c => c ? [c.status, c.score !== undefined ? 'score ' + c.score : null, c.reason, c.rule,
    c.documentRequired ? 'document required' : null, c.requests ? c.requests + ' request(s)' : null, c.error].filter(Boolean).join(', ') : null;
  const cancellable = !incoming && !['REJECTED_BY_APPLICATION', 'REJECTED_BY_EXTERNAL', 'CANCELLED', 'RETURNED'].includes(t.status);
  const cx = t.cancellation;
  const section = (title, pairs) => {
    const shown = pairs.filter(p => p[1] !== null && p[1] !== undefined && p[1] !== '');
    return shown.length ? h('div', { class: 'panel' }, h('h2', { class: 'first' }, title), kv(shown)) : null;
  };
  const directDebit = t.paymentType === 'DD';
  const stopped = ['REPAIR', 'HELD', 'WAITING', 'WAREHOUSED'].includes(t.status);
  return [
    h('nav', { class: 'crumbs', 'aria-label': 'Where you are' }, h('a', { href: '#/transactions' }, 'Transactions'), h('span', { 'aria-hidden': 'true' }, ' / '), h('span', { class: 'mono' }, t.id)),
    h('div', { class: 'panel hero' },
      h('div', { class: 'hero-main' },
        h('div', {}, h('h1', {}, money(t.amount, t.currency)), h('div', { class: 'hero-sub' }, badge(t.status), ' ', h('span', { class: 'mono' }, t.id),
          t.endToEndId ? [' · end-to-end id ', h('span', { class: 'mono' }, t.endToEndId)] : null)),
        h('div', { class: 'parties' },
          h('div', {}, h('div', { class: 'role' }, directDebit ? 'Collected from' : collection ? 'Debited from our customer' : incoming ? 'Received from' : 'From'), h('b', {}, (t.debtor && t.debtor.name) || 'unknown'), h('div', { class: 'mono' }, (t.debtor && t.debtor.account) || '')),
          h('div', { class: 'arrow', 'aria-hidden': 'true' }, '→'),
          h('div', {}, h('div', { class: 'role' }, directDebit || collection ? 'Collected by' : incoming ? 'For our customer' : 'To'), h('b', {}, (t.creditor && t.creditor.name) || 'unknown'), h('div', { class: 'mono' }, (t.creditor && t.creditor.account) || '')))),
      transactionProgress(t)),
    t.reasonCode ? h('div', { class: 'note ' + (stopped ? 'warn' : 'bad') }, h('b', {}, t.reasonCode), ': ' + (t.reasonText || '')) : null,
    (t.notes || []).length ? h('div', { class: 'panel' }, h('h3', {}, 'Notes'), h('ul', {}, (t.notes || []).map(n => h('li', {}, h('b', {}, n.by), ' (' + when(n.at) + '): ' + n.text)))) : null,
    (t.repairs || []).length ? h('div', { class: 'panel' }, h('h3', {}, 'Repairs'), h('ul', {}, (t.repairs || []).map(r => h('li', {}, h('b', {}, r.by), ' (' + when(r.at) + '): '
      + Object.entries(r.changes || {}).map(([f, c]) => f + ' ' + (c.from == null ? '(empty)' : c.from) + ' → ' + c.to).join('; '))))) : null,
    (t.investigations || []).filter(c => c.status === 'OPEN').map(c => h('div', { class: 'note warn' },
      h('b', {}, c.kind === 'RFI' ? 'Another bank asks for information' : c.kind === 'CLAIM_NON_RECEIPT' ? 'Another bank says its customer did not receive this payment' : 'Another bank asks for a change to this payment'),
      ': ' + (c.text || '') + ' (case ' + c.caseId + ' from ' + c.assignerBic + ', received ' + when(c.receivedAt) + '). ',
      can('payments.repair') ? h('button', { type: 'button', class: 'primary', onclick: () => answerCase(c) }, 'Answer') : null)),
    recallOpen ? h('div', { class: 'note warn' }, h('b', {}, 'The sender\'s bank asks for this payment back'),
      ': ' + [recall.reasonCode, recall.reasonText].filter(Boolean).join(' ') + (recall.dueBy ? ', to be answered by ' + recall.dueBy : '') + ' (case ' + (recall.caseId || recall.cancellationId || '') + ', received ' + when(recall.receivedAt) + '). '
      + (t.status === 'CREDITED' ? 'The money is on the customer\'s account, so someone has to decide.' : 'It can be decided once the payment has been processed.')) : null,
    h('div', { class: 'row', style: 'margin-bottom:12px' },
      t.status === 'REPAIR' && can('payments.repair') ? h('button', { class: 'primary', onclick: resubmit }, 'Repair and resubmit') : null,
      t.status === 'WAREHOUSED' && can('payments.repair') ? h('button', { class: 'primary', onclick: releaseNow }, 'Release now') : null,
      h('button', { type: 'button', onclick: addNote }, 'Add a note'),
      t.status === 'HELD' && can('payments.repair') ? h('button', { class: 'primary', onclick: () => review('release') }, 'Request release') : null,
      t.status === 'HELD' && can('payments.repair') ? h('button', { class: 'danger', onclick: () => review('reject') }, incoming ? 'Request sending back' : 'Request rejection') : null,
      recallOpen && t.status === 'CREDITED' && can('payments.cancel') ? h('button', { class: 'primary', onclick: () => decideRecall('accept') }, 'Request to send it back') : null,
      recallOpen && can('payments.cancel') ? h('button', { onclick: () => decideRecall('refuse') }, 'Request to refuse the recall') : null,
      t.charges && ['REQUESTED', 'OPEN', 'FAILED'].includes(t.charges.claimStatus) && can('payments.repair') ? h('button', { onclick: () => {
        const reference = prompt('The charge claim was paid. Reference of the payment received (for the approver):');
        if (reference !== null) request('charge-claim/paid', { reference, comment: reference });
      } }, 'Request to mark the charge claim as paid') : null,
      incoming && ['CREDITED', 'DEBITED'].includes(t.status) && !recallOpen && can('payments.cancel') ? h('button', { class: 'danger', onclick: refund }, 'Request refund') : null,
      cancellable && can('payments.cancel') ? h('button', { class: 'danger', onclick: cancel }, 'Request cancellation') : null),
    out,
    h('div', { class: 'sections' },
      section('Payment', [
        ['Debtor', party(t.debtor)], ['Creditor', party(t.creditor)],
        ['Payment type', directDebit ? 'Direct debit collection (' + (t.sequenceType || '') + ')' : collection ? 'Incoming direct debit collection (' + (t.sequenceType || '') + ')' : incoming ? 'Incoming credit transfer' : null],
        ['Sent by', incoming ? [t.sendingAgentBic, t.originalMsgId ? 'message ' + t.originalMsgId : null, t.originalTxId ? 'transaction ' + t.originalTxId : null].filter(Boolean).join(', ') : null],
        ['Settlement date', t.settlementDate], ['Credited', when(t.creditedAt)], ['Debited', when(t.debitedAt)],
        ['Mandate', t.mandate ? t.mandate.id + (t.mandate.signedOn ? ', signed ' + t.mandate.signedOn : '') : null],
        ['Creditor identifier', t.creditor && t.creditor.schemeId],
        ['Collection date', t.collectionDate ? t.collectionDate + ' (submitted ' + t.submissionDate + ')' : null],
        ['Requested date', t.requestedDate], ['Value date', t.valueDate], ['Charge bearer', t.chargeBearer], ['Remittance', t.remittance],
        ['Our charge', t.charges && t.charges.deducted !== undefined ? money(t.charges.deducted, t.charges.currency) + ' taken off the amount; the customer was credited ' + money(t.charges.netAmount, t.charges.currency)
          : t.charges && t.charges.claim !== undefined ? [money(t.charges.claim, t.charges.currency) + ' claimed from ' + t.charges.claimFrom + ' (the payer bears all charges): ',
            badge(t.charges.claimStatus || 'OPEN'), t.charges.claimId ? [' in ', h('a', { href: '#/outbound/' + t.charges.claimId }, t.charges.claimId)] : null,
            t.charges.claimProblem ? ' ' + t.charges.claimProblem : null, t.charges.claimPaidReference ? ', paid: ' + t.charges.claimPaidReference : null] : null],
        ['Charges of other banks', t.charges && (t.charges.sendersCharges || t.charges.prepaid) ? [t.charges.sendersCharges ? 'deducted before us: ' + t.charges.sendersCharges : null,
          t.charges.prepaid ? 'prepaid for us: ' + t.charges.prepaid : null].filter(Boolean).join(', ') : null],
        ['Exchange', t.fx && t.fx.rate ? (t.fx.creditAmount !== undefined ? 'credited as ' + money(t.fx.creditAmount, t.fx.creditCurrency) : money(t.fx.debitAmount, t.fx.debitCurrency)) + ' at ' + t.fx.rate : null]]),
      section('Checks', [
        ['Debtor account', check(checks.account)], ['Sanctions screening', t.screening ? [t.screening.status, t.screening.list].filter(Boolean).join(', ') : null],
        ['Fraud check', check(checks.fraud)], ['Compliance check', check(checks.compliance)],
        ['Supporting document', checks.document ? checks.document.name + ' uploaded by ' + checks.document.uploadedBy : null],
        ['Liquidity', check(checks.liquidity)],
        ['Held since', t.hold ? when(t.hold.since) : null],
        ['Held in queue', t.hold && t.hold.queue ? t.hold.queue : null],
        ['Instructions', t.hold && t.hold.instructions ? t.hold.instructions : null],
        ['Waiting', t.wait ? 'for ' + t.wait.code + ' since ' + when(t.wait.since) + (t.wait.retryAt ? ', next try ' + when(t.wait.retryAt) : ', until an answer arrives') : null],
        ['Warehoused', t.warehouse ? 'until ' + when(t.warehouse.until) + (t.warehouse.reason ? ': ' + t.warehouse.reason : '') : null],
        ['Holds released', t.overrides && t.overrides.length ? t.overrides.join(', ') : null],
        ['Automatic retry', t.retry ? 'attempt ' + t.retry.count + (t.retry.nextAt ? ', next at ' + when(t.retry.nextAt) : ', no further automatic attempt') : null]]),
      section('Route and settlement', [
        ['Route', t.route ? t.route.scheme + ' via ' + t.route.channel : null],
        ['Method of payment', t.route && t.route.methodOfPayment],
        ['Settlement method', t.route && t.route.settlementMethod ? t.route.settlementMethod + ' through ' + t.route.correspondentBic : null],
        ['UETR', t.uetr],
        ['Posting', t.posting ? [t.posting.status, t.posting.postingId, t.posting.creditAccount ? 'against ' + t.posting.creditAccount : null,
          t.posting.reversal ? 'reversal ' + t.posting.reversal.reversalId : null, t.posting.error].filter(Boolean).join(', ') : null],
        ['Reconciled', t.reconciliation ? h('a', { href: '#/statements/' + t.reconciliation.statementId }, 'statement ' + t.reconciliation.statementId) : null],
        ['Return', t.return ? (t.return.reasonCode ? 'sent back: ' + t.return.reasonCode + ' ' + (t.return.reasonText || '') + ', decided by ' + t.return.requestedBy
          : money(t.return.amount, t.return.currency) + ' in ' + t.return.messageId) : null],
        ['Cancellation', cx ? cx.status + ': ' + cx.reasonCode + ' ' + (cx.reasonText || '') + ', requested by ' + cx.requestedBy
          + (cx.resolutionCode ? ', refused with ' + cx.resolutionCode : '') : null],
        ['Recall by the sender', recall ? [recall.status === 'OPEN' ? 'waiting for a decision' : recall.status === 'ACCEPTED' ? 'accepted, the payment is sent back' : 'refused',
          recall.reasonCode, recall.reasonText, recall.caseId ? 'case ' + recall.caseId : null, recall.decidedBy ? 'decided by ' + recall.decidedBy : null,
          recall.answerCode ? 'answered ' + recall.answerCode + ' ' + (recall.answerText || '') : null].filter(Boolean).join(', ') : null],
        ['Recall answer', recall && recall.answerId ? h('a', { href: '#/outbound/' + recall.answerId }, recall.answerId) : null],
        ['Cancellation request sent', cx && cx.outboundId ? h('a', { href: '#/outbound/' + cx.outboundId }, cx.outboundId) : null]]),
      section('References', [
        ['Instruction', h('a', { href: '#/instructions/' + t.instructionId }, t.instructionId)],
        ['Batch', t.batchId], ['Received on', t.channelIn],
        ['Outbound file', t.outboundId ? h('a', { href: '#/outbound/' + t.outboundId }, t.outboundId) : null],
        ['Acknowledgement', t.acknowledgementId ? h('a', { href: '#/instructions/' + t.acknowledgementId }, t.acknowledgementId + ' (' + t.externalStatus + ')') : null],
        ['Reported to customer as', t.reportedStatus],
        ['Customer notified', t.notify && (t.notify.sent || []).length ? t.notify.sent.map((n, i) => [i ? ', ' : '',
          ({ CREDIT: 'credit', DEBIT: 'debit', CREDIT_REVERSAL: 'reversal of the credit', DEBIT_REVERSAL: 'reversal of the debit' })[n.kind] || n.kind, ' in ',
          h('a', { href: '#/outbound/' + n.outboundId }, n.outboundId)]) : null],
        ['Notification', t.notify && t.notify.state === 'SKIPPED' ? 'none: the customer does not want notifications for this account' : t.notify && t.notify.state === 'FAILED' ? 'could not be written: ' + (t.notify.problem || '') : t.notify && (t.notify.pending || []).length ? 'waiting to be sent' : null],
        ['Received', when(t.createdAt)], ['Last change', when(t.updatedAt)],
        ['Processed by deployment', t.deployment]])),
    t.violations && t.violations.length ? [h('h2', {}, 'Validation'), violations(t.violations)] : null,
    h('h2', {}, 'History'), h('div', { class: 'panel' }, timeline(t.events))];
}

VIEWS.review = async () => {
  live.handler = { kinds: ['payments'], run: refreshMain };
  const items = (await api('GET', '/api/transactions?status=HELD')).items;
  const waiting = (await api('GET', '/api/transactions?status=WAITING')).items;
  const recalls = (await api('GET', '/api/transactions?recall=OPEN')).items;
  const overdue = (await api('GET', '/api/transactions?status=SENT&overdue=true')).items;
  const investigated = (await api('GET', '/api/transactions?investigation=true')).items;
  const requests = (await api('GET', '/api/requests-to-pay?status=PENDING')).items;
  const warehoused = (await api('GET', '/api/transactions?status=WAREHOUSED')).items;
  const columns = [
    { label: 'Id', mono: true, get: t => t.id },
    { label: 'Creditor', get: t => (t.creditor && t.creditor.name) || '' },
    { label: 'Amount', num: true, get: t => money(t.amount, t.currency) },
    { label: 'Reason', get: t => t.reasonCode + ': ' + (t.reasonText || '') },
    { label: 'Since', get: t => when((t.hold && t.hold.since) || (t.wait && t.wait.since)) },
  ];
  const decideRequest = async (r, accept) => {
    const reason = prompt(accept ? 'A note for the approver (optional):' : 'Why is it refused? The creditor\'s bank is told this reason.');
    if (reason === null) return;
    try {
      const a = await api('POST', '/api/requests-to-pay/' + r.id + (accept ? '/accept' : '/refuse'), accept ? { comment: reason } : { reasonCode: 'CUST', reasonText: reason, comment: reason });
      alert('Request ' + a.id + ' takes effect when another user approves it.');
    } catch (e) { alert(e.message); }
  };
  return page('Review queue', 'Payments a check has stopped. Open one to see what the check said, then request its release or rejection; a second person approves the decision.',
    h('div', { class: 'row', style: 'margin-bottom:12px' }, h('button', { onclick: render }, 'Refresh')),
    h('h2', {}, 'Held for a decision'),
    // a task step names the operator queue a payment waits in: those are listed per queue, with the instructions, before the rest
    ...[...new Set(items.filter(t => t.hold && t.hold.queue).map(t => t.hold.queue))].sort().map(queue => [
      h('h3', {}, 'Queue: ' + queue),
      table([...columns, { label: 'Instructions', get: t => (t.hold && t.hold.instructions) || '' }], items.filter(t => t.hold && t.hold.queue === queue), t => go('#/transactions/' + t.id))]),
    table(columns, items.filter(t => !(t.hold && t.hold.queue)), t => go('#/transactions/' + t.id)),
    h('h2', {}, 'Recalls of the sender\'s bank waiting for a decision'),
    h('p', { class: 'sub' }, 'Payments we received and credited that the sender\'s bank wants back. Open one to send it back or to refuse.'),
    table([columns[0], { label: 'From', get: t => (t.debtor && t.debtor.name) || '' }, columns[2],
      { label: 'Reason', get: t => [t.recall.reasonCode, t.recall.reasonText].filter(Boolean).join(' ') }, { label: 'Asked', get: t => when(t.recall.receivedAt) },
      { label: 'Answer by', get: t => t.recall.dueBy ? [t.recall.dueBy, ' ', t.recall.dueBy < new Date().toISOString().substring(0, 10) ? badge('OVERDUE') : null] : '' }],
      recalls, t => go('#/transactions/' + t.id)),
    overdue.length ? [h('h2', {}, 'Sent, and not answered in time'),
      h('p', { class: 'sub' }, 'Payments on a rail that answers within seconds, still without an answer. Ask the clearing what became of them; only its answer decides.'),
      table([columns[0], columns[1], columns[2], { label: 'Route', get: t => (t.route && t.route.scheme) || '' }, { label: 'Sent', get: t => when(t.sentAt) }],
        overdue, t => go('#/transactions/' + t.id))] : null,
    requests.length ? [h('h2', {}, 'Requests to pay waiting for the customer'),
      h('p', { class: 'sub' }, 'A creditor asks our customer to pay. The customer accepts or refuses, here on the customer\'s behalf or through the API; the creditor\'s bank is told either way, and an accepted request becomes a payment.'),
      table([
        { label: 'Request', mono: true, nw: true, get: r => r.id },
        { label: 'Customer', get: r => (r.debtor && r.debtor.name) || '' },
        { label: 'Creditor', get: r => (r.creditor && r.creditor.name) || '' },
        { label: 'Amount', num: true, nw: true, get: r => money(r.amount, r.currency) },
        { label: 'For', get: r => r.remittance || '' },
        { label: 'Answer by', nw: true, get: r => r.expiryDate || '' },
        { label: '', get: r => can('payments.approve') ? h('span', {}, h('button', { type: 'button', class: 'primary', onclick: () => decideRequest(r, true) }, 'Accept'), ' ',
          h('button', { type: 'button', class: 'danger', onclick: () => decideRequest(r, false) }, 'Refuse')) : '' },
      ], requests)] : null,
    investigated.length ? [h('h2', {}, 'Investigations from other banks'),
      h('p', { class: 'sub' }, 'Another bank asks about a payment: for information, because its customer did not receive the funds, or for a change. Open the payment to answer.'),
      table([columns[0], columns[1], columns[2], { label: 'Asked', get: t => (t.investigations || []).filter(c => c.status === 'OPEN').map(c => c.kind + ' by ' + c.assignerBic).join('; ') }],
        investigated, t => go('#/transactions/' + t.id))] : null,
    h('h2', {}, 'Waiting for an answer or a retry'), h('p', { class: 'sub' }, 'These continue by themselves. A payment still waiting after the time limit goes to repair.'),
    table(columns, waiting, t => go('#/transactions/' + t.id)),
    h('h2', {}, 'Warehoused until a date or an opening time'),
    table([...columns.slice(0, 4), { label: 'Released', get: t => when(t.warehouse && t.warehouse.until) }], warehoused, t => go('#/transactions/' + t.id)));
};

VIEWS.outbound = async (id, params) => {
  if (id) {
    const o = await api('GET', '/api/outbound/' + id);
    return page(o.id, o.messageType + ' on ' + o.channel,
      o.reasonText ? h('div', { class: 'note bad' }, o.reasonText) : null,
      h('div', { class: 'panel' }, kv([
        ['Status', badge(o.status)], ['Transactions', h('a', { href: '#/transactions?outboundId=' + o.id }, String(o.transactionCount))],
        ['Total', o.totalAmount === undefined ? null : money(o.totalAmount, o.currency)],
        ['For instruction', o.instructionId ? h('a', { href: '#/instructions/' + o.instructionId }, o.instructionId) : null], ['Created', when(o.createdAt)], ['Sent', when(o.sentAt)], ['Delivered to', o.location],
        ['Acknowledgement', o.acknowledgementId ? h('a', { href: '#/instructions/' + o.acknowledgementId }, o.acknowledgementId) : null],
      ])),
      h('h2', {}, 'History'), h('div', { class: 'panel' }, timeline(o.events)),
      h('h2', {}, 'Message as sent'), h('pre', {}, o.payload || ''));
  }
  return listView({
    title: 'Outbound files', hash: '#/outbound', api: '/api/outbound',
    actions: can('payments.repair') ? [
      h('button', { class: 'danger', onclick: async () => { const why = prompt('Stop sending on every outbound channel. Why? (for the approver)'); if (why === null) return;
        try { const r = await api('POST', '/api/channels/stop-all', { comment: why }); alert('Request ' + r.id + ' stops all sending when another user approves it.'); } catch (e) { alert(e.message); } } }, 'Stop all sending'),
      h('button', { onclick: async () => { const why = prompt('Resume sending on every outbound channel. Why? (for the approver)'); if (why === null) return;
        try { const r = await api('POST', '/api/channels/resume-all', { comment: why }); alert('Request ' + r.id + ' resumes sending when another user approves it.'); } catch (e) { alert(e.message); } } }, 'Resume all sending')] : null,
    sub: 'Bulks built from routed transactions and sent to external systems, status reports to customers and cancellation requests.',
    search: { label: 'Search outbound files', placeholder: 'Search id, channel, message type, destination…' },
    selects: [{ key: 'kind', label: 'All kinds', chip: 'Kind', options: [['payment', 'Payments'], ['statusReport', 'Status report'], ['cancellation', 'Cancellation request'], ['recallAnswer', 'Recall answer'], ['notification', 'Customer notification'], ['chargeClaim', 'Charge claim'], ['cancellationAnswer', 'Answer to a cancellation request'], ['confirmation', 'Confirmation'], ['accountReport', 'Account report'], ['accountStatement', 'Account statement'], ['requestToPayAnswer', 'Answer to a request to pay'], ['investigationAnswer', 'Answer to an investigation'], ['statusEnquiry', 'Status enquiry'], ['statusAnswer', 'Answer to a status enquiry']] },
      { key: 'status', label: 'All statuses', chip: 'Status', options: options(['CREATED', 'SENT', 'ACKNOWLEDGED', 'FAILED']) }],
    more: DATE_FILTERS,
    empty: 'No outbound files match these filters.',
    open: o => '#/outbound/' + o.id,
    columns: [
      { label: 'Id', mono: true, nw: true, sort: 'id', get: o => o.id },
      { label: 'Kind', get: o => ({ payment: 'Payments', statusReport: 'Status report', cancellation: 'Cancellation request', recallAnswer: 'Recall answer', notification: 'Customer notification', chargeClaim: 'Charge claim', cancellationAnswer: 'Answer to a cancellation request', confirmation: 'Confirmation', accountReport: 'Account report', accountStatement: 'Account statement', requestToPayAnswer: 'Answer to a request to pay', investigationAnswer: 'Answer to an investigation', statusEnquiry: 'Status enquiry', statusAnswer: 'Answer to a status enquiry' })[o.kind] || 'Payments' },
      { label: 'Type', nw: true, get: o => o.messageType },
      { label: 'Channel', get: o => (o.channel || '').replace('channels.', '') },
      { label: 'Transactions', num: true, sort: 'transactionCount', get: o => o.transactionCount },
      { label: 'Total', num: true, nw: true, sort: 'totalAmount', get: o => o.totalAmount === undefined ? '' : money(o.totalAmount, o.currency) },
      { label: 'Status', sort: 'status', get: o => badge(o.status) },
      { label: 'Created', nw: true, sort: 'createdAt', get: o => when(o.createdAt) },
    ],
  }, params || new URLSearchParams());
};

const PURPOSES = { recall: 'Recall request', reversal: 'Reversal of a collection', callback: 'Late answer', statement: 'Statement', acknowledgement: 'Acknowledgement', cancellation: 'Cancellation request', resolution: 'Cancellation answer', return: 'Return of funds' };

VIEWS.responses = async (id, params) => {
  const summary = m => [
    m.acceptedCount !== undefined ? m.acceptedCount + ' accepted, ' + m.rejectedCount + ' rejected' : null,
    m.cancelledCount !== undefined ? m.cancelledCount + ' cancelled' : null,
    m.forwardedCount ? m.forwardedCount + ' forwarded' : null,
    m.refusedCount ? m.refusedCount + ' refused' : null,
    m.returnedCount !== undefined ? m.returnedCount + (m.openCount !== undefined ? ' sent back' : ' returned') : null,
    m.openCount !== undefined ? m.openCount + ' waiting for a decision' : null,
    m.matchedCount !== undefined ? m.matchedCount + ' matched, ' + m.mismatchedCount + ' mismatched' : null,
    m.resumedCount !== undefined ? m.resumedCount + ' resumed' : null,
    m.unmatched && m.unmatched.length ? m.unmatched.length + ' unmatched' : null,
  ].filter(Boolean).join(', ');
  return listView({
    title: 'Responses and requests', hash: '#/responses', api: '/api/messages',
    sub: 'Messages about payments already submitted: acknowledgements and returns from external systems, cancellation requests from customers and their answers.',
    fixed: { purpose: Object.keys(PURPOSES).join(',') },
    search: { label: 'Search responses', placeholder: 'Search id, message id, file name, type…' },
    selects: [{ key: 'purpose', label: 'All kinds', chip: 'Kind', options: Object.entries(PURPOSES) }],
    more: DATE_FILTERS,
    sort: ['receivedAt', 'desc'],
    empty: 'No responses match these filters.',
    open: m => '#/instructions/' + m.id,
    columns: [
      { label: 'Id', mono: true, nw: true, sort: 'id', get: m => m.id },
      { label: 'Kind', get: m => PURPOSES[m.purpose] || m.purpose },
      { label: 'Type', nw: true, sort: 'messageType', get: m => m.messageType || '' },
      { label: 'Status', sort: 'status', get: m => badge(m.status) },
      { label: 'Outcome', get: summary },
      { label: 'Received', nw: true, sort: 'receivedAt', get: m => when(m.receivedAt) },
    ],
  }, params);
};

VIEWS.statements = async (id, params) => {
  if (id) {
    const s = await api('GET', '/api/statements/' + id);
    const out = h('div', { 'aria-live': 'polite' });
    // an entry the engine could not match: a person names the payment it is, a second person approves
    const match = async (e, suggested) => {
      const txnId = suggested || prompt('Which payment is this entry? Its id (ORVTXN…):');
      if (!txnId) return;
      const note = prompt('Why does it match? (for the approver)');
      if (note === null) return;
      try {
        const r = await api('POST', '/api/statements/' + id + '/entries/' + (s.entries || []).indexOf(e) + '/match', { transactionId: txnId.trim(), note, comment: note });
        out.replaceChildren(h('div', { class: 'note good' }, 'Request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' takes effect when another user approves it.'));
      } catch (x) { out.replaceChildren(h('div', { class: 'note bad' }, x.message)); }
    };
    return page(s.id, 'Statement ' + (s.statementId || '') + ' of account ' + s.account,
      s.balanceCheck === 'FAILED' ? h('div', { class: 'note bad' }, 'Opening balance plus the entries does not give the closing balance. The statement may be incomplete.') : null,
      h('div', { class: 'panel' }, kv([
        ['Account', s.account], ['Opening balance', money(s.openingBalance, s.currency)], ['Movement', money(s.movement, s.currency)],
        ['Closing balance', money(s.closingBalance, s.currency)], ['Balance check', badge(s.balanceCheck)],
        ['Received', when(s.receivedAt)], ['Message', h('a', { href: '#/instructions/' + s.messageId }, s.messageId)],
      ])),
      h('h2', {}, 'Entries'),
      out,
      table([
        { label: 'Reference', mono: true, get: e => e.reference || '' },
        { label: 'Amount', num: true, get: e => (e.creditDebit === 'DBIT' ? '-' : '') + money(e.amount, e.currency) },
        { label: 'Value date', get: e => e.valueDate || '' },
        { label: 'Result', get: e => badge(e.status) },
        { label: 'Payment', get: e => e.transactionId ? h('a', { href: '#/transactions/' + e.transactionId }, e.transactionId) : '' },
        { label: 'Narrative', get: e => e.problem || e.narrative || '' },
        { label: '', get: (e, i) => e.status !== 'MATCHED' && can('payments.repair') ? [h('button', { type: 'button', onclick: () => match(e) }, 'Match to a payment'), ' ',
          h('button', { type: 'button', onclick: async () => {
            try {
              const found = (await api('GET', '/api/statements/' + id + '/entries/' + (s.entries || []).indexOf(e) + '/candidates')).items;
              out.replaceChildren(h('div', { class: 'panel' }, h('h3', {}, 'Payments this entry could be'),
                found.length ? table([{ label: 'Payment', mono: true, get: c => h('a', { href: '#/transactions/' + c.id }, c.id) }, { label: 'Creditor', get: c => c.creditor || '' },
                  { label: 'Amount', num: true, get: c => money(c.amount, c.currency) }, { label: 'Status', get: c => badge(c.status) }, { label: 'Why', get: c => c.why },
                  { label: '', get: c => h('button', { type: 'button', class: 'primary', onclick: () => match(e, c.id) }, 'Match this') }], found)
                  : h('p', { class: 'sub' }, 'No payment with this amount and currency around the value date is waiting to be matched.')));
            } catch (x) { out.replaceChildren(h('div', { class: 'note bad' }, x.message)); }
          } }, 'Suggest')] : (e.matchedBy ? 'by ' + e.matchedBy : '') },
      ], s.entries || []));
  }
  return listView({
    title: 'Statements', hash: '#/statements', api: '/api/statements',
    sub: 'Statements of our accounts at correspondents and clearing systems, reconciled against the payments sent.',
    search: { label: 'Search statements', placeholder: 'Search statement id, account, message…' },
    selects: [{ key: 'balanceCheck', label: 'Any balance check', chip: 'Balance check', options: [['OK', 'Balances agree'], ['FAILED', 'Balances do not agree']] }],
    more: [{ key: 'from', label: 'Received from', type: 'date', chip: 'From' }, { key: 'to', label: 'Received to', type: 'date', chip: 'To' }],
    sort: ['receivedAt', 'desc'], live: ['payments'],
    empty: 'No statement matches these filters.',
    open: st => '#/statements/' + st.id,
    columns: [
      { label: 'Id', mono: true, nw: true, sort: 'id', get: st => st.id },
      { label: 'Account', mono: true, sort: 'account', get: st => st.account },
      { label: 'Entries', num: true, sort: 'entryCount', get: st => st.entryCount },
      { label: 'Opening', num: true, nw: true, get: st => money(st.openingBalance, st.currency) },
      { label: 'Closing', num: true, nw: true, sort: 'closingBalance', get: st => money(st.closingBalance, st.currency) },
      { label: 'Balance check', get: st => badge(st.balanceCheck) },
      { label: 'Received', nw: true, sort: 'receivedAt', get: st => when(st.receivedAt) },
    ],
  }, params);
};

VIEWS.deadletters = async (id, params) => {
  const requeue = async (d) => {
    try { await api('POST', '/api/deadletters/' + d.id + '/requeue'); refreshMain(); } catch (e) { alert(e.message); }
  };
  if (!params.get('status')) params.set('status', 'OPEN');
  return listView({
    title: 'Failed events', hash: '#/deadletters', api: '/api/deadletters',
    sub: 'Internal events whose handler failed three times. The work they announced did not happen. Requeue an entry after the cause is fixed.',
    search: { label: 'Search failed events', placeholder: 'Search event, id it is about, error…' },
    selects: [{ key: 'status', label: 'Open and requeued', chip: 'Status', options: [['OPEN', 'Open'], ['REQUEUED', 'Requeued'], ['OPEN,REQUEUED', 'Open and requeued']] }],
    more: [{ key: 'from', label: 'From', type: 'date', chip: 'From' }, { key: 'to', label: 'To', type: 'date', chip: 'To' }],
    sort: ['at', 'desc'],
    empty: 'No failed event matches these filters.',
    columns: [
      { label: 'When', nw: true, sort: 'at', get: d => when(d.at) },
      { label: 'Event', mono: true, sort: 'topic', get: d => d.topic },
      { label: 'About', mono: true, get: d => d.refId || '' },
      { label: 'Error', get: d => d.error },
      { label: 'Status', sort: 'status', get: d => badge(d.status) },
      { label: '', get: d => d.status === 'OPEN' ? h('button', { type: 'button', onclick: (e) => { e.stopPropagation(); requeue(d); } }, 'Requeue') : (d.requeuedBy || '') },
    ],
  }, params);
};

// ---------- studio ----------

const NEW_MODEL = 'kind: RuleSet\nname: payments.rules.MyNewRules\ndescription: What these rules check.\nrules:\n  - id: EXAMPLE\n    assert: txn.amount > 0\n    code: AM01\n    message: Amount must be greater than zero\n';
const NEW_FLOW = 'kind: Flow\nname: payments.flows.MyNewFlow\ndescription: What this flow does.\ncompleteStatus: ROUTED\nsteps: []\n';
const NEW_RULES = 'kind: RuleSet\nname: payments.rules.MyNewRules\ndescription: What these rules check.\nrules: []\n';
const NEW_MAPPING = 'kind: Mapping\nname: payments.maps.MyNewMapping\ndescription: What this mapping builds.\nrules: []\n';
const NEW_TABLE = 'kind: DecisionTable\nname: payments.routing.MyNewTable\ndescription: What this table decides.\nrows: []\n';
const NEW_SPEC = 'kind: MessageSpec\nname: specs.swift.MyNewSpec\ndescription: Field rules of this message type.\nformat: swift.mt\nmessageType: MT103\nsequences:\n  - id: A\n    fields:\n      - {tag: "20", name: sender reference, required: true, format: 16x}\n';
const studio = { tab: 'model', draft: {} };
function newModel(text, tab) {
  if (text) studio.draft.new = text; else delete studio.draft.new;
  studio.tab = tab;
  if (location.hash === '#/studio/new') render(); else go('#/studio/new');
}

VIEWS.studio = async (name) => {
  const list = await api('GET', '/api/studio/models');
  const kinds = {};
  for (const m of list.items) (kinds[m.kind] = kinds[m.kind] || []).push(m);
  const order = ['Flow', 'RuleSet', 'Mapping', 'DecisionTable', 'Api', 'DataSet', 'ReferenceTable', 'Channel', 'Connector', 'MessageSpec', 'Format', 'Schedule', 'TestCase'];
  const models = h('div', { class: 'models' },
    can('studio.edit') ? h('button', { style: 'width:100%', onclick: () => newModel(null, 'model') }, 'New model') : null,
    can('studio.edit') ? h('div', { class: 'newkinds' },
      h('button', { onclick: () => newModel(NEW_FLOW, 'design') }, 'New flow'),
      h('button', { onclick: () => newModel(NEW_RULES, 'design') }, 'New rule set'),
      h('button', { onclick: () => newModel(NEW_TABLE, 'design') }, 'New decision table'),
      h('button', { onclick: () => newModel(NEW_MAPPING, 'design') }, 'New mapping'),
      h('button', { onclick: () => newModel(NEW_SPEC, 'design') }, 'New message specification')) : null,
    order.filter(k => kinds[k]).map(k => [h('div', { class: 'kind' }, k),
      kinds[k].sort((a, b) => a.name.localeCompare(b.name)).map(m => h('a', { class: m.name === name ? 'on' : '', href: '#/studio/' + m.name, title: m.description || '' }, m.name))]));
  const header = page('Studio', 'Low-code models of deployment ' + list.deployment + ' (version ' + list.version
    + '). A change is compiled, then goes to a second person for approval before it is deployed.',
    h('p', { class: 'sub' }, h('a', { href: '/api/openapi.json', target: '_blank', rel: 'noopener', style: 'text-decoration: underline' }, 'OpenAPI description of the Api models'), ' for tools and partners.'));
  if (!name) {
    return [...header, h('div', { class: 'studio' }, models, h('div', { class: 'panel' },
      h('p', {}, 'Choose a model on the left.'),
      h('p', {}, h('button', { onclick: runAllTests }, 'Run all model tests')), h('div', { id: 'testout' })))];
  }
  const model = name === 'new' ? { name: 'new', kind: '', text: NEW_MODEL, generated: null, definition: {} } : await api('GET', '/api/studio/models/' + name);
  return [...header, h('div', { class: 'studio' }, models, editor(model, list.items))];
};

async function runAllTests() {
  const out = document.getElementById('testout');
  out.replaceChildren('Running…');
  try {
    const r = await api('POST', '/api/studio/tests');
    out.replaceChildren(h('div', { class: 'note ' + (r.failed ? 'bad' : 'good') }, r.total + ' test(s), ' + r.failed + ' failed'),
      table([{ label: 'Test', get: t => t.name }, { label: 'Target', get: t => t.target },
        { label: 'Result', get: t => badge(t.passed ? 'ACCEPTED' : 'FAILED') }, { label: 'Failures', get: t => t.failures.join(' | ') }], r.results));
  } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
}

function editor(model, models) {
  const isNew = model.name === 'new';
  const text = h('textarea', { rows: 26, spellcheck: 'false', 'aria-label': 'Model text', value: studio.draft[model.name] ?? model.text });
  text.addEventListener('input', () => { studio.draft[model.name] = text.value; });
  const out = h('div', {});
  const changed = () => text.value !== model.text || isNew;
  const problems = r => r.ok
    ? h('div', { class: 'note good' }, 'The model compiles together with the rest of the deployment.')
    : h('div', {}, h('div', { class: 'note bad' }, r.problems.length + ' problem(s). Nothing was changed.'),
      table([{ label: 'Model', get: p => p.element }, { label: 'Where', get: p => p.where }, { label: 'Problem', get: p => p.message }], r.problems));
  const act = async (fn) => { out.replaceChildren('Working…'); try { await fn(); } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); } };
  // the designer writes its changes into the text; an action waits until the last change has arrived there
  let design = null, dirty = false, writing = null;
  const writeBack = () => {
    dirty = true;
    writing = writing || (async () => {
      try {
        while (dirty) {
          dirty = false;
          text.value = (await api('POST', '/api/studio/render', { definition: design })).text;
          studio.draft[model.name] = text.value;
        }
      } finally { writing = null; }
    })();
    return writing;
  };
  const validate = () => act(async () => { await writing; out.replaceChildren(problems(await api('POST', '/api/studio/validate', { text: text.value }))); });
  const submit = () => act(async () => {
    await writing;
    const comment = prompt('Describe the change for the approver:');
    if (comment === null) { out.replaceChildren(); return; }
    const r = await api('POST', '/api/studio/changes', { text: text.value, comment });
    if (r.problems) { out.replaceChildren(problems(r)); return; }
    delete studio.draft[model.name];
    out.replaceChildren(h('div', { class: 'note good' }, 'Change request ', h('a', { href: '#/approvals/' + r.id }, r.id),
      ' was created. It is deployed when another user approves it.'));
  });

  const scope = h('textarea', { rows: 12, spellcheck: 'false', value: studio.scope || '{\n  "txn": {\n    "id": "ORVTXN0000000001",\n    "endToEndId": "E2E-1",\n    "amount": 100.00,\n    "currency": "ZAR",\n    "debtor": {"name": "Debtor", "account": "4051122334"},\n    "creditor": {"name": "Creditor", "account": "62011223344", "agentBic": "FIRNZAJJ"}\n  }\n}' });
  const mocks = h('textarea', { rows: 4, spellcheck: 'false', value: studio.mocks || '{\n  "connectors.SanctionsScreening": {"status": "CLEAR"}\n}' });
  const seed = h('textarea', { rows: 4, spellcheck: 'false', value: studio.seed || '{\n  "data.AccountLimits": []\n}' });
  const runOut = h('div', {});
  const run = async () => {
    runOut.replaceChildren('Running…');
    try {
      await writing;
      studio.scope = scope.value; studio.mocks = mocks.value; studio.seed = seed.value;
      const body = { target: isNew ? undefined : model.name, scope: JSON.parse(scope.value || '{}'), mocks: JSON.parse(mocks.value || '{}'),
        data: JSON.parse(seed.value || '{}') };
      if (changed()) body.text = text.value;
      const r = await api('POST', '/api/studio/run', body);
      if (!r.ok) { runOut.replaceChildren(problems(r)); return; }
      const res = r.result || {};
      runOut.replaceChildren(
        res.status ? h('div', { class: 'note' }, 'Outcome ', badge(res.status), res.code ? ' ' + res.code + ': ' + res.message : '') : null,
        res.trace ? trace(res.trace) : null,
        h('h2', {}, 'Result'), h('pre', {}, JSON.stringify(res, null, 2)),
        h('h2', {}, 'Scope after the run'), h('pre', {}, JSON.stringify(r.scope, null, 2)),
        r.stored && Object.keys(r.stored).length ? [h('h2', {}, 'Data sets after the run'), h('pre', {}, JSON.stringify(r.stored, null, 2))] : null);
    } catch (e) { runOut.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };

  const steps = (model.definition && model.definition.steps) || null;
  const diagram = steps ? h('div', { class: 'steps', style: 'margin-bottom:12px' }, steps.flatMap((s, i) => [
    i ? h('span', { class: 'arrow' }, '→') : null,
    h('div', { class: 'step' }, h('b', {}, s.id), s.type + (s.ref ? ': ' + s.ref.split('.').pop() : s.connector ? ': ' + s.connector.split('.').pop() : ''),
      s.when ? h('div', {}, 'when ' + s.when) : null)])) : null;

  const remove = () => act(async () => {
    const comment = prompt('Why remove ' + model.name + '? (for the approver)');
    if (comment === null) { out.replaceChildren(); return; }
    const r = await api('POST', '/api/studio/removals', { name: model.name, comment });
    out.replaceChildren(r.problems
      ? h('div', {}, h('div', { class: 'note bad' }, 'The model is still used. Nothing was changed.'),
        table([{ label: 'Used by', get: p => p.element }, { label: 'Where', get: p => p.where }, { label: 'Problem', get: p => p.message }], r.problems))
      : h('div', { class: 'note good' }, 'Removal request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' was created. The model is removed when another user approves it.'));
  });
  const actions = (hint) => h('div', { class: 'row', style: 'margin-top:10px' },
    h('button', { onclick: validate }, 'Validate'),
    can('studio.edit') ? h('button', { class: 'primary', onclick: submit }, 'Submit for approval') : null,
    can('studio.edit') && !isNew ? h('button', { class: 'danger', onclick: remove }, 'Request removal') : null,
    h('span', { class: 'spacer' }), h('span', { style: 'color:var(--muted)' }, hint));
  const designer = () => MODEL_DESIGNERS[(/^kind:\s*(\w+)\s*$/m.exec(text.value) || [])[1]];
  const designPane = h('div', {});
  const openDesign = async () => {
    designPane.replaceChildren('Loading…');
    try {
      await writing;
      design = (await api('POST', '/api/studio/parse', { text: text.value })).definition;
      designPane.replaceChildren(designer()(design, models, writeBack),
        actions('Comments in the model text are not kept once it is changed here.'));
    } catch (e) { designPane.replaceChildren(h('div', { class: 'note bad' }, 'The model text cannot be shown as a design: ' + e.message)); }
  };
  const panes = {
    model: h('div', {}, diagram, text,
      actions('YAML. Expressions: paths, and/or/not, == != < >, in [..], functions such as exists(), isBic(), sumOf().')),
    design: designPane,
    java: h('div', {}, h('p', { class: 'sub' }, 'The Java class Forge generated from the deployed version of this model. Read only.'),
      h('pre', {}, model.generated || 'This kind of model is configuration and has no generated code.')),
    run: h('div', {}, h('p', { class: 'sub' }, 'Runs this model' + (isNew ? '' : ' (including unsaved edits)')
        + ' against the data below. Connectors listed under mocks answer with the given reply; any other connector is called for real.'
        + ' Data sets are an in-memory copy filled from the rows given here, so a test run never changes stored data.'),
      h('div', { class: 'cols' }, h('div', {}, h('label', {}, 'Scope (JSON)'), scope), h('div', {}, h('label', {}, 'Connector mocks (JSON)'), mocks, h('label', {}, 'Data set rows (JSON)'), seed,
        h('div', { class: 'row', style: 'margin-top:10px' }, h('button', { class: 'primary', onclick: run, disabled: isNew }, 'Run')))),
      h('div', { style: 'margin-top:12px' }, runOut)),
  };
  // a reference table is also kept as a spreadsheet: its rows go out as a CSV file and come back the same way
  if (model.kind === 'ReferenceTable' && !isNew) {
    const file = h('input', { type: 'file', accept: '.csv,text/csv', 'aria-label': 'CSV file' });
    const pasted = h('textarea', { rows: 6, spellcheck: 'false', placeholder: 'or paste the rows here, header line first', 'aria-label': 'CSV rows' });
    const merge = h('input', { type: 'radio', name: 'csvmode', value: 'merge', checked: true });
    const replace = h('input', { type: 'radio', name: 'csvmode', value: 'replace' });
    const comment = h('input', { type: 'text', style: 'width:100%', 'aria-label': 'Comment for the approver' });
    const csvOut = h('div', {});
    const upload = async () => {
      csvOut.replaceChildren('Working…');
      try {
        const csv = file.files && file.files[0] ? await file.files[0].text() : pasted.value;
        if (!csv.trim()) { csvOut.replaceChildren(h('div', { class: 'note bad' }, 'Choose a file or paste the rows first.')); return; }
        const r = await api('POST', '/api/studio/reference-tables/' + model.name + '/imports', { csv, mode: replace.checked ? 'replace' : 'merge', comment: comment.value });
        if (r.problems) { csvOut.replaceChildren(problems(r)); return; }
        if (r.error) { csvOut.replaceChildren(h('div', { class: 'note bad' }, r.error)); return; }
        csvOut.replaceChildren(h('div', { class: 'note good' }, 'Change request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' was created: ' + r.summary + '. It is deployed when another user approves it.'));
      } catch (e) { csvOut.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
    };
    // the rows as a table to edit in place: every change goes the same way as an uploaded file (the whole table, replaced)
    const columns = [];
    for (const row of model.definition.rows || []) for (const c of Object.keys(row)) if (!columns.includes(c)) columns.push(c);
    if (!columns.includes(model.definition.key)) columns.unshift(model.definition.key);
    const gridRows = (model.definition.rows || []).map(r => Object.assign({}, r));
    const gridOut = h('div', {});
    const gridBody = h('tbody', {});
    const cellInput = (row, c) => { const el = h('input', { type: 'text', value: row[c] == null ? '' : String(row[c]), 'aria-label': c + ' of row ' + (gridRows.indexOf(row) + 1), style: 'width:100%; min-width:90px' });
      el.addEventListener('input', () => { row[c] = el.value; }); return el; };
    const drawGrid = () => gridBody.replaceChildren(...gridRows.map(row => h('tr', {}, columns.map(c => h('td', {}, cellInput(row, c))),
      h('td', {}, h('button', { type: 'button', 'aria-label': 'Remove row ' + (gridRows.indexOf(row) + 1), onclick: () => { gridRows.splice(gridRows.indexOf(row), 1); drawGrid(); } }, 'Remove')))));
    drawGrid();
    const csvCell = (v) => { const s = v == null ? '' : String(v); return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
    const submitGrid = async () => {
      gridOut.replaceChildren('Working…');
      try {
        const csv = [columns.map(csvCell).join(','), ...gridRows.map(r => columns.map(c => csvCell(r[c])).join(','))].join('\r\n') + '\r\n';
        const r = await api('POST', '/api/studio/reference-tables/' + model.name + '/imports', { csv, mode: 'replace', comment: gridComment.value });
        if (r.problems) { gridOut.replaceChildren(problems(r)); return; }
        if (r.error) { gridOut.replaceChildren(h('div', { class: 'note bad' }, r.error)); return; }
        gridOut.replaceChildren(h('div', { class: 'note good' }, 'Change request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' was created: ' + r.summary + '. It is deployed when another user approves it.'));
      } catch (e) { gridOut.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
    };
    const gridComment = h('input', { type: 'text', style: 'width:100%', 'aria-label': 'Comment for the approver of the edited rows' });
    panes.rows = h('div', {},
      h('p', { class: 'sub' }, 'The rows of this table, to edit here or as a spreadsheet file. Either way the change is a request a second person approves, like any model change. ',
        'The key column is ', h('code', {}, model.definition.key), '.'),
      can('studio.edit') ? h('div', { class: 'panel' }, h('h3', {}, 'Edit the rows'),
        h('div', { style: 'overflow-x:auto' }, h('table', { class: 'grid' }, h('thead', {}, h('tr', {}, columns.map(c => h('th', {}, c)), h('th', {}, ''))), gridBody)),
        h('div', { class: 'row', style: 'margin-top:8px' }, h('button', { type: 'button', onclick: () => { const row = {}; columns.forEach(c => { row[c] = ''; }); gridRows.push(row); drawGrid(); } }, 'Add a row')),
        h('div', {}, h('label', {}, 'Comment for the approver'), gridComment),
        h('div', { class: 'row', style: 'margin-top:10px' }, h('button', { type: 'button', class: 'primary', onclick: submitGrid }, 'Submit the edited rows for approval')),
        h('div', { style: 'margin-top:12px' }, gridOut)) : null,
      h('h3', {}, 'As a spreadsheet file'),
      h('p', { class: 'sub' }, 'The header line names the columns and must have the key column.'),
      h('p', {}, h('a', { href: '/api/studio/reference-tables/' + model.name + '/rows.csv', download: model.name + '.csv' }, 'Download the rows as CSV'),
        ' (' + (model.definition.rows || []).length + ' rows)'),
      can('studio.edit') ? h('div', { class: 'panel' }, h('h3', {}, 'Upload rows'),
        h('div', {}, h('label', {}, 'File'), file), h('div', {}, pasted),
        h('div', { class: 'row', style: 'margin-top:8px' }, h('label', {}, merge, ' Merge: rows in the file are added to the table or change the row with the same key'),
          h('label', {}, replace, ' Replace: the file is the whole table; rows not in it are removed')),
        h('div', {}, h('label', {}, 'Comment for the approver'), comment),
        h('div', { class: 'row', style: 'margin-top:10px' }, h('button', { type: 'button', class: 'primary', onclick: upload }, 'Submit for approval')),
        h('div', { style: 'margin-top:12px' }, csvOut)) : null);
  }
  if (studio.tab === 'design' && !designer()) studio.tab = 'model';
  if (studio.tab === 'rows' && !panes.rows) studio.tab = 'model';
  const body = h('div', {}, panes[studio.tab] || panes.model);
  if (studio.tab === 'design') openDesign();
  const tabList = [['model', 'Model'], designer() ? ['design', 'Design'] : null, panes.rows ? ['rows', 'Rows (CSV)'] : null, ['java', 'Generated Java'], ['run', 'Test run']].filter(Boolean);
  const tabs = h('div', { class: 'tabs' }, tabList.map(([k, label]) =>
    h('button', { type: 'button', class: studio.tab === k ? 'on' : '', 'aria-pressed': studio.tab === k ? 'true' : 'false', onclick: (e) => {
      studio.tab = k;
      body.replaceChildren(panes[k]);
      if (k === 'design') openDesign();
      tabs.querySelectorAll('button').forEach(b => { b.classList.remove('on'); b.setAttribute('aria-pressed', 'false'); });
      e.target.classList.add('on');
      e.target.setAttribute('aria-pressed', 'true');
    } }, label)));
  return h('div', { class: 'panel' },
    h('div', { class: 'row' }, h('b', {}, isNew ? 'New model' : model.name), model.kind ? h('span', { class: 'badge' }, model.kind) : null,
      h('span', { class: 'spacer' }), h('span', { class: 'mono', style: 'color:var(--muted)' }, model.path || '')),
    tabs, body, h('div', { style: 'margin-top:12px' }, out));
}

// ---------- deployments ----------

/** Lines of two texts as kept, removed (-) and added (+), by longest common subsequence. */
function diffLines(before, after) {
  const a = (before || '').split('\n'), b = (after || '').split('\n');
  const m = a.length, n = b.length;
  const lcs = Array.from({ length: m + 1 }, () => new Uint16Array(n + 1));
  for (let i = m - 1; i >= 0; i--) for (let j = n - 1; j >= 0; j--) lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
  const out = [];
  let i = 0, j = 0;
  while (i < m || j < n) {
    if (i < m && j < n && a[i] === b[j]) { out.push([' ', a[i]]); i++; j++; }
    else if (j < n && (i === m || lcs[i][j + 1] >= lcs[i + 1][j])) out.push(['+', b[j++]]);
    else out.push(['-', a[i++]]);
  }
  return out;
}

function changeList(changes, emptyText) {
  if (!changes.length) return h('p', { class: 'sub' }, emptyText);
  const words = { ADDED: 'added', REMOVED: 'removed', CHANGED: 'changed' };
  return changes.map(c => h('details', { class: 'panel change' },
    h('summary', {}, h('span', { class: 'badge ' + (c.change === 'REMOVED' ? 'bad' : c.change === 'ADDED' ? 'good' : 'warn') }, words[c.change]), ' ', h('b', {}, c.name), ' ', h('span', { class: 'sub' }, c.kind)),
    h('pre', { class: 'diff' }, diffLines(c.before, c.after).map(([sign, line]) => h('span', { class: sign === '+' ? 'add' : sign === '-' ? 'del' : '' }, sign + ' ' + line + '\n')))));
}

VIEWS.deployments = async (id) => {
  if (!id) {
    const list = await api('GET', '/api/studio/deployments');
    return page('Deployments', 'Every approved change made a new version of the whole model set. Open one to see what it changed, or to go back to it.',
      table([
        { label: 'Version', get: d => d.version },
        { label: 'Id', mono: true, get: d => d.id },
        { label: '', get: d => d.id === list.active ? badge('ACTIVE') : '' },
        { label: 'Change', get: d => d.comment || '' },
        { label: 'By', get: d => d.createdBy + (d.approvedBy ? ', approved by ' + d.approvedBy : '') },
        { label: 'When', get: d => when(d.createdAt) },
        { label: 'Models', get: d => d.modelCount },
      ], list.items, d => go('#/deployments/' + d.id)));
  }
  const d = await api('GET', '/api/studio/deployments/' + id);
  const out = h('div', {});
  const rollback = async () => {
    const comment = prompt('Why go back to version ' + d.version + '? (for the approver)');
    if (comment === null) return;
    try {
      const r = await api('POST', '/api/studio/rollbacks', { deploymentId: d.id, comment });
      out.replaceChildren(r.problems
        ? h('div', {}, h('div', { class: 'note bad' }, 'The models of this version no longer build here. Nothing was changed.'),
          table([{ label: 'Model', get: p => p.element }, { label: 'Where', get: p => p.where }, { label: 'Problem', get: p => p.message }], r.problems))
        : h('div', { class: 'note good' }, 'Rollback request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' was created. It is applied when another user approves it.'));
    } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  return page('Deployment ' + d.id, 'Version ' + d.version + (d.active ? ', the active one' : ''),
    h('div', { class: 'panel' }, kv([
      ['Status', d.active ? badge('ACTIVE') : 'Not active'], ['Change', d.comment], ['Created by', d.createdBy + ' on ' + when(d.createdAt)],
      ['Approved by', d.approvedBy], ['Models', d.modelCount], ['Came after', d.previous ? h('a', { href: '#/deployments/' + d.previous }, d.previous) : 'nothing; this is the first']])),
    h('h2', {}, 'What this version changed'),
    changeList(d.changes, 'Nothing: the models are the same as in the version before.'),
    d.active ? null : [h('h2', {}, 'What going back to this version would change now'),
      changeList(d.rollback, 'Nothing: the active deployment has the same models.'),
      can('studio.edit') && d.rollback.length ? h('div', { class: 'row', style: 'margin:10px 0' },
        h('button', { class: 'primary', onclick: rollback }, 'Request rollback to this version'),
        h('span', { class: 'sub' }, 'Stored data, such as rows of data sets and payments already processed, is not changed by a rollback.')) : null, out]);
};

// ---------- message checks ----------

VIEWS.checks = async () => {
  const data = await api('GET', '/api/schemas');
  const raw = h('textarea', { rows: 10, spellcheck: 'false', 'aria-label': 'Message to check', placeholder: 'Paste an ISO 20022 XML message or a SWIFT MT message' });
  const checkOut = h('div', {});
  const check = async () => {
    checkOut.replaceChildren('Checking…');
    try {
      const r = await api('POST', '/api/studio/check-message', { raw: raw.value });
      checkOut.replaceChildren(...r.messages.map(m => h('div', { class: 'panel' },
        h('div', { class: 'row' }, h('b', {}, m.messageType), h('span', { class: 'sub' }, m.checkedAgainst ? 'checked against the ' + m.checkedAgainst : '')),
        !m.checkedAgainst ? h('div', { class: 'note warn' }, 'Nothing is installed for ' + m.messageType + ', so messages of this type are not checked.')
          : m.problems.length ? [h('div', { class: 'note bad' }, m.problems.length + ' problem(s). A message like this is rejected when it is received.'),
            h('ul', { class: 'problems' }, m.problems.map(p => h('li', {}, p)))]
          : h('div', { class: 'note good' }, 'No problems.'))));
    } catch (e) { checkOut.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const pick = (input, into) => input.addEventListener('change', async () => { if (input.files[0]) { into(await input.files[0].text(), input.files[0].name); input.value = ''; } });
  const messageFile = h('input', { type: 'file', 'aria-label': 'Message file' });
  pick(messageFile, (text) => { raw.value = text; });

  const schemaOut = h('div', {});
  const request = async (path, body, done) => {
    try {
      const r = await api('POST', path, body);
      schemaOut.replaceChildren(h('div', { class: 'note good' }, done + ' Request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' waits for approval by another user.'));
    } catch (e) { schemaOut.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const schemaFile = h('input', { type: 'file', accept: '.xsd,.xml', 'aria-label': 'Schema file' });
  pick(schemaFile, (text, fileName) => {
    const comment = prompt('Import ' + fileName + '. Reason, for the approver:');
    if (comment !== null) request('/api/schemas', { text, fileName, comment }, 'The schema can be used.');
  });
  const remove = (s) => {
    const comment = prompt('Remove the schema of ' + s.messageType + '? Messages of this type are then no longer checked. Reason, for the approver:');
    if (comment !== null) request('/api/schemas/' + s.messageType + '/removal', { comment }, '');
  };
  return page('Message checks', 'A message is checked when it is received and before it is sent: ISO 20022 against the schema of its type, SWIFT MT against the field rules of its type. A received one that fails is rejected; one the platform built is held back for repair. Either way the field is named.',
    h('h2', {}, 'Check a message'),
    h('div', { class: 'panel' }, raw,
      h('div', { class: 'row', style: 'margin-top:10px' }, h('button', { class: 'primary', onclick: check }, 'Check'), h('span', { class: 'sub' }, 'or choose a file:'), messageFile,
        h('span', { class: 'spacer' }), h('span', { class: 'sub' }, 'Nothing is stored or processed.'))),
    checkOut,
    h('h2', {}, 'ISO 20022 schemas'),
    h('p', { class: 'sub' }, 'No schemas are shipped. Download the XSD of each message type you use from iso20022.org or your scheme, and import it here. The message type is read from the schema itself.'),
    data.schemas.length ? table([
      { label: 'Message type', mono: true, get: s => s.messageType }, { label: 'File', get: s => s.fileName || '' },
      { label: 'Size', get: s => Math.round((s.characters || 0) / 1024) + ' KB' },
      { label: 'Imported', get: s => s.importedBy + ', approved by ' + s.approvedBy + ', ' + when(s.importedAt) },
      { label: '', get: s => can('studio.edit') ? h('button', { class: 'danger', onclick: () => remove(s) }, 'Request removal') : '' },
    ], data.schemas) : h('div', { class: 'empty' }, 'No schema is imported, so no ISO 20022 message is checked against one.'),
    can('studio.edit') ? h('div', { class: 'row', style: 'margin:10px 0' }, h('span', {}, 'Import a schema (.xsd):'), schemaFile) : null,
    schemaOut,
    h('h2', {}, 'SWIFT MT field rules'),
    h('p', { class: 'sub' }, 'Each is a MessageSpec model, changed in Studio like any other model. The ones provided were written from the published field formats; compare them with the standards release your bank is on.'),
    table([{ label: 'Message type', mono: true, get: s => s.messageType }, { label: 'Model', get: s => h('a', { href: '#/studio/' + s.name }, s.name) },
      { label: 'Description', get: s => (s.description || '').trim().split('. ')[0] }], data.specs));
};

// ---------- charges that other banks owe us ----------

VIEWS.claims = async (id, params) => {
  if (!params.get('claim')) params.set('claim', 'OPEN,REQUESTED,FAILED');
  return listView({
    title: 'Charge claims', hash: '#/claims', api: '/api/transactions', fixed: { claim: 'OPEN,REQUESTED,FAILED,PAID,SENDING' },
    sub: 'Our charge for a payment received with all charges borne by the payer is claimed from the bank that sent it. A claim is sent by itself; mark it as paid when the money has arrived.',
    search: { label: 'Search claims', placeholder: 'Search payment id, reference, name…' },
    selects: [{ key: 'claim', label: 'Paid and unpaid', chip: 'Claim', options: [['OPEN,REQUESTED,FAILED', 'Not paid yet'], ['OPEN', 'Not sent yet'], ['REQUESTED', 'Sent, not paid'], ['FAILED', 'Could not be sent'], ['PAID', 'Paid']] }],
    empty: 'No claims match these filters.',
    open: t => '#/transactions/' + t.id,
    columns: [
      { label: 'Payment', mono: true, nw: true, sort: 'id', get: t => t.id },
      { label: 'Reference of the sender', nw: true, get: t => t.originalTxId || '' },
      { label: 'Owed by', mono: true, get: t => (t.charges || {}).claimFrom || '' },
      { label: 'Charge', num: true, nw: true, get: t => money((t.charges || {}).claim, (t.charges || {}).currency) },
      { label: 'Claim', get: t => badge((t.charges || {}).claimStatus) },
      { label: 'Claimed', nw: true, get: t => when((t.charges || {}).claimedAt) },
      { label: 'Paid', nw: true, get: t => when((t.charges || {}).claimPaidAt) },
    ],
  }, params);
};

// ---------- scheduled jobs: what the Schedule models say, when they last ran, and running one now ----------

VIEWS.schedules = async () => {
  live.handler = { kinds: ['schedules'], run: refreshMain };
  const items = (await api('GET', '/api/schedules')).items;
  const out = h('div', { 'aria-live': 'polite' });
  const runNow = async (s) => {
    if (!confirm('Run ' + s.name + ' now?')) return;
    try {
      const r = await api('POST', '/api/schedules/' + s.name + '/run');
      out.replaceChildren(h('div', { class: 'note ' + (r.lastOutcome === 'DONE' ? 'good' : 'bad') }, s.name + ': ' + r.lastOutcome + (r.lastProblem ? ' — ' + r.lastProblem : '')));
      refreshMain();
    } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  return page('Schedules', 'Jobs that run at the times their Schedule models name (a cron expression in a time zone): closing the ledger day, statements, account reports, or a flow. A job runs once however many processes there are. Change a schedule in the Studio like any model.',
    out,
    table([
      { label: 'Schedule', mono: true, get: s => h('a', { href: '#/studio/' + s.name }, s.name) },
      { label: 'Job', get: s => s.job },
      { label: 'When', mono: true, nw: true, get: s => s.cron + ' (' + s.timezone + ')' },
      { label: 'Enabled', get: s => s.enabled ? 'yes' : 'no' },
      { label: 'Next', nw: true, get: s => s.problem ? h('span', { class: 'bad' }, s.problem) : when(s.nextAt) },
      { label: 'Last run', nw: true, get: s => when(s.lastRunAt) },
      { label: 'Outcome', get: s => s.lastOutcome ? [badge(s.lastOutcome === 'DONE' ? 'ACCEPTED' : 'FAILED'), s.lastProblem ? ' ' + s.lastProblem : ''] : '' },
      { label: 'Runs', num: true, get: s => s.runs || 0 },
      { label: '', get: s => can('payments.repair') ? h('button', { type: 'button', onclick: (e) => { e.stopPropagation(); runNow(s); } }, 'Run now') : '' },
    ], items));
};

// ---------- a payment initiated from a form, with templates ----------

VIEWS.initiate = async () => {
  const templates = (await api('GET', '/api/payments/templates')).items;
  const fields = [['debtor.name', 'Debtor name'], ['debtor.account', 'Debtor account'], ['debtor.agentBic', 'Debtor bank (BIC)'],
    ['creditor.name', 'Creditor name'], ['creditor.account', 'Creditor account'], ['creditor.agentBic', 'Creditor bank (BIC)'],
    ['amount', 'Amount'], ['currency', 'Currency'], ['requestedDate', 'Requested date'], ['remittance', 'Remittance information'],
    ['chargeBearer', 'Charge bearer (DEBT, CRED, SHAR, SLEV)'], ['purposeCode', 'Purpose code'], ['priority', 'Priority (HIGH or NORM)'], ['endToEndId', 'End-to-end reference']];
  const inputs = fields.map(([path, label]) => { const el = h('input', { type: 'text', 'aria-label': label }); return [path, el]; });
  const comment = h('textarea', { rows: '2', 'aria-label': 'Why (for the approver)' });
  const out = h('div', { 'aria-live': 'polite' });
  const pick = h('select', { 'aria-label': 'Template' }, h('option', { value: '' }, 'No template'), templates.map(t => h('option', { value: t.id }, t.name)));
  const values = () => { const v = {}; for (const [path, el] of inputs) if (el.value.trim()) v[path] = el.value.trim(); return v; };
  pick.addEventListener('change', () => {
    const t = templates.find(x => x.id === pick.value);
    for (const [path, el] of inputs) el.value = t && t.fields[path] != null ? String(t.fields[path]) : '';
  });
  const submit = async () => {
    out.replaceChildren('Working…');
    try {
      const r = await api('POST', '/api/payments/initiations', Object.assign(values(), { template: pick.value || undefined, comment: comment.value.trim() }));
      if (r.error) { out.replaceChildren(h('div', { class: 'note bad' }, r.error)); return; }
      out.replaceChildren(h('div', { class: 'note good' }, 'Request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' was created: ' + r.summary + '. The payment enters processing when another user approves it.'));
    } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const saveTemplate = async () => {
    const name = prompt('Name of the template (the amount and the reference are not kept; everything else is):');
    if (!name || !name.trim()) return;
    out.replaceChildren('Working…');
    try {
      const f = values(); delete f.amount; delete f.endToEndId; delete f.requestedDate;
      const t = await api('POST', '/api/payments/templates', { name: name.trim(), fields: f });
      if (t.error) { out.replaceChildren(h('div', { class: 'note bad' }, t.error)); return; }
      out.replaceChildren(h('div', { class: 'note good' }, 'Template "' + t.name + '" was saved.'));
      if (!templates.some(x => x.id === t.id)) { templates.push(t); pick.append(h('option', { value: t.id }, t.name)); } else { Object.assign(templates.find(x => x.id === t.id), t); }
      pick.value = t.id;
    } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const removeTemplate = async () => {
    if (!pick.value || !confirm('Remove the template "' + pick.selectedOptions[0].textContent + '"?')) return;
    try {
      await api('DELETE', '/api/payments/templates/' + pick.value);
      const i = templates.findIndex(x => x.id === pick.value); if (i >= 0) templates.splice(i, 1);
      pick.selectedOptions[0].remove(); pick.value = '';
      out.replaceChildren(h('div', { class: 'note good' }, 'The template was removed.'));
    } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  return page('New payment', 'One payment from a form. It goes to a second person for approval and only then enters processing, on the Console channel, like any instruction. A template keeps the parties and the standing fields for the next time.',
    h('div', { class: 'panel' },
      h('div', { class: 'row' }, h('label', {}, 'Template ', pick), h('button', { type: 'button', onclick: saveTemplate }, 'Save as template'), h('button', { type: 'button', onclick: removeTemplate }, 'Remove template')),
      h('div', { class: 'formgrid', style: 'margin-top:12px' }, inputs.map(([path, el]) => h('div', {}, h('label', {}, fields.find(f => f[0] === path)[1]), el))),
      h('div', {}, h('label', {}, 'Why (for the approver)'), comment),
      h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', class: 'primary', onclick: submit }, 'Submit for approval')),
      h('div', { style: 'margin-top:12px' }, out)));
};

// ---------- the user's own account: the second step of sign-in ----------

VIEWS.account = async () => {
  const me = await api('GET', '/api/me');
  const out = h('div', { 'aria-live': 'polite' });
  const say = (text, bad) => out.replaceChildren(h('div', { class: 'note ' + (bad ? 'bad' : 'good') }, text));
  const codeField = (label) => { const el = h('input', { type: 'text', inputmode: 'numeric', maxlength: '6', autocomplete: 'one-time-code', spellcheck: 'false' }); el.setAttribute('aria-label', label); return el; };
  const setUp = h('div', {});
  const start = async () => {
    try {
      const e = await api('POST', '/api/me/mfa/enroll');
      const code = codeField('Code shown by the app');
      const confirm = async () => {
        try {
          const r = await api('POST', '/api/me/mfa/confirm', { code: code.value.trim() });
          if (r.error) { say(r.error, true); return; }
          session.user = null;
          showRecovery(r.recoveryCodes, 'The second step is on.');
        } catch (x) { say(x.message, true); }
      };
      setUp.replaceChildren(h('div', { class: 'panel' },
        h('p', {}, 'Add an account in your authenticator app and enter this key by hand. It is shown once.'),
        kv([['Key', h('code', {}, e.secret.replace(/(.{4})/g, '$1 ').trim())], ['Account name', me.username], ['Type', 'Time based, 6 digits, 30 seconds']]),
        h('p', { class: 'sub' }, 'Apps that take a set-up link can use: ', h('code', { style: 'word-break:break-all' }, e.uri)),
        h('div', { class: 'formgrid' }, h('div', {}, h('label', {}, 'Code shown by the app'), code)),
        h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', class: 'primary', onclick: confirm }, 'Turn the second step on'))));
      code.focus();
    } catch (x) { say(x.message, true); }
  };
  // recovery codes are shown once; the page is drawn again when the user has them
  const showRecovery = (codes, lead) => setUp.replaceChildren(h('div', { class: 'panel' }, h('h3', {}, 'Recovery codes'),
    h('p', {}, lead + ' Keep these eight recovery codes somewhere safe: each signs you in once if you do not have your device. They are shown only now.'),
    h('pre', { style: 'font-size:1.1em; letter-spacing:.05em' }, codes.join('\n')),
    h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', class: 'primary', onclick: () => render() }, 'I have saved them'))));
  const newCodesCode = codeField('Current code for new recovery codes');
  const newCodes = async () => {
    try {
      const r = await api('POST', '/api/me/mfa/recovery', { code: newCodesCode.value.trim() });
      if (r.error) { say(r.error, true); return; }
      showRecovery(r.recoveryCodes, 'Your old recovery codes no longer work.');
    } catch (x) { say(x.message, true); }
  };
  const offCode = codeField('Current code');
  const turnOff = async () => {
    try {
      const r = await api('POST', '/api/me/mfa/disable', { code: offCode.value.trim() });
      if (r.error) { say(r.error, true); return; }
      session.user = null;
      await render();
    } catch (x) { say(x.message, true); }
  };
  return page('My account', me.displayName || me.username,
    h('div', { class: 'panel' }, kv([['User name', me.username], ['Roles', me.roles.join(', ')], ['Access', me.scope ? scopeText(me.scope) : 'Not limited'],
      ['Second step at sign-in', me.mfa.enabled ? 'On since ' + when(me.mfa.since) : 'Off']])),
    h('h2', {}, 'Second step at sign-in'),
    h('p', { class: 'sub' }, 'With the second step on, signing in takes your password and a code from an authenticator app on your phone. A password that someone learned is then not enough.'
      + (me.mfa.required ? ' This installation requires it: until it is on, nothing else in the Console is open to you.' : '')),
    me.mfa.enabled ? h('div', { class: 'panel' },
        h('p', {}, 'Recovery codes left: ' + (me.mfa.recoveryCodesLeft ?? 0) + '. Each signs you in once without the device. New codes need a current code from the app and void the old ones.'),
        h('div', { class: 'formgrid' }, h('div', {}, h('label', {}, 'Current code for new recovery codes'), newCodesCode)),
        h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', onclick: newCodes }, 'New recovery codes')), setUp) : null,
    me.mfa.enabled
      ? (me.mfa.required ? h('p', {}, 'The second step is on. An administrator can reset it if you lose the device.')
        : h('div', { class: 'panel' }, h('p', {}, 'To turn it off, enter a current code.'),
            h('div', { class: 'formgrid' }, h('div', {}, h('label', {}, 'Current code'), offCode)),
            h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', class: 'danger', onclick: turnOff }, 'Turn the second step off'))))
      : [h('div', { class: 'row' }, h('button', { type: 'button', class: 'primary', onclick: start }, 'Set up the second step')), setUp],
    out);
};

// ---------- the day's figures per channel, to put next to the clearing's own ----------

VIEWS.daily = async (id, params) => {
  const date = params.get('date') || new Date().toISOString().slice(0, 10);
  const r = await api('GET', '/api/reports/daily?date=' + date);
  const pick = h('input', { type: 'date', value: date, onchange: () => go('#/daily?date=' + pick.value) });
  pick.setAttribute('aria-label', 'Day');
  const statuses = (row) => Object.entries(row.paymentsByStatus || {}).map(([k, v]) => k + ' ' + v).join(', ');
  const inbound = r.items.filter(x => x.direction === 'inbound'), outbound = r.items.filter(x => x.direction === 'outbound');
  return page('Daily report', 'What arrived and what left on one day, per channel, counted from the payments themselves. Compare the figures with what the clearing or the partner reports for the same day.',
    h('div', { class: 'row', style: 'margin-bottom:12px' }, h('label', {}, 'Day'), pick,
      h('a', { class: 'linklike', href: '/api/reports/daily?date=' + date + '&format=csv', download: '' }, 'Export CSV')),
    h('h2', {}, 'Received'),
    table([
      { label: 'Channel', mono: true, get: x => x.channel },
      { label: 'Messages', num: true, get: x => x.messages },
      { label: 'Rejected files', num: true, get: x => x.messagesRejected },
      { label: 'Payments', num: true, get: x => x.payments },
      { label: 'Amount', num: true, get: x => money(x.amount) },
      { label: 'By status', get: statuses },
    ], inbound),
    h('h2', {}, 'Sent'),
    table([
      { label: 'Channel', mono: true, get: x => x.channel },
      { label: 'Files', num: true, get: x => x.files },
      { label: 'Time to route (avg / p95)', nw: true, get: x => x.processed ? x.secondsToRouteAverage + ' s / ' + x.secondsToRouteP95 + ' s' : '' },
      { label: 'Sent', num: true, get: x => x.filesSent },
      { label: 'Failed', num: true, get: x => x.filesFailed },
      { label: 'Payments', num: true, get: x => x.payments },
      { label: 'Amount', num: true, get: x => money(x.amount) },
      { label: 'By status', get: statuses },
    ], outbound));
};

// ---------- the ledger: accounts, their entries and balances ----------

VIEWS.ledger = async (id, params) => {
  const out = h('div', { 'aria-live': 'polite' });
  const told = (r) => {
    out.replaceChildren(r.error ? h('div', { class: 'note bad' }, 'Nothing was requested: ' + r.error)
      : h('div', { class: 'note good' }, 'Request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' takes effect when another user approves it.'));
    out.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  };
  const ask = async (url, body) => {
    const comment = prompt('Why? (for the approver)');
    if (comment === null) return;
    // the note of the previous request goes before the next one is asked, so what is shown is always the answer to the last
    out.replaceChildren();
    try { told(await api('POST', url, { ...body, comment })); } catch (e) { told({ error: e.message }); }
  };
  const input = (label, attrs) => { const el = h('input', { type: 'text', spellcheck: 'false', ...(attrs || {}) }); el.setAttribute('aria-label', label); return el; };
  const choice = (label, options, value) => { const el = h('select', {}, options.map(o => h('option', { value: o, selected: o === value ? '' : null }, o))); el.setAttribute('aria-label', label); return el; };
  const maker = can('payments.repair');
  const signed = (amount, currency) => h('span', { class: Number(amount) < 0 ? 'neg' : '' }, money(amount, currency));

  if (id) {
    const a = await api('GET', '/api/ledger/accounts/' + encodeURIComponent(id) + (params.get('from') ? '?from=' + params.get('from') : ''));
    live.handler = { kinds: ['ledger'], run: refreshMain };
    const status = choice('Status', ['ACTIVE', 'BLOCKED', 'CLOSED'], a.status);
    const name = input('Name', { value: a.name });
    const overdraft = input('Overdraft limit', { type: 'number', step: 'any', min: '0', value: String(a.overdraftLimit || 0) });
    return page('Account ' + a.id, a.name,
      h('p', {}, h('a', { href: '#/ledger' }, '← All accounts')),
      h('div', { class: 'panel' }, kv([
        ['Balance', signed(a.balance, a.currency)], ['Type', a.type], ['Currency', a.currency], ['Status', badge(a.status)],
        ['Overdraft limit', a.type === 'CUSTOMER' ? money(a.overdraftLimit || 0, a.currency) : null],
        ['Opened', when(a.openedAt) + ' by ' + a.openedBy], ['Changed', a.updatedBy ? when(a.updatedAt) + ' by ' + a.updatedBy : null]])),
      h('h2', {}, 'Entries since ' + a.from),
      h('p', { class: 'sub' }, 'Newest first. The balance of the account before these entries was ' + money(a.openingBalance, a.currency) + '.'
        + (a.entryCount > a.entries.length ? ' Only the newest ' + a.entries.length + ' of ' + a.entryCount + ' are shown.' : '')),
      table([
        { label: 'Booked', get: e => when(e.at) },
        { label: 'Value', nw: true, get: e => e.valueDate && e.valueDate !== e.bookDate ? e.valueDate : '' },
        { label: 'Debit', num: true, get: e => e.creditDebit === 'DBIT' ? money(e.amount, e.currency) : '' },
        { label: 'Credit', num: true, get: e => e.creditDebit === 'CRDT' ? money(e.amount, e.currency) : '' },
        { label: 'Balance', num: true, get: e => signed(e.balance, e.currency) },
        { label: 'Other account', mono: true, get: e => h('a', { href: '#/ledger/' + e.otherAccount }, e.otherAccount) },
        { label: 'For', get: e => [/^ORVTXN/.test(e.reference || '') ? h('a', { href: '#/transactions/' + e.reference }, e.reference) : (e.reference || ''),
            e.text ? ' ' + e.text : '', e.reversalOf ? ' (takes back ' + e.reversalOf + ')' : '', e.reversedBy ? ' (taken back)' : ''] },
        { label: 'Posting', mono: true, get: e => e.id },
      ], a.entries),
      maker ? [h('h2', {}, 'Request a change'),
        h('div', { class: 'panel' },
          h('div', { class: 'formgrid' }, h('div', {}, h('label', {}, 'Name'), name), h('div', {}, h('label', {}, 'Status'), status),
            a.type === 'CUSTOMER' ? h('div', {}, h('label', {}, 'Overdraft limit'), overdraft) : null),
          h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', class: 'primary',
            onclick: () => ask('/api/ledger/accounts', { id: a.id, name: name.value.trim(), status: status.value, overdraftLimit: Number(overdraft.value || 0) }) }, 'Submit for approval')))] : null,
      out);
  }

  const list = await listView({
    title: 'Accounts', hash: '#/ledger', api: '/api/ledger/accounts',
    sub: 'The accounts of the platform\'s own ledger with their balances: credits minus debits. For the mirror of an account held at another bank a negative balance is money held there.',
    search: { label: 'Search accounts', placeholder: 'Search account number or name…' },
    selects: [{ key: 'type', label: 'All types', chip: 'Type', options: ['CUSTOMER', 'NOSTRO', 'VOSTRO', 'LORO', 'SUSPENSE', 'FEE', 'POSITION'].map(t => [t, t.charAt(0) + t.slice(1).toLowerCase()]) },
      { key: 'status', label: 'Any status', chip: 'Status', options: [['ACTIVE', 'Active'], ['BLOCKED', 'Blocked'], ['CLOSED', 'Closed']] }],
    sort: ['id', 'asc'], live: ['ledger'],
    empty: 'No account matches. The ledger has accounts only when they are opened here; flows use it when the account connectors point at /ledger.',
    open: a => '#/ledger/' + a.id,
    columns: [
      { label: 'Account', mono: true, nw: true, sort: 'id', get: a => a.id },
      { label: 'Name', sort: 'name', get: a => a.name },
      { label: 'Type', sort: 'type', get: a => a.type },
      { label: 'Currency', sort: 'currency', get: a => a.currency },
      { label: 'Balance', num: true, nw: true, get: a => signed(a.balance, a.currency) },
      { label: 'Status', sort: 'status', get: a => badge(a.status) },
    ],
  }, params);
  const dayBox = h('input', { type: 'date', 'aria-label': 'Day of the journal', value: new Date(Date.now() - 86400000).toISOString().slice(0, 10) });
  const journal = h('div', { class: 'panel' },
    h('h2', {}, 'The day\'s journal'),
    h('p', { class: 'sub' }, 'Every entry booked on a day as a CSV file for the general ledger: both sides, the value date, the fee it belongs to.'),
    h('div', { class: 'row' }, h('label', {}, 'Day ', dayBox), h('button', { type: 'button', onclick: () => { location.href = '/api/ledger/days/' + dayBox.value + '/entries.csv'; } }, 'Download the day')));
  list.push(journal);
  if (!maker) return list;
  const number = input('Account number'), holder = input('Name of the account'), type = choice('Type', ['CUSTOMER', 'NOSTRO', 'VOSTRO', 'LORO', 'SUSPENSE', 'FEE', 'POSITION'], 'CUSTOMER'),
    currency = input('Currency', { maxlength: '3', placeholder: 'ZAR' });
  const from = input('Account debited'), to = input('Account credited'), amount = input('Amount', { type: 'number', step: 'any', min: '0' }),
    ccy = input('Currency of the posting', { maxlength: '3', placeholder: 'ZAR' }), text = input('What the posting is for');
  return [...list,
    h('h2', {}, 'Open an account'),
    h('div', { class: 'panel' },
      h('div', { class: 'formgrid' }, h('div', {}, h('label', {}, 'Account number *'), number), h('div', {}, h('label', {}, 'Name of the account *'), holder),
        h('div', {}, h('label', {}, 'Type *'), type), h('div', {}, h('label', {}, 'Currency *'), currency)),
      h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', class: 'primary',
        onclick: () => ask('/api/ledger/accounts', { id: number.value.trim(), name: holder.value.trim(), type: type.value, currency: currency.value.trim().toUpperCase() }) }, 'Submit account for approval'))),
    h('h2', {}, 'Book a posting'),
    h('div', { class: 'panel' },
      h('p', { class: 'sub' }, 'One amount from one account to another of the same currency, for what no payment books: cash paid in, a correction. A second person approves it.'),
      h('div', { class: 'formgrid' }, h('div', {}, h('label', {}, 'Account debited *'), from), h('div', {}, h('label', {}, 'Account credited *'), to),
        h('div', {}, h('label', {}, 'Amount *'), amount), h('div', {}, h('label', {}, 'Currency of the posting *'), ccy), h('div', {}, h('label', {}, 'What the posting is for *'), text)),
      h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', class: 'primary',
        onclick: () => ask('/api/ledger/postings', { debitAccount: from.value.trim(), creditAccount: to.value.trim(), amount: Number(amount.value), currency: ccy.value.trim().toUpperCase(), text: text.value.trim() }) }, 'Submit posting for approval'))),
    out];
};

// ---------- customer instructions: data sets that a model offers to the Console ----------

VIEWS.data = async (name, params) => {
  const sets = (await api('GET', '/api/datasets')).items;
  if (!sets.length) return page('Customer instructions', 'No data set of the deployed models is offered here. A DataSet model is offered when it has a console section.');
  const set = sets.find(s => s.name === name) || sets[0];
  const c = set.console;
  const words = (field) => field.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/^./, x => x.toUpperCase()).replace(/ ([A-Z])(?=[a-z])/g, (m, x) => ' ' + x.toLowerCase());
  const show = (v) => v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : /^\d{4}-\d\d-\d\dT/.test(String(v)) ? when(v) : String(v);
  const out = h('div', { 'aria-live': 'polite' });
  const told = (r, what) => {
    if (r.error || r.violations) {
      out.replaceChildren(h('div', { class: 'note bad' }, 'Nothing was requested: ' + (r.violations && r.violations.length ? 'the values were refused.' : r.error)),
        r.violations && r.violations.length ? table([{ label: 'Code', get: v => v.code }, { label: 'Problem', get: v => v.message }], r.violations) : null);
    } else {
      out.replaceChildren(h('div', { class: 'note good' }, what + ' Request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' takes effect when another user approves it.'));
    }
    out.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  };

  // the form, built from what the model says the API takes
  const fields = (c.write && c.write.fields) || [];
  const inputs = fields.map(f => {
    const el = f.type === 'select' ? h('select', {}, h('option', { value: '' }, ''), (f.options || []).map(o => h('option', { value: o }, o)))
      : h('input', { type: f.type === 'number' ? 'number' : f.type === 'date' ? 'date' : 'text', step: f.type === 'number' ? 'any' : null, spellcheck: 'false' });
    el.setAttribute('aria-label', f.label || words(f.name));
    return [f, el];
  });
  const fill = (row) => {
    for (const [f, el] of inputs) { const v = row[f.from || f.name]; el.value = v === null || v === undefined ? '' : String(v); }
    inputs[0][1].scrollIntoView({ behavior: 'smooth', block: 'center' });
    inputs[0][1].focus({ preventScroll: true });
  };
  const send = async (action, pathValues, body, done) => {
    const comment = prompt((action === 'remove' ? 'Why is this removed?' : 'Why is this set?') + ' (for the approver)');
    if (comment === null) return;
    out.replaceChildren();
    try { told(await api('POST', '/api/datasets/' + set.name + '/requests', { action, path: pathValues, body, comment }), done); }
    catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const write = () => {
    const pathValues = {}, body = {};
    for (const [f, el] of inputs) {
      const v = el.value.trim();
      if (f.in === 'path') pathValues[f.name] = v; else if (v !== '') body[f.name] = f.type === 'number' ? Number(v) : v;
    }
    send('write', pathValues, body, 'The values were checked.');
  };
  const remove = (row) => {
    const pathValues = {};
    for (const [placeholder, field] of Object.entries(c.remove.path || {})) pathValues[placeholder] = row[field];
    send('remove', pathValues, {}, '');
  };

  const maker = can('payments.repair');
  const columns = c.columns.map(col => ({ label: words(col), sort: col, nw: /At$|On$/.test(col), mono: /account|Id$|^id$/i.test(col), get: r => show(r[col]) }));
  if (maker && c.remove) columns.push({ label: '', get: r => h('button', { type: 'button', class: 'danger', onclick: (e) => { e.stopPropagation(); remove(r); } }, 'Request removal') });
  const list = await listView({
    title: 'Customer instructions', hash: '#/data/' + set.name, api: '/api/datasets/' + set.name + '/rows',
    sub: 'What customers told the bank, kept as data that the processing flows read. A change is a request that a second person approves.',
    search: { label: 'Search ' + c.title.toLowerCase(), placeholder: 'Search ' + (c.search || [set.key]).map(words).join(', ').toLowerCase() + '…' },
    sort: [set.key, 'asc'], live: ['payments'],
    empty: 'Nothing matches.',
    onRow: maker && c.write ? fill : null,
    columns,
  }, params);
  const tabs = h('div', { class: 'tabs', role: 'navigation', 'aria-label': 'Kinds of customer instruction' }, sets.map(s =>
    h('a', { class: 'tab' + (s === set ? ' on' : ''), href: '#/data/' + s.name, 'aria-current': s === set ? 'page' : null }, s.console.title + ' (' + s.rows.toLocaleString() + ')')));
  return [list[0], list[1], tabs, h('p', { class: 'sub' }, c.help || set.description || ''), ...list.slice(2),
    maker && c.write ? [h('h2', {}, 'Request a new entry or a change'),
      h('div', { class: 'panel' },
        h('p', { class: 'sub' }, 'Click a row above to fill the form with its values.'),
        h('div', { class: 'formgrid' }, inputs.map(([f, el]) => h('div', {}, h('label', {}, (f.label || words(f.name)) + (f.optional ? '' : ' *')), el))),
        h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { type: 'button', class: 'primary', onclick: write }, 'Submit for approval'))),
      ] : null,
    out];
};

// ---------- approvals ----------

VIEWS.approvals = async (id, params) => {
  if (id) return approvalDetail(id);
  const kinds = [['MODEL_CHANGE', 'Model change'], ['MODEL_REMOVE', 'Model removal'], ['MODEL_ROLLBACK', 'Rollback'], ['PAYMENT_ACTION', 'Payment action'],
    ['DATA_CHANGE', 'Customer instruction'], ['USER_CHANGE', 'User change'], ['API_KEY_CHANGE', 'API key change'], ['API_CALL', 'API call'], ['ROLE_CHANGE', 'Role change'], ['ROLE_REMOVE', 'Role removal'], ['SCHEMA_IMPORT', 'Schema import'], ['SCHEMA_REMOVE', 'Schema removal']];
  return listView({
    title: 'Approvals', hash: '#/approvals', api: '/api/approvals', live: ['approvals'],
    sub: 'Changes wait here until a second person approves them. Nobody can approve their own request.',
    search: { label: 'Search approvals', placeholder: 'Search id, what is changed, who asked…' },
    selects: [{ key: 'status', label: 'All statuses', chip: 'Status', options: options(['PENDING', 'APPROVED', 'DECLINED', 'FAILED']) },
      { key: 'type', label: 'All kinds', chip: 'Kind', options: kinds }],
    more: DATE_FILTERS,
    empty: 'No requests match these filters.',
    open: a => '#/approvals/' + a.id,
    columns: [
      { label: 'Id', mono: true, nw: true, sort: 'id', get: a => a.id },
      { label: 'Change', get: a => a.summary },
      { label: 'Status', sort: 'status', get: a => badge(a.status) },
      { label: 'Requested by', sort: 'maker', get: a => a.maker },
      { label: 'Requested', nw: true, sort: 'requestedAt', get: a => when(a.requestedAt) },
      { label: 'Decided by', get: a => a.checker || '' },
    ],
  }, params);
};

async function approvalDetail(id) {
  const a = await api('GET', '/api/approvals/' + id);
  const comment = h('input', { style: 'flex:1;min-width:220px', placeholder: 'Comment (optional)' });
  const out = h('div', {});
  const decide = async (what) => {
    try { await api('POST', '/api/approvals/' + id + '/' + what, { comment: comment.value }); render(); }
    catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const own = a.maker === session.user.username;
  const allowed = can(a.approvePermission) && !own;
  return page(a.id, a.summary,
    a.failure ? h('div', { class: 'note bad' }, 'Approval was given but applying the change failed: ' + a.failure) : null,
    h('div', { class: 'panel' }, kv([
      ['Status', badge(a.status)], ['Requested by', a.maker + ' on ' + when(a.requestedAt)], ['Reason', a.makerComment],
      ['Decided by', a.checker ? a.checker + ' on ' + when(a.decidedAt) : null], ['Decision comment', a.checkerComment],
      ['Result', a.result && Object.keys(a.result).length ? JSON.stringify(a.result) : null],
    ])),
    a.status === 'PENDING' ? h('div', { class: 'panel' },
      allowed ? h('div', { class: 'row' }, comment, h('button', { class: 'primary', onclick: () => decide('approve') }, 'Approve and apply'),
        h('button', { class: 'danger', onclick: () => decide('decline') }, 'Decline'))
        : h('div', {}, own ? 'This is your own request. Another user with permission ' + a.approvePermission + ' must decide it.'
          : 'Permission ' + a.approvePermission + ' is required to decide this request.'), out) : null,
    a.type === 'MODEL_CHANGE' ? h('div', { class: 'cols' },
      h('div', {}, h('h2', {}, a.status === 'PENDING' ? 'Currently deployed' : 'Deployed now'), h('pre', {}, a.currentText || '(new model)')),
      h('div', {}, h('h2', {}, 'Proposed'), h('pre', {}, a.payload.text)))
      : a.type === 'MODEL_REMOVE' ? [h('h2', {}, a.status === 'PENDING' ? 'Model to be removed' : 'Removed model'), h('pre', {}, a.currentText || '(no longer deployed)')]
      : a.type === 'DATA_CHANGE' ? [h('h2', {}, a.payload.action === 'remove' ? 'To be removed' : 'To be set'),
        h('div', { class: 'panel' }, kv([['Data set', a.payload.dataset], ...Object.entries(a.payload.path || {}), ...Object.entries(a.payload.body || {}).map(([k, v]) => [k, String(v)])]))]
      : a.type === 'SCHEMA_IMPORT' ? [h('h2', {}, 'Schema'), h('div', { class: 'panel' }, kv([['Message type', a.payload.messageType], ['File', a.payload.fileName],
          ['Size', Math.round((a.payload.characters || 0) / 1024) + ' KB']])),
        h('details', {}, h('summary', {}, 'Schema text'), h('pre', {}, a.payload.text))]
      : a.type === 'MODEL_ROLLBACK' ? [h('h2', {}, 'Rollback'),
        h('p', {}, 'Back to ', h('a', { href: '#/deployments/' + a.payload.deploymentId }, a.payload.deploymentId), ' (version ' + a.payload.version + '), requested while ', a.payload.baseDeployment, ' was active.'),
        a.changesProblem ? h('div', { class: 'note bad' }, a.changesProblem) : null,
        a.changes ? [h('h2', {}, 'What approving changes'), changeList(a.changes, 'Nothing.')] : h('pre', {}, (a.payload.models || []).join('\n'))]
      : [h('h2', {}, 'Requested change'), h('pre', {}, JSON.stringify(a.payload, null, 2))],
    h('h2', {}, 'History'), h('div', { class: 'panel' }, timeline(a.events)));
}

// ---------- users ----------

VIEWS.users = async () => {
  const [users, roles, keys] = await Promise.all([api('GET', '/api/users'), api('GET', '/api/roles'), api('GET', '/api/keys')]);
  const f = {
    username: h('input', { 'aria-label': 'User name' }), displayName: h('input', { 'aria-label': 'Display name' }),
    password: h('input', { type: 'password', autocomplete: 'new-password', 'aria-label': 'Password' }),
    status: h('select', { 'aria-label': 'Status' }, ['ACTIVE', 'DISABLED'].map(s => h('option', {}, s))),
    accounts: h('input', { 'aria-label': 'Debtor accounts', placeholder: 'empty: every account', style: 'width:100%' }),
    currencies: h('input', { 'aria-label': 'Currencies', placeholder: 'empty: every currency, for example ZAR, EUR', style: 'width:100%' }),
    maxAmount: h('input', { type: 'number', min: '0', step: 'any', 'aria-label': 'Most per payment', placeholder: 'empty: any amount', style: 'width:100%' }),
  };
  const checks = roles.items.map(r => [r.id, h('input', { type: 'checkbox' })]);
  const channelChecks = (users.inboundChannels || []).map(c => [c, h('input', { type: 'checkbox' })]);
  const out = h('div', {});
  const note = (target, r, text) => target.replaceChildren(h('div', { class: 'note good' }, 'Request ', h('a', { href: '#/approvals/' + r.id }, r.id), text));
  const submit = async () => {
    try {
      const r = await api('POST', '/api/users', { username: f.username.value.trim(), displayName: f.displayName.value, password: f.password.value,
        status: f.status.value, roles: checks.filter(c => c[1].checked).map(c => c[0]),
        scope: { channels: channelChecks.filter(c => c[1].checked).map(c => c[0]), debtorAccounts: f.accounts.value.split(/[\s,;]+/).filter(Boolean),
          currencies: f.currencies.value.toUpperCase().split(/[\s,;]+/).filter(Boolean), maxAmount: f.maxAmount.value === '' ? undefined : Number(f.maxAmount.value) } });
      note(out, r, ' was created and waits for approval.');
    } catch (e) { out.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const fillUser = (u) => {
    f.username.value = u.id; f.displayName.value = u.displayName || ''; f.status.value = u.status === 'ACTIVE' ? 'ACTIVE' : 'DISABLED';
    checks.forEach(c => { c[1].checked = (u.roles || []).includes(c[0]); });
    channelChecks.forEach(c => { c[1].checked = ((u.scope || {}).channels || []).includes(c[0]); });
    f.accounts.value = ((u.scope || {}).debtorAccounts || []).join(', ');
    f.currencies.value = ((u.scope || {}).currencies || []).join(', ');
    f.maxAmount.value = (u.scope || {}).maxAmount === undefined || (u.scope || {}).maxAmount === null ? '' : String(u.scope.maxAmount);
    f.username.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  // roles of your own
  const rf = { id: h('input', { 'aria-label': 'Role name', placeholder: 'for example PAYMENT_VIEWER' }), description: h('input', { 'aria-label': 'Role description', style: 'width:100%' }),
    limits: h('input', { 'aria-label': 'Approval limits', placeholder: 'for example ZAR 1000000, EUR 50000, * 10000 (empty: no limit)', style: 'width:100%' }) };
  const parseLimits = (text) => {
    const out = {};
    for (const part of text.split(',').map(s => s.trim()).filter(Boolean)) { const [ccy, amount] = part.split(/\s+/); out[ccy] = Number(amount); }
    return out;
  };
  const permChecks = roles.permissions.map(p => [p, h('input', { type: 'checkbox' })]);
  const roleOut = h('div', {});
  const submitRole = async () => {
    try {
      note(roleOut, await api('POST', '/api/roles', { id: rf.id.value.trim(), description: rf.description.value,
        permissions: permChecks.filter(c => c[1].checked).map(c => c[0]), approvalLimits: parseLimits(rf.limits.value) }), ' was created and waits for approval.');
    } catch (e) { roleOut.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const removeRole = async (role) => {
    const comment = prompt('Remove role ' + role.id + '? Reason, for the approver:');
    if (comment === null) return;
    try { note(roleOut, await api('POST', '/api/roles/' + role.id + '/removal', { comment }), ' was created and waits for approval.'); }
    catch (e) { roleOut.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
    roleOut.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };
  const fillRole = (role) => {
    if (role.builtIn) return;
    rf.id.value = role.id; rf.description.value = role.description || '';
    rf.limits.value = Object.entries(role.approvalLimits || {}).map(([c, a]) => c + ' ' + a).join(', ');
    permChecks.forEach(c => { c[1].checked = role.permissions.includes(c[0]); });
    rf.id.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };
  const tick = (c, label) => h('div', {}, h('label', { style: 'display:inline;font-size:14px;color:var(--ink)' }, c[1], ' ' + label));

  // keys for systems: like a user with roles, but never approving or administering; the secret is shown once
  const kf = { id: h('input', { 'aria-label': 'Key name', placeholder: 'for example erp-system' }), description: h('input', { 'aria-label': 'Key description', style: 'width:100%' }),
    status: h('select', { 'aria-label': 'Key status' }, ['ACTIVE', 'DISABLED'].map(s => h('option', {}, s))), rotate: h('input', { type: 'checkbox', 'aria-label': 'Issue a new secret' }) };
  const keyChecks = roles.items.filter(r => !r.permissions.some(p => p === '*' || p.endsWith('.approve') || p.startsWith('admin.'))).map(r => [r.id, h('input', { type: 'checkbox' })]);
  const keyChannelChecks = (users.inboundChannels || []).map(c => [c, h('input', { type: 'checkbox' })]);
  const keyOut = h('div', {});
  const submitKey = async () => {
    try {
      const r = await api('POST', '/api/keys', { id: kf.id.value.trim(), description: kf.description.value, status: kf.status.value, rotate: kf.rotate.checked,
        roles: keyChecks.filter(c => c[1].checked).map(c => c[0]), channels: keyChannelChecks.filter(c => c[1].checked).map(c => c[0]) });
      keyOut.replaceChildren(h('div', { class: 'note good' }, 'Request ', h('a', { href: '#/approvals/' + r.id }, r.id), ' was created and waits for approval.',
        r.key ? [' The key, shown only now, works once the request is approved: ', h('code', { style: 'word-break:break-all' }, r.key)] : null));
    } catch (e) { keyOut.replaceChildren(h('div', { class: 'note bad' }, e.message)); }
  };
  const fillKey = (k) => {
    kf.id.value = k.id; kf.description.value = k.description || ''; kf.status.value = k.status === 'ACTIVE' ? 'ACTIVE' : 'DISABLED'; kf.rotate.checked = false;
    keyChecks.forEach(c => { c[1].checked = (k.roles || []).includes(c[0]); });
    keyChannelChecks.forEach(c => { c[1].checked = (k.channels || []).includes(c[0]); });
    kf.id.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  return page('Users and roles', 'A change to a user or a role is a request that a second person approves.',
    h('h2', {}, 'Users'),
    table([
      { label: 'User', mono: true, get: u => u.id }, { label: 'Name', get: u => u.displayName || '' },
      { label: 'Roles', get: u => (u.roles || []).join(', ') }, { label: 'Limited to', get: u => scopeText(u.scope) },
      { label: 'Status', get: u => badge(u.status) }, { label: 'Last sign-in', get: u => when(u.lastLoginAt) },
    ], users.items, fillUser),
    can('admin.edit') ? [h('h2', {}, 'Request a new user or a change'),
      h('div', { class: 'panel' },
        h('p', { class: 'sub' }, 'Click a user above to fill the form. Leave the password empty to keep the current one. Setting status ACTIVE also unlocks a locked user.'),
        h('div', { class: 'cols' },
          h('div', {}, h('label', {}, 'User name'), f.username, h('label', {}, 'Display name'), f.displayName,
            h('label', {}, 'Password (at least 12 characters)'), f.password, h('label', {}, 'Status'), f.status),
          h('div', {}, h('label', {}, 'Roles: what the user may do'), checks.map(c => tick(c, c[0])),
            h('label', {}, 'Limit to payments received on (none ticked: every channel)'), channelChecks.map(c => tick(c, c[0])),
            h('label', {}, 'Limit to payments of these debtor accounts'), f.accounts,
            h('div', { class: 'cols' }, h('div', {}, h('label', {}, 'Limit to payments in these currencies'), f.currencies), h('div', {}, h('label', {}, 'Limit to payments up to this amount'), f.maxAmount)),
            h('p', { class: 'sub' }, 'A limited user sees and acts on those payments only, and not on outbound files, statements or failed events, which hold payments of everybody. A user limited by account, currency or amount sees no files either.'))),
        h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'primary', onclick: submit }, 'Submit for approval')), out)] : null,
    h('h2', {}, 'API keys for systems'),
    h('p', { class: 'sub' }, 'A system that calls the API without a person signing in sends its key in the header X-Api-Key, also to POST /in/<channel>, so a partner\'s key is rotated here without a redeployment. A key has roles like a user, may never approve or administer, and its secret is shown once, when it is created or rotated.'),
    table([{ label: 'Key', mono: true, get: k => k.id }, { label: 'Description', get: k => k.description || '' }, { label: 'Roles', get: k => (k.roles || []).join(', ') },
      { label: 'May push to', get: k => (k.channels || []).length ? k.channels.map(c => c.replace('channels.', '')).join(', ') : 'any channel' },
      { label: 'Status', get: k => badge(k.status) }, { label: 'Last used', nw: true, get: k => when(k.lastUsedAt) }, { label: 'Secret issued', nw: true, get: k => when(k.rotatedAt) }], keys.items, fillKey),
    can('admin.edit') ? h('div', { class: 'panel' },
        h('p', { class: 'sub' }, 'Click a key above to change it. Tick "issue a new secret" to rotate it; the old secret stops working when the request is approved.'),
        h('div', { class: 'cols' },
          h('div', {}, h('label', {}, 'Key name (lower case letters, digits, dashes)'), kf.id, h('label', {}, 'Description'), kf.description, h('label', {}, 'Status'), kf.status,
            h('div', { style: 'margin-top:8px' }, h('label', { style: 'display:inline;font-size:14px;color:var(--ink)' }, kf.rotate, ' Issue a new secret'))),
          h('div', {}, h('label', {}, 'Roles (those that neither approve nor administer)'), keyChecks.map(c => tick(c, c[0] + ' (for the key)')),
            h('label', {}, 'May push files to these channels with POST /in/<channel> (none ticked: any)'), keyChannelChecks.map(c => tick(c, c[0] + ' (for the key)')))),
        h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'primary', onclick: submitKey }, 'Submit key for approval')), keyOut) : null,
    h('h2', {}, 'Roles'),
    table([{ label: 'Role', mono: true, get: r => r.id }, { label: '', get: r => r.builtIn ? h('span', { class: 'sub' }, 'built in') : '' },
      { label: 'Description', get: r => r.description || '' }, { label: 'Permissions', get: r => r.permissions.join(', ') },
      { label: 'Approves up to', get: r => Object.entries(r.approvalLimits || {}).map(([c, a]) => c + ' ' + money(a)).join(', ') || 'no limit' },
      { label: '', get: r => !r.builtIn && can('admin.edit') ? h('button', { class: 'danger', onclick: (e) => { e.stopPropagation(); removeRole(r); } }, 'Request removal') : '' }],
      roles.items, fillRole),
    can('admin.edit') ? [h('h2', {}, 'Request a new role or a change'),
      h('div', { class: 'panel' },
        h('p', { class: 'sub' }, 'A role is a set of permissions under a name. Click a role of your own above to change it; the built-in roles stay as they are. A change reaches signed-in users at once.'),
        h('div', { class: 'cols' },
          h('div', {}, h('label', {}, 'Role name (capital letters, digits, underscore)'), rf.id, h('label', {}, 'Description'), rf.description,
            h('label', {}, 'Approval limits (currency and amount, comma-separated; * for any currency; empty: no limit)'), rf.limits),
          h('div', {}, h('label', {}, 'Permissions'), permChecks.map(c => tick(c, c[0])))),
        h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'primary', onclick: submitRole }, 'Submit role for approval')), roleOut)] : null);
};

VIEWS.security = async (id, params) => {
  return listView({
    title: 'Security log', hash: '#/security', api: '/api/security/events', live: false,
    sub: 'Sign-ins and their failures, lock-outs, sign-outs and refused permissions, newest first.',
    search: { label: 'Search the security log', placeholder: 'Search user, address, detail…' },
    selects: [{ key: 'type', label: 'All events', chip: 'Event', options: options(['LOGIN_OK', 'LOGIN_FAILED', 'LOGIN_THROTTLED', 'USER_LOCKED', 'LOGOUT', 'DENIED']) }],
    more: DATE_FILTERS,
    sort: ['at', 'desc'],
    empty: 'No events match these filters.',
    columns: [
      { label: 'When', nw: true, sort: 'at', get: e => when(e.at) },
      { label: 'Event', sort: 'type', get: e => badge(e.type) },
      { label: 'User', mono: true, sort: 'username', get: e => e.username || '' },
      { label: 'Address', mono: true, get: e => e.address || '' },
      { label: 'Detail', get: e => e.detail || '' },
    ],
  }, params);
};

window.addEventListener('hashchange', render);
render();
