package com.example.accountingapp.application.port;

import com.example.accounting.model.consolidation.CompanyId;

import java.util.function.Supplier;

/**
 * いま操作している会社。リポジトリはこの会社のデータだけを読み書きする(科目・期・仕訳・資産・商品有高帳)。
 * REST はリクエストヘッダー、MCP はツールの引数で会社を選ぶ。指定がなければ既定の会社。
 */
public interface CompanyContext {

    /** いま操作している会社。指定された会社が存在しなければ {@code ResourceNotFoundException}。 */
    CompanyId current();

    /** 一時的にほかの会社として処理する(連結で子会社の帳簿を読むときなど)。 */
    <T> T callAs(CompanyId company, Supplier<T> action);
}
