package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.Side;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.PeriodStatus;
import com.example.accounting.model.statement.BalanceSheet;
import com.example.accounting.model.statement.Ledger;
import com.example.accounting.model.statement.TrialBalance;
import com.example.accountingapp.application.JournalLineCommand;
import com.example.accountingapp.application.closing.AdjustingEntriesCommand;
import com.example.accountingapp.application.closing.ClosePeriodUseCase;
import com.example.accountingapp.application.closing.RunAdjustingEntriesUseCase;
import com.example.accountingapp.application.journal.PostJournalCommand;
import com.example.accountingapp.application.journal.PostJournalUseCase;
import com.example.accountingapp.application.master.CompanySetupUseCase;
import com.example.accountingapp.application.OperationNotAllowedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 締めの自己検証(収益・費用の残高がゼロ、資産 = 負債 + 純資産、期末残高 = 次期の期首残高、
 * 当期純利益 = 繰越利益剰余金の増加額)を、DB を通したあとでも確認する(CLAUDE.md §10)。
 * 例題はサンプル商店(ドメインの ClosingServiceTest と同じ)。
 */
class ClosingThroughDatabaseTest extends PostgresTestSupport {

    @Autowired
    CompanySetupUseCase companySetup;
    @Autowired
    PostJournalUseCase postJournal;
    @Autowired
    RunAdjustingEntriesUseCase runAdjustingEntries;
    @Autowired
    ClosePeriodUseCase closePeriod;
    @Autowired
    JdbcJournalRepository journals;
    @Autowired
    JdbcAccountingPeriodRepository periods;

    private void post(int month, int day, String description, Account debit, Account credit, long amount) {
        postJournal.post(new PostJournalCommand(LocalDate.of(2026, month, day), description, JournalType.NORMAL, List.of(
                new JournalLineCommand(debit.code().value(), Side.DEBIT, amount),
                new JournalLineCommand(credit.code().value(), Side.CREDIT, amount))));
    }

    private AccountingPeriod postSampleShop() {
        AccountingPeriod period = companySetup.setUp(LocalDate.of(2026, 4, 1));
        post(4, 1, "出資による開業", StandardAccounts.CASH, StandardAccounts.CAPITAL_STOCK, 600_000);
        post(4, 5, "銀行からの借入", StandardAccounts.CASH, StandardAccounts.LOANS_PAYABLE, 200_000);
        post(4, 10, "商品の掛け仕入", StandardAccounts.PURCHASES, StandardAccounts.ACCOUNTS_PAYABLE, 300_000);
        post(4, 15, "商品の掛け売上", StandardAccounts.ACCOUNTS_RECEIVABLE, StandardAccounts.SALES, 280_000);
        post(4, 20, "買掛金の現金支払", StandardAccounts.ACCOUNTS_PAYABLE, StandardAccounts.CASH, 150_000);
        post(4, 25, "売掛金の現金回収", StandardAccounts.CASH, StandardAccounts.ACCOUNTS_RECEIVABLE, 180_000);
        return period;
    }

    @Test
    void selfChecksHoldAfterSavingAndReloading() {
        AccountingPeriod period = postSampleShop();

        ClosePeriodUseCase.Result result = closePeriod.close(period.id().value());

        AccountingPeriod closed = periods.findById(period.id()).orElseThrow();
        AccountingPeriod next = periods.findById(result.nextPeriodId()).orElseThrow();
        List<Journal> closedJournals = PostedJournal.journalsOf(journals.findByPeriod(closed));
        List<Journal> nextJournals = PostedJournal.journalsOf(journals.findByPeriod(next));

        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(result.netIncome()).isEqualTo(Money.ofYen(-20_000));

        assertThat(Ledger.of(StandardAccounts.SALES, closedJournals).balance()).isEqualTo(Money.ZERO);
        assertThat(Ledger.of(StandardAccounts.PURCHASES, closedJournals).balance()).isEqualTo(Money.ZERO);

        BalanceSheet balanceSheet = BalanceSheet.of(closedJournals);
        assertThat(balanceSheet.isBalanced()).isTrue();
        assertThat(balanceSheet.totalAssets()).isEqualTo(Money.ofYen(930_000));
        assertThat(balanceSheet.totalLiabilities()).isEqualTo(Money.ofYen(350_000));
        assertThat(balanceSheet.totalEquity()).isEqualTo(Money.ofYen(580_000));

        TrialBalance endOfPeriod = TrialBalance.of(closedJournals);
        for (Account account : List.of(
                StandardAccounts.CASH, StandardAccounts.ACCOUNTS_RECEIVABLE, StandardAccounts.ACCOUNTS_PAYABLE,
                StandardAccounts.LOANS_PAYABLE, StandardAccounts.CAPITAL_STOCK, StandardAccounts.RETAINED_EARNINGS)) {
            assertThat(Ledger.of(account, nextJournals).balance())
                    .as("opening balance of %s", account.name())
                    .isEqualTo(endOfPeriod.rows().get(account).netBalance(account.type()));
        }

        assertThat(Ledger.of(StandardAccounts.RETAINED_EARNINGS, closedJournals).balance())
                .isEqualTo(result.netIncome());
    }

    /** 決算整理の二重実行の防止は、DB の監査列(作成したユースケース)で判定する。 */
    @Test
    void adjustingEntriesCannotRunTwiceThroughTheDatabase() {
        AccountingPeriod period = postSampleShop();
        AdjustingEntriesCommand command = new AdjustingEntriesCommand(false, null, null, 0L, 50_000L, null, null,
                null, null, null, null, null, null, false, true);

        runAdjustingEntries.run(period.id().value(), command);

        assertThatThrownBy(() -> runAdjustingEntries.run(period.id().value(), command))
                .isInstanceOf(OperationNotAllowedException.class);
        String origin = jdbc.sql("select distinct created_by_use_case from journal where type = 'ADJUSTING'")
                .query(String.class).single();
        assertThat(origin).isEqualTo(RunAdjustingEntriesUseCase.NAME);
    }
}
