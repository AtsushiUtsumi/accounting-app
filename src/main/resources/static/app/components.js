// 画面の部品: ページの枠、フォーム、科目の選択、仕訳の行の入力、仕訳の表示など。

import { h, clear, yen, today, state, get, hasRole, LABELS, options, loadAccounts } from './core.js';

// ---- ページの枠 ----

export function page(title, ...children) {
  return h('section', { class: 'page' }, h('h1', {}, title), ...children);
}

export function card(title, ...children) {
  return h('div', { class: 'card' }, title ? h('h2', {}, title) : null, ...children);
}

export function note(...children) {
  return h('p', { class: 'note' }, ...children);
}

export function alertBox(kind, message) {
  return h('div', { class: `alert alert-${kind}`, role: kind === 'error' ? 'alert' : 'status' }, message);
}

/** UNVERIFIED_RULES.md に載っているルールに依存する機能の目印(CLAUDE.md §9)。 */
export function unverified(ruleIds) {
  return h('span', { class: 'badge badge-warn', title: `UNVERIFIED_RULES.md ${ruleIds}: 問題集の解答例でまだ検証していないルールです` },
    `検証中 ${ruleIds}`);
}

/** ロールが足りないときに表示する。足りていれば null。 */
export function roleRequired(role) {
  if (hasRole(role)) return null;
  return alertBox('info', `この操作には「${LABELS.role[role]}」以上のロールが必要です。内容の確認だけできます。`);
}

export function tabs(items, active, onSelect) {
  return h('div', { class: 'tabs', role: 'tablist' }, items.map(({ key, label }) =>
    h('button', {
      class: key === active ? 'tab active' : 'tab', role: 'tab', type: 'button',
      'aria-selected': key === active ? 'true' : 'false', onclick: () => onSelect(key),
    }, label)));
}

export function loading() {
  return h('p', { class: 'muted' }, '読み込み中…');
}

/** 非同期に中身を作る領域。失敗したらエラーを表示する。 */
export function asyncArea(render) {
  const area = h('div', {}, loading());
  Promise.resolve().then(render).then(
    (node) => clear(area).append(...[node].flat().filter(Boolean)),
    (error) => clear(area).append(alertBox('error', error.message)));
  return area;
}

export function needPeriod() {
  if (state.periodId) return null;
  return alertBox('info', '会計期間がありません。先に「会社の初期化」を行ってください。');
}

// ---- 表 ----

export function table(columns, rows, { footer, empty = 'データはありません' } = {}) {
  if (!rows.length) return h('p', { class: 'muted' }, empty);
  return h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, columns.map((c) => h('th', { class: c.num ? 'num' : null }, c.label)))),
    h('tbody', {}, rows.map((row) => h('tr', { class: row._class || null },
      columns.map((c) => h('td', { class: c.num ? 'num' : null }, c.render ? c.render(row) : row[c.key]))))),
    footer ? h('tfoot', {}, h('tr', {}, footer.map((cell, i) =>
      h('td', { class: columns[i] && columns[i].num ? 'num' : null }, cell)))) : null));
}

// ---- 科目の選択 ----

/** 科目のセレクトボックス。区分ごとにグループにする。無効化された科目は選べない。 */
export function accountSelect(name, { value, required = true, filter } = {}) {
  const select = h('select', { name, required, class: 'account-select' });
  select.append(h('option', { value: '' }, '― 科目を選択 ―'));
  const accounts = (state.accounts || []).filter((a) => a.active && (!filter || filter(a)));
  const groups = new Map();
  for (const account of accounts) {
    if (!groups.has(account.category)) groups.set(account.category, []);
    groups.get(account.category).push(account);
  }
  for (const [category, list] of groups) {
    select.append(h('optgroup', { label: LABELS.accountCategory[category] || category },
      list.map((a) => h('option', { value: a.code }, `${a.code} ${a.name}`))));
  }
  if (value) select.value = value;
  return select;
}

// ---- 仕訳の行の入力 ----

/**
 * 仕訳の行(科目・借方/貸方・金額)の入力欄。{@code side} を指定すると、その側だけの行になる(吸収合併など)。
 * 合計の表示は入力の補助で、貸借の一致はサーバー(ライブラリ)が検査する。
 */
export function linesEditor({ name = 'lines', initial = [], side, minRows = 2 } = {}) {
  const body = h('tbody');
  const totals = h('div', { class: 'lines-totals muted' });
  const wrap = h('div', { class: 'lines-editor', 'data-lines': name },
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, '科目'), side ? null : h('th', {}, '借方/貸方'),
        h('th', { class: 'num' }, '金額(円)'), h('th', {}))),
      body)),
    h('div', { class: 'row-actions' },
      h('button', { type: 'button', class: 'btn-secondary', onclick: () => addRow() }, '+ 行を追加'),
      totals));

  function updateTotals() {
    if (side) {
      const sum = collect().reduce((s, l) => s + l.amount, 0);
      totals.textContent = `合計 ${yen(sum)} 円`;
      return;
    }
    let debit = 0, credit = 0;
    for (const line of collect()) line.side === 'DEBIT' ? (debit += line.amount) : (credit += line.amount);
    totals.textContent = `借方合計 ${yen(debit)} 円 / 貸方合計 ${yen(credit)} 円`;
  }

  function addRow(line = {}) {
    const amount = h('input', { type: 'number', min: '1', step: '1', class: 'num', value: line.amount ?? '', oninput: updateTotals });
    const sideSelect = side ? null : h('select', { onchange: updateTotals },
      h('option', { value: 'DEBIT' }, '借方'), h('option', { value: 'CREDIT' }, '貸方'));
    if (sideSelect) sideSelect.value = line.side || (body.children.length % 2 === 0 ? 'DEBIT' : 'CREDIT');
    const tr = h('tr', {},
      h('td', {}, accountSelect('', { value: line.accountCode, required: false })),
      sideSelect ? h('td', {}, sideSelect) : null,
      h('td', {}, amount),
      h('td', {}, h('button', { type: 'button', class: 'btn-icon', title: '行を削除', onclick: () => { tr.remove(); updateTotals(); } }, '×')));
    body.append(tr);
    updateTotals();
  }

  function collect() {
    return [...body.children].map((tr) => {
      const [accountCell, second, third] = tr.children;
      const code = accountCell.querySelector('select').value;
      const amountInput = (side ? second : third).querySelector('input');
      return {
        accountCode: code,
        side: side || second.querySelector('select').value,
        amount: Number(amountInput.value || 0),
      };
    }).filter((line) => line.accountCode && line.amount > 0);
  }

  initial.forEach(addRow);
  for (let i = initial.length; i < minRows; i++) addRow();
  wrap.collect = collect;
  return wrap;
}

// ---- フォーム ----

/**
 * 項目の定義からフォームを作る。type は date / text / yen / int / decimal / account / select / checkbox /
 * textarea / lines。送信すると onSubmit(values) を呼び、返り値を result(values, response) で表示する。
 */
export function form({ fields, submitLabel = '記帳する', onSubmit, result, role, extra }) {
  const message = h('div', { class: 'form-message' });
  const controls = new Map();
  const formEl = h('form', { class: 'form', onsubmit: submit });

  for (const field of fields) {
    if (field.type === 'heading') {
      formEl.append(h('h3', {}, field.label, field.badge || null));
      continue;
    }
    const control = makeControl(field);
    controls.set(field.name, { field, control });
    const label = h('label', { class: field.type === 'checkbox' ? 'field field-check' : 'field' },
      field.type === 'checkbox' ? [control, h('span', {}, field.label)] : [h('span', { class: 'field-label' }, field.label,
        field.required ? h('span', { class: 'req', title: '必須' }, '*') : null, field.badge || null), control],
      field.help ? h('small', { class: 'help' }, field.help) : null);
    if (field.type === 'lines') label.className = 'field field-wide';
    formEl.append(label);
  }
  if (extra) formEl.append(extra);
  const canSubmit = !role || hasRole(role);
  const button = h('button', { type: 'submit', class: 'btn-primary', disabled: !canSubmit }, submitLabel);
  formEl.append(h('div', { class: 'form-actions' }, button,
    canSubmit ? null : h('span', { class: 'muted' }, `「${LABELS.role[role]}」以上のロールが必要です`)), message);

  async function submit(event) {
    event.preventDefault();
    const values = {};
    for (const [name, { field, control }] of controls) values[name] = readValue(field, control);
    button.disabled = true;
    clear(message);
    try {
      const response = await onSubmit(values);
      if (result) {
        const node = await result(values, response);
        if (node) message.append(node);
      } else {
        message.append(alertBox('success', '完了しました。'));
      }
    } catch (error) {
      message.append(alertBox('error', error.message));
    } finally {
      button.disabled = !canSubmit;
    }
  }

  formEl.values = () => Object.fromEntries([...controls].map(([n, { field, control }]) => [n, readValue(field, control)]));
  formEl.control = (name) => controls.get(name)?.control;
  formEl.showMessage = (node) => clear(message).append(node);
  return formEl;
}

function makeControl(field) {
  const common = { name: field.name, required: field.required || false };
  switch (field.type) {
    case 'date':
      return h('input', { ...common, type: 'date', value: field.value ?? today() });
    case 'yen':
      return h('input', { ...common, type: 'number', min: field.allowNegative ? null : '0', step: '1', class: 'num', value: field.value ?? '' });
    case 'int':
      return h('input', { ...common, type: 'number', min: field.min ?? '0', step: '1', class: 'num', value: field.value ?? '' });
    case 'decimal':
      return h('input', { ...common, type: 'number', min: '0', step: 'any', class: 'num', value: field.value ?? '' });
    case 'account':
      return accountSelect(field.name, { value: field.value, required: field.required, filter: field.filter });
    case 'select': {
      const select = h('select', common, field.optional ? h('option', { value: '' }, '―') : null,
        field.options.map((o) => h('option', { value: o.value }, o.label)));
      if (field.value != null) select.value = field.value;
      return select;
    }
    case 'checkbox':
      return h('input', { name: field.name, type: 'checkbox', checked: field.value || false });
    case 'textarea':
      return h('textarea', { ...common, rows: field.rows || 3 }, field.value ?? '');
    case 'lines':
      return linesEditor({ name: field.name, initial: field.value || [], side: field.side, minRows: field.minRows ?? 2 });
    default:
      return h('input', { ...common, type: 'text', value: field.value ?? '', maxlength: field.maxlength || null });
  }
}

function readValue(field, control) {
  switch (field.type) {
    case 'yen':
    case 'int':
    case 'decimal':
      return control.value === '' ? null : Number(control.value);
    case 'checkbox':
      return control.checked;
    case 'lines':
      return control.collect();
    case 'date':
    case 'account':
    case 'select':
      return control.value || null;
    default:
      return control.value;
  }
}

// ---- 仕訳の表示 ----

/** 仕訳を借方・貸方の2列で表示する(仕訳帳の形)。 */
export function journalView(journal, { actions } = {}) {
  const debits = journal.lines.filter((l) => l.side === 'DEBIT');
  const credits = journal.lines.filter((l) => l.side === 'CREDIT');
  const rows = Math.max(debits.length, credits.length);
  const lineCells = (line) => line
    ? [h('td', {}, `${line.accountCode} ${line.accountName}`), h('td', { class: 'num' }, yen(line.amount))]
    : [h('td', {}), h('td', {})];
  return h('div', { class: journal.reversed ? 'journal reversed' : 'journal' },
    h('div', { class: 'journal-head' },
      h('span', { class: 'journal-date' }, journal.date),
      h('span', { class: `badge type-${journal.type.toLowerCase()}` }, LABELS.journalType[journal.type] || journal.type),
      h('span', { class: 'journal-desc' }, journal.description),
      journal.reversed ? h('span', { class: 'badge badge-muted' }, '取消済み') : null,
      journal.reversalOf ? h('a', { href: `#/journals/detail?id=${journal.reversalOf}`, class: 'small' }, '元の仕訳') : null,
      actions || null),
    h('table', { class: 'journal-lines' },
      h('thead', {}, h('tr', {}, h('th', {}, '借方科目'), h('th', { class: 'num' }, '金額'),
        h('th', {}, '貸方科目'), h('th', { class: 'num' }, '金額'))),
      h('tbody', {}, Array.from({ length: rows }, (_, i) => h('tr', {}, lineCells(debits[i]), lineCells(credits[i]))))));
}

/** 記帳した仕訳を ID から読み込んで表示する(操作の結果の確認用)。 */
export async function postedJournals(ids, title = '記帳した仕訳') {
  if (!ids || !ids.length) return alertBox('info', '記帳が必要な仕訳はありませんでした(金額の差がない、など)。');
  const journals = await Promise.all(ids.map((id) => get(`/journals/${id}`)));
  return h('div', {}, alertBox('success', `${title}: ${journals.length} 件`), journals.map((j) => journalView(j)));
}

/** 記帳前の仕訳(プレビュー)を表示する。 */
export function previewJournals(journals, title = '作られる仕訳(まだ記帳していません)') {
  if (!journals.length) return alertBox('info', '作られる仕訳はありません。');
  return h('div', {}, alertBox('info', `${title}: ${journals.length} 件`), journals.map((j) => journalView(j)));
}

export async function ensureAccounts() {
  await loadAccounts();
}

export { options };
