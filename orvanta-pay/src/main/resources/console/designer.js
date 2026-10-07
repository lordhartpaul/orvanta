'use strict';

// The visual flow designer of Studio. It edits the definition of a Flow model as data: steps are dragged
// from the palette onto the canvas, reordered by dragging, and filled in through the form on the right.
// Every change calls onChange, which turns the definition back into model text on the server.

const FLOW_END_STATUSES = ['ROUTED', 'REJECTED', 'HOLD', 'WAIT', 'WAREHOUSE', 'REPAIR'];

// field kinds: text, expr (an expression), ref (a model of the given kind), select, bool, pairs (name to expression), number
const FLOW_STEPS = {
  rules: { label: 'Rules', group: 'check', hint: 'Check a rule set; a failed rule can reject',
    fields: [['ref', 'Rule set', 'ref', 'RuleSet'], ['onViolation', 'When a rule fails', 'select', ['reject', 'continue']], ['status', 'Status when rejected', 'select', FLOW_END_STATUSES]] },
  map: { label: 'Mapping', group: 'data', hint: 'Fill or convert fields with a mapping',
    fields: [['ref', 'Mapping', 'ref', 'Mapping'], ['into', 'Put the result in (empty: the mapping writes to its own target)', 'text']] },
  set: { label: 'Set values', group: 'data', hint: 'Set fields from expressions',
    fields: [['values', 'Field = expression', 'pairs']] },
  decide: { label: 'Decision', group: 'check', hint: 'Let a decision table choose',
    fields: [['ref', 'Decision table', 'ref', 'DecisionTable'], ['onNoMatch', 'When no row matches', 'select', ['reject', 'continue']],
      ['status', 'Status when rejected', 'select', FLOW_END_STATUSES], ['code', 'Reason code', 'text'], ['message', 'Message', 'text']] },
  call: { label: 'Call', group: 'external', hint: 'Call an external system through a connector',
    fields: [['connector', 'Connector', 'ref', 'Connector'], ['request', 'Request field = expression', 'pairs'], ['into', 'Put the answer in', 'text'],
      ['once', 'Keep the answer when the flow runs again', 'bool'], ['onError', 'When the call fails', 'select', ['repair', 'reject', 'continue']],
      ['status', 'Status when rejected', 'select', FLOW_END_STATUSES]] },
  find: { label: 'Find', group: 'store', hint: 'Read rows of a data set',
    fields: [['dataset', 'Data set', 'ref', 'DataSet'], ['where', 'Column = expression', 'pairs'], ['into', 'Put the result in', 'text'],
      ['first', 'Only the first row', 'bool'], ['sort', 'Sort by column', 'text'], ['descending', 'Descending', 'bool'], ['limit', 'At most (rows)', 'number']] },
  save: { label: 'Save', group: 'store', hint: 'Insert or update a row of a data set',
    fields: [['dataset', 'Data set', 'ref', 'DataSet'], ['values', 'Column = expression', 'pairs'], ['onlyIfAbsent', 'Only when no row with this key exists', 'bool'],
      ['into', 'Put the stored row in', 'text']] },
  remove: { label: 'Remove', group: 'store', hint: 'Delete a row of a data set',
    fields: [['dataset', 'Data set', 'ref', 'DataSet'], ['key', 'Key of the row (expression)', 'expr']] },
  forEach: { label: 'For each', group: 'control', hint: 'Repeat nested steps for every item of a list',
    fields: [['in', 'List (expression)', 'expr'], ['as', 'Name of the current item', 'text']] },
  parallel: { label: 'In parallel', group: 'control', hint: 'Run the nested steps at once, each on its own copy of the data; what each changes meets afterwards, and the first to end the flow decides',
    fields: [] },
  flow: { label: 'Sub-flow', group: 'control', hint: 'Run another flow',
    fields: [['ref', 'Flow', 'ref', 'Flow'], ['refExpr', 'Or: flow named by an expression', 'expr']] },
  wait: { label: 'Wait', group: 'end', hint: 'Stop until an answer arrives or a time has passed; the flow then runs again from the start',
    fields: [['code', 'Reason code (what is waited for)', 'text'], ['message', 'Message', 'text'], ['for', 'Field that an answer fills (empty when already there: no wait)', 'text'],
      ['retrySeconds', 'Run again after (seconds)', 'number'], ['timeoutSeconds', 'Give up after (seconds; empty: the installation\'s limit)', 'number']] },
  task: { label: 'Human task', group: 'end', hint: 'Hold the payment for a person in an operator queue, with instructions; released or rejected from the review queue with a second person',
    fields: [['code', 'Reason code (the hold code a release overrides)', 'text'], ['queue', 'Operator queue', 'text'], ['message', 'Message', 'text'], ['instructions', 'Instructions for the person (expression)', 'expr']] },
  end: { label: 'End', group: 'end', hint: 'Stop the flow with a status',
    fields: [['status', 'Status', 'select', FLOW_END_STATUSES], ['code', 'Reason code', 'text'], ['message', 'Message', 'text']] },
};

const FLOW_NEW_STEP = {
  rules: () => ({ onViolation: 'reject' }),
  set: () => ({ values: {} }),
  decide: () => ({ onNoMatch: 'reject' }),
  call: () => ({ request: {}, into: 'txn.checks.answer' }),
  find: () => ({ where: {}, first: true, into: 'found' }),
  save: () => ({ values: {} }),
  forEach: () => ({ in: 'txn.items', as: 'item', steps: [] }),
  parallel: () => ({ steps: [] }),
  end: () => ({ status: 'REJECTED', code: '', message: '' }),
  wait: () => ({ code: 'ANSWER', message: '' }),
  task: () => ({ code: 'REVIEW', queue: 'Payments desk', message: '' }),
};

function flowDesigner(def, models, onChange) {
  if (!Array.isArray(def.steps)) def.steps = [];
  let selected = null;      // the step shown in the form; null shows the properties of the flow itself
  let drag = null;          // { type } for a new step from the palette, { step, list } for a step being moved
  const canvas = h('div', { class: 'fd-canvas' });
  const form = h('div', { class: 'fd-form' });

  const allSteps = (list, out = []) => { for (const s of list) { out.push(s); if (Array.isArray(s.steps)) allSteps(s.steps, out); } return out; };
  const listOf = (step, list = def.steps) => {
    if (list.includes(step)) return list;
    for (const s of list) { if (Array.isArray(s.steps)) { const found = listOf(step, s.steps); if (found) return found; } }
    return null;
  };
  const contains = (step, list) => Array.isArray(step.steps) && (step.steps === list || step.steps.some(s => contains(s, list)));
  const freshId = (type) => {
    const used = new Set(allSteps(def.steps).map(s => s.id));
    for (let n = 1; ; n++) { const id = type + n; if (!used.has(id)) return id; }
  };
  const changed = (structure) => { drawCanvas(); if (structure) drawForm(); onChange(); };

  const insert = (list, index, step) => { list.splice(index, 0, step); selected = step; changed(true); };
  const addNew = (type, list, index) => insert(list, index, Object.assign({ id: freshId(type), type }, (FLOW_NEW_STEP[type] || (() => ({})))()));
  const move = (step, from, to, index) => {
    if (step === undefined || contains(step, to)) return;          // a step cannot be moved into itself
    const at = from.indexOf(step);
    from.splice(at, 1);
    to.splice(from === to && at < index ? index - 1 : index, 0, step);
    selected = step;
    changed(true);
  };

  function summary(s) {
    const short = v => String(v).split('.').pop();
    if (s.ref) return short(s.ref);
    if (s.refExpr) return s.refExpr;
    if (s.connector) return short(s.connector);
    if (s.dataset) return short(s.dataset);
    if (s.type === 'end') return [s.status, s.code].filter(Boolean).join(' ');
    if (s.type === 'forEach') return (s.as || 'item') + ' in ' + (s.in || '');
    if (s.type === 'parallel') return (s.steps || []).length + ' branch(es)';
    if (s.type === 'set') return Object.keys(s.values || {}).join(', ');
    return '';
  }

  function dropZone(list, index) {
    const zone = h('div', { class: 'fd-drop' });
    zone.addEventListener('dragover', e => { if (drag) { e.preventDefault(); e.stopPropagation(); zone.classList.add('over'); } });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', e => {
      e.preventDefault(); e.stopPropagation();
      zone.classList.remove('over');
      const d = drag; drag = null;
      if (!d) return;
      if (d.type) addNew(d.type, list, index); else move(d.step, d.list, list, index);
    });
    return zone;
  }

  function card(step, list) {
    const meta = FLOW_STEPS[step.type] || { label: step.type || '?', group: 'control' };
    const head = h('div', { class: 'fd-head', tabindex: '0', role: 'button', 'aria-pressed': step === selected ? 'true' : 'false' },
      h('span', { class: 'fd-type' }, meta.label), h('b', {}, step.id || '(no id)'), h('span', { class: 'fd-sum' }, summary(step)));
    const el = h('div', { class: 'fd-step g-' + meta.group + (step === selected ? ' on' : ''), draggable: 'true', 'data-step': step.id || '' },
      head,
      step.when ? h('div', { class: 'fd-when' }, 'when ', h('span', { class: 'mono' }, step.when)) : null,
      step.type === 'forEach' || step.type === 'parallel' ? stepList(step.steps || (step.steps = [])) : null);
    const choose = e => { e.stopPropagation(); selected = step; drawCanvas(); drawForm(); };
    el.addEventListener('click', choose);
    head.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(e); } });
    el.addEventListener('dragstart', e => { e.stopPropagation(); drag = { step, list }; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', step.id || ''); });
    el.addEventListener('dragend', () => { drag = null; });
    return el;
  }

  function stepList(list) {
    const el = h('div', { class: 'fd-list' }, dropZone(list, 0));
    list.forEach((s, i) => el.append(card(s, list), dropZone(list, i + 1)));
    if (!list.length) el.append(h('div', { class: 'fd-empty' }, 'Drag a step here'));
    return el;
  }

  function drawCanvas() {
    canvas.replaceChildren(
      h('div', { class: 'fd-terminal' + (selected === null ? ' on' : ''), role: 'button', tabindex: '0', onclick: () => { selected = null; drawCanvas(); drawForm(); } }, 'Start: ' + (def.name || 'flow')),
      stepList(def.steps),
      h('div', { class: 'fd-terminal' }, 'End: ' + (def.completeStatus || 'COMPLETED')));
  }

  // ---- the form ----

  const setOrDrop = (target, key, value) => { if (value === '' || value === false || value === null || value === undefined) delete target[key]; else target[key] = value; };

  function input(target, key, kind, extra) {
    if (kind === 'bool') {
      const box = h('input', { type: 'checkbox' });
      box.checked = target[key] === true || target[key] === 'true';
      box.addEventListener('change', () => { setOrDrop(target, key, box.checked); changed(false); });
      return box;
    }
    if (kind === 'select' || kind === 'ref') {
      const options = kind === 'ref' ? models.filter(m => m.kind === extra).map(m => m.name).sort() : extra.slice();
      const current = target[key] === undefined ? '' : String(target[key]);
      if (current && !options.includes(current)) options.unshift(current);
      const sel = h('select', {}, h('option', { value: '' }, kind === 'ref' ? '' : '(default)'), options.map(o => h('option', { value: o }, o)));
      sel.value = current;
      sel.addEventListener('change', () => { setOrDrop(target, key, sel.value); changed(false); });
      return sel;
    }
    const box = kind === 'expr'
      ? h('textarea', { rows: 2, spellcheck: 'false', value: target[key] === undefined ? '' : String(target[key]) })
      : h('input', { type: kind === 'number' ? 'number' : 'text', spellcheck: 'false', value: target[key] === undefined ? '' : String(target[key]) });
    box.addEventListener('input', () => {
      setOrDrop(target, key, kind === 'number' && box.value !== '' ? Number(box.value) : box.value);
      changed(false);
    });
    return box;
  }

  function pairs(target, key) {
    let rows = Object.entries(target[key] || {}).map(([k, v]) => [k, String(v)]);
    const box = h('div', { class: 'fd-pairs' });
    const store = () => { target[key] = Object.fromEntries(rows.filter(r => r[0] !== '')); changed(false); };
    const draw = () => box.replaceChildren(
      ...rows.map((r, i) => {
        const name = h('input', { type: 'text', spellcheck: 'false', value: r[0], placeholder: 'field', 'aria-label': 'field' });
        const value = h('input', { type: 'text', spellcheck: 'false', class: 'mono', value: r[1], placeholder: 'expression', 'aria-label': 'expression' });
        name.addEventListener('input', () => { r[0] = name.value.trim(); store(); });
        value.addEventListener('input', () => { r[1] = value.value; store(); });
        return h('div', { class: 'fd-pair' }, name, h('span', {}, '='), value,
          h('button', { type: 'button', title: 'Remove this line', 'aria-label': 'Remove this line', onclick: () => { rows.splice(i, 1); store(); draw(); } }, '×'));
      }),
      h('button', { type: 'button', onclick: () => { rows.push(['', '']); draw(); box.querySelector('.fd-pair:last-of-type input').focus(); } }, 'Add line'));
    draw();
    return box;
  }

  function field(label, control) {
    return control.type === 'checkbox' ? h('label', { class: 'fd-check' }, control, ' ' + label) : h('div', {}, h('label', {}, label), control);
  }

  function drawForm() {
    if (selected === null) {
      form.replaceChildren(h('h2', {}, 'Flow'),
        field('Name (dotted, for example payments.flows.MyFlow)', input(def, 'name', 'text')),
        field('Description', input(def, 'description', 'expr')),
        field('Status when every step has run', input(def, 'completeStatus', 'select', FLOW_END_STATUSES)),
        h('p', { class: 'sub' }, 'Choose a step to edit it. Drag a step type from the left onto the canvas, or click it: it is added after the chosen step, or inside it when that is a For each.'));
      return;
    }
    const step = selected;
    const list = listOf(step) || def.steps;
    const at = list.indexOf(step);
    const meta = FLOW_STEPS[step.type];
    const idBox = input(step, 'id', 'text');
    form.replaceChildren(
      h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, (meta ? meta.label : step.type) + ' step'), h('span', { class: 'spacer' }),
        h('button', { type: 'button', disabled: at === 0, onclick: () => move(step, list, list, at - 1) }, 'Move up'),
        h('button', { type: 'button', disabled: at === list.length - 1, onclick: () => move(step, list, list, at + 2) }, 'Move down'),
        h('button', { type: 'button', onclick: () => insert(list, at + 1, Object.assign(JSON.parse(JSON.stringify(step)), { id: freshId(step.type) })) }, 'Duplicate'),
        h('button', { type: 'button', class: 'danger', onclick: () => { list.splice(at, 1); selected = list[Math.min(at, list.length - 1)] || null; changed(true); } }, 'Delete')),
      meta ? h('p', { class: 'sub' }, meta.hint) : h('div', { class: 'note warn' }, 'The designer does not know the step type "' + step.type + '". Edit it on the Model tab.'),
      field('Id (unique in the flow)', idBox),
      field('Only when (expression; empty: always)', input(step, 'when', 'expr')),
      ...(meta ? meta.fields.map(([key, label, kind, extra]) => field(label, kind === 'pairs' ? pairs(step, key) : input(step, key, kind, extra))) : []));
  }

  // ---- the palette ----

  const palette = h('div', { class: 'fd-palette' }, h('div', { class: 'kind' }, 'Steps'),
    Object.entries(FLOW_STEPS).map(([type, meta]) => {
      const b = h('button', { type: 'button', class: 'fd-tool g-' + meta.group, draggable: 'true', title: meta.hint, 'data-tool': type }, meta.label);
      b.addEventListener('dragstart', e => { drag = { type }; e.dataTransfer.effectAllowed = 'copy'; e.dataTransfer.setData('text/plain', type); });
      b.addEventListener('dragend', () => { drag = null; });
      b.addEventListener('click', () => {
        // inside the chosen step when that is a loop, otherwise after it
        if (selected && (selected.type === 'forEach' || selected.type === 'parallel')) { addNew(type, selected.steps || (selected.steps = []), (selected.steps || []).length); return; }
        const list = selected ? (listOf(selected) || def.steps) : def.steps;
        addNew(type, list, selected ? list.indexOf(selected) + 1 : list.length);
      });
      return b;
    }));

  drawCanvas();
  drawForm();
  return h('div', { class: 'fd' }, palette, canvas, form);
}

// ---- shared by the rule set and decision table designers ----

function designerHeader(def, changed, nameHint) {
  const name = h('input', { type: 'text', spellcheck: 'false', value: def.name || '', 'aria-label': 'Model name' });
  name.addEventListener('input', () => { def.name = name.value.trim(); changed(); });
  const description = h('textarea', { rows: 2, value: (def.description || '').trim(), 'aria-label': 'Description' });
  description.addEventListener('input', () => { if (description.value.trim()) def.description = description.value.trim(); else delete def.description; changed(); });
  return h('div', { class: 'cols dh' }, h('div', {}, h('label', {}, 'Name (dotted, for example ' + nameHint + ')'), name), h('div', {}, h('label', {}, 'Description'), description));
}

function freshItemId(items, prefix) {
  const used = new Set(items.map(i => i.id));
  for (let n = 1; ; n++) { const id = prefix + n; if (!used.has(id)) return id; }
}

// ---- rule set designer: a list of rules, each edited in a form ----

function ruleSetDesigner(def, models, onChange) {
  if (!Array.isArray(def.rules)) def.rules = [];
  const rules = def.rules;
  let selected = rules[0] || null;
  let dragged = null;
  const list = h('div', { class: 'fd-canvas' });
  const form = h('div', { class: 'fd-form' });
  const changed = (structure) => { drawList(); if (structure) drawForm(); onChange(); };
  const setOrDrop = (target, key, value) => { if (value === '' || value === null || value === undefined) delete target[key]; else target[key] = value; };
  const moveTo = (rule, index) => {
    const at = rules.indexOf(rule);
    if (at < 0) return;
    rules.splice(at, 1);
    rules.splice(at < index ? index - 1 : index, 0, rule);
    selected = rule;
    changed(true);
  };
  const add = () => {
    const rule = { id: freshItemId(rules, 'RULE_') };
    rules.splice(selected ? rules.indexOf(selected) + 1 : rules.length, 0, rule);
    selected = rule;
    changed(true);
  };

  function drop(index) {
    const zone = h('div', { class: 'fd-drop' });
    zone.addEventListener('dragover', e => { if (dragged) { e.preventDefault(); zone.classList.add('over'); } });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', e => { e.preventDefault(); const rule = dragged; dragged = null; if (rule) moveTo(rule, index); });
    return zone;
  }

  function drawList() {
    const cards = [drop(0)];
    rules.forEach((rule, i) => {
      const card = h('div', { class: 'fd-step ' + (rule.severity === 'warning' ? 'g-external' : 'g-check') + (rule === selected ? ' on' : ''), draggable: 'true', tabindex: '0',
        role: 'button', 'data-rule': rule.id || '', 'aria-pressed': rule === selected ? 'true' : 'false' },
        h('div', { class: 'fd-head' }, h('b', {}, rule.id || '(no id)'), h('span', { class: 'fd-sum' }, [rule.code, rule.severity === 'warning' ? 'warning' : null].filter(Boolean).join(' · '))),
        h('div', { class: 'fd-when' }, rule.when ? ['when ', h('span', { class: 'mono' }, rule.when), ': '] : null, h('span', { class: 'mono' }, rule.assert || '(nothing to check yet)')));
      const choose = () => { selected = rule; drawList(); drawForm(); };
      card.addEventListener('click', choose);
      card.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(); } });
      card.addEventListener('dragstart', e => { dragged = rule; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', rule.id || ''); });
      card.addEventListener('dragend', () => { dragged = null; });
      cards.push(card, drop(i + 1));
    });
    if (!rules.length) cards.push(h('div', { class: 'fd-empty' }, 'No rules yet'));
    list.replaceChildren(...cards);
  }

  function drawForm() {
    if (!selected) { form.replaceChildren(h('p', { class: 'sub' }, 'Add a rule, or choose one to edit it. A rule states what must be true; when it is not, the rule fails with its code and message.')); return; }
    const rule = selected;
    const at = rules.indexOf(rule);
    const box = (key, label, multi) => {
      const el = multi ? h('textarea', { rows: 2, spellcheck: 'false', value: rule[key] === undefined ? '' : String(rule[key]) })
        : h('input', { type: 'text', spellcheck: 'false', value: rule[key] === undefined ? '' : String(rule[key]) });
      el.setAttribute('aria-label', label);
      el.addEventListener('input', () => { setOrDrop(rule, key, el.value); changed(false); });
      return h('div', {}, h('label', {}, label), el);
    };
    const severity = h('select', { 'aria-label': 'Severity' }, h('option', { value: '' }, 'error (the payment is rejected)'), h('option', { value: 'warning' }, 'warning (recorded, the payment continues)'));
    severity.value = rule.severity === 'warning' ? 'warning' : '';
    severity.addEventListener('change', () => { setOrDrop(rule, 'severity', severity.value); changed(false); });
    form.replaceChildren(
      h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, 'Rule'), h('span', { class: 'spacer' }),
        h('button', { type: 'button', disabled: at === 0, onclick: () => moveTo(rule, at - 1) }, 'Move up'),
        h('button', { type: 'button', disabled: at === rules.length - 1, onclick: () => moveTo(rule, at + 2) }, 'Move down'),
        h('button', { type: 'button', onclick: () => { const copy = Object.assign(JSON.parse(JSON.stringify(rule)), { id: freshItemId(rules, 'RULE_') }); rules.splice(at + 1, 0, copy); selected = copy; changed(true); } }, 'Duplicate'),
        h('button', { type: 'button', class: 'danger', onclick: () => { rules.splice(at, 1); selected = rules[Math.min(at, rules.length - 1)] || null; changed(true); } }, 'Delete')),
      box('id', 'Id (unique in the rule set)'),
      box('when', 'Only checked when (expression; empty: always)', true),
      box('assert', 'Must be true (expression)', true),
      box('code', 'Reason code reported when it fails (empty: the id)'),
      box('message', 'Message reported when it fails'),
      h('div', {}, h('label', {}, 'Severity'), severity));
  }

  drawList();
  drawForm();
  return h('div', {}, designerHeader(def, onChange, 'payments.rules.MyRules'),
    h('div', { class: 'fd fd-two', style: 'margin-top:12px' },
      h('div', {}, h('div', { class: 'row', style: 'margin-bottom:8px' }, h('button', { type: 'button', onclick: add }, 'Add rule'),
        h('span', { class: 'sub' }, 'Every rule is checked. Drag to change the order.')), list),
      form));
}

// ---- decision table designer: rows are tried from the top, columns are the fields a row sets ----

function decisionTableDesigner(def, models, onChange) {
  if (!Array.isArray(def.rows)) def.rows = [];
  const rows = def.rows;
  const columns = [];
  for (const row of rows) for (const key of Object.keys(row.set || {})) if (!columns.includes(key)) columns.push(key);
  let dragged = null;
  const grid = h('div', { class: 'dt-wrap' });
  const note = h('div', {});

  // a row keeps only the cells that are filled, in the order of the columns
  const store = (row, cells) => {
    const set = {};
    for (const c of columns) if (cells[c] !== undefined && String(cells[c]) !== '') set[c] = cells[c];
    if (Object.keys(set).length) row.set = set; else delete row.set;
  };
  const check = () => {
    const open = rows.findIndex(r => r.when === undefined || String(r.when).trim() === '');
    note.replaceChildren(open >= 0 && open < rows.length - 1
      ? h('div', { class: 'note warn' }, 'Row ' + (rows[open].id || open + 1) + ' has no condition, so it matches everything: it must be the last row.') : '');
  };
  const changed = (structure) => { if (structure) draw(); check(); onChange(); };
  const moveTo = (row, index) => {
    const at = rows.indexOf(row);
    if (at < 0) return;
    rows.splice(at, 1);
    rows.splice(at < index ? index - 1 : index, 0, row);
    changed(true);
  };
  const cell = (value, label, onInput, cls) => {
    const el = h('input', { type: 'text', spellcheck: 'false', class: cls || 'mono', value: value === undefined ? '' : String(value), 'aria-label': label });
    el.addEventListener('input', () => onInput(el.value));
    return el;
  };

  function draw() {
    const head = h('tr', {}, h('th', {}), h('th', {}, 'Row'), h('th', {}, 'When (expression)'),
      columns.map((c, i) => {
        const name = h('input', { type: 'text', spellcheck: 'false', class: 'mono', value: c, 'aria-label': 'Field set by column ' + (i + 1), placeholder: 'txn.field' });
        name.addEventListener('change', () => {       // on leaving the box, so that half a path is never written to every row
          const to = name.value.trim();
          if (!to || to === c || columns.includes(to)) { name.value = c; return; }
          const before = rows.map(row => Object.assign({}, row.set));
          columns[i] = to;
          rows.forEach((row, r) => { const cells = before[r]; if (cells[c] !== undefined) { cells[to] = cells[c]; delete cells[c]; } store(row, cells); });
          changed(true);
        });
        return h('th', {}, h('div', { class: 'dt-col' }, h('span', { class: 'sub' }, 'set'), name,
          h('button', { type: 'button', title: 'Remove this column', 'aria-label': 'Remove column ' + c, onclick: () => {
            columns.splice(i, 1);
            for (const row of rows) store(row, Object.assign({}, row.set));
            changed(true);
          } }, '×')));
      }),
      h('th', {}, h('button', { type: 'button', onclick: () => {
        const path = (prompt('Field this column sets, for example txn.route.channel:') || '').trim();
        if (!path || columns.includes(path)) return;
        columns.push(path);
        draw();
      } }, 'Add column')));
    const body = rows.map((row, at) => {
      const handle = h('span', { class: 'dt-handle', draggable: 'true', title: 'Drag to move this row', 'aria-hidden': 'true' }, '⋮⋮');
      const label = row.id || String(at + 1);
      const tr = h('tr', { 'data-row': row.id || '' },
        h('td', {}, handle),
        h('td', {}, cell(row.id, 'Id of row ' + (at + 1), v => { row.id = v.trim(); changed(false); }, 'dt-id')),
        h('td', {}, cell(row.when, 'When of row ' + label, v => { if (v.trim()) row.when = v; else delete row.when; changed(false); })),
        columns.map(c => h('td', {}, cell((row.set || {})[c], c + ' of row ' + label, v => {
          const cells = Object.assign({}, row.set); cells[c] = v; store(row, cells); changed(false);
        }))),
        h('td', { class: 'dt-actions' },
          h('button', { type: 'button', disabled: at === 0, title: 'Move up', 'aria-label': 'Move row ' + label + ' up', onclick: () => moveTo(row, at - 1) }, '↑'),
          h('button', { type: 'button', disabled: at === rows.length - 1, title: 'Move down', 'aria-label': 'Move row ' + label + ' down', onclick: () => moveTo(row, at + 2) }, '↓'),
          h('button', { type: 'button', class: 'danger', title: 'Delete this row', 'aria-label': 'Delete row ' + label, onclick: () => { rows.splice(at, 1); changed(true); } }, '×')));
      handle.addEventListener('dragstart', e => { dragged = row; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', row.id || ''); e.dataTransfer.setDragImage(tr, 10, 10); });
      handle.addEventListener('dragend', () => { dragged = null; });
      tr.addEventListener('dragover', e => { if (dragged && dragged !== row) { e.preventDefault(); tr.classList.add('over'); } });
      tr.addEventListener('dragleave', () => tr.classList.remove('over'));
      tr.addEventListener('drop', e => { e.preventDefault(); tr.classList.remove('over'); const moved = dragged; dragged = null; if (moved) moveTo(moved, rows.indexOf(row)); });
      return tr;
    });
    grid.replaceChildren(h('table', { class: 'dt' }, h('thead', {}, head), h('tbody', {}, body)));
  }

  draw();
  check();
  return h('div', {}, designerHeader(def, onChange, 'payments.routing.MyRouting'),
    h('p', { class: 'sub', style: 'margin:12px 0 6px' }, 'Rows are tried from the top and the first one whose condition holds wins: it sets the fields of its columns. '
      + 'A cell is an expression, so text is written in quotes, for example \'SWIFT\'. An empty cell sets nothing. Dropping a row on another puts it in front of that row.'),
    grid, note,
    h('div', { class: 'row', style: 'margin-top:8px' }, h('button', { type: 'button', onclick: () => { rows.push({ id: freshItemId(rows, 'ROW_') }); changed(true); } }, 'Add row')));
}

const MODEL_DESIGNERS = { Flow: flowDesigner, RuleSet: ruleSetDesigner, DecisionTable: decisionTableDesigner };

// ---- mapping designer: rules that fill a record, nested inside loops and appended records ----

// a rule is recognised by the key it has; 'forEach' comes first because a loop may also have 'append'
const MAPPING_RULES = {
  set: { label: 'Set field', group: 'data', hint: 'Write a value into a field of the record being built. An empty value writes nothing.' },
  let: { label: 'Variable', group: 'store', hint: 'Name a value so that later rules can use it.' },
  forEach: { label: 'For each', group: 'control', hint: 'Repeat the rules inside for every item of a list. With a target list, each item becomes a new record in it.' },
  append: { label: 'Add record', group: 'check', hint: 'Add one new record to a list, filled by the rules inside.' },
  field: { label: 'MT field', group: 'external', hint: 'Add a field to the SWIFT MT message being built. Fields are written in the order of the rules.' },
};
const MAPPING_NEW = {
  set: () => ({ set: '', value: '' }),
  let: () => ({ let: '', value: '' }),
  forEach: () => ({ forEach: '', as: 'item', rules: [] }),
  append: () => ({ append: '', rules: [] }),
  field: () => ({ field: '', value: '' }),
};

function mappingRuleKind(rule) {
  return ['forEach', 'set', 'let', 'append', 'field'].find(k => rule[k] !== undefined && rule[k] !== null) || 'set';
}

function mappingDesigner(def, models, onChange) {
  if (!Array.isArray(def.rules)) def.rules = [];
  let selected = null;
  let drag = null;
  const canvas = h('div', { class: 'fd-canvas' });
  const form = h('div', { class: 'fd-form' });
  const isContainer = (rule) => ['forEach', 'append'].includes(mappingRuleKind(rule));
  const listOf = (rule, list = def.rules) => {
    if (list.includes(rule)) return list;
    for (const r of list) { if (Array.isArray(r.rules)) { const found = listOf(rule, r.rules); if (found) return found; } }
    return null;
  };
  const contains = (rule, list) => Array.isArray(rule.rules) && (rule.rules === list || rule.rules.some(r => contains(r, list)));
  const changed = (structure) => { drawCanvas(); if (structure) drawForm(); onChange(); };
  const insert = (list, index, rule) => { list.splice(index, 0, rule); selected = rule; changed(true); };
  const move = (rule, from, to, index) => {
    if (contains(rule, to)) return;
    const at = from.indexOf(rule);
    from.splice(at, 1);
    to.splice(from === to && at < index ? index - 1 : index, 0, rule);
    selected = rule;
    changed(true);
  };

  function summary(rule) {
    const kind = mappingRuleKind(rule);
    const value = rule.value === undefined ? '' : String(rule.value);
    if (kind === 'set') return [h('b', {}, rule.set || '(no field)'), h('span', { class: 'fd-sum mono' }, '= ' + value)];
    if (kind === 'let') return [h('b', {}, rule.let || '(no name)'), h('span', { class: 'fd-sum mono' }, '= ' + value)];
    if (kind === 'field') return [h('b', {}, ':' + (rule.field || '??') + ':'), h('span', { class: 'fd-sum mono' }, '= ' + value)];
    if (kind === 'append') return [h('b', {}, rule.append || '(no list)')];
    return [h('b', {}, (rule.as || 'item') + ' in'), h('span', { class: 'fd-sum mono' }, String(rule.forEach || '')), rule.append ? h('span', { class: 'fd-sum' }, '→ ' + rule.append) : null];
  }

  function dropZone(list, index) {
    const zone = h('div', { class: 'fd-drop' });
    zone.addEventListener('dragover', e => { if (drag) { e.preventDefault(); e.stopPropagation(); zone.classList.add('over'); } });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', e => {
      e.preventDefault(); e.stopPropagation();
      zone.classList.remove('over');
      const d = drag; drag = null;
      if (!d) return;
      if (d.kind) insert(list, index, MAPPING_NEW[d.kind]()); else move(d.rule, d.list, list, index);
    });
    return zone;
  }

  function card(rule, list) {
    const kind = mappingRuleKind(rule);
    const meta = MAPPING_RULES[kind];
    const key = kind + ':' + String(rule[kind] === undefined ? '' : rule[kind]);
    const head = h('div', { class: 'fd-head', tabindex: '0', role: 'button', 'aria-pressed': rule === selected ? 'true' : 'false' },
      h('span', { class: 'fd-type' }, meta.label), summary(rule));
    const el = h('div', { class: 'fd-step g-' + meta.group + (rule === selected ? ' on' : ''), draggable: 'true', 'data-rule': key },
      head,
      rule.when ? h('div', { class: 'fd-when' }, 'when ', h('span', { class: 'mono' }, String(rule.when))) : null,
      isContainer(rule) ? ruleList(rule.rules || (rule.rules = [])) : null);
    const choose = e => { e.stopPropagation(); selected = rule; drawCanvas(); drawForm(); };
    el.addEventListener('click', choose);
    head.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(e); } });
    el.addEventListener('dragstart', e => { e.stopPropagation(); drag = { rule, list }; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', key); });
    el.addEventListener('dragend', () => { drag = null; });
    return el;
  }

  function ruleList(list) {
    const el = h('div', { class: 'fd-list' }, dropZone(list, 0));
    list.forEach((r, i) => el.append(card(r, list), dropZone(list, i + 1)));
    if (!list.length) el.append(h('div', { class: 'fd-empty' }, 'Drag a rule here'));
    return el;
  }

  function drawCanvas() {
    canvas.replaceChildren(
      h('div', { class: 'fd-terminal' + (selected === null ? ' on' : ''), role: 'button', tabindex: '0', onclick: () => { selected = null; drawCanvas(); drawForm(); } },
        'Builds: ' + (def.target ? def.target : 'a new record') + ' (' + (def.name || 'mapping') + ')'),
      ruleList(def.rules));
  }

  const box = (target, key, label, multi, placeholder) => {
    const el = multi ? h('textarea', { rows: 2, spellcheck: 'false', value: target[key] === undefined || target[key] === null ? '' : String(target[key]) })
      : h('input', { type: 'text', spellcheck: 'false', value: target[key] === undefined || target[key] === null ? '' : String(target[key]), placeholder: placeholder || '' });
    el.setAttribute('aria-label', label);
    return [el, h('div', {}, h('label', {}, label), el)];
  };
  // the key that says what a rule is always stays, even when empty; any other empty key is dropped
  const bind = (el, target, key, keep) => el.addEventListener('input', () => {
    if (el.value === '' && !keep) delete target[key]; else target[key] = el.value;
    changed(false);
  });
  const input = (target, key, label, multi, keep, placeholder) => { const [el, wrapped] = box(target, key, label, multi, placeholder); bind(el, target, key, keep); return wrapped; };

  function drawForm() {
    if (selected === null) {
      form.replaceChildren(h('h2', {}, 'Mapping'),
        input(def, 'name', 'Name (dotted, for example payments.maps.MyMapping)', false, true),
        input(def, 'description', 'Description', true),
        input(def, 'target', 'Fill an existing record instead of building a new one (a path such as txn; empty: a new record)', false, false, 'empty: a new record'),
        h('p', { class: 'sub' }, 'Choose a rule to edit it. Drag a rule type from the left onto the canvas, or click it: it is added after the chosen rule, or inside it when that is a loop or an added record.'));
      return;
    }
    const rule = selected;
    const kind = mappingRuleKind(rule);
    const list = listOf(rule) || def.rules;
    const at = list.indexOf(rule);
    const fields = {
      set: () => [input(rule, 'set', 'Field to write (a path such as debtor.name)', false, true), input(rule, 'value', 'Value (expression)', true, true)],
      let: () => [input(rule, 'let', 'Name of the variable', false, true), input(rule, 'value', 'Value (expression)', true, true)],
      field: () => [input(rule, 'field', 'Field tag (for example 20, 32A, 59)', false, true), input(rule, 'value', 'Value (expression)', true, true)],
      append: () => [input(rule, 'append', 'List to add the record to (a path such as messages)', false, true)],
      forEach: () => [input(rule, 'forEach', 'List to go through (expression)', true, true), input(rule, 'as', 'Name of the current item', false, true),
        input(rule, 'append', 'Add a record per item to this list (empty: the rules inside write to the current record)')],
    }[kind]();
    form.replaceChildren(
      h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, MAPPING_RULES[kind].label), h('span', { class: 'spacer' }),
        h('button', { type: 'button', disabled: at === 0, onclick: () => move(rule, list, list, at - 1) }, 'Move up'),
        h('button', { type: 'button', disabled: at === list.length - 1, onclick: () => move(rule, list, list, at + 2) }, 'Move down'),
        h('button', { type: 'button', onclick: () => insert(list, at + 1, JSON.parse(JSON.stringify(rule))) }, 'Duplicate'),
        h('button', { type: 'button', class: 'danger', onclick: () => { list.splice(at, 1); selected = list[Math.min(at, list.length - 1)] || null; changed(true); } }, 'Delete')),
      h('p', { class: 'sub' }, MAPPING_RULES[kind].hint),
      ...fields,
      kind === 'let' ? h('p', { class: 'sub' }, 'A variable has no condition; use a conditional expression as its value.')
        : input(rule, 'when', kind === 'forEach' ? 'Only the items for which (expression; empty: all)' : 'Only when (expression; empty: always)', true));
  }

  const palette = h('div', { class: 'fd-palette' }, h('div', { class: 'kind' }, 'Rules'),
    Object.entries(MAPPING_RULES).map(([kind, meta]) => {
      const b = h('button', { type: 'button', class: 'fd-tool g-' + meta.group, draggable: 'true', title: meta.hint, 'data-tool': kind }, meta.label);
      b.addEventListener('dragstart', e => { drag = { kind }; e.dataTransfer.effectAllowed = 'copy'; e.dataTransfer.setData('text/plain', kind); });
      b.addEventListener('dragend', () => { drag = null; });
      b.addEventListener('click', () => {
        if (selected && isContainer(selected)) { insert(selected.rules || (selected.rules = []), (selected.rules || []).length, MAPPING_NEW[kind]()); return; }
        const list = selected ? (listOf(selected) || def.rules) : def.rules;
        insert(list, selected ? list.indexOf(selected) + 1 : list.length, MAPPING_NEW[kind]());
      });
      return b;
    }));

  drawCanvas();
  drawForm();
  return h('div', { class: 'fd' }, palette, canvas, form);
}

MODEL_DESIGNERS.Mapping = mappingDesigner;


// ---- forms for the configuration kinds: channels, connectors, data sets, APIs, reference tables ----
// A configuration model is a map of settings; the form shows every setting the kind knows, nested where the model is.
// field kinds: text, number, bool, select, ref (a model of a kind), list (comma-separated values), pairs (name = value),
// section (a nested map with fields), sections (a list of nested maps, each with a 'type' that chooses its fields)

const INBOUND_PURPOSES = ['instruction', 'acknowledgement', 'cancellation', 'resolution', 'return', 'callback', 'statement', 'recall', 'reversal',
  'statusEnquiry', 'deliveryNotification', 'investigation', 'requestToPay', 'mandate'];
const TRANSPORT_FIELDS = {
  folder: [['path', 'Folder below the data directory', 'text']],
  rest: [['apiKey', 'API key the caller sends (at least 16 characters; ${env.NAME:-} keeps it out of the model)', 'text']],
  http: [['url', 'URL to fetch from', 'text'], ['every', 'Fetch every (seconds)', 'number'], ['auth', 'Authentication', 'section', 'auth']],
  rabbitmq: [['uri', 'Broker URI', 'text'], ['queue', 'Queue', 'text'], ['enabled', 'Enabled (true, false or ${env.NAME:-false})', 'text'], ['receipt', 'Receipt back to the sender', 'section', 'receiptQueue']],
  sftp: [['host', 'Host', 'text'], ['port', 'Port', 'number'], ['username', 'User name', 'text'], ['password', 'Password (use ${env.NAME:-})', 'text'], ['keyFile', 'Private key file', 'text'],
    ['hostKey', 'Host key fingerprint (SHA256:...)', 'text'], ['path', 'Remote directory', 'text'], ['archive', 'Remote archive directory (empty: files are removed)', 'text'], ['every', 'Fetch every (seconds)', 'number']],
  kafka: [['bootstrapServers', 'Bootstrap servers', 'text'], ['topic', 'Topic', 'text'], ['groupId', 'Consumer group', 'text'], ['enabled', 'Enabled', 'text'], ['receipt', 'Receipt back to the sender', 'section', 'receiptTopic']],
  ibmmq: [['host', 'Host', 'text'], ['port', 'Port', 'number'], ['channel', 'Server-connection channel', 'text'], ['queueManager', 'Queue manager', 'text'], ['queue', 'Queue', 'text'],
    ['username', 'User name', 'text'], ['password', 'Password (use ${env.NAME:-})', 'text'], ['waitSeconds', 'Wait for a message (seconds)', 'number'], ['enabled', 'Enabled', 'text'], ['receipt', 'Receipt back to the sender', 'section', 'receiptQueue']],
};
const COMMON_FILE_FIELDS = [['checksum', 'Checksum companion file', 'select', ['sha256']], ['pgp', 'OpenPGP', 'section', 'pgp']];
const CONFIG_SECTIONS = {
  soap: [['operation', 'Operation name: the element in the SOAP Body', 'text']],
  auth: [['type', 'Type', 'select', ['basic', 'bearer', 'header', 'oauth2']], ['username', 'User name (basic)', 'text'], ['password', 'Password (basic; use ${env.NAME:-})', 'text'],
    ['token', 'Token (bearer; use ${env.NAME:-})', 'text'], ['name', 'Header name (header)', 'text'], ['value', 'Header value (header)', 'text'],
    ['tokenUrl', 'Token URL (oauth2)', 'text'], ['clientId', 'Client id (oauth2)', 'text'], ['clientSecret', 'Client secret (oauth2; use ${env.NAME:-})', 'text'], ['scope', 'Scope (oauth2)', 'text'],
    ['credentials', 'Client credentials sent as (oauth2)', 'select', ['basic', 'body']]],
  circuitBreaker: [['failures', 'Failures in a row before the circuit opens', 'number'], ['openSeconds', 'Seconds the circuit stays open', 'number']],
  receiptQueue: [['queue', 'Queue for the receipts', 'text']],
  receiptTopic: [['topic', 'Topic for the receipts', 'text']],
  pgp: [['decryptKeyFile', 'Our private key file (to decrypt what arrives)', 'text'], ['passphrase', 'Its passphrase (use ${env.NAME:-})', 'text'], ['verifyKeyFile', "Partner's public key file (to check signatures)", 'text'],
    ['encryptKeyFile', "Partner's public key file (to encrypt what leaves)", 'text'], ['signKeyFile', 'Our private key file (to sign what leaves)', 'text'], ['armor', 'ASCII armour (.asc)', 'bool']],
  destination: [['type', 'Type', 'select', ['folder', 'http', 'rabbitmq', 'sftp', 'kafka', 'ibmmq']], ['enabled', 'Enabled', 'text'], ['extension', 'File extension', 'text'],
    ['path', 'Folder or remote directory', 'text'], ['url', 'URL (http)', 'text'], ['auth', 'Authentication (http)', 'section', 'auth'],
    ['uri', 'Broker URI (rabbitmq)', 'text'], ['queue', 'Queue (rabbitmq, ibmmq)', 'text'], ['exchange', 'Exchange (rabbitmq)', 'text'], ['routingKey', 'Routing key (rabbitmq)', 'text'],
    ['host', 'Host (sftp, ibmmq)', 'text'], ['port', 'Port (sftp, ibmmq)', 'number'], ['username', 'User name', 'text'], ['password', 'Password (use ${env.NAME:-})', 'text'], ['hostKey', 'Host key fingerprint (sftp)', 'text'],
    ['bootstrapServers', 'Bootstrap servers (kafka)', 'text'], ['topic', 'Topic (kafka)', 'text'], ['channel', 'Server-connection channel (ibmmq)', 'text'], ['queueManager', 'Queue manager (ibmmq)', 'text'],
    ...COMMON_FILE_FIELDS],
  bulking: [['maxTransactions', 'Most payments in one file', 'number'], ['maxAmount', 'Most in one file (amount)', 'number'], ['every', 'Build a file every (seconds)', 'number'], ['priority', 'Priority handling', 'text']],
};
const CONFIG_KINDS = {
  Channel: { hint: 'Where messages come in or go out, and how they are read and answered.', nameHint: 'channels.MyChannel',
    fields: [['direction', 'Direction', 'select', ['inbound', 'outbound']], ['purpose', 'Purpose (inbound)', 'select', INBOUND_PURPOSES], ['format', 'Format', 'select', ['iso20022', 'swift.mt', 'json']],
      ['messageTypes', 'Message types (comma-separated, for example pain.001)', 'list'], ['mapping', 'Mapping that reads or writes the message', 'ref', 'Mapping'],
      ['validation', 'Rule set that checks an instruction', 'ref', 'RuleSet'], ['statusReport', 'Channel for status reports to the sender', 'ref', 'Channel'],
      ['duplicates', 'A possible duplicate is', 'select', ['HOLD', 'REJECT']], ['heldExpiryDays', 'Days a held payment waits before it expires', 'number'], ['maxBytes', 'Largest message (bytes)', 'number'],
      ['answerWithinSeconds', 'Answer expected within (seconds)', 'number'], ['sanctionsHit', 'A sanctions hit is', 'select', ['FREEZE', 'RETURN']], ['consoleOnly', 'Takes only what the Console form delivers', 'bool'],
      ['allowedFields', 'Fields a callback may fill (comma-separated)', 'list'], ['messageType', 'Message type built (outbound)', 'text'], ['scheme', 'Scheme (outbound)', 'text'],
      ['minAmount', 'Smallest amount the route takes (outbound)', 'number'], ['maxAmount', 'Largest amount the route takes (outbound)', 'number'],
      ['transport', 'Transports (inbound)', 'sections', TRANSPORT_FIELDS], ['destination', 'Destination (outbound)', 'section', 'destination'], ['bulking', 'Bulking (outbound)', 'section', 'bulking']] },
  Connector: { hint: 'An external system a flow calls.', nameHint: 'connectors.MySystem',
    fields: [['type', 'Type', 'select', ['http', 'soap', 'mock']], ['url', 'URL (placeholders like {account} are filled from the request)', 'text'], ['method', 'Method (http)', 'select', ['GET', 'POST', 'PUT', 'DELETE']],
      ['operation', 'Operation (soap)', 'text'], ['namespace', 'Namespace (soap)', 'text'], ['action', 'SOAPAction (soap)', 'text'], ['version', 'SOAP version', 'select', ['1.1', '1.2']],
      ['timeoutMs', 'Timeout (ms)', 'number'], ['retries', 'Retries', 'number'], ['retryDelayMs', 'Delay before a retry (ms)', 'number'],
      ['headers', 'Headers (name = value)', 'pairs'], ['auth', 'Authentication', 'section', 'auth'], ['circuitBreaker', 'Circuit breaker', 'section', 'circuitBreaker']] },
  Api: { hint: 'A REST endpoint under /api/x that serves a model.', nameHint: 'api.MyEndpoint',
    fields: [['method', 'Method', 'select', ['GET', 'POST', 'PUT', 'DELETE']], ['path', 'Path (for example /limits/{account})', 'text'], ['permission', 'Permission the caller needs', 'text'],
      ['approval', 'A call that changes data needs a second person', 'bool'], ['target', 'Model that serves it (flow, mapping, rule set or decision table)', 'text'],
      ['soap', 'Also a SOAP operation (POST /api/soap)', 'section', 'soap']] },
  DataSet: { hint: 'Stored data a flow reads and changes, or a read-only view of engine data.', nameHint: 'data.MyRows',
    fields: [['collection', 'Collection (its own rows)', 'text'], ['key', 'Key field', 'text'], ['source', 'Engine data it is a view of (instead of a collection)', 'text'],
      ['table', 'SQL table (in a relational database, instead of a collection)', 'text'], ['datasource', 'Data source of the configuration the table is in', 'text']] },
  ReferenceTable: { hint: 'Rows looked up by key with lookup(). The rows themselves are on the Rows (CSV) tab.', nameHint: 'reference.MyTable',
    fields: [['key', 'Key field', 'text']] },
  Schedule: { hint: 'A job at the times a cron expression names, in a time zone: once across processes.', nameHint: 'schedules.MyJob',
    fields: [['cron', 'When (cron: minute hour day month weekday, for example "0 18 * * 1-5")', 'text'], ['timezone', 'Time zone (for example Africa/Johannesburg)', 'text'],
      ['job', 'Job', 'select', ['closeDay', 'statement', 'accountReports', 'flow']], ['day', 'Day the job is for (closeDay, statement)', 'select', ['today', 'yesterday']],
      ['channel', 'Channel the statement goes out on (statement)', 'ref', 'Channel'], ['account', 'Account (statement)', 'text'],
      ['flow', 'Flow to run (flow)', 'ref', 'Flow'], ['scope', 'Data given to the flow (name = value)', 'pairs'], ['enabled', 'Enabled (true, false or ${env.NAME:-false})', 'text']] },
};

function configDesigner(kind) {
  return function (def, models, onChange) {
    const meta = CONFIG_KINDS[kind];
    const changed = () => onChange();
    const setOrDrop = (target, key, value) => { if (value === '' || value === false || value === null || value === undefined) delete target[key]; else target[key] = value; };
    const field = (label, control) => control.type === 'checkbox' ? h('label', { class: 'fd-check' }, control, ' ' + label) : h('div', {}, h('label', {}, label), control);
    function input(target, key, kind, extra) {
      if (kind === 'bool') {
        const box = h('input', { type: 'checkbox', 'aria-label': key });
        box.checked = target[key] === true || target[key] === 'true';
        box.addEventListener('change', () => { setOrDrop(target, key, box.checked); changed(); });
        return box;
      }
      if (kind === 'select' || kind === 'ref') {
        const options = kind === 'ref' ? models.filter(m => m.kind === extra).map(m => m.name).sort() : extra.slice();
        const current = target[key] === undefined ? '' : String(target[key]);
        if (current && !options.includes(current)) options.unshift(current);
        const sel = h('select', { 'aria-label': key }, h('option', { value: '' }, '(not set)'), options.map(o => h('option', { value: o }, o)));
        sel.value = current;
        sel.addEventListener('change', () => { setOrDrop(target, key, sel.value); changed(); });
        return sel;
      }
      if (kind === 'list') {
        const box = h('input', { type: 'text', spellcheck: 'false', 'aria-label': key, value: Array.isArray(target[key]) ? target[key].join(', ') : (target[key] || '') });
        box.addEventListener('input', () => { const items = box.value.split(',').map(x => x.trim()).filter(Boolean); setOrDrop(target, key, items.length ? items : ''); changed(); });
        return box;
      }
      const box = h('input', { type: kind === 'number' ? 'number' : 'text', spellcheck: 'false', 'aria-label': key, value: target[key] === undefined || target[key] === null ? '' : String(target[key]) });
      box.addEventListener('input', () => { setOrDrop(target, key, kind === 'number' && box.value !== '' && !isNaN(Number(box.value)) ? Number(box.value) : box.value); changed(); });
      return box;
    }
    function pairs(target, key) {
      let rows = Object.entries(target[key] || {}).map(([k, v]) => [k, String(v)]);
      const box = h('div', { class: 'fd-pairs' });
      const store = () => { const o = Object.fromEntries(rows.filter(r => r[0] !== '')); setOrDrop(target, key, Object.keys(o).length ? o : ''); changed(); };
      const draw = () => box.replaceChildren(
        ...rows.map((r, i) => {
          const name = h('input', { type: 'text', spellcheck: 'false', value: r[0], placeholder: 'name', 'aria-label': 'name' });
          const value = h('input', { type: 'text', spellcheck: 'false', class: 'mono', value: r[1], placeholder: 'value', 'aria-label': 'value' });
          name.addEventListener('input', () => { r[0] = name.value.trim(); store(); });
          value.addEventListener('input', () => { r[1] = value.value; store(); });
          return h('div', { class: 'fd-pair' }, name, h('span', {}, '='), value,
            h('button', { type: 'button', title: 'Remove this line', 'aria-label': 'Remove this line', onclick: () => { rows.splice(i, 1); store(); draw(); } }, '×'));
        }),
        h('button', { type: 'button', onclick: () => { rows.push(['', '']); draw(); } }, 'Add line'));
      draw();
      return box;
    }
    function control(target, [key, label, kind, extra]) {
      if (kind === 'pairs') return field(label, pairs(target, key));
      if (kind === 'section') return section(target, key, label, CONFIG_SECTIONS[extra]);
      if (kind === 'sections') return sections(target, key, label, extra);
      return field(label, input(target, key, kind, extra));
    }
    // a nested map: present or not; when present its own fields, and those of its type when it has one
    function section(target, key, label, fields) {
      const box = h('fieldset', { class: 'fd-section' }, h('legend', {}, label));
      const draw = () => {
        const present = target[key] !== undefined && target[key] !== null && typeof target[key] === 'object';
        const toggle = h('input', { type: 'checkbox', 'aria-label': label });
        toggle.checked = present;
        toggle.addEventListener('change', () => { if (toggle.checked) target[key] = {}; else delete target[key]; changed(); draw(); });
        box.replaceChildren(...[h('legend', {}, label), h('label', { class: 'fd-check' }, toggle, present ? ' Set' : ' Not set'),
          present ? fields.map(f => control(target[key], f)) : null].flat().filter(Boolean));
      };
      draw();
      return box;
    }
    // a list of nested maps, each with a type that chooses its fields
    function sections(target, key, label, byType) {
      const box = h('fieldset', { class: 'fd-section' }, h('legend', {}, label));
      const draw = () => {
        const list = Array.isArray(target[key]) ? target[key] : [];
        box.replaceChildren(...[h('legend', {}, label),
          list.map((item, i) => {
            const typeSel = h('select', { 'aria-label': label + ' ' + (i + 1) + ' type' }, Object.keys(byType).map(t => h('option', { value: t }, t)));
            typeSel.value = item.type || Object.keys(byType)[0];
            if (!item.type) item.type = typeSel.value;
            typeSel.addEventListener('change', () => { item.type = typeSel.value; changed(); draw(); });
            return h('div', { class: 'fd-item' },
              h('div', { class: 'row' }, h('label', {}, 'Type ', typeSel), h('span', { class: 'spacer' }),
                h('button', { type: 'button', class: 'danger', onclick: () => { list.splice(i, 1); setOrDrop(target, key, list.length ? list : ''); changed(); draw(); } }, 'Remove')),
              (byType[item.type] || []).map(f => control(item, f)), COMMON_FILE_FIELDS.filter(() => item.type === 'folder' || item.type === 'sftp').map(f => control(item, f)));
          }),
          h('button', { type: 'button', onclick: () => { const list2 = Array.isArray(target[key]) ? target[key] : (target[key] = []); list2.push({ type: Object.keys(byType)[0] }); changed(); draw(); } }, 'Add')].flat().filter(Boolean));
      };
      draw();
      return box;
    }
    const nameBox = h('input', { type: 'text', spellcheck: 'false', value: def.name || '', 'aria-label': 'Model name' });
    nameBox.addEventListener('input', () => { def.name = nameBox.value.trim(); changed(); });
    const description = h('textarea', { rows: 2, value: (def.description || '').trim(), 'aria-label': 'Description' });
    description.addEventListener('input', () => { if (description.value.trim()) def.description = description.value.trim(); else delete def.description; changed(); });
    return h('div', { class: 'fd fd-config' },
      h('div', { class: 'fd-form', style: 'max-width: 900px' },
        h('p', { class: 'sub' }, meta.hint + ' A setting left empty is left out of the model; a value of the form ${env.NAME:-default} is read from the environment at start.'),
        h('div', { class: 'cols dh' }, h('div', {}, h('label', {}, 'Name (dotted, for example ' + meta.nameHint + ')'), nameBox), h('div', {}, h('label', {}, 'Description'), description)),
        meta.fields.map(f => control(def, f))));
  };
}
for (const kind of Object.keys(CONFIG_KINDS)) MODEL_DESIGNERS[kind] = configDesigner(kind);


// ---- test case designer: the model under test, what is given, what the connectors answer, and what must hold ----

function testCaseDesigner(def, models, onChange) {
  const changed = () => onChange();
  const targets = models.filter(m => ['Flow', 'Mapping', 'RuleSet', 'DecisionTable'].includes(m.kind)).map(m => m.name).sort();
  const jsonBox = (key, label, rows) => {
    const box = h('textarea', { rows: String(rows), spellcheck: 'false', 'aria-label': label, class: 'mono' });
    box.value = def[key] === undefined ? '' : JSON.stringify(def[key], null, 2);
    const note = h('div', { class: 'sub' });
    box.addEventListener('input', () => {
      if (!box.value.trim()) { delete def[key]; note.textContent = ''; changed(); return; }
      try { def[key] = JSON.parse(box.value); note.textContent = ''; changed(); } catch (e) { note.textContent = 'Not JSON yet: ' + e.message; }
    });
    return h('div', {}, h('label', {}, label), box, note);
  };
  const target = h('select', { 'aria-label': 'Model under test' }, h('option', { value: '' }, '(choose)'), targets.map(t => h('option', { value: t }, t)));
  target.value = def.target || '';
  target.addEventListener('change', () => { if (target.value) def.target = target.value; else delete def.target; changed(); });
  // expectations: one expression per line, each must be true
  const expectations = h('textarea', { rows: '6', spellcheck: 'false', 'aria-label': 'Expectations (one expression per line)', class: 'mono' });
  expectations.value = Array.isArray(def.expect) ? def.expect.join('\n') : '';
  expectations.addEventListener('input', () => { const lines = expectations.value.split('\n').map(l => l.trim()).filter(Boolean); if (lines.length) def.expect = lines; else delete def.expect; changed(); });
  return h('div', { class: 'fd fd-config' },
    h('div', { class: 'fd-form', style: 'max-width: 900px' },
      h('p', { class: 'sub' }, 'A test case runs one model with the data given and checks the expectations. Connectors named under mocks answer with the given record; data sets listed under data start with those rows. Run all model tests from the Studio page or in the build.'),
      designerHeader(def, changed, 'tests.MyCase'),
      h('div', {}, h('label', {}, 'Model under test'), target),
      jsonBox('given', 'Given: the scope the model runs with (JSON, for example {"txn": {...}})', 10),
      jsonBox('mocks', 'Connector mocks: connector name to the answer it gives (JSON)', 4),
      jsonBox('data', 'Data set rows: data set name to a list of rows (JSON)', 4),
      h('div', {}, h('label', {}, 'Expectations (one expression per line; result.status, result.code, txn.… are available)'), expectations)));
}
MODEL_DESIGNERS.TestCase = testCaseDesigner;


// ---- message specification designer: the sequences of an MT message and the rules of each field ----

const SPEC_FIELD_KINDS = ['', 'date', 'bic', 'currencyAmount'];
function messageSpecDesigner(def, models, onChange) {
  const changed = () => onChange();
  def.format = 'swift.mt';
  // a format of several lines is a list in the model; in the form the lines are separated by |
  const lines = v => Array.isArray(v) ? v.join(' | ') : (v === undefined || v === null ? '' : String(v));
  const unlines = s => { const parts = s.split('|').map(x => x.trim()).filter(Boolean); return parts.length > 1 ? parts : (parts[0] || ''); };
  const setOrDrop = (t, k, v) => { if (v === '' || v === false || v === null || v === undefined) delete t[k]; else t[k] = v; };
  const text = (t, k, label, cls, multi) => {
    const box = h('input', { type: 'text', spellcheck: 'false', 'aria-label': label, class: cls || '', value: multi ? lines(t[k]) : (t[k] === undefined || t[k] === null ? '' : String(t[k])) });
    box.addEventListener('input', () => { setOrDrop(t, k, multi ? unlines(box.value) : box.value.trim()); changed(); });
    return box;
  };
  const check = (t, k, label, inverted) => {
    const box = h('input', { type: 'checkbox', 'aria-label': label });
    box.checked = inverted ? t[k] === false : t[k] === true;
    box.addEventListener('change', () => { if (inverted) { if (box.checked) t[k] = false; else delete t[k]; } else setOrDrop(t, k, box.checked); changed(); });
    return h('label', { class: 'fd-check' }, box, ' ' + label);
  };
  if (!Array.isArray(def.sequences)) { def.sequences = Array.isArray(def.fields) ? [{ id: 'A', fields: def.fields }] : []; delete def.fields; }
  const body = h('div', {});
  // the letter options of a field: letter = format (lines with |); an empty letter is the option without one
  function optionsBox(field) {
    let rows = Object.entries(field.options || {}).map(([k, v]) => [k, lines(v)]);
    const box = h('div', { class: 'fd-pairs' });
    const store = () => {
      const o = {};
      for (const r of rows) if (r[1].trim()) o[r[0]] = unlines(r[1]);
      setOrDrop(field, 'options', Object.keys(o).length ? o : '');
      changed();
    };
    const draw = () => box.replaceChildren(
      ...rows.map((r, i) => {
        const letter = h('input', { type: 'text', spellcheck: 'false', value: r[0], placeholder: 'letter', 'aria-label': 'option letter', style: 'width:4em' });
        const format = h('input', { type: 'text', spellcheck: 'false', class: 'mono', value: r[1], placeholder: 'format, lines with |', 'aria-label': 'option format' });
        letter.addEventListener('input', () => { r[0] = letter.value.trim().toUpperCase(); store(); });
        format.addEventListener('input', () => { r[1] = format.value; store(); });
        return h('div', { class: 'fd-pair' }, letter, h('span', {}, '='), format,
          h('button', { type: 'button', title: 'Remove this option', 'aria-label': 'Remove this option', onclick: () => { rows.splice(i, 1); store(); draw(); } }, '×'));
      }),
      h('button', { type: 'button', onclick: () => { rows.push(['', '']); draw(); } }, 'Add option'));
    draw();
    return box;
  }
  function fieldRow(fields, f, i, redraw) {
    const kind = h('select', { 'aria-label': 'field kind' }, SPEC_FIELD_KINDS.map(k => h('option', { value: k }, k || '(text)')));
    kind.value = f.is || '';
    kind.addEventListener('change', () => { setOrDrop(f, 'is', kind.value); changed(); });
    const move = (to) => { if (to < 0 || to >= fields.length) return; fields.splice(i, 1); fields.splice(to, 0, f); changed(); redraw(); };
    return h('tr', {},
      h('td', {}, text(f, 'tag', 'tag', 'mono')),
      h('td', {}, text(f, 'name', 'field name')),
      h('td', {}, text(f, 'format', 'format', 'mono', true), f.options || f.format === undefined ? optionsBox(f) : h('button', { type: 'button', class: 'link', onclick: () => { f.options = {}; changed(); redraw(); } }, 'letter options…')),
      h('td', {}, kind),
      h('td', {}, check(f, 'required', 'required'), check(f, 'repeat', 'repeats')),
      h('td', { class: 'row' },
        h('button', { type: 'button', title: 'Move up', 'aria-label': 'Move field up', onclick: () => move(i - 1) }, '↑'),
        h('button', { type: 'button', title: 'Move down', 'aria-label': 'Move field down', onclick: () => move(i + 1) }, '↓'),
        h('button', { type: 'button', class: 'danger', title: 'Remove this field', 'aria-label': 'Remove field', onclick: () => { fields.splice(i, 1); changed(); redraw(); } }, '×')));
  }
  function sequenceBox(seq, si) {
    const box = h('fieldset', { class: 'fd-section' });
    const draw = () => {
      if (!Array.isArray(seq.fields)) seq.fields = [];
      box.replaceChildren(...[
        h('legend', {}, 'Sequence ' + (seq.id || '?')),
        h('div', { class: 'row' },
          h('label', {}, 'Id ', text(seq, 'id', 'sequence id', 'mono')),
          check(seq, 'required', 'optional sequence', true), check(seq, 'repeat', 'repeats'),
          h('span', { class: 'spacer' }),
          h('button', { type: 'button', class: 'danger', onclick: () => { def.sequences.splice(si, 1); changed(); drawAll(); } }, 'Remove sequence')),
        h('table', { class: 'grid spec-grid' },
          h('thead', {}, h('tr', {}, h('th', {}, 'Tag'), h('th', {}, 'Name'), h('th', {}, 'Format (SWIFT notation; lines with |) or letter options'), h('th', {}, 'Checked as'), h('th', {}, ''), h('th', {}, ''))),
          h('tbody', {}, seq.fields.map((f, i) => fieldRow(seq.fields, f, i, draw)))),
        h('button', { type: 'button', onclick: () => { seq.fields.push({ tag: '', name: '' }); changed(); draw(); } }, 'Add field')].flat());
    };
    draw();
    return box;
  }
  const drawAll = () => body.replaceChildren(...[
    def.sequences.map((s, i) => sequenceBox(s, i)),
    h('button', { type: 'button', onclick: () => { def.sequences.push({ id: String.fromCharCode(65 + def.sequences.length), fields: [] }); changed(); drawAll(); } }, 'Add sequence')].flat());
  drawAll();
  return h('div', { class: 'fd fd-config' },
    h('div', { class: 'fd-form', style: 'max-width: 1100px' },
      h('p', { class: 'sub' }, 'Field rules of an MT message, sequence by sequence: the tag (two digits, with the letter unless the field has letter options), the format in SWIFT notation (16x, 6!n, 3!a15d; several lines separated by |), '
        + 'whether it is required and may repeat, and what the content is checked as. Messages received or built on an MT channel of this type are checked against it.'),
      designerHeader(def, changed, 'specs.swift.MT103'),
      h('div', {}, h('label', {}, 'Message type (for example MT103)'), text(def, 'messageType', 'Message type', 'mono')),
      body));
}
MODEL_DESIGNERS.MessageSpec = messageSpecDesigner;
