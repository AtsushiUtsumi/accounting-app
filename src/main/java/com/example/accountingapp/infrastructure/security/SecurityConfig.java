package com.example.accountingapp.infrastructure.security;

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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * 認可(CLAUDE.md §9)。ロールは閲覧者(VIEWER)・記帳者(BOOKKEEPER)・承認者(APPROVER)の3つで、
 * 上位のロールは下位のロールの操作もできる。決算整理・締め・再振替・会社の初期化は承認者だけ。
 *
 * <p>MCP(AI エージェント向けの {@code /mcp})は1つの URL にすべての操作が来るため、URL では閲覧者以上であることだけを確かめ、
 * ツールごとのロールはツールのメソッドの {@code @PreAuthorize} で確かめる。
 *
 * <p>ユーザーは設定ファイル({@code accounting.security.users})から読み込む(最初は単一の会社の小さな運用を想定)。
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
                        // MCP はツールごとに @PreAuthorize で認可する
                        .requestMatchers("/mcp").hasRole(VIEWER)
                        .requestMatchers(HttpMethod.GET, "/**").hasRole(VIEWER)
                        .requestMatchers(HttpMethod.POST, "/company").hasRole(APPROVER)
                        .requestMatchers(HttpMethod.POST,
                                "/periods/*/adjustments/preview", "/periods/*/adjustments",
                                "/periods/*/close", "/periods/*/reversals").hasRole(APPROVER)
                        .anyRequest().hasRole(BOOKKEEPER))
                // 画面からの呼び出し(X-Requested-With 付き)では、ブラウザの認証ダイアログを出さないよう
                // WWW-Authenticate を付けずに 401 を返す。画面は自分のログインフォームで認証情報を送る
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
    UserDetailsService userDetailsService(UserProperties properties) {
        List<UserDetails> users = properties.users().stream()
                .map(user -> User.withUsername(user.name()).password(user.password()).roles(user.role()).build())
                .toList();
        if (users.isEmpty()) {
            LOG.warn("ユーザーが1人も設定されていないため、誰もログインできません(accounting.security.users)");
        }
        return new InMemoryUserDetailsManager(users);
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
