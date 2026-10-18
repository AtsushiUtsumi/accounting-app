-- accounting-app のスキーマ(CLAUDE.md §5)。
-- B/S・P/L・試算表のテーブルは作らない(導出できる値は保存しない)。累計償却額・帳簿価額も保存しない。

create table account (
    code        varchar(16)  primary key,
    name        varchar(100) not null,
    type        varchar(16)  not null,     -- AccountType
    category    varchar(48)  not null,     -- AccountCategory
    standard    boolean      not null,
    active      boolean      not null
);

create table account_tag (
    account_code varchar(16) not null references account(code),
    tag          varchar(50) not null,
    primary key (account_code, tag)
);

create table accounting_period (
    id          uuid primary key,
    start_date  date       not null unique,
    end_date    date       not null,
    status      varchar(8) not null,       -- OPEN / CLOSED
    version     bigint     not null
);

create table journal (
    id                  uuid primary key,
    seq                 bigint generated always as identity,   -- 保存した順(照会の並び順)
    period_id           uuid         not null references accounting_period(id),   -- PostedJournal.periodId()
    period_version      bigint       not null,                                   -- PostedJournal.periodVersion()
    date                date         not null,
    description         varchar(200) not null,
    type                varchar(16)  not null,     -- JournalType
    reversal_of         uuid references journal(id),
    created_at          timestamptz  not null,     -- 監査用(ドメインには戻さない)
    created_by          varchar(100) not null,     -- 監査用
    created_by_use_case varchar(64)  not null      -- 監査用。決算整理が作った仕訳と手入力の仕訳を区別する
);
create index journal_period_idx on journal(period_id, seq);
-- 同じ仕訳を2回取り消せない(アプリの JournalQuery.isReversed によるチェックを、同時実行でも守る)
create unique index journal_reversal_of_uq on journal(reversal_of) where reversal_of is not null;

create table journal_line (
    journal_id   uuid        not null references journal(id),
    line_no      int         not null,
    account_code varchar(16) not null references account(code),
    side         varchar(8)  not null,     -- DEBIT / CREDIT
    amount       bigint      not null check (amount > 0),   -- 円(Money は円固定)
    primary key (journal_id, line_no)
);
create index journal_line_account_idx on journal_line(account_code);

-- 仕訳は追記のみ。アプリの DB ユーザーから UPDATE / DELETE 権限を外すのに加えて、トリガーでも拒否する。
create function reject_journal_modification() returns trigger as $$
begin
    raise exception 'journals are append-only: % on % is not allowed', tg_op, tg_table_name;
end;
$$ language plpgsql;

create trigger journal_append_only
    before update or delete on journal
    for each row execute function reject_journal_modification();
create trigger journal_line_append_only
    before update or delete on journal_line
    for each row execute function reject_journal_modification();

create table fixed_asset (
    id                               uuid primary key,
    name                             varchar(100) not null,
    asset_account_code               varchar(16)  not null references account(code),
    accumulated_depreciation_code    varchar(16)  not null references account(code),
    depreciation_expense_code        varchar(16)  not null references account(code),
    acquisition_cost                 bigint       not null,
    acquisition_date                 date         not null,
    useful_life_years                int          not null,
    residual_value                   bigint       not null,
    policy_kind                      varchar(32)  not null,   -- STRAIGHT_LINE / DECLINING_BALANCE / UNITS_OF_PRODUCTION
    declining_rate                   numeric(6,4),            -- 定率法の償却率
    total_estimated_usage            bigint,                  -- 生産高比例法の総利用可能量
    rounding                         varchar(16)  not null    -- DOWN / HALF_UP
);

-- 売却・除却・廃棄した記録(ライブラリの FixedAsset は状態を持たないため、アプリ側で持つ)
create table fixed_asset_disposal (
    fixed_asset_id uuid primary key references fixed_asset(id),
    disposed_on    date not null
);

create table intangible_asset (
    id                           uuid primary key,
    name                         varchar(100) not null,
    asset_account_code           varchar(16)  not null references account(code),
    amortization_expense_code    varchar(16)  not null references account(code),
    acquisition_cost             bigint       not null,
    acquisition_date             date         not null,
    useful_life_years            int          not null
);

-- ライブラリの HeldToMaturityBond は ID を持たないため、アプリ側の ID と組で保存する
create table held_to_maturity_bond (
    id                 uuid primary key,
    name               varchar(100) not null,
    bond_account_code  varchar(16)  not null references account(code),
    face_value         bigint       not null,
    acquisition_cost   bigint       not null,
    acquisition_date   date         not null,
    maturity_date      date         not null
);

-- 商品有高帳の受入・払出の明細(仕訳とは別に、追記のみ)
create table inventory_movement (
    seq          bigint generated always as identity primary key,
    item_code    varchar(32)  not null,
    kind         varchar(8)   not null,    -- RECEIPT / ISSUE
    date         date         not null,
    description  varchar(200),
    quantity     int          not null,
    unit_cost    bigint                    -- 受入のときだけ
);
create index inventory_movement_item_idx on inventory_movement(item_code, seq);
