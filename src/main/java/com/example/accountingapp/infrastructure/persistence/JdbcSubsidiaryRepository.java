package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.consolidation.Subsidiary;
import com.example.accounting.model.money.Money;
import com.example.accountingapp.application.port.SubsidiaryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 子会社(支配獲得時の事実)。支配獲得日の資本の科目は、いま操作している会社(親会社)の科目表で組み立てる。 */
@Repository
public class JdbcSubsidiaryRepository implements SubsidiaryRepository {

    private final JdbcClient jdbc;
    private final JdbcAccountRepository accounts;

    public JdbcSubsidiaryRepository(JdbcClient jdbc, JdbcAccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Override
    public List<Subsidiary> findByParent(CompanyId parent) {
        Map<AccountCode, Account> accountsByCode = accounts.findAllAsMap();
        Map<UUID, Map<Account, Money>> equity = new HashMap<>();
        jdbc.sql("""
                        select e.* from subsidiary_equity e join subsidiary s on s.company_id = e.company_id
                        where s.parent_company_id = ? order by e.account_code
                        """)
                .param(parent.value())
                .query(rs -> {
                    equity.computeIfAbsent(rs.getObject("company_id", UUID.class), id -> new LinkedHashMap<>())
                            .put(PersistedAccounts.get(accountsByCode, rs.getString("account_code")),
                                    Money.ofYen(rs.getLong("amount")));
                });
        return jdbc.sql("select * from subsidiary where parent_company_id = ? order by acquisition_date, company_id")
                .param(parent.value())
                .query((rs, n) -> toSubsidiary(rs, equity))
                .list();
    }

    @Override
    public Optional<CompanyId> findParentOf(CompanyId subsidiary) {
        return jdbc.sql("select parent_company_id from subsidiary where company_id = ?")
                .param(subsidiary.value())
                .query((rs, n) -> new CompanyId(rs.getObject("parent_company_id", UUID.class)))
                .optional();
    }

    @Override
    public void save(CompanyId parent, Subsidiary subsidiary) {
        jdbc.sql("""
                        insert into subsidiary (company_id, parent_company_id, acquisition_date, parent_ownership,
                            investment_cost, goodwill_amortization_years)
                        values (:company, :parent, :acquired, :ownership, :cost, :years)
                        """)
                .param("company", subsidiary.company().value())
                .param("parent", parent.value())
                .param("acquired", subsidiary.acquisitionDate())
                .param("ownership", subsidiary.parentOwnership())
                .param("cost", subsidiary.investmentCost().amountInYen())
                .param("years", subsidiary.goodwillAmortizationYears())
                .update();
        for (Map.Entry<Account, Money> entry : subsidiary.equityAtAcquisition().entrySet()) {
            jdbc.sql("insert into subsidiary_equity (company_id, account_code, amount) values (?, ?, ?)")
                    .params(subsidiary.company().value(), entry.getKey().code().value(), entry.getValue().amountInYen())
                    .update();
        }
    }

    @Override
    public void delete(CompanyId subsidiary) {
        jdbc.sql("delete from subsidiary where company_id = ?").param(subsidiary.value()).update();
    }

    private static Subsidiary toSubsidiary(ResultSet rs, Map<UUID, Map<Account, Money>> equity) throws SQLException {
        UUID company = rs.getObject("company_id", UUID.class);
        return new Subsidiary(
                new CompanyId(company),
                rs.getObject("acquisition_date", LocalDate.class),
                rs.getBigDecimal("parent_ownership").stripTrailingZeros(),
                Money.ofYen(rs.getLong("investment_cost")),
                equity.getOrDefault(company, Map.of()),
                rs.getInt("goodwill_amortization_years"));
    }
}
