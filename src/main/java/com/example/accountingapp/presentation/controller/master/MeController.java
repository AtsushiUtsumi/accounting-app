package com.example.accountingapp.presentation.controller.master;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ログイン中のユーザーと、使えるロール(上位のロールが含む下位のロールも含む)。画面の出し分けに使う。 */
@RestController
public class MeController {

    private final RoleHierarchy roleHierarchy;

    public MeController(RoleHierarchy roleHierarchy) {
        this.roleHierarchy = roleHierarchy;
    }

    public record MeResponse(String name, List<String> roles) {

        public static MeResponse of(Authentication authentication, RoleHierarchy roleHierarchy) {
            List<String> roles = roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority.startsWith("ROLE_"))
                    .map(authority -> authority.substring("ROLE_".length()))
                    .sorted()
                    .toList();
            return new MeResponse(authentication.getName(), roles);
        }
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        return MeResponse.of(authentication, roleHierarchy);
    }
}
