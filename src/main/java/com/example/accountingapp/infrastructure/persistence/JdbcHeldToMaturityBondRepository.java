package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.securities.HeldToMaturityBond;
import com.example.accountingapp.application.port.HeldToMaturityBondRepository;
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

/** 満期保有目的債券。ライブラリの record に ID がないため、アプリ側の ID と組で保存する。帳簿価額は保存しない。 */
@Repository
public class JdbcHeldToMaturityBondRepository implements HeldToMaturityBondRepository {

    private final JdbcClient jdbc;
    private final JdbcAccountRepository accounts;
    private final CompanyContext companyContext;

    public JdbcHeldToMaturityBondRepository(JdbcClient jdbc, JdbcAccountRepository accounts, CompanyContext companyContext) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.companyContext = companyContext;
    }

    private UUID company() {
        return companyContext.current().value();
    }

    @Override
    public Optional<RegisteredBond> findById(UUID id) {
        Map<AccountCode, Account> accountsByCode = accounts.findAllAsMap();
        return jdbc.sql("select * from held_to_maturity_bond where company_id = ? and id = ?")
                .params(company(), id)
                .query((rs, n) -> toBond(rs, accountsByCode))
                .optional();
    }

    @Override
    public List<RegisteredBond> findAll() {
        Map<AccountCode, Account> accountsByCode = accounts.findAllAsMap();
        return jdbc.sql("select * from held_to_maturity_bond where company_id = ? order by acquisition_date, name")
                .param(company())
                .query((rs, n) -> toBond(rs, accountsByCode))
                .list();
    }

    @Override
    public void save(RegisteredBond registered) {
        HeldToMaturityBond bond = registered.bond();
        jdbc.sql("""
                        insert into held_to_maturity_bond (id, company_id, name, bond_account_code, face_value,
                            acquisition_cost, acquisition_date, maturity_date)
                        values (:id, :company, :name, :account, :face, :cost, :acquired, :maturity)
                        on conflict (id) do update set
                            name = excluded.name,
                            bond_account_code = excluded.bond_account_code,
                            face_value = excluded.face_value,
                            acquisition_cost = excluded.acquisition_cost,
                            acquisition_date = excluded.acquisition_date,
                            maturity_date = excluded.maturity_date
                        """)
                .param("id", registered.id())
                .param("company", company())
                .param("name", registered.name())
                .param("account", bond.bondAccount().code().value())
                .param("face", bond.faceValue().amountInYen())
                .param("cost", bond.acquisitionCost().amountInYen())
                .param("acquired", bond.acquisitionDate())
                .param("maturity", bond.maturityDate())
                .update();
    }

    private static RegisteredBond toBond(ResultSet rs, Map<AccountCode, Account> accounts) throws SQLException {
        HeldToMaturityBond bond = new HeldToMaturityBond(
                accounts.get(AccountCode.of(rs.getString("bond_account_code"))),
                Money.ofYen(rs.getLong("face_value")),
                Money.ofYen(rs.getLong("acquisition_cost")),
                rs.getObject("acquisition_date", LocalDate.class),
                rs.getObject("maturity_date", LocalDate.class));
        return new RegisteredBond(rs.getObject("id", UUID.class), rs.getString("name"), bond);
    }
}
