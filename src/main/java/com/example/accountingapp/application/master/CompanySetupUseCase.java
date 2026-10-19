package com.example.accountingapp.application.master;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountRepository;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.AccountingPeriodRepository;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.port.PeriodLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 会社の初期化(CLAUDE.md §6.8)。標準科目を投入し、最初の会計期間(1年固定)を作る。
 * いま操作している会社が対象で、その会社に期が1つでもあれば初期化済みとして拒否する。
 */
@Service
public class CompanySetupUseCase {

    private final AccountRepository accountRepository;
    private final AccountingPeriodRepository periodRepository;
    private final PeriodLookup periodLookup;

    public CompanySetupUseCase(AccountRepository accountRepository, AccountingPeriodRepository periodRepository,
                               PeriodLookup periodLookup) {
        this.accountRepository = accountRepository;
        this.periodRepository = periodRepository;
        this.periodLookup = periodLookup;
    }

    @Transactional
    public AccountingPeriod setUp(LocalDate firstPeriodStart) {
        if (periodLookup.findLatest().isPresent()) {
            throw new OperationNotAllowedException("company is already set up");
        }
        for (Account account : StandardAccounts.ALL) {
            if (!accountRepository.existsByCode(account.code())) {
                accountRepository.save(account);
            }
        }
        AccountingPeriod period = AccountingPeriod.startingAt(firstPeriodStart);
        periodRepository.save(period);
        return period;
    }
}
