package com.example.accountingapp.presentation.statement;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.Side;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.statement.BalanceSheet;
import com.example.accounting.model.statement.CashFlowStatement;
import com.example.accounting.model.statement.EquityChangeReason;
import com.example.accounting.model.statement.IncomeStatement;
import com.example.accounting.model.statement.Ledger;
import com.example.accounting.model.statement.StatementOfChangesInEquity;
import com.example.accounting.model.statement.TrialBalance;
import com.example.accounting.model.statement.Worksheet;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.presentation.AccountAmount;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 試算表・B/S・P/L・C/F・株主資本等変動計算書・精算表・元帳。値はすべてライブラリが計算したもの。 */
@RestController
public class StatementController {

    private final StatementQueryService queries;

    public StatementController(StatementQueryService queries) {
        this.queries = queries;
    }

    // ---- 試算表 ----

    public record TrialBalanceResponse(List<TrialBalanceRow> rows, long debitTotal, long creditTotal, boolean balanced) {
    }

    public record TrialBalanceRow(String accountCode, String accountName, long debitTotal, long creditTotal,
                                  long balance) {
    }

    /** 合計残高試算表。既定では損益振替(CLOSING)を除く。 */
    @GetMapping("/periods/{id}/trial-balance")
    public TrialBalanceResponse trialBalance(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "false") boolean includeClosing) {
        TrialBalance trialBalance = queries.trialBalance(id, includeClosing);
        List<TrialBalanceRow> rows = trialBalance.rows().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().code().value()))
                .map(entry -> new TrialBalanceRow(
                        entry.getKey().code().value(), entry.getKey().name(),
                        entry.getValue().debitTotal().amountInYen(), entry.getValue().creditTotal().amountInYen(),
                        entry.getValue().netBalance(entry.getKey().type()).amountInYen()))
                .toList();
        return new TrialBalanceResponse(rows, trialBalance.debitTotal().amountInYen(),
                trialBalance.creditTotal().amountInYen(), trialBalance.isBalanced());
    }

    // ---- B/S ----

    /**
     * 貸借対照表。損益振替後を前提とするため、締め前の期では {@code balanced} が false になる
     * (収益・費用が純資産に振り替えられていない)。
     */
    public record BalanceSheetResponse(List<AccountAmount> assets, List<AccountAmount> liabilities,
                                       List<AccountAmount> equity, long totalAssets, long totalLiabilities,
                                       long totalEquity, boolean balanced) {
    }

    @GetMapping("/periods/{id}/balance-sheet")
    public BalanceSheetResponse balanceSheet(@PathVariable UUID id) {
        BalanceSheet bs = queries.balanceSheet(id);
        return new BalanceSheetResponse(AccountAmount.listOf(bs.assets()), AccountAmount.listOf(bs.liabilities()),
                AccountAmount.listOf(bs.equity()), bs.totalAssets().amountInYen(),
                bs.totalLiabilities().amountInYen(), bs.totalEquity().amountInYen(), bs.isBalanced());
    }

    // ---- P/L ----

    public record IncomeStatementResponse(long sales, long costOfSales, long grossProfit, long sgAndA,
                                          long operatingIncome, long nonOperatingIncome, long nonOperatingExpense,
                                          long ordinaryIncome, long extraordinaryIncome, long extraordinaryLoss,
                                          long incomeBeforeIncomeTax, long incomeTaxExpense, long netIncome) {
    }

    @GetMapping("/periods/{id}/income-statement")
    public IncomeStatementResponse incomeStatement(@PathVariable UUID id) {
        IncomeStatement pl = queries.incomeStatement(id);
        return new IncomeStatementResponse(
                yen(pl.sales()), yen(pl.costOfSales()), yen(pl.grossProfit()), yen(pl.sgAndA()),
                yen(pl.operatingIncome()), yen(pl.nonOperatingIncome()), yen(pl.nonOperatingExpense()),
                yen(pl.ordinaryIncome()), yen(pl.extraordinaryIncome()), yen(pl.extraordinaryLoss()),
                yen(pl.incomeBeforeIncomeTax()), yen(pl.incomeTaxExpense()), yen(pl.netIncome()));
    }

    // ---- C/F ----

    /** ライブラリの既知の制限。画面に表示する。 */
    static final String CASH_FLOW_NOTE = "資金の範囲は「現金」勘定だけです。預金を使う会社では正しい値になりません。";

    public record CashFlowResponse(long netIncome, long depreciationAddBack,
                                   List<AccountAmount> operatingWorkingCapitalChanges,
                                   long operatingActivities, long investingActivities, long financingActivities,
                                   long netChangeInCash, long cashAtBeginningOfPeriod, long cashAtEndOfPeriod,
                                   boolean reconciles, String note) {
    }

    @GetMapping("/periods/{id}/cash-flow")
    public CashFlowResponse cashFlow(@PathVariable UUID id) {
        CashFlowStatement cf = queries.cashFlow(id);
        return new CashFlowResponse(
                yen(cf.netIncome()), yen(cf.depreciationAddBack()),
                AccountAmount.listOf(cf.operatingWorkingCapitalChanges()),
                yen(cf.operatingActivitiesCashFlow()), yen(cf.investingActivitiesCashFlow()),
                yen(cf.financingActivitiesCashFlow()), yen(cf.netChangeInCash()),
                yen(cf.cashAtBeginningOfPeriod()), yen(cf.cashAtEndOfPeriod()), cf.reconcilesToEndingCash(),
                CASH_FLOW_NOTE);
    }

    // ---- 株主資本等変動計算書 ----

    public record EquityChangesResponse(List<AccountAmount> openingBalances,
                                        Map<EquityChangeReason, List<AccountAmount>> changesByReason,
                                        List<AccountAmount> closingBalances, long totalEquityAtBeginning,
                                        long totalEquityAtEnd, boolean reconciles) {
    }

    @GetMapping("/periods/{id}/equity-changes")
    public EquityChangesResponse equityChanges(@PathVariable UUID id) {
        StatementOfChangesInEquity sce = queries.equityChanges(id);
        Map<EquityChangeReason, List<AccountAmount>> changes = new LinkedHashMap<>();
        for (EquityChangeReason reason : EquityChangeReason.values()) {
            Map<Account, Money> byAccount = sce.changesByReason().get(reason);
            if (byAccount != null) {
                changes.put(reason, AccountAmount.listOf(byAccount));
            }
        }
        return new EquityChangesResponse(AccountAmount.listOf(sce.openingBalances()), changes,
                AccountAmount.listOf(sce.closingBalances()), yen(sce.totalEquityAtBeginning()),
                yen(sce.totalEquityAtEnd()), sce.reconciles());
    }

    // ---- 精算表 ----

    public record WorksheetResponse(List<WorksheetRow> rows, WorksheetTotals totals, long netIncome, boolean balanced) {
    }

    public record WorksheetRow(String accountCode, String accountName,
                               long trialBalanceDebit, long trialBalanceCredit,
                               long adjustmentDebit, long adjustmentCredit,
                               long incomeStatementDebit, long incomeStatementCredit,
                               long balanceSheetDebit, long balanceSheetCredit) {
    }

    public record WorksheetTotals(long trialBalanceDebit, long trialBalanceCredit,
                                  long adjustmentDebit, long adjustmentCredit,
                                  long incomeStatementDebit, long incomeStatementCredit,
                                  long balanceSheetDebit, long balanceSheetCredit) {
    }

    @GetMapping("/periods/{id}/worksheet")
    public WorksheetResponse worksheet(@PathVariable UUID id) {
        Worksheet ws = queries.worksheet(id);
        List<WorksheetRow> rows = ws.rows().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().code().value()))
                .map(entry -> {
                    Worksheet.Row r = entry.getValue();
                    return new WorksheetRow(entry.getKey().code().value(), entry.getKey().name(),
                            yen(r.trialBalanceDebit()), yen(r.trialBalanceCredit()),
                            yen(r.adjustmentDebit()), yen(r.adjustmentCredit()),
                            yen(r.incomeStatementDebit()), yen(r.incomeStatementCredit()),
                            yen(r.balanceSheetDebit()), yen(r.balanceSheetCredit()));
                })
                .toList();
        WorksheetTotals totals = new WorksheetTotals(
                yen(ws.trialBalanceDebitTotal()), yen(ws.trialBalanceCreditTotal()),
                yen(ws.adjustmentDebitTotal()), yen(ws.adjustmentCreditTotal()),
                yen(ws.incomeStatementDebitTotal()), yen(ws.incomeStatementCreditTotal()),
                yen(ws.balanceSheetDebitTotal()), yen(ws.balanceSheetCreditTotal()));
        return new WorksheetResponse(rows, totals, yen(ws.netIncome()), ws.isBalanced());
    }

    // ---- 総勘定元帳 ----

    public record LedgerResponse(String accountCode, String accountName, List<LedgerEntry> entries,
                                 long debitTotal, long creditTotal, long balance) {
    }

    /** 元帳の1行。日付・摘要・種類は仕訳から、金額はその科目の行から取る。 */
    public record LedgerEntry(UUID journalId, LocalDate date, String description, JournalType type, Side side,
                              long amount) {
    }

    @GetMapping("/accounts/{code}/ledger")
    public LedgerResponse ledger(@PathVariable String code, @RequestParam UUID periodId) {
        StatementQueryService.LedgerView view = queries.ledger(code, periodId);
        Ledger ledger = view.ledger();
        List<LedgerEntry> entries = view.journals().stream()
                .flatMap(journal -> journal.lines().stream()
                        .filter(line -> line.account().equals(ledger.account()))
                        .map(line -> entryOf(journal, line)))
                .toList();
        return new LedgerResponse(ledger.account().code().value(), ledger.account().name(), entries,
                yen(ledger.debitTotal()), yen(ledger.creditTotal()), yen(ledger.balance()));
    }

    private static LedgerEntry entryOf(Journal journal, JournalLine line) {
        return new LedgerEntry(journal.id().value(), journal.date(), journal.description(), journal.type(),
                line.side(), yen(line.amount()));
    }

    private static long yen(Money money) {
        return money.amountInYen();
    }
}
