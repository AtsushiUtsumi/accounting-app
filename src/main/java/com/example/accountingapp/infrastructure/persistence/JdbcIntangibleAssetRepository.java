package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.asset.FixedAssetId;
import com.example.accounting.model.asset.IntangibleAsset;
import com.example.accounting.model.money.Money;
import com.example.accountingapp.application.port.IntangibleAssetRepository;
import com.example.accountingapp.application.port.CompanyContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 無形固定資産。帳簿価額は保存しない(資産の勘定の残高から導出する)。 */
@Repository
public class JdbcIntangibleAssetRepository implements IntangibleAssetRepository {

    private final JdbcClient jdbc;
    private final JdbcAccountRepository accounts;
    private final CompanyContext companyContext;

    public JdbcIntangibleAssetRepository(JdbcClient jdbc, JdbcAccountRepository accounts, CompanyContext companyContext) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.companyContext = companyContext;
    }

    private UUID company() {
        return companyContext.current().value();
    }

    @Override
    public Optional<IntangibleAsset> findById(FixedAssetId id) {
        Map<AccountCode, Account> accountsByCode = accounts.findAllAsMap();
        return jdbc.sql("select * from intangible_asset where company_id = ? and id = ?")
                .params(company(), id.value())
                .query((rs, n) -> toAsset(rs, accountsByCode))
                .optional();
    }

    @Override
    public List<IntangibleAsset> findAll() {
        Map<AccountCode, Account> accountsByCode = accounts.findAllAsMap();
        return jdbc.sql("select * from intangible_asset where company_id = ? order by acquisition_date, name")
                .param(company())
                .query((rs, n) -> toAsset(rs, accountsByCode))
                .list();
    }

    @Override
    public void save(IntangibleAsset asset) {
        jdbc.sql("""
                        insert into intangible_asset (id, company_id, name, asset_account_code,
                            amortization_expense_code, acquisition_cost, acquisition_date, useful_life_years)
                        values (:id, :company, :name, :asset, :expense, :cost, :acquired, :life)
                        on conflict (id) do update set
                            name = excluded.name,
                            asset_account_code = excluded.asset_account_code,
                            amortization_expense_code = excluded.amortization_expense_code,
                            acquisition_cost = excluded.acquisition_cost,
                            acquisition_date = excluded.acquisition_date,
                            useful_life_years = excluded.useful_life_years
                        """)
                .param("id", asset.id().value())
                .param("company", company())
                .param("name", asset.name())
                .param("asset", asset.assetAccount().code().value())
                .param("expense", asset.amortizationExpenseAccount().code().value())
                .param("cost", asset.acquisitionCost().amountInYen())
                .param("acquired", asset.acquisitionDate())
                .param("life", asset.usefulLifeYears())
                .update();
    }

    private static IntangibleAsset toAsset(ResultSet rs, Map<AccountCode, Account> accounts) throws SQLException {
        return new IntangibleAsset(
                new FixedAssetId(rs.getObject("id", UUID.class)),
                rs.getString("name"),
                accounts.get(AccountCode.of(rs.getString("asset_account_code"))),
                accounts.get(AccountCode.of(rs.getString("amortization_expense_code"))),
                Money.ofYen(rs.getLong("acquisition_cost")),
                rs.getObject("acquisition_date", LocalDate.class),
                rs.getInt("useful_life_years"));
    }
}
