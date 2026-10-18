package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCategory;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.account.AccountTag;
import com.example.accounting.model.account.AccountType;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.asset.FixedAssetId;
import com.example.accounting.model.asset.IntangibleAsset;
import com.example.accounting.model.inventory.InventoryMovement;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.securities.HeldToMaturityBond;
import com.example.accounting.service.ReversalService;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.asset.DepreciationSettings;
import com.example.accountingapp.application.port.HeldToMaturityBondRepository.RegisteredBond;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 保存 → 復元の往復と、saveAll の「全件か0件か」・version 検証・楽観ロック・追記のみ。 */
class JdbcRepositoryTest extends PostgresTestSupport {

    @Autowired
    JdbcAccountRepository accounts;
    @Autowired
    JdbcAccountingPeriodRepository periods;
    @Autowired
    JdbcJournalRepository journals;
    @Autowired
    JdbcFixedAssetRepository fixedAssets;
    @Autowired
    JdbcIntangibleAssetRepository intangibleAssets;
    @Autowired
    JdbcHeldToMaturityBondRepository bonds;
    @Autowired
    JdbcInventoryMovementRepository inventory;

    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        StandardAccounts.ALL.forEach(accounts::save);
        period = AccountingPeriod.startingAt(LocalDate.of(2026, 4, 1));
        periods.save(period);
    }

    private PostedJournal posted(LocalDate date, long amount) {
        return PostedJournal.post(period, Journal.of(JournalId.newId(), date, "売上", JournalType.NORMAL, List.of(
                JournalLine.debit(StandardAccounts.CASH, Money.ofYen(amount)),
                JournalLine.credit(StandardAccounts.SALES, Money.ofYen(amount)))));
    }

    @Test
    void account_roundTripWithTags() {
        Account account = Account.of(AccountCode.of("1081"), "備品(PC)", AccountType.ASSET, AccountCategory.FIXED_ASSET)
                .withTags(Set.of(AccountTag.of("IT"), AccountTag.of("本社")));
        accounts.save(account);
        accounts.save(account.rename("備品(パソコン)").withTags(Set.of(AccountTag.of("IT"))));

        Account restored = accounts.findByCode(AccountCode.of("1081")).orElseThrow();
        assertThat(restored.name()).isEqualTo("備品(パソコン)");
        assertThat(restored.tags()).containsExactly(AccountTag.of("IT"));
        assertThat(accounts.findAll()).hasSize(StandardAccounts.ALL.size() + 1);
        assertThat(accounts.existsByCode(AccountCode.of("1081"))).isTrue();
    }

    @Test
    void period_optimisticLockRejectsAStaleUpdate() {
        AccountingPeriod closed = period.close();
        periods.save(closed);

        assertThat(periods.findById(period.id())).contains(closed);
        assertThatThrownBy(() -> periods.save(period.close())).isInstanceOf(ConcurrencyConflictException.class);
        assertThat(periods.findContaining(LocalDate.of(2026, 12, 31))).contains(closed);
        assertThat(periods.findLatest()).contains(closed);
    }

    @Test
    void journal_roundTrip() {
        PostedJournal original = posted(LocalDate.of(2026, 4, 15), 280_000);
        journals.save(original);
        PostedJournal reversal = PostedJournal.post(period,
                new ReversalService().reverse(JournalId.newId(), LocalDate.of(2026, 4, 30), original.journal()));
        journals.save(reversal);

        assertThat(journals.findById(original.id())).contains(original);
        assertThat(journals.findByPeriod(period)).containsExactly(original, reversal);
        assertThat(journals.isReversed(original.id())).isTrue();
        assertThat(journals.isAccountUsed(StandardAccounts.SALES.code())).isTrue();
        assertThat(journals.isAccountUsed(StandardAccounts.PURCHASES.code())).isFalse();
    }

    /** 途中の行で制約違反(DB にない科目)を起こさせ、1件も残らないことを確認する。 */
    @Test
    void saveAll_savesNothingWhenALaterJournalViolatesAConstraint() {
        PostedJournal valid = posted(LocalDate.of(2026, 5, 1), 1_000);
        Account unknown = Account.of(AccountCode.of("999"), "DB にない科目", AccountType.ASSET, AccountCategory.CURRENT_ASSET);
        PostedJournal invalid = PostedJournal.post(period, Journal.of(JournalId.newId(), LocalDate.of(2026, 5, 2),
                "制約違反", JournalType.NORMAL, List.of(
                        JournalLine.debit(unknown, Money.ofYen(1_000)),
                        JournalLine.credit(StandardAccounts.SALES, Money.ofYen(1_000)))));

        assertThatThrownBy(() -> journals.saveAll(List.of(valid, invalid))).isInstanceOf(RuntimeException.class);
        assertThat(journals.findByPeriod(period)).isEmpty();
    }

    @Test
    void saveAll_failsAfterThePeriodVersionHasAdvanced() {
        PostedJournal postedWhileOpen = posted(LocalDate.of(2026, 12, 1), 5_000);
        periods.save(period.close());

        assertThatThrownBy(() -> journals.saveAll(List.of(postedWhileOpen)))
                .isInstanceOf(ConcurrencyConflictException.class);
        assertThat(journals.findByPeriod(period)).isEmpty();
    }

    @Test
    void journalsAreAppendOnlyInTheDatabase() {
        PostedJournal journal = posted(LocalDate.of(2026, 5, 1), 1_000);
        journals.save(journal);

        assertThatThrownBy(() -> jdbc.sql("update journal set description = 'x' where id = ?")
                .param(journal.id().value()).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from journal_line where journal_id = ?")
                .param(journal.id().value()).update()).hasMessageContaining("append-only");
    }

    /** 使用済みの科目を後から無効化しても、過去の仕訳は読める。 */
    @Test
    void journalWithADeactivatedAccountCanStillBeRestored() {
        Account temporary = Account.of(AccountCode.of("801"), "一時的な科目", AccountType.EXPENSE, AccountCategory.SG_AND_A);
        accounts.save(temporary);
        PostedJournal journal = PostedJournal.post(period, Journal.of(JournalId.newId(), LocalDate.of(2026, 5, 1),
                "一時的な費用", JournalType.NORMAL, List.of(
                        JournalLine.debit(temporary, Money.ofYen(1_000)),
                        JournalLine.credit(StandardAccounts.CASH, Money.ofYen(1_000)))));
        journals.save(journal);
        accounts.save(temporary.deactivate());

        assertThat(journals.findById(journal.id())).contains(journal);
    }

    @Test
    void fixedAsset_roundTripIncludingTheDepreciationMethod() {
        FixedAsset declining = new FixedAsset(FixedAssetId.newId(), "車両", StandardAccounts.VEHICLES,
                StandardAccounts.ACCUMULATED_DEPRECIATION_VEHICLES, StandardAccounts.DEPRECIATION_EXPENSE,
                Money.ofYen(1_000_000), LocalDate.of(2026, 7, 1), 5, Money.ZERO,
                new DepreciationSettings(DepreciationSettings.Method.DECLINING_BALANCE, new BigDecimal("0.4"), null,
                        DepreciationSettings.Rounding.HALF_UP).toPolicy());
        FixedAsset unitsOfProduction = new FixedAsset(FixedAssetId.newId(), "機械", StandardAccounts.EQUIPMENT,
                StandardAccounts.ACCUMULATED_DEPRECIATION_EQUIPMENT, StandardAccounts.DEPRECIATION_EXPENSE,
                Money.ofYen(1_000_000), LocalDate.of(2026, 4, 1), 5, Money.ofYen(100_000),
                new DepreciationSettings(DepreciationSettings.Method.UNITS_OF_PRODUCTION, null, 90_000L, null).toPolicy());
        fixedAssets.save(declining);
        fixedAssets.save(unitsOfProduction);

        assertThat(fixedAssets.findById(declining.id())).contains(declining);
        assertThat(fixedAssets.findById(unitsOfProduction.id())).contains(unitsOfProduction);

        // 圧縮記帳で新しいインスタンスになるため、上書き保存できる
        FixedAsset compressed = declining.compress(Money.ofYen(200_000), LocalDate.of(2026, 7, 1)).compressedAsset();
        fixedAssets.save(compressed);
        assertThat(fixedAssets.findById(declining.id()).orElseThrow().acquisitionCost()).isEqualTo(Money.ofYen(800_000));

        fixedAssets.markDisposed(declining.id(), LocalDate.of(2027, 1, 31));
        assertThat(fixedAssets.disposedOn(declining.id())).contains(LocalDate.of(2027, 1, 31));
        assertThat(fixedAssets.disposedOn(unitsOfProduction.id())).isEmpty();
    }

    @Test
    void intangibleAssetBondAndInventory_roundTrip() {
        IntangibleAsset software = new IntangibleAsset(FixedAssetId.newId(), "会計ソフト", StandardAccounts.SOFTWARE,
                StandardAccounts.SOFTWARE_AMORTIZATION, Money.ofYen(500_000), LocalDate.of(2026, 4, 1), 5);
        intangibleAssets.save(software);
        assertThat(intangibleAssets.findById(software.id())).contains(software);

        RegisteredBond bond = new RegisteredBond(UUID.randomUUID(), "A社社債", new HeldToMaturityBond(
                StandardAccounts.HELD_TO_MATURITY_BONDS, Money.ofYen(1_000_000), Money.ofYen(970_000),
                LocalDate.of(2026, 4, 1), LocalDate.of(2029, 3, 31)));
        bonds.save(bond);
        assertThat(bonds.findById(bond.id())).contains(bond);

        List<InventoryMovement> movements = List.of(
                new InventoryMovement.Receipt(LocalDate.of(2026, 10, 1), "前月繰越", 10, Money.ofYen(100)),
                new InventoryMovement.Issue(LocalDate.of(2026, 10, 12), "売上", 5));
        movements.forEach(movement -> inventory.append("A", movement));
        assertThat(inventory.findByItem("A")).containsExactlyElementsOf(movements);
        assertThat(inventory.findByItem("B")).isEmpty();
    }
}
