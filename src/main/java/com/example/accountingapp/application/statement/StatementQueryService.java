package com.example.accountingapp.application.statement;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.statement.BalanceSheet;
import com.example.accounting.model.statement.CashFlowClassifier;
import com.example.accounting.model.statement.CashFlowStatement;
import com.example.accounting.model.statement.IncomeStatement;
import com.example.accounting.model.statement.Ledger;
import com.example.accounting.model.statement.StatementOfChangesInEquity;
import com.example.accounting.model.statement.TrialBalance;
import com.example.accounting.model.statement.Worksheet;
import com.example.accounting.service.FinancialStatementFactory;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.port.PeriodLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 照会(CLAUDE.md §6.7)。照会のたびに期の全仕訳を読み込み、ライブラリに計算させる(保存した集計値は使わない)。
 *
 * <p>締め前の状態を見せるかどうか(§12 の未決事項4)は照会ごとに次のように決めた:
 * <ul>
 *   <li>試算表: 既定では損益振替(CLOSING)を除く。{@code includeClosing} で含められる</li>
 *   <li>P/L・精算表: 常に CLOSING を除く(含めると収益・費用がゼロになる)</li>
 *   <li>B/S・株主資本等変動計算書: すべての仕訳(B/S は損益振替後を前提とするため、締め前の期では
 *       資産 = 負債 + 純資産にならない。{@code BalanceSheet#isBalanced()} をそのまま返す)</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class StatementQueryService {

    private final JournalBook book;
    private final PeriodLookup periodLookup;
    private final AccountResolver accounts;
    private final FinancialStatementFactory statements;
    private final CashFlowClassifier cashFlowClassifier;

    public StatementQueryService(JournalBook book, PeriodLookup periodLookup, AccountResolver accounts,
                                 FinancialStatementFactory statements, CashFlowClassifier cashFlowClassifier) {
        this.book = book;
        this.periodLookup = periodLookup;
        this.accounts = accounts;
        this.statements = statements;
        this.cashFlowClassifier = cashFlowClassifier;
    }

    public AccountingPeriod period(UUID periodId) {
        return book.period(periodId);
    }

    public AccountingPeriod latestPeriod() {
        return book.latestPeriod();
    }

    public PostedJournal journal(UUID journalId) {
        return book.journal(new JournalId(journalId));
    }

    /** 仕訳帳。その期に記帳された仕訳。 */
    public List<PostedJournal> journals(UUID periodId) {
        return book.postedJournalsOf(book.period(periodId));
    }

    public TrialBalance trialBalance(UUID periodId, boolean includeClosing) {
        List<Journal> journals = book.journalsOf(book.period(periodId));
        return statements.trialBalance(includeClosing ? journals : excluding(journals, JournalType.CLOSING));
    }

    public BalanceSheet balanceSheet(UUID periodId) {
        return statements.balanceSheet(book.journalsOf(book.period(periodId)));
    }

    public IncomeStatement incomeStatement(UUID periodId) {
        return statements.incomeStatement(excluding(book.journalsOf(book.period(periodId)), JournalType.CLOSING));
    }

    /** 精算表。決算整理前(ADJUSTING・CLOSING 以外)と ADJUSTING に分けて渡す。 */
    public Worksheet worksheet(UUID periodId) {
        List<Journal> journals = book.journalsOf(book.period(periodId));
        List<Journal> beforeAdjustment = excluding(journals, JournalType.ADJUSTING, JournalType.CLOSING);
        List<Journal> adjusting = journals.stream().filter(journal -> journal.type() == JournalType.ADJUSTING).toList();
        return Worksheet.of(beforeAdjustment, adjusting);
    }

    /**
     * C/F。前期末の B/S を得るために前期の仕訳(損益振替・開始仕訳を含む全件)を、当期分として当期の仕訳から
     * 開始仕訳(前期末残高の引継ぎで、前期の仕訳と二重になる)と損益振替を除いたものを渡す。
     * 前期がない(最初の期)ときは、当期の開始仕訳を前期末の B/S の代わりにする(通常は空)。
     *
     * <p>資金は「現金」勘定だけ(ライブラリの既知の制限)。画面に注記する。
     */
    public CashFlowStatement cashFlow(UUID periodId) {
        AccountingPeriod period = book.period(periodId);
        List<Journal> current = book.journalsOf(period);
        List<Journal> prior = periodLookup.findPrevious(period)
                .map(book::journalsOf)
                .orElseGet(() -> current.stream().filter(journal -> journal.type() == JournalType.OPENING).toList());
        return CashFlowStatement.of(prior, excluding(current, JournalType.OPENING, JournalType.CLOSING),
                cashFlowClassifier);
    }

    public StatementOfChangesInEquity equityChanges(UUID periodId) {
        return StatementOfChangesInEquity.of(book.journalsOf(book.period(periodId)));
    }

    /** 総勘定元帳。合計・残高は {@link Ledger}、明細の日付・摘要は、その科目を含む仕訳から取る。 */
    public LedgerView ledger(String accountCode, UUID periodId) {
        Account account = accounts.resolve(accountCode);
        List<Journal> journals = book.journalsOf(book.period(periodId));
        List<Journal> touching = journals.stream()
                .filter(journal -> journal.lines().stream().anyMatch(line -> line.account().equals(account)))
                .toList();
        return new LedgerView(Ledger.of(account, journals), touching);
    }

    /** 元帳と、その科目を含む仕訳(明細の日付・摘要・相手科目の表示用)。 */
    public record LedgerView(Ledger ledger, List<Journal> journals) {
    }

    private static List<Journal> excluding(List<Journal> journals, JournalType... types) {
        List<JournalType> excluded = List.of(types);
        return journals.stream().filter(journal -> !excluded.contains(journal.type())).toList();
    }
}
