package com.example.accountingapp.presentation.mcp.master;

import com.example.accountingapp.application.master.CompanySetupUseCase;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.presentation.controller.master.CompanyController.PeriodResponse;
import com.example.accountingapp.presentation.controller.master.CompanyController.ProgressResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.constraints.NotNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 会社の初期化と、会計期間の照会。 */
@Component
@Validated
public class CompanyTools implements McpTools {

    private final CompanySetupUseCase companySetupUseCase;
    private final StatementQueryService queries;

    public CompanyTools(CompanySetupUseCase companySetupUseCase, StatementQueryService queries) {
        this.companySetupUseCase = companySetupUseCase;
        this.queries = queries;
    }

    @Tool(name = "set_up_company", description = """
            会社を初期化する。標準の勘定科目を投入し、指定した期首日から1年間の最初の会計期間を作る。
            最初に1回だけ実行する(承認者のみ)。""")
    @PreAuthorize(APPROVER)
    public PeriodResponse setUpCompany(
            @ToolParam(description = "最初の会計期間の期首日(yyyy-MM-dd)") @NotNull LocalDate firstPeriodStart) {
        return PeriodResponse.of(companySetupUseCase.setUp(firstPeriodStart));
    }

    @Tool(name = "list_periods", description = "すべての会計期間を、期首日の新しい順に返す。")
    @PreAuthorize(VIEWER)
    public List<PeriodResponse> listPeriods() {
        return queries.periods().stream().map(PeriodResponse::of).toList();
    }

    @Tool(name = "get_latest_period", description = "最新の会計期間を返す。期の ID が分からないときはまずこれを使う。")
    @PreAuthorize(VIEWER)
    public PeriodResponse getLatestPeriod() {
        return PeriodResponse.of(queries.latestPeriod());
    }

    @Tool(name = "get_period", description = "会計期間を1つ返す。")
    @PreAuthorize(VIEWER)
    public PeriodResponse getPeriod(@ToolParam(description = "会計期間の ID") @NotNull UUID periodId) {
        return PeriodResponse.of(queries.period(periodId));
    }

    @Tool(name = "get_period_progress", description = """
            決算の進み具合を返す(決算整理の実行済みか、再振替の実行済みか、現金過不足の残高、仕訳の件数、前期)。""")
    @PreAuthorize(VIEWER)
    public ProgressResponse getPeriodProgress(@ToolParam(description = "会計期間の ID") @NotNull UUID periodId) {
        return ProgressResponse.of(queries.progress(periodId));
    }
}
