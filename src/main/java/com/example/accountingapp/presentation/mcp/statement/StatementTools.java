package com.example.accountingapp.presentation.mcp.statement;

import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.presentation.controller.statement.StatementController.BalanceSheetResponse;
import com.example.accountingapp.presentation.controller.statement.StatementController.CashFlowResponse;
import com.example.accountingapp.presentation.controller.statement.StatementController.EquityChangesResponse;
import com.example.accountingapp.presentation.controller.statement.StatementController.IncomeStatementResponse;
import com.example.accountingapp.presentation.controller.statement.StatementController.LedgerResponse;
import com.example.accountingapp.presentation.controller.statement.StatementController.TrialBalanceResponse;
import com.example.accountingapp.presentation.controller.statement.StatementController.WorksheetResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/** 試算表・B/S・P/L・C/F・株主資本等変動計算書・精算表・元帳。値はすべてライブラリが計算したもの。金額は円。 */
@Component
@Validated
public class StatementTools implements McpTools {

    private static final String PERIOD_ID = "会計期間の ID";

    private final StatementQueryService queries;

    public StatementTools(StatementQueryService queries) {
        this.queries = queries;
    }

    @Tool(name = "get_trial_balance", description = "合計残高試算表。既定では損益振替(CLOSING)の仕訳を除いた、締め前の状態を返す。")
    @PreAuthorize(VIEWER)
    public TrialBalanceResponse getTrialBalance(
            @ToolParam(description = PERIOD_ID) @NotNull UUID periodId,
            @ToolParam(description = "true なら損益振替の仕訳も含める。省略すると false", required = false)
            Boolean includeClosing) {
        return TrialBalanceResponse.of(queries.trialBalance(periodId, Boolean.TRUE.equals(includeClosing)));
    }

    @Tool(name = "get_balance_sheet", description = """
            貸借対照表。損益振替後を前提とするため、締め前の期では balanced が false になる(収益・費用が純資産に振り替えられていない)。""")
    @PreAuthorize(VIEWER)
    public BalanceSheetResponse getBalanceSheet(@ToolParam(description = PERIOD_ID) @NotNull UUID periodId) {
        return BalanceSheetResponse.of(queries.balanceSheet(periodId));
    }

    @Tool(name = "get_income_statement", description = "損益計算書(売上高から当期純利益までの段階損益)。")
    @PreAuthorize(VIEWER)
    public IncomeStatementResponse getIncomeStatement(@ToolParam(description = PERIOD_ID) @NotNull UUID periodId) {
        return IncomeStatementResponse.of(queries.incomeStatement(periodId));
    }

    @Tool(name = "get_cash_flow_statement", description = """
            キャッシュ・フロー計算書(間接法)。前期末の B/S が必要なので、前期がある期で使う。
            資金の範囲は「現金」勘定だけ(ライブラリの既知の制限)で、預金を使う会社では正しい値にならない。""")
    @PreAuthorize(VIEWER)
    public CashFlowResponse getCashFlowStatement(@ToolParam(description = PERIOD_ID) @NotNull UUID periodId) {
        return CashFlowResponse.of(queries.cashFlow(periodId));
    }

    @Tool(name = "get_equity_changes", description = "株主資本等変動計算書。")
    @PreAuthorize(VIEWER)
    public EquityChangesResponse getEquityChanges(@ToolParam(description = PERIOD_ID) @NotNull UUID periodId) {
        return EquityChangesResponse.of(queries.equityChanges(periodId));
    }

    @Tool(name = "get_worksheet", description = "精算表(試算表・修正記入・損益計算書・貸借対照表の各欄)。")
    @PreAuthorize(VIEWER)
    public WorksheetResponse getWorksheet(@ToolParam(description = PERIOD_ID) @NotNull UUID periodId) {
        return WorksheetResponse.of(queries.worksheet(periodId));
    }

    @Tool(name = "get_ledger", description = "総勘定元帳。指定した科目の、期中の仕訳の明細と残高を返す。")
    @PreAuthorize(VIEWER)
    public LedgerResponse getLedger(
            @ToolParam(description = "科目コード") @NotBlank String accountCode,
            @ToolParam(description = PERIOD_ID) @NotNull UUID periodId) {
        return LedgerResponse.of(queries.ledger(accountCode, periodId));
    }
}
