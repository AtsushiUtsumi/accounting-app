package com.example.accountingapp.application.closing;

import com.example.accounting.exception.AccountingException;
import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.PeriodStatus;
import com.example.accounting.model.statement.BalanceSheet;
import com.example.accounting.model.statement.Ledger;
import com.example.accounting.model.statement.TrialBalance;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.support.SampleShop;
import com.example.accountingapp.support.TestAccounting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.example.accountingapp.support.TestAccounting.cr;
import static com.example.accountingapp.support.TestAccounting.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** サンプル商店(ドメインの ClosingServiceTest と同じ例題)を、ユースケース経由で締める。 */
class ClosingFlowTest {

    private final TestAccounting app = new TestAccounting();
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        period = app.setUpCompany(SampleShop.PERIOD_START);
        SampleShop.postAprilJournals(app);
    }

    @Test
    void close_returnsNextPeriodAndNetIncome() {
        ClosePeriodUseCase.Result result = app.closePeriod.close(period.id().value());

        assertThat(result.netIncome()).isEqualTo(Money.ofYen(-20_000));
        AccountingPeriod closed = app.reload(period);
        AccountingPeriod next = app.periods.findById(result.nextPeriodId()).orElseThrow();
        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closed.version()).isEqualTo(period.version() + 1);
        assertThat(next.startDate()).isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(next.isOpen()).isTrue();
    }

    /** ドメインの自己検証を、リポジトリを通したあとでも確認する(CLAUDE.md §10)。 */
    @Test
    void close_selfChecksHoldAfterSaving() {
        ClosePeriodUseCase.Result result = app.closePeriod.close(period.id().value());
        List<Journal> closedPeriodJournals = app.journalsOf(app.reload(period));
        List<Journal> nextPeriodJournals = app.journalsOf(app.periods.findById(result.nextPeriodId()).orElseThrow());

        // 収益・費用の残高がゼロ
        assertThat(Ledger.of(StandardAccounts.SALES, closedPeriodJournals).balance()).isEqualTo(Money.ZERO);
        assertThat(Ledger.of(StandardAccounts.PURCHASES, closedPeriodJournals).balance()).isEqualTo(Money.ZERO);
        assertThat(Ledger.of(StandardAccounts.PROFIT_AND_LOSS, closedPeriodJournals).balance()).isEqualTo(Money.ZERO);

        // 資産 = 負債 + 純資産
        BalanceSheet balanceSheet = BalanceSheet.of(closedPeriodJournals);
        assertThat(balanceSheet.isBalanced()).isTrue();
        assertThat(balanceSheet.totalAssets()).isEqualTo(Money.ofYen(930_000));
        assertThat(balanceSheet.totalLiabilities()).isEqualTo(Money.ofYen(350_000));
        assertThat(balanceSheet.totalEquity()).isEqualTo(Money.ofYen(580_000));

        // 期末残高 = 次期の期首残高
        TrialBalance endOfPeriod = TrialBalance.of(closedPeriodJournals);
        for (Account account : List.of(
                StandardAccounts.CASH, StandardAccounts.ACCOUNTS_RECEIVABLE, StandardAccounts.ACCOUNTS_PAYABLE,
                StandardAccounts.LOANS_PAYABLE, StandardAccounts.CAPITAL_STOCK, StandardAccounts.RETAINED_EARNINGS)) {
            assertThat(Ledger.of(account, nextPeriodJournals).balance())
                    .as("opening balance of %s", account.name())
                    .isEqualTo(endOfPeriod.rows().get(account).netBalance(account.type()));
        }

        // 当期純利益 = 繰越利益剰余金の増加額
        assertThat(Ledger.of(StandardAccounts.RETAINED_EARNINGS, closedPeriodJournals).balance())
                .isEqualTo(result.netIncome());
        assertThat(nextPeriodJournals).allMatch(journal -> journal.type() == JournalType.OPENING);
    }

    @Test
    void close_alreadyClosedPeriodIsRejectedByTheDomain() {
        app.closePeriod.close(period.id().value());

        assertThatThrownBy(() -> app.closePeriod.close(period.id().value()))
                .isInstanceOf(AccountingException.class);
    }

    @Test
    void close_requiresCashOverShortToBeSettled() {
        app.transactions.recordCashDiscrepancy(LocalDate.of(2026, 4, 30), 829_000);

        assertThatThrownBy(() -> app.closePeriod.close(period.id().value()))
                .isInstanceOf(OperationNotAllowedException.class);

        app.transactions.settleCashOverShort(LocalDate.of(2027, 3, 31));
        app.closePeriod.close(period.id().value());
        assertThat(app.reload(period).status()).isEqualTo(PeriodStatus.CLOSED);
    }

    /** 記帳と締めが同時に走っても、締めた期に仕訳は入らない(記帳時の version と保存時の version が違う)。 */
    @Test
    void journalPostedBeforeClosingCannotBeSavedAfterClosing() {
        Journal late = Journal.of(JournalId.newId(), LocalDate.of(2026, 5, 1),
                "締めと競合した記帳", JournalType.NORMAL,
                List.of(JournalLine.debit(StandardAccounts.CASH, Money.ofYen(1_000)),
                        JournalLine.credit(StandardAccounts.SALES, Money.ofYen(1_000))));
        PostedJournal postedWhileOpen = PostedJournal.post(period, late);

        app.closePeriod.close(period.id().value());

        assertThatThrownBy(() -> app.journals.save(postedWhileOpen)).isInstanceOf(ConcurrencyConflictException.class);
        assertThat(app.journals.findById(late.id())).isEmpty();
    }

    /**
     * 再振替: 前期末に手入力した経過勘定(未払家賃)を、次期の期首日付で戻す。
     * 期首の日付で、借方・貸方が入れ替わった REVERSING 仕訳になる。
     */
    @Test
    void reverseAccruals_reversesPreviousAdjustingJournalsOnTheFirstDay() {
        app.postAdjusting(LocalDate.of(2027, 3, 31), "家賃の未払計上",
                dr(StandardAccounts.RENT_EXPENSE, 10_000), cr(StandardAccounts.ACCRUED_EXPENSE, 10_000));
        AccountingPeriod next = app.periods.findById(app.closePeriod.close(period.id().value()).nextPeriodId())
                .orElseThrow();

        app.reverseAccruals.reverse(next.id().value());

        List<Journal> reversing = app.journalsOf(next).stream()
                .filter(journal -> journal.type() == JournalType.REVERSING).toList();
        assertThat(reversing).singleElement().satisfies(journal -> {
            assertThat(journal.date()).isEqualTo(next.startDate());
            assertThat(journal.lines()).containsExactly(
                    JournalLine.credit(StandardAccounts.RENT_EXPENSE, Money.ofYen(10_000)),
                    JournalLine.debit(StandardAccounts.ACCRUED_EXPENSE, Money.ofYen(10_000)));
        });
        // 期首の未払費用は開始仕訳と再振替で相殺される
        assertThat(Ledger.of(StandardAccounts.ACCRUED_EXPENSE, app.journalsOf(next)).balance()).isEqualTo(Money.ZERO);
    }

    @Test
    void reverseAccruals_cannotRunTwice() {
        app.postAdjusting(LocalDate.of(2027, 3, 31), "家賃の未払計上",
                dr(StandardAccounts.RENT_EXPENSE, 10_000), cr(StandardAccounts.ACCRUED_EXPENSE, 10_000));
        var next = app.closePeriod.close(period.id().value()).nextPeriodId().value();
        app.reverseAccruals.reverse(next);

        assertThatThrownBy(() -> app.reverseAccruals.reverse(next)).isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void reverseAccruals_requiresThePreviousPeriodToBeClosed() {
        AccountingPeriod next = period.nextPeriod();
        app.periods.save(next);

        assertThatThrownBy(() -> app.reverseAccruals.reverse(next.id().value()))
                .isInstanceOf(OperationNotAllowedException.class);
    }
}
