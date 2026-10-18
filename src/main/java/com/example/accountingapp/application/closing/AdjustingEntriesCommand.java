package com.example.accountingapp.application.closing;

import com.example.accounting.service.ProvisionType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 決算整理の入力。すべて外部から与える事実(棚卸の結果、時価、引当金の設定額、確定した税額など)で、
 * アプリは計算しない。省略(null・空)した手順は実行しない。
 *
 * @param reclassifyBankOverdraft 当座借越の振替を行うか
 * @param checkingAccountCode     当座預金の科目(省略時は標準科目の当座預金)
 * @param inventoryMethod         商品の記帳方法。三分法なら売上原価の算定を行う
 * @param beginningInventory      期首商品棚卸高(三分法)
 * @param endingInventory         期末商品棚卸高(三分法。帳簿棚卸高)
 * @param inventoryCounts         実地棚卸の結果(品目ごと)
 * @param fixedAssetUsages        生産高比例法の資産ごとの当期の利用量
 * @param tradingSecuritiesFairValue  売買目的有価証券の期末時価
 * @param availableForSaleSecurities  その他有価証券の期末時価(科目ごと)
 * @param allowanceForDoubtfulAccounts 貸倒引当金の設定額(期末に必要な残高)
 * @param provisions              その他の引当金の設定額
 * @param retirementBenefitExpense 当期の退職給付費用
 * @param incomeTaxAmount         確定した法人税等の額
 * @param settleConsumptionTax    消費税の精算を行うか
 * @param accrualsConfirmed       経過勘定・貯蔵品(手順7)の手入力が済んでいることを利用者が確認したか。確定のときは必須
 */
public record AdjustingEntriesCommand(
        boolean reclassifyBankOverdraft,
        String checkingAccountCode,
        InventoryMethod inventoryMethod,
        Long beginningInventory,
        Long endingInventory,
        List<InventoryCountCommand> inventoryCounts,
        Map<UUID, Long> fixedAssetUsages,
        Long tradingSecuritiesFairValue,
        List<FairValueCommand> availableForSaleSecurities,
        Long allowanceForDoubtfulAccounts,
        List<ProvisionCommand> provisions,
        Long retirementBenefitExpense,
        Long incomeTaxAmount,
        boolean settleConsumptionTax,
        boolean accrualsConfirmed
) {

    public AdjustingEntriesCommand {
        inventoryMethod = inventoryMethod == null ? InventoryMethod.PERIODIC : inventoryMethod;
        inventoryCounts = inventoryCounts == null ? List.of() : List.copyOf(inventoryCounts);
        fixedAssetUsages = fixedAssetUsages == null ? Map.of() : Map.copyOf(fixedAssetUsages);
        availableForSaleSecurities = availableForSaleSecurities == null ? List.of() : List.copyOf(availableForSaleSecurities);
        provisions = provisions == null ? List.of() : List.copyOf(provisions);
    }

    /** 商品の記帳方法。三分法(仕入・繰越商品)と売上原価対立法(商品・売上原価)。 */
    public enum InventoryMethod {
        PERIODIC, PERPETUAL
    }

    public record InventoryCountCommand(int bookQuantity, int actualQuantity, long unitCost, long netRealizableValue) {
    }

    public record FairValueCommand(String accountCode, long fairValue) {
    }

    /** 引当金の設定。{@code method} は差額補充法(DIFFERENCE)か洗替法(REPLACEMENT)。 */
    public record ProvisionCommand(ProvisionType type, ProvisionMethod method, long requiredBalance) {
    }

    public enum ProvisionMethod {
        DIFFERENCE, REPLACEMENT
    }
}
