// #22 試算表・精算表、#23 財務諸表(B/S・P/L・C/F・株主資本等変動計算書)、#24 総勘定元帳。
// 金額はすべてサーバー(ライブラリ)が計算した値をそのまま表示する。

import { h, get, state, yen, LABELS, accountName } from '../core.js';
import { page, card, note, alertBox, asyncArea, table, tabs, accountSelect, needPeriod, unverified } from '../components.js';

// ---- #22 試算表・精算表 ----

export function trialBalance(params, { navigate }) {
  const missing = needPeriod();
  if (missing) return page('試算表・精算表', missing);
  const tab = params.tab || 'tb';
  const includeClosing = params.includeClosing === 'true';
  return page('試算表・精算表',
    tabs([{ key: 'tb', label: '合計残高試算表' }, { key: 'ws', label: '精算表(8桁)' }], tab,
      (key) => navigate(`/statements/trial-balance?tab=${key}`)),
    tab === 'tb' ? trialBalanceView(includeClosing, navigate) : worksheetView());
}

function trialBalanceView(includeClosing, navigate) {
  const toggle = h('label', { class: 'field-check' },
    h('input', { type: 'checkbox', checked: includeClosing,
      onchange: (e) => navigate(`/statements/trial-balance?tab=tb&includeClosing=${e.target.checked}`) }),
    h('span', {}, '損益振替(締めの仕訳)を含める'));
  return card(null, toggle, asyncArea(async () => {
    const tb = await get(`/periods/${state.periodId}/trial-balance?includeClosing=${includeClosing}`);
    return [
      table([
        { label: 'コード', key: 'accountCode' },
        { label: '科目', render: (r) => h('a', { href: `#/statements/ledger?code=${r.accountCode}` }, r.accountName) },
        { label: '借方合計', render: (r) => yen(r.debitTotal), num: true },
        { label: '貸方合計', render: (r) => yen(r.creditTotal), num: true },
        { label: '残高', render: (r) => yen(r.balance), num: true },
      ], tb.rows, { footer: ['', '合計', yen(tb.debitTotal), yen(tb.creditTotal), ''], empty: '仕訳はまだありません。' }),
      note('残高は科目の通常の側(資産・費用は借方、負債・純資産・収益は貸方)を正として表示します。',
        tb.balanced ? '借方合計と貸方合計は一致しています。' : '借方合計と貸方合計が一致していません。'),
    ];
  }));
}

function worksheetView() {
  return card(null, asyncArea(async () => {
    const ws = await get(`/periods/${state.periodId}/worksheet`);
    const t = ws.totals;
    return [
      table([
        { label: '科目', render: (r) => `${r.accountCode} ${r.accountName}` },
        { label: '試算表 借方', render: (r) => yen0(r.trialBalanceDebit), num: true },
        { label: '試算表 貸方', render: (r) => yen0(r.trialBalanceCredit), num: true },
        { label: '修正 借方', render: (r) => yen0(r.adjustmentDebit), num: true },
        { label: '修正 貸方', render: (r) => yen0(r.adjustmentCredit), num: true },
        { label: 'P/L 借方', render: (r) => yen0(r.incomeStatementDebit), num: true },
        { label: 'P/L 貸方', render: (r) => yen0(r.incomeStatementCredit), num: true },
        { label: 'B/S 借方', render: (r) => yen0(r.balanceSheetDebit), num: true },
        { label: 'B/S 貸方', render: (r) => yen0(r.balanceSheetCredit), num: true },
      ], ws.rows, {
        footer: ['合計', yen(t.trialBalanceDebit), yen(t.trialBalanceCredit), yen(t.adjustmentDebit), yen(t.adjustmentCredit),
          yen(t.incomeStatementDebit), yen(t.incomeStatementCredit), yen(t.balanceSheetDebit), yen(t.balanceSheetCredit)],
        empty: '仕訳はまだありません。',
      }),
      h('dl', { class: 'kv' },
        h('dt', {}, '当期純利益(P/L 欄の差額)'), h('dd', { class: 'num' }, `${yen(ws.netIncome)} 円`),
        h('dt', {}, '自己検証'), h('dd', {}, ws.balanced ? h('span', { class: 'badge badge-ok' }, 'P/L 欄と B/S 欄の差額が一致')
          : h('span', { class: 'badge badge-error' }, '一致していません'))),
      note('修正記入欄は決算整理仕訳(ADJUSTING)、試算表欄はそれ以外(損益振替を除く)です。'),
    ];
  }));
}

function yen0(amount) {
  return amount ? yen(amount) : '';
}

// ---- #23 財務諸表 ----

export function financial(params, { navigate }) {
  const missing = needPeriod();
  if (missing) return page('財務諸表', missing);
  const tab = params.tab || 'bs';
  const views = { bs: balanceSheet, pl: incomeStatement, cf: cashFlow, sce: equityChanges };
  return page('財務諸表',
    tabs([{ key: 'bs', label: '貸借対照表' }, { key: 'pl', label: '損益計算書' }, { key: 'cf', label: 'キャッシュ・フロー計算書' },
      { key: 'sce', label: '株主資本等変動計算書' }], tab, (key) => navigate(`/statements/financial?tab=${key}`)),
    card(null, asyncArea(views[tab])));
}

function amountRows(items) {
  return items.map((i) => h('tr', {}, h('td', {}, i.accountName), h('td', { class: 'num' }, yen(i.amount))));
}

async function balanceSheet() {
  const bs = await get(`/periods/${state.periodId}/balance-sheet`);
  const side = (title, sections) => h('table', { class: 'statement' },
    h('thead', {}, h('tr', {}, h('th', { colspan: 2 }, title))),
    h('tbody', {}, sections.map(([label, items, total]) => [
      h('tr', { class: 'section' }, h('td', { colspan: 2 }, label)),
      amountRows(items),
      h('tr', { class: 'subtotal' }, h('td', {}, `${label}合計`), h('td', { class: 'num' }, yen(total)))])));
  return [
    bs.balanced ? null : alertBox('info',
      '資産と負債・純資産の合計が一致していません。貸借対照表は損益振替(締め)の後を前提とするため、締め前の期では当期の損益が純資産に入っていません。'),
    h('div', { class: 'two-columns' },
      side('資産の部', [['資産', bs.assets, bs.totalAssets]]),
      side('負債・純資産の部', [['負債', bs.liabilities, bs.totalLiabilities], ['純資産', bs.equity, bs.totalEquity]])),
    note('貸倒引当金・減価償却累計額は資産のマイナスとして表示されます。'),
  ];
}

async function incomeStatement() {
  const pl = await get(`/periods/${state.periodId}/income-statement`);
  const rows = [
    ['売上高', pl.sales], ['売上原価', pl.costOfSales], ['売上総利益', pl.grossProfit, true],
    ['販売費及び一般管理費', pl.sgAndA], ['営業利益', pl.operatingIncome, true],
    ['営業外収益', pl.nonOperatingIncome], ['営業外費用', pl.nonOperatingExpense], ['経常利益', pl.ordinaryIncome, true],
    ['特別利益', pl.extraordinaryIncome], ['特別損失', pl.extraordinaryLoss], ['税引前当期純利益', pl.incomeBeforeIncomeTax, true],
    ['法人税等', pl.incomeTaxExpense], ['当期純利益', pl.netIncome, true],
  ];
  return [
    h('table', { class: 'statement narrow' }, h('tbody', {}, rows.map(([label, amount, subtotal]) =>
      h('tr', { class: subtotal ? 'subtotal' : null }, h('td', {}, label), h('td', { class: 'num' }, yen(amount)))))),
    note('損益振替(締めの仕訳)は除いて計算します。マイナスは損失です。'),
  ];
}

async function cashFlow() {
  const cf = await get(`/periods/${state.periodId}/cash-flow`);
  return [
    alertBox('info', cf.note),
    h('table', { class: 'statement narrow' }, h('tbody', {},
      h('tr', { class: 'section' }, h('td', { colspan: 2 }, 'Ⅰ 営業活動によるキャッシュ・フロー')),
      h('tr', {}, h('td', {}, '当期純利益'), h('td', { class: 'num' }, yen(cf.netIncome))),
      h('tr', {}, h('td', {}, '減価償却費'), h('td', { class: 'num' }, yen(cf.depreciationAddBack))),
      cf.operatingWorkingCapitalChanges.map((c) => h('tr', {}, h('td', {}, `${c.accountName}の増減`), h('td', { class: 'num' }, yen(c.amount)))),
      h('tr', { class: 'subtotal' }, h('td', {}, '営業活動によるキャッシュ・フロー'), h('td', { class: 'num' }, yen(cf.operatingActivities))),
      h('tr', { class: 'subtotal' }, h('td', {}, 'Ⅱ 投資活動によるキャッシュ・フロー'), h('td', { class: 'num' }, yen(cf.investingActivities))),
      h('tr', { class: 'subtotal' }, h('td', {}, 'Ⅲ 財務活動によるキャッシュ・フロー'), h('td', { class: 'num' }, yen(cf.financingActivities))),
      h('tr', {}, h('td', {}, 'Ⅳ 現金の増減額'), h('td', { class: 'num' }, yen(cf.netChangeInCash))),
      h('tr', {}, h('td', {}, 'Ⅴ 現金の期首残高'), h('td', { class: 'num' }, yen(cf.cashAtBeginningOfPeriod))),
      h('tr', { class: 'subtotal' }, h('td', {}, 'Ⅵ 現金の期末残高'), h('td', { class: 'num' }, yen(cf.cashAtEndOfPeriod))))),
    h('p', {}, cf.reconciles ? h('span', { class: 'badge badge-ok' }, '期首残高 + 増減額 = 期末残高(自己検証)')
      : h('span', { class: 'badge badge-error' }, '期首残高 + 増減額が期末残高と一致しません')),
  ];
}

async function equityChanges() {
  const sce = await get(`/periods/${state.periodId}/equity-changes`);
  const accounts = [...new Map([...sce.openingBalances, ...sce.closingBalances,
    ...Object.values(sce.changesByReason).flat()].map((a) => [a.accountCode, a.accountName])).entries()]
    .sort(([a], [b]) => a.localeCompare(b));
  const amountOf = (list, code) => {
    const found = list.find((a) => a.accountCode === code);
    return found ? yen(found.amount) : '';
  };
  const reasons = Object.keys(sce.changesByReason);
  return [
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, ''), accounts.map(([, name]) => h('th', { class: 'num' }, name)))),
      h('tbody', {},
        h('tr', { class: 'subtotal' }, h('td', {}, '当期首残高'), accounts.map(([code]) => h('td', { class: 'num' }, amountOf(sce.openingBalances, code)))),
        reasons.map((reason) => h('tr', {}, h('td', {}, LABELS.equityReason[reason] || reason),
          accounts.map(([code]) => h('td', { class: 'num' }, amountOf(sce.changesByReason[reason], code))))),
        h('tr', { class: 'subtotal' }, h('td', {}, '当期末残高'), accounts.map(([code]) => h('td', { class: 'num' }, amountOf(sce.closingBalances, code))))))),
    h('dl', { class: 'kv' },
      h('dt', {}, '純資産合計(期首)'), h('dd', { class: 'num' }, `${yen(sce.totalEquityAtBeginning)} 円`),
      h('dt', {}, '純資産合計(期末)'), h('dd', { class: 'num' }, `${yen(sce.totalEquityAtEnd)} 円`),
      h('dt', {}, '自己検証'), h('dd', {}, sce.reconciles ? h('span', { class: 'badge badge-ok' }, '期首残高 + 変動額 = 期末残高')
        : h('span', { class: 'badge badge-error' }, '一致していません'))),
    note('当期首残高は開始仕訳から求めます。当期純利益は締め(損益振替)の後に表示されます。事由の判定順は未検証のルールです。', unverified('12-4, 12-5')),
  ];
}

// ---- #24 総勘定元帳 ----

export function ledger(params, { navigate }) {
  const missing = needPeriod();
  if (missing) return page('総勘定元帳', missing);
  const select = accountSelect('code', { value: params.code, required: true });
  select.addEventListener('change', () => select.value && navigate(`/statements/ledger?code=${select.value}`));
  // 無効化した科目も過去の元帳は見られるようにする
  if (params.code && !select.value) {
    select.append(h('option', { value: params.code }, `${params.code} ${accountName(params.code)}(無効)`));
    select.value = params.code;
  }
  return page('総勘定元帳',
    card(null, h('label', { class: 'field' }, h('span', { class: 'field-label' }, '科目'), select)),
    params.code ? card(null, asyncArea(async () => {
      const l = await get(`/accounts/${encodeURIComponent(params.code)}/ledger?periodId=${state.periodId}`);
      return [
        table([
          { label: '日付', key: 'date' },
          { label: '種類', render: (e) => LABELS.journalType[e.type] },
          { label: '摘要', render: (e) => h('a', { href: `#/journals/detail?id=${e.journalId}` }, e.description) },
          { label: '借方', render: (e) => e.side === 'DEBIT' ? yen(e.amount) : '', num: true },
          { label: '貸方', render: (e) => e.side === 'CREDIT' ? yen(e.amount) : '', num: true },
        ], l.entries, { footer: ['', '', '合計', yen(l.debitTotal), yen(l.creditTotal)], empty: 'この期の取引はありません。' }),
        h('dl', { class: 'kv' }, h('dt', {}, '残高'), h('dd', { class: 'num' }, `${yen(l.balance)} 円`)),
        note('残高は科目の通常の側を正として表示します(逆側の残高はマイナス)。'),
      ];
    })) : note('科目を選ぶと、選択中の期の元帳を表示します。'));
}
