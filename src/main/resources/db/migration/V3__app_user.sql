-- サインアップしたユーザー(CLAUDE.md §9)。設定ファイルのユーザー(accounting.security.users)はここに入れない。
-- role が null のユーザーは、承認者がロールを付けるまで帳簿を見られない。

create table app_user (
    name        varchar(50)  primary key,
    password    varchar(200) not null,     -- エンコード方式の接頭辞付きのハッシュ({bcrypt}...)
    role        varchar(16)  check (role in ('VIEWER', 'BOOKKEEPER', 'APPROVER')),
    created_at  timestamptz  not null default now()
);
