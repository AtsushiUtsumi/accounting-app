// 設定(自分のアカウント、MCP 用の API キー、アカウントの削除)と、ユーザーの管理(承認者がロールを付ける・削除する)。

import { h, get, post, patch, del, state, LABELS, clear } from '../core.js';
import { page, card, note, alertBox, asyncArea, table, roleRequired, form } from '../components.js';

const SOURCE_LABELS = { CONFIGURED: '設定ファイル', SIGNED_UP: 'サインアップ' };

/** 自分のアカウントを削除するカード。ロールのないユーザーの画面からも使う。 */
export function deleteAccountCard(signOut) {
  const message = h('div');
  return card('アカウントの削除',
    note('アカウントを削除すると、このユーザー名ではサインインできなくなります。元に戻せません。',
      '記帳した仕訳の記帳者の記録(ユーザー名)は残ります。'),
    h('div', { class: 'form-actions' }, h('button', {
      type: 'button', class: 'btn-danger',
      onclick: async () => {
        if (!confirm(`アカウント「${state.me.name}」を削除しますか?元に戻せません。`)) return;
        clear(message);
        try {
          await del('/me');
          signOut('アカウントを削除しました。');
        } catch (error) {
          message.append(alertBox('error', error.status === 409
            ? `削除できません: ${error.message}(設定ファイルのユーザーと、最後の承認者は削除できません)` : error.message));
        }
      },
    }, 'アカウントを削除する')),
    message);
}

export function settings(params, { signOut }) {
  return page('設定',
    card('アカウント', h('dl', { class: 'kv' },
      h('dt', {}, 'ユーザー名'), h('dd', {}, state.me.name),
      h('dt', {}, 'ロール'), h('dd', {}, state.me.roles.map((r) => LABELS.role[r]).join('・')))),
    apiKeysCard(),
    deleteAccountCard(signOut));
}

// ---- MCP 用の API キー ----

function apiKeysCard() {
  const listArea = h('div');
  const issuedArea = h('div');

  async function reload() {
    const keys = await get('/me/api-keys');
    clear(listArea).append(table([
      { label: '名前', key: 'name' },
      { label: 'キー', render: (k) => h('code', {}, `${k.prefix}…`) },
      { label: '発行日時', render: (k) => dateTime(k.createdAt) },
      { label: '最後に使った日時', render: (k) => k.lastUsedAt ? dateTime(k.lastUsedAt) : h('span', { class: 'muted' }, '未使用') },
      { label: '', render: (k) => h('button', {
        type: 'button', class: 'btn-secondary btn-small',
        onclick: async () => {
          if (!confirm(`API キー「${k.name}」を失効させますか?このキーを使っている AI エージェントは接続できなくなります。`)) return;
          await del(`/me/api-keys/${k.id}`);
          clear(issuedArea);
          await reload();
        },
      }, '失効させる') },
    ], keys, { empty: 'まだ API キーはありません。' }));
  }

  function showIssued(issued) {
    const command = `claude mcp add --transport http accounting ${location.origin}/mcp --header "Authorization: Bearer ${issued.token}"`;
    clear(issuedArea).append(
      alertBox('success', `API キー「${issued.key.name}」を発行しました。キーはこの画面でしか表示されません。いまコピーして保管してください。`),
      copyField('API キー', issued.token),
      copyField('Claude Code に登録するコマンド', command),
      note('キーを使ったときの権限は、あなたのその時点のロールです。キーが漏れたら、すぐに失効させてください。'));
  }

  return card('MCP 用の API キー',
    note('AI エージェント(MCP)から ', h('code', {}, '/mcp'), ' に接続するときに、パスワードの代わりに使うキーです。',
      'リクエストヘッダー ', h('code', {}, 'Authorization: Bearer <キー>'), ' で送ります。REST API と画面には使えません。'),
    asyncArea(async () => { await reload(); return listArea; }),
    issuedArea,
    form({
      submitLabel: '発行する',
      fields: [{ name: 'name', label: 'キーの名前(どこで使うか。例: 自分の PC の Claude Code)', required: true, maxlength: 100 }],
      onSubmit: (v) => post('/me/api-keys', v),
      result: async (v, issued) => { showIssued(issued); await reload(); return null; },
    }));
}

function copyField(label, value) {
  const input = h('input', { type: 'text', readonly: true, value, class: 'copy-field', onfocus: (e) => e.target.select() });
  const status = h('span', { class: 'muted small' });
  return h('div', { class: 'field field-wide' }, h('span', { class: 'field-label' }, label),
    h('div', { class: 'copy-row' }, input, h('button', {
      type: 'button', class: 'btn-secondary',
      onclick: async () => {
        try {
          await navigator.clipboard.writeText(value);
          status.textContent = 'コピーしました';
        } catch {
          input.select();
          status.textContent = '選択しました。Ctrl+C でコピーしてください';
        }
      },
    }, 'コピー'), status));
}

function dateTime(iso) {
  return new Date(iso).toLocaleString('ja-JP', { dateStyle: 'short', timeStyle: 'short' });
}

export function users(params, { navigate }) {
  const reload = () => navigate(`/users?t=${Date.now()}`);
  if (!state.me.roles.includes('APPROVER')) return page('ユーザー', roleRequired('APPROVER'));
  return page('ユーザー', asyncArea(async () => {
    const list = await get('/users');
    const pending = list.filter((u) => !u.role).length;
    return [
      pending ? alertBox('info', `ロールの付いていないユーザーが ${pending} 人います。ロールを付けると帳簿を使えるようになります。`) : null,
      card(null, table([
        { label: 'ユーザー名', key: 'name' },
        { label: 'ロール', render: (u) => u.source === 'CONFIGURED'
          ? LABELS.role[u.role]
          : roleSelect(u, reload) },
        { label: '種類', render: (u) => SOURCE_LABELS[u.source] },
        { label: '', render: (u) => u.source === 'CONFIGURED' ? null : h('button', {
          type: 'button', class: 'btn-secondary btn-small',
          onclick: async () => {
            if (!confirm(`ユーザー「${u.name}」を削除しますか?元に戻せません。`)) return;
            try {
              await del(`/users/${encodeURIComponent(u.name)}`);
              reload();
            } catch (error) {
              alert(error.message);
            }
          },
        }, '削除') },
      ], list), note('設定ファイルのユーザー(accounting.security.users)は、画面からは変更・削除できません。',
        '承認者がいなくなるとロールを付けられなくなるため、最後の承認者は削除もロールの変更もできません。')),
    ];
  }));
}

function roleSelect(user, reload) {
  const select = h('select', {
    'aria-label': `${user.name} のロール`,
    onchange: async () => {
      try {
        await patch(`/users/${encodeURIComponent(user.name)}`, { role: select.value || null });
        reload();
      } catch (error) {
        alert(error.message);
        select.value = user.role || '';
      }
    },
  }, h('option', { value: '' }, '― なし(承認待ち)―'),
  Object.entries(LABELS.role).map(([value, label]) => h('option', { value }, label)));
  select.value = user.role || '';
  return select;
}
