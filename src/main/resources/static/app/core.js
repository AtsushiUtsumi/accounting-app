// 画面の共通処理: API 呼び出し、ログイン状態、DOM の組み立て、表示用の書式。
// 会計の計算はしない(金額・仕訳はすべてサーバー(ライブラリ)が決める)。

const AUTH_KEY = 'accounting.auth';
const PERIOD_KEY = 'accounting.periodId';

export const state = {
  me: null,          // { name, roles }
  accounts: null,    // 科目一覧(キャッシュ)
  periods: null,     // 期の一覧(キャッシュ)
  periodId: null,    // 照会・記帳の対象として選んでいる期
};

// ---- ストレージ(使えない環境でも動くようにする) ----

function storageGet(storage, key) {
  try { return storage.getItem(key); } catch { return null; }
}
function storageSet(storage, key, value) {
  try { value == null ? storage.removeItem(key) : storage.setItem(key, value); } catch { /* 保存できなくても動く */ }
}

export function credentials() {
  const raw = storageGet(sessionStorage, AUTH_KEY);
  return raw ? JSON.parse(raw) : null;
}
export function setCredentials(user, pass) {
  storageSet(sessionStorage, AUTH_KEY, user == null ? null : JSON.stringify({ user, pass }));
}
export function savedPeriodId() {
  return storageGet(localStorage, PERIOD_KEY);
}
export function savePeriodId(id) {
  storageSet(localStorage, PERIOD_KEY, id);
}

// ---- API ----

export class ApiError extends Error {
  constructor(status, detail, code) {
    super(detail || `HTTP ${status}`);
    this.status = status;
    this.code = code;
  }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(handler) { onUnauthorized = handler; }

function basicAuth({ user, pass }) {
  const bytes = new TextEncoder().encode(`${user}:${pass}`);
  return 'Basic ' + btoa(String.fromCharCode(...bytes));
}

export async function api(method, path, body, auth = credentials()) {
  const headers = { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' };
  if (auth) headers['Authorization'] = basicAuth(auth);
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401) {
    onUnauthorized();
    throw new ApiError(401, 'ログインが必要です');
  }
  const text = await response.text();
  const data = text ? safeJson(text) : null;
  if (!response.ok) {
    const detail = data && typeof data === 'object' ? (data.detail || data.title) : text;
    throw new ApiError(response.status, describeError(response.status, detail), data && data.code);
  }
  return data;
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return text; }
}

const STATUS_LABELS = {
  400: '入力内容に誤りがあります',
  403: 'この操作を行う権限がありません',
  404: '見つかりません',
  409: 'ほかの操作と競合したか、いまは実行できない操作です',
  422: '会計のルールに反しています',
};

function describeError(status, detail) {
  const label = STATUS_LABELS[status] || `エラーが発生しました (HTTP ${status})`;
  return detail ? `${label}: ${detail}` : label;
}

export const get = (path) => api('GET', path);
export const post = (path, body = {}) => api('POST', path, body);
export const patch = (path, body) => api('PATCH', path, body);

// ---- ロール ----

export function hasRole(role) {
  return !!state.me && state.me.roles.includes(role);
}

// ---- キャッシュ ----

export async function loadAccounts(force = false) {
  if (!state.accounts || force) state.accounts = await get('/accounts');
  return state.accounts;
}

export async function loadPeriods(force = false) {
  if (!state.periods || force) state.periods = await get('/periods');
  if (state.periods.length && !state.periods.some((p) => p.id === state.periodId)) {
    const saved = savedPeriodId();
    state.periodId = state.periods.some((p) => p.id === saved) ? saved : state.periods[0].id;
  }
  if (!state.periods.length) state.periodId = null;
  return state.periods;
}

export function currentPeriod() {
  return state.periods ? state.periods.find((p) => p.id === state.periodId) || null : null;
}

export function accountName(code) {
  const account = state.accounts && state.accounts.find((a) => a.code === code);
  return account ? account.name : '';
}

// ---- DOM ----

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value == null || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
    else if (key === 'value') el.value = value;
    else if (value === true) el.setAttribute(key, '');
    else el.setAttribute(key, value);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children) {
    if (child == null || child === false) continue;
    if (Array.isArray(child)) append(el, child);
    else el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(el) {
  el.replaceChildren();
  return el;
}

// ---- 書式 ----

const yenFormat = new Intl.NumberFormat('ja-JP');

export function yen(amount) {
  if (amount == null || amount === '') return '';
  return yenFormat.format(amount);
}

export function today() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export const LABELS = {
  side: { DEBIT: '借方', CREDIT: '貸方' },
  journalType: {
    NORMAL: '通常', ADJUSTING: '決算整理', CLOSING: '損益振替', OPENING: '開始', REVERSING: '再振替', REVERSAL: '取消',
  },
  accountType: { ASSET: '資産', LIABILITY: '負債', EQUITY: '純資産', REVENUE: '収益', EXPENSE: '費用' },
  accountCategory: {
    CURRENT_ASSET: '流動資産', FIXED_ASSET: '固定資産', CURRENT_LIABILITY: '流動負債', FIXED_LIABILITY: '固定負債',
    CAPITAL: '資本', RETAINED_EARNINGS: '利益剰余金', VALUATION_AND_TRANSLATION_ADJUSTMENTS: '評価・換算差額等',
    SALES: '売上高', COST_OF_SALES: '売上原価', SG_AND_A: '販売費及び一般管理費',
    NON_OPERATING_INCOME: '営業外収益', NON_OPERATING_EXPENSE: '営業外費用',
    EXTRAORDINARY_INCOME: '特別利益', EXTRAORDINARY_LOSS: '特別損失', INCOME_TAX_EXPENSE: '法人税等',
    PROFIT_AND_LOSS: '損益', SUSPENSE: '仮勘定',
  },
  provisionType: {
    BONUSES: '賞与引当金', REPAIRS: '修繕引当金', PRODUCT_WARRANTIES: '商品保証引当金',
    RETIREMENT_BENEFITS: '退職給付引当金',
  },
  equityReason: {
    ISSUANCE_OF_SHARES: '新株の発行', DIVIDENDS: '剰余金の配当', NET_INCOME: '当期純利益',
    TRANSFER_WITHIN_EQUITY: '株主資本の計数の変動', VALUATION_CHANGE: '株主資本以外の項目の当期変動額', OTHER: 'その他',
  },
  periodStatus: { OPEN: '記帳中', CLOSED: '締め済み' },
  role: { VIEWER: '閲覧者', BOOKKEEPER: '記帳者', APPROVER: '承認者' },
};

export function options(labels) {
  return Object.entries(labels).map(([value, label]) => ({ value, label }));
}
