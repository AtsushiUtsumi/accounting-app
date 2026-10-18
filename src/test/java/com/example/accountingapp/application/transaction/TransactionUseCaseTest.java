package com.example.accountingapp.application.transaction;

import com.example.accounting.exception.AccountingException;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.inventory.InventoryLedgerEntry;
import com.example.accounting.model.inventory.InventoryLot;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.inventory.InventoryUseCase;
import com.example.accountingapp.support.TestAccounting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.example.accountingapp.support.TestAccounting.cr;
import static com.example.accountingapp.support.TestAccounting.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 個別取引と商品有高帳。例題はドメインの各サービスのテストと同じ。 */
class TransactionUseCaseTest {

    private final TestAccounting app = new TestAccounting();
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        period = app.setUpCompany(LocalDate.of(2026, 4, 1));
    }

    /**
     * ドメインの NotesReceivableServiceTest: 売掛金100,000円を約束手形で受け取り、銀行で割引に付し、
     * 割引料2,000円を差し引かれた残額98,000円を現金で受け取る。
     */
    @Test
    void discountNote() {
        app.post(LocalDate.of(2026, 6, 1), "約束手形の受取",
                dr(StandardAccounts.NOTES_RECEIVABLE, 100_000), cr(StandardAccounts.ACCOUNTS_RECEIVABLE, 100_000));

        JournalId id = app.transactions.discountNote(LocalDate.of(2026, 6, 10),
                StandardAccounts.CASH.code().value(), 100_000, 2_000, false);

        Journal journal = app.journals.findById(id).orElseThrow().journal();
        assertThat(journal.lines()).containsExactly(
                JournalLine.debit(StandardAccounts.CASH, Money.ofYen(98_000)),
                JournalLine.debit(StandardAccounts.LOSS_ON_NOTE_DISCOUNTING, Money.ofYen(2_000)),
                JournalLine.credit(StandardAccounts.NOTES_RECEIVABLE, Money.ofYen(100_000)));
    }

    @Test
    void recordCashDiscrepancy_noJournalWhenCashMatches() {
        app.post(LocalDate.of(2026, 4, 1), "出資による開業",
                dr(StandardAccounts.CASH, 600_000), cr(StandardAccounts.CAPITAL_STOCK, 600_000));

        assertThat(app.transactions.recordCashDiscrepancy(LocalDate.of(2026, 4, 30), 600_000)).isEmpty();
        assertThat(app.journalsOf(period)).hasSize(1);
    }

    @Test
    void sellTradingSecurities_withoutHoldingsIsRejectedByTheDomain() {
        assertThatThrownBy(() -> app.transactions.sellTradingSecurities(LocalDate.of(2026, 6, 1),
                StandardAccounts.CASH.code().value(), 1_000, null, null, null))
                .isInstanceOf(AccountingException.class);
        assertThat(app.journalsOf(period)).isEmpty();
    }

    /**
     * ドメインの InventoryValuationMethodTest(先入先出法): 前月繰越10個@100円、仕入30個@120円、売上25個。
     * 売上の払出原価は2,800円、残高は15個@120円。
     */
    @Test
    void inventory_perpetualSaleUsesTheIssueCostFromTheLedger() {
        app.inventory.recordReceipt("A", LocalDate.of(2026, 10, 1), "前月繰越", 10, 100);
        app.inventory.recordReceipt("A", LocalDate.of(2026, 10, 5), "仕入", 30, 120);

        JournalId id = app.inventory.recordSale("A", LocalDate.of(2026, 10, 12), "売上", 25,
                InventoryUseCase.ValuationMethod.FIFO, StandardAccounts.ACCOUNTS_RECEIVABLE.code().value(), 5_000);

        Journal journal = app.journals.findById(id).orElseThrow().journal();
        assertThat(journal.lines()).contains(
                JournalLine.debit(StandardAccounts.COST_OF_GOODS_SOLD, Money.ofYen(2_800)),
                JournalLine.credit(StandardAccounts.MERCHANDISE, Money.ofYen(2_800)));
        List<InventoryLedgerEntry> ledger = app.inventory.ledger("A", InventoryUseCase.ValuationMethod.FIFO);
        assertThat(ledger.getLast().balance()).containsExactly(new InventoryLot(15, Money.ofYen(120)));
    }

    @Test
    void inventory_issueBeyondTheBalanceIsRejectedAndNotRecorded() {
        app.inventory.recordReceipt("A", LocalDate.of(2026, 10, 1), "前月繰越", 10, 100);

        assertThatThrownBy(() -> app.inventory.recordIssue("A", LocalDate.of(2026, 10, 2), "売上", 20,
                InventoryUseCase.ValuationMethod.MOVING_AVERAGE))
                .isInstanceOf(AccountingException.class);
        assertThat(app.inventoryMovements.findByItem("A")).hasSize(1);
    }
}
