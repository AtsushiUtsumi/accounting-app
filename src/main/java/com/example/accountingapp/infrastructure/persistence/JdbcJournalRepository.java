package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.account.Side;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalRepository;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.AccountingPeriodId;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.port.AuditContext;
import com.example.accountingapp.application.port.CurrentUser;
import com.example.accountingapp.application.port.JournalQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 仕訳帳。journal / journal_line は INSERT だけ(DB のトリガーでも UPDATE / DELETE を拒否している)。
 *
 * <p>復元するときは、科目を1回で全件読み込んでから {@link JournalLine} を組み立て、{@link Journal#of} /
 * {@link Journal#reversalOf} を通して {@link PostedJournal#restore} で包む。DB から読んだ仕訳も、
 * ドメインの不変条件(貸借一致)で検証される。
 */
@Repository
public class JdbcJournalRepository implements JournalRepository, JournalQuery {

    private final JdbcClient jdbc;
    private final JdbcAccountRepository accounts;
    private final CurrentUser currentUser;
    private final Clock clock;

    public JdbcJournalRepository(JdbcClient jdbc, JdbcAccountRepository accounts, CurrentUser currentUser,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void save(PostedJournal journal) {
        saveAll(List.of(journal));
    }

    /**
     * 全件保存するか、1件も保存しないか(呼び出し側のトランザクションに参加し、失敗すればロールバックする)。
     * 保存時の会計期間の version が {@link PostedJournal#periodVersion()} と違う仕訳があれば、1件も保存せずに失敗する。
     */
    @Override
    @Transactional
    public void saveAll(List<PostedJournal> journals) {
        Map<AccountingPeriodId, Long> versions = new HashMap<>();
        for (PostedJournal journal : journals) {
            long stored = versions.computeIfAbsent(journal.periodId(), this::lockPeriodVersion);
            if (stored != journal.periodVersion()) {
                throw new ConcurrencyConflictException(
                        "accounting period was changed after posting (closed?): " + journal.periodId().value());
            }
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        String user = currentUser.name();
        String useCase = AuditContext.currentUseCase();
        try {
            for (PostedJournal posted : journals) {
                insert(posted, now, user, useCase);
            }
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyConflictException("journal already exists or is already reversed", e);
        }
    }

    /** 期の version を共有ロックで読む。締め(version の更新)と競合したら、どちらかが待つ。 */
    private long lockPeriodVersion(AccountingPeriodId periodId) {
        return jdbc.sql("select version from accounting_period where id = ? for share")
                .param(periodId.value())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("unknown accounting period: " + periodId.value()));
    }

    private void insert(PostedJournal posted, OffsetDateTime now, String user, String useCase) {
        Journal journal = posted.journal();
        jdbc.sql("""
                        insert into journal (id, period_id, period_version, date, description, type, reversal_of,
                                             created_at, created_by, created_by_use_case)
                        values (:id, :periodId, :periodVersion, :date, :description, :type, :reversalOf,
                                :createdAt, :createdBy, :useCase)
                        """)
                .param("id", journal.id().value())
                .param("periodId", posted.periodId().value())
                .param("periodVersion", posted.periodVersion())
                .param("date", journal.date())
                .param("description", journal.description())
                .param("type", journal.type().name())
                .param("reversalOf", journal.reversalOf() == null ? null : journal.reversalOf().value())
                .param("createdAt", now)
                .param("createdBy", user)
                .param("useCase", useCase)
                .update();
        int lineNo = 1;
        for (JournalLine line : journal.lines()) {
            jdbc.sql("""
                            insert into journal_line (journal_id, line_no, account_code, side, amount)
                            values (?, ?, ?, ?, ?)
                            """)
                    .params(journal.id().value(), lineNo++, line.account().code().value(), line.side().name(),
                            line.amount().amountInYen())
                    .update();
        }
    }

    @Override
    public Optional<PostedJournal> findById(JournalId id) {
        return restore("where j.id = :value", id.value()).stream().findFirst();
    }

    @Override
    public List<PostedJournal> findByPeriod(AccountingPeriod period) {
        return restore("where j.period_id = :value", period.id().value());
    }

    private List<PostedJournal> restore(String where, UUID value) {
        Map<AccountCode, Account> accountsByCode = accounts.findAllAsMap();
        Map<UUID, JournalRow> rows = new LinkedHashMap<>();
        jdbc.sql("select j.* from journal j " + where + " order by j.seq")
                .param("value", value)
                .query((rs, n) -> JournalRow.of(rs))
                .list()
                .forEach(row -> rows.put(row.id(), row));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<JournalLine>> lines = new HashMap<>();
        jdbc.sql("select l.* from journal_line l join journal j on j.id = l.journal_id " + where
                        + " order by l.journal_id, l.line_no")
                .param("value", value)
                .query(rs -> {
                    Account account = usableForRestore(accountsByCode.get(AccountCode.of(rs.getString("account_code"))));
                    lines.computeIfAbsent(rs.getObject("journal_id", UUID.class), id -> new ArrayList<>())
                            .add(new JournalLine(account, Side.valueOf(rs.getString("side")),
                                    Money.ofYen(rs.getLong("amount"))));
                });
        List<PostedJournal> result = new ArrayList<>();
        for (JournalRow row : rows.values()) {
            result.add(row.toPostedJournal(lines.getOrDefault(row.id(), List.of())));
        }
        return result;
    }

    /**
     * 記帳済みの仕訳の科目。{@link JournalLine} は無効化された科目を拒否するが、それは新しく使うことの禁止であって、
     * 使用済みの科目を後から無効化しても過去の仕訳は読めなければならない。科目の同一性はコードで決まるため、
     * 復元のときだけ有効な状態の科目として組み立てる。
     */
    private static Account usableForRestore(Account account) {
        if (account.active()) {
            return account;
        }
        return new Account(account.code(), account.name(), account.type(), account.category(), account.tags(),
                account.standard(), true);
    }

    private record JournalRow(UUID id, UUID periodId, long periodVersion, LocalDate date, String description,
                              JournalType type, UUID reversalOf) {

        static JournalRow of(ResultSet rs) throws SQLException {
            return new JournalRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("period_id", UUID.class),
                    rs.getLong("period_version"),
                    rs.getObject("date", LocalDate.class),
                    rs.getString("description"),
                    JournalType.valueOf(rs.getString("type")),
                    rs.getObject("reversal_of", UUID.class));
        }

        PostedJournal toPostedJournal(List<JournalLine> lines) {
            Journal journal = reversalOf == null
                    ? Journal.of(new JournalId(id), date, description, type, lines)
                    : Journal.reversalOf(new JournalId(id), date, description, type, lines, new JournalId(reversalOf));
            return PostedJournal.restore(journal, new AccountingPeriodId(periodId), periodVersion);
        }
    }

    // ---- JournalQuery ----

    @Override
    public boolean existsByPeriodAndType(AccountingPeriod period, JournalType type) {
        return jdbc.sql("select exists(select 1 from journal where period_id = ? and type = ?)")
                .params(period.id().value(), type.name())
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean existsUnreversedByPeriodAndTypeAndOrigin(AccountingPeriod period, JournalType type, String origin) {
        return jdbc.sql("""
                        select exists(
                            select 1 from journal j
                            where j.period_id = ? and j.type = ? and j.created_by_use_case = ?
                              and not exists (select 1 from journal r where r.reversal_of = j.id))
                        """)
                .params(period.id().value(), type.name(), origin)
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean isReversed(JournalId id) {
        return jdbc.sql("select exists(select 1 from journal where reversal_of = ?)")
                .param(id.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean isAccountUsed(AccountCode code) {
        return jdbc.sql("select exists(select 1 from journal_line where account_code = ?)")
                .param(code.value())
                .query(Boolean.class)
                .single();
    }
}
