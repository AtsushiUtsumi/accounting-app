package com.example.accountingapp.presentation.controller.consolidation;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.consolidation.ConsolidatedBalanceSheet;
import com.example.accounting.model.consolidation.ConsolidatedIncomeStatement;
import com.example.accounting.model.consolidation.ConsolidationWorksheet;
import com.example.accounting.model.consolidation.IntercompanyBalance;
import com.example.accounting.model.consolidation.IntercompanyTransaction;
import com.example.accounting.model.consolidation.Subsidiary;
import com.example.accounting.model.consolidation.UnrealizedProfit;
import com.example.accounting.model.money.Money;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.IntercompanyBalanceCommand;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.IntercompanyTransactionCommand;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.SubsidiaryCommand;
import com.example.accountingapp.application.consolidation.ConsolidationUseCase.UnrealizedProfitCommand;
import com.example.accountingapp.application.port.ConsolidationMaterialRepository.Registered;
import com.example.accountingapp.presentation.AccountAmount;
import com.example.accountingapp.presentation.JournalResponse;
import com.example.accountingapp.presentation.controller.master.CompanyController.CompanyResponse;
import com.example.accountingapp.presentation.controller.statement.StatementController.IncomeStatementResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 連結会計(CLAUDE.md §6.9)。リクエストヘッダー {@code X-Company-Id} の会社(省略すれば既定の会社)を親会社とする。
 * 連結のルールはライブラリでは未検証(UNVERIFIED_RULES.md の 14-x)なので、結果に {@code unverified: true} を付ける。
 */
@RestController
public class ConsolidationController {

    private final ConsolidationUseCase useCase;

    public ConsolidationController(ConsolidationUseCase useCase) {
        this.useCase = useCase;
    }

    // ---- 子会社 ----

    public record SubsidiaryRequest(@NotNull UUID companyId, @NotNull LocalDate acquisitionDate,
                                    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
                                    BigDecimal parentOwnership,
                                    @Positive long investmentCost,
                                    @NotEmpty List<@Valid EquityLine> equityAtAcquisition,
                                    @Positive int goodwillAmortizationYears) {

        public record EquityLine(@NotBlank String accountCode, long amount) {
        }

        public SubsidiaryCommand toCommand() {
            return new SubsidiaryCommand(companyId, acquisitionDate, parentOwnership, investmentCost,
                    equityAtAcquisition.stream()
                            .map(line -> new SubsidiaryCommand.EquityLine(line.accountCode(), line.amount()))
                            .toList(),
                    goodwillAmortizationYears);
        }
    }

    public record SubsidiaryResponse(CompanyResponse company, LocalDate acquisitionDate, BigDecimal parentOwnership,
                                     long investmentCost, List<AccountAmount> equityAtAcquisition,
                                     int goodwillAmortizationYears) {

        public static SubsidiaryResponse of(ConsolidationUseCase.SubsidiaryView view) {
            Subsidiary s = view.subsidiary();
            return new SubsidiaryResponse(CompanyResponse.of(view.company()), s.acquisitionDate(), s.parentOwnership(),
                    s.investmentCost().amountInYen(), AccountAmount.listOf(s.equityAtAcquisition()),
                    s.goodwillAmortizationYears());
        }
    }

    /** いま操作している会社(親会社)の子会社。 */
    @GetMapping("/subsidiaries")
    public List<SubsidiaryResponse> subsidiaries() {
        return useCase.subsidiaries().stream().map(SubsidiaryResponse::of).toList();
    }

    @PostMapping("/subsidiaries")
    @ResponseStatus(HttpStatus.CREATED)
    public SubsidiaryResponse registerSubsidiary(@Valid @RequestBody SubsidiaryRequest request) {
        return SubsidiaryResponse.of(useCase.registerSubsidiary(request.toCommand()));
    }

    @DeleteMapping("/subsidiaries/{companyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSubsidiary(@PathVariable UUID companyId) {
        useCase.removeSubsidiary(companyId);
    }

    // ---- 連結の資料 ----

    public record IntercompanyBalanceRequest(@NotNull UUID creditorCompanyId, @NotBlank String receivableCode,
                                             @NotBlank String payableCode, @PositiveOrZero long amount,
                                             @PositiveOrZero long allowance, @PositiveOrZero long allowanceAtBeginning) {

        public IntercompanyBalanceCommand toCommand() {
            return new IntercompanyBalanceCommand(creditorCompanyId, receivableCode, payableCode, amount, allowance,
                    allowanceAtBeginning);
        }
    }

    public record IntercompanyTransactionRequest(@NotBlank String revenueCode, @NotBlank String expenseCode,
                                                 @Positive long amount) {

        public IntercompanyTransactionCommand toCommand() {
            return new IntercompanyTransactionCommand(revenueCode, expenseCode, amount);
        }
    }

    public record UnrealizedProfitRequest(@NotNull UnrealizedProfitCommand.Kind kind, @NotNull UUID sellerCompanyId,
                                          String inventoryAccountCode, @PositiveOrZero Long ending,
                                          @PositiveOrZero Long beginning, @Positive Long amount, LocalDate saleDate) {

        public UnrealizedProfitCommand toCommand() {
            return new UnrealizedProfitCommand(kind, sellerCompanyId, inventoryAccountCode, ending, beginning, amount,
                    saleDate);
        }
    }

    public record IntercompanyBalanceResponse(UUID id, UUID creditorCompanyId, String receivableCode,
                                              String receivableName, String payableCode, String payableName,
                                              long amount, long allowance, long allowanceAtBeginning) {

        public static IntercompanyBalanceResponse of(Registered<IntercompanyBalance> registered) {
            IntercompanyBalance b = registered.item();
            return new IntercompanyBalanceResponse(registered.id(), b.creditor().value(), b.receivable().code().value(),
                    b.receivable().name(), b.payable().code().value(), b.payable().name(), b.amount().amountInYen(),
                    b.allowance().amountInYen(), b.allowanceAtBeginning().amountInYen());
        }
    }

    public record IntercompanyTransactionResponse(UUID id, String revenueCode, String revenueName, String expenseCode,
                                                  String expenseName, long amount) {

        public static IntercompanyTransactionResponse of(Registered<IntercompanyTransaction> registered) {
            IntercompanyTransaction t = registered.item();
            return new IntercompanyTransactionResponse(registered.id(), t.revenue().code().value(), t.revenue().name(),
                    t.expense().code().value(), t.expense().name(), t.amount().amountInYen());
        }
    }

    public record UnrealizedProfitResponse(UUID id, UnrealizedProfitCommand.Kind kind, UUID sellerCompanyId,
                                           String inventoryAccountCode, String inventoryAccountName, Long ending,
                                           Long beginning, Long amount, LocalDate saleDate) {

        public static UnrealizedProfitResponse of(Registered<UnrealizedProfit> registered) {
            return switch (registered.item()) {
                case UnrealizedProfit.Inventory i -> new UnrealizedProfitResponse(registered.id(),
                        UnrealizedProfitCommand.Kind.INVENTORY, i.seller().value(), i.inventory().code().value(),
                        i.inventory().name(), i.ending().amountInYen(), i.beginning().amountInYen(), null, null);
                case UnrealizedProfit.Land l -> new UnrealizedProfitResponse(registered.id(),
                        UnrealizedProfitCommand.Kind.LAND, l.seller().value(), null, null, null, null,
                        l.amount().amountInYen(), l.saleDate());
            };
        }
    }

    public record MaterialsResponse(List<IntercompanyBalanceResponse> intercompanyBalances,
                                    List<IntercompanyTransactionResponse> intercompanyTransactions,
                                    List<UnrealizedProfitResponse> unrealizedProfits) {

        public static MaterialsResponse of(ConsolidationUseCase.Materials materials) {
            return new MaterialsResponse(
                    materials.balances().stream().map(IntercompanyBalanceResponse::of).toList(),
                    materials.transactions().stream().map(IntercompanyTransactionResponse::of).toList(),
                    materials.unrealizedProfits().stream().map(UnrealizedProfitResponse::of).toList());
        }
    }

    /** 親会社の期の、連結の資料(債権債務・取引高・未実現利益)。 */
    @GetMapping("/periods/{id}/consolidation/materials")
    public MaterialsResponse materials(@PathVariable UUID id) {
        return MaterialsResponse.of(useCase.materials(id));
    }

    @PostMapping("/periods/{id}/consolidation/intercompany-balances")
    @ResponseStatus(HttpStatus.CREATED)
    public IntercompanyBalanceResponse addIntercompanyBalance(@PathVariable UUID id,
                                                              @Valid @RequestBody IntercompanyBalanceRequest request) {
        return IntercompanyBalanceResponse.of(useCase.addBalance(id, request.toCommand()));
    }

    @PostMapping("/periods/{id}/consolidation/intercompany-transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public IntercompanyTransactionResponse addIntercompanyTransaction(
            @PathVariable UUID id, @Valid @RequestBody IntercompanyTransactionRequest request) {
        return IntercompanyTransactionResponse.of(useCase.addTransaction(id, request.toCommand()));
    }

    @PostMapping("/periods/{id}/consolidation/unrealized-profits")
    @ResponseStatus(HttpStatus.CREATED)
    public UnrealizedProfitResponse addUnrealizedProfit(@PathVariable UUID id,
                                                        @Valid @RequestBody UnrealizedProfitRequest request) {
        return UnrealizedProfitResponse.of(useCase.addUnrealizedProfit(id, request.toCommand()));
    }

    /** 連結の資料を1件削除する(種類は問わない)。 */
    @DeleteMapping("/periods/{id}/consolidation/materials/{materialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMaterial(@PathVariable UUID id, @PathVariable UUID materialId) {
        useCase.removeMaterial(id, materialId);
    }

    // ---- 連結財務諸表 ----

    public record ConsolidationResponse(boolean unverified, List<CompanyResponse> companies,
                                        List<JournalResponse> adjustments, WorksheetResponse worksheet,
                                        ConsolidatedIncomeStatementResponse incomeStatement,
                                        ConsolidatedBalanceSheetResponse balanceSheet) {

        public static ConsolidationResponse of(ConsolidationUseCase.Result result) {
            return new ConsolidationResponse(true,
                    result.companies().stream().map(CompanyResponse::of).toList(),
                    result.result().adjustments().stream().map(JournalResponse::of).toList(),
                    WorksheetResponse.of(result.result().worksheet()),
                    ConsolidatedIncomeStatementResponse.of(result.result().incomeStatement()),
                    ConsolidatedBalanceSheetResponse.of(result.result().balanceSheet()));
        }
    }

    /** 連結精算表。金額はすべて科目の正常な向きを正とした残高。{@code individual} は会社 ID ごとの個別財務諸表の残高。 */
    public record WorksheetResponse(List<WorksheetRow> rows, long adjustmentDebitTotal, long adjustmentCreditTotal,
                                    boolean adjustmentBalanced) {

        public static WorksheetResponse of(ConsolidationWorksheet ws) {
            List<WorksheetRow> rows = ws.rows().entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().code().value()))
                    .map(entry -> WorksheetRow.of(entry.getKey(), entry.getValue()))
                    .toList();
            return new WorksheetResponse(rows, ws.adjustmentDebitTotal().amountInYen(),
                    ws.adjustmentCreditTotal().amountInYen(), ws.isAdjustmentBalanced());
        }
    }

    public record WorksheetRow(String accountCode, String accountName, Map<UUID, Long> individual, long individualTotal,
                               long adjustmentDebit, long adjustmentCredit, long consolidated) {

        static WorksheetRow of(Account account, ConsolidationWorksheet.Row row) {
            Map<UUID, Long> individual = new LinkedHashMap<>();
            for (Map.Entry<CompanyId, Money> entry : row.individual().entrySet()) {
                individual.put(entry.getKey().value(), entry.getValue().amountInYen());
            }
            return new WorksheetRow(account.code().value(), account.name(), individual,
                    row.individualTotal().amountInYen(), row.adjustmentDebit().amountInYen(),
                    row.adjustmentCredit().amountInYen(), row.consolidated().amountInYen());
        }
    }

    /** 連結損益計算書。当期純利益から非支配株主に帰属する分を引いて、親会社株主に帰属する当期純利益を出す。 */
    public record ConsolidatedIncomeStatementResponse(IncomeStatementResponse incomeStatement,
                                                      long profitAttributableToNonControllingInterests,
                                                      long profitAttributableToOwnersOfParent) {

        public static ConsolidatedIncomeStatementResponse of(ConsolidatedIncomeStatement pl) {
            return new ConsolidatedIncomeStatementResponse(IncomeStatementResponse.of(pl.incomeStatement()),
                    pl.profitAttributableToNonControllingInterests().amountInYen(),
                    pl.profitAttributableToOwnersOfParent().amountInYen());
        }
    }

    /**
     * 連結貸借対照表。{@code shareholdersEquity} の利益剰余金には当期純利益を含まない
     * ({@code retainedEarnings} は親会社株主に帰属する当期純利益を含めた利益剰余金の合計)。
     */
    public record ConsolidatedBalanceSheetResponse(List<AccountAmount> assets, List<AccountAmount> liabilities,
                                                   List<AccountAmount> shareholdersEquity,
                                                   long profitAttributableToOwnersOfParent, long retainedEarnings,
                                                   long nonControllingInterests, long totalAssets,
                                                   long totalLiabilities, long totalEquity, boolean balanced) {

        public static ConsolidatedBalanceSheetResponse of(ConsolidatedBalanceSheet bs) {
            return new ConsolidatedBalanceSheetResponse(AccountAmount.listOf(bs.assets()),
                    AccountAmount.listOf(bs.liabilities()), AccountAmount.listOf(bs.shareholdersEquity()),
                    bs.profitAttributableToOwnersOfParent().amountInYen(), bs.retainedEarnings().amountInYen(),
                    bs.nonControllingInterests().amountInYen(), bs.totalAssets().amountInYen(),
                    bs.totalLiabilities().amountInYen(), bs.totalEquity().amountInYen(), bs.isBalanced());
        }
    }

    /** 親会社の期について、連結修正仕訳・連結精算表・連結損益計算書・連結貸借対照表を作る(保存しない)。 */
    @GetMapping("/periods/{id}/consolidation")
    public ConsolidationResponse consolidate(@PathVariable UUID id) {
        return ConsolidationResponse.of(useCase.consolidate(id));
    }
}
