# MCP ツール一覧

AI エージェント向けに `/mcp`(Streamable HTTP、ステートレス)で公開しているツールの一覧(74 個)。
認証は REST と同じ HTTP Basic。ロールは上位が下位を含む(APPROVER ⊃ BOOKKEEPER ⊃ VIEWER)。

帳簿は会社ごと。**どのツールにも省略可能な引数 `companyId`** があり、指定するとその会社を操作する
(省略すれば既定の会社。会社の ID は `list_companies` で確認する)。

各ツールの説明文と引数は `src/main/java/com/example/accountingapp/presentation/mcp/` の `*Tools.java` にある。
サーバー全体の案内(決算の流れなど)は `application.yml` の `spring.ai.mcp.server.instructions`。

## 会社・会計期間・ユーザー(`master`)

| ツール | 内容 | ロール |
|---|---|---|
| `list_companies` | 会社の一覧 | VIEWER |
| `create_company` | 会社の作成(標準科目と最初の期も用意する) | APPROVER |
| `rename_company` | 会社名の変更 | APPROVER |
| `set_up_company` | 会社の初期化(標準科目の投入と最初の期の作成。会社ごとに最初に1回だけ) | APPROVER |
| `list_periods` | 全会計期間(新しい順) | VIEWER |
| `get_latest_period` | 最新の会計期間 | VIEWER |
| `get_period` | 会計期間を1つ | VIEWER |
| `get_period_progress` | 決算の進み具合(決算整理・再振替の実行済みか、現金過不足の残高など) | VIEWER |
| `who_am_i` | 接続しているユーザーとロール、操作している会社 | VIEWER |

## 勘定科目(`master`)

| ツール | 内容 | ロール |
|---|---|---|
| `list_accounts` | 科目の一覧 | VIEWER |
| `get_account` | 科目を1つ | VIEWER |
| `add_account` | 科目の追加 | BOOKKEEPER |
| `update_account` | 名前・タグの変更、無効化 | BOOKKEEPER |

## 仕訳(`journal`)

| ツール | 内容 | ロール |
|---|---|---|
| `post_journal` | 記帳(NORMAL / 手入力の ADJUSTING) | BOOKKEEPER |
| `correct_journal` | 赤黒訂正(黒を省略すると取消だけ) | BOOKKEEPER |
| `get_journal` | 仕訳を1つ(取り消し済みかどうかも返す) | VIEWER |
| `list_journals` | 仕訳帳(期の全仕訳) | VIEWER |

## 個別取引(`transaction`)

| ツール | 内容 | ロール |
|---|---|---|
| `write_off_bad_debt` | 貸倒れ | BOOKKEEPER |
| `recover_written_off_receivable` | 償却債権の回収 | BOOKKEEPER |
| `purchase_bond_with_accrued_interest` | 端数利息付きの債券購入 | BOOKKEEPER |
| `record_cash_count` | 現金の実際有高を入力して現金過不足を記帳 | BOOKKEEPER |
| `settle_cash_over_short` | 現金過不足を雑損・雑収入へ振替(締めの前に必要) | BOOKKEEPER |
| `declare_dividends` | 剰余金の配当 | BOOKKEEPER |
| `purchase_in_installments` | 固定資産の割賦購入 | BOOKKEEPER |
| `get_installment_schedule` | 割賦の支払予定表(記帳しない) | BOOKKEEPER |
| `pay_installment` | 割賦の第 n 回の支払 | BOOKKEEPER |
| `merge` | 吸収合併(のれん・負ののれん) | BOOKKEEPER |
| `discount_note` | 手形・電子記録債権の割引 | BOOKKEEPER |
| `use_provision` | 引当金の取崩し | BOOKKEEPER |
| `record_sale_with_expected_refund` | 返品見込みの販売(返金負債) | BOOKKEEPER |
| `recognize_contract_asset` | 契約資産の計上 | BOOKKEEPER |
| `bill_completed_contract` | 契約の完了と請求 | BOOKKEEPER |
| `recognize_service_revenue` | 役務収益と役務原価 | BOOKKEEPER |
| `sell_trading_securities` | 売買目的有価証券の売却(全部・一部) | BOOKKEEPER |
| `preview_bank_reconciliation` | 銀行勘定調整表のプレビュー(記帳しない) | BOOKKEEPER |
| `reconcile_bank` | 銀行勘定調整と修正仕訳の記帳 | BOOKKEEPER |

## 固定資産・債券(`asset`)

| ツール | 内容 | ロール |
|---|---|---|
| `register_fixed_asset` | 有形固定資産の登録(取得の仕訳は `post_journal` で記帳する) | BOOKKEEPER |
| `list_fixed_assets` | 固定資産台帳(累計償却額・帳簿価額) | VIEWER |
| `get_fixed_asset` | 固定資産を1つ | VIEWER |
| `sell_fixed_asset` | 売却 | BOOKKEEPER |
| `retire_fixed_asset` | 除却 | BOOKKEEPER |
| `abandon_fixed_asset` | 廃棄 | BOOKKEEPER |
| `compress_fixed_asset` | 圧縮記帳(直接減額方式) | BOOKKEEPER |
| `register_intangible_asset` | 無形固定資産の登録 | BOOKKEEPER |
| `list_intangible_assets` | 無形固定資産の台帳 | VIEWER |
| `register_held_to_maturity_bond` | 満期保有目的債券の登録 | BOOKKEEPER |
| `list_held_to_maturity_bonds` | 満期保有目的債券の一覧(償却原価) | VIEWER |

## 商品有高帳(`inventory`)

| ツール | 内容 | ロール |
|---|---|---|
| `get_inventory_ledger` | 商品有高帳 | VIEWER |
| `record_inventory_movement` | 受入・払出の記録(仕訳は記帳しない) | BOOKKEEPER |
| `record_inventory_sale` | 売上原価対立法の販売(売上と売上原価を同時に記帳) | BOOKKEEPER |
| `record_sales_return` | 売上の返品 | BOOKKEEPER |

## 決算(`closing`)

| ツール | 内容 | ロール |
|---|---|---|
| `preview_adjusting_entries` | 決算整理のプレビュー(保存しない) | APPROVER |
| `run_adjusting_entries` | 決算整理の確定(1つの期に1回だけ) | APPROVER |
| `close_period` | 締め(損益振替、次期の作成、開始仕訳。元に戻せない) | APPROVER |
| `reverse_accruals` | 再振替(次期の期首の日付で記帳) | APPROVER |

## 照会(`statement`)

| ツール | 内容 | ロール |
|---|---|---|
| `get_trial_balance` | 合計残高試算表(既定では損益振替の前の状態) | VIEWER |
| `get_balance_sheet` | 貸借対照表 | VIEWER |
| `get_income_statement` | 損益計算書 | VIEWER |
| `get_cash_flow_statement` | C/F(間接法。資金は「現金」勘定だけ) | VIEWER |
| `get_equity_changes` | 株主資本等変動計算書 | VIEWER |
| `get_worksheet` | 精算表 | VIEWER |
| `get_ledger` | 総勘定元帳 | VIEWER |

## 連結(`consolidation`)

操作している会社(`companyId`)を親会社とする。連結のルールはライブラリで未検証(結果に `unverified: true`)。

| ツール | 内容 | ロール |
|---|---|---|
| `list_subsidiaries` | 子会社と支配獲得時の事実 | VIEWER |
| `register_subsidiary` | 子会社の登録(支配獲得日・持分比率・取得原価・支配獲得日の資本・のれんの償却年数) | APPROVER |
| `remove_subsidiary` | 子会社の登録の取消(子会社の帳簿は残る) | APPROVER |
| `get_consolidation_materials` | 親会社の期の連結の資料 | VIEWER |
| `add_intercompany_balance` | 連結会社間の債権債務(貸倒引当金の修正を含む) | APPROVER |
| `add_intercompany_transaction` | 連結会社間の取引高 | APPROVER |
| `add_unrealized_profit` | 未実現利益(棚卸資産・土地) | APPROVER |
| `remove_consolidation_material` | 連結の資料の削除 | APPROVER |
| `get_consolidated_statements` | 連結修正仕訳・連結精算表・連結損益計算書・連結貸借対照表(保存しない) | VIEWER |

## ユーザー(`user`)

サインアップ(`POST /signup`)と自分のアカウントの削除(`DELETE /me`)は、画面から人が行う操作なのでツールにしていない。

| ツール | 内容 | ロール |
|---|---|---|
| `list_users` | ユーザーの一覧(ロールのないユーザーは承認待ち) | APPROVER |
| `change_user_role` | サインアップしたユーザーのロールを付ける・変える・外す | APPROVER |
| `delete_user` | サインアップしたユーザーの削除 | APPROVER |
