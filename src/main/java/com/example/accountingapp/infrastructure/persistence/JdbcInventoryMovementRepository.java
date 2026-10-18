package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.inventory.InventoryMovement;
import com.example.accounting.model.money.Money;
import com.example.accountingapp.application.port.InventoryMovementRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/** 商品有高帳の受入・払出の明細。仕訳とは別に、追記のみで保存する。 */
@Repository
public class JdbcInventoryMovementRepository implements InventoryMovementRepository {

    private final JdbcClient jdbc;

    public JdbcInventoryMovementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(String itemCode, InventoryMovement movement) {
        Long unitCost = switch (movement) {
            case InventoryMovement.Receipt receipt -> receipt.unitCost().amountInYen();
            case InventoryMovement.Issue issue -> null;
        };
        jdbc.sql("""
                        insert into inventory_movement (item_code, kind, date, description, quantity, unit_cost)
                        values (:item, :kind, :date, :description, :quantity, :unitCost)
                        """)
                .param("item", itemCode)
                .param("kind", movement instanceof InventoryMovement.Receipt ? "RECEIPT" : "ISSUE")
                .param("date", movement.date())
                .param("description", movement.description())
                .param("quantity", movement.quantity())
                .param("unitCost", unitCost)
                .update();
    }

    @Override
    public List<InventoryMovement> findByItem(String itemCode) {
        return jdbc.sql("select * from inventory_movement where item_code = ? order by seq")
                .param(itemCode)
                .query((rs, n) -> toMovement(rs))
                .list();
    }

    private static InventoryMovement toMovement(ResultSet rs) throws SQLException {
        LocalDate date = rs.getObject("date", LocalDate.class);
        String description = rs.getString("description");
        int quantity = rs.getInt("quantity");
        return switch (rs.getString("kind")) {
            case "RECEIPT" -> new InventoryMovement.Receipt(date, description, quantity, Money.ofYen(rs.getLong("unit_cost")));
            case "ISSUE" -> new InventoryMovement.Issue(date, description, quantity);
            default -> throw new IllegalStateException("unknown inventory movement kind: " + rs.getString("kind"));
        };
    }
}
