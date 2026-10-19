package com.example.accountingapp.presentation.mcp.consolidation;

import com.example.accountingapp.application.consolidation.ConsolidationUseCase;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.IntercompanyBalanceCommand;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.IntercompanyTransactionCommand;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.SubsidiaryCommand;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.UnrealizedProfitCommand;
import com.example.accountingapp.presentation.controller.consolidation.ConsolidationController.ConsolidationResponse;
import com.example.accountingapp.presentation.controller.consolidation.ConsolidationController.IntercompanyBalanceResponse;
import com.example.accountingapp.presentation.controller.consolidation.ConsolidationController.IntercompanyTransactionResponse;
import com.example.accountingapp.presentation.controller.consolidation.ConsolidationController.MaterialsResponse;
import com.example.accountingapp.presentation.controller.consolidation.ConsolidationController.SubsidiaryRequest.EquityLine;
import com.example.accountingapp.presentation.controller.consolidation.ConsolidationController.SubsidiaryResponse;
import com.example.accountingapp.presentation.controller.consolidation.ConsolidationController.UnrealizedProfitResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
import java.util.UUID;

/**
 * 連結会計。操作している会社(companyId、省略すれば既定の会社)を親会社とする。子会社も、このアプリで帳簿を持つ会社
 * (create_company で作る)。連結のルールはライブラリで未検証なので、結果は確認用として扱う。
 */
@Component
@Validated
public class ConsolidationTools implements McpTools {

    private static final String PERIOD_ID = "親会社の会計期間の ID";

    private final ConsolidationUseCase useCase;

    public ConsolidationTools(ConsolidationUseCase useCase) {
        this.useCase = useCase;
    }

    @Tool(name = "list_subsidiaries", description = "親会社(操作している会社)の子会社と、支配獲得時の事実を返す。")
    @PreAuthorize(VIEWER)
    public List<SubsidiaryResponse> listSubsidiaries() {
        return useCase.subsidiaries().stream().map(SubsidiaryResponse::of).toList();
    }

    @Tool(name = "register_subsidiary", description = """
            ほかの会社を、操作している会社の子会社として登録する(承認者のみ)。支配獲得日は子会社の決算日(期末)。
            孫会社・期中の支配獲得・持分の変動・子会社の資産負債の評価差額は扱わない。""")
    @PreAuthorize(APPROVER)
    public SubsidiaryResponse registerSubsidiary(
            @ToolParam(description = "子会社にする会社の ID") @NotNull UUID subsidiaryCompanyId,
            @ToolParam(description = "支配獲得日(子会社の期末日。yyyy-MM-dd)") @NotNull LocalDate acquisitionDate,
            @ToolParam(description = "親会社の持分比率(例: 0.8)") @NotNull @DecimalMin(value = "0", inclusive = false)
            @DecimalMax("1") BigDecimal parentOwnership,
            @ToolParam(description = "子会社株式の取得原価(円)") @Positive long investmentCost,
            @ToolParam(description = "支配獲得日の子会社の資本(資本金・資本剰余金・利益剰余金の科目コードと金額)")
            @NotEmpty List<@Valid EquityLine> equityAtAcquisition,
            @ToolParam(description = "のれんの償却年数") @Positive int goodwillAmortizationYears) {
        return SubsidiaryResponse.of(useCase.registerSubsidiary(new SubsidiaryCommand(subsidiaryCompanyId,
                acquisitionDate, parentOwnership, investmentCost,
                equityAtAcquisition.stream()
                        .map(line -> new SubsidiaryCommand.EquityLine(line.accountCode(), line.amount()))
                        .toList(),
                goodwillAmortizationYears)));
    }

    @Tool(name = "remove_subsidiary", description = "子会社の登録を取り消す(承認者のみ)。子会社の帳簿は残る。")
    @PreAuthorize(APPROVER)
    public void removeSubsidiary(@ToolParam(description = "子会社の会社 ID") @NotNull UUID subsidiaryCompanyId) {
        useCase.removeSubsidiary(subsidiaryCompanyId);
    }

    @Tool(name = "get_consolidation_materials", description = """
            親会社の期の連結の資料(連結会社間の債権債務・取引高・未実現利益)を返す。""")
    @PreAuthorize(VIEWER)
    public MaterialsResponse getConsolidationMaterials(@ToolParam(description = PERIOD_ID) @NotNull UUID periodId) {
        return MaterialsResponse.of(useCase.materials(periodId));
    }

    @Tool(name = "add_intercompany_balance", description = """
            連結会社間の債権債務(売掛金と買掛金、貸付金と借入金など)を追加する(承認者のみ)。連結で相殺消去する。
            債権者が設定した貸倒引当金があれば allowance(期末)と allowanceAtBeginning(前期末)も渡す。""")
    @PreAuthorize(APPROVER)
    public IntercompanyBalanceResponse addIntercompanyBalance(
            @ToolParam(description = PERIOD_ID) @NotNull UUID periodId,
            @ToolParam(description = "債権を持つ会社の ID(親会社か子会社)") @NotNull UUID creditorCompanyId,
            @ToolParam(description = "債権の科目コード") @NotBlank String receivableCode,
            @ToolParam(description = "債務の科目コード") @NotBlank String payableCode,
            @ToolParam(description = "金額(円)") @PositiveOrZero long amount,
            @ToolParam(description = "この債権に対する期末の貸倒引当金(円)", required = false) @PositiveOrZero Long allowance,
            @ToolParam(description = "前期末の連結会社間の債権に対する貸倒引当金(円)", required = false)
            @PositiveOrZero Long allowanceAtBeginning) {
        return IntercompanyBalanceResponse.of(useCase.addBalance(periodId, new IntercompanyBalanceCommand(
                creditorCompanyId, receivableCode, payableCode, amount, zeroIfNull(allowance),
                zeroIfNull(allowanceAtBeginning))));
    }

    @Tool(name = "add_intercompany_transaction", description = """
            連結会社間の取引高(売上高と仕入、受取利息と支払利息など)を追加する(承認者のみ)。連結で相殺消去する。""")
    @PreAuthorize(APPROVER)
    public IntercompanyTransactionResponse addIntercompanyTransaction(
            @ToolParam(description = PERIOD_ID) @NotNull UUID periodId,
            @ToolParam(description = "売った側(受け取った側)の収益の科目コード") @NotBlank String revenueCode,
            @ToolParam(description = "買った側(支払った側)の費用の科目コード") @NotBlank String expenseCode,
            @ToolParam(description = "金額(円)") @Positive long amount) {
        return IntercompanyTransactionResponse.of(useCase.addTransaction(periodId,
                new IntercompanyTransactionCommand(revenueCode, expenseCode, amount)));
    }

    @Tool(name = "add_unrealized_profit", description = """
            未実現利益を追加する(承認者のみ)。kind が INVENTORY なら棚卸資産(inventoryAccountCode・ending・beginning)、
            LAND なら土地の売却益(amount・saleDate)。売った側が子会社ならアップストリームとして非支配株主に按分される。""")
    @PreAuthorize(APPROVER)
    public UnrealizedProfitResponse addUnrealizedProfit(
            @ToolParam(description = PERIOD_ID) @NotNull UUID periodId,
            @ToolParam(description = "INVENTORY(棚卸資産)または LAND(土地)") @NotNull UnrealizedProfitCommand.Kind kind,
            @ToolParam(description = "売った側の会社の ID(親会社か子会社)") @NotNull UUID sellerCompanyId,
            @ToolParam(description = "INVENTORY: 買った側の棚卸資産の科目コード", required = false) String inventoryAccountCode,
            @ToolParam(description = "INVENTORY: 期末の棚卸資産に含まれる未実現利益(円)", required = false)
            @PositiveOrZero Long ending,
            @ToolParam(description = "INVENTORY: 期首の棚卸資産に含まれる未実現利益(円)", required = false)
            @PositiveOrZero Long beginning,
            @ToolParam(description = "LAND: 売却益(円)", required = false) @Positive Long amount,
            @ToolParam(description = "LAND: 売却日(yyyy-MM-dd)", required = false) LocalDate saleDate) {
        return UnrealizedProfitResponse.of(useCase.addUnrealizedProfit(periodId, new UnrealizedProfitCommand(
                kind, sellerCompanyId, inventoryAccountCode, ending, beginning, amount, saleDate)));
    }

    @Tool(name = "remove_consolidation_material", description = "連結の資料を1件削除する(種類は問わない。承認者のみ)。")
    @PreAuthorize(APPROVER)
    public void removeConsolidationMaterial(
            @ToolParam(description = PERIOD_ID) @NotNull UUID periodId,
            @ToolParam(description = "資料の ID(get_consolidation_materials で確認する)") @NotNull UUID materialId) {
        useCase.removeMaterial(periodId, materialId);
    }

    @Tool(name = "get_consolidated_statements", description = """
            親会社の期について、連結修正仕訳・連結精算表・連結損益計算書・連結貸借対照表を作る(保存しない)。
            各社の損益振替前の仕訳と、登録した子会社・連結の資料から毎回作り直す。子会社の期は期末日が同じものを使う。
            連結のルールはライブラリで未検証(unverified: true)。""")
    @PreAuthorize(VIEWER)
    public ConsolidationResponse getConsolidatedStatements(@ToolParam(description = PERIOD_ID) @NotNull UUID periodId) {
        return ConsolidationResponse.of(useCase.consolidate(periodId));
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0 : value;
    }
}
