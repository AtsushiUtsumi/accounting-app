package com.example.accountingapp.infrastructure.security;

import com.example.accountingapp.application.port.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 認証済みのユーザー名。認証の外(起動時の処理など)から呼ばれたときは "system"。 */
@Component
public class SecurityContextCurrentUser implements CurrentUser {

    @Override
    public String name() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() ? "system" : authentication.getName();
    }
}
