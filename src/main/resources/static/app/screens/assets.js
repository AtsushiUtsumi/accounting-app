// #17 商品有高帳、#18 固定資産台帳、#19 無形固定資産・満期保有目的債券。

import { h, get, post, state, yen, clear, accountName } from '../core.js';
import {
  page, card, form, note, alertBox, roleRequired, asyncArea, postedJournals, unverified, table, tabs, needPeriod,
} from '../components.js';

const CASH = '101';
const ACCOUNTS_RECEIVABLE = '102';
const OTHER_RECEIVABLES = '112';
const DEPRECIATION_EXPENSE = '502';

// ---- #17 商品有高帳 ----

const METHODS = [{ value: 'FIFO', label: '先入先出法' }, { value: 'MOVING_AVERAGE', label: '移動平均法' }];

export function inventory(params, { navigate }) {
  const item = params.item || '';
  const method = params.method || 'FIFO';
  const itemInput = h('input', { value: item, placeholder: '例: A', required: true, maxlength: 32 });
  const methodSelect = h('select', {}, METHODS.map((m) => h('option', { value: m.value }, m.label)));
  methodSelect.value = method;
  const picker = h('form', { class: 'toolbar', onsubmit: (e) => {
    e.preventDefault();
    navigate(`/inventory?item=${encodeURIComponent(itemInput.value.trim())}&method=${methodSelect.value}`);
  } }, h('label', {}, '品目コード ', itemInput), h('label', {}, '払出単価の決め方 ', methodSelect),
  h('button', { type: 'submit', class: 'btn-secondary' }, '表示'));

  if (!item) {
    return page('商品有高帳', card(null, picker, note('品目コードを入力して表示します。受入・払出の明細は品目ごとに、仕訳とは別に記録します。')));
  }

  const base = `/inventory/${encodeURIComponent(item)}`;
  const reload = () => navigate(`/inventory?item=${encodeURIComponent(item)}&method=${method}&t=${Date.now()}`);
  const methodLabel = METHODS.find((m) => m.value === method).label;

  return page(`商品有高帳: ${item}`,
    card(null, picker),
    card(`帳簿(${methodLabel})`, asyncArea(async () => ledgerTable(await get(`${base}/movements?method=${method}`)))),
    note('払出単価の決め方は、品目ごとに一つに決めて使い続けてください(払出・販売の記録は、選んでいる方法で計算します)。'),
    roleRequired('BOOKKEEPER'),
    card('受入(仕入・前期繰越など)', form({
      role: 'BOOKKEEPER', submitLabel: '記録する',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'description', label: '摘要', value: '仕入', required: true },
        { name: 'quantity', label: '数量', type: 'int', min: '1', required: true },
        { name: 'unitCost', label: '単価', type: 'yen', required: true },
      ],
      onSubmit: (v) => post(`${base}/movements`, { kind: 'RECEIPT', ...v }),
      result: () => { reload(); return null; },
    }), note('三分法の会社で仕入の仕訳も必要なときは、「仕訳入力」で別に記帳します。')),
    card(`払出(${methodLabel})`, form({
      role: 'BOOKKEEPER', submitLabel: '記録する',
      fields: [
        { name: 'date', label: '日付', type: 'date', required: true },
        { name: 'description', label: '摘要', value: '売上', required: true },
        { name: 'quantity', label: '数量', type: 'int', min: '1', required: true },
      ],
      onSubmit: (v) => post(`${base}/movements`, { kind: 'ISSUE', method, ...v }),
      result: () => { reload(); return null; },
    })),
    card(h('span', {}, '販売(売上原価対立法) ', unverified('8-1')),
      note('払出を記録し、売上と売上原価(払出の原価)の仕訳を同時に記帳します。'),
      form({
        role: 'BOOKKEEPER',
        fields: [
          { name: 'date', label: '日付', type: 'date', required: true },
          { name: 'description', label: '摘要', value: '売上', required: true },
          { name: 'quantity', label: '数量', type: 'int', min: '1', required: true },
          { name: 'receivableAccountCode', label: '売上代金の科目', type: 'account', value: ACCOUNTS_RECEIVABLE, required: true },
          { name: 'salesAmount', label: '売上高', type: 'yen', required: true },
        ],
        onSubmit: (v) => post(`${base}/sales`, { ...v, method }),
        result: async (v, res) => { const node = await postedJournals(res.journalIds, '販売の仕訳'); setTimeout(reload, 1500); return node; },
      })),
    card(h('span', {}, '売上の返品(売上原価対立法) ', unverified('8-2')),
      note('戻ってきた商品を払出時の単価で受け入れ、売上の取消と売上原価から商品への振替を記帳します。払出時の単価は上の帳簿の払出欄で確認できます。'),
      form({
        role: 'BOOKKEEPER',
        fields: [
          { name: 'date', label: '日付', type: 'date', required: true },
          { name: 'description', label: '摘要', value: '売上戻り', required: true },
          { name: 'quantity', label: '数量', type: 'int', min: '1', required: true },
          { name: 'unitCost', label: '払出時の単価', type: 'yen', required: true },
          { name: 'receivableAccountCode', label: '売上代金の科目', type: 'account', value: ACCOUNTS_RECEIVABLE, required: true },
          { name: 'salesAmount', label: '取り消す売上高', type: 'yen', required: true },
        ],
        onSubmit: (v) => post(`${base}/sales-returns`, v),
        result: async (v, res) => { const node = await postedJournals(res.journalIds, '返品の仕訳'); setTimeout(reload, 1500); return node; },
      })));
}

function lots(list) {
  if (!list.length) return '';
  return h('div', {}, list.map((lot) => h('div', { class: 'lot' },
    h('span', {}, `${lot.quantity} 個`), h('span', {}, `@${yen(lot.unitCost)}`), h('span', {}, yen(lot.amount)))));
}

function ledgerTable(entries) {
  return table([
    { label: '日付', key: 'date' },
    { label: '摘要', key: 'description' },
    { label: '受入(数量 単価 金額)', render: (e) => lots(e.receipts) },
    { label: '払出(数量 単価 金額)', render: (e) => lots(e.issues) },
    { label: '残高(数量 単価 金額)', render: (e) => lots(e.balance) },
    { label: '残高合計', render: (e) => `${e.balanceQuantity} 個 / ${yen(e.balanceAmount)} 円`, num: true },
  ], entries, { empty: 'この品目の受入・払出はまだありません。' });
}

// ---- #18 固定資産台帳 ----

const DEPRECIATION_METHODS = {
  STRAIGHT_LINE: '定額法', DECLINING_BALANCE: '定率法', UNITS_OF_PRODUCTION: '生産高比例法',
};

export function fixedAssets(params, { navigate }) {
  const missing = needPeriod();
  if (missing) return page('固定資産台帳', missing);
  const actionArea = h('div');
  const reload = () => navigate(`/fixed-assets?t=${Date.now()}`);

  const list = asyncArea(async () => {
    const assets = await get(`/fixed-assets?periodId=${state.periodId}`);
    return table([
      { label: '名称', key: 'name' },
      { label: '資産の科目', render: (a) => `${a.assetAccountCode} ${accountName(a.assetAccountCode)}` },
      { label: '取得日', key: 'acquisitionDate' },
      { label: '取得価額', render: (a) => yen(a.acquisitionCost), num: true },
      { label: '耐用年数', render: (a) => `${a.usefulLifeYears} 年`, num: true },
      { label: '償却方法', render: (a) => methodLabel(a.depreciation) },
      { label: '累計償却額', render: (a) => yen(a.accumulatedDepreciation), num: true },
      { label: '帳簿価額', render: (a) => yen(a.bookValue), num: true },
      { label: '状態', render: (a) => a.disposedOn ? h('span', { class: 'badge badge-muted' }, `${a.disposedOn} 処分`) : '使用中' },
      { label: '', render: (a) => a.disposedOn ? '' : h('button', {
        type: 'button', class: 'btn-secondary btn-small', onclick: () => showActions(a),
      }, '売却・除却など') },
    ], assets, { empty: '登録された固定資産はありません。' });
  });

  function showActions(asset, active = 'sale') {
    const disposalFields = [
      { name: 'date', label: '処分日', type: 'date', required: true },
      { name: 'recordDepreciationUntilDisposal', label: '処分日までの当期の減価償却(月割)を先に記帳する', type: 'checkbox', value: true,
        badge: unverified('6-4, 6-5') },
      asset.depreciation.method === 'UNITS_OF_PRODUCTION'
        ? { name: 'usageThisPeriod', label: '当期の利用量(生産高比例法)', type: 'int', badge: unverified('9-6') } : null,
    ].filter(Boolean);
    const forms = {
      sale: () => form({
        role: 'BOOKKEEPER', submitLabel: '売却を記帳する',
        fields: [...disposalFields,
          { name: 'proceedsAccountCode', label: '売却代金を受け取る科目', type: 'account', value: OTHER_RECEIVABLES, required: true },
          { name: 'proceeds', label: '売却価額', type: 'yen', required: true }],
        onSubmit: (v) => post(`/fixed-assets/${asset.id}/disposal`, v),
        result: done('売却の仕訳'),
      }),
      retirement: () => form({
        role: 'BOOKKEEPER', submitLabel: '除却を記帳する',
        fields: [...disposalFields,
          { name: 'estimatedDisposalValue', label: '見積処分価額(なければ 0)', type: 'yen', required: true, value: 0, badge: unverified('9-1, 9-2') }],
        onSubmit: (v) => post(`/fixed-assets/${asset.id}/retirement`, v),
        result: done('除却の仕訳'),
      }),
      abandonment: () => form({
        role: 'BOOKKEEPER', submitLabel: '廃棄を記帳する',
        fields: [...disposalFields,
          { name: 'disposalCost', label: '廃棄費用(なければ 0)', type: 'yen', required: true, value: 0, badge: unverified('9-3') },
          { name: 'paymentAccountCode', label: '廃棄費用を支払う科目', type: 'account', value: CASH, required: false }],
        onSubmit: (v) => post(`/fixed-assets/${asset.id}/abandonment`, v),
        result: done('廃棄の仕訳'),
      }),
      compression: () => h('div', {},
        note('国庫補助金の額だけ取得価額を減らし、固定資産圧縮損を計上します(直接減額方式)。減価償却を始める前(取得時)に行います。',
          '国庫補助金の受け取りは「仕訳入力」で別に記帳します。'),
        form({
          role: 'BOOKKEEPER', submitLabel: '圧縮記帳する',
          fields: [
            { name: 'date', label: '日付', type: 'date', required: true },
            { name: 'reduction', label: '圧縮額(国庫補助金の額)', type: 'yen', required: true, badge: unverified('9-4') },
          ],
          onSubmit: (v) => post(`/fixed-assets/${asset.id}/compression`, v),
          result: done('圧縮記帳の仕訳'),
        })),
    };
    clear(actionArea).append(card(`${asset.name} の処分・圧縮記帳`,
      tabs([{ key: 'sale', label: '売却' }, { key: 'retirement', label: '除却' }, { key: 'abandonment', label: '廃棄' },
        { key: 'compression', label: '圧縮記帳' }], active, (key) => showActions(asset, key)),
      forms[active]()));
    actionArea.scrollIntoView({ behavior: 'smooth' });
  }

  function done(title) {
    return async (v, res) => {
      const node = await postedJournals(res.journalIds, title);
      setTimeout(reload, 2500);
      return node;
    };
  }

  return page('固定資産台帳',
    note('累計償却額・帳簿価額は、選択中の期の仕訳からライブラリが求めます(保存していません)。減価償却は決算整理で行います。'),
    card('一覧', list),
    actionArea,
    card('固定資産の登録', roleRequired('BOOKKEEPER'),
      note('取得そのものの仕訳(備品/現金 など)は「仕訳入力」で別に記帳してください。',
        '同じ種類の資産が複数あるなら、資産ごとに専用の科目(資産・減価償却累計額)を「勘定科目」で作ってから登録してください(ライブラリの制限)。'),
      registerFixedAssetForm()));
}

function methodLabel(d) {
  const label = DEPRECIATION_METHODS[d.method];
  if (d.method === 'DECLINING_BALANCE') return `${label}(償却率 ${d.decliningRate})`;
  if (d.method === 'UNITS_OF_PRODUCTION') return `${label}(総利用可能量 ${yen(d.totalEstimatedUsage)})`;
  return label;
}

function registerFixedAssetForm() {
  const methodSelect = h('select', { name: 'method', required: true },
    Object.entries(DEPRECIATION_METHODS).map(([value, label]) => h('option', { value }, label)));
  const rate = h('input', { type: 'number', step: 'any', min: '0', class: 'num', placeholder: '例: 0.4' });
  const usage = h('input', { type: 'number', step: '1', min: '1', class: 'num' });
  const rounding = h('select', {}, h('option', { value: 'DOWN' }, '切り捨て'), h('option', { value: 'HALF_UP' }, '四捨五入'));
  const rateField = h('label', { class: 'field' }, h('span', { class: 'field-label' }, '償却率(定率法)'), rate);
  const usageField = h('label', { class: 'field' }, h('span', { class: 'field-label' }, '総利用可能量(生産高比例法)', unverified('9-6')), usage);
  const syncMethod = () => {
    rateField.hidden = methodSelect.value !== 'DECLINING_BALANCE';
    usageField.hidden = methodSelect.value !== 'UNITS_OF_PRODUCTION';
  };
  methodSelect.addEventListener('change', syncMethod);
  syncMethod();

  return form({
    role: 'BOOKKEEPER', submitLabel: '登録する',
    fields: [
      { name: 'name', label: '名称', required: true, maxlength: 100 },
      { name: 'assetAccountCode', label: '資産の科目', type: 'account', required: true, filter: (a) => a.category === 'FIXED_ASSET' },
      { name: 'accumulatedDepreciationAccountCode', label: '減価償却累計額の科目', type: 'account', required: true,
        filter: (a) => a.category === 'FIXED_ASSET' },
      { name: 'depreciationExpenseAccountCode', label: '減価償却費の科目', type: 'account', value: DEPRECIATION_EXPENSE, required: true },
      { name: 'acquisitionCost', label: '取得価額', type: 'yen', required: true },
      { name: 'acquisitionDate', label: '取得日', type: 'date', required: true },
      { name: 'usefulLifeYears', label: '耐用年数(年)', type: 'int', min: '1', required: true },
      { name: 'residualValue', label: '残存価額', type: 'yen', required: true, value: 0 },
    ],
    extra: h('div', { class: 'field-wide fieldset' },
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, '償却方法'), methodSelect),
      rateField, usageField,
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, '円未満の端数'), rounding)),
    onSubmit: (v) => post('/fixed-assets', {
      ...v,
      depreciation: {
        method: methodSelect.value,
        decliningRate: methodSelect.value === 'DECLINING_BALANCE' && rate.value ? Number(rate.value) : null,
        totalEstimatedUsage: methodSelect.value === 'UNITS_OF_PRODUCTION' && usage.value ? Number(usage.value) : null,
        rounding: rounding.value,
      },
    }),
    result: () => { setTimeout(() => location.reload(), 800); return alertBox('success', '登録しました。'); },
  });
}

// ---- #19 無形固定資産・満期保有目的債券 ----

export function otherAssets() {
  const missing = needPeriod();
  if (missing) return page('無形固定資産・満期保有目的債券', missing);
  return page('無形固定資産・満期保有目的債券',
    note('帳簿価額は、選択中の期の仕訳から求めます。償却(無形固定資産)と償却原価法(債券)は決算整理で行います。',
      '取得そのものの仕訳は「仕訳入力」(債券は「有価証券の売買」も可)で別に記帳してください。'),
    card(h('span', {}, '無形固定資産 ', unverified('9-7')),
      asyncArea(async () => table([
        { label: '名称', key: 'name' },
        { label: '資産の科目', render: (a) => `${a.assetAccountCode} ${accountName(a.assetAccountCode)}` },
        { label: '取得日', key: 'acquisitionDate' },
        { label: '取得価額', render: (a) => yen(a.acquisitionCost), num: true },
        { label: '耐用年数', render: (a) => `${a.usefulLifeYears} 年`, num: true },
        { label: '帳簿価額', render: (a) => yen(a.bookValue), num: true },
      ], await get(`/intangible-assets?periodId=${state.periodId}`), { empty: '登録された無形固定資産はありません。' })),
      h('h3', {}, '登録'), roleRequired('BOOKKEEPER'),
      form({
        role: 'BOOKKEEPER', submitLabel: '登録する',
        fields: [
          { name: 'name', label: '名称', required: true, maxlength: 100 },
          { name: 'assetAccountCode', label: '資産の科目(この資産専用)', type: 'account', value: '138', required: true },
          { name: 'amortizationExpenseAccountCode', label: '償却費の科目', type: 'account', value: '537', required: true },
          { name: 'acquisitionCost', label: '取得価額', type: 'yen', required: true },
          { name: 'acquisitionDate', label: '取得日', type: 'date', required: true },
          { name: 'usefulLifeYears', label: '耐用年数(年)', type: 'int', min: '1', required: true },
        ],
        onSubmit: (v) => post('/intangible-assets', v),
        result: () => { setTimeout(() => location.reload(), 800); return alertBox('success', '登録しました。'); },
      })),
    card(h('span', {}, '満期保有目的債券 ', unverified('10-1, 10-2')),
      asyncArea(async () => table([
        { label: '名称', key: 'name' },
        { label: '債券の科目', render: (b) => `${b.bondAccountCode} ${accountName(b.bondAccountCode)}` },
        { label: '額面金額', render: (b) => yen(b.faceValue), num: true },
        { label: '取得価額', render: (b) => yen(b.acquisitionCost), num: true },
        { label: '取得日', key: 'acquisitionDate' },
        { label: '満期日', key: 'maturityDate' },
        { label: '帳簿価額', render: (b) => yen(b.bookValue), num: true },
      ], await get(`/held-to-maturity-bonds?periodId=${state.periodId}`), { empty: '登録された債券はありません。' })),
      h('h3', {}, '登録'), roleRequired('BOOKKEEPER'),
      form({
        role: 'BOOKKEEPER', submitLabel: '登録する',
        fields: [
          { name: 'name', label: '名称', required: true, maxlength: 100 },
          { name: 'bondAccountCode', label: '債券の科目(この債券専用)', type: 'account', value: '140', required: true },
          { name: 'faceValue', label: '額面金額', type: 'yen', required: true },
          { name: 'acquisitionCost', label: '取得価額', type: 'yen', required: true },
          { name: 'acquisitionDate', label: '取得日', type: 'date', required: true },
          { name: 'maturityDate', label: '満期日', type: 'date', required: true },
        ],
        onSubmit: (v) => post('/held-to-maturity-bonds', v),
        result: () => { setTimeout(() => location.reload(), 800); return alertBox('success', '登録しました。'); },
      })));
}
