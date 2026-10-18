package com.example.accountingapp.application.closing;

import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalRepository;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.AccountingPeriodId;
import com.example.accounting.model.period.AccountingPeriodRepository;
import com.example.accounting.model.statement.Ledger;
import com.example.accounting.service.ClosingResult;
import com.example.accounting.service.ClosingService;
import com.example.accounting.service.FinancialStatementFactory;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.port.AuditContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 決算を締める(CLAUDE.md §6.5)。{@link ClosingResult} の Javadoc の順序で、1トランザクションで保存する。
 * すでに CLOSED の期はドメインが拒否し、同時に2回実行されたら楽観ロックで片方が弾かれる。
 */
@Service
public class ClosePeriodUseCase {

    public static final String NAME = "ClosePeriod";

    private final JournalBook book;
    private final JournalRepository journalRepository;
    private final AccountingPeriodRepository periodRepository;
    private final ClosingService closingService;
    private final FinancialStatementFactory statements;

    public ClosePeriodUseCase(JournalBook book, JournalRepository journalRepository,
                              AccountingPeriodRepository periodRepository, ClosingService closingService,
                              FinancialStatementFactory statements) {
        this.book = book;
        this.journalRepository = journalRepository;
        this.periodRepository = periodRepository;
        this.closingService = closingService;
        this.statements = statements;
    }

    @Transactional
    public Result close(UUID periodId) {
        return AuditContext.within(NAME, () -> {
            AccountingPeriod period = book.period(periodId);
            List<PostedJournal> posted = book.postedJournalsOf(period);
            List<Journal> journals = PostedJournal.journalsOf(posted);

            // 締めの前に、現金過不足の残高ゼロ化(CashOverShortService.settleAtPeriodEnd)が済んでいること
            if (!Ledger.of(StandardAccounts.CASH_OVER_SHORT, journals).balance().isZero()) {
                throw new OperationNotAllowedException(
                        "cash over/short must be settled (settleAtPeriodEnd) before closing the period");
            }

            ClosingResult result = closingService.close(period, posted);

            journalRepository.saveAll(result.closingEntries());
            periodRepository.save(result.closedPeriod());
            periodRepository.save(result.nextPeriod());
            journalRepository.saveAll(result.openingEntries());

            return new Result(result.nextPeriod().id(), statements.incomeStatement(journals).netIncome());
        });
    }

    /** 次期の ID と当期純利益(純損失ならマイナス)。 */
    public record Result(AccountingPeriodId nextPeriodId, Money netIncome) {
    }
}
