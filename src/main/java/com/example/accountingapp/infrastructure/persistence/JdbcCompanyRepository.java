package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.consolidation.CompanyId;
import com.example.accountingapp.application.port.CompanyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会社。既定の会社はマイグレーション(V2)で作る。 */
@Repository
public class JdbcCompanyRepository implements CompanyRepository {

    private final JdbcClient jdbc;

    public JdbcCompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Company> findById(CompanyId id) {
        return jdbc.sql("select id, name from company where id = ?")
                .param(id.value())
                .query((rs, n) -> toCompany(rs))
                .optional();
    }

    @Override
    public List<Company> findAll() {
        return jdbc.sql("select id, name from company order by created_at, name")
                .query((rs, n) -> toCompany(rs))
                .list();
    }

    @Override
    public void save(Company company) {
        jdbc.sql("""
                        insert into company (id, name) values (?, ?)
                        on conflict (id) do update set name = excluded.name
                        """)
                .params(company.id().value(), company.name())
                .update();
    }

    private static Company toCompany(ResultSet rs) throws SQLException {
        return new Company(new CompanyId(rs.getObject("id", UUID.class)), rs.getString("name"));
    }
}
