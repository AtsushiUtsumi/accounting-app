package com.example.accountingapp.presentation.mcp.transaction;

import com.example.accounting.model.journal.JournalId;
import com.example.accounting.service.ProvisionType;
import com.example.accountingapp.application.transaction.BankReconciliationUseCase;
import com.example.accountingapp.application.transaction.TransactionUseCase;
import com.example.accountingapp.presentation.IdsResponse;
import com.example.accountingapp.presentation.JournalLineRequest;
import com.example.accountingapp.presentation.Today;
import com.example.accountingapp.presentation.controller.transaction.TransactionController.BankReconciliationPreviewResponse;
import com.example.accountingapp.presentation.controller.transaction.TransactionController.BankReconciliationRequest;
import com.example.accountingapp.presentation.controller.transaction.TransactionController.BankReconciliationResponse;
import com.example.accountingapp.presentation.controller.transaction.TransactionController.InstallmentPlanRequest;
import com.example.accountingapp.presentation.controller.transaction.TransactionController.InstallmentScheduleRow;
import com.example.accountingapp.presentation.controller.transaction.TransactionController.ReconcilingItemRequest;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 個別取引(CLAUDE.md §6.3 の表ごとに1つ)。金額などの事実は利用者が入力し、仕訳はライブラリが作る。
 * 日付を省略すると今日。結果は記帳した仕訳の ID(仕訳が不要だったときは空)。
 */
@Component
@Validated
public class TransactionTools implements McpTools {

    private static final String DATE = "日付(yyyy-MM-dd)。省略すると今日";
    private static final String PLAN = """
            割賦の条件。assetAccountCode(資産の科目コード)、cashPrice(現金正価、円)、totalPayment(支払総額、円)、
            numberOfInstallments(支払回数)、payableAccountCode(未払金などの科目コード)。購入時と各回の支払で同じ条件を渡す。""";
    private static final String LINES = "各行は accountCode(科目コード)、side(DEBIT / CREDIT)、amount(円)";

    private final TransactionUseCase useCase;
    private final BankReconciliationUseCase bankReconciliationUseCase;
    private final Today today;

    public TransactionTools(TransactionUseCase useCase, BankReconciliationUseCase bankReconciliationUseCase,
                            Today today) {
        this.useCase = useCase;
        this.bankReconciliationUseCase = bankReconciliationUseCase;
        this.today = today;
    }

    // ---- 貸倒れ・償却債権の回収 ----

    @Tool(name = "write_off_bad_debt", description = """
            売掛金などの債権の貸倒れを記帳する。前期以前に発生した債権は貸倒引当金で賄い(足りない分は貸倒損失)、
            当期に発生した債権は全額を貸倒損失にする。債権の発生時期はアプリが追跡していないので、arisingInCurrentPeriod で指定する。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse writeOffBadDebt(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "貸し倒れた債権の科目コード") @NotBlank String receivableAccountCode,
            @ToolParam(description = "貸倒額(円)") @Positive long amount,
            @ToolParam(description = "債権が当期に発生したものなら true") boolean arisingInCurrentPeriod) {
        return one(useCase.writeOffBadDebt(today.orToday(date), receivableAccountCode, amount, arisingInCurrentPeriod));
    }

    @Tool(name = "recover_written_off_receivable", description = "前期以前に貸倒れとして処理した債権を回収した(償却債権取立益)。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse recoverWrittenOffReceivable(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "回収した代金を受け取る科目コード") @NotBlank String proceedsAccountCode,
            @ToolParam(description = "回収額(円)") @Positive long amount) {
        return one(useCase.recoverWrittenOff(today.orToday(date), proceedsAccountCode, amount));
    }

    // ---- 端数利息付きの債券購入 ----

    @Tool(name = "purchase_bond_with_accrued_interest", description = """
            利払日の間に債券を購入する。端数利息(前回の利払日の翌日から購入日まで)はライブラリが計算する。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse purchaseBondWithAccruedInterest(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "有価証券の科目コード") @NotBlank String securitiesAccountCode,
            @ToolParam(description = "額面(円)") @Positive long faceValue,
            @ToolParam(description = "年利率(例: 0.073 は 7.3%)") @NotNull @PositiveOrZero BigDecimal annualRate,
            @ToolParam(description = "前回の利払日(yyyy-MM-dd)") @NotNull LocalDate lastInterestDate,
            @ToolParam(description = "購入代価(円。端数利息を含まない)") @Positive long purchasePrice,
            @ToolParam(description = "支払の科目コード") @NotBlank String paymentAccountCode) {
        return one(useCase.purchaseBondWithAccruedInterest(today.orToday(date), securitiesAccountCode, faceValue,
                annualRate, lastInterestDate, purchasePrice, paymentAccountCode));
    }

    // ---- 現金過不足 ----

    @Tool(name = "record_cash_count", description = """
            手許現金の実際有高を入力し、帳簿残高との差を現金過不足として記帳する。一致していれば仕訳は作られない(空を返す)。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse recordCashCount(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "手許現金の実際有高(円)") @PositiveOrZero long actualCashOnHand) {
        return optional(useCase.recordCashDiscrepancy(today.orToday(date), actualCashOnHand));
    }

    @Tool(name = "settle_cash_over_short", description = """
            決算時に、原因不明の現金過不足を雑損・雑収入へ振り替える(締めの前に必要)。残高がなければ仕訳は作られない。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse settleCashOverShort(@ToolParam(description = DATE, required = false) LocalDate date) {
        return optional(useCase.settleCashOverShort(today.orToday(date)));
    }

    // ---- 剰余金の配当 ----

    @Tool(name = "declare_dividends", description = """
            剰余金の配当を決議する。準備金の積立額は、資本金・準備金の残高からライブラリが計算する。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse declareDividends(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "配当金の額(円)") @Positive long dividendAmount,
            @ToolParam(description = "配当の財源。RETAINED_EARNINGS(繰越利益剰余金)か OTHER_CAPITAL_SURPLUS(その他資本剰余金)")
            @NotNull TransactionUseCase.DividendSource source) {
        return one(useCase.declareDividends(today.orToday(date), dividendAmount, source));
    }

    // ---- 固定資産の割賦購入・支払 ----

    @Tool(name = "purchase_in_installments", description = "固定資産を割賦で購入する。利息相当額はライブラリが計算する。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse purchaseInInstallments(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = PLAN) @NotNull @Valid InstallmentPlanRequest plan) {
        return one(useCase.purchaseInInstallments(today.orToday(date), plan.toCommand()));
    }

    @Tool(name = "get_installment_schedule", description = "割賦の支払予定表(各回の支払額と利息)を返す。記帳はしない。")
    @PreAuthorize(BOOKKEEPER)
    public List<InstallmentScheduleRow> getInstallmentSchedule(
            @ToolParam(description = PLAN) @NotNull @Valid InstallmentPlanRequest plan) {
        return InstallmentScheduleRow.listOf(useCase.installmentSchedule(plan.toCommand()));
    }

    @Tool(name = "pay_installment", description = "割賦の第 n 回を支払う。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse payInstallment(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = PLAN) @NotNull @Valid InstallmentPlanRequest plan,
            @ToolParam(description = "支払う回(1から)") @Positive int installmentNumber,
            @ToolParam(description = "支払の科目コード") @NotBlank String paymentAccountCode) {
        return one(useCase.payInstallment(today.orToday(date), plan.toCommand(), installmentNumber, paymentAccountCode));
    }

    // ---- 吸収合併 ----

    @Tool(name = "merge", description = "吸収合併。受け入れた資産・引き受けた負債・交付した対価を渡すと、のれん(負ののれん)はライブラリが求める。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse merge(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "受け入れた資産(時価)。" + LINES) @NotEmpty List<@Valid JournalLineRequest> acquiredAssets,
            @ToolParam(description = "引き受けた負債(時価)。" + LINES, required = false)
            List<@Valid JournalLineRequest> assumedLiabilities,
            @ToolParam(description = "交付した対価(資本金・資本準備金など)。" + LINES)
            @NotEmpty List<@Valid JournalLineRequest> consideration) {
        return one(useCase.merge(today.orToday(date), JournalLineRequest.toCommands(acquiredAssets),
                JournalLineRequest.toCommands(assumedLiabilities), JournalLineRequest.toCommands(consideration)));
    }

    // ---- 手形・電子記録債権の割引 ----

    @Tool(name = "discount_note", description = "受取手形(または電子記録債権)を銀行で割り引く。割引料は手形売却損になる。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse discountNote(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "手取金を受け取る科目コード") @NotBlank String proceedsAccountCode,
            @ToolParam(description = "額面(円)") @Positive long faceValue,
            @ToolParam(description = "割引料(円)") @PositiveOrZero long discountFee,
            @ToolParam(description = "電子記録債権なら true", required = false) Boolean electronicallyRecorded) {
        return one(useCase.discountNote(today.orToday(date), proceedsAccountCode, faceValue, discountFee,
                Boolean.TRUE.equals(electronicallyRecorded)));
    }

    // ---- 引当金の取崩し ----

    @Tool(name = "use_provision", description = "引当金を取り崩して支払う(賞与の支給、修繕の実施など)。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse useProvision(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "引当金の種類") @NotNull ProvisionType type,
            @ToolParam(description = "取り崩す額(円)") @Positive long amount,
            @ToolParam(description = "支払の科目コード") @NotBlank String paymentAccountCode) {
        return one(useCase.useProvision(today.orToday(date), type, amount, paymentAccountCode));
    }

    // ---- 収益認識 ----

    @Tool(name = "record_sale_with_expected_refund", description = "返品が見込まれる販売。返金見込額を返金負債にする。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse recordSaleWithExpectedRefund(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "代金の科目コード") @NotBlank String receivableAccountCode,
            @ToolParam(description = "対価の総額(円)") @Positive long totalConsideration,
            @ToolParam(description = "返金見込額(円)") @PositiveOrZero long expectedRefund) {
        return one(useCase.recordSaleWithExpectedRefund(today.orToday(date), receivableAccountCode,
                totalConsideration, expectedRefund));
    }

    @Tool(name = "recognize_contract_asset", description = "履行義務の一部を充足したが、まだ請求できない収益を契約資産として計上する。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse recognizeContractAsset(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "計上する収益(円)") @Positive long revenue) {
        return one(useCase.recognizeContractAsset(today.orToday(date), revenue));
    }

    @Tool(name = "bill_completed_contract", description = """
            契約の完了と請求。残りの収益を計上し、契約資産を債権に振り替える。
            contractJournalIds はこの契約の契約資産を計上した仕訳の ID(契約が1件だけなら省略できる)。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse billCompletedContract(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "請求した代金の科目コード") @NotBlank String receivableAccountCode,
            @ToolParam(description = "残りの収益(円)") @PositiveOrZero long remainingRevenue,
            @ToolParam(description = "この契約の契約資産を計上した仕訳の ID", required = false) Set<UUID> contractJournalIds) {
        Set<JournalId> ids = contractJournalIds == null ? Set.of()
                : contractJournalIds.stream().map(JournalId::new).collect(Collectors.toSet());
        return one(useCase.billCompletedContract(today.orToday(date), receivableAccountCode, remainingRevenue, ids));
    }

    @Tool(name = "recognize_service_revenue", description = "役務収益を計上し、対応する役務原価を振り替える。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse recognizeServiceRevenue(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "代金の科目コード") @NotBlank String receivableAccountCode,
            @ToolParam(description = "役務収益(円)") @Positive long revenue,
            @ToolParam(description = "役務原価(円)") @PositiveOrZero long costOfServices) {
        return one(useCase.recognizeServiceRevenue(today.orToday(date), receivableAccountCode, revenue, costOfServices));
    }

    // ---- 売買目的有価証券の売却 ----

    @Tool(name = "sell_trading_securities", description = """
            売買目的有価証券を売却する。数量を省略すると全額売却、指定すると一部売却。売却損益はライブラリが計算する。
            端数利息は債券の一部売却のときだけ指定する。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse sellTradingSecurities(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "売却代金を受け取る科目コード") @NotBlank String proceedsAccountCode,
            @ToolParam(description = "売却代金(円)") @PositiveOrZero long proceeds,
            @ToolParam(description = "売却した数量(一部売却のとき)", required = false) @Positive Integer quantitySold,
            @ToolParam(description = "売却前の保有数量(一部売却のとき)", required = false) @Positive Integer quantityHeld,
            @ToolParam(description = "受け取った端数利息(円)", required = false) @PositiveOrZero Long accruedInterest) {
        return one(useCase.sellTradingSecurities(today.orToday(date), proceedsAccountCode, proceeds, quantitySold,
                quantityHeld, accruedInterest));
    }

    // ---- 銀行勘定調整 ----

    private static final String RECONCILING_ITEMS = """
            調整項目。kind は銀行側(仕訳しない): DEPOSIT_IN_TRANSIT(時間外預入)、OUTSTANDING_CHECK(未取付小切手)、
            UNCOLLECTED_CHECK(未取立小切手)、帳簿側(修正仕訳になる。counterAccountCode が必要): UNRECORDED_DEPOSIT(入金連絡未通知)、
            UNRECORDED_WITHDRAWAL(引落未通知)、UNISSUED_CHECK(未渡小切手)、RECORDING_ERROR(誤記入。amount は帳簿残高の訂正額で、負なら減らす)。
            amount は円。""";

    @Tool(name = "preview_bank_reconciliation", description = "銀行勘定調整表のプレビュー。調整表と、記帳される修正仕訳を返す(記帳はしない)。")
    @PreAuthorize(BOOKKEEPER)
    public BankReconciliationPreviewResponse previewBankReconciliation(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "当座預金の科目コード") @NotBlank String checkingAccountCode,
            @ToolParam(description = "銀行の残高証明書の残高(円)") long bankStatementBalance,
            @ToolParam(description = RECONCILING_ITEMS) @NotNull List<@Valid ReconcilingItemRequest> items) {
        BankReconciliationRequest r = new BankReconciliationRequest(date, checkingAccountCode, bankStatementBalance, items);
        return BankReconciliationPreviewResponse.of(bankReconciliationUseCase.preview(
                today.orToday(date), checkingAccountCode, bankStatementBalance, r.toItems()));
    }

    @Tool(name = "reconcile_bank", description = "銀行勘定調整表を作り、帳簿側の調整項目だけを修正仕訳として記帳する。")
    @PreAuthorize(BOOKKEEPER)
    public BankReconciliationResponse reconcileBank(
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "当座預金の科目コード") @NotBlank String checkingAccountCode,
            @ToolParam(description = "銀行の残高証明書の残高(円)") long bankStatementBalance,
            @ToolParam(description = RECONCILING_ITEMS) @NotNull List<@Valid ReconcilingItemRequest> items) {
        BankReconciliationRequest r = new BankReconciliationRequest(date, checkingAccountCode, bankStatementBalance, items);
        return BankReconciliationResponse.of(bankReconciliationUseCase.reconcile(
                today.orToday(date), checkingAccountCode, bankStatementBalance, r.toItems()));
    }

    private static IdsResponse one(JournalId id) {
        return IdsResponse.of(List.of(id));
    }

    private static IdsResponse optional(Optional<JournalId> id) {
        return IdsResponse.of(id.stream().toList());
    }
}
