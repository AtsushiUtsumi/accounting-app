package com.example.accountingapp.presentation.mcp.master;

import com.example.accounting.model.consolidation.CompanyId;
import com.example.accountingapp.application.master.CompanySetupUseCase;
import com.example.accountingapp.application.master.CompanyUseCase;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.presentation.controller.master.CompanyController.CompanyResponse;
import com.example.accountingapp.presentation.controller.master.CompanyController.CreatedCompanyResponse;
import com.example.accountingapp.presentation.controller.master.CompanyController.PeriodResponse;
import com.example.accountingapp.presentation.controller.master.CompanyController.ProgressResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 会社の管理と初期化、会計期間の照会。 */
@Component
@Validated
public class CompanyTools implements McpTools {

    private final CompanyUseCase companyUseCase;
    private final CompanySetupUseCase companySetupUseCase;
    private final StatementQueryService queries;

    public CompanyTools(CompanyUseCase companyUseCase, CompanySetupUseCase companySetupUseCase,
                        StatementQueryService queries) {
        this.companyUseCase = companyUseCase;
        this.companySetupUseCase = companySetupUseCase;
        this.queries = queries;
    }

    @Tool(name = "list_companies", description = """
            帳簿を持っているすべての会社を返す。ほかのツールの companyId には、ここで確認した ID を渡す。""")
    @PreAuthorize(VIEWER)
    public List<CompanyResponse> listCompanies() {
        return companyUseCase.list().stream().map(CompanyResponse::of).toList();
    }

    @Tool(name = "create_company", description = """
            会社を作り、標準の勘定科目と、指定した期首日から1年間の最初の会計期間を用意する(承認者のみ)。
            子会社の帳簿をつけるときなどに使う。""")
    @PreAuthorize(APPROVER)
    public CreatedCompanyResponse createCompany(
            @ToolParam(description = "会社の名前") @NotBlank @Size(max = 100) String name,
            @ToolParam(description = "最初の会計期間の期首日(yyyy-MM-dd)") @NotNull LocalDate firstPeriodStart) {
        return CreatedCompanyResponse.of(companyUseCase.create(name, firstPeriodStart));
    }

    @Tool(name = "rename_company", description = "会社の名前を変更する(承認者のみ)。")
    @PreAuthorize(APPROVER)
    public CompanyResponse renameCompany(
            @ToolParam(description = "名前を変える会社の ID") @NotNull UUID targetCompanyId,
            @ToolParam(description = "新しい名前") @NotBlank @Size(max = 100) String name) {
        return CompanyResponse.of(companyUseCase.rename(new CompanyId(targetCompanyId), name));
    }

    @Tool(name = "set_up_company", description = """
            会社を初期化する。標準の勘定科目を投入し、指定した期首日から1年間の最初の会計期間を作る。
            会社ごとに最初に1回だけ実行する(承認者のみ)。create_company で作った会社は初期化済み。""")
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
