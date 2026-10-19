# accounting-app

`accounting-domain` ライブラリを使った会計アプリ(Spring Boot)。設計は [CLAUDE.md](CLAUDE.md)。

## 前提

- Java 25
- `../accounting-domain` にドメインのリポジトリがあること(composite build で取り込む)
- PostgreSQL(テストは Testcontainers を使うので Docker が必要。Docker がなければ DB のテストはスキップされる)

## ビルドとテスト

```
./gradlew test
```

## 起動

Windows では `run.bat` を実行する(Docker Desktop を起動しておくこと)。
`compose.yaml` の PostgreSQL をポート 15432 で起動してから、アプリを http://localhost/ (ポート 80)で起動する。
ポート 80 がほかのプログラム(別プロジェクトの nginx など)に使われていると、run.bat はメッセージを出して止まる。
そのときは、そのプログラムを止めるか、`set ACCOUNTING_PORT=8080 & run.bat` のように別のポートで起動する。
止めるときは Ctrl+C。PostgreSQL のコンテナは `docker compose down` で止める(データはボリュームに残る)。

アプリもコンテナで動かすときは `run-container.bat` を実行する。jar を作り(`gradlew bootJar`)、
`Dockerfile` からイメージ `accounting-app:dev` を手元で作って、PostgreSQL と一緒に起動する(`compose.yaml` の `app` サービス。
プロファイル `app` のときだけ起動するので、`run.bat` では起動しない)。
ポートと開発用ユーザーは `run.bat` と同じ(http://localhost/、`set ACCOUNTING_PORT=8080 & run-container.bat` で変更)。
Ctrl+C でアプリと PostgreSQL の両方が止まる。DB のボリュームは `run.bat` と共通。

起動したら、ブラウザで http://localhost/ を開く(サインイン画面が出る)。
画面はアプリに同梱した静的ファイル(`src/main/resources/static`)で、データはすべて REST API から取得する。

ほかの DB を使うときは、環境変数で接続先を指定して起動する。

```
ACCOUNTING_DB_URL=jdbc:postgresql://localhost:5432/accounting \
ACCOUNTING_DB_USER=accounting ACCOUNTING_DB_PASSWORD=accounting \
./gradlew bootRun
```

スキーマは起動時に Flyway が作る。認証は HTTP Basic で、ユーザーは `application.yml` の
`accounting.security.users`(開発用の初期値: `viewer` / `bookkeeper` / `approver`。本番では必ず上書きする)と、
サインアップしたユーザー。

- **サインアップ**: サインイン画面の「サインアップ」、または `POST /signup`(`{"name":"alice","password":"..."}`、認証不要)。
  サインアップした直後はロールがなく、帳簿を見られない(画面は「承認待ち」になる)。
- **ロールの付与**: 承認者が画面の「マスタ > ユーザー」、または `PATCH /users/{name}`(`{"role":"VIEWER"}`)で付ける。
- **アカウントの削除**: 本人は画面右上の「アカウント」、または `DELETE /me`。承認者は `DELETE /users/{name}` でほかのユーザーも削除できる。
  設定ファイルのユーザーと、最後の承認者は削除できない。記帳した仕訳の記帳者の記録(ユーザー名)は残る。
- サインアップに回数制限や CAPTCHA はない。インターネットに公開するなら、リバースプロキシなどで制限すること。

```
# 会社の初期化(承認者)
curl -u approver:approver -H 'Content-Type: application/json' \
     -d '{"firstPeriodStart":"2026-04-01"}' localhost/company

# 記帳(記帳者)
curl -u bookkeeper:bookkeeper -H 'Content-Type: application/json' \
     -d '{"date":"2026-04-01","description":"出資による開業","lines":[
          {"accountCode":"101","side":"DEBIT","amount":600000},
          {"accountCode":"301","side":"CREDIT","amount":600000}]}' localhost/journals
```

## 複数の会社と連結

帳簿(勘定科目・会計期間・仕訳・資産・商品有高帳)は会社ごとに持つ。既存のデータと、会社を指定しない操作は「既定の会社」
(`00000000-0000-0000-0000-000000000001`)のもの。

- **会社を選ぶ**: REST はリクエストヘッダー `X-Company-Id`、MCP は各ツールの引数 `companyId`、画面は右上の「会社」で選ぶ。
- **会社を作る**(承認者): `POST /companies`(`{"name":"子会社A","firstPeriodStart":"2026-04-01"}`)。標準科目と最初の期も用意する。
- **連結**(親会社を選んだ状態で、承認者が入力): 子会社を `POST /subsidiaries` で登録し、連結会社間の債権債務・取引高・未実現利益を
  `POST /periods/{親会社の期}/consolidation/...` で入力して、`GET /periods/{親会社の期}/consolidation` で
  連結修正仕訳・連結精算表・連結損益計算書・連結貸借対照表を作る(保存しない)。子会社は、期末日が同じ期の帳簿を使う。
- 連結のルールはライブラリで**未検証**(`UNVERIFIED_RULES.md` の 14-x)。結果には `unverified: true` が付き、画面には「検証中」と出る。
- 会社ごとの権限は分けていない(どのユーザーも、すべての会社を同じロールで操作できる)。

## AI エージェントから使う(MCP)

REST API で公開している機能は、すべて MCP のツールとしても `/mcp`(Streamable HTTP、ステートレス)で公開している
(`presentation.mcp`。`presentation.controller` と同じ並び)。認証は REST と同じ HTTP Basic で、ツールごとに REST と同じロールが必要になる。

接続には、画面右上の「設定」で発行する **API キー**を使う(パスワードを AI エージェントに渡さずに済む)。
キーは発行した直後に一度だけ表示され、DB にはハッシュだけを保存する。設定画面から、いつでも失効させられる。
キーを使ったときの権限は、持ち主のその時点のロール(ロールを外されたり、アカウントを削除されたりすれば、キーでも使えなくなる)。
キーは `/mcp` にだけ使え、REST API と画面には使えない。

```
claude mcp add --transport http accounting http://localhost/mcp \
       --header "Authorization: Bearer acc_..."
```

HTTP Basic でも接続できる(Basic の値は `user:password` を Base64 にしたもの):

```
claude mcp add --transport http accounting http://localhost/mcp \
       --header "Authorization: Basic $(printf approver:approver | base64)"
```

ツールの一覧は `MCP_TOOLS.md`。どのツールにも省略可能な引数 `companyId` があり、指定するとその会社を操作する
(省略すれば既定の会社。接続時に `--header "X-Company-Id: ..."` を付けて、接続ごとの既定を変えることもできる)。

エラーは、先頭に REST の ProblemDetail と同じ `code` を付けて返す(例: `[ACCOUNTING_RULE_VIOLATION] debit total (50000) must equal credit total (40000)`)。
ロールが足りないときは `[FORBIDDEN]`。

## 本番(GHCR のイメージで起動)

アプリのイメージは `ghcr.io/atsushiutsumi/accounting-app` に置く。タグは `latest` と、コミットの短いハッシュ。

### イメージを作って push する

アプリは `../accounting-domain` を composite build で取り込むため、Docker の中では Gradle を動かさない。
先に jar を作り、Dockerfile はその jar をコピーするだけにしている。

- **GitHub Actions**(`.github/workflows/image.yml`): `main` への push、`v*` のタグ、手動実行で、
  テスト → jar → イメージ(linux/amd64 と linux/arm64)→ push を行う。accounting-domain も並べて checkout する。
- **手元から**: `build-image.bat`(テスト → jar → イメージ → push)。先に `docker login ghcr.io -u AtsushiUtsumi`
  (パスワードは `write:packages` 権限のトークン)を実行しておく。コミットしていない変更があると止まる。
  `build-image.bat --local` なら push せず、手元に `:local` のタグで作る。

最初に push したイメージは非公開になる。本番サーバーで `docker login ghcr.io`(`read:packages` 権限のトークン)
をするか、GitHub のパッケージの設定で公開にする。

### 本番サーバーで起動する

本番サーバーに `compose.prod.yaml`・`run-prod.sh`・`.env.example` を置いて、次を実行する。

```
cp .env.example .env    # 値を書き換える(パスワードの作り方は .env.example に書いてある)
./run-prod.sh           # イメージを pull し、このアプリのコンテナだけを入れ替える
```

- コンテナの中のアプリは 8080 番で待ち受け(root 以外のユーザーで動かすため)、外からは `ACCOUNTING_HTTP_PORT`(既定 80)で受ける。
- 本番では `prod` プロファイルで動き、`application.yml` の開発用ユーザーは使われない。ユーザーは `.env` で与える。
- DB は外部に公開しない。データはボリューム `accounting-app-prod_accounting-data` に残る。
- 特定の版に戻すときは、`.env` の `ACCOUNTING_IMAGE_TAG` をコミットのハッシュにして `./run-prod.sh` を実行する。

## 運用上の注意

- 仕訳(journal / journal_line)は追記のみ。DB のトリガーで UPDATE / DELETE を拒否している。
  アプリの DB ユーザーからも UPDATE / DELETE 権限を外すこと。
- 決算整理(`POST /periods/{id}/adjustments`)の前に、経過勘定・貯蔵品を ADJUSTING の仕訳として手入力しておく。
- 締め(`POST /periods/{id}/close`)の前に、現金過不足を振り替えておく(`POST /transactions/cash-over-short-settlements`)。
