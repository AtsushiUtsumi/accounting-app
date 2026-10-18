// 画面の入口: ログイン、メニュー、期の選択、ルーティング(#/パス?クエリ)。

import {
  h, clear, state, api, credentials, setCredentials, setUnauthorizedHandler, loadAccounts, loadPeriods,
  savePeriodId, LABELS,
} from './core.js';
import { alertBox } from './components.js';
import * as home from './screens/home.js';
import * as master from './screens/master.js';
import * as journals from './screens/journals.js';
import * as transactions from './screens/transactions.js';
import * as assets from './screens/assets.js';
import * as closing from './screens/closing.js';
import * as statements from './screens/statements.js';

const MENU = [
  { label: null, items: [{ path: '/', label: 'ホーム' }] },
  { label: '記帳', items: [
    { path: '/journals/new', label: '仕訳入力' },
    { path: '/journals', label: '仕訳帳' },
  ] },
  { label: '個別取引', items: [
    { path: '/tx/receivables', label: '債権(貸倒れ)' },
    { path: '/tx/notes', label: '手形・電子記録債権' },
    { path: '/tx/cash', label: '現金過不足' },
    { path: '/tx/bank', label: '銀行勘定調整表' },
    { path: '/tx/securities', label: '有価証券の売買' },
    { path: '/tx/installments', label: '割賦購入' },
    { path: '/tx/equity', label: '配当・合併' },
    { path: '/tx/provisions', label: '引当金の取崩し' },
    { path: '/tx/revenue', label: '収益認識' },
  ] },
  { label: '商品・資産', items: [
    { path: '/inventory', label: '商品有高帳' },
    { path: '/fixed-assets', label: '固定資産台帳' },
    { path: '/other-assets', label: '無形固定資産・債券' },
  ] },
  { label: '決算', items: [
    { path: '/closing/adjustments', label: '決算整理' },
    { path: '/closing/close', label: '締め・再振替' },
  ] },
  { label: '照会', items: [
    { path: '/statements/trial-balance', label: '試算表・精算表' },
    { path: '/statements/financial', label: '財務諸表' },
    { path: '/statements/ledger', label: '総勘定元帳' },
  ] },
  { label: 'マスタ', items: [
    { path: '/accounts', label: '勘定科目' },
    { path: '/periods', label: '会計期間' },
    { path: '/setup', label: '会社の初期化' },
  ] },
];

const ROUTES = {
  '/': home.render,
  '/setup': master.setup,
  '/accounts': master.accounts,
  '/periods': master.periods,
  '/journals/new': journals.newJournal,
  '/journals': journals.journalBook,
  '/journals/detail': journals.detail,
  '/journals/correct': journals.correct,
  '/tx/receivables': transactions.receivables,
  '/tx/notes': transactions.notes,
  '/tx/cash': transactions.cash,
  '/tx/bank': transactions.bank,
  '/tx/securities': transactions.securities,
  '/tx/installments': transactions.installments,
  '/tx/equity': transactions.equity,
  '/tx/provisions': transactions.provisions,
  '/tx/revenue': transactions.revenue,
  '/inventory': assets.inventory,
  '/fixed-assets': assets.fixedAssets,
  '/other-assets': assets.otherAssets,
  '/closing/adjustments': closing.adjustments,
  '/closing/close': closing.close,
  '/statements/trial-balance': statements.trialBalance,
  '/statements/financial': statements.financial,
  '/statements/ledger': statements.ledger,
};

const content = document.getElementById('content');
const sidebar = document.getElementById('sidebar');
const userArea = document.getElementById('user');
const periodPicker = document.getElementById('period-picker');

document.getElementById('menu-toggle').addEventListener('click', () => document.body.classList.toggle('menu-open'));

function parseHash() {
  const hash = location.hash.replace(/^#/, '') || '/';
  const [path, query = ''] = hash.split('?');
  return { path, params: Object.fromEntries(new URLSearchParams(query)) };
}

export function navigate(path) {
  location.hash = '#' + path;
}

function renderMenu(activePath) {
  clear(sidebar).append(...MENU.map((group) => h('div', { class: 'menu-group' },
    group.label ? h('div', { class: 'menu-label' }, group.label) : null,
    group.items.map((item) => h('a', {
      href: '#' + item.path,
      class: item.path === activePath || (item.path !== '/' && activePath.startsWith(item.path + '/')) ? 'active' : null,
      onclick: () => document.body.classList.remove('menu-open'),
    }, item.label)))));
}

function renderUser() {
  clear(userArea);
  if (!state.me) return;
  userArea.append(
    h('span', { class: 'user-name' }, state.me.name),
    h('span', { class: 'badge badge-muted' }, state.me.roles.map((r) => LABELS.role[r]).join('・')),
    h('button', { class: 'btn-secondary btn-small', onclick: logout }, 'ログアウト'));
}

export function renderPeriodPicker() {
  clear(periodPicker);
  if (!state.me || !state.periods || !state.periods.length) return;
  const select = h('select', {
    'aria-label': '対象の会計期間',
    onchange: () => { state.periodId = select.value; savePeriodId(select.value); route(); },
  }, state.periods.map((p) => h('option', { value: p.id },
    `${p.startDate} 〜 ${p.endDate}(${LABELS.periodStatus[p.status]})`)));
  select.value = state.periodId;
  periodPicker.append(h('span', { class: 'muted' }, '会計期間'), select);
}

async function route() {
  if (!state.me) return renderLogin();
  const { path, params } = parseHash();
  renderMenu(path);
  const render = ROUTES[path];
  clear(content);
  if (!render) {
    content.append(alertBox('error', 'ページが見つかりません。'));
    return;
  }
  try {
    const node = await render(params, { refresh: refreshShared, navigate });
    if (node) content.append(node);
  } catch (error) {
    content.append(alertBox('error', error.message));
  }
  window.scrollTo(0, 0);
}

/** 期や科目が変わる操作(初期化・締め・科目の追加)のあとに、共有のキャッシュを読み直す。 */
async function refreshShared() {
  await Promise.all([loadAccounts(true), loadPeriods(true)]);
  renderPeriodPicker();
}

function renderLogin(message) {
  document.body.classList.add('logged-out');
  clear(sidebar);
  clear(periodPicker);
  clear(userArea);
  const error = h('div');
  if (message) error.append(alertBox('error', message));
  const user = h('input', { name: 'user', autocomplete: 'username', required: true });
  const pass = h('input', { name: 'pass', type: 'password', autocomplete: 'current-password', required: true });
  clear(content).append(h('section', { class: 'login' },
    h('h1', {}, 'ログイン'),
    h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      try {
        await start({ user: user.value, pass: pass.value });
      } catch (err) {
        clear(error).append(alertBox('error', err.status === 401 ? 'ユーザー名またはパスワードが違います。' : err.message));
      }
    } },
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'ユーザー名'), user),
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'パスワード'), pass),
      h('div', { class: 'form-actions' }, h('button', { type: 'submit', class: 'btn-primary' }, 'ログイン')),
      error),
    h('p', { class: 'muted small' }, '開発用の初期ユーザー: viewer / bookkeeper / approver(パスワードはユーザー名と同じ)')));
  user.focus();
}

async function start(auth) {
  const me = await api('GET', '/me', undefined, auth);
  setCredentials(auth.user, auth.pass);
  state.me = me;
  document.body.classList.remove('logged-out');
  await refreshShared();
  renderUser();
  route();
}

function logout() {
  setCredentials(null);
  state.me = null;
  state.accounts = null;
  state.periods = null;
  renderLogin();
}

setUnauthorizedHandler(() => {
  if (state.me) {
    setCredentials(null);
    state.me = null;
    renderLogin('ログインの有効期限が切れたか、認証に失敗しました。もう一度ログインしてください。');
  }
});

window.addEventListener('hashchange', route);

const saved = credentials();
if (saved) {
  start(saved).catch(() => renderLogin());
} else {
  renderLogin();
}
