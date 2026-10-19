package com.example.accountingapp.infrastructure.persistence;

import com.example.accountingapp.application.port.UserAccountRepository;
import com.example.accountingapp.application.user.UserAccount;
import com.example.accountingapp.application.user.UserAccount.Role;
import com.example.accountingapp.application.user.UserAccount.Source;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** サインアップしたユーザー。会社には属さない(どのユーザーも、すべての会社を同じロールで操作する)。 */
@Repository
public class JdbcUserAccountRepository implements UserAccountRepository {

    private final JdbcClient jdbc;

    public JdbcUserAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByName(String name) {
        return jdbc.sql("select * from app_user where name = ?")
                .param(name)
                .query((rs, n) -> toUser(rs))
                .optional();
    }

    @Override
    public List<UserAccount> findAll() {
        return jdbc.sql("select * from app_user order by name")
                .query((rs, n) -> toUser(rs))
                .list();
    }

    @Override
    public void save(UserAccount user) {
        String role = user.role() == null ? null : user.role().name();
        int updated = jdbc.sql("update app_user set role = ? where name = ?")
                .params(role, user.name())
                .update();
        if (updated == 0) {
            jdbc.sql("insert into app_user (name, password, role) values (?, ?, ?)")
                    .params(user.name(), user.passwordHash(), role)
                    .update();
        }
    }

    @Override
    public void delete(String name) {
        jdbc.sql("delete from app_user where name = ?").param(name).update();
    }

    private static UserAccount toUser(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        return new UserAccount(rs.getString("name"), rs.getString("password"),
                role == null ? null : Role.valueOf(role), Source.SIGNED_UP);
    }
}
