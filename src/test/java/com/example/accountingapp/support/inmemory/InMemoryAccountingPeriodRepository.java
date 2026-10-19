package com.example.accountingapp.support.inmemory;

import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.AccountingPeriodId;
import com.example.accounting.model.period.AccountingPeriodRepository;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.port.PeriodLookup;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 会計期間のインメモリ実装。既存の期の保存は、JDBC 実装と同じく version が1つ進んでいることを検証する。 */
public final class InMemoryAccountingPeriodRepository implements AccountingPeriodRepository, PeriodLookup {

    private final Map<AccountingPeriodId, AccountingPeriod> periods = new LinkedHashMap<>();

    @Override
    public Optional<AccountingPeriod> findById(AccountingPeriodId id) {
        return Optional.ofNullable(periods.get(id));
    }

    @Override
    public void save(AccountingPeriod period) {
        AccountingPeriod stored = periods.get(period.id());
        if (stored != null && stored.version() != period.version() - 1) {
            throw new ConcurrencyConflictException("accounting period was updated concurrently: " + period.id().value());
        }
        periods.put(period.id(), period);
    }

    @Override
    public Optional<AccountingPeriod> findContaining(LocalDate date) {
        return periods.values().stream().filter(period -> period.contains(date)).findFirst();
    }

    @Override
    public Optional<AccountingPeriod> findPrevious(AccountingPeriod period) {
        return periods.values().stream()
                .filter(candidate -> candidate.endDate().equals(period.startDate().minusDays(1)))
                .findFirst();
    }

    @Override
    public Optional<AccountingPeriod> findEndingOn(LocalDate endDate) {
        return periods.values().stream().filter(period -> period.endDate().equals(endDate)).findFirst();
    }

    @Override
    public Optional<AccountingPeriod> findLatest() {
        return periods.values().stream().max(Comparator.comparing(AccountingPeriod::startDate));
    }

    @Override
    public List<AccountingPeriod> findAll() {
        return periods.values().stream().sorted(Comparator.comparing(AccountingPeriod::startDate).reversed()).toList();
    }
}
