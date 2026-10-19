package com.example.accountingapp.application.master;

import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.CompanyContext;
import com.example.accountingapp.application.port.CompanyRepository;
import com.example.accountingapp.application.port.CompanyRepository.Company;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 会社の管理(複数の会社の帳簿を持つ。CLAUDE.md §9 のマルチテナント)。科目表・期・仕訳は会社ごとで、
 * どの会社を操作するかはリクエストごとに選ぶ({@link CompanyContext})。
 */
@Service
public class CompanyUseCase {

    private final CompanyRepository companyRepository;
    private final CompanyContext companyContext;
    private final CompanySetupUseCase companySetupUseCase;

    public CompanyUseCase(CompanyRepository companyRepository, CompanyContext companyContext,
                          CompanySetupUseCase companySetupUseCase) {
        this.companyRepository = companyRepository;
        this.companyContext = companyContext;
        this.companySetupUseCase = companySetupUseCase;
    }

    @Transactional(readOnly = true)
    public List<Company> list() {
        return companyRepository.findAll();
    }

    /** いま操作している会社。 */
    @Transactional(readOnly = true)
    public Company current() {
        return get(companyContext.current());
    }

    /** 会社を作り、標準科目と最初の会計期間を用意する(既定の会社の「会社の初期化」と同じ)。 */
    @Transactional
    public Created create(String name, LocalDate firstPeriodStart) {
        Company company = new Company(CompanyId.newId(), requireName(name));
        companyRepository.save(company);
        AccountingPeriod period = companyContext.callAs(company.id(), () -> companySetupUseCase.setUp(firstPeriodStart));
        return new Created(company, period);
    }

    @Transactional
    public Company rename(CompanyId id, String name) {
        Company renamed = new Company(get(id).id(), requireName(name));
        companyRepository.save(renamed);
        return renamed;
    }

    public record Created(Company company, AccountingPeriod firstPeriod) {
    }

    private Company get(CompanyId id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("company not found: " + id.value()));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidCommandException("company name is required");
        }
        return name.strip();
    }
}
