package com.example.accountingapp.presentation.mcp.closing;

import com.example.accountingapp.application.closing.AdjustingEntriesCommand;
import com.example.accountingapp.application.closing.ClosePeriodUseCase;
import com.example.accountingapp.application.closing.ReverseAccrualsUseCase;
import com.example.accountingapp.application.closing.RunAdjustingEntriesUseCase;
import com.example.accountingapp.presentation.IdsResponse;
import com.example.accountingapp.presentation.JournalResponse;
import com.example.accountingapp.presentation.controller.closing.ClosingController.AdjustmentsRequest;
import com.example.accountingapp.presentation.controller.closing.ClosingController.CloseResponse;
import com.example.accountingapp.presentation.controller.closing.ClosingController.FairValueRequest;
import com.example.accountingapp.presentation.controller.closing.ClosingController.InventoryCountRequest;
import com.example.accountingapp.presentation.controller.closing.ClosingController.ProvisionRequest;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 決算整理(プレビュー・確定)、締め、再振替。どれも承認者の操作。 */
@Component
@Validated
public class ClosingTools implements McpTools {

    private static final String ADJUSTMENTS_DESCRIPTION = """
            入力はすべて外部から与える事実で、省略した手順は実行しない。
            ライブラリが決めた順序(当座借越の振替 → 売上原価・棚卸減耗 → 減価償却・償却 → 有価証券の評価 → 引当金 → 法人税等・消費税)で
            決算整理仕訳を作る。経過勘定・貯蔵品は自動では作らないので、先に post_journal で ADJUSTING の仕訳として記帳しておく。""";

    private final RunAdjustingEntriesUseCase runAdjustingEntriesUseCase;
    private final ClosePeriodUseCase closePeriodUseCase;
    private final ReverseAccrualsUseCase reverseAccrualsUseCase;

    public ClosingTools(RunAdjustingEntriesUseCase runAdjustingEntriesUseCase, ClosePeriodUseCase closePeriodUseCase,
                        ReverseAccrualsUseCase reverseAccrualsUseCase) {
        this.runAdjustingEntriesUseCase = runAdjustingEntriesUseCase;
        this.closePeriodUseCase = closePeriodUseCase;
        this.reverseAccrualsUseCase = reverseAccrualsUseCase;
    }

    @Tool(name = "preview_adjusting_entries", description = """
            決算整理のプレビュー。保存せずに、生成される決算整理仕訳を返す。確定する前に必ずこれで内容を確認する。
            """ + ADJUSTMENTS_DESCRIPTION)
    @PreAuthorize(APPROVER)
    public List<JournalResponse> previewAdjustingEntries(
            @ToolParam(description = "会計期間の ID") @NotNull UUID periodId,
            @ToolParam(description = "決算整理の入力") @NotNull @Valid AdjustmentsInput adjustments) {
        return runAdjustingEntriesUseCase.preview(periodId, adjustments.toCommand(false)).stream()
                .map(JournalResponse::of)
                .toList();
    }

    @Tool(name = "run_adjusting_entries", description = """
            決算整理を確定し、決算整理仕訳を記帳する。1つの期に1回だけ実行できる(やり直すときは整理仕訳を赤黒で取り消してから)。
            accrualsConfirmed は「経過勘定・貯蔵品の入力は済みましたか」の確認で、利用者に確認したうえで true にする。
            """ + ADJUSTMENTS_DESCRIPTION)
    @PreAuthorize(APPROVER)
    public IdsResponse runAdjustingEntries(
            @ToolParam(description = "会計期間の ID") @NotNull UUID periodId,
            @ToolParam(description = "決算整理の入力") @NotNull @Valid AdjustmentsInput adjustments,
            @ToolParam(description = "経過勘定・貯蔵品の入力が済んでいることを利用者に確認したか") boolean accrualsConfirmed) {
        return IdsResponse.of(runAdjustingEntriesUseCase.run(periodId, adjustments.toCommand(accrualsConfirmed)));
    }

    @Tool(name = "close_period", description = """
            会計期間を締める。損益振替を記帳して期を CLOSED にし、次期を作って開始仕訳を記帳する。
            締める前に、現金過不足の残高ゼロ化(settle_cash_over_short)と決算整理が済んでいること。元に戻せない。""")
    @PreAuthorize(APPROVER)
    public CloseResponse closePeriod(@ToolParam(description = "締める会計期間の ID") @NotNull UUID periodId) {
        ClosePeriodUseCase.Result result = closePeriodUseCase.close(periodId);
        return new CloseResponse(result.nextPeriodId().value(), result.netIncome().amountInYen());
    }

    @Tool(name = "reverse_accruals", description = """
            再振替。前期の決算整理仕訳のうち経過勘定・当座借越・貯蔵品・その他有価証券の評価差額を、次期の期首の日付で振り戻す。
            締めたあとの次期に対して実行する。""")
    @PreAuthorize(APPROVER)
    public IdsResponse reverseAccruals(@ToolParam(description = "次期(再振替を記帳する期)の ID") @NotNull UUID periodId) {
        return IdsResponse.of(reverseAccrualsUseCase.reverse(periodId));
    }

    /** 決算整理の入力。REST の {@link AdjustmentsRequest} と同じ項目で、確認のフラグだけを別の引数にする。 */
    public record AdjustmentsInput(
            @ToolParam(description = "当座預金のマイナス残高を当座借越へ振り替えるか", required = false)
            Boolean reclassifyBankOverdraft,
            @ToolParam(description = "当座預金の科目コード。省略すると標準科目の当座預金", required = false)
            String checkingAccountCode,
            @ToolParam(description = "商品の記帳方法。PERIODIC(三分法。省略時の既定。売上原価の算定に期首・期末の商品棚卸高を使う)か PERPETUAL(売上原価対立法)", required = false)
            AdjustingEntriesCommand.InventoryMethod inventoryMethod,
            @ToolParam(description = "期首商品棚卸高(円)", required = false) @PositiveOrZero Long beginningInventory,
            @ToolParam(description = "期末商品棚卸高(帳簿、円)", required = false) @PositiveOrZero Long endingInventory,
            @ToolParam(description = "実地棚卸の結果(品目ごと。帳簿数量・実地数量・原価・正味売却価額)", required = false)
            List<@Valid InventoryCountRequest> inventoryCounts,
            @ToolParam(description = "生産高比例法の固定資産の当期利用量(固定資産 ID → 利用量)", required = false)
            Map<UUID, @PositiveOrZero Long> fixedAssetUsages,
            @ToolParam(description = "売買目的有価証券の期末時価(円)", required = false) @PositiveOrZero Long tradingSecuritiesFairValue,
            @ToolParam(description = "その他有価証券の期末時価(科目ごと)", required = false)
            List<@Valid FairValueRequest> availableForSaleSecurities,
            @ToolParam(description = "貸倒引当金の設定額(期末にあるべき残高、円)", required = false)
            @PositiveOrZero Long allowanceForDoubtfulAccounts,
            @ToolParam(description = "その他の引当金。method は DIFFERENCE(差額補充法)か REPLACEMENT(洗替法)、requiredBalance は期末に必要な残高", required = false) List<@Valid ProvisionRequest> provisions,
            @ToolParam(description = "退職給付費用(円)", required = false) Long retirementBenefitExpense,
            @ToolParam(description = "確定した法人税等の額(円)", required = false) @PositiveOrZero Long incomeTaxAmount,
            @ToolParam(description = "仮払消費税と仮受消費税を相殺するか", required = false) Boolean settleConsumptionTax) {

        AdjustingEntriesCommand toCommand(boolean accrualsConfirmed) {
            return new AdjustmentsRequest(
                    Boolean.TRUE.equals(reclassifyBankOverdraft), checkingAccountCode, inventoryMethod,
                    beginningInventory, endingInventory, inventoryCounts, fixedAssetUsages, tradingSecuritiesFairValue,
                    availableForSaleSecurities, allowanceForDoubtfulAccounts, provisions, retirementBenefitExpense,
                    incomeTaxAmount, Boolean.TRUE.equals(settleConsumptionTax), accrualsConfirmed).toCommand();
        }
    }
}
