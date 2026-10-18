package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.AccountingPeriodId;
import com.example.accounting.model.period.AccountingPeriodRepository;
import com.example.accounting.model.period.PeriodStatus;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.port.PeriodLookup;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会計期間。既存の期の保存は version による楽観ロック: {@code close()} は version を1つ進めるので、
 * 保存時の DB の version が「新しい version − 1」でなければ、ほかの誰かが先に更新したとみなす。
 */
@Repository
public class JdbcAccountingPeriodRepository implements AccountingPeriodRepository, PeriodLookup {

    private final JdbcClient jdbc;

    public JdbcAccountingPeriodRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountingPeriod> findById(AccountingPeriodId id) {
        return jdbc.sql("select * from accounting_period where id = ?")
                .param(id.value())
                .query((rs, n) -> toPeriod(rs))
                .optional();
    }

    @Override
    public void save(AccountingPeriod period) {
        boolean exists = jdbc.sql("select exists(select 1 from accounting_period where id = ?)")
                .param(period.id().value())
                .query(Boolean.class)
                .single();
        if (!exists) {
            insert(period);
            return;
        }
        int updated = jdbc.sql("""
                        update accounting_period set status = :status, version = :version
                        where id = :id and version = :version - 1
                        """)
                .param("status", period.status().name())
                .param("version", period.version())
                .param("id", period.id().value())
                .update();
        if (updated == 0) {
            throw new ConcurrencyConflictException(
                    "accounting period was updated concurrently: " + period.id().value());
        }
    }

    private void insert(AccountingPeriod period) {
        try {
            jdbc.sql("""
                            insert into accounting_period (id, start_date, end_date, status, version)
                            values (:id, :start, :end, :status, :version)
                            """)
                    .param("id", period.id().value())
                    .param("start", period.startDate())
                    .param("end", period.endDate())
                    .param("status", period.status().name())
                    .param("version", period.version())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyConflictException("accounting period already exists: " + period.startDate(), e);
        }
    }

    @Override
    public Optional<AccountingPeriod> findContaining(LocalDate date) {
        return jdbc.sql("select * from accounting_period where start_date <= :date and end_date >= :date")
                .param("date", date)
                .query((rs, n) -> toPeriod(rs))
                .optional();
    }

    @Override
    public Optional<AccountingPeriod> findPrevious(AccountingPeriod period) {
        return jdbc.sql("select * from accounting_period where end_date = ?")
                .param(period.startDate().minusDays(1))
                .query((rs, n) -> toPeriod(rs))
                .optional();
    }

    @Override
    public Optional<AccountingPeriod> findLatest() {
        return jdbc.sql("select * from accounting_period order by start_date desc limit 1")
                .query((rs, n) -> toPeriod(rs))
                .optional();
    }

    @Override
    public List<AccountingPeriod> findAll() {
        return jdbc.sql("select * from accounting_period order by start_date desc")
                .query((rs, n) -> toPeriod(rs))
                .list();
    }

    private static AccountingPeriod toPeriod(ResultSet rs) throws SQLException {
        return new AccountingPeriod(
                new AccountingPeriodId(rs.getObject("id", UUID.class)),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                PeriodStatus.valueOf(rs.getString("status")),
                rs.getLong("version"));
    }
}
