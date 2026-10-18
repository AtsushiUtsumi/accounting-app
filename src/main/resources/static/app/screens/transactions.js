// #8〜#16 個別取引。金額などの事実を入力し、仕訳はサーバー(ライブラリ)が作る。

import { h, get, post, state, yen, LABELS, options } from '../core.js';
import {
  page, card, form, note, alertBox, roleRequired, asyncArea, postedJournals, previewJournals, unverified, table,
  accountSelect,
} from '../components.js';

const CASH = '101';
const ACCOUNTS_RECEIVABLE = '102';
const EQUIPMENT = '108';
const TRADING_SECURITIES = '114';
const CHECKING = '115';
const NON_TRADE_NOTES_PAYABLE = '217';

const journalsResult = (title) => (v, res) => postedJournals(res.journalIds, title);

// ---- #8 債権 ----

export function receivables() {
  return page('債権(貸倒れ・償却債権の回収)', roleRequired('BOOKKEEPER'),
    card('貸倒れ', note('前期以前に発生した債権は貸倒引当金を先に充当し、足りない分を貸倒損失にします。当期に発生した債権は全額を貸倒損失にします。'),
      form({
        role: 'BOOKKEEPER',
        fields: [
          { name: 'date', label: '日付', type: 'date', required: true },
          { name: 'receivableAccountCode', label: '貸倒れた債権の科目', type: 'account', value: ACCOUNTS_RECEIVABLE, required: true },
          { name: 'amount', label: '貸倒額', type: 'yen', required: true },
          { name: 'arisingInCurrentPeriod', label: '当期に発生した債権である', type: 'checkbox', badge: unverified('6-1, 6-2') },
        ],
        onSubmit: (v) => post('/transactions/bad-debts', v),
        result: journalsResult('貸倒れの仕訳'),
      })),
    card(h('span', {}, '償却済み債権の回収 ', unverified('6-3')), form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'proceedsAccountCode', label: '回収額を受け取る科目', type: 'account', value: CASH, required: true },
        { name: 'amount', label: '回収額', type: 'yen', required: true },
      ],
      onSubmit: (v) => post('/transactions/bad-debt-recoveries', v),
      result: journalsResult('回収の仕訳'),
    })));
}

// ---- #9 手形・電子記録債権 ----

export function notes() {
  return page('手形・電子記録債権の割引', roleRequired('BOOKKEEPER'),
    note('手形の受取・振出・期日決済・裏書・不渡は、計算が不要なので「仕訳入力」で記帳します。'),
    card(null, form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'proceedsAccountCode', label: '受取額を入れる科目', type: 'account', value: CASH, required: true },
        { name: 'faceValue', label: '額面金額', type: 'yen', required: true },
        { name: 'discountFee', label: '割引料', type: 'yen', required: true, help: '割引料は額で入力します(日割計算はしません)。' },
        { name: 'electronicallyRecorded', label: '電子記録債権の譲渡(割引)である', type: 'checkbox', badge: unverified('11-1') },
      ],
      onSubmit: (v) => post('/transactions/notes-discounts', v),
      result: journalsResult('割引の仕訳'),
    })));
}

// ---- #10 現金過不足 ----

export function cash() {
  const status = asyncArea(async () => {
    if (!state.periodId) return null;
    const [ledger, progress] = await Promise.all([
      get(`/accounts/${CASH}/ledger?periodId=${state.periodId}`),
      get(`/periods/${state.periodId}/progress`),
    ]);
    return h('dl', { class: 'kv' },
      h('dt', {}, '現金の帳簿残高'), h('dd', { class: 'num' }, `${yen(ledger.balance)} 円`),
      h('dt', {}, '現金過不足の残高'), h('dd', { class: 'num' }, `${yen(progress.cashOverShortBalance)} 円`));
  });
  return page('現金過不足', roleRequired('BOOKKEEPER'),
    card('現在の状況(選択中の期)', status),
    card('手許現金の実際有高の記録', note('帳簿残高との差を現金過不足に記録します。一致していれば仕訳は作られません。'),
      form({
        role: 'BOOKKEEPER',
        fields: [
          { name: 'date', label: '日付', type: 'date', required: true },
          { name: 'actualCashOnHand', label: '手許現金の実際有高', type: 'yen', required: true },
        ],
        onSubmit: (v) => post('/transactions/cash-counts', v),
        result: journalsResult('現金過不足の仕訳'),
      })),
    card('決算時の振替', note('原因がわからないまま残っている現金過不足を、雑損(不足)または雑収入(過剰)へ振り替えます。締めの前に必要です。',
      '原因が判明したときの振替は「仕訳入力」で記帳します。'),
    form({
      role: 'BOOKKEEPER', submitLabel: '振り替える',
      fields: [{ name: 'date', label: '日付', type: 'date', required: true,
        value: periodEnd() }],
      onSubmit: (v) => post('/transactions/cash-over-short-settlements', v),
      result: journalsResult('振替の仕訳'),
    })));
}

function periodEnd() {
  const period = state.periods && state.periods.find((p) => p.id === state.periodId);
  return period ? period.endDate : undefined;
}

// ---- #11 銀行勘定調整表 ----

const ITEM_KINDS = {
  DEPOSIT_IN_TRANSIT: { label: '時間外預入(銀行側 +)', book: false },
  OUTSTANDING_CHECK: { label: '未取付小切手(銀行側 −)', book: false },
  UNCOLLECTED_CHECK: { label: '未取立小切手(銀行側 +)', book: false },
  UNRECORDED_DEPOSIT: { label: '連絡未通知・入金(帳簿側 +)', book: true },
  UNRECORDED_WITHDRAWAL: { label: '連絡未通知・引落し(帳簿側 −)', book: true },
  UNISSUED_CHECK: { label: '未渡小切手(帳簿側 +)', book: true },
  RECORDING_ERROR: { label: '誤記入(帳簿側 ±)', book: true },
};

export function bank() {
  const itemsBody = h('tbody');
  function addItem() {
    const kind = h('select', {}, Object.entries(ITEM_KINDS).map(([value, k]) => h('option', { value }, k.label)));
    const amount = h('input', { type: 'number', step: '1', class: 'num' });
    const counter = accountSelect('', { required: false });
    const tr = h('tr', {}, h('td', {}, kind), h('td', {}, amount), h('td', {}, counter),
      h('td', {}, h('button', { type: 'button', class: 'btn-icon', onclick: () => tr.remove() }, '×')));
    itemsBody.append(tr);
  }
  addItem();
  const itemsEditor = h('div', { class: 'field field-wide' },
    h('span', { class: 'field-label' }, '調整項目', unverified('11-2〜11-5')),
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, '種類'), h('th', { class: 'num' }, '金額(円)'), h('th', {}, '相手科目(帳簿側のみ)'), h('th', {}))),
      itemsBody)),
    h('div', { class: 'row-actions' }, h('button', { type: 'button', class: 'btn-secondary', onclick: addItem }, '+ 項目を追加')),
    h('small', { class: 'help' }, '誤記入の金額は帳簿残高の訂正額です(減らすときはマイナス)。未渡小切手の相手科目は、商品代金なら買掛金、それ以外は未払金を選びます。'));

  function collectItems() {
    return [...itemsBody.children].map((tr) => {
      const [kind, amount, counter] = [...tr.querySelectorAll('select, input')];
      return { kind: kind.value, amount: Number(amount.value || 0), counterAccountCode: counter.value || null };
    }).filter((item) => item.amount !== 0);
  }

  const body = (v) => ({ date: v.date, checkingAccountCode: v.checkingAccountCode,
    bankStatementBalance: v.bankStatementBalance ?? 0, items: collectItems() });

  const reconciliationForm = form({
    role: 'BOOKKEEPER', submitLabel: '修正仕訳を記帳する',
    fields: [
      { name: 'date', label: '日付', type: 'date', required: true },
      { name: 'checkingAccountCode', label: '当座預金の科目', type: 'account', value: CHECKING, required: true },
      { name: 'bankStatementBalance', label: '銀行の残高証明書の残高', type: 'yen', allowNegative: true, required: true },
    ],
    extra: h('div', { class: 'field-wide' }, itemsEditor,
      h('div', { class: 'form-actions' }, h('button', { type: 'button', class: 'btn-secondary', onclick: preview }, '調整表をプレビュー'))),
    onSubmit: (v) => post('/bank-reconciliations', body(v)),
    result: async (v, res) => h('div', {}, reconciliationSummary(res), await postedJournals(res.journalIds, '修正仕訳')),
  });

  async function preview() {
    try {
      const res = await post('/bank-reconciliations/preview', body(reconciliationForm.values()));
      reconciliationForm.showMessage(h('div', {}, reconciliationSummary(res), previewJournals(res.correctingEntries, '記帳される修正仕訳(まだ記帳していません)')));
    } catch (error) {
      reconciliationForm.showMessage(alertBox('error', error.message));
    }
  }

  return page('銀行勘定調整表', roleRequired('BOOKKEEPER'),
    note('帳簿残高は選んだ日付の期の仕訳から求めます。銀行側の項目は仕訳にならず、帳簿側の項目だけが修正仕訳になります。'),
    card(null, reconciliationForm));
}

function reconciliationSummary(r) {
  return h('table', { class: 'summary' }, h('tbody', {},
    h('tr', {}, h('th', {}, '銀行の残高証明書残高'), h('td', { class: 'num' }, yen(r.bankStatementBalance)),
      h('th', {}, '帳簿残高'), h('td', { class: 'num' }, yen(r.bookBalance))),
    h('tr', {}, h('th', {}, '銀行側の調整後残高'), h('td', { class: 'num' }, yen(r.adjustedBankBalance)),
      h('th', {}, '帳簿側の調整後残高'), h('td', { class: 'num' }, yen(r.adjustedBookBalance))),
    h('tr', {}, h('th', {}, '一致'), h('td', { colspan: 3 }, r.reconciles
      ? h('span', { class: 'badge badge-ok' }, '一致しています')
      : h('span', { class: 'badge badge-error' }, '一致していません(調整項目を見直してください)')))));
}

// ---- #12 有価証券の売買 ----

export function securities() {
  return page('有価証券の売買', roleRequired('BOOKKEEPER'),
    note('売買目的有価証券の購入(端数利息なし)は「仕訳入力」で記帳します。'),
    card(h('span', {}, '端数利息付きの債券の購入 ', unverified('10-5, 10-6')),
      note('端数利息は、前回の利払日の翌日から購入日までの日数でライブラリが計算します(額面 × 年利率 × 日数 ÷ 365、円未満切り捨て)。'),
      form({
        role: 'BOOKKEEPER',
        fields: [
          { name: 'date', label: '購入日', type: 'date', required: true },
          { name: 'securitiesAccountCode', label: '債券の科目', type: 'account', value: TRADING_SECURITIES, required: true },
          { name: 'faceValue', label: '額面金額', type: 'yen', required: true },
          { name: 'annualRate', label: '年利率(例: 0.0365)', type: 'decimal', required: true },
          { name: 'lastInterestDate', label: '前回の利払日', type: 'date', required: true },
          { name: 'purchasePrice', label: '購入価額(端数利息を含まない)', type: 'yen', required: true },
          { name: 'paymentAccountCode', label: '支払う科目', type: 'account', value: CASH, required: true },
        ],
        onSubmit: (v) => post('/transactions/bond-purchases', v),
        result: journalsResult('購入の仕訳'),
      })),
    card('売買目的有価証券の売却', note('数量を空にすると全額売却、入力すると一部売却(平均単価で帳簿価額を按分)になります。'),
      form({
        role: 'BOOKKEEPER',
        fields: [
          { name: 'date', label: '売却日', type: 'date', required: true },
          { name: 'proceedsAccountCode', label: '売却代金を受け取る科目', type: 'account', value: CASH, required: true },
          { name: 'proceeds', label: '売却価額', type: 'yen', required: true },
          { name: 'quantitySold', label: '売却数量', type: 'int', min: '1', badge: unverified('10-7') },
          { name: 'quantityHeld', label: '保有数量', type: 'int', min: '1' },
          { name: 'accruedInterest', label: '端数利息(債券の一部売却のとき)', type: 'yen' },
        ],
        onSubmit: (v) => post('/transactions/trading-securities-sales', v),
        result: journalsResult('売却の仕訳'),
      })));
}

// ---- #13 割賦購入 ----

export function installments() {
  const planForm = form({
    role: 'BOOKKEEPER', submitLabel: '支払予定表を表示',
    fields: [
      { name: 'assetAccountCode', label: '取得する資産の科目', type: 'account', value: EQUIPMENT, required: true },
      { name: 'cashPrice', label: '現金購入価額', type: 'yen', required: true },
      { name: 'totalPayment', label: '支払総額', type: 'yen', required: true },
      { name: 'numberOfInstallments', label: '支払回数', type: 'int', min: '1', required: true },
      { name: 'payableAccountCode', label: '支払義務の科目', type: 'account', value: NON_TRADE_NOTES_PAYABLE, required: true },
    ],
    onSubmit: (v) => post('/transactions/installment-schedules', v),
    result: (v, rows) => table([
      { label: '回', key: 'installmentNumber', num: true },
      { label: '支払額', render: (r) => yen(r.payment), num: true },
      { label: '利息(前払利息から振替)', render: (r) => yen(r.interest), num: true },
    ], rows),
  });
  return page('固定資産の割賦購入', roleRequired('BOOKKEEPER'),
    note('利息相当額(支払総額 − 現金購入価額)を前払利息とし、支払のたびに定額で支払利息へ振り替えます。購入と各回の支払で同じ条件を使います。', unverified('9-5')),
    card('割賦の条件', planForm),
    card('購入の記帳', form({
      role: 'BOOKKEEPER',
      fields: [{ name: 'date', label: '購入日', type: 'date', required: true }],
      onSubmit: (v) => post('/transactions/installment-purchases', { date: v.date, plan: planForm.values() }),
      result: journalsResult('購入の仕訳'),
    })),
    card('支払の記帳', form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '支払日', type: 'date', required: true },
        { name: 'installmentNumber', label: '何回目の支払か', type: 'int', min: '1', required: true },
        { name: 'paymentAccountCode', label: '支払う科目', type: 'account', value: CHECKING, required: true },
      ],
      onSubmit: (v) => post('/transactions/installment-payments', { ...v, plan: planForm.values() }),
      result: journalsResult('支払の仕訳'),
    })));
}

// ---- #14 配当・合併 ----

export function equity() {
  return page('剰余金の配当・吸収合併', roleRequired('BOOKKEEPER'),
    note('新株の発行は計算が不要なので「仕訳入力」で記帳します。'),
    card('剰余金の配当', note('準備金の積立額は、資本金・資本準備金・利益準備金の残高(当期の仕訳から求める)をもとにライブラリが計算します。'),
      form({
        role: 'BOOKKEEPER',
        fields: [
          { name: 'date', label: '日付', type: 'date', required: true },
          { name: 'dividendAmount', label: '配当額', type: 'yen', required: true },
          { name: 'source', label: '原資', type: 'select', required: true, options: [
            { value: 'RETAINED_EARNINGS', label: '繰越利益剰余金(利益準備金を積み立てる)' },
            { value: 'OTHER_CAPITAL_SURPLUS', label: 'その他資本剰余金(資本準備金を積み立てる)' }],
          help: 'その他資本剰余金からの配当は未検証のルールです(12-1)。' },
        ],
        onSubmit: (v) => post('/transactions/dividends', v),
        result: journalsResult('配当の仕訳'),
      })),
    card(h('span', {}, '吸収合併 ', unverified('12-3')),
      note('受け入れた資産・負債は時価で入力します。対価との差額は、のれん(対価が多い)または負ののれん発生益(対価が少ない)になります。'),
      form({
        role: 'BOOKKEEPER',
        fields: [
          { name: 'date', label: '合併日', type: 'date', required: true },
          { name: 'acquiredAssets', label: '受け入れた資産(借方)', type: 'lines', side: 'DEBIT', minRows: 1 },
          { name: 'assumedLiabilities', label: '引き受けた負債(貸方)', type: 'lines', side: 'CREDIT', minRows: 1 },
          { name: 'consideration', label: '対価(資本金・資本準備金・現金など。貸方)', type: 'lines', side: 'CREDIT', minRows: 1 },
        ],
        onSubmit: (v) => post('/transactions/mergers', v),
        result: journalsResult('合併の仕訳'),
      })));
}

// ---- #15 引当金の取崩し ----

export function provisions() {
  return page('引当金の取崩し', roleRequired('BOOKKEEPER'),
    note('支払額のうち引当金の残高までを引当金で充当し、足りない分を費用(賞与・修繕費など)にします。決算での繰入は「決算整理」で行います。', unverified('7-7')),
    card(null, form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '支払日', type: 'date', required: true },
        { name: 'type', label: '引当金の種類', type: 'select', options: options(LABELS.provisionType), required: true },
        { name: 'amount', label: '支払額', type: 'yen', required: true },
        { name: 'paymentAccountCode', label: '支払う科目', type: 'account', value: CASH, required: true },
      ],
      onSubmit: (v) => post('/transactions/provision-uses', v),
      result: journalsResult('取崩しの仕訳'),
    })));
}

// ---- #16 収益認識 ----

export function revenue() {
  return page('収益認識', roleRequired('BOOKKEEPER'),
    note('契約負債(前受け)の計上や、役務の費用を仕掛品に振り替える仕訳は「仕訳入力」で記帳します。'),
    card(h('span', {}, '返金が見込まれる販売 ', unverified('13-1')), form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'receivableAccountCode', label: '債権の科目', type: 'account', value: ACCOUNTS_RECEIVABLE, required: true },
        { name: 'totalConsideration', label: '対価の総額', type: 'yen', required: true },
        { name: 'expectedRefund', label: '返金の見込額', type: 'yen', required: true },
      ],
      onSubmit: (v) => post('/transactions/sales-with-expected-refund', v),
      result: journalsResult('販売の仕訳'),
    })),
    card(h('span', {}, '履行義務の充足(契約資産) ', unverified('13-2')), form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'revenue', label: '履行した分の売上', type: 'yen', required: true },
      ],
      onSubmit: (v) => post('/transactions/contract-assets', v),
      result: journalsResult('契約資産の仕訳'),
    })),
    card(h('span', {}, '契約の完了と請求 ', unverified('13-2')), form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'receivableAccountCode', label: '債権の科目', type: 'account', value: ACCOUNTS_RECEIVABLE, required: true },
        { name: 'remainingRevenue', label: '今回の履行で認識する売上', type: 'yen', required: true },
        { name: 'contractJournalIds', label: 'この契約の契約資産を計上した仕訳の ID(1行に1つ)', type: 'textarea',
          help: '契約資産の残高を、この契約の分だけで求めるために指定します。契約が1件だけなら空でかまいません。仕訳 ID は仕訳帳の「詳細」で確認できます。' },
      ],
      onSubmit: (v) => post('/transactions/contract-billings', {
        ...v, contractJournalIds: (v.contractJournalIds || '').split(/\s+/).filter(Boolean),
      }),
      result: journalsResult('請求の仕訳'),
    })),
    card(h('span', {}, '役務収益の計上 ', unverified('13-3')), form({
      role: 'BOOKKEEPER',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'receivableAccountCode', label: '債権の科目', type: 'account', value: ACCOUNTS_RECEIVABLE, required: true },
        { name: 'revenue', label: '役務収益', type: 'yen', required: true },
        { name: 'costOfServices', label: '仕掛品から役務原価へ振り替える額', type: 'yen', required: true },
      ],
      onSubmit: (v) => post('/transactions/service-revenues', v),
      result: journalsResult('役務収益の仕訳'),
    })));
}

