package com.example.accountingapp.presentation.mcp.master;

import com.example.accountingapp.presentation.controller.master.MeController.MeResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 接続しているユーザーと、使えるロール。 */
@Component
public class MeTools implements McpTools {

    private final RoleHierarchy roleHierarchy;

    public MeTools(RoleHierarchy roleHierarchy) {
        this.roleHierarchy = roleHierarchy;
    }

    @Tool(name = "who_am_i", description = """
            接続しているユーザー名と、使えるロール(VIEWER: 照会、BOOKKEEPER: 記帳、APPROVER: 決算整理・締め・再振替)を返す。""")
    @PreAuthorize(VIEWER)
    public MeResponse whoAmI() {
        return MeResponse.of(SecurityContextHolder.getContext().getAuthentication(), roleHierarchy);
    }
}
