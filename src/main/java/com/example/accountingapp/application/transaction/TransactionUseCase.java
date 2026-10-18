package com.example.accountingapp.application.transaction;

import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.statement.Ledger;
import com.example.accounting.service.BadDebtService;
import com.example.accounting.service.BondService;
import com.example.accounting.service.CashOverShortService;
import com.example.accounting.service.DividendService;
import com.example.accounting.service.InstallmentPurchaseService;
import com.example.accounting.service.InstallmentPurchaseService.InstallmentPlan;
import com.example.accounting.service.MergerService;
import com.example.accounting.service.NotesReceivableService;
import com.example.accounting.service.ProvisionService;
import com.example.accounting.service.ProvisionType;
import com.example.accounting.service.RevenueRecognitionService;
import com.example.accounting.service.TradingSecuritiesService;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.JournalLineCommand;
import com.example.accountingapp.application.port.AuditContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * 個別取引(CLAUDE.md §6.3)。どれも「期を取得 → journalsSoFar を作る → ドメインサービス → 記帳 → 保存」の形。
 * 金額などの事実(割引料、売却代金、実際の手許現金など)は利用者が入力し、アプリは計算しない。
 */
@Service
public class TransactionUseCase {

    public static final String NAME = "Transaction";

    private final JournalBook book;
    private final AccountResolver accounts;
    private final BadDebtService badDebtService;
    private final BondService bondService;
    private final CashOverShortService cashOverShortService;
    private final DividendService dividendService;
    private final InstallmentPurchaseService installmentPurchaseService;
    private final MergerService mergerService;
    private final NotesReceivableService notesReceivableService;
    private final ProvisionService provisionService;
    private final RevenueRecognitionService revenueRecognitionService;
    private final TradingSecuritiesService tradingSecuritiesService;

    public TransactionUseCase(
            JournalBook book, AccountResolver accounts, BadDebtService badDebtService, BondService bondService,
            CashOverShortService cashOverShortService, DividendService dividendService,
            InstallmentPurchaseService installmentPurchaseService, MergerService mergerService,
            NotesReceivableService notesReceivableService, ProvisionService provisionService,
            RevenueRecognitionService revenueRecognitionService, TradingSecuritiesService tradingSecuritiesService) {
        this.book = book;
        this.accounts = accounts;
        this.badDebtService = badDebtService;
        this.bondService = bondService;
        this.cashOverShortService = cashOverShortService;
        this.dividendService = dividendService;
        this.installmentPurchaseService = installmentPurchaseService;
        this.mergerService = mergerService;
        this.notesReceivableService = notesReceivableService;
        this.provisionService = provisionService;
        this.revenueRecognitionService = revenueRecognitionService;
        this.tradingSecuritiesService = tradingSecuritiesService;
    }

    // ---- 貸倒れ・償却債権の回収 ----

    @Transactional
    public JournalId writeOffBadDebt(LocalDate date, String receivableAccountCode, long amount,
                                     boolean arisingInCurrentPeriod) {
        return record(date, soFar -> badDebtService.writeOff(
                accounts.resolve(receivableAccountCode), Money.ofYen(amount), arisingInCurrentPeriod, soFar, date));
    }

    @Transactional
    public JournalId recoverWrittenOff(LocalDate date, String proceedsAccountCode, long amount) {
        return record(date, soFar -> badDebtService.recoverWrittenOff(
                accounts.resolve(proceedsAccountCode), Money.ofYen(amount), date));
    }

    // ---- 端数利息付きの債券購入 ----

    /** 端数利息は {@link BondService#accruedInterest} が計算する(前回の利払日の翌日から購入日まで)。 */
    @Transactional
    public JournalId purchaseBondWithAccruedInterest(
            LocalDate date, String securitiesAccountCode, long faceValue, BigDecimal annualRate,
            LocalDate lastInterestDate, long purchasePrice, String paymentAccountCode) {
        return record(date, soFar -> {
            Money accruedInterest = bondService.accruedInterest(
                    Money.ofYen(faceValue), annualRate, lastInterestDate, date);
            return bondService.purchaseWithAccruedInterest(
                    accounts.resolve(securitiesAccountCode), Money.ofYen(purchasePrice), accruedInterest,
                    accounts.resolve(paymentAccountCode), date);
        });
    }

    // ---- 現金過不足 ----

    /** 手許現金の実際有高を入力する。帳簿残高と一致していれば仕訳は作られない(空を返す)。 */
    @Transactional
    public Optional<JournalId> recordCashDiscrepancy(LocalDate date, long actualCashOnHand) {
        return recordIfAny(date, soFar -> cashOverShortService.recordDiscrepancy(
                Money.ofYen(actualCashOnHand), soFar, date));
    }

    /** 決算時、原因不明の現金過不足を雑損・雑収入へ振り替える(締めの前に必要)。 */
    @Transactional
    public Optional<JournalId> settleCashOverShort(LocalDate date) {
        return recordIfAny(date, soFar -> cashOverShortService.settleAtPeriodEnd(soFar, date));
    }

    // ---- 剰余金の配当 ----

    public enum DividendSource {
        RETAINED_EARNINGS, OTHER_CAPITAL_SURPLUS
    }

    /** 資本金・資本準備金・利益準備金の残高は、当期の仕訳から {@link Ledger} で求めて渡す。 */
    @Transactional
    public JournalId declareDividends(LocalDate date, long dividendAmount, DividendSource source) {
        return record(date, soFar -> {
            Money capitalStock = Ledger.of(StandardAccounts.CAPITAL_STOCK, soFar).balance();
            Money capitalSurplus = Ledger.of(StandardAccounts.CAPITAL_SURPLUS, soFar).balance();
            Money statutoryReserve = Ledger.of(StandardAccounts.STATUTORY_RESERVE, soFar).balance();
            return source == DividendSource.RETAINED_EARNINGS
                    ? dividendService.declareDividends(
                    Money.ofYen(dividendAmount), capitalStock, capitalSurplus, statutoryReserve, date)
                    : dividendService.declareDividendsFromCapitalSurplus(
                    Money.ofYen(dividendAmount), capitalStock, capitalSurplus, statutoryReserve, date);
        });
    }

    // ---- 固定資産の割賦購入・支払 ----

    /** 割賦の条件。購入時と各回の支払で同じ条件を渡す。 */
    public record InstallmentPlanCommand(String assetAccountCode, long cashPrice, long totalPayment,
                                         int numberOfInstallments, String payableAccountCode) {
    }

    @Transactional
    public JournalId purchaseInInstallments(LocalDate date, InstallmentPlanCommand plan) {
        return record(date, soFar -> installmentPurchaseService.purchase(toPlan(plan), date));
    }

    @Transactional
    public JournalId payInstallment(LocalDate date, InstallmentPlanCommand plan, int installmentNumber,
                                    String paymentAccountCode) {
        return record(date, soFar -> installmentPurchaseService.payInstallment(
                toPlan(plan), installmentNumber, accounts.resolve(paymentAccountCode), date));
    }

    /** 支払予定表の1行。各回の支払額と利息はライブラリの {@link InstallmentPlan} が決める。 */
    public record InstallmentScheduleRow(int installmentNumber, Money payment, Money interest) {
    }

    @Transactional(readOnly = true)
    public List<InstallmentScheduleRow> installmentSchedule(InstallmentPlanCommand plan) {
        InstallmentPlan domainPlan = toPlan(plan);
        return IntStream.rangeClosed(1, domainPlan.numberOfInstallments())
                .mapToObj(n -> new InstallmentScheduleRow(n, domainPlan.installmentAmount(n), domainPlan.interestFor(n)))
                .toList();
    }

    private InstallmentPlan toPlan(InstallmentPlanCommand plan) {
        return new InstallmentPlan(
                accounts.resolve(plan.assetAccountCode()), Money.ofYen(plan.cashPrice()),
                Money.ofYen(plan.totalPayment()), plan.numberOfInstallments(),
                accounts.resolve(plan.payableAccountCode()));
    }

    // ---- 吸収合併 ----

    @Transactional
    public JournalId merge(LocalDate date, List<JournalLineCommand> acquiredAssets,
                           List<JournalLineCommand> assumedLiabilities, List<JournalLineCommand> consideration) {
        return record(date, soFar -> mergerService.merge(
                accounts.toLines(acquiredAssets), accounts.toLines(assumedLiabilities),
                accounts.toLines(consideration), date));
    }

    // ---- 手形・電子記録債権の割引 ----

    @Transactional
    public JournalId discountNote(LocalDate date, String proceedsAccountCode, long faceValue, long discountFee,
                                  boolean electronicallyRecorded) {
        return record(date, soFar -> electronicallyRecorded
                ? notesReceivableService.discountElectronicReceivable(
                accounts.resolve(proceedsAccountCode), Money.ofYen(faceValue), Money.ofYen(discountFee), date)
                : notesReceivableService.discount(
                accounts.resolve(proceedsAccountCode), Money.ofYen(faceValue), Money.ofYen(discountFee), date));
    }

    // ---- 引当金の取崩し ----

    @Transactional
    public JournalId useProvision(LocalDate date, ProvisionType type, long amount, String paymentAccountCode) {
        return record(date, soFar -> provisionService.use(
                type, Money.ofYen(amount), accounts.resolve(paymentAccountCode), soFar, date));
    }

    // ---- 収益認識 ----

    @Transactional
    public JournalId recordSaleWithExpectedRefund(LocalDate date, String receivableAccountCode,
                                                  long totalConsideration, long expectedRefund) {
        return record(date, soFar -> revenueRecognitionService.recordSaleWithExpectedRefund(
                accounts.resolve(receivableAccountCode), Money.ofYen(totalConsideration),
                Money.ofYen(expectedRefund), date));
    }

    @Transactional
    public JournalId recognizeContractAsset(LocalDate date, long revenue) {
        return record(date, soFar -> revenueRecognitionService.recognizeContractAsset(Money.ofYen(revenue), date));
    }

    /**
     * 契約の完了と請求。ライブラリは契約資産の残高を渡された仕訳から求める(この契約の分だけを渡すこと)。
     * アプリは契約を追跡しないため、{@code contractJournalIds}(この契約の契約資産を計上した仕訳)を指定させる。
     * 省略したときは当期の全仕訳を渡す(契約が1件だけのとき)。
     */
    @Transactional
    public JournalId billCompletedContract(LocalDate date, String receivableAccountCode, long remainingRevenue,
                                           Set<JournalId> contractJournalIds) {
        return record(date, soFar -> {
            List<Journal> contractJournals = contractJournalIds == null || contractJournalIds.isEmpty()
                    ? soFar
                    : soFar.stream().filter(journal -> contractJournalIds.contains(journal.id())).toList();
            return revenueRecognitionService.billCompletedContract(
                    accounts.resolve(receivableAccountCode), Money.ofYen(remainingRevenue), contractJournals, date);
        });
    }

    @Transactional
    public JournalId recognizeServiceRevenue(LocalDate date, String receivableAccountCode, long revenue,
                                             long costOfServices) {
        return record(date, soFar -> revenueRecognitionService.recognizeServiceRevenue(
                accounts.resolve(receivableAccountCode), Money.ofYen(revenue), Money.ofYen(costOfServices), date));
    }

    // ---- 売買目的有価証券の売却 ----

    /**
     * 売買目的有価証券の売却。数量を省略すると全額売却({@code sell})、指定すると一部売却({@code sellPortion})。
     * 端数利息は債券の一部売却のときだけ指定する。
     */
    @Transactional
    public JournalId sellTradingSecurities(LocalDate date, String proceedsAccountCode, long proceeds,
                                           Integer quantitySold, Integer quantityHeld, Long accruedInterest) {
        return record(date, soFar -> {
            if (quantitySold == null || quantityHeld == null) {
                return tradingSecuritiesService.sell(accounts.resolve(proceedsAccountCode), Money.ofYen(proceeds),
                        soFar, date);
            }
            return tradingSecuritiesService.sellPortion(
                    accounts.resolve(proceedsAccountCode), Money.ofYen(proceeds),
                    accruedInterest == null ? Money.ZERO : Money.ofYen(accruedInterest),
                    quantitySold, quantityHeld, soFar, date);
        });
    }

    // ---- 共通 ----

    private JournalId record(LocalDate date, Function<List<Journal>, Journal> create) {
        return AuditContext.within(NAME, () -> {
            AccountingPeriod period = book.periodContaining(date);
            Journal journal = create.apply(book.journalsOf(period));
            book.postAndSave(period, journal);
            return journal.id();
        });
    }

    private Optional<JournalId> recordIfAny(LocalDate date, Function<List<Journal>, Optional<Journal>> create) {
        return AuditContext.within(NAME, () -> {
            AccountingPeriod period = book.periodContaining(date);
            Optional<Journal> journal = create.apply(book.journalsOf(period));
            journal.ifPresent(j -> book.postAndSave(period, j));
            return journal.map(Journal::id);
        });
    }
}
