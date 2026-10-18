package com.example.accountingapp.application.closing;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.asset.FixedAssetRepository;
import com.example.accounting.model.asset.IntangibleAsset;
import com.example.accounting.model.asset.UsageBasedDepreciationPolicy;
import com.example.accounting.model.inventory.InventoryCount;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.service.AdjustingEntryService;
import com.example.accounting.service.TradingSecuritiesService;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.closing.AdjustingEntriesCommand.FairValueCommand;
import com.example.accountingapp.application.closing.AdjustingEntriesCommand.InventoryCountCommand;
import com.example.accountingapp.application.closing.AdjustingEntriesCommand.InventoryMethod;
import com.example.accountingapp.application.closing.AdjustingEntriesCommand.ProvisionCommand;
import com.example.accountingapp.application.port.AuditContext;
import com.example.accountingapp.application.port.FixedAssetDisposalRegistry;
import com.example.accountingapp.application.port.HeldToMaturityBondRepository;
import com.example.accountingapp.application.port.IntangibleAssetRepository;
import com.example.accountingapp.application.port.JournalQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 決算整理(CLAUDE.md §6.4)。{@link AdjustingEntryService} の Javadoc の順序で呼び、各手順の結果を
 * {@code soFar} に足してから次の手順へ進む(法人税等は、それまでの整理がすべて入った税引前当期純利益に依存する)。
 * 途中では保存せず、最後にまとめて記帳・保存する。
 *
 * <p>手順1(再振替)は {@link ReverseAccrualsUseCase} で期首に実施済み、手順7(経過勘定・貯蔵品)は利用者が
 * 事前に ADJUSTING の仕訳として手入力しておく運用。
 */
@Service
public class RunAdjustingEntriesUseCase {

    public static final String NAME = "RunAdjustingEntries";

    private final JournalBook book;
    private final JournalQuery journalQuery;
    private final AccountResolver accounts;
    private final AdjustingEntryService adjustingEntryService;
    private final TradingSecuritiesService tradingSecuritiesService;
    private final FixedAssetRepository fixedAssetRepository;
    private final FixedAssetDisposalRegistry disposals;
    private final IntangibleAssetRepository intangibleAssetRepository;
    private final HeldToMaturityBondRepository bondRepository;

    public RunAdjustingEntriesUseCase(
            JournalBook book, JournalQuery journalQuery, AccountResolver accounts,
            AdjustingEntryService adjustingEntryService, TradingSecuritiesService tradingSecuritiesService,
            FixedAssetRepository fixedAssetRepository, FixedAssetDisposalRegistry disposals,
            IntangibleAssetRepository intangibleAssetRepository, HeldToMaturityBondRepository bondRepository) {
        this.book = book;
        this.journalQuery = journalQuery;
        this.accounts = accounts;
        this.adjustingEntryService = adjustingEntryService;
        this.tradingSecuritiesService = tradingSecuritiesService;
        this.fixedAssetRepository = fixedAssetRepository;
        this.disposals = disposals;
        this.intangibleAssetRepository = intangibleAssetRepository;
        this.bondRepository = bondRepository;
    }

    /** プレビュー。保存せずに、生成される決算整理仕訳を返す(記帳前の {@link Journal})。 */
    @Transactional(readOnly = true)
    public List<Journal> preview(UUID periodId, AdjustingEntriesCommand command) {
        AccountingPeriod period = book.period(periodId);
        requireNotYetRun(period);
        return generate(period, command);
    }

    /** 確定。生成した決算整理仕訳をまとめて記帳・保存する。 */
    @Transactional
    public List<JournalId> run(UUID periodId, AdjustingEntriesCommand command) {
        if (!command.accrualsConfirmed()) {
            throw new InvalidCommandException(
                    "accrued and deferred items (step 7) must be posted before running adjusting entries");
        }
        return AuditContext.within(NAME, () -> {
            AccountingPeriod period = book.period(periodId);
            requireNotYetRun(period);
            List<Journal> generated = generate(period, command);
            book.postAllAndSave(period, generated);
            return generated.stream().map(Journal::id).toList();
        });
    }

    /** 二重実行の防止。やり直すときは、整理仕訳を赤黒で取り消してから。 */
    private void requireNotYetRun(AccountingPeriod period) {
        if (journalQuery.existsUnreversedByPeriodAndTypeAndOrigin(period, JournalType.ADJUSTING, NAME)) {
            throw new OperationNotAllowedException(
                    "adjusting entries have already been run for this period; reverse them before running again");
        }
    }

    private List<Journal> generate(AccountingPeriod period, AdjustingEntriesCommand command) {
        Steps steps = new Steps(book.journalsOf(period));
        LocalDate date = period.endDate();

        // 2. 当座借越
        if (command.reclassifyBankOverdraft()) {
            Account checking = command.checkingAccountCode() == null
                    ? StandardAccounts.CHECKING_ACCOUNT : accounts.resolve(command.checkingAccountCode());
            steps.add(adjustingEntryService.reclassifyBankOverdraft(checking, steps.soFar(), date));
        }

        // 3. 売上原価(三分法のみ) → 棚卸減耗損・商品評価損
        if (command.inventoryMethod() == InventoryMethod.PERIODIC
                && (command.beginningInventory() != null || command.endingInventory() != null)) {
            steps.addAll(adjustingEntryService.recognizeCostOfSales(
                    yen(command.beginningInventory()), yen(command.endingInventory()), date));
        }
        for (InventoryCountCommand c : command.inventoryCounts()) {
            InventoryCount count = new InventoryCount(
                    c.bookQuantity(), c.actualQuantity(), Money.ofYen(c.unitCost()), Money.ofYen(c.netRealizableValue()));
            steps.addAll(command.inventoryMethod() == InventoryMethod.PERIODIC
                    ? adjustingEntryService.recognizeInventoryLosses(count, date)
                    : adjustingEntryService.recognizeInventoryLosses(count, StandardAccounts.MERCHANDISE,
                    StandardAccounts.INVENTORY_SHRINKAGE_LOSS, StandardAccounts.INVENTORY_VALUATION_LOSS, date));
        }

        // 4. 減価償却(有形固定資産ごと) → 償却(無形固定資産ごと)
        for (FixedAsset asset : fixedAssetRepository.findAll()) {
            if (disposals.disposedOn(asset.id()).isPresent()) {
                continue;
            }
            if (asset.depreciationPolicy() instanceof UsageBasedDepreciationPolicy) {
                Long usage = command.fixedAssetUsages().get(asset.id().value());
                if (usage == null) {
                    throw new InvalidCommandException("usage for this period is required for " + asset.name());
                }
                steps.add(asset.depreciationEntryByUsage(steps.soFar(), usage, date));
            } else {
                steps.add(adjustingEntryService.depreciate(asset, steps.soFar(), period));
            }
        }
        for (IntangibleAsset asset : intangibleAssetRepository.findAll()) {
            steps.add(adjustingEntryService.amortize(asset, steps.soFar(), period));
        }

        // 5. 有価証券: 売買目的 → 満期保有目的 → その他
        if (command.tradingSecuritiesFairValue() != null) {
            steps.add(tradingSecuritiesService.valuateAtPeriodEnd(
                    Money.ofYen(command.tradingSecuritiesFairValue()), steps.soFar(), date));
        }
        for (HeldToMaturityBondRepository.RegisteredBond registered : bondRepository.findAll()) {
            steps.add(adjustingEntryService.amortizeHeldToMaturityBond(registered.bond(), steps.soFar(), date));
        }
        for (FairValueCommand afs : command.availableForSaleSecurities()) {
            steps.add(adjustingEntryService.valuateAvailableForSaleSecurities(
                    accounts.resolve(afs.accountCode()), Money.ofYen(afs.fairValue()), steps.soFar(), date));
        }

        // 6. 貸倒引当金 → その他の引当金
        if (command.allowanceForDoubtfulAccounts() != null) {
            steps.add(adjustingEntryService.adjustAllowanceForDoubtfulAccounts(
                    Money.ofYen(command.allowanceForDoubtfulAccounts()), steps.soFar(), date));
        }
        for (ProvisionCommand provision : command.provisions()) {
            Money required = Money.ofYen(provision.requiredBalance());
            switch (provision.method()) {
                case DIFFERENCE -> steps.add(
                        adjustingEntryService.adjustProvision(provision.type(), required, steps.soFar(), date));
                case REPLACEMENT -> steps.addAll(
                        adjustingEntryService.replaceProvision(provision.type(), required, steps.soFar(), date));
            }
        }
        if (command.retirementBenefitExpense() != null) {
            steps.add(adjustingEntryService.accrueRetirementBenefits(
                    Money.ofYen(command.retirementBenefitExpense()), date));
        }

        // 7. 経過勘定・貯蔵品は手入力済み(soFar に入っている)

        // 8. 法人税等 → 消費税
        if (command.incomeTaxAmount() != null) {
            steps.add(adjustingEntryService.accrueIncomeTax(Money.ofYen(command.incomeTaxAmount()), steps.soFar(), date));
        }
        if (command.settleConsumptionTax()) {
            steps.add(adjustingEntryService.settleConsumptionTax(steps.soFar(), date));
        }

        return steps.generated();
    }

    private static Money yen(Long amount) {
        return amount == null ? Money.ZERO : Money.ofYen(amount);
    }

    /** 各手順の結果を、次の手順の {@code journalsSoFar} と、最後に保存する一覧の両方に足していく。 */
    private static final class Steps {

        private final List<Journal> soFar;
        private final List<Journal> generated = new ArrayList<>();

        Steps(List<Journal> journalsSoFar) {
            this.soFar = new ArrayList<>(journalsSoFar);
        }

        List<Journal> soFar() {
            return List.copyOf(soFar);
        }

        void add(Journal journal) {
            soFar.add(journal);
            generated.add(journal);
        }

        void add(Optional<Journal> journal) {
            journal.ifPresent(this::add);
        }

        void addAll(List<Journal> journals) {
            journals.forEach(this::add);
        }

        List<Journal> generated() {
            return List.copyOf(generated);
        }
    }
}
