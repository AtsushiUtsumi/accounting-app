package com.example.accountingapp.presentation.mcp;

/**
 * MCP のツールを持つクラスの目印。実装したクラスの {@code @Tool} メソッドが、MCP サーバーのツールとして公開される
 * ({@link McpToolConfig})。
 *
 * <p>ツールは REST のコントローラーと同じユースケースを呼び、同じレスポンスの形を返す。認可はコントローラーの URL ごとの
 * 規則と同じロールを、メソッドごとに {@code @PreAuthorize} で指定する(MCP は1つの URL にすべての操作が来るため)。
 * 入力の形式は {@code @Validated} によるメソッドの検証で検査する。
 */
public interface McpTools {

    /** 照会(REST の GET に当たる操作)。 */
    String VIEWER = "hasRole('VIEWER')";

    /** 記帳などの更新。 */
    String BOOKKEEPER = "hasRole('BOOKKEEPER')";

    /** 会社の作成と初期化・決算整理・締め・再振替・連結の入力。 */
    String APPROVER = "hasRole('APPROVER')";
}
