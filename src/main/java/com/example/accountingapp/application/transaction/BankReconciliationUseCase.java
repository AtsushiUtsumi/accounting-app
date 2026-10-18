package com.example.accountingapp.application.transaction;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.statement.BankReconciliation;
import com.example.accounting.model.statement.BankReconciliation.ReconcilingItem;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.port.AuditContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 銀行勘定調整表の作成と、帳簿側の修正仕訳の記帳(CLAUDE.md §6.3)。帳簿残高は当期の仕訳から
 * ライブラリが求め、銀行側の項目は仕訳にならない。
 */
@Service
public class BankReconciliationUseCase {

    public static final String NAME = "BankReconciliation";

    private final JournalBook book;
    private final AccountResolver accounts;

    public BankReconciliationUseCase(JournalBook book, AccountResolver accounts) {
        this.book = book;
        this.accounts = accounts;
    }

    /** 調整項目の種類。銀行側(仕訳しない)と帳簿側(修正仕訳になる)。 */
    public enum ItemKind {
        DEPOSIT_IN_TRANSIT, OUTSTANDING_CHECK, UNCOLLECTED_CHECK,
        UNRECORDED_DEPOSIT, UNRECORDED_WITHDRAWAL, UNISSUED_CHECK, RECORDING_ERROR
    }

    /** 調整項目。帳簿側の項目には相手科目が必要。誤記入の金額は帳簿残高の訂正額(負なら減らす)。 */
    public record ItemCommand(ItemKind kind, long amount, String counterAccountCode) {
    }

    public record Result(BankReconciliation reconciliation, List<JournalId> correctingJournalIds) {
    }

    /** プレビュー。調整表と、記帳される修正仕訳(記帳前)を返す。保存はしない。 */
    public record Preview(BankReconciliation reconciliation, List<Journal> correctingEntries) {
    }

    @Transactional(readOnly = true)
    public Preview preview(LocalDate date, String checkingAccountCode, long bankStatementBalance,
                           List<ItemCommand> items) {
        BankReconciliation reconciliation = BankReconciliation.of(
                accounts.resolve(checkingAccountCode), Money.ofYen(bankStatementBalance),
                book.journalsOf(book.periodContaining(date)), items.stream().map(this::toItem).toList());
        return new Preview(reconciliation, reconciliation.correctingEntries(date));
    }

    @Transactional
    public Result reconcile(LocalDate date, String checkingAccountCode, long bankStatementBalance,
                            List<ItemCommand> items) {
        return AuditContext.within(NAME, () -> {
            AccountingPeriod period = book.periodContaining(date);
            BankReconciliation reconciliation = BankReconciliation.of(
                    accounts.resolve(checkingAccountCode), Money.ofYen(bankStatementBalance),
                    book.journalsOf(period), items.stream().map(this::toItem).toList());
            List<Journal> correcting = reconciliation.correctingEntries(date);
            book.postAllAndSave(period, correcting);
            return new Result(reconciliation, correcting.stream().map(Journal::id).toList());
        });
    }

    private ReconcilingItem toItem(ItemCommand item) {
        Money amount = Money.ofYen(item.amount());
        Account counter = accounts.resolveOptional(item.counterAccountCode());
        return switch (item.kind()) {
            case DEPOSIT_IN_TRANSIT -> new BankReconciliation.DepositInTransit(amount);
            case OUTSTANDING_CHECK -> new BankReconciliation.OutstandingCheck(amount);
            case UNCOLLECTED_CHECK -> new BankReconciliation.UncollectedCheck(amount);
            case UNRECORDED_DEPOSIT -> new BankReconciliation.UnrecordedDeposit(amount, counter);
            case UNRECORDED_WITHDRAWAL -> new BankReconciliation.UnrecordedWithdrawal(amount, counter);
            case UNISSUED_CHECK -> new BankReconciliation.UnissuedCheck(amount, counter);
            case RECORDING_ERROR -> new BankReconciliation.RecordingError(amount, counter);
        };
    }
}
