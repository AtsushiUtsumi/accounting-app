package com.example.accountingapp.presentation.mcp.inventory;

import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.inventory.InventoryUseCase;
import com.example.accountingapp.application.inventory.InventoryUseCase.ValuationMethod;
import com.example.accountingapp.presentation.IdsResponse;
import com.example.accountingapp.presentation.Today;
import com.example.accountingapp.presentation.controller.inventory.InventoryController.LedgerEntryResponse;
import com.example.accountingapp.presentation.controller.inventory.InventoryController.MovementKind;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;

/** 商品有高帳。受入・払出の明細を追記し、払出単価はライブラリの評価方法が決める。 */
@Component
@Validated
public class InventoryTools implements McpTools {

    private static final String ITEM_CODE = "商品のコード(品目ごとに利用者が決める)";
    private static final String DATE = "日付(yyyy-MM-dd)。省略すると今日";
    private static final String METHOD = "払出単価の評価方法。FIFO(先入先出法)か MOVING_AVERAGE(移動平均法)。省略すると FIFO";

    private final InventoryUseCase useCase;
    private final Today today;

    public InventoryTools(InventoryUseCase useCase, Today today) {
        this.useCase = useCase;
        this.today = today;
    }

    @Tool(name = "get_inventory_ledger", description = "商品有高帳。受入・払出・残高を、評価方法に従って計算して返す。")
    @PreAuthorize(VIEWER)
    public List<LedgerEntryResponse> getInventoryLedger(
            @ToolParam(description = ITEM_CODE) @NotBlank String itemCode,
            @ToolParam(description = METHOD, required = false) ValuationMethod method) {
        return useCase.ledger(itemCode, orFifo(method)).stream().map(LedgerEntryResponse::of).toList();
    }

    @Tool(name = "record_inventory_movement", description = """
            商品有高帳に受入(RECEIPT)か払出(ISSUE)を記録する(仕訳は記帳しない)。受入なら単価が必要。
            払出の単価は評価方法が決める(単価は受け付けない)。""")
    @PreAuthorize(BOOKKEEPER)
    public String recordInventoryMovement(
            @ToolParam(description = ITEM_CODE) @NotBlank String itemCode,
            @ToolParam(description = "RECEIPT(受入)か ISSUE(払出)") @NotNull MovementKind kind,
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "摘要") @NotBlank String description,
            @ToolParam(description = "数量") @Positive int quantity,
            @ToolParam(description = "受入の単価(円)。受入のときだけ必要", required = false) @Positive Long unitCost,
            @ToolParam(description = METHOD + "。払出のときだけ使う", required = false) ValuationMethod method) {
        LocalDate movementDate = today.orToday(date);
        switch (kind) {
            case RECEIPT -> {
                if (unitCost == null) {
                    throw new InvalidCommandException("unitCost is required for a receipt");
                }
                useCase.recordReceipt(itemCode, movementDate, description, quantity, unitCost);
            }
            case ISSUE -> useCase.recordIssue(itemCode, movementDate, description, quantity, orFifo(method));
        }
        return "recorded";
    }

    @Tool(name = "record_inventory_sale", description = """
            売上原価対立法の販売。払出を商品有高帳に記録し、売上と売上原価の仕訳を同時に記帳する。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse recordInventorySale(
            @ToolParam(description = ITEM_CODE) @NotBlank String itemCode,
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "摘要") @NotBlank String description,
            @ToolParam(description = "数量") @Positive int quantity,
            @ToolParam(description = METHOD, required = false) ValuationMethod method,
            @ToolParam(description = "売上代金の科目コード(売掛金・現金など)") @NotBlank String receivableAccountCode,
            @ToolParam(description = "売上高(円)") @Positive long salesAmount) {
        return IdsResponse.of(List.of(useCase.recordSale(itemCode, today.orToday(date), description, quantity,
                orFifo(method), receivableAccountCode, salesAmount)));
    }

    @Tool(name = "record_sales_return", description = """
            売上の返品(売上原価対立法)。商品有高帳に戻し、売上と売上原価を取り消す仕訳を記帳する。
            unitCost は払い出したときの単価(get_inventory_ledger の払出欄で確認できる)。""")
    @PreAuthorize(BOOKKEEPER)
    public IdsResponse recordSalesReturn(
            @ToolParam(description = ITEM_CODE) @NotBlank String itemCode,
            @ToolParam(description = DATE, required = false) LocalDate date,
            @ToolParam(description = "摘要") @NotBlank String description,
            @ToolParam(description = "数量") @Positive int quantity,
            @ToolParam(description = "払い出したときの単価(円)") @Positive long unitCost,
            @ToolParam(description = "売上代金の科目コード(売掛金・現金など)") @NotBlank String receivableAccountCode,
            @ToolParam(description = "返品する売上高(円)") @Positive long salesAmount) {
        return IdsResponse.of(List.of(useCase.recordSalesReturn(itemCode, today.orToday(date), description, quantity,
                unitCost, receivableAccountCode, salesAmount)));
    }

    private static ValuationMethod orFifo(ValuationMethod method) {
        return method == null ? ValuationMethod.FIFO : method;
    }
}
