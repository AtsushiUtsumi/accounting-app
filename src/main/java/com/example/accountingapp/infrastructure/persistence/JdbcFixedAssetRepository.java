package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.asset.FixedAssetId;
import com.example.accounting.model.asset.FixedAssetRepository;
import com.example.accounting.model.money.Money;
import com.example.accountingapp.application.asset.DepreciationSettings;
import com.example.accountingapp.application.port.FixedAssetDisposalRegistry;
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

/**
 * 有形固定資産。累計償却額・帳簿価額は保存しない(仕訳から導出する)。
 * 償却方法は、登録時に受け取った値({@link DepreciationSettings})を保存し、復元時に作り直す。
 * 圧縮記帳で新しいインスタンスになるため、上書き保存を許す。
 */
@Repository
public class JdbcFixedAssetRepository implements FixedAssetRepository, FixedAssetDisposalRegistry {

    private final JdbcClient jdbc;
    private final JdbcAccountRepository accounts;
    private final CompanyContext companyContext;

    public JdbcFixedAssetRepository(JdbcClient jdbc, JdbcAccountRepository accounts, CompanyContext companyContext) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.companyContext = companyContext;
    }

    private UUID company() {
        return companyContext.current().value();
    }

    @Override
    public Optional<FixedAsset> findById(FixedAssetId id) {
        Map<AccountCode, Account> accountsByCode = accounts.findAllAsMap();
        return jdbc.sql("select * from fixed_asset where company_id = ? and id = ?")
                .params(company(), id.value())
                .query((rs, n) -> toAsset(rs, accountsByCode))
                .optional();
    }

    @Override
    public List<FixedAsset> findAll() {
        Map<AccountCode, Account> accountsByCode = accounts.findAllAsMap();
        return jdbc.sql("select * from fixed_asset where company_id = ? order by acquisition_date, name")
                .param(company())
                .query((rs, n) -> toAsset(rs, accountsByCode))
                .list();
    }

    @Override
    public void save(FixedAsset asset) {
        DepreciationSettings settings = DepreciationSettings.of(asset.depreciationPolicy());
        jdbc.sql("""
                        insert into fixed_asset (id, company_id, name, asset_account_code, accumulated_depreciation_code,
                            depreciation_expense_code, acquisition_cost, acquisition_date, useful_life_years,
                            residual_value, policy_kind, declining_rate, total_estimated_usage, rounding)
                        values (:id, :company, :name, :asset, :accumulated, :expense, :cost, :acquired, :life,
                            :residual, :kind, :rate, :usage, :rounding)
                        on conflict (id) do update set
                            name = excluded.name,
                            asset_account_code = excluded.asset_account_code,
                            accumulated_depreciation_code = excluded.accumulated_depreciation_code,
                            depreciation_expense_code = excluded.depreciation_expense_code,
                            acquisition_cost = excluded.acquisition_cost,
                            acquisition_date = excluded.acquisition_date,
                            useful_life_years = excluded.useful_life_years,
                            residual_value = excluded.residual_value,
                            policy_kind = excluded.policy_kind,
                            declining_rate = excluded.declining_rate,
                            total_estimated_usage = excluded.total_estimated_usage,
                            rounding = excluded.rounding
                        """)
                .param("id", asset.id().value())
                .param("company", company())
                .param("name", asset.name())
                .param("asset", asset.assetAccount().code().value())
                .param("accumulated", asset.accumulatedDepreciationAccount().code().value())
                .param("expense", asset.depreciationExpenseAccount().code().value())
                .param("cost", asset.acquisitionCost().amountInYen())
                .param("acquired", asset.acquisitionDate())
                .param("life", asset.usefulLifeYears())
                .param("residual", asset.residualValue().amountInYen())
                .param("kind", settings.method().name())
                .param("rate", settings.decliningRate())
                .param("usage", settings.totalEstimatedUsage())
                .param("rounding", settings.rounding().name())
                .update();
    }

    @Override
    public void markDisposed(FixedAssetId id, LocalDate date) {
        jdbc.sql("insert into fixed_asset_disposal (fixed_asset_id, disposed_on) values (?, ?)")
                .params(id.value(), date)
                .update();
    }

    @Override
    public Optional<LocalDate> disposedOn(FixedAssetId id) {
        return jdbc.sql("select disposed_on from fixed_asset_disposal where fixed_asset_id = ?")
                .param(id.value())
                .query(LocalDate.class)
                .optional();
    }

    private static FixedAsset toAsset(ResultSet rs, Map<AccountCode, Account> accounts) throws SQLException {
        DepreciationSettings settings = new DepreciationSettings(
                DepreciationSettings.Method.valueOf(rs.getString("policy_kind")),
                rs.getBigDecimal("declining_rate"),
                rs.getObject("total_estimated_usage", Long.class),
                DepreciationSettings.Rounding.valueOf(rs.getString("rounding")));
        return new FixedAsset(
                new FixedAssetId(rs.getObject("id", UUID.class)),
                rs.getString("name"),
                accounts.get(AccountCode.of(rs.getString("asset_account_code"))),
                accounts.get(AccountCode.of(rs.getString("accumulated_depreciation_code"))),
                accounts.get(AccountCode.of(rs.getString("depreciation_expense_code"))),
                Money.ofYen(rs.getLong("acquisition_cost")),
                rs.getObject("acquisition_date", LocalDate.class),
                rs.getInt("useful_life_years"),
                Money.ofYen(rs.getLong("residual_value")),
                settings.toPolicy());
    }
}
