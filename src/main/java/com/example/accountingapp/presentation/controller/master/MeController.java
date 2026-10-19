package com.example.accountingapp.presentation.controller.master;

import com.example.accountingapp.application.master.CompanyUseCase;
import com.example.accountingapp.presentation.controller.master.CompanyController.CompanyResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * サインイン中のユーザーと、使えるロール(上位のロールが含む下位のロールも含む)、いま操作している会社。
 * 画面の出し分けに使う。
 */
@RestController
public class MeController {

    private final RoleHierarchy roleHierarchy;
    private final CompanyUseCase companyUseCase;

    public MeController(RoleHierarchy roleHierarchy, CompanyUseCase companyUseCase) {
        this.roleHierarchy = roleHierarchy;
        this.companyUseCase = companyUseCase;
    }

    public record MeResponse(String name, List<String> roles, CompanyResponse company) {

        public static MeResponse of(Authentication authentication, RoleHierarchy roleHierarchy,
                                    CompanyUseCase companyUseCase) {
            List<String> roles = roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority.startsWith("ROLE_"))
                    .map(authority -> authority.substring("ROLE_".length()))
                    .sorted()
                    .toList();
            return new MeResponse(authentication.getName(), roles, CompanyResponse.of(companyUseCase.current()));
        }
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        return MeResponse.of(authentication, roleHierarchy, companyUseCase);
    }
}
