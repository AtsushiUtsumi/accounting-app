package com.example.accountingapp.application.journal;

import com.example.accounting.exception.AccountingException;
import com.example.accounting.model.account.AccountCategory;
import com.example.accounting.model.account.AccountType;
import com.example.accounting.model.account.Side;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.statement.Ledger;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.JournalLineCommand;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.support.SampleShop;
import com.example.accountingapp.support.TestAccounting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.example.accountingapp.support.TestAccounting.cr;
import static com.example.accountingapp.support.TestAccounting.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalUseCaseTest {

    private final TestAccounting app = new TestAccounting();
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        period = app.setUpCompany(SampleShop.PERIOD_START);
    }

    @Test
    void post_savesTheJournalToThePeriodContainingItsDate() {
        JournalId id = app.post(LocalDate.of(2026, 4, 1), "出資による開業",
                dr(StandardAccounts.CASH, 600_000), cr(StandardAccounts.CAPITAL_STOCK, 600_000));

        assertThat(app.journals.findById(id)).hasValueSatisfying(posted -> {
            assertThat(posted.periodId()).isEqualTo(period.id());
            assertThat(posted.journal().lines()).containsExactly(
                    JournalLine.debit(StandardAccounts.CASH, Money.ofYen(600_000)),
                    JournalLine.credit(StandardAccounts.CAPITAL_STOCK, Money.ofYen(600_000)));
        });
    }

    @Test
    void post_unbalancedJournalIsRejectedByTheDomain() {
        assertThatThrownBy(() -> app.post(LocalDate.of(2026, 4, 1), "貸借不一致",
                dr(StandardAccounts.CASH, 600_000), cr(StandardAccounts.CAPITAL_STOCK, 500_000)))
                .isInstanceOf(AccountingException.class);
        assertThat(app.journalsOf(period)).isEmpty();
    }

    @Test
    void post_dateOutsideEveryPeriodIsNotFound() {
        assertThatThrownBy(() -> app.post(LocalDate.of(2030, 1, 1), "期がない",
                dr(StandardAccounts.CASH, 1_000), cr(StandardAccounts.SALES, 1_000)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void post_unknownAccountIsNotFound() {
        assertThatThrownBy(() -> app.post(LocalDate.of(2026, 4, 1), "科目がない",
                new JournalLineCommand("999", Side.DEBIT, 1_000), cr(StandardAccounts.SALES, 1_000)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void post_deactivatedAccountIsRejectedByTheDomain() {
        app.accountManagement.add("801", "旧科目", AccountType.EXPENSE, AccountCategory.SG_AND_A, Set.of());
        app.accountManagement.update("801", null, null, true);

        assertThatThrownBy(() -> app.post(LocalDate.of(2026, 4, 1), "無効な科目",
                new JournalLineCommand("801", Side.DEBIT, 1_000), cr(StandardAccounts.CASH, 1_000)))
                .isInstanceOf(AccountingException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void post_onlyNormalAndAdjustingAreAccepted() {
        assertThatThrownBy(() -> app.postJournal.post(new PostJournalCommand(
                LocalDate.of(2027, 3, 31), "手入力の損益振替", JournalType.CLOSING,
                List.of(dr(StandardAccounts.SALES, 1_000), cr(StandardAccounts.PROFIT_AND_LOSS, 1_000)))))
                .isInstanceOf(InvalidCommandException.class);
    }

    @Test
    void post_closedPeriodIsRejectedByTheDomain() {
        SampleShop.postAprilJournals(app);
        app.closePeriod.close(period.id().value());

        // 期は残っているが CLOSED なので、PostedJournal.post が拒否する
        assertThatThrownBy(() -> app.post(LocalDate.of(2026, 5, 1), "締めた期への記帳",
                dr(StandardAccounts.CASH, 1_000), cr(StandardAccounts.SALES, 1_000)))
                .isInstanceOf(AccountingException.class)
                .hasMessageContaining("closed");
    }

    /** 赤黒訂正: 売掛金の回収 180,000円を、誤って売上で記帳した仕訳を訂正する。 */
    @Test
    void correct_postsReversalAndCorrectJournalTogether() {
        JournalId wrong = app.post(LocalDate.of(2026, 4, 25), "売掛金の現金回収(科目誤り)",
                dr(StandardAccounts.CASH, 180_000), cr(StandardAccounts.SALES, 180_000));

        CorrectJournalUseCase.Result result = app.correctJournal.correct(new CorrectJournalCommand(
                wrong, LocalDate.of(2026, 4, 30), Optional.of(new CorrectJournalCommand.CorrectedJournal(
                "売掛金の現金回収", JournalType.NORMAL,
                List.of(dr(StandardAccounts.CASH, 180_000), cr(StandardAccounts.ACCOUNTS_RECEIVABLE, 180_000))))));

        Journal reversal = app.journals.findById(result.reversalId()).orElseThrow().journal();
        assertThat(reversal.type()).isEqualTo(JournalType.REVERSAL);
        assertThat(reversal.reversalOf()).isEqualTo(wrong);
        List<Journal> all = app.journalsOf(period);
        assertThat(Ledger.of(StandardAccounts.SALES, all).balance()).isEqualTo(Money.ZERO);
        assertThat(Ledger.of(StandardAccounts.ACCOUNTS_RECEIVABLE, all).balance()).isEqualTo(Money.ofYen(-180_000));
        assertThat(Ledger.of(StandardAccounts.CASH, all).balance()).isEqualTo(Money.ofYen(180_000));
    }

    @Test
    void correct_theSameJournalCannotBeReversedTwice() {
        JournalId wrong = app.post(LocalDate.of(2026, 4, 25), "二重に取り消す仕訳",
                dr(StandardAccounts.CASH, 1_000), cr(StandardAccounts.SALES, 1_000));
        app.correctJournal.correct(new CorrectJournalCommand(wrong, LocalDate.of(2026, 4, 30), Optional.empty()));

        assertThatThrownBy(() -> app.correctJournal.correct(
                new CorrectJournalCommand(wrong, LocalDate.of(2026, 4, 30), Optional.empty())))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void correct_journalOfAClosedPeriodIsCorrectedInTheCurrentPeriod() {
        JournalId wrong = app.post(LocalDate.of(2026, 4, 25), "前期の誤り",
                dr(StandardAccounts.CASH, 1_000), cr(StandardAccounts.SALES, 1_000));
        app.closePeriod.close(period.id().value());
        AccountingPeriod next = app.periods.findLatest().orElseThrow();

        CorrectJournalUseCase.Result result = app.correctJournal.correct(
                new CorrectJournalCommand(wrong, next.startDate(), Optional.empty()));

        assertThat(app.journals.findById(result.reversalId()).orElseThrow().periodId()).isEqualTo(next.id());
    }
}
