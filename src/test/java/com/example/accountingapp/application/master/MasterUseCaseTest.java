package com.example.accountingapp.application.master;

import com.example.accounting.exception.AccountingException;
import com.example.accounting.model.account.AccountCategory;
import com.example.accounting.model.account.AccountTag;
import com.example.accounting.model.account.AccountType;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.support.TestAccounting;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static com.example.accountingapp.support.TestAccounting.cr;
import static com.example.accountingapp.support.TestAccounting.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasterUseCaseTest {

    private final TestAccounting app = new TestAccounting();

    @Test
    void setUp_loadsStandardAccountsAndCreatesTheFirstOneYearPeriod() {
        AccountingPeriod period = app.setUpCompany(LocalDate.of(2026, 4, 1));

        assertThat(app.accounts.findAll()).containsExactlyInAnyOrderElementsOf(StandardAccounts.ALL);
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(period.isOpen()).isTrue();
        assertThatThrownBy(() -> app.setUpCompany(LocalDate.of(2027, 4, 1)))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void accounts_addRenameTagAndDeactivate() {
        app.setUpCompany(LocalDate.of(2026, 4, 1));

        app.accountManagement.add("1081", "備品(PC)", AccountType.ASSET, AccountCategory.FIXED_ASSET, Set.of("IT"));
        app.accountManagement.update("1081", "備品(パソコン)", Set.of("IT", "本社"), null);
        var updated = app.accountManagement.update("1081", null, null, true);

        assertThat(updated.name()).isEqualTo("備品(パソコン)");
        assertThat(updated.tags()).containsExactlyInAnyOrder(AccountTag.of("IT"), AccountTag.of("本社"));
        assertThat(updated.active()).isFalse();
        assertThatThrownBy(() -> app.accountManagement.add("1081", "重複", AccountType.ASSET,
                AccountCategory.FIXED_ASSET, Set.of()))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void accounts_standardAccountIsProtectedByTheDomain() {
        app.setUpCompany(LocalDate.of(2026, 4, 1));

        assertThatThrownBy(() -> app.accountManagement.update(StandardAccounts.CASH.code().value(), null, null, true))
                .isInstanceOf(AccountingException.class);
    }

    @Test
    void accounts_usedFlag() {
        app.setUpCompany(LocalDate.of(2026, 4, 1));
        app.post(LocalDate.of(2026, 4, 1), "出資による開業",
                dr(StandardAccounts.CASH, 600_000), cr(StandardAccounts.CAPITAL_STOCK, 600_000));

        assertThat(app.accountManagement.get(StandardAccounts.CASH.code().value()).used()).isTrue();
        assertThat(app.accountManagement.get(StandardAccounts.SALES.code().value()).used()).isFalse();
    }
}
