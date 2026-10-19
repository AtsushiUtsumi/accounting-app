package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.consolidation.IntercompanyBalance;
import com.example.accounting.model.consolidation.IntercompanyTransaction;
import com.example.accounting.model.consolidation.UnrealizedProfit;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.port.ConsolidationMaterialRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 連結の資料(親会社の期ごと)。科目は、いま操作している会社(親会社)の科目表で組み立てる。
 * ID はテーブルをまたいで一意(UUID)なので、削除は種類を問わずに ID で行う。
 */
@Repository
public class JdbcConsolidationMaterialRepository implements ConsolidationMaterialRepository {

    private static final List<String> TABLES = List.of("intercompany_balance", "intercompany_transaction", "unrealized_profit");

    private final JdbcClient jdbc;
    private final JdbcAccountRepository accounts;

    public JdbcConsolidationMaterialRepository(JdbcClient jdbc, JdbcAccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Override
    public List<Registered<IntercompanyBalance>> findBalances(AccountingPeriod period) {
        Map<AccountCode, Account> chart = accounts.findAllAsMap();
        return jdbc.sql("select * from intercompany_balance where period_id = ? order by receivable_code, id")
                .param(period.id().value())
                .query((rs, n) -> new Registered<>(id(rs), new IntercompanyBalance(
                        company(rs, "creditor_company_id"),
                        PersistedAccounts.get(chart, rs.getString("receivable_code")),
                        PersistedAccounts.get(chart, rs.getString("payable_code")),
                        Money.ofYen(rs.getLong("amount")),
                        Money.ofYen(rs.getLong("allowance")),
                        Money.ofYen(rs.getLong("allowance_at_beginning")))))
                .list();
    }

    @Override
    public List<Registered<IntercompanyTransaction>> findTransactions(AccountingPeriod period) {
        Map<AccountCode, Account> chart = accounts.findAllAsMap();
        return jdbc.sql("select * from intercompany_transaction where period_id = ? order by revenue_code, id")
                .param(period.id().value())
                .query((rs, n) -> new Registered<>(id(rs), new IntercompanyTransaction(
                        PersistedAccounts.get(chart, rs.getString("revenue_code")),
                        PersistedAccounts.get(chart, rs.getString("expense_code")),
                        Money.ofYen(rs.getLong("amount")))))
                .list();
    }

    @Override
    public List<Registered<UnrealizedProfit>> findUnrealizedProfits(AccountingPeriod period) {
        Map<AccountCode, Account> chart = accounts.findAllAsMap();
        return jdbc.sql("select * from unrealized_profit where period_id = ? order by kind, id")
                .param(period.id().value())
                .query((rs, n) -> new Registered<>(id(rs), toUnrealizedProfit(rs, chart)))
                .list();
    }

    @Override
    public void addBalance(AccountingPeriod period, Registered<IntercompanyBalance> registered) {
        IntercompanyBalance balance = registered.item();
        jdbc.sql("""
                        insert into intercompany_balance (id, period_id, creditor_company_id, receivable_code,
                            payable_code, amount, allowance, allowance_at_beginning)
                        values (:id, :period, :creditor, :receivable, :payable, :amount, :allowance, :beginning)
                        """)
                .param("id", registered.id())
                .param("period", period.id().value())
                .param("creditor", balance.creditor().value())
                .param("receivable", balance.receivable().code().value())
                .param("payable", balance.payable().code().value())
                .param("amount", balance.amount().amountInYen())
                .param("allowance", balance.allowance().amountInYen())
                .param("beginning", balance.allowanceAtBeginning().amountInYen())
                .update();
    }

    @Override
    public void addTransaction(AccountingPeriod period, Registered<IntercompanyTransaction> registered) {
        IntercompanyTransaction transaction = registered.item();
        jdbc.sql("""
                        insert into intercompany_transaction (id, period_id, revenue_code, expense_code, amount)
                        values (?, ?, ?, ?, ?)
                        """)
                .params(registered.id(), period.id().value(), transaction.revenue().code().value(),
                        transaction.expense().code().value(), transaction.amount().amountInYen())
                .update();
    }

    @Override
    public void addUnrealizedProfit(AccountingPeriod period, Registered<UnrealizedProfit> registered) {
        JdbcClient.StatementSpec insert = jdbc.sql("""
                        insert into unrealized_profit (id, period_id, kind, seller_company_id, inventory_account_code,
                            ending, beginning, amount, sale_date)
                        values (:id, :period, :kind, :seller, :inventory, :ending, :beginning, :amount, :saleDate)
                        """)
                .param("id", registered.id())
                .param("period", period.id().value())
                .param("seller", registered.item().seller().value());
        switch (registered.item()) {
            case UnrealizedProfit.Inventory inventory -> insert
                    .param("kind", "INVENTORY")
                    .param("inventory", inventory.inventory().code().value())
                    .param("ending", inventory.ending().amountInYen())
                    .param("beginning", inventory.beginning().amountInYen())
                    .param("amount", null)
                    .param("saleDate", null);
            case UnrealizedProfit.Land land -> insert
                    .param("kind", "LAND")
                    .param("inventory", null)
                    .param("ending", null)
                    .param("beginning", null)
                    .param("amount", land.amount().amountInYen())
                    .param("saleDate", land.saleDate());
        }
        insert.update();
    }

    @Override
    public boolean delete(AccountingPeriod period, UUID id) {
        int deleted = 0;
        for (String table : TABLES) {
            deleted += jdbc.sql("delete from " + table + " where period_id = ? and id = ?")
                    .params(period.id().value(), id)
                    .update();
        }
        return deleted > 0;
    }

    private static UnrealizedProfit toUnrealizedProfit(ResultSet rs, Map<AccountCode, Account> chart) throws SQLException {
        CompanyId seller = company(rs, "seller_company_id");
        return switch (rs.getString("kind")) {
            case "INVENTORY" -> new UnrealizedProfit.Inventory(seller,
                    PersistedAccounts.get(chart, rs.getString("inventory_account_code")),
                    Money.ofYen(rs.getLong("ending")), Money.ofYen(rs.getLong("beginning")));
            case "LAND" -> new UnrealizedProfit.Land(seller, Money.ofYen(rs.getLong("amount")),
                    rs.getObject("sale_date", LocalDate.class));
            default -> throw new IllegalStateException("unknown unrealized profit kind: " + rs.getString("kind"));
        };
    }

    private static UUID id(ResultSet rs) throws SQLException {
        return rs.getObject("id", UUID.class);
    }

    private static CompanyId company(ResultSet rs, String column) throws SQLException {
        return new CompanyId(rs.getObject(column, UUID.class));
    }
}
