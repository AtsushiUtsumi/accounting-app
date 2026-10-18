package com.example.accountingapp.application.inventory;

import com.example.accounting.model.inventory.FifoInventoryValuationMethod;
import com.example.accounting.model.inventory.InventoryLedgerEntry;
import com.example.accounting.model.inventory.InventoryMovement;
import com.example.accounting.model.inventory.InventoryValuationMethod;
import com.example.accounting.model.inventory.MovingAverageInventoryValuationMethod;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.money.Money;
import com.example.accounting.service.PerpetualInventoryService;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.port.AuditContext;
import com.example.accountingapp.application.port.InventoryMovementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品有高帳(CLAUDE.md §6.8)。受入・払出の明細を、仕訳とは別に追記する。払出単価はライブラリの
 * 評価方法(先入先出法・移動平均法)が決める。売上原価対立法の会社では、払出の結果を
 * {@link PerpetualInventoryService#recordSale} に渡して、売上と売上原価の仕訳を同時に作る。
 */
@Service
public class InventoryUseCase {

    public static final String NAME = "Inventory";

    private final InventoryMovementRepository movements;
    private final PerpetualInventoryService perpetualInventoryService;
    private final JournalBook book;
    private final AccountResolver accounts;

    public InventoryUseCase(InventoryMovementRepository movements, PerpetualInventoryService perpetualInventoryService,
                            JournalBook book, AccountResolver accounts) {
        this.movements = movements;
        this.perpetualInventoryService = perpetualInventoryService;
        this.book = book;
        this.accounts = accounts;
    }

    /** 払出単価の決め方。 */
    public enum ValuationMethod {
        FIFO, MOVING_AVERAGE;

        InventoryValuationMethod toDomain() {
            return this == FIFO ? FifoInventoryValuationMethod.of() : MovingAverageInventoryValuationMethod.of();
        }
    }

    @Transactional
    public void recordReceipt(String itemCode, LocalDate date, String description, int quantity, long unitCost) {
        movements.append(itemCode, new InventoryMovement.Receipt(date, description, quantity, Money.ofYen(unitCost)));
    }

    /** 払出。在庫不足ならライブラリが例外にする(保存しない)。払出の行を返す。 */
    @Transactional
    public InventoryLedgerEntry recordIssue(String itemCode, LocalDate date, String description, int quantity,
                                            ValuationMethod method) {
        InventoryMovement.Issue issue = new InventoryMovement.Issue(date, description, quantity);
        InventoryLedgerEntry entry = valuateWith(itemCode, issue, method);
        movements.append(itemCode, issue);
        return entry;
    }

    /** 売上原価対立法の販売。払出の記録と、売上・売上原価の仕訳の記帳を同じトランザクションで行う。 */
    @Transactional
    public JournalId recordSale(String itemCode, LocalDate date, String description, int quantity,
                                ValuationMethod method, String receivableAccountCode, long salesAmount) {
        return AuditContext.within(NAME, () -> {
            InventoryLedgerEntry issued = recordIssue(itemCode, date, description, quantity, method);
            Journal journal = perpetualInventoryService.recordSale(
                    accounts.resolve(receivableAccountCode), Money.ofYen(salesAmount), issued, date);
            book.postAndSave(book.periodContaining(date), journal);
            return journal.id();
        });
    }

    @Transactional(readOnly = true)
    public List<InventoryLedgerEntry> ledger(String itemCode, ValuationMethod method) {
        return method.toDomain().valuate(movements.findByItem(itemCode));
    }

    private InventoryLedgerEntry valuateWith(String itemCode, InventoryMovement.Issue issue, ValuationMethod method) {
        List<InventoryMovement> all = new ArrayList<>(movements.findByItem(itemCode));
        all.add(issue);
        List<InventoryLedgerEntry> entries = method.toDomain().valuate(all);
        return entries.getLast();
    }
}
