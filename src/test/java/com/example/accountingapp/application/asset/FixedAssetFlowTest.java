package com.example.accountingapp.application.asset;

import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.closing.AdjustingEntriesCommand;
import com.example.accountingapp.support.TestAccounting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.accountingapp.support.TestAccounting.cr;
import static com.example.accountingapp.support.TestAccounting.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定資産を、決算整理 → 締め を繰り返して期をまたいで償却し、期中に売却する。
 * 例題はドメインの FixedAssetExtendedTest と同じ(ドメイン側で未検証のため、ここも unverified):
 * 2026年10月1日に取得した備品600,000円(定額法・5年・残存0)を2028年6月30日に400,000円で売却。
 * 償却累計額 = 60,000(6か月) + 120,000(12か月) + 30,000(4〜6月の3か月) = 210,000円、売却益10,000円。
 */
@Tag("unverified")
class FixedAssetFlowTest {

    private static final AdjustingEntriesCommand DEPRECIATION_ONLY = new AdjustingEntriesCommand(
            false, null, null, null, null, null, null, null, null, null, null, null, null, false, true);

    private final TestAccounting app = new TestAccounting();
    private AccountingPeriod period2026;
    private UUID assetId;

    @BeforeEach
    void setUp() {
        period2026 = app.setUpCompany(LocalDate.of(2026, 4, 1));
        app.post(LocalDate.of(2026, 10, 1), "備品の購入",
                dr(StandardAccounts.EQUIPMENT, 600_000), cr(StandardAccounts.CASH, 600_000));
        FixedAsset asset = app.fixedAssetUseCase.register(new FixedAssetUseCase.RegisterCommand(
                "備品", StandardAccounts.EQUIPMENT.code().value(),
                StandardAccounts.ACCUMULATED_DEPRECIATION_EQUIPMENT.code().value(),
                StandardAccounts.DEPRECIATION_EXPENSE.code().value(), 600_000, LocalDate.of(2026, 10, 1), 5, 0,
                new DepreciationSettings(DepreciationSettings.Method.STRAIGHT_LINE, null, null, null)));
        assetId = asset.id().value();
    }

    @Test
    void depreciatesAcrossPeriodsAndSellsInTheMiddleOfAPeriod() {
        AccountingPeriod period2027 = adjustAndClose(period2026);
        AccountingPeriod period2028 = adjustAndClose(period2027);

        assertThat(app.fixedAssetUseCase.get(assetId, Optional.of(period2028.id().value())).accumulatedDepreciation())
                .isEqualTo(Money.ofYen(180_000));

        List<JournalId> ids = app.fixedAssetUseCase.sell(assetId,
                new FixedAssetUseCase.DisposalOptions(LocalDate.of(2028, 6, 30), true, null),
                StandardAccounts.OTHER_RECEIVABLES.code().value(), 400_000);

        Journal untilSale = app.journals.findById(ids.get(0)).orElseThrow().journal();
        Journal sale = app.journals.findById(ids.get(1)).orElseThrow().journal();
        assertThat(untilSale.debitTotal()).isEqualTo(Money.ofYen(30_000));
        assertThat(sale.lines()).containsExactlyInAnyOrder(
                JournalLine.debit(StandardAccounts.ACCUMULATED_DEPRECIATION_EQUIPMENT, Money.ofYen(210_000)),
                JournalLine.debit(StandardAccounts.OTHER_RECEIVABLES, Money.ofYen(400_000)),
                JournalLine.credit(StandardAccounts.EQUIPMENT, Money.ofYen(600_000)),
                JournalLine.credit(StandardAccounts.GAIN_ON_DISPOSAL_OF_FIXED_ASSETS, Money.ofYen(10_000)));

        // 売却した資産は、以後の決算整理で償却しない
        assertThat(app.runAdjustingEntries.preview(period2028.id().value(), DEPRECIATION_ONLY)).isEmpty();
        assertThatThrownBy(() -> app.fixedAssetUseCase.retire(assetId,
                new FixedAssetUseCase.DisposalOptions(LocalDate.of(2028, 7, 1), false, null), 0))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void registeredDepreciationSettingsAreKept() {
        UUID declining = app.fixedAssetUseCase.register(new FixedAssetUseCase.RegisterCommand(
                "車両", StandardAccounts.VEHICLES.code().value(),
                StandardAccounts.ACCUMULATED_DEPRECIATION_VEHICLES.code().value(),
                StandardAccounts.DEPRECIATION_EXPENSE.code().value(), 1_000_000, LocalDate.of(2026, 7, 1), 5, 0,
                new DepreciationSettings(DepreciationSettings.Method.DECLINING_BALANCE, new BigDecimal("0.400"), null,
                        DepreciationSettings.Rounding.HALF_UP))).id().value();

        assertThat(app.fixedAssetUseCase.get(declining, Optional.empty()).depreciation())
                .isEqualTo(new DepreciationSettings(DepreciationSettings.Method.DECLINING_BALANCE, new BigDecimal("0.4"),
                        null, DepreciationSettings.Rounding.HALF_UP));
    }

    private AccountingPeriod adjustAndClose(AccountingPeriod period) {
        app.runAdjustingEntries.run(period.id().value(), DEPRECIATION_ONLY);
        return app.periods.findById(app.closePeriod.close(period.id().value()).nextPeriodId()).orElseThrow();
    }
}
