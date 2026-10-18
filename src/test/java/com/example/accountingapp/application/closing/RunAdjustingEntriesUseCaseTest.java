package com.example.accountingapp.application.closing;

import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.journal.CorrectJournalCommand;
import com.example.accountingapp.support.TestAccounting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.accountingapp.support.TestAccounting.cr;
import static com.example.accountingapp.support.TestAccounting.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 決算整理。金額は、ドメインの AdjustingEntryServiceTest と同じ例題:
 * <ul>
 *   <li>売上原価(三分法): 期首商品棚卸高80,000円、期末商品棚卸高50,000円</li>
 *   <li>貸倒引当金(差額補充法): 必要額20,000円、現在残高5,000円 → 15,000円繰入</li>
 *   <li>法人税等: 確定額120,000円、仮払法人税等60,000円 → 未払法人税等60,000円</li>
 *   <li>消費税(税抜方式): 仮受消費税80,000円、仮払消費税50,000円 → 未払消費税30,000円</li>
 * </ul>
 */
class RunAdjustingEntriesUseCaseTest {

    private static final LocalDate END = LocalDate.of(2027, 3, 31);

    private final TestAccounting app = new TestAccounting();
    private AccountingPeriod period;
    private UUID periodId;

    @BeforeEach
    void setUp() {
        period = app.setUpCompany(LocalDate.of(2026, 4, 1));
        periodId = period.id().value();
        app.post(LocalDate.of(2026, 4, 1), "前期繰越の商品",
                dr(StandardAccounts.MERCHANDISE_INVENTORY, 80_000), cr(StandardAccounts.CASH, 80_000));
        app.post(LocalDate.of(2026, 5, 1), "前期設定の貸倒引当金",
                dr(StandardAccounts.BAD_DEBT_EXPENSE, 5_000), cr(StandardAccounts.ALLOWANCE_FOR_DOUBTFUL_ACCOUNTS, 5_000));
        app.post(LocalDate.of(2026, 11, 30), "法人税等の中間納付",
                dr(StandardAccounts.PREPAID_CORPORATE_TAX, 60_000), cr(StandardAccounts.CASH, 60_000));
        app.post(LocalDate.of(2026, 12, 1), "税込の売上",
                dr(StandardAccounts.CASH, 880_000), cr(StandardAccounts.SALES, 800_000),
                cr(StandardAccounts.CONSUMPTION_TAX_RECEIVED, 80_000));
        app.post(LocalDate.of(2026, 12, 2), "税込の仕入",
                dr(StandardAccounts.PURCHASES, 500_000), dr(StandardAccounts.CONSUMPTION_TAX_PAID, 50_000),
                cr(StandardAccounts.CASH, 550_000));
    }

    private static AdjustingEntriesCommand command(boolean accrualsConfirmed) {
        return new AdjustingEntriesCommand(
                false, null, AdjustingEntriesCommand.InventoryMethod.PERIODIC, 80_000L, 50_000L, List.of(), null,
                null, List.of(), 20_000L, List.of(), null, 120_000L, true, accrualsConfirmed);
    }

    @Test
    void run_generatesJournalsInTheDocumentedOrderAndSavesThemAll() {
        List<JournalId> ids = app.runAdjustingEntries.run(periodId, command(true));

        List<Journal> generated = ids.stream().map(id -> app.journals.findById(id).orElseThrow().journal()).toList();
        assertThat(generated).allSatisfy(journal -> {
            assertThat(journal.type()).isEqualTo(JournalType.ADJUSTING);
            assertThat(journal.date()).isEqualTo(END);
        });
        assertThat(generated).extracting(Journal::lines).containsExactly(
                // 3. 売上原価
                List.of(JournalLine.debit(StandardAccounts.PURCHASES, yen(80_000)),
                        JournalLine.credit(StandardAccounts.MERCHANDISE_INVENTORY, yen(80_000))),
                List.of(JournalLine.debit(StandardAccounts.MERCHANDISE_INVENTORY, yen(50_000)),
                        JournalLine.credit(StandardAccounts.PURCHASES, yen(50_000))),
                // 6. 貸倒引当金
                List.of(JournalLine.debit(StandardAccounts.BAD_DEBT_EXPENSE, yen(15_000)),
                        JournalLine.credit(StandardAccounts.ALLOWANCE_FOR_DOUBTFUL_ACCOUNTS, yen(15_000))),
                // 8. 法人税等 → 消費税
                List.of(JournalLine.debit(StandardAccounts.CORPORATE_TAX_EXPENSE, yen(120_000)),
                        JournalLine.credit(StandardAccounts.PREPAID_CORPORATE_TAX, yen(60_000)),
                        JournalLine.credit(StandardAccounts.ACCRUED_CORPORATE_TAX, yen(60_000))),
                List.of(JournalLine.debit(StandardAccounts.CONSUMPTION_TAX_RECEIVED, yen(80_000)),
                        JournalLine.credit(StandardAccounts.CONSUMPTION_TAX_PAID, yen(50_000)),
                        JournalLine.credit(StandardAccounts.CONSUMPTION_TAX_PAYABLE, yen(30_000))));
    }

    @Test
    void preview_doesNotSaveAndMatchesTheConfirmedResult() {
        List<Journal> preview = app.runAdjustingEntries.preview(periodId, command(false));
        assertThat(app.journalsOf(period)).hasSize(5);

        List<JournalId> ids = app.runAdjustingEntries.run(periodId, command(true));
        List<Journal> confirmed = ids.stream().map(id -> app.journals.findById(id).orElseThrow().journal()).toList();

        assertThat(confirmed).extracting(Journal::description, Journal::type, Journal::date, Journal::lines)
                .containsExactlyElementsOf(preview.stream()
                        .map(j -> org.assertj.core.groups.Tuple.tuple(j.description(), j.type(), j.date(), j.lines()))
                        .toList());
    }

    @Test
    void run_requiresConfirmationThatAccrualsArePosted() {
        assertThatThrownBy(() -> app.runAdjustingEntries.run(periodId, command(false)))
                .isInstanceOf(InvalidCommandException.class);
    }

    @Test
    void run_cannotRunTwiceUntilTheGeneratedJournalsAreReversed() {
        List<JournalId> first = app.runAdjustingEntries.run(periodId, command(true));

        assertThatThrownBy(() -> app.runAdjustingEntries.run(periodId, command(true)))
                .isInstanceOf(OperationNotAllowedException.class);
        assertThatThrownBy(() -> app.runAdjustingEntries.preview(periodId, command(true)))
                .isInstanceOf(OperationNotAllowedException.class);

        for (JournalId id : first) {
            app.correctJournal.correct(new CorrectJournalCommand(id, END, Optional.empty()));
        }
        assertThat(app.runAdjustingEntries.run(periodId, command(true))).hasSameSizeAs(first);
    }

    /** 手入力の ADJUSTING(経過勘定など)は、決算整理の実行を妨げない。 */
    @Test
    void manualAdjustingJournalsDoNotBlockTheRun() {
        app.postAdjusting(END, "家賃の未払計上",
                dr(StandardAccounts.RENT_EXPENSE, 10_000), cr(StandardAccounts.ACCRUED_EXPENSE, 10_000));

        assertThat(app.runAdjustingEntries.run(periodId, command(true))).hasSize(5);
    }

    private static Money yen(long amount) {
        return Money.ofYen(amount);
    }
}
