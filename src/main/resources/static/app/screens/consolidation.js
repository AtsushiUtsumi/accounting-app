// 連結: 子会社の登録、連結の資料(債権債務・取引高・未実現利益)、連結財務諸表。
// 操作している会社を親会社とする。連結修正仕訳・連結財務諸表はサーバー(ライブラリ)が毎回作り直す(保存しない)。

import { h, get, post, del, state, yen, clear, currentCompany, companyName, currentPeriod } from '../core.js';
import {
  page, card, note, alertBox, asyncArea, table, tabs, form, roleRequired, needPeriod, unverified, journalView, linesEditor,
} from '../components.js';

const UNVERIFIED_IDS = '14-x';

// ---- 子会社 ----

export function subsidiaries(params, { refresh }) {
  const parent = currentCompany();
  const listArea = h('div');

  async function reload() {
    const list = await get('/subsidiaries');
    clear(listArea).append(table([
      { label: '会社', render: (s) => s.company.name },
      { label: '支配獲得日', key: 'acquisitionDate' },
      { label: '親会社の持分比率', render: (s) => `${(Number(s.parentOwnership) * 100).toLocaleString('ja-JP', { maximumFractionDigits: 4 })}%`, num: true },
      { label: '子会社株式の取得原価', render: (s) => yen(s.investmentCost), num: true },
      { label: '支配獲得日の資本', render: (s) => s.equityAtAcquisition.map((e) => `${e.accountName} ${yen(e.amount)}`).join('、') },
      { label: 'のれんの償却年数', render: (s) => `${s.goodwillAmortizationYears} 年`, num: true },
      { label: '', render: (s) => h('button', {
        type: 'button', class: 'btn-secondary btn-small', disabled: !state.me.roles.includes('APPROVER'),
        onclick: async () => {
          if (!confirm(`「${s.company.name}」の子会社の登録を取り消しますか?(子会社の帳簿は残ります)`)) return;
          await del(`/subsidiaries/${s.company.id}`);
          await reload();
        },
      }, '登録を取り消す') },
    ], list, { empty: 'まだ子会社はありません。' }));
  }

  const equity = linesEditor({ name: 'equityAtAcquisition', side: 'CREDIT', minRows: 2,
    initial: [{ accountCode: '301' }, { accountCode: '302' }].filter((l) => state.accounts.some((a) => a.code === l.accountCode)) });
  const candidates = (state.companies || []).filter((c) => c.id !== state.companyId);
  const period = currentPeriod();

  return page('子会社',
    h('p', {}, unverified(UNVERIFIED_IDS)),
    note(`操作している会社「${parent ? parent.name : ''}」を親会社とします。子会社も、このアプリで帳簿を持つ会社です(「会社」の画面で追加します)。`),
    card('子会社の一覧', asyncArea(async () => { await reload(); return listArea; })),
    card('子会社の登録', roleRequired('APPROVER'),
      candidates.length ? null : alertBox('info', '子会社にできる会社がありません。先に「会社」の画面で会社を追加してください。'),
      form({
        role: 'APPROVER', submitLabel: '登録する',
        fields: [
          { name: 'companyId', label: '子会社にする会社', type: 'select', required: true,
            options: candidates.map((c) => ({ value: c.id, label: c.name })) },
          { name: 'acquisitionDate', label: '支配獲得日(子会社の決算日)', type: 'date', required: true, value: period ? period.endDate : undefined },
          { name: 'parentOwnership', label: '親会社の持分比率(例: 0.8)', type: 'decimal', required: true },
          { name: 'investmentCost', label: '子会社株式の取得原価(円)', type: 'yen', required: true },
          { name: 'goodwillAmortizationYears', label: 'のれんの償却年数', type: 'int', required: true, min: '1', value: 20 },
        ],
        extra: h('div', { class: 'field field-wide' }, h('span', { class: 'field-label' }, '支配獲得日の子会社の資本(資本金・資本剰余金・利益剰余金)'), equity),
        onSubmit: (v) => post('/subsidiaries', {
          ...v,
          equityAtAcquisition: equity.collect().map((l) => ({ accountCode: l.accountCode, amount: l.amount })),
        }),
        result: async (v, created) => { await reload(); await refresh(); return alertBox('success', `登録しました: ${created.company.name}`); },
      }),
      note('支配獲得日は子会社の決算日(期末)とします。期中の支配獲得、追加取得・一部売却、子会社の資産・負債の評価差額、孫会社は扱いません(ライブラリの範囲外)。')));
}

// ---- 連結財務諸表 ----

export function statements(params, { navigate }) {
  const missing = needPeriod();
  if (missing) return page('連結財務諸表', missing);
  const tab = params.tab || 'materials';
  const views = { materials: materialsView, ws: worksheetView, fs: statementsView, journals: adjustmentsView };
  return page('連結財務諸表',
    h('p', {}, unverified(UNVERIFIED_IDS)),
    note('操作している会社を親会社とし、選んでいる期の期末日で連結します。子会社は、期末日が同じ期の帳簿(損益振替の前)を使います。'),
    tabs([{ key: 'materials', label: '連結の資料' }, { key: 'ws', label: '連結精算表' }, { key: 'fs', label: '連結財務諸表' },
      { key: 'journals', label: '連結修正仕訳' }], tab, (key) => navigate(`/consolidation/statements?tab=${key}`)),
    views[tab](navigate));
}

/** 連結会社(親会社と、この期に連結する子会社)。資料の会社の選択肢に使う。 */
async function groupCompanies() {
  const subs = await get('/subsidiaries');
  const parent = currentCompany();
  return [{ id: state.companyId, name: `${parent ? parent.name : ''}(親会社)` },
    ...subs.map((s) => ({ id: s.company.id, name: s.company.name }))];
}

function materialsView(navigate) {
  const base = `/periods/${state.periodId}/consolidation`;
  const reload = () => navigate(`/consolidation/statements?tab=materials&t=${Date.now()}`);
  const remove = (id) => async () => {
    if (!confirm('この資料を削除しますか?')) return;
    await del(`${base}/materials/${id}`);
    reload();
  };
  const removeButton = (row) => h('button', { type: 'button', class: 'btn-secondary btn-small',
    disabled: !state.me.roles.includes('APPROVER'), onclick: remove(row.id) }, '削除');

  return asyncArea(async () => {
    const [materials, group] = await Promise.all([get(`${base}/materials`), groupCompanies()]);
    const companyOptions = group.map((c) => ({ value: c.id, label: c.name }));
    const nameOf = (id) => (group.find((c) => c.id === id) || { name: companyName(id) }).name;
    return [
      roleRequired('APPROVER'),
      card('連結会社間の債権債務', table([
        { label: '債権者', render: (r) => nameOf(r.creditorCompanyId) },
        { label: '債権', render: (r) => `${r.receivableCode} ${r.receivableName}` },
        { label: '債務', render: (r) => `${r.payableCode} ${r.payableName}` },
        { label: '金額', render: (r) => yen(r.amount), num: true },
        { label: '貸倒引当金(期末)', render: (r) => yen(r.allowance), num: true },
        { label: '貸倒引当金(前期末)', render: (r) => yen(r.allowanceAtBeginning), num: true },
        { label: '', render: removeButton },
      ], materials.intercompanyBalances, { empty: '登録された債権債務はありません。' }),
      form({
        role: 'APPROVER', submitLabel: '追加する',
        fields: [
          { name: 'creditorCompanyId', label: '債権を持つ会社', type: 'select', options: companyOptions, required: true },
          { name: 'receivableCode', label: '債権の科目', type: 'account', required: true },
          { name: 'payableCode', label: '債務の科目', type: 'account', required: true },
          { name: 'amount', label: '金額(円)', type: 'yen', required: true },
          { name: 'allowance', label: 'この債権に対する期末の貸倒引当金(円)', type: 'yen', value: 0 },
          { name: 'allowanceAtBeginning', label: '前期末の連結会社間の債権に対する貸倒引当金(円)', type: 'yen', value: 0 },
        ],
        onSubmit: (v) => post(`${base}/intercompany-balances`, { ...v, allowance: v.allowance || 0, allowanceAtBeginning: v.allowanceAtBeginning || 0 }),
        result: async () => { reload(); return null; },
      })),
      card('連結会社間の取引高', table([
        { label: '収益(売った側)', render: (r) => `${r.revenueCode} ${r.revenueName}` },
        { label: '費用(買った側)', render: (r) => `${r.expenseCode} ${r.expenseName}` },
        { label: '金額', render: (r) => yen(r.amount), num: true },
        { label: '', render: removeButton },
      ], materials.intercompanyTransactions, { empty: '登録された取引高はありません。' }),
      form({
        role: 'APPROVER', submitLabel: '追加する',
        fields: [
          { name: 'revenueCode', label: '収益の科目(例: 売上)', type: 'account', required: true },
          { name: 'expenseCode', label: '費用の科目(例: 仕入)', type: 'account', required: true },
          { name: 'amount', label: '金額(円)', type: 'yen', required: true },
        ],
        onSubmit: (v) => post(`${base}/intercompany-transactions`, v),
        result: async () => { reload(); return null; },
      })),
      card('未実現利益', table([
        { label: '種類', render: (r) => r.kind === 'INVENTORY' ? '棚卸資産' : '土地' },
        { label: '売った側', render: (r) => nameOf(r.sellerCompanyId) },
        { label: '内容', render: (r) => r.kind === 'INVENTORY'
          ? `${r.inventoryAccountCode} ${r.inventoryAccountName}: 期末 ${yen(r.ending)} 円 / 期首 ${yen(r.beginning)} 円`
          : `売却益 ${yen(r.amount)} 円(${r.saleDate} 売却)` },
        { label: '', render: removeButton },
      ], materials.unrealizedProfits, { empty: '登録された未実現利益はありません。' }),
      form({
        role: 'APPROVER', submitLabel: '追加する',
        fields: [
          { name: 'kind', label: '種類', type: 'select', required: true, options: [{ value: 'INVENTORY', label: '棚卸資産' }, { value: 'LAND', label: '土地' }] },
          { name: 'sellerCompanyId', label: '売った側の会社', type: 'select', options: companyOptions, required: true },
          { type: 'heading', label: '棚卸資産のとき' },
          { name: 'inventoryAccountCode', label: '買った側の棚卸資産の科目', type: 'account' },
          { name: 'ending', label: '期末の棚卸資産に含まれる未実現利益(円)', type: 'yen' },
          { name: 'beginning', label: '期首の棚卸資産に含まれる未実現利益(円)', type: 'yen' },
          { type: 'heading', label: '土地のとき' },
          { name: 'amount', label: '売却益(円)', type: 'yen' },
          { name: 'saleDate', label: '売却日', type: 'date', value: '' },
        ],
        onSubmit: (v) => post(`${base}/unrealized-profits`, v.kind === 'INVENTORY'
          ? { kind: v.kind, sellerCompanyId: v.sellerCompanyId, inventoryAccountCode: v.inventoryAccountCode, ending: v.ending, beginning: v.beginning }
          : { kind: v.kind, sellerCompanyId: v.sellerCompanyId, amount: v.amount, saleDate: v.saleDate }),
        result: async () => { reload(); return null; },
      }),
      note('売った側(債権者)が子会社ならアップストリームとして、消去額を非支配株主に按分します。')),
    ];
  });
}

async function consolidated() {
  return get(`/periods/${state.periodId}/consolidation`);
}

function worksheetView() {
  return card(null, asyncArea(async () => {
    const result = await consolidated();
    const companies = result.companies;
    const ws = result.worksheet;
    return [
      table([
        { label: '科目', render: (r) => `${r.accountCode} ${r.accountName}` },
        ...companies.map((c) => ({ label: c.name, render: (r) => yen0(r.individual[c.id]), num: true })),
        { label: '合計', render: (r) => yen0(r.individualTotal), num: true },
        { label: '修正・消去 借方', render: (r) => yen0(r.adjustmentDebit), num: true },
        { label: '修正・消去 貸方', render: (r) => yen0(r.adjustmentCredit), num: true },
        { label: '連結財務諸表', render: (r) => yen0(r.consolidated), num: true },
      ], ws.rows, {
        footer: ['合計', ...companies.map(() => ''), '', yen(ws.adjustmentDebitTotal), yen(ws.adjustmentCreditTotal), ''],
      }),
      h('p', {}, ws.adjustmentBalanced ? h('span', { class: 'badge badge-ok' }, '修正・消去欄の借方合計と貸方合計が一致')
        : h('span', { class: 'badge badge-error' }, '修正・消去欄の借方合計と貸方合計が一致しません')),
      note('金額は各科目の通常の側を正とした残高です。個別財務諸表は損益振替の前の残高なので、利益剰余金の行には当期純利益が含まれません。',
        '支配獲得日が当期末の子会社は、貸借対照表だけを連結します。'),
    ];
  }));
}

function statementsView() {
  return card(null, asyncArea(async () => {
    const result = await consolidated();
    const pl = result.incomeStatement.incomeStatement;
    const plRows = [
      ['売上高', pl.sales], ['売上原価', pl.costOfSales], ['売上総利益', pl.grossProfit, true],
      ['販売費及び一般管理費', pl.sgAndA], ['営業利益', pl.operatingIncome, true],
      ['営業外収益', pl.nonOperatingIncome], ['営業外費用', pl.nonOperatingExpense], ['経常利益', pl.ordinaryIncome, true],
      ['特別利益', pl.extraordinaryIncome], ['特別損失', pl.extraordinaryLoss], ['税金等調整前当期純利益', pl.incomeBeforeIncomeTax, true],
      ['法人税等', pl.incomeTaxExpense], ['当期純利益', pl.netIncome, true],
      ['非支配株主に帰属する当期純利益', result.incomeStatement.profitAttributableToNonControllingInterests],
      ['親会社株主に帰属する当期純利益', result.incomeStatement.profitAttributableToOwnersOfParent, true],
    ];
    const bs = result.balanceSheet;
    const amountRows = (items) => items.map((i) => h('tr', {}, h('td', {}, i.accountName), h('td', { class: 'num' }, yen(i.amount))));
    const subtotal = (label, amount) => h('tr', { class: 'subtotal' }, h('td', {}, label), h('td', { class: 'num' }, yen(amount)));
    return [
      h('h3', {}, '連結損益計算書'),
      h('table', { class: 'statement narrow' }, h('tbody', {}, plRows.map(([label, amount, isSubtotal]) =>
        h('tr', { class: isSubtotal ? 'subtotal' : null }, h('td', {}, label), h('td', { class: 'num' }, yen(amount)))))),
      h('h3', {}, '連結貸借対照表'),
      bs.balanced ? null : alertBox('error', '資産と負債・純資産の合計が一致していません。'),
      h('div', { class: 'two-columns' },
        h('table', { class: 'statement' },
          h('thead', {}, h('tr', {}, h('th', { colspan: 2 }, '資産の部'))),
          h('tbody', {}, amountRows(bs.assets), subtotal('資産合計', bs.totalAssets))),
        h('table', { class: 'statement' },
          h('thead', {}, h('tr', {}, h('th', { colspan: 2 }, '負債・純資産の部'))),
          h('tbody', {},
            h('tr', { class: 'section' }, h('td', { colspan: 2 }, '負債')),
            amountRows(bs.liabilities), subtotal('負債合計', bs.totalLiabilities),
            h('tr', { class: 'section' }, h('td', { colspan: 2 }, '純資産')),
            amountRows(bs.shareholdersEquity),
            h('tr', {}, h('td', {}, '親会社株主に帰属する当期純利益'), h('td', { class: 'num' }, yen(bs.profitAttributableToOwnersOfParent))),
            h('tr', {}, h('td', {}, '非支配株主持分'), h('td', { class: 'num' }, yen(bs.nonControllingInterests))),
            subtotal('純資産合計', bs.totalEquity),
            subtotal('負債・純資産合計', bs.totalLiabilities + bs.totalEquity)))),
      note(`利益剰余金の合計(親会社株主に帰属する当期純利益を含む): ${yen(bs.retainedEarnings)} 円。連結修正に伴う税効果は考慮しません。`),
    ];
  }));
}

function adjustmentsView() {
  return card(null, asyncArea(async () => {
    const result = await consolidated();
    if (!result.adjustments.length) return alertBox('info', '連結修正仕訳はありません。');
    return [
      note('連結修正仕訳は個別の帳簿には記帳しません。前期以前の修正は、毎期「開始仕訳」として作り直します。'),
      result.adjustments.map((j) => journalView(j)),
    ];
  }));
}

function yen0(amount) {
  return amount ? yen(amount) : '';
}

