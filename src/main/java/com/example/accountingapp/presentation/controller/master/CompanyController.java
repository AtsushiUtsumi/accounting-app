package com.example.accountingapp.presentation.controller.master;

import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.PeriodStatus;
import com.example.accountingapp.application.master.CompanySetupUseCase;
import com.example.accountingapp.application.master.CompanyUseCase;
import com.example.accountingapp.application.port.CompanyRepository.Company;
import com.example.accountingapp.application.statement.StatementQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 会社の管理と初期化、会計期間の照会。会社を指定する操作は、リクエストヘッダー {@code X-Company-Id} の会社
 * (省略すれば既定の会社)が対象になる。
 */
@RestController
public class CompanyController {

    private final CompanyUseCase companyUseCase;
    private final CompanySetupUseCase companySetupUseCase;
    private final StatementQueryService queries;

    public CompanyController(CompanyUseCase companyUseCase, CompanySetupUseCase companySetupUseCase,
                             StatementQueryService queries) {
        this.companyUseCase = companyUseCase;
        this.companySetupUseCase = companySetupUseCase;
        this.queries = queries;
    }

    public record CompanyResponse(UUID id, String name) {

        public static CompanyResponse of(Company company) {
            return new CompanyResponse(company.id().value(), company.name());
        }
    }

    public record CreateCompanyRequest(@NotBlank @Size(max = 100) String name, @NotNull LocalDate firstPeriodStart) {
    }

    public record CreatedCompanyResponse(CompanyResponse company, PeriodResponse firstPeriod) {

        public static CreatedCompanyResponse of(CompanyUseCase.Created created) {
            return new CreatedCompanyResponse(CompanyResponse.of(created.company()),
                    PeriodResponse.of(created.firstPeriod()));
        }
    }

    public record RenameCompanyRequest(@NotBlank @Size(max = 100) String name) {
    }

    /** すべての会社(作った順)。 */
    @GetMapping("/companies")
    public List<CompanyResponse> companies() {
        return companyUseCase.list().stream().map(CompanyResponse::of).toList();
    }

    /** 会社を作り、標準科目と最初の会計期間を用意する。 */
    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedCompanyResponse createCompany(@Valid @RequestBody CreateCompanyRequest request) {
        return CreatedCompanyResponse.of(companyUseCase.create(request.name(), request.firstPeriodStart()));
    }

    @PatchMapping("/companies/{id}")
    public CompanyResponse renameCompany(@PathVariable UUID id, @Valid @RequestBody RenameCompanyRequest request) {
        return CompanyResponse.of(companyUseCase.rename(new CompanyId(id), request.name()));
    }

    public record SetUpRequest(@NotNull LocalDate firstPeriodStart) {
    }

    public record PeriodResponse(UUID id, LocalDate startDate, LocalDate endDate, PeriodStatus status, long version) {

        public static PeriodResponse of(AccountingPeriod period) {
            return new PeriodResponse(period.id().value(), period.startDate(), period.endDate(), period.status(),
                    period.version());
        }
    }

    /** いま操作している会社に、標準科目を投入し、最初の会計期間を作る。 */
    @PostMapping("/company")
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodResponse setUp(@Valid @RequestBody SetUpRequest request) {
        return PeriodResponse.of(companySetupUseCase.setUp(request.firstPeriodStart()));
    }

    /** すべての期(期首日の新しい順)。 */
    @GetMapping("/periods")
    public List<PeriodResponse> periods() {
        return queries.periods().stream().map(PeriodResponse::of).toList();
    }

    /** 決算の進み具合(ホーム画面の表示用)。 */
    public record ProgressResponse(PeriodResponse period, PeriodResponse previousPeriod, boolean adjustmentsRun,
                                   boolean accrualsReversed, long cashOverShortBalance, int journalCount) {

        public static ProgressResponse of(StatementQueryService.Progress p) {
            return new ProgressResponse(PeriodResponse.of(p.period()),
                    p.previousPeriod().map(PeriodResponse::of).orElse(null), p.adjustmentsRun(), p.accrualsReversed(),
                    p.cashOverShortBalance().amountInYen(), p.journalCount());
        }
    }

    @GetMapping("/periods/{id}/progress")
    public ProgressResponse progress(@PathVariable UUID id) {
        return ProgressResponse.of(queries.progress(id));
    }

    @GetMapping("/periods/latest")
    public PeriodResponse latest() {
        return PeriodResponse.of(queries.latestPeriod());
    }

    @GetMapping("/periods/{id}")
    public PeriodResponse get(@PathVariable UUID id) {
        return PeriodResponse.of(queries.period(id));
    }
}
