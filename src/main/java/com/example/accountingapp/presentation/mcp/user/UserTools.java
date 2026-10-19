package com.example.accountingapp.presentation.mcp.user;

import com.example.accountingapp.application.user.UserAccount.Role;
import com.example.accountingapp.application.user.UserAccountUseCase;
import com.example.accountingapp.presentation.controller.user.UserController.UserResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** ユーザーの管理(承認者のみ)。サインアップと自分のアカウントの削除は、画面から人が行う。 */
@Component
@Validated
public class UserTools implements McpTools {

    private final UserAccountUseCase useCase;

    public UserTools(UserAccountUseCase useCase) {
        this.useCase = useCase;
    }

    @Tool(name = "list_users", description = """
            ユーザーの一覧(名前・ロール・設定ファイルのユーザーかサインアップしたユーザーか)。
            role が null のユーザーはサインアップしたばかりで、承認者がロールを付けるまで帳簿を見られない(承認者のみ)。""")
    @PreAuthorize(APPROVER)
    public List<UserResponse> listUsers() {
        return useCase.list().stream().map(UserResponse::of).toList();
    }

    @Tool(name = "change_user_role", description = """
            サインアップしたユーザーのロールを付ける・変える・外す(承認者のみ)。role を省略するとロールを外す。
            設定ファイルのユーザーは変更できない。最後の承認者のロールは変えられない。""")
    @PreAuthorize(APPROVER)
    public UserResponse changeUserRole(
            @ToolParam(description = "ユーザー名") @NotBlank String name,
            @ToolParam(description = "VIEWER(閲覧)・BOOKKEEPER(記帳)・APPROVER(承認)。省略するとロールを外す", required = false)
            Role role) {
        return UserResponse.of(useCase.changeRole(name, role));
    }

    @Tool(name = "delete_user", description = """
            サインアップしたユーザーを削除する(承認者のみ。元に戻せない)。設定ファイルのユーザーと最後の承認者は削除できない。
            記帳済みの仕訳の記帳者の記録(ユーザー名)は残る。""")
    @PreAuthorize(APPROVER)
    public void deleteUser(@ToolParam(description = "ユーザー名") @NotBlank String name) {
        useCase.delete(name);
    }
}
