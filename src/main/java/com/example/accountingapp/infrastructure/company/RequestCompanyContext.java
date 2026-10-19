package com.example.accountingapp.infrastructure.company;

import com.example.accounting.model.consolidation.CompanyId;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.CompanyContext;
import com.example.accountingapp.application.port.CompanyRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * いま操作している会社。{@link #callAs} で切り替えていればその会社、そうでなければリクエストヘッダー
 * {@value #HEADER} の会社、ヘッダーがなければ既定の会社({@link CompanyRepository#DEFAULT_COMPANY})。
 * ヘッダーの会社が存在するかは、リクエストごとに1回だけ確かめる。
 */
@Component
public class RequestCompanyContext implements CompanyContext {

    public static final String HEADER = "X-Company-Id";

    private static final String VERIFIED_ATTRIBUTE = RequestCompanyContext.class.getName() + ".verified";

    private static final ThreadLocal<CompanyId> OVERRIDE = new ThreadLocal<>();

    private final CompanyRepository companyRepository;

    public RequestCompanyContext(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @Override
    public CompanyId current() {
        CompanyId override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return CompanyRepository.DEFAULT_COMPANY;
        }
        String header = servlet.getRequest().getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return CompanyRepository.DEFAULT_COMPANY;
        }
        CompanyId company = parse(header.strip());
        if (!company.equals(attributes.getAttribute(VERIFIED_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST))) {
            companyRepository.findById(company)
                    .orElseThrow(() -> new ResourceNotFoundException("company not found: " + company.value()));
            attributes.setAttribute(VERIFIED_ATTRIBUTE, company, RequestAttributes.SCOPE_REQUEST);
        }
        return company;
    }

    @Override
    public <T> T callAs(CompanyId company, Supplier<T> action) {
        CompanyId previous = OVERRIDE.get();
        OVERRIDE.set(company);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }

    private static CompanyId parse(String value) {
        try {
            return new CompanyId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new InvalidCommandException(HEADER + " must be a company ID (UUID): " + value);
        }
    }
}
