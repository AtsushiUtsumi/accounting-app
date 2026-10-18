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

    @GetMapping("/periods/latest")
    public PeriodResponse latest() {
        return PeriodResponse.of(queries.latestPeriod());
    }

    @GetMapping("/periods/{id}")
    public PeriodResponse get(@PathVariable UUID id) {
        return PeriodResponse.of(queries.period(id));
    }
}
