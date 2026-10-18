package com.example.accountingapp.application.statement;

import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.statement.BalanceSheet;
import com.example.accounting.model.statement.CashFlowStatement;
import com.example.accounting.model.statement.IncomeStatement;
import com.example.accounting.model.statement.StatementOfChangesInEquity;
import com.example.accounting.model.statement.TrialBalance;
import com.example.accounting.model.statement.Worksheet;
import com.example.accountingapp.support.SampleShop;
import com.example.accountingapp.support.TestAccounting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** サンプル商店(ドメインの TrialBalanceTest・ClosingServiceTest と同じ例題)の照会。 */
class StatementQueryServiceTest {

    private final TestAccounting app = new TestAccounting();
    private UUID periodId;

    @BeforeEach
    void setUp() {
        AccountingPeriod period = app.setUpCompany(SampleShop.PERIOD_START);
        periodId = period.id().value();
        SampleShop.postAprilJournals(app);
    }

    @Test
    void trialBalance_totals() {
        TrialBalance trialBalance = app.statements.trialBalance(periodId, false);

        assertThat(trialBalance.debitTotal()).isEqualTo(Money.ofYen(1_710_000));
        assertThat(trialBalance.creditTotal()).isEqualTo(Money.ofYen(1_710_000));
        assertThat(trialBalance.rows().get(StandardAccounts.CASH).debitTotal()).isEqualTo(Money.ofYen(980_000));
        assertThat(trialBalance.rows().get(StandardAccounts.CASH).creditTotal()).isEqualTo(Money.ofYen(150_000));
    }

    @Test
    void trialBalance_excludesClosingEntriesUnlessRequested() {
        app.closePeriod.close(periodId);

        assertThat(app.statements.trialBalance(periodId, false).debitTotal()).isEqualTo(Money.ofYen(1_710_000));
        assertThat(app.statements.trialBalance(periodId, true).debitTotal()).isNotEqualTo(Money.ofYen(1_710_000));
    }

    @Test
    void statementsOfAClosedPeriod() {
        app.closePeriod.close(periodId);

        BalanceSheet balanceSheet = app.statements.balanceSheet(periodId);
        assertThat(balanceSheet.isBalanced()).isTrue();
        assertThat(balanceSheet.totalAssets()).isEqualTo(Money.ofYen(930_000));
        assertThat(balanceSheet.totalLiabilities()).isEqualTo(Money.ofYen(350_000));
        assertThat(balanceSheet.totalEquity()).isEqualTo(Money.ofYen(580_000));

        // P/L は損益振替を除いて計算する(含めると収益・費用がゼロになる)
        IncomeStatement incomeStatement = app.statements.incomeStatement(periodId);
        assertThat(incomeStatement.netIncome()).isEqualTo(Money.ofYen(-20_000));

        StatementOfChangesInEquity equity = app.statements.equityChanges(periodId);
        assertThat(equity.reconciles()).isTrue();
        assertThat(equity.totalChangeFor(StandardAccounts.RETAINED_EARNINGS)).isEqualTo(Money.ofYen(-20_000));
    }

    @Test
    void worksheet_netIncomeMatchesIncomeStatement() {
        Worksheet worksheet = app.statements.worksheet(periodId);

        assertThat(worksheet.isBalanced()).isTrue();
        assertThat(worksheet.netIncome()).isEqualTo(Money.ofYen(-20_000));
    }

    @Test
    void cashFlow_reconcilesToEndingCashInTheFirstPeriodAndTheNext() {
        CashFlowStatement first = app.statements.cashFlow(periodId);
        assertThat(first.reconcilesToEndingCash()).isTrue();
        assertThat(first.cashAtEndOfPeriod()).isEqualTo(Money.ofYen(830_000));

        UUID next = app.closePeriod.close(periodId).nextPeriodId().value();
        CashFlowStatement second = app.statements.cashFlow(next);
        assertThat(second.cashAtBeginningOfPeriod()).isEqualTo(Money.ofYen(830_000));
        assertThat(second.reconcilesToEndingCash()).isTrue();
    }

    @Test
    void ledger_cash() {
        StatementQueryService.LedgerView ledger = app.statements.ledger(StandardAccounts.CASH.code().value(), periodId);

        assertThat(ledger.ledger().debitTotal()).isEqualTo(Money.ofYen(980_000));
        assertThat(ledger.ledger().creditTotal()).isEqualTo(Money.ofYen(150_000));
        assertThat(ledger.journals()).hasSize(4);
    }
}
