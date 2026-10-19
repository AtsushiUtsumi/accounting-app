package com.example.accountingapp.presentation.mcp.asset;

import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.asset.IntangibleAsset;
import com.example.accountingapp.application.asset.DepreciationSettings;
import com.example.accountingapp.application.asset.FixedAssetUseCase;
import com.example.accountingapp.application.asset.HeldToMaturityBondUseCase;
import com.example.accountingapp.application.asset.IntangibleAssetUseCase;
import com.example.accountingapp.application.port.HeldToMaturityBondRepository.RegisteredBond;
import com.example.accountingapp.presentation.IdsResponse;
import com.example.accountingapp.presentation.Today;
import com.example.accountingapp.presentation.controller.asset.AssetController.BondResponse;
import com.example.accountingapp.presentation.controller.asset.AssetController.CreatedResponse;
import com.example.accountingapp.presentation.controller.asset.AssetController.FixedAssetResponse;
import com.example.accountingapp.presentation.controller.asset.AssetController.IntangibleAssetResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.constraints.NotBlank;
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
import java.util.UUID;

/**
 * 固定資産台帳(有形・無形)と満期保有目的債券。累計償却額・帳簿価額は、指定した期(省略時は最新の期)の
 * 仕訳からライブラリが導出する。資産ごとの科目は利用者が選ぶ。
 */
@Component
@Validated
public class AssetTools implements McpTools {

    private static final String PERIOD_ID = "帳簿価額を求める会計期間の ID。省略すると最新の期";
    private static final String DATE = "日付(yyyy-MM-dd)。省略すると今日";
    private static final String DEPRECIATE = "true なら、処分日までの当期の減価償却を先に記帳する。省略すると false";
    private static final String USAGE = "生産高比例法で処分日までの償却をするときの当期利用量";

    private final FixedAssetUseCase fixedAssets;
    private final IntangibleAssetUseCase intangibleAssets;
    private final HeldToMaturityBondUseCase bonds;
    private final Today today;

    public AssetTools(FixedAssetUseCase fixedAssets, IntangibleAssetUseCase intangibleAssets,
                      HeldToMaturityBondUseCase bonds, Today today) {
        this.fixedAssets = fixedAssets;
        this.intangibleAssets = intangibleAssets;
        this.bonds = bonds;
        this.today = today;
    }

    // ---- 有形固定資産 ----

    @Tool(name = "register_fixed_asset", description = """
            有形固定資産を台帳に登録する(取得の仕訳は記帳しない。取得は post_journal で記帳する)。
            帳簿価額を勘定の残高から導出するため、同じ種類の資産が複数あるなら資産ごとに科目が必要。""")
    @PreAuthorize(BOOKKEEPER)
    public CreatedResponse registerFixedAsset(
            @ToolParam(description = "資産の名前") @NotBlank String name,
            @ToolParam(description = "資産の科目コード") @NotBlank String assetAccountCode,
            @ToolParam(description = "減価償却累計額の科目コード") @NotBlank String accumulatedDepreciationAccountCode,
            @ToolParam(description = "減価償却費の科目コード") @NotBlank String depreciationExpenseAccountCode,
            @ToolParam(description = "取得原価(円)") @Positive long acquisitionCost,
            @ToolParam(description = "取得日(yyyy-MM-dd)") @NotNull LocalDate acquisitionDate,
            @ToolParam(description = "耐用年数") @Positive int usefulLifeYears,
            @ToolParam(description = "残存価額(円)") @PositiveOrZero long residualValue,
            @ToolParam(description = "償却方法。STRAIGHT_LINE(定額法)、DECLINING_BALANCE(定率法)、UNITS_OF_PRODUCTION(生産高比例法)")
            @NotNull DepreciationSettings.Method depreciationMethod,
            @ToolParam(description = "定率法の償却率(例: 0.25)", required = false) BigDecimal decliningRate,
            @ToolParam(description = "生産高比例法の総利用可能量", required = false) @Positive Long totalEstimatedUsage,
            @ToolParam(description = "端数処理。DOWN(切り捨て)か HALF_UP(四捨五入)", required = false)
            DepreciationSettings.Rounding rounding) {
        FixedAsset asset = fixedAssets.register(new FixedAssetUseCase.RegisterCommand(
                name, assetAccountCode, accumulatedDepreciationAccountCode, depreciationExpenseAccountCode,
                acquisitionCost, acquisitionDate, usefulLifeYears, residualValue,
                new DepreciationSettings(depreciationMethod, decliningRate, totalEstimatedUsage, rounding)));
        return new CreatedResponse(asset.id().value());
    }

    @Tool(name = "list_fixed_assets", description = "固定資産台帳。資産ごとの累計償却額・帳簿価額・処分日を返す。")
    @PreAuthorize(VIEWER)
    public List<FixedAssetResponse> listFixedAssets(@ToolParam(description = PERIOD_ID, required = false) UUID periodId) {
        return fixedAssets.list(Optional.ofNullable(periodId)).stream().map(FixedAssetResponse::of).toList();
    }

    @Tool(name = "get_fixed_asset", description = "固定資産を1つ返す。")
    @PreAuthorize(VIEWER)
    public FixedAssetResponse getFixedAsset(
            @ToolParam(description = "固定資産の ID") @NotNull UUID fixedAssetId,
            @ToolParam(description = PERIOD_ID, required = false) UUID periodId) {
        return FixedAssetResponse.of(fixedAssets.get(fixedAssetId, Optional.ofNullable(periodId)));
    }

    @Tool(name = "sell_fixed_asset", description = "固定資産を売却する。売却損益の計算と仕訳はライブラリが行う。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse sellFixedAsset(
            @ToolParam(description = "固定資産の ID") @NotNull UUID fixedAssetId,
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = DEPRECIATE, required = false) Boolean recordDepreciationUntilDisposal,
            @ToolParam(description = USAGE, required = false) @PositiveOrZero Long usageThisPeriod,
            @ToolParam(description = "売却代金を受け取る科目コード(現金・未収入金など)") @NotBlank String proceedsAccountCode,
            @ToolParam(description = "売却代金(円)") @PositiveOrZero long proceeds) {
        return IdsResponse.of(fixedAssets.sell(fixedAssetId,
                options(date, recordDepreciationUntilDisposal, usageThisPeriod), proceedsAccountCode, proceeds));
    }

    @Tool(name = "retire_fixed_asset", description = "固定資産を除却する。見積処分価額を指定する。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse retireFixedAsset(
            @ToolParam(description = "固定資産の ID") @NotNull UUID fixedAssetId,
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = DEPRECIATE, required = false) Boolean recordDepreciationUntilDisposal,
            @ToolParam(description = USAGE, required = false) @PositiveOrZero Long usageThisPeriod,
            @ToolParam(description = "見積処分価額(円)") @PositiveOrZero long estimatedDisposalValue) {
        return IdsResponse.of(fixedAssets.retire(fixedAssetId,
                options(date, recordDepreciationUntilDisposal, usageThisPeriod), estimatedDisposalValue));
    }

    @Tool(name = "abandon_fixed_asset", description = "固定資産を廃棄する。廃棄費用があれば支払の科目も指定する。")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse abandonFixedAsset(
            @ToolParam(description = "固定資産の ID") @NotNull UUID fixedAssetId,
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = DEPRECIATE, required = false) Boolean recordDepreciationUntilDisposal,
            @ToolParam(description = USAGE, required = false) @PositiveOrZero Long usageThisPeriod,
            @ToolParam(description = "廃棄費用(円)") @PositiveOrZero long disposalCost,
            @ToolParam(description = "廃棄費用を支払う科目コード", required = false) String paymentAccountCode) {
        return IdsResponse.of(fixedAssets.abandon(fixedAssetId,
                options(date, recordDepreciationUntilDisposal, usageThisPeriod), disposalCost, paymentAccountCode));
    }

    @Tool(name = "compress_fixed_asset", description = """
            圧縮記帳(直接減額方式)。取得原価を減額し、圧縮損の仕訳を記帳する。圧縮後の資産と仕訳は同じトランザクションで保存される。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse compressFixedAsset(
            @ToolParam(description = "固定資産の ID") @NotNull UUID fixedAssetId,
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "圧縮額(円)") @Positive long reduction) {
        return IdsResponse.of(List.of(fixedAssets.compress(fixedAssetId, reduction, today.orToday(date))));
    }

    private FixedAssetUseCase.DisposalOptions options(LocalDate date, Boolean depreciate, Long usage) {
        return new FixedAssetUseCase.DisposalOptions(today.orToday(date), Boolean.TRUE.equals(depreciate), usage);
    }

    // ---- 無形固定資産 ----

    @Tool(name = "register_intangible_asset", description = "無形固定資産(ソフトウェアなど)を台帳に登録する。決算整理で償却の対象になる。")
    @PreAuthorize(BOOKKEEPER)
    public CreatedResponse registerIntangibleAsset(
            @ToolParam(description = "資産の名前") @NotBlank String name,
            @ToolParam(description = "資産の科目コード") @NotBlank String assetAccountCode,
            @ToolParam(description = "償却費の科目コード") @NotBlank String amortizationExpenseAccountCode,
            @ToolParam(description = "取得原価(円)") @Positive long acquisitionCost,
            @ToolParam(description = "取得日(yyyy-MM-dd)") @NotNull LocalDate acquisitionDate,
            @ToolParam(description = "耐用年数") @Positive int usefulLifeYears) {
        IntangibleAsset asset = intangibleAssets.register(new IntangibleAssetUseCase.RegisterCommand(
                name, assetAccountCode, amortizationExpenseAccountCode, acquisitionCost, acquisitionDate,
                usefulLifeYears));
        return new CreatedResponse(asset.id().value());
    }

    @Tool(name = "list_intangible_assets", description = "無形固定資産の台帳。資産ごとの帳簿価額を返す。")
    @PreAuthorize(VIEWER)
    public List<IntangibleAssetResponse> listIntangibleAssets(
            @ToolParam(description = PERIOD_ID, required = false) UUID periodId) {
        return intangibleAssets.list(Optional.ofNullable(periodId)).stream().map(IntangibleAssetResponse::of).toList();
    }

    // ---- 満期保有目的債券 ----

    @Tool(name = "register_held_to_maturity_bond", description = "満期保有目的債券を登録する。決算整理で償却原価法の対象になる。")
    @PreAuthorize(BOOKKEEPER)
    public CreatedResponse registerHeldToMaturityBond(
            @ToolParam(description = "債券の名前") @NotBlank String name,
            @ToolParam(description = "債券の科目コード") @NotBlank String bondAccountCode,
            @ToolParam(description = "額面(円)") @Positive long faceValue,
            @ToolParam(description = "取得原価(円)") @Positive long acquisitionCost,
            @ToolParam(description = "取得日(yyyy-MM-dd)") @NotNull LocalDate acquisitionDate,
            @ToolParam(description = "満期日(yyyy-MM-dd)") @NotNull LocalDate maturityDate) {
        RegisteredBond bond = bonds.register(new HeldToMaturityBondUseCase.RegisterCommand(
                name, bondAccountCode, faceValue, acquisitionCost, acquisitionDate, maturityDate));
        return new CreatedResponse(bond.id());
    }

    @Tool(name = "list_held_to_maturity_bonds", description = "満期保有目的債券の一覧。債券ごとの帳簿価額(償却原価)を返す。")
    @PreAuthorize(VIEWER)
    public List<BondResponse> listHeldToMaturityBonds(@ToolParam(description = PERIOD_ID, required = false) UUID periodId) {
        return bonds.list(Optional.ofNullable(periodId)).stream().map(BondResponse::of).toList();
    }
}
