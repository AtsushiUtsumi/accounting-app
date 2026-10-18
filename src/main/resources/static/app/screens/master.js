// #2 会社の初期化、#3 勘定科目、#4 会計期間。

import { h, get, post, patch, state, LABELS, options, loadAccounts, savePeriodId, clear } from '../core.js';
import { page, card, form, table, note, alertBox, roleRequired, asyncArea } from '../components.js';

// ---- #2 会社の初期化 ----

export function setup(params, { refresh, navigate }) {
  if (state.periods && state.periods.length) {
    return page('会社の初期化', alertBox('info', '会社は初期化済みです(初期化は一度しかできません)。'));
  }
  return page('会社の初期化',
    note('標準科目を投入し、最初の会計期間(期首日から1年間)を作ります。初期化は一度しかできません。'),
    card(null, form({
      role: 'APPROVER',
      submitLabel: '初期化する',
      fields: [{ name: 'firstPeriodStart', label: '最初の期の期首日', type: 'date', required: true, value: `${new Date().getFullYear()}-04-01` }],
      onSubmit: (v) => post('/company', v),
      result: async (v, period) => {
        await refresh();
        setTimeout(() => navigate('/'), 800);
        return alertBox('success', `初期化しました: ${period.startDate} 〜 ${period.endDate}`);
      },
    })));
}

// ---- #3 勘定科目 ----

export function accounts(params, { refresh }) {
  const listArea = h('div');
  let filterText = '';

  async function reload() {
    await loadAccounts(true);
    renderList();
  }

  function renderList() {
    const rows = state.accounts.filter((a) => !filterText || a.code.includes(filterText) || a.name.includes(filterText));
    clear(listArea).append(table([
      { label: 'コード', key: 'code' },
      { label: '科目名', render: (a) => h('span', {}, a.name, a.standard ? h('span', { class: 'badge badge-muted' }, '標準') : null) },
      { label: '要素', render: (a) => LABELS.accountType[a.type] },
      { label: '区分', render: (a) => LABELS.accountCategory[a.category] },
      { label: 'タグ', render: (a) => a.tags.join(', ') },
      { label: '状態', render: (a) => a.active ? '有効' : h('span', { class: 'badge badge-muted' }, '無効') },
      { label: '使用', render: (a) => a.used ? '使用済み' : '' },
      { label: '', render: (a) => h('button', { class: 'btn-secondary btn-small', type: 'button', onclick: () => edit(a) }, '編集') },
    ], rows.map((a) => ({ ...a, _class: a.active ? null : 'inactive' }))));
  }

  const editArea = h('div');
  function edit(account) {
    clear(editArea).append(card(`科目の編集: ${account.code} ${account.name}`,
      account.standard ? note('標準科目は無効化できません(ドメインのルール)。') : null,
      account.used ? note('この科目は使用済みです。科目は削除せず、無効化します(無効化すると新しい仕訳には使えません)。') : null,
      form({
        role: 'BOOKKEEPER', submitLabel: '保存する',
        fields: [
          { name: 'name', label: '科目名', value: account.name, required: true, maxlength: 100 },
          { name: 'tags', label: 'タグ(カンマ区切り)', value: account.tags.join(', ') },
          { name: 'deactivate', label: '無効化する', type: 'checkbox', value: false },
        ],
        onSubmit: (v) => patch(`/accounts/${encodeURIComponent(account.code)}`, {
          name: v.name, tags: splitTags(v.tags), deactivate: v.deactivate || null,
        }),
        result: async () => { await reload(); await refresh(); clear(editArea); return null; },
      })));
    editArea.scrollIntoView({ behavior: 'smooth' });
  }

  renderList();
  return page('勘定科目',
    card('科目の一覧',
      h('div', { class: 'toolbar' }, h('input', {
        type: 'search', placeholder: 'コード・科目名で絞り込み',
        oninput: (e) => { filterText = e.target.value.trim(); renderList(); },
      })),
      listArea),
    editArea,
    card('科目の追加', roleRequired('BOOKKEEPER'), form({
      role: 'BOOKKEEPER', submitLabel: '追加する',
      fields: [
        { name: 'code', label: 'コード', required: true, maxlength: 16 },
        { name: 'name', label: '科目名', required: true, maxlength: 100 },
        { name: 'type', label: '要素(作成後は変更できません)', type: 'select', options: options(LABELS.accountType), required: true },
        { name: 'category', label: '区分', type: 'select', options: options(LABELS.accountCategory), required: true },
        { name: 'tags', label: 'タグ(カンマ区切り)' },
      ],
      onSubmit: (v) => post('/accounts', { ...v, tags: splitTags(v.tags) }),
      result: async (v, created) => { await reload(); await refresh(); return alertBox('success', `追加しました: ${created.code} ${created.name}`); },
    }),
    note('同じ種類の固定資産・無形固定資産・満期保有目的債券が複数あるときは、資産ごとに専用の科目(減価償却累計額など)を作ってください(ライブラリの制限)。')));
}

function splitTags(text) {
  return (text || '').split(',').map((t) => t.trim()).filter(Boolean);
}

// ---- #4 会計期間 ----

export function periods(params, { navigate }) {
  return page('会計期間', asyncArea(async () => {
    const list = await get('/periods');
    return card(null, table([
      { label: '期間', render: (p) => `${p.startDate} 〜 ${p.endDate}` },
      { label: '状態', render: (p) => h('span', { class: p.status === 'OPEN' ? 'badge badge-ok' : 'badge badge-muted' }, LABELS.periodStatus[p.status]) },
      { label: 'version', key: 'version', num: true },
      { label: '', render: (p) => p.id === state.periodId
        ? h('span', { class: 'muted' }, '選択中')
        : h('button', { type: 'button', class: 'btn-secondary btn-small', onclick: () => select(p.id) }, 'この期を選ぶ') },
      { label: '照会', render: (p) => h('span', { class: 'link-list' },
        h('a', { href: '#/statements/trial-balance', onclick: () => select(p.id, false) }, '試算表'),
        h('a', { href: '#/statements/financial', onclick: () => select(p.id, false) }, '財務諸表'),
        h('a', { href: '#/journals', onclick: () => select(p.id, false) }, '仕訳帳')) },
    ], list), note('期は締めると「締め済み」になり、次の期が作られます。会計期間は1年固定です。'));
  }));

  function select(id, go = true) {
    state.periodId = id;
    savePeriodId(id);
    const picker = document.querySelector('#period-picker select');
    if (picker) picker.value = id;
    if (go) navigate('/');
  }
}
