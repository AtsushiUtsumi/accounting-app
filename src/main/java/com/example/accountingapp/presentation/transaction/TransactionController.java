package com.example.accountingapp.presentation.transaction;

import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.statement.BankReconciliation;
import com.example.accounting.service.ProvisionType;
import com.example.accountingapp.application.transaction.BankReconciliationUseCase;
import com.example.accountingapp.application.transaction.TransactionUseCase;
import com.example.accountingapp.presentation.IdsResponse;
import com.example.accountingapp.presentation.JournalLineRequest;
import com.example.accountingapp.presentation.Today;
import com.example.accountingapp.presentation.journal.JournalResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 個別取引(CLAUDE.md §6.3 の表ごとに1つ)。金額などの事実は利用者が入力し、仕訳はライブラリが作る。
 * 日付を省略すると今日。レスポンスは記帳した仕訳の ID(仕訳が不要だったときは空)。
 */
@RestController
@ResponseStatus(HttpStatus.CREATED)
public class TransactionController {

    private final TransactionUseCase useCase;
    private final BankReconciliationUseCase bankReconciliationUseCase;
    private final Today today;

    public TransactionController(TransactionUseCase useCase, BankReconciliationUseCase bankReconciliationUseCase,
                                 Today today) {
        this.useCase = useCase;
        this.bankReconciliationUseCase = bankReconciliationUseCase;
        this.today = today;
    }

    // ---- 貸倒れ・償却債権の回収 ----

    public record BadDebtRequest(LocalDate date, @NotBlank String receivableAccountCode, @Positive long amount,
                                 boolean arisingInCurrentPeriod) {
    }

    @PostMapping("/transactions/bad-debts")
    public IdsResponse writeOffBadDebt(@Valid @RequestBody BadDebtRequest r) {
        return one(useCase.writeOffBadDebt(today.orToday(r.date()), r.receivableAccountCode(), r.amount(),
                r.arisingInCurrentPeriod()));
    }

    public record RecoveryRequest(LocalDate date, @NotBlank String proceedsAccountCode, @Positive long amount) {
    }

    @PostMapping("/transactions/bad-debt-recoveries")
    public IdsResponse recoverWrittenOff(@Valid @RequestBody RecoveryRequest r) {
        return one(useCase.recoverWrittenOff(today.orToday(r.date()), r.proceedsAccountCode(), r.amount()));
    }

    // ---- 端数利息付きの債券購入 ----

    public record BondPurchaseRequest(LocalDate date, @NotBlank String securitiesAccountCode, @Positive long faceValue,
                                      @NotNull @PositiveOrZero BigDecimal annualRate, @NotNull LocalDate lastInterestDate,
                                      @Positive long purchasePrice, @NotBlank String paymentAccountCode) {
    }

    @PostMapping("/transactions/bond-purchases")
    public IdsResponse purchaseBond(@Valid @RequestBody BondPurchaseRequest r) {
        return one(useCase.purchaseBondWithAccruedInterest(today.orToday(r.date()), r.securitiesAccountCode(),
                r.faceValue(), r.annualRate(), r.lastInterestDate(), r.purchasePrice(), r.paymentAccountCode()));
    }

    // ---- 現金過不足 ----

    public record CashCountRequest(LocalDate date, @PositiveOrZero long actualCashOnHand) {
    }

    @PostMapping("/transactions/cash-counts")
    public IdsResponse recordCashDiscrepancy(@Valid @RequestBody CashCountRequest r) {
        return optional(useCase.recordCashDiscrepancy(today.orToday(r.date()), r.actualCashOnHand()));
    }

    public record DateRequest(LocalDate date) {
    }

    /** 決算時に、原因不明の現金過不足を雑損・雑収入へ振り替える(締めの前に必要)。 */
    @PostMapping("/transactions/cash-over-short-settlements")
    public IdsResponse settleCashOverShort(@RequestBody DateRequest r) {
        return optional(useCase.settleCashOverShort(today.orToday(r.date())));
    }

    // ---- 剰余金の配当 ----

    public record DividendRequest(LocalDate date, @Positive long dividendAmount,
                                  @NotNull TransactionUseCase.DividendSource source) {
    }

    @PostMapping("/transactions/dividends")
    public IdsResponse declareDividends(@Valid @RequestBody DividendRequest r) {
        return one(useCase.declareDividends(today.orToday(r.date()), r.dividendAmount(), r.source()));
    }

    // ---- 固定資産の割賦購入・支払 ----

    public record InstallmentPlanRequest(@NotBlank String assetAccountCode, @Positive long cashPrice,
                                         @Positive long totalPayment, @Positive int numberOfInstallments,
                                         @NotBlank String payableAccountCode) {

        TransactionUseCase.InstallmentPlanCommand toCommand() {
            return new TransactionUseCase.InstallmentPlanCommand(
                    assetAccountCode, cashPrice, totalPayment, numberOfInstallments, payableAccountCode);
        }
    }

    public record InstallmentPurchaseRequest(LocalDate date, @NotNull @Valid InstallmentPlanRequest plan) {
    }

    @PostMapping("/transactions/installment-purchases")
    public IdsResponse purchaseInInstallments(@Valid @RequestBody InstallmentPurchaseRequest r) {
        return one(useCase.purchaseInInstallments(today.orToday(r.date()), r.plan().toCommand()));
    }

    public record InstallmentPaymentRequest(LocalDate date, @NotNull @Valid InstallmentPlanRequest plan,
                                            @Positive int installmentNumber, @NotBlank String paymentAccountCode) {
    }

    public record InstallmentScheduleRow(int installmentNumber, long payment, long interest) {
    }

    /** 支払予定表(各回の支払額と利息)。記帳はしない。 */
    @PostMapping("/transactions/installment-schedules")
    @ResponseStatus(HttpStatus.OK)
    public List<InstallmentScheduleRow> installmentSchedule(@Valid @RequestBody InstallmentPlanRequest plan) {
        return useCase.installmentSchedule(plan.toCommand()).stream()
                .map(row -> new InstallmentScheduleRow(row.installmentNumber(), yen(row.payment()), yen(row.interest())))
                .toList();
    }

    @PostMapping("/transactions/installment-payments")
    public IdsResponse payInstallment(@Valid @RequestBody InstallmentPaymentRequest r) {
        return one(useCase.payInstallment(today.orToday(r.date()), r.plan().toCommand(), r.installmentNumber(),
                r.paymentAccountCode()));
    }

    // ---- 吸収合併 ----

    public record MergerRequest(LocalDate date, @NotEmpty List<@Valid JournalLineRequest> acquiredAssets,
                                List<@Valid JournalLineRequest> assumedLiabilities,
                                @NotEmpty List<@Valid JournalLineRequest> consideration) {
    }

    @PostMapping("/transactions/mergers")
    public IdsResponse merge(@Valid @RequestBody MergerRequest r) {
        return one(useCase.merge(today.orToday(r.date()), JournalLineRequest.toCommands(r.acquiredAssets()),
                JournalLineRequest.toCommands(r.assumedLiabilities()), JournalLineRequest.toCommands(r.consideration())));
    }

    // ---- 手形・電子記録債権の割引 ----

    public record NoteDiscountRequest(LocalDate date, @NotBlank String proceedsAccountCode, @Positive long faceValue,
                                      @PositiveOrZero long discountFee, boolean electronicallyRecorded) {
    }

    @PostMapping("/transactions/notes-discounts")
    public IdsResponse discountNote(@Valid @RequestBody NoteDiscountRequest r) {
        return one(useCase.discountNote(today.orToday(r.date()), r.proceedsAccountCode(), r.faceValue(),
                r.discountFee(), r.electronicallyRecorded()));
    }

    // ---- 引当金の取崩し ----

    public record ProvisionUseRequest(LocalDate date, @NotNull ProvisionType type, @Positive long amount,
                                      @NotBlank String paymentAccountCode) {
    }

    @PostMapping("/transactions/provision-uses")
    public IdsResponse useProvision(@Valid @RequestBody ProvisionUseRequest r) {
        return one(useCase.useProvision(today.orToday(r.date()), r.type(), r.amount(), r.paymentAccountCode()));
    }

    // ---- 収益認識 ----

    public record SaleWithRefundRequest(LocalDate date, @NotBlank String receivableAccountCode,
                                        @Positive long totalConsideration, @PositiveOrZero long expectedRefund) {
    }

    @PostMapping("/transactions/sales-with-expected-refund")
    public IdsResponse recordSaleWithExpectedRefund(@Valid @RequestBody SaleWithRefundRequest r) {
        return one(useCase.recordSaleWithExpectedRefund(today.orToday(r.date()), r.receivableAccountCode(),
                r.totalConsideration(), r.expectedRefund()));
    }

    public record ContractAssetRequest(LocalDate date, @Positive long revenue) {
    }

    @PostMapping("/transactions/contract-assets")
    public IdsResponse recognizeContractAsset(@Valid @RequestBody ContractAssetRequest r) {
        return one(useCase.recognizeContractAsset(today.orToday(r.date()), r.revenue()));
    }

    /** {@code contractJournalIds} は、この契約の契約資産を計上した仕訳の ID(契約が1件だけなら省略可)。 */
    public record ContractBillingRequest(LocalDate date, @NotBlank String receivableAccountCode,
                                         @PositiveOrZero long remainingRevenue, Set<UUID> contractJournalIds) {
    }

    @PostMapping("/transactions/contract-billings")
    public IdsResponse billCompletedContract(@Valid @RequestBody ContractBillingRequest r) {
        Set<JournalId> ids = r.contractJournalIds() == null ? Set.of()
                : r.contractJournalIds().stream().map(JournalId::new).collect(Collectors.toSet());
        return one(useCase.billCompletedContract(today.orToday(r.date()), r.receivableAccountCode(),
                r.remainingRevenue(), ids));
    }

    public record ServiceRevenueRequest(LocalDate date, @NotBlank String receivableAccountCode, @Positive long revenue,
                                        @PositiveOrZero long costOfServices) {
    }

    @PostMapping("/transactions/service-revenues")
    public IdsResponse recognizeServiceRevenue(@Valid @RequestBody ServiceRevenueRequest r) {
        return one(useCase.recognizeServiceRevenue(today.orToday(r.date()), r.receivableAccountCode(), r.revenue(),
                r.costOfServices()));
    }

    // ---- 売買目的有価証券の売却 ----

    /** 数量を省略すると全額売却。端数利息は債券の一部売却のときだけ。 */
    public record SecuritiesSaleRequest(LocalDate date, @NotBlank String proceedsAccountCode,
                                        @PositiveOrZero long proceeds, @Positive Integer quantitySold,
                                        @Positive Integer quantityHeld, @PositiveOrZero Long accruedInterest) {
    }

    @PostMapping("/transactions/trading-securities-sales")
    public IdsResponse sellTradingSecurities(@Valid @RequestBody SecuritiesSaleRequest r) {
        return one(useCase.sellTradingSecurities(today.orToday(r.date()), r.proceedsAccountCode(), r.proceeds(),
                r.quantitySold(), r.quantityHeld(), r.accruedInterest()));
    }

    // ---- 銀行勘定調整 ----

    public record BankReconciliationRequest(LocalDate date, @NotBlank String checkingAccountCode,
                                            long bankStatementBalance,
                                            @NotNull List<@Valid ReconcilingItemRequest> items) {
    }

    public record ReconcilingItemRequest(@NotNull BankReconciliationUseCase.ItemKind kind, long amount,
                                         String counterAccountCode) {
    }

    public record BankReconciliationResponse(long bankStatementBalance, long bookBalance, long adjustedBankBalance,
                                             long adjustedBookBalance, boolean reconciles, List<UUID> journalIds) {
    }

    /** 銀行勘定調整表を作り、帳簿側の調整項目だけを修正仕訳として記帳する。 */
    @PostMapping("/bank-reconciliations")
    public BankReconciliationResponse reconcile(@Valid @RequestBody BankReconciliationRequest r) {
        BankReconciliationUseCase.Result result = bankReconciliationUseCase.reconcile(
                today.orToday(r.date()), r.checkingAccountCode(), r.bankStatementBalance(), toItems(r));
        BankReconciliation rec = result.reconciliation();
        return new BankReconciliationResponse(yen(rec.bankStatementBalance()), yen(rec.bookBalance()),
                yen(rec.adjustedBankBalance()), yen(rec.adjustedBookBalance()), rec.reconciles(),
                result.correctingJournalIds().stream().map(JournalId::value).toList());
    }

    public record BankReconciliationPreviewResponse(long bankStatementBalance, long bookBalance,
                                                    long adjustedBankBalance, long adjustedBookBalance,
                                                    boolean reconciles, List<JournalResponse> correctingEntries) {
    }

    /** 銀行勘定調整表のプレビュー。調整表と、記帳される修正仕訳を返す(記帳はしない)。 */
    @PostMapping("/bank-reconciliations/preview")
    @ResponseStatus(HttpStatus.OK)
    public BankReconciliationPreviewResponse previewReconciliation(@Valid @RequestBody BankReconciliationRequest r) {
        BankReconciliationUseCase.Preview preview = bankReconciliationUseCase.preview(
                today.orToday(r.date()), r.checkingAccountCode(), r.bankStatementBalance(), toItems(r));
        BankReconciliation rec = preview.reconciliation();
        return new BankReconciliationPreviewResponse(yen(rec.bankStatementBalance()), yen(rec.bookBalance()),
                yen(rec.adjustedBankBalance()), yen(rec.adjustedBookBalance()), rec.reconciles(),
                preview.correctingEntries().stream().map(JournalResponse::of).toList());
    }

    private static List<BankReconciliationUseCase.ItemCommand> toItems(BankReconciliationRequest r) {
        return r.items().stream().map(item -> new BankReconciliationUseCase.ItemCommand(
                item.kind(), item.amount(), item.counterAccountCode())).toList();
    }

    private static IdsResponse one(JournalId id) {
        return IdsResponse.of(List.of(id));
    }

    private static IdsResponse optional(Optional<JournalId> id) {
        return IdsResponse.of(id.stream().toList());
    }

    private static long yen(Money money) {
        return money.amountInYen();
    }
}
