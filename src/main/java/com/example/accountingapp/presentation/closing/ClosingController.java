package com.example.accountingapp.presentation.closing;

import com.example.accounting.service.ProvisionType;
import com.example.accountingapp.application.closing.AdjustingEntriesCommand;
import com.example.accountingapp.application.closing.ClosePeriodUseCase;
import com.example.accountingapp.application.closing.ReverseAccrualsUseCase;
import com.example.accountingapp.application.closing.RunAdjustingEntriesUseCase;
import com.example.accountingapp.presentation.IdsResponse;
import com.example.accountingapp.presentation.journal.JournalResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 決算整理(プレビュー・確定)、締め、再振替。どれも承認者の操作。 */
@RestController
@RequestMapping("/periods/{id}")
public class ClosingController {

    private final RunAdjustingEntriesUseCase runAdjustingEntriesUseCase;
    private final ClosePeriodUseCase closePeriodUseCase;
    private final ReverseAccrualsUseCase reverseAccrualsUseCase;

    public ClosingController(RunAdjustingEntriesUseCase runAdjustingEntriesUseCase,
                             ClosePeriodUseCase closePeriodUseCase, ReverseAccrualsUseCase reverseAccrualsUseCase) {
        this.runAdjustingEntriesUseCase = runAdjustingEntriesUseCase;
        this.closePeriodUseCase = closePeriodUseCase;
        this.reverseAccrualsUseCase = reverseAccrualsUseCase;
    }

    /**
     * 決算整理の入力(すべて外部から与える事実)。省略した手順は実行しない。
     * {@code accrualsConfirmed} は「経過勘定・貯蔵品の入力は済みましたか」の確認で、確定のときは true が必要。
     */
    public record AdjustmentsRequest(
            boolean reclassifyBankOverdraft,
            String checkingAccountCode,
            AdjustingEntriesCommand.InventoryMethod inventoryMethod,
            @PositiveOrZero Long beginningInventory,
            @PositiveOrZero Long endingInventory,
            List<@Valid InventoryCountRequest> inventoryCounts,
            Map<UUID, @PositiveOrZero Long> fixedAssetUsages,
            @PositiveOrZero Long tradingSecuritiesFairValue,
            List<@Valid FairValueRequest> availableForSaleSecurities,
            @PositiveOrZero Long allowanceForDoubtfulAccounts,
            List<@Valid ProvisionRequest> provisions,
            Long retirementBenefitExpense,
            @PositiveOrZero Long incomeTaxAmount,
            boolean settleConsumptionTax,
            boolean accrualsConfirmed) {

        AdjustingEntriesCommand toCommand() {
            return new AdjustingEntriesCommand(
                    reclassifyBankOverdraft, checkingAccountCode, inventoryMethod, beginningInventory, endingInventory,
                    inventoryCounts == null ? null : inventoryCounts.stream().map(c ->
                            new AdjustingEntriesCommand.InventoryCountCommand(
                                    c.bookQuantity(), c.actualQuantity(), c.unitCost(), c.netRealizableValue())).toList(),
                    fixedAssetUsages, tradingSecuritiesFairValue,
                    availableForSaleSecurities == null ? null : availableForSaleSecurities.stream().map(v ->
                            new AdjustingEntriesCommand.FairValueCommand(v.accountCode(), v.fairValue())).toList(),
                    allowanceForDoubtfulAccounts,
                    provisions == null ? null : provisions.stream().map(p ->
                            new AdjustingEntriesCommand.ProvisionCommand(p.type(), p.method(), p.requiredBalance())).toList(),
                    retirementBenefitExpense, incomeTaxAmount, settleConsumptionTax, accrualsConfirmed);
        }
    }

    public record InventoryCountRequest(@PositiveOrZero int bookQuantity, @PositiveOrZero int actualQuantity,
                                        @PositiveOrZero long unitCost, @PositiveOrZero long netRealizableValue) {
    }

    public record FairValueRequest(@NotBlank String accountCode, @PositiveOrZero long fairValue) {
    }

    public record ProvisionRequest(@NotNull ProvisionType type, @NotNull AdjustingEntriesCommand.ProvisionMethod method,
                                   @PositiveOrZero long requiredBalance) {
    }

    /** プレビュー。保存せずに、生成される決算整理仕訳を返す。 */
    @PostMapping("/adjustments/preview")
    public List<JournalResponse> preview(@PathVariable UUID id, @Valid @RequestBody AdjustmentsRequest request) {
        return runAdjustingEntriesUseCase.preview(id, request.toCommand()).stream().map(JournalResponse::of).toList();
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public IdsResponse run(@PathVariable UUID id, @Valid @RequestBody AdjustmentsRequest request) {
        return IdsResponse.of(runAdjustingEntriesUseCase.run(id, request.toCommand()));
    }

    public record CloseResponse(UUID nextPeriodId, long netIncome) {
    }

    @PostMapping("/close")
    public CloseResponse close(@PathVariable UUID id) {
        ClosePeriodUseCase.Result result = closePeriodUseCase.close(id);
        return new CloseResponse(result.nextPeriodId().value(), result.netIncome().amountInYen());
    }

    @PostMapping("/reversals")
    @ResponseStatus(HttpStatus.CREATED)
    public IdsResponse reverse(@PathVariable UUID id) {
        return IdsResponse.of(reverseAccrualsUseCase.reverse(id));
    }
}
