package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCategory;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.account.AccountRepository;
import com.example.accounting.model.account.AccountTag;
import com.example.accounting.model.account.AccountType;
import com.example.accounting.model.consolidation.CompanyId;
import com.example.accountingapp.application.port.CompanyContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 勘定科目。科目表は会社ごと({@link CompanyContext} の会社の科目だけを読み書きする)。 */
@Repository
public class JdbcAccountRepository implements AccountRepository {

    private final JdbcClient jdbc;
    private final CompanyContext companyContext;

    public JdbcAccountRepository(JdbcClient jdbc, CompanyContext companyContext) {
        this.jdbc = jdbc;
        this.companyContext = companyContext;
    }

    @Override
    public Optional<Account> findByCode(AccountCode code) {
        CompanyId company = companyContext.current();
        Set<AccountTag> tags = jdbc.sql("select tag from account_tag where company_id = ? and account_code = ?")
                .params(company.value(), code.value())
                .query((rs, n) -> AccountTag.of(rs.getString("tag")))
                .set();
        return jdbc.sql("select * from account where company_id = ? and code = ?")
                .params(company.value(), code.value())
                .query((rs, n) -> toAccount(rs, tags))
                .optional();
    }

    @Override
    public List<Account> findAll() {
        CompanyId company = companyContext.current();
        Map<String, Set<AccountTag>> tags = new HashMap<>();
        jdbc.sql("select account_code, tag from account_tag where company_id = ?").param(company.value()).query(rs -> {
            tags.computeIfAbsent(rs.getString("account_code"), code -> new HashSet<>())
                    .add(AccountTag.of(rs.getString("tag")));
        });
        return jdbc.sql("select * from account where company_id = ? order by code")
                .param(company.value())
                .query((rs, n) -> toAccount(rs, tags.getOrDefault(rs.getString("code"), Set.of())))
                .list();
    }

    /** 仕訳などを組み立てるための、全科目の一括読み込み(N+1 問題を避ける)。 */
    public Map<AccountCode, Account> findAllAsMap() {
        return findAll().stream().collect(Collectors.toMap(Account::code, Function.identity()));
    }

    @Override
    public void save(Account account) {
        CompanyId company = companyContext.current();
        jdbc.sql("""
                        insert into account (company_id, code, name, type, category, standard, active)
                        values (:company, :code, :name, :type, :category, :standard, :active)
                        on conflict (company_id, code) do update
                        set name = excluded.name, standard = excluded.standard, active = excluded.active
                        """)
                .param("company", company.value())
                .param("code", account.code().value())
                .param("name", account.name())
                .param("type", account.type().name())
                .param("category", account.category().name())
                .param("standard", account.standard())
                .param("active", account.active())
                .update();
        jdbc.sql("delete from account_tag where company_id = ? and account_code = ?")
                .params(company.value(), account.code().value())
                .update();
        for (AccountTag tag : account.tags()) {
            jdbc.sql("insert into account_tag (company_id, account_code, tag) values (?, ?, ?)")
                    .params(company.value(), account.code().value(), tag.value())
                    .update();
        }
    }

    @Override
    public boolean existsByCode(AccountCode code) {
        return jdbc.sql("select exists(select 1 from account where company_id = ? and code = ?)")
                .params(companyContext.current().value(), code.value())
                .query(Boolean.class)
                .single();
    }

    private static Account toAccount(ResultSet rs, Set<AccountTag> tags) throws SQLException {
        return new Account(
                AccountCode.of(rs.getString("code")),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                AccountCategory.valueOf(rs.getString("category")),
                tags,
                rs.getBoolean("standard"),
                rs.getBoolean("active"));
    }
}
