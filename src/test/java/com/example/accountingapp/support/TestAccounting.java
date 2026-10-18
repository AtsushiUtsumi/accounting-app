package com.example.accountingapp.support;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.Side;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.service.AdjustingEntryService;
import com.example.accounting.service.BadDebtService;
import com.example.accounting.service.BondService;
import com.example.accounting.service.CashOverShortService;
import com.example.accounting.service.ClosingService;
import com.example.accounting.service.DividendService;
import com.example.accounting.service.FinancialStatementFactory;
import com.example.accounting.service.InstallmentPurchaseService;
import com.example.accounting.service.MergerService;
import com.example.accounting.service.NotesReceivableService;
import com.example.accounting.service.PerpetualInventoryService;
import com.example.accounting.service.ProvisionService;
import com.example.accounting.service.ReversalService;
import com.example.accounting.service.RevenueRecognitionService;
import com.example.accounting.service.TradingSecuritiesService;
import com.example.accounting.model.statement.StandardCashFlowClassifier;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.JournalLineCommand;
import com.example.accountingapp.application.asset.FixedAssetUseCase;
import com.example.accountingapp.application.asset.HeldToMaturityBondUseCase;
import com.example.accountingapp.application.asset.IntangibleAssetUseCase;
import com.example.accountingapp.application.closing.ClosePeriodUseCase;
import com.example.accountingapp.application.closing.ReverseAccrualsUseCase;
import com.example.accountingapp.application.closing.RunAdjustingEntriesUseCase;
import com.example.accountingapp.application.inventory.InventoryUseCase;
import com.example.accountingapp.application.journal.CorrectJournalUseCase;
import com.example.accountingapp.application.journal.PostJournalCommand;
import com.example.accountingapp.application.journal.PostJournalUseCase;
import com.example.accountingapp.application.master.AccountManagementUseCase;
import com.example.accountingapp.application.master.CompanySetupUseCase;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.application.transaction.BankReconciliationUseCase;
import com.example.accountingapp.application.transaction.TransactionUseCase;
import com.example.accountingapp.support.inmemory.InMemoryAccountRepository;
import com.example.accountingapp.support.inmemory.InMemoryAccountingPeriodRepository;
import com.example.accountingapp.support.inmemory.InMemoryAssetRepositories;
import com.example.accountingapp.support.inmemory.InMemoryJournalRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * ユースケースの単体テスト用に、インメモリのリポジトリでアプリケーション層を組み立てる(Spring を使わない)。
 * ドメインサービスは本番と同じものを使う。
 */
public final class TestAccounting {

    public final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    public final InMemoryAccountingPeriodRepository periods = new InMemoryAccountingPeriodRepository();
    public final InMemoryJournalRepository journals = new InMemoryJournalRepository(periods);
    public final InMemoryAssetRepositories.FixedAssets fixedAssets = new InMemoryAssetRepositories.FixedAssets();
    public final InMemoryAssetRepositories.IntangibleAssets intangibleAssets = new InMemoryAssetRepositories.IntangibleAssets();
    public final InMemoryAssetRepositories.Bonds bonds = new InMemoryAssetRepositories.Bonds();
    public final InMemoryAssetRepositories.InventoryMovements inventoryMovements =
            new InMemoryAssetRepositories.InventoryMovements();

    public final JournalBook book = new JournalBook(periods, periods, journals);
    public final AccountResolver accountResolver = new AccountResolver(accounts);

    public final CompanySetupUseCase companySetup = new CompanySetupUseCase(accounts, periods, periods);
    public final AccountManagementUseCase accountManagement = new AccountManagementUseCase(accounts, journals);
    public final PostJournalUseCase postJournal = new PostJournalUseCase(book, accountResolver);
    public final CorrectJournalUseCase correctJournal =
            new CorrectJournalUseCase(book, journals, new ReversalService(), postJournal);
    public final RunAdjustingEntriesUseCase runAdjustingEntries = new RunAdjustingEntriesUseCase(
            book, journals, accountResolver, new AdjustingEntryService(), new TradingSecuritiesService(),
            fixedAssets, fixedAssets, intangibleAssets, bonds);
    public final ClosePeriodUseCase closePeriod = new ClosePeriodUseCase(
            book, journals, periods, new ClosingService(), new FinancialStatementFactory());
    public final ReverseAccrualsUseCase reverseAccruals =
            new ReverseAccrualsUseCase(book, periods, journals, new AdjustingEntryService());
    public final StatementQueryService statements = new StatementQueryService(
            book, periods, accountResolver, new FinancialStatementFactory(), new StandardCashFlowClassifier());
    public final FixedAssetUseCase fixedAssetUseCase = new FixedAssetUseCase(fixedAssets, fixedAssets, book, accountResolver);
    public final IntangibleAssetUseCase intangibleAssetUseCase =
            new IntangibleAssetUseCase(intangibleAssets, book, accountResolver);
    public final HeldToMaturityBondUseCase bondUseCase = new HeldToMaturityBondUseCase(bonds, book, accountResolver);
    public final InventoryUseCase inventory =
            new InventoryUseCase(inventoryMovements, new PerpetualInventoryService(), book, accountResolver);
    public final TransactionUseCase transactions = new TransactionUseCase(
            book, accountResolver, new BadDebtService(), new BondService(), new CashOverShortService(),
            new DividendService(), new InstallmentPurchaseService(), new MergerService(), new NotesReceivableService(),
            new ProvisionService(), new RevenueRecognitionService(), new TradingSecuritiesService());
    public final BankReconciliationUseCase bankReconciliation = new BankReconciliationUseCase(book, accountResolver);

    /** 標準科目を投入し、指定日に始まる最初の期を作る。 */
    public AccountingPeriod setUpCompany(LocalDate firstPeriodStart) {
        return companySetup.setUp(firstPeriodStart);
    }

    public JournalId post(LocalDate date, String description, JournalLineCommand... lines) {
        return postJournal.post(new PostJournalCommand(date, description, JournalType.NORMAL, List.of(lines)));
    }

    public JournalId postAdjusting(LocalDate date, String description, JournalLineCommand... lines) {
        return postJournal.post(new PostJournalCommand(date, description, JournalType.ADJUSTING, List.of(lines)));
    }

    public List<Journal> journalsOf(AccountingPeriod period) {
        return book.journalsOf(period);
    }

    public AccountingPeriod reload(AccountingPeriod period) {
        return periods.findById(period.id()).orElseThrow();
    }

    public static JournalLineCommand dr(Account account, long yen) {
        return new JournalLineCommand(account.code().value(), Side.DEBIT, yen);
    }

    public static JournalLineCommand cr(Account account, long yen) {
        return new JournalLineCommand(account.code().value(), Side.CREDIT, yen);
    }
}
