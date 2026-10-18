# accounting-app 設計書(Spring Boot)

`accounting-domain` ライブラリを使い、仕訳の記帳から決算・財務諸表の照会までを提供する Spring Boot アプリケーションの設計。

- 位置づけ: ドメイン層(会計ルール)は `accounting-domain` が持つ。このアプリは **アプリケーション層・インフラ層・プレゼンテーション層** だけを担う。
- 原則: **会計ルールをアプリ側に書かない**。借方・貸方・金額の計算は、すべてライブラリのドメインサービスに任せる。アプリの役割は「取得する → ドメインに計算させる → 保存する」と、トランザクション・入出力・認可。
- 用語は `GLOSSARY.md` に合わせる。ライブラリ側で未検証の会計ルールは `UNVERIFIED_RULES.md` にある。

---

## 1. 技術スタック

| 項目 | 選定 | 理由 |
|---|---|---|
| 言語 | Java 25(ドメインと同じ toolchain) | |
| フレームワーク | Spring Boot 4.x(着手時に最新の安定版を確認する) | |
| ビルド | Gradle(Kotlin DSL) | ドメインと揃える |
| 永続化 | **Spring JDBC(`JdbcClient`)** | ドメインモデルはイミュータブルな record で、アノテーションも付けられない。JPA エンティティを別に作って詰め替えるより、SQL ↔ record を手で変換するほうが単純で、学習目的にも見通しが良い |
| マイグレーション | Flyway | |
| DB | PostgreSQL(開発・テストは Testcontainers) | |
| API | Spring Web MVC(REST / JSON) | |
| テスト | JUnit / AssertJ(ドメインと同じバージョン)/ Spring Boot Test / Testcontainers | |

JPA を採らない理由をもう少し: `Journal` は `JournalLine` が `Account` を直接持ち、生成時に「借方合計 = 貸方合計」を検証する。JPA の遅延ロードや dirty checking、引数なしコンストラクタは、この「不正な状態を作らせない」設計と相性が悪い。

## 2. プロジェクト構成

ドメインのリポジトリはそのまま残し、アプリは別の Gradle プロジェクトにする。

```
accounting-domain/            ← 既存
accounting-app/              ← 新規
 ├─ settings.gradle.kts      includeBuild("../accounting-domain")   ※または publishToMavenLocal
 └─ build.gradle.kts         implementation("com.example:accounting-domain")
```

- ドメインが Spring に依存しないことを、**ビルドの構成そのもので保証する**(CLAUDE.md の「Spring 向けモジュールは別モジュールとして検討」に対応)。
- マルチモジュール(`accounting-domain/domain`, `accounting-domain/app`)にしてもよい。その場合も domain → app の一方向の依存を守る。

## 3. レイヤーとパッケージ

```
com.example.accountingapp
 ├─ presentation          REST と MCP で共有する DTO(JournalResponse など)、Today
 │   ├─ controller        REST コントローラ、リクエスト/レスポンス DTO、例外ハンドラ
 │   │   ├─ journal / transaction / closing / statement / master / asset / inventory
 │   └─ mcp               AI エージェント向けの MCP ツール(/mcp)。controller と同じ並びで、全エンドポイントに対応するツールを置く
 │       ├─ journal / transaction / closing / statement / master / asset / inventory
 ├─ application           ユースケース(@Service, @Transactional)とコマンド/結果の型
 │   ├─ port              アプリ都合で追加する検索・保存のインターフェース(§4.2)
 │   ├─ journal           PostJournalUseCase, CorrectJournalUseCase
 │   ├─ transaction       個別取引のユースケース(§6.3)
 │   ├─ asset             FixedAssetUseCase(登録・売却・除却・廃棄・圧縮記帳)
 │   ├─ inventory         InventoryUseCase(商品有高帳・売上原価対立法)
 │   ├─ closing           RunAdjustingEntriesUseCase, ClosePeriodUseCase, ReverseAccrualsUseCase
 │   ├─ statement         StatementQueryService(読み取り専用)
 │   └─ master            AccountManagementUseCase, CompanySetupUseCase
 ├─ infrastructure
 │   ├─ persistence       Jdbc*Repository(ライブラリと §4.2 のインターフェースの実装)、行 ↔ ドメインの変換
 │   └─ config            DomainServiceConfig(ドメインサービスの Bean 登録)、ClockConfig
 └─ AccountingApplication
```

依存の向き: `presentation → application → domain`、`infrastructure → application(port)・domain`。application は infrastructure を知らない。

### ドメインサービスの Bean 登録

ライブラリのクラスにアノテーションは付けられないため、`@Configuration` で登録する。ドメインサービスはどれも引数なしのコンストラクタで作れ、副作用もないので、すべてシングルトンでよい。

```java
@Configuration
class DomainServiceConfig {
    @Bean ClosingService closingService() { return new ClosingService(); }
    @Bean AdjustingEntryService adjustingEntryService() { return new AdjustingEntryService(); }
    @Bean ReversalService reversalService() { return new ReversalService(); }
    @Bean FinancialStatementFactory financialStatementFactory() { return new FinancialStatementFactory(); }
    @Bean CashFlowClassifier cashFlowClassifier() { return new StandardCashFlowClassifier(); }  // 会社ごとに差し替え可
    // 個別取引: BadDebtService, BondService, CashOverShortService, DividendService,
    //   InstallmentPurchaseService, MergerService, NotesReceivableService, PerpetualInventoryService,
    //   ProvisionService, RevenueRecognitionService, TradingSecuritiesService も同様
}

@Configuration
class ClockConfig {
    @Bean Clock clock() { return Clock.system(ZoneId.of("Asia/Tokyo")); }  // テストでは Clock.fixed に差し替える
}
```

## 4. リポジトリ

### 4.1 ライブラリのインターフェース(アプリが実装する)

扱う集約と同じパッケージにある。シグネチャはライブラリのとおり:

```java
// model.journal
interface JournalRepository {
    void save(PostedJournal journal);
    /**
     * 全件保存するか、1件も保存しないか。途中まで保存された状態を残してはならない。
     * 保存時の会計期間の version が PostedJournal.periodVersion() と違う仕訳があれば、1件も保存せずに失敗する。
     */
    void saveAll(List<PostedJournal> journals);
    Optional<PostedJournal> findById(JournalId id);
    List<PostedJournal> findByPeriod(AccountingPeriod period);   // その期に記帳された仕訳(締めた期も含む)
}

// model.period
interface AccountingPeriodRepository {
    Optional<AccountingPeriod> findById(AccountingPeriodId id);
    void save(AccountingPeriod period);   // version による楽観ロックの検証は実装側
}

// model.account
interface AccountRepository {
    Optional<Account> findByCode(AccountCode code);
    List<Account> findAll();
    void save(Account account);
    boolean existsByCode(AccountCode code);
}

// model.asset
interface FixedAssetRepository {
    Optional<FixedAsset> findById(FixedAssetId id);
    List<FixedAsset> findAll();
    void save(FixedAsset asset);   // 圧縮記帳で新しいインスタンスになるため、上書き保存を許す
}
```

### 4.2 アプリ側で追加するインターフェース(`application.port`)

ライブラリにない検索や、ライブラリがリポジトリを持たない型の保存は、アプリが自分で定義する。

| インターフェース | メソッド(案) | 用途 |
|---|---|---|
| `PeriodLookup` | `findContaining(LocalDate)`、`findPrevious(AccountingPeriod)`、`findLatest()` | 記帳先の期を日付で探す、C/F・再振替で前期を探す |
| `JournalQuery` | `existsByPeriodAndType(period, JournalType)`、`isReversed(JournalId)`、`isAccountUsed(AccountCode)` | 決算整理の二重実行防止、二重取消の防止、使用済み科目の判定 |
| `IntangibleAssetRepository` | `findById`、`findAll`、`save` | 無形固定資産(ライブラリにリポジトリがない) |
| `HeldToMaturityBondRepository` | `findById`、`findAll`、`save` | 満期保有目的債券(ライブラリの record に ID がないため、アプリ側の ID と組で保存する) |
| `InventoryMovementRepository` | `append`、`findByItem(itemCode)` | 商品有高帳の受入・払出の明細(仕訳とは別に保存する) |

## 5. 永続化モデル

```sql
create table account (
    code        varchar(16) primary key,
    name        varchar(100) not null,
    type        varchar(16)  not null,     -- AccountType
    category    varchar(48)  not null,     -- AccountCategory
    standard    boolean      not null,
    active      boolean      not null
);
create table account_tag (
    account_code varchar(16) references account(code),
    tag          varchar(50),
    primary key (account_code, tag)
);

create table accounting_period (
    id          uuid primary key,
    start_date  date not null,
    end_date    date not null,
    status      varchar(8) not null,       -- OPEN / CLOSED
    version     bigint not null
);

create table journal (
    id             uuid primary key,
    period_id      uuid not null references accounting_period(id),   -- PostedJournal.periodId()
    period_version bigint not null,                                  -- PostedJournal.periodVersion()
    date           date not null,
    description    varchar(200) not null,
    type           varchar(16) not null,     -- JournalType
    reversal_of    uuid references journal(id),
    created_at     timestamptz not null,     -- 監査用(ドメインには戻さない)
    created_by     varchar(100) not null     -- 監査用
);
create index journal_period_idx on journal(period_id);

create table journal_line (
    journal_id   uuid references journal(id),
    line_no      int,
    account_code varchar(16) not null references account(code),
    side         varchar(8)  not null,     -- DEBIT / CREDIT
    amount       bigint      not null,     -- 円(Money は円固定)
    primary key (journal_id, line_no)
);

create table fixed_asset (
    id                               uuid primary key,
    name                             varchar(100) not null,
    asset_account_code               varchar(16) not null references account(code),
    accumulated_depreciation_code    varchar(16) not null references account(code),
    depreciation_expense_code        varchar(16) not null references account(code),
    acquisition_cost                 bigint not null,
    acquisition_date                 date   not null,
    useful_life_years                int    not null,
    residual_value                   bigint not null,
    policy_kind                      varchar(32) not null,   -- STRAIGHT_LINE / DECLINING_BALANCE / UNITS_OF_PRODUCTION
    declining_rate                   numeric(6,4),           -- 定率法の償却率
    total_estimated_usage            bigint,                 -- 生産高比例法の総利用可能量
    rounding                         varchar(16) not null    -- DOWN / HALF_UP
    -- 累計償却額・帳簿価額は保存しない(仕訳から導出)
);
-- intangible_asset, held_to_maturity_bond, inventory_movement も同じ考え方で作る(帳簿価額は保存しない)
```

方針:
- **journal / journal_line は INSERT だけ**。アプリの DB ユーザーから `UPDATE` / `DELETE` 権限を外すと、追記のみの原則を DB 側でも守れる。
- **B/S・P/L・試算表のテーブルは作らない**(導出できる値は保存しない)。
- **どの期の仕訳かは `period_id` で決まる**(日付ではない)。`findByPeriod` は `where period_id = ?` で検索する。
- 復元するとき: `journal_line.account_code` から `Account` を取ってきて `JournalLine` を組み立て、`Journal.of(...)` / `Journal.reversalOf(...)` を通し、`PostedJournal.restore(journal, periodId, periodVersion)` で包む。こうすると、DB から読んだデータもドメインの不変条件(貸借一致)で検証される。`restore` はリポジトリの実装だけが使う。
- 仕訳をまとめて読むときは、科目を1回で全件読み込み、`Map<AccountCode, Account>` にしてから組み立てる(N+1 問題を避けるため)。
- **`saveAll` の version 検証**: 同じトランザクションで `select version from accounting_period where id = ? for share` を読み、各仕訳の `periodVersion` と違えば例外を投げてロールバックする。
- **`AccountingPeriodRepository.save` の楽観ロック**: 新規は INSERT。既存は `update ... set status = ?, version = :new where id = ? and version = :new - 1` とし、更新件数が 0 なら楽観ロックの失敗にする(`close()` は version を1つ進める)。
- **償却方法の保存**: `DepreciationPolicy` の実装は償却率などの値を外に出していない。そのため、登録時に受け取った値(`policy_kind`・償却率・総利用可能量・端数処理)をアプリが保存し、復元時に `StraightLineDepreciationPolicy.of(...)` などで作り直す。`RoundingPolicy` もラムダなので、アプリ側の enum(`DOWN` / `HALF_UP`)で保存する。→ ライブラリに値を取り出すメソッドを足すかは §12 の未決事項。

## 6. ユースケース

共通の形:

```
@Transactional
1. 入力 DTO を検証する(形式チェックのみ。会計ルールはチェックしない)
2. リポジトリから期・科目・仕訳を取得する
3. ドメインのファクトリ/サービスを呼んで、結果(仕訳など)を受け取る
4. PostedJournal.post / postAll で記帳し、リポジトリで保存する
5. 結果(ID など)を返す
```

ドメインサービスに渡す `journalsSoFar` は、`PostedJournal.journalsOf(journalRepository.findByPeriod(period))` で作る。

### 6.1 仕訳を記帳する(PostJournalUseCase)

入力: 日付、摘要、種類(`NORMAL` または `ADJUSTING`)、行のリスト(科目コード、借方/貸方、金額)

1. `periodLookup.findContaining(date)` で期を探す。見つからなければ 404。
2. 科目コードを `Account` に変換する。存在しない、または無効化された科目なら例外。
3. `Journal.of(JournalId.newId(), date, description, type, lines)` を呼ぶ。貸借の不一致はここで例外になる。
4. `PostedJournal.post(period, journal)` で記帳する。CLOSED の期・期間外の日付はここで例外になる。
5. `journalRepository.save(posted)`。

- 種類は `NORMAL` と `ADJUSTING` だけ受け付ける。`ADJUSTING` を手入力で受けるのは、経過勘定・貯蔵品のように、ライブラリが自動で作らない決算整理があるため(§6.4 の手順7)。
- **同時実行**: 記帳と締めが同時に走っても、締めた期に仕訳は入らない。`PostedJournal` は記帳した時点の期の `version` を持ち、`saveAll` の実装が保存時の期の `version` と比べて弾くため(締めると version が1つ進む)。

### 6.2 仕訳を訂正する — 赤黒(CorrectJournalUseCase)

入力: 元の仕訳 ID、訂正日、正しい仕訳の内容(省略すれば取消のみ)

1. 元の仕訳を取得する。
2. `reversalService.reverse(JournalId.newId(), date, original.journal())` で取消仕訳(赤)を作る。
3. 正しい仕訳(黒)を 6.1 と同じ手順で作る。
4. 赤と黒を、訂正日を含む OPEN の期に `PostedJournal.postAll` で記帳し、`journalRepository.saveAll` で保存する。元の仕訳が締めた期のものでも、訂正は当期に記帳される。

同じ仕訳を2回取り消せないようにするかどうかは**未決**(ライブラリ側のルールにするか、`JournalQuery.isReversed` でアプリ側のチェックにするか)。

### 6.3 個別取引(transaction パッケージ)

どれも同じ形になる:

```
期を取得 → journalsSoFar を作る → ドメインサービス(..., journalsSoFar, date) → post → save
```

金額などの事実(割引料、売却代金、実際の手許現金など)はユーザーが入力する。アプリは計算しない。

| ユースケース | 呼び出すもの |
|---|---|
| 貸倒れ・償却債権の回収 | `BadDebtService.writeOff` / `recoverWrittenOff` |
| 端数利息付きの債券購入 | `BondService.accruedInterest` → `purchaseWithAccruedInterest` |
| 現金過不足の記録 | `CashOverShortService.recordDiscrepancy` |
| 剰余金の配当 | `DividendService.declareDividends` / `declareDividendsFromCapitalSurplus`(資本金などの残高は `Ledger` から求めて渡す) |
| 固定資産の割賦購入・支払 | `InstallmentPurchaseService.purchase` / `payInstallment` |
| 吸収合併 | `MergerService.merge` |
| 手形・電子記録債権の割引 | `NotesReceivableService.discount` / `discountElectronicReceivable` |
| 引当金の取崩し | `ProvisionService.use` |
| 収益認識(返金負債・契約資産・役務収益) | `RevenueRecognitionService` の各メソッド |
| 売買目的有価証券の売却 | `TradingSecuritiesService.sell` / `sellPortion` |
| 銀行勘定調整 | `BankReconciliation.of(...).correctingEntries(date)`(帳簿側の調整だけが仕訳になる) |

### 6.4 決算整理(RunAdjustingEntriesUseCase)

入力(すべて外部から与える事実): 期首・期末の商品棚卸高、実地棚卸の結果(`InventoryCount`)、有価証券の期末時価、貸倒引当金・その他の引当金の設定額、確定した法人税等の額 など

呼び出す順序は `AdjustingEntryService` の Javadoc で決まっている:

```
1. reverseAccruals(期首。§6.6 で実施済み)
2. reclassifyBankOverdraft
3. recognizeCostOfSales(三分法のみ) → recognizeInventoryLosses
4. depreciate(有形固定資産ごと) → amortize(無形固定資産ごと)
5. TradingSecuritiesService.valuateAtPeriodEnd → amortizeHeldToMaturityBond → valuateAvailableForSaleSecurities
6. adjustAllowanceForDoubtfulAccounts → adjustProvision / replaceProvision / accrueRetirementBenefits
7. 経過勘定・貯蔵品(利用者が §6.1 で ADJUSTING の仕訳として記帳しておく)
8. accrueIncomeTax → settleConsumptionTax
```

```java
List<Journal> soFar = new ArrayList<>(PostedJournal.journalsOf(journalRepository.findByPeriod(period)));
List<Journal> generated = new ArrayList<>();
// 各ステップの結果を soFar と generated の両方に追加してから、次のステップへ進む
// (法人税等は税引前当期純利益に依存するので、それまでの整理がすべて soFar に入っている必要がある)
...
journalRepository.saveAll(PostedJournal.postAll(period, generated));   // 最後にまとめて記帳・保存する
```

- **途中で保存しない**。どこかで失敗したら全部やり直す。
- 手順7は手入力なので、このユースケースを実行する**前に**記帳しておく運用にする(手順8が手順7の結果に依存するため)。画面で「経過勘定の入力は済みましたか」を確認させる。
- 二重実行を防ぐ: 当期に、このユースケースが作った `ADJUSTING` 仕訳が1件でもあれば拒否する(やり直すときは、整理仕訳を赤黒で取り消してから)。手入力の `ADJUSTING` と区別するため、どのユースケースが作った仕訳かを `journal` の監査列に残す。
- 「プレビュー」用のエンドポイントでは、保存せずに `generated` を返す(記帳前の `Journal` は計算に自由に使える)。ユーザーが確認してから確定させる流れにする。

### 6.5 決算を締める(ClosePeriodUseCase)

1. 期を取得する。
2. `posted = journalRepository.findByPeriod(period)`(開始仕訳・再振替仕訳・通常仕訳・決算整理仕訳がすべて入る)。
3. `ClosingResult result = closingService.close(period, posted)`。損益振替は当期の期末日付で、開始仕訳は次期の期首日付で、記帳済みで返る。
4. **1トランザクションで、この順に**保存する(`ClosingResult` の Javadoc の順序):
   - `journalRepository.saveAll(result.closingEntries())` — 締める前の version で記帳されているので先に保存する
   - `periodRepository.save(result.closedPeriod())` — version による楽観ロック
   - `periodRepository.save(result.nextPeriod())`
   - `journalRepository.saveAll(result.openingEntries())`
5. 結果として、次期の ID と当期純利益を返す。

すでに CLOSED の期を締めようとすると、ドメインが例外を投げる。同時に2回実行された場合は、楽観ロックの失敗で片方が弾かれる。締めの前に、現金過不足の残高ゼロ化(`CashOverShortService.settleAtPeriodEnd`)が済んでいることを確認する。

### 6.6 再振替(ReverseAccrualsUseCase)

次期に対して実行する。前期の `ADJUSTING` 仕訳を取得し、`reverseAccruals(prevAdjusting, nextPeriod.startDate())` の結果を次期に記帳して `saveAll` する。再振替仕訳は**期首の日付でしか記帳できない**(`PostedJournal` のルール)。対象は経過勘定・当座借越・貯蔵品・その他有価証券の評価差額。

締めのあとに同じトランザクションで続けて実行するか、別の操作にするかは**未決**。どちらでも日付は次期の期首になる。

### 6.7 照会(StatementQueryService, `@Transactional(readOnly = true)`)

| 照会 | 必要な仕訳 | 呼び出すもの |
|---|---|---|
| 試算表 | 当期 | `FinancialStatementFactory.trialBalance` |
| B/S・P/L | 当期 | `balanceSheet` / `incomeStatement` |
| 精算表 | 当期(決算整理前と `ADJUSTING` に分ける) | `Worksheet.of(beforeAdjustment, adjusting)` |
| C/F | 前期 + 当期(前期末の B/S が必要) | `CashFlowStatement.of(prior, current, cashFlowClassifier)` |
| 株主資本等変動計算書 | 当期 | `StatementOfChangesInEquity.of` |
| 総勘定元帳 | 当期 | `Ledger.of(account, journals)` |
| 商品有高帳 | 入出庫の明細 | `FifoInventoryValuationMethod` / `MovingAverageInventoryValuationMethod` の `valuate` |
| 固定資産台帳 | 当期まで | `FixedAsset.accumulatedDepreciation` / `bookValue` |

- 締め前の期を照会する場合は、`CLOSING` 仕訳を除いた「締め前の状態」を見せたい場面がある(試算表など)。どちらを見せるかを画面ごとに決める。
- **C/F の資金は「現金」勘定だけ**(ライブラリの既知の制限)。預金を使う会社では正しくならないので、画面に注記するか、ライブラリ側の対応を待つ。

### 6.8 マスタ・資産

- **科目**: 追加、名前の変更(`rename`)、タグの変更(`withTags`)、無効化(`deactivate`)。標準科目の保護はドメインが担う。使用済みかどうかは `JournalQuery.isAccountUsed` で判定し、削除 API は作らない。
- **会社の初期化**: `StandardAccounts.ALL` を投入し、`AccountingPeriod.startingAt(期首日)` で最初の期を作る(会計期間は1年固定)。
- **固定資産**: 登録、償却方法の選択(定額法・定率法・生産高比例法)、売却(`disposalEntry`)、除却(`retirementEntry`)、廃棄(`abandonmentEntry`)、圧縮記帳(`compress`)。
  - 帳簿価額を勘定の残高から導出するため、**同じ種類の資産が複数あるなら資産ごとに科目が必要**(ライブラリの既知の制限)。登録時に科目を自動で作るか、利用者に選ばせるかは**未決**。
  - 圧縮記帳は、返された新しい `FixedAsset` と仕訳を**同じトランザクションで**保存する(古いインスタンスを使い続けると取得価額が合わない)。
- **商品有高帳**: 受入・払出を `InventoryMovementRepository` に追記する。売上原価対立法の会社では、払出の結果(`InventoryLedgerEntry`)を `PerpetualInventoryService.recordSale` に渡して、売上と売上原価の仕訳を同時に作る。

## 7. REST API(案)

| メソッド | パス | ユースケース |
|---|---|---|
| POST | `/journals` | 記帳(通常・手入力の決算整理) |
| POST | `/journals/{id}/corrections` | 赤黒訂正 |
| GET | `/periods/{id}/journals` | 仕訳帳 |
| POST | `/transactions/notes-discounts` 他 | 個別取引(§6.3 の表ごとに1つ) |
| POST | `/bank-reconciliations` | 銀行勘定調整表の作成と修正仕訳 |
| POST | `/periods/{id}/adjustments/preview` | 決算整理のプレビュー |
| POST | `/periods/{id}/adjustments` | 決算整理の確定 |
| POST | `/periods/{id}/close` | 締め |
| POST | `/periods/{id}/reversals` | 再振替 |
| GET | `/periods/{id}/trial-balance` | 試算表 |
| GET | `/periods/{id}/balance-sheet` | B/S |
| GET | `/periods/{id}/income-statement` | P/L |
| GET | `/periods/{id}/cash-flow` | C/F |
| GET | `/periods/{id}/equity-changes` | 株主資本等変動計算書 |
| GET | `/periods/{id}/worksheet` | 精算表 |
| GET | `/accounts/{code}/ledger?periodId=` | 元帳 |
| GET/POST/PATCH | `/accounts` | 科目マスタ(DELETE はない) |
| GET/POST | `/fixed-assets`、`POST /fixed-assets/{id}/disposal` 他 | 固定資産 |
| GET/POST | `/inventory/{itemCode}/movements` | 商品有高帳 |

- 金額は JSON の整数(円)でやり取りする。`Money` を JSON にどう変換するかは presentation 層で決める(ライブラリはシリアライズに対応しない)。
- レスポンス DTO は、ドメインの record から presentation 層で詰め替える。ドメインの型をそのまま JSON にしない。

## 8. 例外の扱い

| 発生元 | HTTP | 備考 |
|---|---|---|
| `AccountingException`(貸借の不一致、CLOSED の期への記帳、など) | 422 Unprocessable Entity | メッセージを ProblemDetail の `detail` に入れる |
| リソースが見つからない(アプリ側) | 404 | |
| 楽観ロックの失敗・`saveAll` の version 不一致 | 409 Conflict | |
| 入力の形式エラー(Bean Validation) | 400 | |

`@RestControllerAdvice` で `ProblemDetail`(RFC 9457)に変換する。ライブラリの例外は今は `AccountingException` 1種類で、メッセージは英語の開発者向け文言。画面向けの日本語メッセージにどう変換するかは**未決**(ライブラリの例外が細分化されたら、種類ごとにコードを振り分ける)。

## 9. 横断的な関心事

- **日付**: 「今日」が必要なのは、入力が省略されたときの初期値だけ。`Clock` Bean から取り、ドメインには `LocalDate` で渡す。
- **認可**: 記帳者、承認者(決算整理・締め・再振替)、閲覧者のロールを分ける(Spring Security)。
- **監査**: `journal.created_at / created_by`(と作成したユースケース)に記録する。ドメインの `Journal` には含めない。
- **マルチテナント**: 必要になったら全テーブルに `company_id` を足す。最初は単一の会社で作る。
- **性能**: 照会のたびに「期の全仕訳をメモリに読み込んで計算する」。件数が問題になってから、読み取り用のキャッシュを検討する(保存した集計値は**キャッシュ**であって正本ではない、という扱いにする)。
- **未検証の会計ルール**: `UNVERIFIED_RULES.md` に載っているルールに依存する機能は、ライブラリ側で検証が済むまで画面に「検証中」と分かるようにするか、公開を遅らせる。

## 10. テスト方針

| 対象 | 方法 |
|---|---|
| ユースケース | インメモリのリポジトリ実装 + `Clock.fixed` で単体テスト。期待値は、ドメインと同じく**問題集の解答例**を使う(期待値を自分で作らない) |
| リポジトリ | Testcontainers の PostgreSQL で、保存 → 復元の往復テスト(`PostedJournal` が等しくなること。固定資産は償却方法も含めて往復すること) |
| `saveAll` | 途中の行で制約違反を起こさせ、1件も残らないことを確認する。期の version が進んだ後の保存が失敗することも確認する |
| 締め | ドメインの自己検証(収益・費用の残高がゼロ、資産 = 負債 + 純資産、期末残高 = 次期の期首残高、当期純利益 = 繰越利益剰余金の増加額)を、**DB を通したあとでも**確認する |
| 決算整理 | 手順の順序どおりに呼ばれることと、プレビューと確定の結果が一致することを確認する |
| API | `@WebMvcTest` で DTO の変換と例外の HTTP 変換を確認 |

## 11. 実装の順序(案)

1. プロジェクトを作り、ドメインを依存に追加し、空の状態でビルドが通るようにする
2. ライブラリと §4.2 のインターフェースに対するインメモリ実装
3. 会社の初期化 → 記帳 → 試算表の照会(ユースケースと API)
4. JDBC 実装 + Flyway + Testcontainers
5. 赤黒訂正
6. 決算整理 → 締め → 再振替
7. 残りの照会(B/S・P/L、C/F、精算表、株主資本等変動計算書、元帳)
8. 個別取引、固定資産、商品有高帳
9. 認可・監査

## 12. 未決事項

1. (決定済み)リポジトリのインターフェースは、ライブラリの集約と同じパッケージに置く
2. 同じ仕訳を2回取り消すことを防ぐのは、ドメインかアプリか
3. 再振替を締めのあとに自動実行するか、別の操作にするか
4. 締め前の照会で、損益振替前の状態を見せるかどうか
5. (決定済み)商品有高帳の入出庫明細は、仕訳とは別に保存する(ライブラリの `InventoryMovement` が仕訳と独立した入力のため)
6. マルチテナント対応の要否
7. `DepreciationPolicy` の実装から償却率などを取り出すメソッドをライブラリに足すか(足さなければ、アプリが登録時の値を別に保存し続ける)
8. 資産ごとの科目(減価償却累計額など)を、登録時にアプリが自動で作るか、利用者に選ばせるか
9. C/F の資金に預金を含めるライブラリ側の対応を待つか、それまで C/F 画面を出さないか
10. `AccountingException` のメッセージを画面向けにどう変換するか
