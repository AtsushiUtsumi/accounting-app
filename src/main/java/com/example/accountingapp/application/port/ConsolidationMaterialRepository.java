package com.example.accountingapp.application.port;

import com.example.accounting.model.consolidation.IntercompanyBalance;
import com.example.accounting.model.consolidation.IntercompanyTransaction;
import com.example.accounting.model.consolidation.UnrealizedProfit;
import com.example.accounting.model.period.AccountingPeriod;

import java.util.List;
import java.util.UUID;

/**
 * 連結の資料(連結会社間の債権債務・取引高・未実現利益)の保存先。親会社の会計期間ごとに保存する。
 * ライブラリの record は ID を持たないため、削除できるようアプリ側の ID と組で保存する。
 * 科目は、いま操作している会社(親会社)の科目表で組み立てる。
 */
public interface ConsolidationMaterialRepository {

    List<Registered<IntercompanyBalance>> findBalances(AccountingPeriod period);

    List<Registered<IntercompanyTransaction>> findTransactions(AccountingPeriod period);

    List<Registered<UnrealizedProfit>> findUnrealizedProfits(AccountingPeriod period);

    void addBalance(AccountingPeriod period, Registered<IntercompanyBalance> balance);

    void addTransaction(AccountingPeriod period, Registered<IntercompanyTransaction> transaction);

    void addUnrealizedProfit(AccountingPeriod period, Registered<UnrealizedProfit> profit);

    /** その期の資料を1件削除する(種類は問わない)。削除できたら true。 */
    boolean delete(AccountingPeriod period, UUID id);

    /** アプリ側の ID と、ライブラリの資料の組。 */
    record Registered<T>(UUID id, T item) {
    }
}
