package com.example.accountingapp.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

import java.io.IOException;

/**
 * 認証が必要なときの応答。curl などには通常どおり HTTP Basic のチャレンジ(WWW-Authenticate)を返す。
 * 画面からの呼び出し({@code X-Requested-With: XMLHttpRequest})には WWW-Authenticate を付けずに 401 だけを返し、
 * ブラウザの認証ダイアログが出ないようにする(画面は自分のサインインフォームを出す)。
 */
class ScreenAwareBasicEntryPoint implements AuthenticationEntryPoint {

    private final BasicAuthenticationEntryPoint basic = new BasicAuthenticationEntryPoint();

    ScreenAwareBasicEntryPoint() {
        basic.setRealmName("accounting-app");
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        basic.commence(request, response, authException);
    }
}
