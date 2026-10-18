package com.example.accountingapp.presentation.master;

import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.PeriodStatus;
import com.example.accountingapp.application.master.CompanySetupUseCase;
import com.example.accountingapp.application.statement.StatementQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 会社の初期化と、会計期間の照会。 */
@RestController
public class CompanyController {

    private final CompanySetupUseCase companySetupUseCase;
    private final StatementQueryService queries;

    public CompanyController(CompanySetupUseCase companySetupUseCase, StatementQueryService queries) {
        this.companySetupUseCase = companySetupUseCase;
        this.queries = queries;
    }

    public record SetUpRequest(@NotNull LocalDate firstPeriodStart) {
    }

    public record PeriodResponse(UUID id, LocalDate startDate, LocalDate endDate, PeriodStatus status, long version) {

        public static PeriodResponse of(AccountingPeriod period) {
            return new PeriodResponse(period.id().value(), period.startDate(), period.endDate(), period.status(),
                    period.version());
        }
    }

    /** 標準科目を投入し、最初の会計期間を作る。 */
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
    }

    @GetMapping("/periods/{id}/progress")
    public ProgressResponse progress(@PathVariable UUID id) {
        StatementQueryService.Progress p = queries.progress(id);
        return new ProgressResponse(PeriodResponse.of(p.period()),
                p.previousPeriod().map(PeriodResponse::of).orElse(null), p.adjustmentsRun(), p.accrualsReversed(),
                p.cashOverShortBalance().amountInYen(), p.journalCount());
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
