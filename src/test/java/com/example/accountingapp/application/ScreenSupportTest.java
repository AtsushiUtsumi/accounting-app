package com.example.accountingapp.application;

import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.inventory.InventoryUseCase;
import com.example.accountingapp.application.journal.CorrectJournalCommand;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.application.transaction.BankReconciliationUseCase;
import com.example.accountingapp.application.transaction.TransactionUseCase;
import com.example.accountingapp.support.SampleShop;
import com.example.accountingapp.support.TestAccounting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.example.accountingapp.support.TestAccounting.cr;
import static com.example.accountingapp.support.TestAccounting.dr;
import static org.assertj.core.api.Assertions.assertThat;

/** 画面のために足した照会と操作(期の一覧、決算の進み具合、取消済み、返品、支払予定表、銀行勘定調整のプレビュー)。 */
class ScreenSupportTest {

    private final TestAccounting app = new TestAccounting();
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        period = app.setUpCompany(SampleShop.PERIOD_START);
    }

    @Test
    void periodsAreListedNewestFirst() {
        SampleShop.postAprilJournals(app);
        var next = app.closePeriod.close(period.id().value()).nextPeriodId();

        assertThat(app.statements.periods()).extracting(AccountingPeriod::id).containsExactly(next, period.id());
    }

    @Test
    void progressReflectsTheClosingSteps() {
        SampleShop.postAprilJournals(app);
        app.transactions.recordCashDiscrepancy(LocalDate.of(2026, 4, 30), 829_000);

        StatementQueryService.Progress progress = app.statements.progress(period.id().value());

        assertThat(progress.journalCount()).isEqualTo(7);
        assertThat(progress.cashOverShortBalance().isZero()).isFalse();
        assertThat(progress.adjustmentsRun()).isFalse();
        assertThat(progress.accrualsReversed()).isFalse();
        assertThat(progress.previousPeriod()).isEmpty();
    }

    @Test
    void reversedJournalsAreReported() {
        JournalId wrong = app.post(LocalDate.of(2026, 4, 25), "誤り",
                dr(StandardAccounts.CASH, 1_000), cr(StandardAccounts.SALES, 1_000));
        JournalId right = app.post(LocalDate.of(2026, 4, 26), "正しい",
                dr(StandardAccounts.CASH, 2_000), cr(StandardAccounts.SALES, 2_000));
        app.correctJournal.correct(new CorrectJournalCommand(wrong, LocalDate.of(2026, 4, 30), Optional.empty()));

        assertThat(app.statements.reversedJournalIds(period.id().value())).containsExactly(wrong);
        assertThat(app.statements.isReversed(right.value())).isFalse();
    }

    /** 支払予定表の金額はライブラリの InstallmentPlan がそのまま決める(端数は最終回)。 */
    @Test
    void installmentScheduleComesFromTheDomainPlan() {
        var plan = new TransactionUseCase.InstallmentPlanCommand(
                StandardAccounts.EQUIPMENT.code().value(), 300_000, 310_000, 3,
                StandardAccounts.NON_TRADE_NOTES_PAYABLE.code().value());
        var domainPlan = new com.example.accounting.service.InstallmentPurchaseService.InstallmentPlan(
                StandardAccounts.EQUIPMENT, Money.ofYen(300_000), Money.ofYen(310_000), 3,
                StandardAccounts.NON_TRADE_NOTES_PAYABLE);

        List<TransactionUseCase.InstallmentScheduleRow> rows = app.transactions.installmentSchedule(plan);

        assertThat(rows).hasSize(3);
        for (var row : rows) {
            assertThat(row.payment()).isEqualTo(domainPlan.installmentAmount(row.installmentNumber()));
            assertThat(row.interest()).isEqualTo(domainPlan.interestFor(row.installmentNumber()));
        }
    }

    /** 銀行勘定調整のプレビューは記帳しない。 */
    @Test
    void bankReconciliationPreviewDoesNotPost() {
        app.post(LocalDate.of(2026, 4, 1), "当座預金への預入",
                dr(StandardAccounts.CHECKING_ACCOUNT, 100_000), cr(StandardAccounts.CASH, 100_000));

        BankReconciliationUseCase.Preview preview = app.bankReconciliation.preview(LocalDate.of(2026, 4, 30),
                StandardAccounts.CHECKING_ACCOUNT.code().value(), 105_000, List.of(
                        new BankReconciliationUseCase.ItemCommand(BankReconciliationUseCase.ItemKind.UNRECORDED_DEPOSIT,
                                5_000, StandardAccounts.ACCOUNTS_RECEIVABLE.code().value())));

        assertThat(preview.reconciliation().reconciles()).isTrue();
        assertThat(preview.correctingEntries()).hasSize(1);
        assertThat(app.journalsOf(period)).hasSize(1);
    }

    /**
     * 売上の返品: 戻った商品を払出時の単価で受け入れ、売上原価から商品へ戻す。
     * 例題はドメインの InventoryValuationMethodTest(先入先出法)の最初の売上(25個、払出原価2,800円)。
     */
    @Test
    @Tag("unverified")
    void salesReturnReceivesGoodsBackAndPostsTheReturn() {
        app.inventory.recordReceipt("A", LocalDate.of(2026, 10, 1), "前月繰越", 10, 100);
        app.inventory.recordReceipt("A", LocalDate.of(2026, 10, 5), "仕入", 30, 120);
        app.inventory.recordSale("A", LocalDate.of(2026, 10, 12), "売上", 25, InventoryUseCase.ValuationMethod.FIFO,
                StandardAccounts.ACCOUNTS_RECEIVABLE.code().value(), 5_000);

        JournalId id = app.inventory.recordSalesReturn("A", LocalDate.of(2026, 10, 13), "売上戻り", 5, 120,
                StandardAccounts.ACCOUNTS_RECEIVABLE.code().value(), 1_000);

        Journal journal = app.journals.findById(id).orElseThrow().journal();
        assertThat(journal.lines()).contains(
                JournalLine.debit(StandardAccounts.MERCHANDISE, Money.ofYen(600)),
                JournalLine.credit(StandardAccounts.COST_OF_GOODS_SOLD, Money.ofYen(600)));
        assertThat(app.inventory.ledger("A", InventoryUseCase.ValuationMethod.FIFO).getLast().balanceQuantity())
                .isEqualTo(20);
    }
}
