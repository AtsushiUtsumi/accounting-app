package com.example.accountingapp.support.inmemory;

import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalRepository;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.AccountingPeriodRepository;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.port.AuditContext;
import com.example.accountingapp.application.port.JournalQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 仕訳のインメモリ実装。saveAll は全件を検証してから保存する(全件か0件か)。
 * 検証は、追記のみ(同じ ID を二度保存しない)と、会計期間の version の一致。
 * 監査列の代わりに、保存したユースケースの名前を覚えておく。
 */
public final class InMemoryJournalRepository implements JournalRepository, JournalQuery {

    private record Stored(PostedJournal journal, String origin) {
    }

    private final Map<JournalId, Stored> journals = new LinkedHashMap<>();
    private final AccountingPeriodRepository periods;

    public InMemoryJournalRepository(AccountingPeriodRepository periods) {
        this.periods = periods;
    }

    @Override
    public void save(PostedJournal journal) {
        saveAll(List.of(journal));
    }

    @Override
    public void saveAll(List<PostedJournal> newJournals) {
        for (PostedJournal journal : newJournals) {
            if (journals.containsKey(journal.id())) {
                throw new ConcurrencyConflictException("journal already exists (append-only): " + journal.id().value());
            }
            AccountingPeriod stored = periods.findById(journal.periodId())
                    .orElseThrow(() -> new IllegalStateException("unknown period: " + journal.periodId().value()));
            if (stored.version() != journal.periodVersion()) {
                throw new ConcurrencyConflictException("period was changed after posting (closed?): " + stored.id().value());
            }
        }
        String origin = AuditContext.currentUseCase();
        for (PostedJournal journal : newJournals) {
            journals.put(journal.id(), new Stored(journal, origin));
        }
    }

    @Override
    public Optional<PostedJournal> findById(JournalId id) {
        return Optional.ofNullable(journals.get(id)).map(Stored::journal);
    }

    @Override
    public List<PostedJournal> findByPeriod(AccountingPeriod period) {
        return journals.values().stream()
                .map(Stored::journal)
                .filter(journal -> journal.periodId().equals(period.id()))
                .toList();
    }

    @Override
    public boolean existsByPeriodAndType(AccountingPeriod period, JournalType type) {
        return findByPeriod(period).stream().anyMatch(journal -> journal.journal().type() == type);
    }

    @Override
    public boolean existsUnreversedByPeriodAndTypeAndOrigin(AccountingPeriod period, JournalType type, String origin) {
        return journals.values().stream()
                .filter(stored -> stored.journal().periodId().equals(period.id()))
                .filter(stored -> stored.journal().journal().type() == type)
                .filter(stored -> stored.origin().equals(origin))
                .anyMatch(stored -> !isReversed(stored.journal().id()));
    }

    @Override
    public boolean isReversed(JournalId id) {
        return journals.values().stream().anyMatch(stored -> id.equals(stored.journal().journal().reversalOf()));
    }

    @Override
    public Set<JournalId> findReversedIds(AccountingPeriod period) {
        return findByPeriod(period).stream()
                .map(PostedJournal::id)
                .filter(this::isReversed)
                .collect(Collectors.toSet());
    }

    @Override
    public boolean isAccountUsed(AccountCode code) {
        return journals.values().stream()
                .flatMap(stored -> stored.journal().journal().lines().stream())
                .anyMatch(line -> line.account().code().equals(code));
    }
}
