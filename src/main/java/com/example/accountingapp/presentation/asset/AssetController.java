package com.example.accountingapp.presentation.asset;

import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.asset.IntangibleAsset;
import com.example.accounting.model.securities.HeldToMaturityBond;
import com.example.accountingapp.application.asset.DepreciationSettings;
import com.example.accountingapp.application.asset.FixedAssetUseCase;
import com.example.accountingapp.application.asset.HeldToMaturityBondUseCase;
import com.example.accountingapp.application.asset.IntangibleAssetUseCase;
import com.example.accountingapp.application.port.HeldToMaturityBondRepository.RegisteredBond;
import com.example.accountingapp.presentation.IdsResponse;
import com.example.accountingapp.presentation.Today;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 固定資産台帳(有形・無形)と満期保有目的債券。累計償却額・帳簿価額は、指定した期(省略時は最新の期)の
 * 仕訳からライブラリが導出する。資産ごとの科目は利用者が選ぶ。
 */
@RestController
public class AssetController {

    private final FixedAssetUseCase fixedAssets;
    private final IntangibleAssetUseCase intangibleAssets;
    private final HeldToMaturityBondUseCase bonds;
    private final Today today;

    public AssetController(FixedAssetUseCase fixedAssets, IntangibleAssetUseCase intangibleAssets,
                           HeldToMaturityBondUseCase bonds, Today today) {
        this.fixedAssets = fixedAssets;
        this.intangibleAssets = intangibleAssets;
        this.bonds = bonds;
        this.today = today;
    }

    // ---- 有形固定資産 ----

    public record DepreciationRequest(@NotNull DepreciationSettings.Method method, BigDecimal decliningRate,
                                      @Positive Long totalEstimatedUsage, DepreciationSettings.Rounding rounding) {
    }

    public record RegisterFixedAssetRequest(
            @NotBlank String name, @NotBlank String assetAccountCode,
            @NotBlank String accumulatedDepreciationAccountCode, @NotBlank String depreciationExpenseAccountCode,
            @Positive long acquisitionCost, @NotNull LocalDate acquisitionDate, @Positive int usefulLifeYears,
            @PositiveOrZero long residualValue, @NotNull @Valid DepreciationRequest depreciation) {
    }

    public record FixedAssetResponse(UUID id, String name, String assetAccountCode,
                                     String accumulatedDepreciationAccountCode, String depreciationExpenseAccountCode,
                                     long acquisitionCost, LocalDate acquisitionDate, int usefulLifeYears,
                                     long residualValue, DepreciationSettings depreciation,
                                     long accumulatedDepreciation, long bookValue, LocalDate disposedOn) {

        static FixedAssetResponse of(FixedAssetUseCase.FixedAssetView view) {
            FixedAsset a = view.asset();
            return new FixedAssetResponse(a.id().value(), a.name(), a.assetAccount().code().value(),
                    a.accumulatedDepreciationAccount().code().value(), a.depreciationExpenseAccount().code().value(),
                    a.acquisitionCost().amountInYen(), a.acquisitionDate(), a.usefulLifeYears(),
                    a.residualValue().amountInYen(), view.depreciation(),
                    view.accumulatedDepreciation().amountInYen(), view.bookValue().amountInYen(),
                    view.disposedOn().orElse(null));
        }
    }

    public record CreatedResponse(UUID id) {
    }

    @PostMapping("/fixed-assets")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse registerFixedAsset(@Valid @RequestBody RegisterFixedAssetRequest r) {
        DepreciationRequest d = r.depreciation();
        FixedAsset asset = fixedAssets.register(new FixedAssetUseCase.RegisterCommand(
                r.name(), r.assetAccountCode(), r.accumulatedDepreciationAccountCode(),
                r.depreciationExpenseAccountCode(), r.acquisitionCost(), r.acquisitionDate(), r.usefulLifeYears(),
                r.residualValue(),
                new DepreciationSettings(d.method(), d.decliningRate(), d.totalEstimatedUsage(), d.rounding())));
        return new CreatedResponse(asset.id().value());
    }

    @GetMapping("/fixed-assets")
    public List<FixedAssetResponse> fixedAssets(@RequestParam Optional<UUID> periodId) {
        return fixedAssets.list(periodId).stream().map(FixedAssetResponse::of).toList();
    }

    @GetMapping("/fixed-assets/{id}")
    public FixedAssetResponse fixedAsset(@PathVariable UUID id, @RequestParam Optional<UUID> periodId) {
        return FixedAssetResponse.of(fixedAssets.get(id, periodId));
    }

    /**
     * 処分(売却・除却・廃棄)に共通の項目。{@code recordDepreciationUntilDisposal} を true にすると、処分日までの
     * 当期の減価償却を先に記帳する(生産高比例法なら {@code usageThisPeriod} が必要)。
     */
    public record SaleRequest(LocalDate date, boolean recordDepreciationUntilDisposal, @PositiveOrZero Long usageThisPeriod,
                              @NotBlank String proceedsAccountCode, @PositiveOrZero long proceeds) {
    }

    @PostMapping("/fixed-assets/{id}/disposal")
    @ResponseStatus(HttpStatus.CREATED)
    public IdsResponse sell(@PathVariable UUID id, @Valid @RequestBody SaleRequest r) {
        return IdsResponse.of(fixedAssets.sell(id, options(r.date(), r.recordDepreciationUntilDisposal(),
                r.usageThisPeriod()), r.proceedsAccountCode(), r.proceeds()));
    }

    public record RetirementRequest(LocalDate date, boolean recordDepreciationUntilDisposal,
                                    @PositiveOrZero Long usageThisPeriod, @PositiveOrZero long estimatedDisposalValue) {
    }

    @PostMapping("/fixed-assets/{id}/retirement")
    @ResponseStatus(HttpStatus.CREATED)
    public IdsResponse retire(@PathVariable UUID id, @Valid @RequestBody RetirementRequest r) {
        return IdsResponse.of(fixedAssets.retire(id, options(r.date(), r.recordDepreciationUntilDisposal(),
                r.usageThisPeriod()), r.estimatedDisposalValue()));
    }

    public record AbandonmentRequest(LocalDate date, boolean recordDepreciationUntilDisposal,
                                     @PositiveOrZero Long usageThisPeriod, @PositiveOrZero long disposalCost,
                                     String paymentAccountCode) {
    }

    @PostMapping("/fixed-assets/{id}/abandonment")
    @ResponseStatus(HttpStatus.CREATED)
    public IdsResponse abandon(@PathVariable UUID id, @Valid @RequestBody AbandonmentRequest r) {
        return IdsResponse.of(fixedAssets.abandon(id, options(r.date(), r.recordDepreciationUntilDisposal(),
                r.usageThisPeriod()), r.disposalCost(), r.paymentAccountCode()));
    }

    public record CompressionRequest(LocalDate date, @Positive long reduction) {
    }

    /** 圧縮記帳(直接減額方式)。圧縮後の資産と圧縮損の仕訳を同じトランザクションで保存する。 */
    @PostMapping("/fixed-assets/{id}/compression")
    @ResponseStatus(HttpStatus.CREATED)
    public IdsResponse compress(@PathVariable UUID id, @Valid @RequestBody CompressionRequest r) {
        return IdsResponse.of(List.of(fixedAssets.compress(id, r.reduction(), today.orToday(r.date()))));
    }

    private FixedAssetUseCase.DisposalOptions options(LocalDate date, boolean depreciate, Long usage) {
        return new FixedAssetUseCase.DisposalOptions(today.orToday(date), depreciate, usage);
    }

    // ---- 無形固定資産 ----

    public record RegisterIntangibleAssetRequest(@NotBlank String name, @NotBlank String assetAccountCode,
                                                 @NotBlank String amortizationExpenseAccountCode,
                                                 @Positive long acquisitionCost, @NotNull LocalDate acquisitionDate,
                                                 @Positive int usefulLifeYears) {
    }

    public record IntangibleAssetResponse(UUID id, String name, String assetAccountCode,
                                          String amortizationExpenseAccountCode, long acquisitionCost,
                                          LocalDate acquisitionDate, int usefulLifeYears, long bookValue) {
    }

    @PostMapping("/intangible-assets")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse registerIntangibleAsset(@Valid @RequestBody RegisterIntangibleAssetRequest r) {
        IntangibleAsset asset = intangibleAssets.register(new IntangibleAssetUseCase.RegisterCommand(
                r.name(), r.assetAccountCode(), r.amortizationExpenseAccountCode(), r.acquisitionCost(),
                r.acquisitionDate(), r.usefulLifeYears()));
        return new CreatedResponse(asset.id().value());
    }

    @GetMapping("/intangible-assets")
    public List<IntangibleAssetResponse> intangibleAssets(@RequestParam Optional<UUID> periodId) {
        return intangibleAssets.list(periodId).stream().map(view -> {
            IntangibleAsset a = view.asset();
            return new IntangibleAssetResponse(a.id().value(), a.name(), a.assetAccount().code().value(),
                    a.amortizationExpenseAccount().code().value(), a.acquisitionCost().amountInYen(),
                    a.acquisitionDate(), a.usefulLifeYears(), view.bookValue().amountInYen());
        }).toList();
    }

    // ---- 満期保有目的債券 ----

    public record RegisterBondRequest(@NotBlank String name, @NotBlank String bondAccountCode, @Positive long faceValue,
                                      @Positive long acquisitionCost, @NotNull LocalDate acquisitionDate,
                                      @NotNull LocalDate maturityDate) {
    }

    public record BondResponse(UUID id, String name, String bondAccountCode, long faceValue, long acquisitionCost,
                               LocalDate acquisitionDate, LocalDate maturityDate, long bookValue) {
    }

    @PostMapping("/held-to-maturity-bonds")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse registerBond(@Valid @RequestBody RegisterBondRequest r) {
        RegisteredBond bond = bonds.register(new HeldToMaturityBondUseCase.RegisterCommand(
                r.name(), r.bondAccountCode(), r.faceValue(), r.acquisitionCost(), r.acquisitionDate(),
                r.maturityDate()));
        return new CreatedResponse(bond.id());
    }

    @GetMapping("/held-to-maturity-bonds")
    public List<BondResponse> bonds(@RequestParam Optional<UUID> periodId) {
        return bonds.list(periodId).stream().map(view -> {
            HeldToMaturityBond b = view.bond().bond();
            return new BondResponse(view.bond().id(), view.bond().name(), b.bondAccount().code().value(),
                    b.faceValue().amountInYen(), b.acquisitionCost().amountInYen(), b.acquisitionDate(),
                    b.maturityDate(), view.bookValue().amountInYen());
        }).toList();
    }
}
