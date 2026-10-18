// #1 ホーム: 対象の期と、決算の進み具合。

import { h, get, state, yen, hasRole, LABELS } from '../core.js';
import { page, card, asyncArea, alertBox, note } from '../components.js';

export function render() {
  if (!state.periodId) {
    return page('ホーム',
      alertBox('info', 'まだ会計期間がありません。最初に会社の初期化を行います。'),
      hasRole('APPROVER')
        ? h('p', {}, h('a', { class: 'btn-primary', href: '#/setup' }, '会社の初期化へ'))
        : note('会社の初期化は承認者が行います。'));
  }
  return page('ホーム', asyncArea(async () => {
    const progress = await get(`/periods/${state.periodId}/progress`);
    const period = progress.period;
    const open = period.status === 'OPEN';
    const isLatest = state.periods[0].id === period.id;

    const steps = [
      progress.previousPeriod ? step(progress.accrualsReversed, '再振替(期首)',
        progress.accrualsReversed ? '前期の経過勘定などを戻しました。' : '前期の決算整理のうち、経過勘定などを期首に戻します。',
        '#/closing/close') : null,
      step(progress.journalCount > 0, '日々の記帳', `この期の仕訳: ${progress.journalCount} 件`, '#/journals/new'),
      step(progress.adjustmentsRun || !open, '経過勘定・貯蔵品の手入力',
        '決算整理の前に、ADJUSTING の仕訳として手入力します。', '#/journals/new?type=ADJUSTING'),
      step(progress.cashOverShortBalance === 0, '現金過不足の振替',
        progress.cashOverShortBalance === 0 ? '現金過不足の残高はありません。'
          : `現金過不足の残高が ${yen(progress.cashOverShortBalance)} 円あります。締めの前に振り替えてください。`, '#/tx/cash'),
      step(progress.adjustmentsRun || !open, '決算整理', progress.adjustmentsRun ? '決算整理仕訳を記帳済みです。'
        : '棚卸・減価償却・引当金・法人税等などの整理仕訳を作ります。', '#/closing/adjustments'),
      step(!open, '締め', open ? '損益振替と開始仕訳を記帳し、次期を作ります。' : 'この期は締め済みです。', '#/closing/close'),
    ].filter(Boolean);

    return [
      card('対象の会計期間',
        h('dl', { class: 'kv' },
          h('dt', {}, '期間'), h('dd', {}, `${period.startDate} 〜 ${period.endDate}`),
          h('dt', {}, '状態'), h('dd', {}, h('span', { class: open ? 'badge badge-ok' : 'badge badge-muted' }, LABELS.periodStatus[period.status])),
          h('dt', {}, '前期'), h('dd', {}, progress.previousPeriod
            ? `${progress.previousPeriod.startDate} 〜 ${progress.previousPeriod.endDate}(${LABELS.periodStatus[progress.previousPeriod.status]})` : 'なし')),
        isLatest ? null : note('最新の期ではありません。記帳は最新の(締めていない)期に行います。右上で期を切り替えられます。')),
      card('決算までの流れ', h('ol', { class: 'steps' }, steps)),
      card('よく使う画面', h('div', { class: 'quick-links' },
        link('#/journals/new', '仕訳入力'), link('#/journals', '仕訳帳'),
        link('#/statements/trial-balance', '試算表'), link('#/statements/financial', '財務諸表'),
        link('#/statements/ledger', '総勘定元帳'), link('#/fixed-assets', '固定資産台帳'))),
    ];
  }));
}

function step(done, title, description, href) {
  return h('li', { class: done ? 'step done' : 'step' },
    h('span', { class: 'step-mark', 'aria-hidden': 'true' }, done ? '✓' : ''),
    h('div', {}, h('a', { href }, title), h('div', { class: 'muted small' }, description)));
}

function link(href, label) {
  return h('a', { class: 'quick-link', href }, label);
}

