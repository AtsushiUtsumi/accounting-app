package com.example.accountingapp.application.consolidation;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.account.AccountRepository;
import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.consolidation.IndividualStatements;
import com.example.accounting.model.consolidation.IntercompanyBalance;
import com.example.accounting.model.consolidation.IntercompanyTransaction;
import com.example.accounting.model.consolidation.Subsidiary;
import com.example.accounting.model.consolidation.UnrealizedProfit;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.service.ConsolidationInput;
import com.example.accounting.service.ConsolidationResult;
import com.example.accounting.service.ConsolidationService;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.CompanyContext;
import com.example.accountingapp.application.port.CompanyRepository;
import com.example.accountingapp.application.port.CompanyRepository.Company;
import com.example.accountingapp.application.port.ConsolidationMaterialRepository;
import com.example.accountingapp.application.port.ConsolidationMaterialRepository.Registered;
import com.example.accountingapp.application.port.PeriodLookup;
import com.example.accountingapp.application.port.SubsidiaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 連結会計(CLAUDE.md §6.9)。いま操作している会社を親会社とし、子会社の登録、連結の資料(債権債務・取引高・
 * 未実現利益)の入力、連結財務諸表の作成を行う。
 *
 * <p>連結修正仕訳・連結財務諸表は保存しない。作成のたびに、各社の損益振替前の仕訳と保存した資料を
 * {@link ConsolidationService} に渡して作り直す。子会社の期は、親会社の期と期末日が同じものを使う。
 * 各社の科目はコードで同じ科目とみなすので、親会社と子会社で同じコードの科目の種類・区分が違えば作成を拒否する。
 */
@Service
public class ConsolidationUseCase {

    private final CompanyContext companyContext;
    private final CompanyRepository companyRepository;
    private final SubsidiaryRepository subsidiaryRepository;
    private final ConsolidationMaterialRepository materialRepository;
    private final AccountRepository accountRepository;
    private final PeriodLookup periodLookup;
    private final JournalBook book;
    private final AccountResolver accountResolver;
    private final ConsolidationService consolidationService;

    public ConsolidationUseCase(CompanyContext companyContext, CompanyRepository companyRepository,
                                SubsidiaryRepository subsidiaryRepository, ConsolidationMaterialRepository materialRepository,
                                AccountRepository accountRepository, PeriodLookup periodLookup, JournalBook book,
                                AccountResolver accountResolver, ConsolidationService consolidationService) {
        this.companyContext = companyContext;
        this.companyRepository = companyRepository;
        this.subsidiaryRepository = subsidiaryRepository;
        this.materialRepository = materialRepository;
        this.accountRepository = accountRepository;
        this.periodLookup = periodLookup;
        this.book = book;
        this.accountResolver = accountResolver;
        this.consolidationService = consolidationService;
    }

    // ---- 子会社 ----

    /**
     * 子会社の登録の入力(支配獲得時の事実)。
     *
     * @param equityAtAcquisition 支配獲得日の子会社の資本(資本金・資本剰余金・利益剰余金の各科目)
     */
    public record SubsidiaryCommand(UUID companyId, LocalDate acquisitionDate, BigDecimal parentOwnership,
                                    long investmentCost, List<EquityLine> equityAtAcquisition,
                                    int goodwillAmortizationYears) {

        public record EquityLine(String accountCode, long amount) {
        }
    }

    public record SubsidiaryView(Company company, Subsidiary subsidiary) {
    }

    @Transactional(readOnly = true)
    public List<SubsidiaryView> subsidiaries() {
        return subsidiaryRepository.findByParent(companyContext.current()).stream()
                .map(subsidiary -> new SubsidiaryView(company(subsidiary.company()), subsidiary))
                .toList();
    }

    /** いま操作している会社の子会社として登録する。孫会社(子会社の子会社)は扱わない。 */
    @Transactional
    public SubsidiaryView registerSubsidiary(SubsidiaryCommand command) {
        CompanyId parent = companyContext.current();
        CompanyId child = new CompanyId(command.companyId());
        Company company = company(child);
        if (child.equals(parent)) {
            throw new InvalidCommandException("a company cannot be its own subsidiary");
        }
        if (subsidiaryRepository.findParentOf(child).isPresent()) {
            throw new OperationNotAllowedException("company is already a subsidiary: " + child.value());
        }
        if (subsidiaryRepository.findParentOf(parent).isPresent() || !subsidiaryRepository.findByParent(child).isEmpty()) {
            throw new OperationNotAllowedException("sub-subsidiaries are not supported");
        }
        Map<Account, Money> equity = new LinkedHashMap<>();
        for (SubsidiaryCommand.EquityLine line : command.equityAtAcquisition()) {
            if (equity.put(accountResolver.resolve(line.accountCode()), Money.ofYen(line.amount())) != null) {
                throw new InvalidCommandException("duplicate equity account: " + line.accountCode());
            }
        }
        Subsidiary subsidiary = new Subsidiary(child, command.acquisitionDate(), command.parentOwnership(),
                Money.ofYen(command.investmentCost()), equity, command.goodwillAmortizationYears());
        subsidiaryRepository.save(parent, subsidiary);
        return new SubsidiaryView(company, subsidiary);
    }

    @Transactional
    public void removeSubsidiary(UUID companyId) {
        CompanyId child = new CompanyId(companyId);
        if (!subsidiaryRepository.findParentOf(child).map(companyContext.current()::equals).orElse(false)) {
            throw new ResourceNotFoundException("subsidiary not found: " + companyId);
        }
        subsidiaryRepository.delete(child);
    }

    // ---- 連結の資料(親会社の期ごと) ----

    public record IntercompanyBalanceCommand(UUID creditorCompanyId, String receivableCode, String payableCode,
                                             long amount, long allowance, long allowanceAtBeginning) {
    }

    public record IntercompanyTransactionCommand(String revenueCode, String expenseCode, long amount) {
    }

    /** 未実現利益。棚卸資産なら inventoryAccountCode・ending・beginning、土地なら amount・saleDate を使う。 */
    public record UnrealizedProfitCommand(Kind kind, UUID sellerCompanyId, String inventoryAccountCode, Long ending,
                                          Long beginning, Long amount, LocalDate saleDate) {

        public enum Kind { INVENTORY, LAND }
    }

    public record Materials(List<Registered<IntercompanyBalance>> balances,
                            List<Registered<IntercompanyTransaction>> transactions,
                            List<Registered<UnrealizedProfit>> unrealizedProfits) {
    }

    @Transactional(readOnly = true)
    public Materials materials(UUID periodId) {
        AccountingPeriod period = book.period(periodId);
        return new Materials(materialRepository.findBalances(period), materialRepository.findTransactions(period),
                materialRepository.findUnrealizedProfits(period));
    }

    @Transactional
    public Registered<IntercompanyBalance> addBalance(UUID periodId, IntercompanyBalanceCommand command) {
        AccountingPeriod period = book.period(periodId);
        IntercompanyBalance balance = new IntercompanyBalance(groupCompany(command.creditorCompanyId()),
                accountResolver.resolve(command.receivableCode()), accountResolver.resolve(command.payableCode()),
                Money.ofYen(command.amount()), Money.ofYen(command.allowance()),
                Money.ofYen(command.allowanceAtBeginning()));
        Registered<IntercompanyBalance> registered = new Registered<>(UUID.randomUUID(), balance);
        materialRepository.addBalance(period, registered);
        return registered;
    }

    @Transactional
    public Registered<IntercompanyTransaction> addTransaction(UUID periodId, IntercompanyTransactionCommand command) {
        AccountingPeriod period = book.period(periodId);
        IntercompanyTransaction transaction = new IntercompanyTransaction(accountResolver.resolve(command.revenueCode()),
                accountResolver.resolve(command.expenseCode()), Money.ofYen(command.amount()));
        Registered<IntercompanyTransaction> registered = new Registered<>(UUID.randomUUID(), transaction);
        materialRepository.addTransaction(period, registered);
        return registered;
    }

    @Transactional
    public Registered<UnrealizedProfit> addUnrealizedProfit(UUID periodId, UnrealizedProfitCommand command) {
        AccountingPeriod period = book.period(periodId);
        CompanyId seller = groupCompany(command.sellerCompanyId());
        UnrealizedProfit profit = switch (command.kind()) {
            case INVENTORY -> new UnrealizedProfit.Inventory(seller,
                    accountResolver.resolve(required(command.inventoryAccountCode(), "inventoryAccountCode")),
                    Money.ofYen(required(command.ending(), "ending")),
                    Money.ofYen(command.beginning() == null ? 0 : command.beginning()));
            case LAND -> new UnrealizedProfit.Land(seller, Money.ofYen(required(command.amount(), "amount")),
                    required(command.saleDate(), "saleDate"));
        };
        Registered<UnrealizedProfit> registered = new Registered<>(UUID.randomUUID(), profit);
        materialRepository.addUnrealizedProfit(period, registered);
        return registered;
    }

    @Transactional
    public void removeMaterial(UUID periodId, UUID materialId) {
        if (!materialRepository.delete(book.period(periodId), materialId)) {
            throw new ResourceNotFoundException("consolidation material not found: " + materialId);
        }
    }

    // ---- 連結財務諸表 ----

    /** @param companies 連結した会社(親会社、子会社の順)。連結精算表の個別財務諸表の欄の並び */
    public record Result(List<Company> companies, ConsolidationResult result) {
    }

    /** 親会社の期について、連結修正仕訳と連結精算表・連結損益計算書・連結貸借対照表を作る(保存しない)。 */
    @Transactional(readOnly = true)
    public Result consolidate(UUID periodId) {
        CompanyId parent = companyContext.current();
        AccountingPeriod period = book.period(periodId);
        LocalDate end = period.endDate();
        List<Subsidiary> subsidiaries = subsidiaryRepository.findByParent(parent).stream()
                .filter(subsidiary -> !subsidiary.acquisitionDate().isAfter(end))
                .toList();
        if (subsidiaries.isEmpty()) {
            throw new OperationNotAllowedException("no subsidiary has been acquired by " + end);
        }
        Map<AccountCode, Account> chart = accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::code, Function.identity()));

        List<Company> companies = new ArrayList<>();
        companies.add(company(parent));
        List<IndividualStatements> subsidiaryStatements = new ArrayList<>();
        for (Subsidiary subsidiary : subsidiaries) {
            companies.add(company(subsidiary.company()));
            List<Journal> journals = companyContext.callAs(subsidiary.company(), () -> {
                AccountingPeriod subsidiaryPeriod = periodLookup.findEndingOn(end)
                        .orElseThrow(() -> new ResourceNotFoundException("subsidiary " + subsidiary.company().value()
                                + " has no accounting period ending on " + end));
                return beforeClosing(book.journalsOf(subsidiaryPeriod));
            });
            requireSameChart(chart, journals, subsidiary.company());
            subsidiaryStatements.add(new IndividualStatements(subsidiary.company(), journals));
        }

        Materials materials = materials(periodId);
        ConsolidationInput input = new ConsolidationInput(end,
                new IndividualStatements(parent, beforeClosing(book.journalsOf(period))),
                subsidiaries, subsidiaryStatements,
                items(materials.balances()), items(materials.transactions()), items(materials.unrealizedProfits()));
        return new Result(companies, consolidationService.consolidate(input));
    }

    /** 連結の入力は損益振替の前の仕訳(締めた期なら損益振替の仕訳を除く)。 */
    private static List<Journal> beforeClosing(List<Journal> journals) {
        return journals.stream().filter(journal -> journal.type() != JournalType.CLOSING).toList();
    }

    private static void requireSameChart(Map<AccountCode, Account> chart, List<Journal> journals, CompanyId company) {
        for (Journal journal : journals) {
            for (JournalLine line : journal.lines()) {
                Account account = line.account();
                Account parentAccount = chart.get(account.code());
                if (parentAccount != null && (parentAccount.type() != account.type()
                        || parentAccount.category() != account.category())) {
                    throw new OperationNotAllowedException("account " + account.code().value() + " of subsidiary "
                            + company.value() + " differs from the parent's chart of accounts in type or category");
                }
            }
        }
    }

    private static <T> List<T> items(List<Registered<T>> registered) {
        return registered.stream().map(Registered::item).toList();
    }

    /** 連結の資料に出てくる会社は、親会社(いま操作している会社)かその子会社でなければならない。 */
    private CompanyId groupCompany(UUID id) {
        CompanyId company = new CompanyId(required(id, "company ID"));
        CompanyId parent = companyContext.current();
        if (!company.equals(parent)
                && !subsidiaryRepository.findParentOf(company).map(parent::equals).orElse(false)) {
            throw new InvalidCommandException("company is neither this company nor its subsidiary: " + id);
        }
        return company;
    }

    private Company company(CompanyId id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("company not found: " + id.value()));
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new InvalidCommandException(name + " is required");
        }
        return value;
    }
}
