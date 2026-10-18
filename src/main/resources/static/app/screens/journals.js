// #5 仕訳入力、#6 仕訳帳・仕訳の詳細、#7 赤黒訂正。

import { h, get, post, state, LABELS, today } from '../core.js';
import {
  page, card, form, note, alertBox, roleRequired, asyncArea, journalView, postedJournals, tabs, needPeriod,
} from '../components.js';

// ---- #5 仕訳入力 ----

export function newJournal(params) {
  return page('仕訳入力',
    roleRequired('BOOKKEEPER'),
    note('貸借の一致・締めた期への記帳・期間外の日付などは、サーバー(ライブラリ)が検査します。',
      '「決算整理」は、ライブラリが自動で作らない決算整理(経過勘定・貯蔵品など)を手入力するときに選びます。'),
    card(null, form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'type', label: '種類', type: 'select', value: params.type || 'NORMAL',
          options: [{ value: 'NORMAL', label: '通常' }, { value: 'ADJUSTING', label: '決算整理(経過勘定・貯蔵品など)' }] },
        { name: 'description', label: '摘要', required: true, maxlength: 200 },
        { name: 'lines', label: '仕訳の行', type: 'lines' },
      ],
      onSubmit: (v) => post('/journals', v),
      result: (v, res) => postedJournals([res.journalId]),
    })));
}

// ---- #6 仕訳帳 ----

const TYPE_TABS = [
  { key: 'ALL', label: 'すべて' },
  ...Object.entries(LABELS.journalType).map(([key, label]) => ({ key, label })),
];

export function journalBook(params, { navigate }) {
  const missing = needPeriod();
  if (missing) return page('仕訳帳', missing);
  const active = params.type || 'ALL';
  return page('仕訳帳',
    tabs(TYPE_TABS, active, (key) => navigate(key === 'ALL' ? '/journals' : `/journals?type=${key}`)),
    asyncArea(async () => {
      const journals = (await get(`/periods/${state.periodId}/journals`))
        .filter((j) => active === 'ALL' || j.type === active);
      if (!journals.length) return h('p', { class: 'muted' }, '仕訳はありません。');
      return [h('p', { class: 'muted' }, `${journals.length} 件`), ...journals.map((j) => journalView(j, { actions: actionsFor(j) }))];
    }));
}

function actionsFor(journal) {
  return h('span', { class: 'journal-actions' },
    h('a', { href: `#/journals/detail?id=${journal.id}`, class: 'small' }, '詳細'),
    journal.reversed ? null : h('a', { href: `#/journals/correct?id=${journal.id}`, class: 'small' }, '訂正(赤黒)'));
}

export function detail(params) {
  return page('仕訳の詳細', asyncArea(async () => {
    const journal = await get(`/journals/${params.id}`);
    return [
      journalView(journal, { actions: actionsFor(journal) }),
      h('dl', { class: 'kv' },
        h('dt', {}, '仕訳 ID'), h('dd', { class: 'mono' }, journal.id),
        h('dt', {}, '会計期間 ID'), h('dd', { class: 'mono' }, journal.periodId),
        journal.reversalOf ? [h('dt', {}, '取り消した仕訳'), h('dd', { class: 'mono' }, journal.reversalOf)] : null),
    ];
  }));
}

// ---- #7 赤黒訂正 ----

export function correct(params) {
  return page('赤黒訂正', roleRequired('BOOKKEEPER'), asyncArea(async () => {
    const original = await get(`/journals/${params.id}`);
    if (original.reversed) return [journalView(original), alertBox('info', 'この仕訳はすでに取り消されています。')];

    const withCorrect = h('input', { type: 'checkbox', checked: true });
    const correctForm = form({
      role: 'BOOKKEEPER',
      submitLabel: '訂正を記帳する',
      fields: [
        { name: 'date', label: '訂正日', type: 'date', required: true, value: today(),
          help: '取消(赤)と正しい仕訳(黒)は、訂正日を含む記帳中の期に記帳します。元の仕訳が締めた期のものでも同じです。' },
        { type: 'heading', label: '正しい仕訳(黒)' },
        { name: 'description', label: '摘要', value: original.description, maxlength: 200 },
        { name: 'type', label: '種類', type: 'select', value: original.type === 'ADJUSTING' ? 'ADJUSTING' : 'NORMAL',
          options: [{ value: 'NORMAL', label: '通常' }, { value: 'ADJUSTING', label: '決算整理' }] },
        { name: 'lines', label: '仕訳の行', type: 'lines',
          value: original.lines.map((l) => ({ accountCode: l.accountCode, side: l.side, amount: l.amount })) },
      ],
      extra: h('label', { class: 'field field-check' }, withCorrect, h('span', {}, '正しい仕訳(黒)も記帳する(外すと取消のみ)')),
      onSubmit: (v) => post(`/journals/${original.id}/corrections`, {
        date: v.date,
        correctJournal: withCorrect.checked ? { description: v.description, type: v.type, lines: v.lines } : null,
      }),
      result: (v, res) => postedJournals([res.reversalId, res.correctJournalId].filter(Boolean), '訂正の仕訳'),
    });
    return [card('元の仕訳', journalView(original)), card('訂正の内容', correctForm)];
  }));
}

