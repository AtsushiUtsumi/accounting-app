package com.example.accountingapp.application.port;

import com.example.accounting.model.inventory.InventoryMovement;

import java.util.List;

/** 商品有高帳の受入・払出の明細。仕訳とは別に、追記のみで保存する。 */
public interface InventoryMovementRepository {

    void append(String itemCode, InventoryMovement movement);

    /** 品目の明細を、記録した順(時系列順)に返す。 */
    List<InventoryMovement> findByItem(String itemCode);
}
