package com.example.accountingapp.presentation.inventory;

import com.example.accounting.model.inventory.InventoryLedgerEntry;
import com.example.accounting.model.inventory.InventoryLot;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.inventory.InventoryUseCase;
import com.example.accountingapp.application.inventory.InventoryUseCase.ValuationMethod;
import com.example.accountingapp.presentation.IdsResponse;
import com.example.accountingapp.presentation.Today;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 商品有高帳。受入・払出の明細を追記し、払出単価はライブラリの評価方法が決める。 */
@RestController
@RequestMapping("/inventory/{itemCode}")
public class InventoryController {

    private final InventoryUseCase useCase;
    private final Today today;

    public InventoryController(InventoryUseCase useCase, Today today) {
        this.useCase = useCase;
        this.today = today;
    }

    public record LotResponse(int quantity, long unitCost, long amount) {

        static List<LotResponse> listOf(List<InventoryLot> lots) {
            return lots.stream()
                    .map(lot -> new LotResponse(lot.quantity(), lot.unitCost().amountInYen(), lot.amount().amountInYen()))
                    .toList();
        }
    }

    public record LedgerEntryResponse(LocalDate date, String description, List<LotResponse> receipts,
                                      List<LotResponse> issues, List<LotResponse> balance,
                                      int balanceQuantity, long balanceAmount) {

        static LedgerEntryResponse of(InventoryLedgerEntry entry) {
            return new LedgerEntryResponse(entry.date(), entry.description(), LotResponse.listOf(entry.receipts()),
                    LotResponse.listOf(entry.issues()), LotResponse.listOf(entry.balance()),
                    entry.balanceQuantity(), entry.balanceAmount().amountInYen());
        }
    }

    /** 商品有高帳。{@code method} は先入先出法(FIFO)か移動平均法(MOVING_AVERAGE)。 */
    @GetMapping("/movements")
    public List<LedgerEntryResponse> ledger(@PathVariable String itemCode,
                                            @RequestParam(defaultValue = "FIFO") ValuationMethod method) {
        return useCase.ledger(itemCode, method).stream().map(LedgerEntryResponse::of).toList();
    }

    public enum MovementKind {
        RECEIPT, ISSUE
    }

    /** 受入なら単価が必要。払出の単価は評価方法が決める(単価は受け付けない)。 */
    public record MovementRequest(@NotNull MovementKind kind, LocalDate date, @NotBlank String description,
                                  @Positive int quantity, @Positive Long unitCost, ValuationMethod method) {
    }

    @PostMapping("/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public void record(@PathVariable String itemCode, @Valid @RequestBody MovementRequest r) {
        LocalDate date = today.orToday(r.date());
        switch (r.kind()) {
            case RECEIPT -> {
                if (r.unitCost() == null) {
                    throw new InvalidCommandException("unitCost is required for a receipt");
                }
                useCase.recordReceipt(itemCode, date, r.description(), r.quantity(), r.unitCost());
            }
            case ISSUE -> useCase.recordIssue(itemCode, date, r.description(), r.quantity(),
                    r.method() == null ? ValuationMethod.FIFO : r.method());
        }
    }

    /** 売上原価対立法の販売。払出を記録し、売上と売上原価の仕訳を同時に記帳する。 */
    public record SaleRequest(LocalDate date, @NotBlank String description, @Positive int quantity,
                              ValuationMethod method, @NotBlank String receivableAccountCode,
                              @Positive long salesAmount) {
    }

    /** 売上の返品。{@code unitCost} は払い出したときの単価(商品有高帳の払出欄で確認できる)。 */
    public record SalesReturnRequest(LocalDate date, @NotBlank String description, @Positive int quantity,
                                     @Positive long unitCost, @NotBlank String receivableAccountCode,
                                     @Positive long salesAmount) {
    }

    @PostMapping("/sales-returns")
    @ResponseStatus(HttpStatus.CREATED)
    public IdsResponse salesReturn(@PathVariable String itemCode, @Valid @RequestBody SalesReturnRequest r) {
        return IdsResponse.of(List.of(useCase.recordSalesReturn(itemCode, today.orToday(r.date()), r.description(),
                r.quantity(), r.unitCost(), r.receivableAccountCode(), r.salesAmount())));
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    public IdsResponse sell(@PathVariable String itemCode, @Valid @RequestBody SaleRequest r) {
        return IdsResponse.of(List.of(useCase.recordSale(itemCode, today.orToday(r.date()), r.description(),
                r.quantity(), r.method() == null ? ValuationMethod.FIFO : r.method(), r.receivableAccountCode(),
                r.salesAmount())));
    }
}
