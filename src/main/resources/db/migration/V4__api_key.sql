-- MCP 用の API キー(CLAUDE.md §9)。キーそのものは保存せず、SHA-256 のハッシュだけを保存する。
-- キーを使ったときの権限は、持ち主のユーザーのその時点のロール。user_name は設定ファイルのユーザーも指すため外部キーにしない。

create table api_key (
    id            uuid primary key,
    user_name     varchar(50)  not null,
    name          varchar(100) not null,     -- 利用者が付ける名前(例: Claude Code)
    token_hash    char(64)     not null unique,
    prefix        varchar(16)  not null,     -- 一覧で見分けるための先頭の数文字
    created_at    timestamptz  not null,
    last_used_at  timestamptz
);
create index api_key_user_idx on api_key(user_name);
