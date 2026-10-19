package com.example.accountingapp.presentation.controller.user;

import com.example.accountingapp.application.user.UserAccount;
import com.example.accountingapp.application.user.UserAccount.Role;
import com.example.accountingapp.application.user.UserAccount.Source;
import com.example.accountingapp.application.user.UserAccountUseCase;
import com.example.accountingapp.presentation.controller.NotExposedAsMcpTool;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ユーザー(サインアップ、自分のアカウントの削除、承認者によるロールの付与と削除)。
 * パスワードはレスポンスに含めない。
 */
@RestController
public class UserController {

    private final UserAccountUseCase useCase;

    public UserController(UserAccountUseCase useCase) {
        this.useCase = useCase;
    }

    public record SignUpRequest(@NotBlank String name, @NotBlank String password) {
    }

    /** @param role ロール。null ならまだ付いていない(帳簿を見られない) */
    public record UserResponse(String name, Role role, Source source) {

        public static UserResponse of(UserAccount user) {
            return new UserResponse(user.name(), user.role(), user.source());
        }
    }

    /** 承認者が付けるまでロールのないユーザー。 */
    public record ChangeRoleRequest(Role role) {
    }

    /** だれでもサインアップできる。作られるユーザーにはロールがない。 */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @NotExposedAsMcpTool("MCP は認証済みの接続なので、サインアップは画面から人が行う")
    public UserResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return UserResponse.of(useCase.signUp(request.name(), request.password()));
    }

    /** 自分のアカウントを削除する。設定ファイルのユーザーと、最後の承認者は削除できない。 */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @NotExposedAsMcpTool("自分のアカウントの削除は取り返しがつかないので、AI エージェントには任せず画面から人が行う")
    public void deleteMyAccount(Authentication authentication) {
        useCase.delete(authentication.getName());
    }

    @GetMapping("/users")
    public List<UserResponse> users() {
        return useCase.list().stream().map(UserResponse::of).toList();
    }

    /** ロールを付ける・変える・外す(role を null にする)。 */
    @PatchMapping("/users/{name}")
    public UserResponse changeRole(@PathVariable String name, @Valid @RequestBody ChangeRoleRequest request) {
        return UserResponse.of(useCase.changeRole(name, request.role()));
    }

    @DeleteMapping("/users/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable String name) {
        useCase.delete(name);
    }
}
