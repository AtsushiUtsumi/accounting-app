package com.example.accountingapp.presentation.mcp.master;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCategory;
import com.example.accounting.model.account.AccountType;
import com.example.accountingapp.application.master.AccountManagementUseCase;
import com.example.accountingapp.presentation.controller.master.AccountController.AccountResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;

/** 科目マスタ。削除のツールはない(科目は無効化する)。 */
@Component
@Validated
public class AccountTools implements McpTools {

    private final AccountManagementUseCase useCase;

    public AccountTools(AccountManagementUseCase useCase) {
        this.useCase = useCase;
    }

    @Tool(name = "list_accounts", description = """
            勘定科目の一覧を返す(コード・名前・種類・区分・タグ・標準科目か・有効か・使用済みか)。
            仕訳などで科目コードを指定する前に、これで正しいコードを確認する。""")
    @PreAuthorize(VIEWER)
    public List<AccountResponse> listAccounts() {
        return useCase.list().stream().map(view -> AccountResponse.of(view.account(), view.used())).toList();
    }

    @Tool(name = "get_account", description = "勘定科目を1つ返す。")
    @PreAuthorize(VIEWER)
    public AccountResponse getAccount(@ToolParam(description = "科目コード") @NotBlank String code) {
        AccountManagementUseCase.AccountView view = useCase.get(code);
        return AccountResponse.of(view.account(), view.used());
    }

    @Tool(name = "add_account", description = "勘定科目を追加する。")
    @PreAuthorize(BOOKKEEPER)
    public AccountResponse addAccount(
            @ToolParam(description = "科目コード(16文字以内)") @NotBlank @Size(max = 16) String code,
            @ToolParam(description = "科目名(100文字以内)") @NotBlank @Size(max = 100) String name,
            @ToolParam(description = "科目の種類") @NotNull AccountType type,
            @ToolParam(description = "科目の区分(財務諸表での表示区分)") @NotNull AccountCategory category,
            @ToolParam(description = "タグ", required = false) Set<String> tags) {
        Account account = useCase.add(code, name, type, category, tags);
        return AccountResponse.of(account, false);
    }

    @Tool(name = "update_account", description = """
            勘定科目の名前・タグを変更する、または無効化する。省略した項目は変更しない。標準科目は変更できないことがある。""")
    @PreAuthorize(BOOKKEEPER)
    public AccountResponse updateAccount(
            @ToolParam(description = "科目コード") @NotBlank String code,
            @ToolParam(description = "新しい科目名", required = false) @Size(max = 100) String name,
            @ToolParam(description = "新しいタグ(全体を置き換える)", required = false) Set<String> tags,
            @ToolParam(description = "true なら無効化する", required = false) Boolean deactivate) {
        useCase.update(code, name, tags, deactivate);
        return getAccount(code);
    }
}
