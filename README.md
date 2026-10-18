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

起動したら、ブラウザで http://localhost/ を開く(ログイン画面が出る)。
画面はアプリに同梱した静的ファイル(`src/main/resources/static`)で、データはすべて REST API から取得する。

ほかの DB を使うときは、環境変数で接続先を指定して起動する。

```
ACCOUNTING_DB_URL=jdbc:postgresql://localhost:5432/accounting \
ACCOUNTING_DB_USER=accounting ACCOUNTING_DB_PASSWORD=accounting \
./gradlew bootRun
```

スキーマは起動時に Flyway が作る。認証は HTTP Basic で、ユーザーは `application.yml` の
`accounting.security.users`(開発用の初期値: `viewer` / `bookkeeper` / `approver`。本番では必ず上書きする)。

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

## AI エージェントから使う(MCP)

REST API で公開している機能は、すべて MCP のツールとしても `/mcp`(Streamable HTTP、ステートレス)で公開している
(`presentation.mcp`。`presentation.controller` と同じ並び)。認証は REST と同じ HTTP Basic で、ツールごとに REST と同じロールが必要になる。

Claude Code から接続する例(承認者として。Basic の値は `user:password` を Base64 にしたもの):

```
claude mcp add --transport http accounting http://localhost/mcp \
       --header "Authorization: Basic $(printf approver:approver | base64)"
```

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
