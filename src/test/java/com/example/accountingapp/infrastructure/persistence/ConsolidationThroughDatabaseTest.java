package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.Side;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.consolidation.IndividualStatements;
import com.example.accounting.model.consolidation.IntercompanyBalance;
import com.example.accounting.model.consolidation.Subsidiary;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.service.ConsolidationInput;
import com.example.accounting.service.ConsolidationResult;
import com.example.accounting.service.ConsolidationService;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.JournalLineCommand;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.IntercompanyBalanceCommand;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.SubsidiaryCommand;
import com.example.accountingapp.application.journal.PostJournalCommand;
import com.example.accountingapp.application.journal.PostJournalUseCase;
import com.example.accountingapp.application.master.CompanySetupUseCase;
import com.example.accountingapp.application.master.CompanyUseCase;
import com.example.accountingapp.application.port.CompanyContext;
import com.example.accountingapp.application.port.CompanyRepository;
import com.example.accountingapp.application.statement.StatementQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 複数の会社の帳簿と連結を、DB を通して確かめる。会社ごとにデータが分かれること、連結の資料が保存・復元できること、
 * アプリが組み立てた連結の入力がライブラリに正しく渡ること(同じ仕訳を直接ライブラリに渡した結果と一致すること)。
 *
 * <p>連結のルール自体はライブラリで未検証(UNVERIFIED_RULES.md の 14-x)で、ここでは金額の正しさを確かめない
 * (期待値を自分で作らない)。確かめるのは、連結精算表の修正・消去欄の貸借一致と、連結貸借対照表の貸借一致だけ。
 */
@Tag("unverified")
class ConsolidationThroughDatabaseTest extends PostgresTestSupport {

    private static final LocalDate START = LocalDate.of(2026, 4, 1);
    private static final LocalDate END = LocalDate.of(2027, 3, 31);

    @Autowired
    CompanyUseCase companyUseCase;
    @Autowired
    CompanySetupUseCase companySetup;
    @Autowired
    CompanyContext companyContext;
    @Autowired
    PostJournalUseCase postJournal;
    @Autowired
    ConsolidationUseCase consolidation;
    @Autowired
    StatementQueryService statements;
    @Autowired
    JournalBook book;

    private final CompanyId parent = CompanyRepository.DEFAULT_COMPANY;
    private AccountingPeriod parentPeriod;
    private CompanyId subsidiary;

    private void post(LocalDate date, String description, Account debit, Account credit, long amount) {
        postJournal.post(new PostJournalCommand(date, description, JournalType.NORMAL, List.of(
                new JournalLineCommand(debit.code().value(), Side.DEBIT, amount),
                new JournalLineCommand(credit.code().value(), Side.CREDIT, amount))));
    }

    @BeforeEach
    void setUpGroup() {
        parentPeriod = companySetup.setUp(START);
        post(START, "出資による開業", StandardAccounts.CASH, StandardAccounts.CAPITAL_STOCK, 5_000_000);
        post(LocalDate.of(2026, 6, 1), "子会社への売上", StandardAccounts.ACCOUNTS_RECEIVABLE, StandardAccounts.SALES, 50_000);
        post(LocalDate.of(2026, 7, 1), "現金売上", StandardAccounts.CASH, StandardAccounts.SALES, 500_000);
        post(END, "子会社株式の取得", StandardAccounts.SUBSIDIARY_STOCKS, StandardAccounts.CASH, 1_000_000);

        subsidiary = companyUseCase.create("子会社", START).company().id();
        companyContext.callAs(subsidiary, () -> {
            post(START, "出資による開業", StandardAccounts.CASH, StandardAccounts.CAPITAL_STOCK, 1_000_000);
            post(LocalDate.of(2026, 6, 1), "親会社からの仕入", StandardAccounts.PURCHASES, StandardAccounts.ACCOUNTS_PAYABLE, 50_000);
            post(LocalDate.of(2026, 8, 1), "現金売上", StandardAccounts.CASH, StandardAccounts.SALES, 300_000);
            post(LocalDate.of(2026, 9, 1), "給料", StandardAccounts.SALARIES, StandardAccounts.CASH, 100_000);
            return null;
        });
    }

    private SubsidiaryCommand acquiredAtPeriodEnd() {
        return new SubsidiaryCommand(subsidiary.value(), END, new BigDecimal("0.8"), 1_000_000, List.of(
                new SubsidiaryCommand.EquityLine(StandardAccounts.CAPITAL_STOCK.code().value(), 1_000_000),
                new SubsidiaryCommand.EquityLine(StandardAccounts.RETAINED_EARNINGS.code().value(), 150_000)), 10);
    }

    @Test
    void eachCompanyHasItsOwnBooks() {
        // 期首日が同じ期を、会社ごとに持てる
        AccountingPeriod subsidiaryPeriod = companyContext.callAs(subsidiary, () -> book.latestPeriod());
        assertThat(subsidiaryPeriod.startDate()).isEqualTo(parentPeriod.startDate());
        assertThat(subsidiaryPeriod.id()).isNotEqualTo(parentPeriod.id());

        assertThat(statements.journals(parentPeriod.id().value())).hasSize(4);
        assertThat(companyContext.callAs(subsidiary, () -> statements.journals(subsidiaryPeriod.id().value()))).hasSize(4);
        // ほかの会社の期は見えない
        assertThatThrownBy(() -> statements.journals(subsidiaryPeriod.id().value()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(companyUseCase.list()).extracting(CompanyRepository.Company::name).containsExactly("既定の会社", "子会社");
    }

    @Test
    void consolidation_passesTheBooksAndMaterialsOfEveryCompanyToTheLibrary() {
        consolidation.registerSubsidiary(acquiredAtPeriodEnd());
        consolidation.addBalance(parentPeriod.id().value(), new IntercompanyBalanceCommand(parent.value(),
                StandardAccounts.ACCOUNTS_RECEIVABLE.code().value(), StandardAccounts.ACCOUNTS_PAYABLE.code().value(),
                50_000, 0, 0));

        ConsolidationUseCase.Result result = consolidation.consolidate(parentPeriod.id().value());

        assertThat(result.companies()).extracting(CompanyRepository.Company::id).containsExactly(parent, subsidiary);
        ConsolidationResult expected = new ConsolidationService().consolidate(expectedInput());
        assertThat(result.result().adjustments()).hasSameSizeAs(expected.adjustments());
        assertThat(result.result().worksheet()).isEqualTo(expected.worksheet());
        assertThat(result.result().incomeStatement()).isEqualTo(expected.incomeStatement());
        assertThat(result.result().balanceSheet()).isEqualTo(expected.balanceSheet());

        assertThat(result.result().worksheet().isAdjustmentBalanced()).isTrue();
        assertThat(result.result().balanceSheet().isBalanced()).isTrue();
    }

    /** アプリを通さずに、同じ仕訳と資料からライブラリの入力を組み立てる。 */
    private ConsolidationInput expectedInput() {
        AccountingPeriod subsidiaryPeriod = companyContext.callAs(subsidiary, () -> book.latestPeriod());
        return ConsolidationInput.of(END,
                        new IndividualStatements(parent, book.journalsOf(parentPeriod)),
                        List.of(new Subsidiary(subsidiary, END, new BigDecimal("0.8"), Money.ofYen(1_000_000),
                                java.util.Map.of(StandardAccounts.CAPITAL_STOCK, Money.ofYen(1_000_000),
                                        StandardAccounts.RETAINED_EARNINGS, Money.ofYen(150_000)), 10)),
                        List.of(new IndividualStatements(subsidiary,
                                companyContext.callAs(subsidiary, () -> book.journalsOf(subsidiaryPeriod)))))
                .withIntercompanyBalances(List.of(IntercompanyBalance.of(parent, StandardAccounts.ACCOUNTS_RECEIVABLE,
                        StandardAccounts.ACCOUNTS_PAYABLE, Money.ofYen(50_000))));
    }

    @Test
    void subsidiaryAndMaterials_roundTrip() {
        consolidation.registerSubsidiary(acquiredAtPeriodEnd());
        UUID balanceId = consolidation.addBalance(parentPeriod.id().value(), new IntercompanyBalanceCommand(
                subsidiary.value(), StandardAccounts.ACCOUNTS_RECEIVABLE.code().value(),
                StandardAccounts.ACCOUNTS_PAYABLE.code().value(), 30_000, 600, 200)).id();
        consolidation.addTransaction(parentPeriod.id().value(), new ConsolidationUseCase.IntercompanyTransactionCommand(
                StandardAccounts.SALES.code().value(), StandardAccounts.PURCHASES.code().value(), 50_000));
        consolidation.addUnrealizedProfit(parentPeriod.id().value(), new ConsolidationUseCase.UnrealizedProfitCommand(
                ConsolidationUseCase.UnrealizedProfitCommand.Kind.LAND, parent.value(), null, null, null, 40_000L, END));

        ConsolidationUseCase.SubsidiaryView view = consolidation.subsidiaries().getFirst();
        assertThat(view.company().name()).isEqualTo("子会社");
        assertThat(view.subsidiary().parentOwnership()).isEqualByComparingTo("0.8");
        assertThat(view.subsidiary().equityAtAcquisition()).containsEntry(StandardAccounts.RETAINED_EARNINGS, Money.ofYen(150_000));

        ConsolidationUseCase.Materials materials = consolidation.materials(parentPeriod.id().value());
        assertThat(materials.balances()).singleElement().satisfies(balance -> assertThat(balance.item()).isEqualTo(
                new IntercompanyBalance(subsidiary, StandardAccounts.ACCOUNTS_RECEIVABLE, StandardAccounts.ACCOUNTS_PAYABLE,
                        Money.ofYen(30_000), Money.ofYen(600), Money.ofYen(200))));
        assertThat(materials.transactions()).hasSize(1);
        assertThat(materials.unrealizedProfits()).hasSize(1);

        consolidation.removeMaterial(parentPeriod.id().value(), balanceId);
        assertThat(consolidation.materials(parentPeriod.id().value()).balances()).isEmpty();
        assertThatThrownBy(() -> consolidation.removeMaterial(parentPeriod.id().value(), balanceId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsMaterialsAndSubsidiariesOutsideTheGroup() {
        assertThatThrownBy(() -> consolidation.registerSubsidiary(new SubsidiaryCommand(parent.value(), END,
                BigDecimal.ONE, 1, List.of(new SubsidiaryCommand.EquityLine("301", 1)), 1)))
                .isInstanceOf(InvalidCommandException.class);
        // 子会社として登録していない会社は、連結の資料に出てこられない
        assertThatThrownBy(() -> consolidation.addBalance(parentPeriod.id().value(), new IntercompanyBalanceCommand(
                subsidiary.value(), "102", "201", 1, 0, 0)))
                .isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> consolidation.consolidate(parentPeriod.id().value()))
                .isInstanceOf(OperationNotAllowedException.class);

        consolidation.registerSubsidiary(acquiredAtPeriodEnd());
        assertThatThrownBy(() -> consolidation.registerSubsidiary(acquiredAtPeriodEnd()))
                .isInstanceOf(OperationNotAllowedException.class);
        // 子会社の帳簿からは親会社の連結の操作はできない(孫会社は扱わない)
        CompanyId other = companyUseCase.create("別会社", START).company().id();
        assertThatThrownBy(() -> companyContext.callAs(subsidiary, () -> consolidation.registerSubsidiary(
                new SubsidiaryCommand(other.value(), END, BigDecimal.ONE, 1,
                        List.of(new SubsidiaryCommand.EquityLine("301", 1)), 1))))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void consolidation_requiresTheSubsidiaryPeriodWithTheSameYearEnd() {
        CompanyId late = companyUseCase.create("決算日が違う子会社", LocalDate.of(2026, 7, 1)).company().id();
        consolidation.registerSubsidiary(new SubsidiaryCommand(late.value(), END, BigDecimal.ONE, 1_000_000,
                List.of(new SubsidiaryCommand.EquityLine("301", 1_000_000)), 10));

        assertThatThrownBy(() -> consolidation.consolidate(parentPeriod.id().value()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("no accounting period ending on " + END);
    }
}
