// 画面の入口: サインイン、メニュー、会社と期の選択、ルーティング(#/パス?クエリ)。

import {
  h, clear, state, api, credentials, setCredentials, setUnauthorizedHandler, loadAccounts, loadPeriods, loadCompanies,
  savePeriodId, savedCompanyId, saveCompanyId, LABELS,
} from './core.js';
import { alertBox } from './components.js';
import * as home from './screens/home.js';
import * as master from './screens/master.js';
import * as journals from './screens/journals.js';
import * as transactions from './screens/transactions.js';
import * as assets from './screens/assets.js';
import * as closing from './screens/closing.js';
import * as statements from './screens/statements.js';
import * as consolidation from './screens/consolidation.js';
import * as users from './screens/users.js';

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
  { label: '連結', items: [
    { path: '/consolidation/subsidiaries', label: '子会社' },
    { path: '/consolidation/statements', label: '連結財務諸表' },
  ] },
  { label: 'マスタ', items: [
    { path: '/accounts', label: '勘定科目' },
    { path: '/periods', label: '会計期間' },
    { path: '/companies', label: '会社' },
    { path: '/users', label: 'ユーザー' },
    { path: '/setup', label: '会社の初期化' },
  ] },
];

const ROUTES = {
  '/': home.render,
  '/setup': master.setup,
  '/accounts': master.accounts,
  '/periods': master.periods,
  '/companies': master.companies,
  '/users': users.users,
  '/settings': users.settings,
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
  '/consolidation/subsidiaries': consolidation.subsidiaries,
  '/consolidation/statements': consolidation.statements,
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
    h('a', { class: 'btn-secondary btn-small', href: '#/settings' }, '設定'),
    h('button', { class: 'btn-secondary btn-small', onclick: signOut }, 'サインアウト'));
}

export function renderPeriodPicker() {
  clear(periodPicker);
  if (!state.me) return;
  if (state.companies && state.companies.length > 1) {
    const company = h('select', {
      class: 'company-select', 'aria-label': '操作する会社',
      onchange: () => switchCompany(company.value),
    }, state.companies.map((c) => h('option', { value: c.id }, c.name)));
    company.value = state.companyId;
    periodPicker.append(h('span', { class: 'muted' }, '会社'), company);
  }
  if (!state.periods || !state.periods.length) return;
  const select = h('select', {
    'aria-label': '対象の会計期間',
    onchange: () => { state.periodId = select.value; savePeriodId(select.value); route(); },
  }, state.periods.map((p) => h('option', { value: p.id },
    `${p.startDate} 〜 ${p.endDate}(${LABELS.periodStatus[p.status]})`)));
  select.value = state.periodId;
  periodPicker.append(h('span', { class: 'muted' }, '会計期間'), select);
}

async function route() {
  if (!state.me) return renderSignIn();
  if (!state.me.roles.length) return renderPending();
  const { path, params } = parseHash();
  renderMenu(path);
  const render = ROUTES[path];
  clear(content);
  if (!render) {
    content.append(alertBox('error', 'ページが見つかりません。'));
    return;
  }
  try {
    const node = await render(params, { refresh: refreshShared, navigate, switchCompany, signOut });
    if (node) content.append(node);
  } catch (error) {
    content.append(alertBox('error', error.message));
  }
  window.scrollTo(0, 0);
}

/** 期や科目・会社が変わる操作(初期化・締め・科目の追加・会社の作成)のあとに、共有のキャッシュを読み直す。 */
async function refreshShared() {
  const companies = await loadCompanies(true);
  if (!companies.some((c) => c.id === state.companyId)) {
    const saved = savedCompanyId();
    state.companyId = companies.some((c) => c.id === saved) ? saved : companies[0].id;
  }
  await Promise.all([loadAccounts(true), loadPeriods(true)]);
  renderPeriodPicker();
}

/** 操作する会社を切り替える。科目表・期は会社ごとなので読み直す。 */
export async function switchCompany(id) {
  state.companyId = id;
  saveCompanyId(id);
  state.periodId = null;
  await refreshShared();
  route();
}

function renderSignIn(message) {
  showSignedOutLayout();
  const error = h('div');
  if (message) error.append(alertBox('error', message));
  const user = h('input', { name: 'user', autocomplete: 'username', required: true });
  const pass = h('input', { name: 'pass', type: 'password', autocomplete: 'current-password', required: true });
  clear(content).append(h('section', { class: 'login' },
    h('h1', {}, 'サインイン'),
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
      h('div', { class: 'form-actions' }, h('button', { type: 'submit', class: 'btn-primary' }, 'サインイン')),
      error),
    h('p', {}, 'アカウントをお持ちでない方は ', h('a', { href: '#', onclick: (e) => { e.preventDefault(); renderSignUp(); } }, 'サインアップ')),
    h('p', { class: 'muted small' }, '開発用の初期ユーザー: viewer / bookkeeper / approver(パスワードはユーザー名と同じ)')));
  user.focus();
}

function showSignedOutLayout() {
  document.body.classList.add('logged-out');
  clear(sidebar);
  clear(periodPicker);
  clear(userArea);
}

function renderSignUp() {
  showSignedOutLayout();
  const error = h('div');
  const user = h('input', { name: 'user', autocomplete: 'username', required: true, minlength: 3, maxlength: 50,
    pattern: '[A-Za-z0-9._\\-]{3,50}' });
  const pass = h('input', { name: 'pass', type: 'password', autocomplete: 'new-password', required: true, minlength: 8 });
  const confirmPass = h('input', { name: 'confirm', type: 'password', autocomplete: 'new-password', required: true, minlength: 8 });
  clear(content).append(h('section', { class: 'login' },
    h('h1', {}, 'サインアップ'),
    h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      clear(error);
      if (pass.value !== confirmPass.value) {
        error.append(alertBox('error', 'パスワードが一致しません。'));
        return;
      }
      try {
        await api('POST', '/signup', { name: user.value, password: pass.value }, null);
        await start({ user: user.value, pass: pass.value });
      } catch (err) {
        error.append(alertBox('error', err.status === 409 ? 'このユーザー名はすでに使われています。' : err.message));
      }
    } },
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'ユーザー名'), user,
        h('small', { class: 'help' }, '3〜50文字の半角英数字と . _ -')),
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'パスワード'), pass,
        h('small', { class: 'help' }, '8文字以上')),
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'パスワード(確認)'), confirmPass),
      h('div', { class: 'form-actions' }, h('button', { type: 'submit', class: 'btn-primary' }, 'サインアップ')),
      error),
    h('p', { class: 'muted small' }, 'サインアップした直後は帳簿を見られません。承認者がロール(閲覧者・記帳者・承認者)を付けると使えるようになります。'),
    h('p', {}, 'アカウントをお持ちの方は ', h('a', { href: '#', onclick: (e) => { e.preventDefault(); renderSignIn(); } }, 'サインイン'))));
  user.focus();
}

/** ロールがまだ付いていないユーザーの画面。帳簿は見られない。 */
function renderPending() {
  showSignedOutLayout();
  userArea.append(h('span', { class: 'user-name' }, state.me.name),
    h('button', { class: 'btn-secondary btn-small', onclick: signOut }, 'サインアウト'));
  clear(content).append(h('section', { class: 'login' },
    h('h1', {}, '承認待ち'),
    alertBox('info', `「${state.me.name}」にはまだロールがありません。承認者がロールを付けるまでお待ちください。`),
    h('p', { class: 'muted small' }, 'ロールが付いたら、もう一度サインインしてください。'),
    users.deleteAccountCard(signOut)));
}

async function start(auth) {
  const me = await api('GET', '/me', undefined, auth);
  setCredentials(auth.user, auth.pass);
  state.me = me;
  if (!me.roles.length) {
    renderPending();
    return;
  }
  document.body.classList.remove('logged-out');
  await refreshShared();
  renderUser();
  route();
}

export function signOut(message) {
  setCredentials(null);
  state.me = null;
  state.companies = null;
  state.companyId = null;
  state.accounts = null;
  state.periods = null;
  state.periodId = null;
  renderSignIn(typeof message === 'string' ? message : undefined);
}

setUnauthorizedHandler(() => {
  if (state.me) {
    setCredentials(null);
    state.me = null;
    renderSignIn('サインインの有効期限が切れたか、認証に失敗しました。もう一度サインインしてください。');
  }
});

window.addEventListener('hashchange', route);

const saved = credentials();
if (saved) {
  start(saved).catch(() => renderSignIn());
} else {
  renderSignIn();
}
