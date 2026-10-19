package com.example.accountingapp.presentation.mcp.master;

import com.example.accountingapp.application.master.CompanyUseCase;
import com.example.accountingapp.presentation.controller.master.MeController.MeResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 接続しているユーザーと、使えるロール、操作している会社。 */
@Component
public class MeTools implements McpTools {

    private final RoleHierarchy roleHierarchy;
    private final CompanyUseCase companyUseCase;

    public MeTools(RoleHierarchy roleHierarchy, CompanyUseCase companyUseCase) {
        this.roleHierarchy = roleHierarchy;
        this.companyUseCase = companyUseCase;
    }

    @Tool(name = "who_am_i", description = """
            接続しているユーザー名と、使えるロール(VIEWER: 照会、BOOKKEEPER: 記帳、APPROVER: 決算整理・締め・再振替・会社・連結)、
            操作している会社を返す。""")
    @PreAuthorize(VIEWER)
    public MeResponse whoAmI() {
        return MeResponse.of(SecurityContextHolder.getContext().getAuthentication(), roleHierarchy, companyUseCase);
    }
}
