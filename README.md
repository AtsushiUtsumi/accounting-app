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
     -d '{"firstPeriodStart":"2026-04-01"}' localhost:8080/company

# 記帳(記帳者)
curl -u bookkeeper:bookkeeper -H 'Content-Type: application/json' \
     -d '{"date":"2026-04-01","description":"出資による開業","lines":[
          {"accountCode":"101","side":"DEBIT","amount":600000},
          {"accountCode":"301","side":"CREDIT","amount":600000}]}' localhost:8080/journals
```

## 運用上の注意

- 仕訳(journal / journal_line)は追記のみ。DB のトリガーで UPDATE / DELETE を拒否している。
  アプリの DB ユーザーからも UPDATE / DELETE 権限を外すこと。
- 決算整理(`POST /periods/{id}/adjustments`)の前に、経過勘定・貯蔵品を ADJUSTING の仕訳として手入力しておく。
- 締め(`POST /periods/{id}/close`)の前に、現金過不足を振り替えておく(`POST /transactions/cash-over-short-settlements`)。
