-- 複数の会社(マルチテナント)と連結会計(CLAUDE.md §5・§6.9)。
-- 既存のデータは「既定の会社」のものにする。リクエストで会社を指定しなければ既定の会社を使う。

create table company (
    id          uuid primary key,
    name        varchar(100) not null,
    created_at  timestamptz  not null default now()
);
insert into company (id, name) values ('00000000-0000-0000-0000-000000000001', '既定の会社');

-- ---- 科目: 主キーを (会社, コード) にする ----
alter table account_tag drop constraint account_tag_account_code_fkey;
alter table journal_line drop constraint journal_line_account_code_fkey;
alter table fixed_asset
    drop constraint fixed_asset_asset_account_code_fkey,
    drop constraint fixed_asset_accumulated_depreciation_code_fkey,
    drop constraint fixed_asset_depreciation_expense_code_fkey;
alter table intangible_asset
    drop constraint intangible_asset_asset_account_code_fkey,
    drop constraint intangible_asset_amortization_expense_code_fkey;
alter table held_to_maturity_bond drop constraint held_to_maturity_bond_bond_account_code_fkey;

-- 定数の既定値で列を足すのは表の書き換えを伴わない(追記のみのトリガーも発火しない)。足したあと既定値を外す
alter table account add column company_id uuid not null default '00000000-0000-0000-0000-000000000001'
    references company(id);
alter table account alter column company_id drop default;
alter table account drop constraint account_pkey, add primary key (company_id, code);

alter table account_tag add column company_id uuid not null default '00000000-0000-0000-0000-000000000001';
alter table account_tag alter column company_id drop default;
alter table account_tag drop constraint account_tag_pkey,
    add primary key (company_id, account_code, tag),
    add foreign key (company_id, account_code) references account(company_id, code);

-- ---- 会計期間: 期首日の一意性は会社ごと ----
alter table accounting_period add column company_id uuid not null default '00000000-0000-0000-0000-000000000001'
    references company(id);
alter table accounting_period alter column company_id drop default;
alter table accounting_period drop constraint accounting_period_start_date_key,
    add unique (company_id, start_date);

-- ---- 仕訳: 科目の外部キーを会社つきにする ----
alter table journal add column company_id uuid not null default '00000000-0000-0000-0000-000000000001'
    references company(id);
alter table journal alter column company_id drop default;

alter table journal_line add column company_id uuid not null default '00000000-0000-0000-0000-000000000001';
alter table journal_line alter column company_id drop default;
alter table journal_line add foreign key (company_id, account_code) references account(company_id, code);
drop index journal_line_account_idx;
create index journal_line_account_idx on journal_line(company_id, account_code);

-- ---- 資産・商品有高帳 ----
alter table fixed_asset add column company_id uuid not null default '00000000-0000-0000-0000-000000000001'
    references company(id);
alter table fixed_asset alter column company_id drop default;
alter table fixed_asset
    add foreign key (company_id, asset_account_code) references account(company_id, code),
    add foreign key (company_id, accumulated_depreciation_code) references account(company_id, code),
    add foreign key (company_id, depreciation_expense_code) references account(company_id, code);

alter table intangible_asset add column company_id uuid not null default '00000000-0000-0000-0000-000000000001'
    references company(id);
alter table intangible_asset alter column company_id drop default;
alter table intangible_asset
    add foreign key (company_id, asset_account_code) references account(company_id, code),
    add foreign key (company_id, amortization_expense_code) references account(company_id, code);

alter table held_to_maturity_bond add column company_id uuid not null default '00000000-0000-0000-0000-000000000001'
    references company(id);
alter table held_to_maturity_bond alter column company_id drop default;
alter table held_to_maturity_bond
    add foreign key (company_id, bond_account_code) references account(company_id, code);

alter table inventory_movement add column company_id uuid not null default '00000000-0000-0000-0000-000000000001'
    references company(id);
alter table inventory_movement alter column company_id drop default;
drop index inventory_movement_item_idx;
create index inventory_movement_item_idx on inventory_movement(company_id, item_code, seq);

-- ---- 連結 ----
-- 連結修正仕訳・連結財務諸表は保存しない(毎期、各社の仕訳とここにある資料から作り直す)。
-- 科目コードは親会社の科目表のもの(各社の科目はコードで同じ科目とみなす)。

-- 子会社(支配獲得時の事実)。孫会社は扱わない
create table subsidiary (
    company_id                   uuid primary key references company(id),
    parent_company_id            uuid          not null references company(id),
    acquisition_date             date          not null,
    parent_ownership             numeric(7,6)  not null,   -- 親会社の持分比率(0 より大きく 1 以下)
    investment_cost              bigint        not null,   -- 子会社株式の取得原価
    goodwill_amortization_years  int           not null,
    check (company_id <> parent_company_id)
);
create index subsidiary_parent_idx on subsidiary(parent_company_id);

-- 支配獲得日の子会社の資本(資本金・資本剰余金・利益剰余金の各科目)
create table subsidiary_equity (
    company_id    uuid        not null references subsidiary(company_id) on delete cascade,
    account_code  varchar(16) not null,
    amount        bigint      not null,
    primary key (company_id, account_code)
);

-- 連結会社間の債権債務・取引高・未実現利益。親会社の会計期間ごとの資料
create table intercompany_balance (
    id                      uuid primary key,
    period_id               uuid        not null references accounting_period(id),   -- 親会社の期
    creditor_company_id     uuid        not null references company(id),
    receivable_code         varchar(16) not null,
    payable_code            varchar(16) not null,
    amount                  bigint      not null,
    allowance               bigint      not null,
    allowance_at_beginning  bigint      not null
);
create index intercompany_balance_period_idx on intercompany_balance(period_id);

create table intercompany_transaction (
    id            uuid primary key,
    period_id     uuid        not null references accounting_period(id),
    revenue_code  varchar(16) not null,
    expense_code  varchar(16) not null,
    amount        bigint      not null
);
create index intercompany_transaction_period_idx on intercompany_transaction(period_id);

create table unrealized_profit (
    id                      uuid primary key,
    period_id               uuid        not null references accounting_period(id),
    kind                    varchar(16) not null,   -- INVENTORY / LAND
    seller_company_id       uuid        not null references company(id),
    inventory_account_code  varchar(16),            -- INVENTORY のとき
    ending                  bigint,                 -- INVENTORY のとき: 期末の棚卸資産に含まれる未実現利益
    beginning               bigint,                 -- INVENTORY のとき: 期首の棚卸資産に含まれる未実現利益
    amount                  bigint,                 -- LAND のとき: 売却益
    sale_date               date                    -- LAND のとき
);
create index unrealized_profit_period_idx on unrealized_profit(period_id);
