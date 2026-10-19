package com.example.accountingapp.infrastructure.persistence;

import com.example.accountingapp.application.port.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers の PostgreSQL に Flyway でスキーマを作り、アプリ全体を起動して試す。
 * Docker が使えない環境ではスキップする。テストごとに全テーブルを空にする
 * (TRUNCATE は行単位の DELETE トリガーを発火しないため、追記のみの仕訳テーブルも空にできる)。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
abstract class PostgresTestSupport {

    /**
     * 全テストクラスで1つのコンテナを共有する(Spring のコンテキストはキャッシュされるため、
     * クラスごとにコンテナを作り直すと、キャッシュされたコンテキストが止まったコンテナを指してしまう)。
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void truncateAllTables() {
        jdbc.sql("""
                truncate table app_user, unrealized_profit, intercompany_transaction, intercompany_balance, subsidiary_equity,
                    subsidiary, inventory_movement, held_to_maturity_bond, intangible_asset, fixed_asset_disposal,
                    fixed_asset, journal_line, journal, accounting_period, account_tag, account cascade
                """).update();
        // 既定の会社(マイグレーションで作る)だけを残す
        jdbc.sql("delete from company where id <> ?").param(CompanyRepository.DEFAULT_COMPANY.value()).update();
    }
}
