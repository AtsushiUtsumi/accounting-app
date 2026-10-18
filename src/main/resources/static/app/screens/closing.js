// #20 決算整理(確認 → 入力 → プレビュー → 確定)、#21 締め・再振替。どれも承認者の操作。

import { h, get, post, state, yen, LABELS, savePeriodId } from '../core.js';
import {
  page, card, form, note, alertBox, roleRequired, asyncArea, postedJournals, previewJournals, unverified, table,
  accountSelect, journalView, needPeriod,
} from '../components.js';

// ---- #20 決算整理 ----

export function adjustments() {
  const missing = needPeriod();
  if (missing) return page('決算整理', missing);
  return page('決算整理', roleRequired('APPROVER'), asyncArea(async () => {
    const [progress, journals, fixedAssets, intangibles, bonds] = await Promise.all([
      get(`/periods/${state.periodId}/progress`),
      get(`/periods/${state.periodId}/journals`),
      get(`/fixed-assets?periodId=${state.periodId}`),
      get(`/intangible-assets?periodId=${state.periodId}`),
      get(`/held-to-maturity-bonds?periodId=${state.periodId}`),
    ]);
    const period = progress.period;
    if (period.status !== 'OPEN') return alertBox('info', 'この期は締め済みです。');
    if (progress.adjustmentsRun) {
      return alertBox('info', 'この期の決算整理は実行済みです。やり直すときは、仕訳帳で決算整理仕訳を赤黒で取り消してから、もう一度実行してください。');
    }
    const manualAdjusting = journals.filter((j) => j.type === 'ADJUSTING' && !j.reversed);
    const usageAssets = fixedAssets.filter((a) => !a.disposedOn && a.depreciation.method === 'UNITS_OF_PRODUCTION');
    const autoTargets = [
      ...fixedAssets.filter((a) => !a.disposedOn).map((a) => `減価償却: ${a.name}`),
      ...intangibles.map((a) => `償却: ${a.name}`),
      ...bonds.map((b) => `償却原価法: ${b.name}`),
    ];

    // ① 経過勘定・貯蔵品の確認
    const confirmed = h('input', { type: 'checkbox' });
    const step1 = card('① 経過勘定・貯蔵品の入力の確認',
      note('法人税等は、それまでのすべての整理を含めた利益に依存するため、経過勘定(前払・未払・前受・未収)と貯蔵品の振替は、',
        'この画面を実行する前に「仕訳入力」で「決算整理」の仕訳として記帳しておきます。'),
      manualAdjusting.length
        ? h('div', {}, h('p', {}, `記帳済みの手入力の決算整理仕訳: ${manualAdjusting.length} 件`), manualAdjusting.map((j) => journalView(j)))
        : alertBox('info', '手入力の決算整理仕訳はまだありません。'),
      h('p', {}, h('a', { href: '#/journals/new?type=ADJUSTING' }, '経過勘定・貯蔵品を入力する(仕訳入力)')),
      h('label', { class: 'field field-check' }, confirmed, h('span', {}, '経過勘定・貯蔵品の入力は済みました')));

    // ② 入力
    const counts = rowsEditor([
      { label: '帳簿数量', make: () => num('0') },
      { label: '実地数量', make: () => num('0') },
      { label: '原価(単価)', make: () => num('0') },
      { label: '正味売却価額(単価)', make: () => num('0') },
    ], 0);
    const afs = rowsEditor([
      { label: 'その他有価証券の科目', make: () => accountSelect('', { value: '143', required: false }) },
      { label: '期末時価', make: () => num('0') },
    ], 0);
    const provisionTypes = Object.entries(LABELS.provisionType).filter(([key]) => key !== 'RETIREMENT_BENEFITS');
    const provisions = rowsEditor([
      { label: '引当金', make: () => h('select', {}, provisionTypes.map(([value, label]) => h('option', { value }, label))) },
      { label: '方法', make: () => h('select', {}, h('option', { value: 'DIFFERENCE' }, '差額補充法'), h('option', { value: 'REPLACEMENT' }, '洗替法')) },
      { label: '期末の必要額', make: () => num('0') },
    ], 0);
    const usageInputs = usageAssets.map((a) => ({ asset: a, input: num('0') }));
    const checking = accountSelect('checkingAccountCode', { value: '115', required: false });

    const inputForm = form({
      role: 'APPROVER', submitLabel: '確定して記帳する',
      fields: [
        { type: 'heading', label: '当座借越', badge: unverified('6-6') },
        { name: 'reclassifyBankOverdraft', label: '当座預金の貸方残高を当座借越へ振り替える', type: 'checkbox' },
        { type: 'heading', label: '商品(売上原価・棚卸減耗損・商品評価損)' },
        { name: 'inventoryMethod', label: '商品の記帳方法', type: 'select', value: 'PERIODIC', options: [
          { value: 'PERIODIC', label: '三分法(仕入・繰越商品)' }, { value: 'PERPETUAL', label: '売上原価対立法(商品・売上原価)' }] },
        { name: 'beginningInventory', label: '期首商品棚卸高(三分法)', type: 'yen' },
        { name: 'endingInventory', label: '期末商品棚卸高・帳簿棚卸高(三分法)', type: 'yen' },
        { type: 'heading', label: '有価証券' },
        { name: 'tradingSecuritiesFairValue', label: '売買目的有価証券の期末時価', type: 'yen' },
        { type: 'heading', label: '引当金' },
        { name: 'allowanceForDoubtfulAccounts', label: '貸倒引当金の期末の必要額(差額補充法)', type: 'yen' },
        { name: 'retirementBenefitExpense', label: '当期の退職給付費用', type: 'yen', badge: unverified('7-8') },
        { type: 'heading', label: '税金' },
        { name: 'incomeTaxAmount', label: '確定した法人税等の額', type: 'yen' },
        { name: 'settleConsumptionTax', label: '消費税(税抜方式)を精算する', type: 'checkbox' },
      ],
      extra: h('div', { class: 'field-wide' },
        h('label', { class: 'field' }, h('span', { class: 'field-label' }, '当座預金の科目(当座借越の振替)'), checking),
        h('h3', {}, '実地棚卸の結果(品目ごと) ', unverified('7-1〜7-5')), counts,
        usageInputs.length ? [h('h3', {}, '生産高比例法の資産の当期の利用量 ', unverified('9-6')),
          usageInputs.map(({ asset, input }) => h('label', { class: 'field' }, h('span', { class: 'field-label' }, asset.name), input))] : null,
        h('h3', {}, 'その他有価証券の期末時価 ', unverified('10-3, 10-4')), afs,
        h('h3', {}, 'その他の引当金 ', unverified('7-6')), provisions,
        h('h3', {}, '自動で処理する資産'),
        autoTargets.length ? h('ul', {}, autoTargets.map((t) => h('li', {}, t))) : h('p', { class: 'muted' }, '登録された資産はありません。'),
        h('div', { class: 'form-actions' }, h('button', { type: 'button', class: 'btn-secondary', onclick: preview }, 'プレビュー'))),
      onSubmit: (v) => {
        if (!confirmed.checked) throw new Error('① の「経過勘定・貯蔵品の入力は済みました」を確認してください。');
        return post(`/periods/${state.periodId}/adjustments`, body(v, true));
      },
      result: (v, res) => postedJournals(res.journalIds, '決算整理仕訳'),
    });

    function body(v, accrualsConfirmed) {
      return {
        reclassifyBankOverdraft: v.reclassifyBankOverdraft,
        checkingAccountCode: checking.value || null,
        inventoryMethod: v.inventoryMethod,
        beginningInventory: v.beginningInventory,
        endingInventory: v.endingInventory,
        inventoryCounts: counts.collect().map(([bookQuantity, actualQuantity, unitCost, netRealizableValue]) => ({
          bookQuantity: Number(bookQuantity), actualQuantity: Number(actualQuantity),
          unitCost: Number(unitCost), netRealizableValue: Number(netRealizableValue),
        })),
        fixedAssetUsages: Object.fromEntries(usageInputs.map(({ asset, input }) => [asset.id, Number(input.value || 0)])),
        tradingSecuritiesFairValue: v.tradingSecuritiesFairValue,
        availableForSaleSecurities: afs.collect().filter(([code]) => code)
          .map(([accountCode, fairValue]) => ({ accountCode, fairValue: Number(fairValue) })),
        allowanceForDoubtfulAccounts: v.allowanceForDoubtfulAccounts,
        provisions: provisions.collect().map(([type, method, requiredBalance]) => ({ type, method, requiredBalance: Number(requiredBalance) })),
        retirementBenefitExpense: v.retirementBenefitExpense,
        incomeTaxAmount: v.incomeTaxAmount,
        settleConsumptionTax: v.settleConsumptionTax,
        accrualsConfirmed,
      };
    }

    async function preview() {
      try {
        const journals = await post(`/periods/${state.periodId}/adjustments/preview`, body(inputForm.values(), confirmed.checked));
        inputForm.showMessage(previewJournals(journals, '③ プレビュー: 作られる決算整理仕訳(まだ記帳していません)'));
      } catch (error) {
        inputForm.showMessage(alertBox('error', error.message));
      }
    }

    return [
      note(`対象: ${period.startDate} 〜 ${period.endDate}。整理仕訳はすべて期末日(${period.endDate})付けで、`,
        'ライブラリが決めた順序(当座借越 → 売上原価・棚卸 → 減価償却 → 有価証券 → 引当金 → 法人税等 → 消費税)で作られます。',
        '途中では保存せず、確定したときにまとめて記帳します。空欄の手順は実行しません。'),
      step1,
      card('② 決算整理の入力(外部から与える事実)', inputForm),
    ];
  }));
}

function num(value) {
  return h('input', { type: 'number', step: '1', class: 'num', value });
}

/** 行を追加できる入力表。collect() は各行の入力値の配列を返す(空の行は除く)。 */
function rowsEditor(columns, initialRows = 1) {
  const body = h('tbody');
  function addRow() {
    const cells = columns.map((c) => c.make());
    const tr = h('tr', {}, cells.map((el) => h('td', {}, el)),
      h('td', {}, h('button', { type: 'button', class: 'btn-icon', onclick: () => tr.remove() }, '×')));
    body.append(tr);
  }
  for (let i = 0; i < initialRows; i++) addRow();
  const wrap = h('div', { class: 'rows-editor' },
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, columns.map((c) => h('th', {}, c.label)), h('th', {}))), body)),
    h('button', { type: 'button', class: 'btn-secondary', onclick: addRow }, '+ 行を追加'));
  wrap.collect = () => [...body.children]
    .map((tr) => [...tr.querySelectorAll('input, select')].map((el) => el.value))
    .filter((values) => values.some((v) => v !== '' && v !== '0'));
  return wrap;
}

// ---- #21 締め・再振替 ----

export function close(params, { refresh, navigate }) {
  const missing = needPeriod();
  if (missing) return page('締め・再振替', missing);
  return page('締め・再振替', roleRequired('APPROVER'), asyncArea(async () => {
    const progress = await get(`/periods/${state.periodId}/progress`);
    const period = progress.period;
    const open = period.status === 'OPEN';

    const checks = h('ul', { class: 'checks' },
      check(progress.cashOverShortBalance === 0, progress.cashOverShortBalance === 0
        ? '現金過不足の残高はありません'
        : h('span', {}, `現金過不足の残高が ${yen(progress.cashOverShortBalance)} 円あります。`, h('a', { href: '#/tx/cash' }, '振り替える'))),
      check(progress.adjustmentsRun, progress.adjustmentsRun ? '決算整理は実行済みです'
        : h('span', {}, '決算整理がまだです(必要なければ、このまま締められます)。', h('a', { href: '#/closing/adjustments' }, '決算整理へ'))));

    const closeCard = card('締め',
      note('収益・費用を損益勘定へ振り替えて当期純利益を繰越利益剰余金へ移し(期末日付)、資産・負債・純資産の残高を次期に引き継ぐ開始仕訳(次期の期首日付)を記帳します。',
        'この期は「締め済み」になり、以後は記帳できません(訂正は次期に赤黒で行います)。'),
      open ? [checks, form({
        role: 'APPROVER', submitLabel: 'この期を締める',
        fields: [],
        onSubmit: () => {
          if (!confirm(`${period.startDate} 〜 ${period.endDate} を締めます。締めた期には記帳できなくなります。よろしいですか?`)) {
            throw new Error('取り消しました。');
          }
          return post(`/periods/${period.id}/close`);
        },
        result: async (v, res) => {
          await refresh();
          state.periodId = res.nextPeriodId;
          savePeriodId(res.nextPeriodId);
          const picker = document.querySelector('#period-picker select');
          if (picker) picker.value = res.nextPeriodId;
          return h('div', {},
            alertBox('success', `締めました。当期純利益: ${yen(res.netIncome)} 円(マイナスは純損失)。次期を作成し、対象の期を次期に切り替えました。`),
            h('p', {}, h('a', { href: '#/closing/close', onclick: () => setTimeout(() => navigate('/closing/close?t=' + Date.now()), 0) }, '続けて次期の再振替へ')));
        },
      })] : alertBox('info', 'この期は締め済みです。'));

    const previous = progress.previousPeriod;
    const reversalCard = card('再振替(期首)',
      note('前期の決算整理仕訳のうち、経過勘定・当座借越・貯蔵品・その他有価証券の評価差額を、この期の期首日付で戻します。'),
      !previous ? alertBox('info', '前期がないため、再振替はありません。')
        : previous.status !== 'CLOSED' ? alertBox('info', '前期を締めてから実行します。')
          : progress.accrualsReversed ? alertBox('info', 'この期の再振替は実行済みです。')
            : form({
              role: 'APPROVER', submitLabel: '再振替を実行する',
              fields: [],
              onSubmit: () => post(`/periods/${period.id}/reversals`),
              result: (v, res) => postedJournals(res.journalIds, '再振替仕訳'),
            }));

    return [
      h('p', {}, `対象: ${period.startDate} 〜 ${period.endDate}(${LABELS.periodStatus[period.status]})`),
      reversalCard,
      closeCard,
    ];
  }));
}

function check(ok, text) {
  return h('li', { class: ok ? 'ok' : 'warn' }, h('span', { 'aria-hidden': 'true' }, ok ? '✓ ' : '! '), text);
}

