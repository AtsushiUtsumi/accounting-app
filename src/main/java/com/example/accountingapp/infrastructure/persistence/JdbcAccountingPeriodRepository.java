package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.AccountingPeriodId;
import com.example.accounting.model.period.AccountingPeriodRepository;
import com.example.accounting.model.period.PeriodStatus;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.port.CompanyContext;
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
 * 期は会社ごと({@link CompanyContext} の会社の期だけを読み書きする)。
 */
@Repository
public class JdbcAccountingPeriodRepository implements AccountingPeriodRepository, PeriodLookup {

    private final JdbcClient jdbc;
    private final CompanyContext companyContext;

    public JdbcAccountingPeriodRepository(JdbcClient jdbc, CompanyContext companyContext) {
        this.jdbc = jdbc;
        this.companyContext = companyContext;
    }

    private UUID company() {
        return companyContext.current().value();
    }

    @Override
    public Optional<AccountingPeriod> findById(AccountingPeriodId id) {
        return jdbc.sql("select * from accounting_period where company_id = ? and id = ?")
                .params(company(), id.value())
                .query((rs, n) -> toPeriod(rs))
                .optional();
    }

    @Override
    public void save(AccountingPeriod period) {
        boolean exists = jdbc.sql("select exists(select 1 from accounting_period where company_id = ? and id = ?)")
                .params(company(), period.id().value())
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
                            insert into accounting_period (id, company_id, start_date, end_date, status, version)
                            values (:id, :company, :start, :end, :status, :version)
                            """)
                    .param("id", period.id().value())
                    .param("company", company())
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
        return jdbc.sql("""
                        select * from accounting_period
                        where company_id = :company and start_date <= :date and end_date >= :date
                        """)
                .param("company", company())
                .param("date", date)
                .query((rs, n) -> toPeriod(rs))
                .optional();
    }

    @Override
    public Optional<AccountingPeriod> findPrevious(AccountingPeriod period) {
        return findEndingOn(period.startDate().minusDays(1));
    }

    @Override
    public Optional<AccountingPeriod> findEndingOn(LocalDate endDate) {
        return jdbc.sql("select * from accounting_period where company_id = ? and end_date = ?")
                .params(company(), endDate)
                .query((rs, n) -> toPeriod(rs))
                .optional();
    }

    @Override
    public Optional<AccountingPeriod> findLatest() {
        return jdbc.sql("select * from accounting_period where company_id = ? order by start_date desc limit 1")
                .param(company())
                .query((rs, n) -> toPeriod(rs))
                .optional();
    }

    @Override
    public List<AccountingPeriod> findAll() {
        return jdbc.sql("select * from accounting_period where company_id = ? order by start_date desc")
                .param(company())
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
