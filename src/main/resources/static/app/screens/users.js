// ユーザー: 自分のアカウント(削除)、ユーザーの管理(承認者がロールを付ける・削除する)。

import { h, get, patch, del, state, LABELS, clear } from '../core.js';
import { page, card, note, alertBox, asyncArea, table, roleRequired } from '../components.js';

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

export function account(params, { signOut }) {
  return page('アカウント',
    card(null, h('dl', { class: 'kv' },
      h('dt', {}, 'ユーザー名'), h('dd', {}, state.me.name),
      h('dt', {}, 'ロール'), h('dd', {}, state.me.roles.map((r) => LABELS.role[r]).join('・')))),
    deleteAccountCard(signOut));
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
