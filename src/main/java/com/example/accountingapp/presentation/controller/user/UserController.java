package com.example.accountingapp.presentation.controller.user;

import com.example.accountingapp.application.user.ApiKey;
import com.example.accountingapp.application.user.ApiKeyUseCase;
import com.example.accountingapp.application.user.UserAccount;
import com.example.accountingapp.application.user.UserAccount.Role;
import com.example.accountingapp.application.user.UserAccount.Source;
import com.example.accountingapp.application.user.UserAccountUseCase;
import com.example.accountingapp.presentation.controller.NotExposedAsMcpTool;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ユーザー(サインアップ、自分のアカウントの削除、MCP 用の API キー、承認者によるロールの付与と削除)。
 * パスワードと API キーのハッシュはレスポンスに含めない。
 */
@RestController
public class UserController {

    private static final String BROWSER_ONLY_KEYS =
            "API キーは AI エージェントに渡す認証情報なので、エージェント自身には発行させず画面から人が行う";

    private final UserAccountUseCase useCase;
    private final ApiKeyUseCase apiKeyUseCase;

    public UserController(UserAccountUseCase useCase, ApiKeyUseCase apiKeyUseCase) {
        this.useCase = useCase;
        this.apiKeyUseCase = apiKeyUseCase;
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

    // ---- MCP 用の API キー(自分のもの) ----

    public record IssueApiKeyRequest(@NotBlank @Size(max = 100) String name) {
    }

    /** API キー。キーそのものは返さない({@link IssuedApiKeyResponse} で発行したときだけ返す)。 */
    public record ApiKeyResponse(UUID id, String name, String prefix, Instant createdAt, Instant lastUsedAt) {

        public static ApiKeyResponse of(ApiKey key) {
            return new ApiKeyResponse(key.id(), key.name(), key.prefix(), key.createdAt(), key.lastUsedAt());
        }
    }

    /** @param token キーそのもの。このレスポンスでしか取り出せない */
    public record IssuedApiKeyResponse(ApiKeyResponse key, String token) {
    }

    @GetMapping("/me/api-keys")
    @NotExposedAsMcpTool(BROWSER_ONLY_KEYS)
    public List<ApiKeyResponse> myApiKeys(Authentication authentication) {
        return apiKeyUseCase.list(authentication.getName()).stream().map(ApiKeyResponse::of).toList();
    }

    @PostMapping("/me/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @NotExposedAsMcpTool(BROWSER_ONLY_KEYS)
    public IssuedApiKeyResponse issueApiKey(Authentication authentication,
                                            @Valid @RequestBody IssueApiKeyRequest request) {
        ApiKeyUseCase.Issued issued = apiKeyUseCase.issue(authentication.getName(), request.name());
        return new IssuedApiKeyResponse(ApiKeyResponse.of(issued.key()), issued.token());
    }

    /** API キーを失効させる(削除する)。 */
    @DeleteMapping("/me/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @NotExposedAsMcpTool(BROWSER_ONLY_KEYS)
    public void revokeApiKey(Authentication authentication, @PathVariable UUID id) {
        apiKeyUseCase.revoke(authentication.getName(), id);
    }

    // ---- ユーザーの管理(承認者) ----

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
