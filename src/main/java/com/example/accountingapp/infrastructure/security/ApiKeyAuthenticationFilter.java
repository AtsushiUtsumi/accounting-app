package com.example.accountingapp.infrastructure.security;

import com.example.accountingapp.application.user.ApiKeyUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * MCP({@code /mcp})だけで、{@code Authorization: Bearer <API キー>} による認証を受け付ける。
 * キーの持ち主のその時点のロールで認証する(ロールを外されたユーザーのキーでは、どのツールも使えない)。
 * キーが正しくなければ 401 を返す。REST API と画面は、これまでどおり HTTP Basic だけ。
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyUseCase apiKeys;
    private final UserDetailsService userDetailsService;

    ApiKeyAuthenticationFilter(ApiKeyUseCase apiKeys, UserDetailsService userDetailsService) {
        this.apiKeys = apiKeys;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"/mcp".equals(path) || header == null || !header.startsWith(BEARER);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER.length()).strip();
        Optional<UserDetails> user = apiKeys.authenticate(token).flatMap(this::loadUser);
        if (user.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user.get().getUsername(), null, user.get().getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** キーの持ち主(削除されていれば空)。 */
    private Optional<UserDetails> loadUser(String name) {
        try {
            return Optional.of(userDetailsService.loadUserByUsername(name));
        } catch (UsernameNotFoundException e) {
            return Optional.empty();
        }
    }
}
