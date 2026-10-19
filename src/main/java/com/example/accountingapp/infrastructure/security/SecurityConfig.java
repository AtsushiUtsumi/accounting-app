package com.example.accountingapp.infrastructure.security;

import com.example.accountingapp.application.port.ConfiguredUsers;
import com.example.accountingapp.application.port.PasswordHasher;
import com.example.accountingapp.application.port.UserAccountRepository;
import com.example.accountingapp.application.user.UserAccount;
import com.example.accountingapp.application.user.UserAccount.Role;
import com.example.accountingapp.application.user.UserAccount.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * 認可(CLAUDE.md §9)。ロールは閲覧者(VIEWER)・記帳者(BOOKKEEPER)・承認者(APPROVER)の3つで、
 * 上位のロールは下位のロールの操作もできる。決算整理・締め・再振替・会社の作成と初期化・連結の入力は承認者だけ。
 * 会社ごとの権限は分けない(どのユーザーも、すべての会社を同じロールで操作できる)。
 *
 * <p>MCP(AI エージェント向けの {@code /mcp})は1つの URL にすべての操作が来るため、URL では閲覧者以上であることだけを確かめ、
 * ツールごとのロールはツールのメソッドの {@code @PreAuthorize} で確かめる。
 *
 * <p>ユーザーは2種類: 設定ファイル({@code accounting.security.users})のユーザーと、サインアップして DB に保存したユーザー。
 * サインアップは誰でもできるが、ロールがないうちは自分の情報({@code /me})しか扱えない。承認者がロールを付ける。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityConfig.UserProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    public static final String VIEWER = "VIEWER";
    public static final String BOOKKEEPER = "BOOKKEEPER";
    public static final String APPROVER = "APPROVER";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        // 画面(静的ファイル)は誰でも取得できる。データはすべて API 側で認可する
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app/**", "/favicon.ico").permitAll()
                        // サインアップは認証なしでできる(作られるユーザーにはロールがない)
                        .requestMatchers(HttpMethod.POST, "/signup").permitAll()
                        // 自分の情報の照会と、自分のアカウントの削除は、ロールがなくてもできる
                        .requestMatchers("/me").authenticated()
                        // ユーザーの管理(ロールの付与・削除)は承認者だけ
                        .requestMatchers("/users", "/users/**").hasRole(APPROVER)
                        // MCP はツールごとに @PreAuthorize で認可する
                        .requestMatchers("/mcp").hasRole(VIEWER)
                        .requestMatchers(HttpMethod.GET, "/**").hasRole(VIEWER)
                        .requestMatchers(HttpMethod.POST, "/company", "/companies").hasRole(APPROVER)
                        .requestMatchers(HttpMethod.PATCH, "/companies/*").hasRole(APPROVER)
                        // 連結(子会社の登録と連結の資料)。照会は上の GET の規則で閲覧者以上
                        .requestMatchers("/subsidiaries", "/subsidiaries/**", "/periods/*/consolidation/**")
                        .hasRole(APPROVER)
                        .requestMatchers(HttpMethod.POST,
                                "/periods/*/adjustments/preview", "/periods/*/adjustments",
                                "/periods/*/close", "/periods/*/reversals").hasRole(APPROVER)
                        .anyRequest().hasRole(BOOKKEEPER))
                // 画面からの呼び出し(X-Requested-With 付き)では、ブラウザの認証ダイアログを出さないよう
                // WWW-Authenticate を付けずに 401 を返す。画面は自分のサインインフォームで認証情報を送る
                .httpBasic(basic -> basic.authenticationEntryPoint(new ScreenAwareBasicEntryPoint()))
                // REST API はセッションを使わず、リクエストごとに認証する(CSRF 対策のトークンも不要)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(APPROVER).implies(BOOKKEEPER)
                .role(BOOKKEEPER).implies(VIEWER)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    PasswordHasher passwordHasher(PasswordEncoder passwordEncoder) {
        return passwordEncoder::encode;
    }

    @Bean
    ConfiguredUsers configuredUsers(UserProperties properties) {
        List<UserAccount> users = properties.users().stream()
                .map(user -> new UserAccount(user.name(), user.password(), Role.valueOf(user.role()), Source.CONFIGURED))
                .toList();
        if (users.stream().noneMatch(user -> user.role() == Role.APPROVER)) {
            LOG.warn("設定ファイルに承認者がいません(accounting.security.users)。サインアップしたユーザーにロールを付けられる人がいなくなります");
        }
        return () -> users;
    }

    /** 設定ファイルのユーザーを先に探し、なければサインアップしたユーザーを探す。ロールがなければ権限なしで認証する。 */
    @Bean
    UserDetailsService userDetailsService(ConfiguredUsers configuredUsers, UserAccountRepository userAccountRepository) {
        return name -> configuredUsers.all().stream()
                .filter(user -> user.name().equals(name))
                .findFirst()
                .or(() -> userAccountRepository.findByName(name))
                .map(SecurityConfig::toUserDetails)
                .orElseThrow(() -> new UsernameNotFoundException(name));
    }

    private static UserDetails toUserDetails(UserAccount user) {
        return User.withUsername(user.name())
                .password(user.passwordHash())
                .authorities(user.role() == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())))
                .build();
    }

    /**
     * ユーザーの設定。パスワードは {@code {bcrypt}...} のように、エンコード方式の接頭辞を付けて書く。
     *
     * @param users ユーザーの一覧
     */
    @ConfigurationProperties("accounting.security")
    public record UserProperties(List<UserProperty> users) {

        public UserProperties {
            users = users == null ? List.of() : List.copyOf(users);
        }

        public record UserProperty(String name, String password, String role) {
        }
    }
}
