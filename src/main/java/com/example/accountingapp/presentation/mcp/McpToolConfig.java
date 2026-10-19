package com.example.accountingapp.presentation.mcp;

import com.example.accountingapp.application.port.CompanyContext;
import com.example.accountingapp.application.port.CompanyRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * {@link McpTools} を実装した Bean の {@code @Tool} メソッドを、会社の選択({@link CompanyScopedToolCallback})と
 * 例外の変換を挟んで MCP サーバーに登録する。
 */
@Configuration(proxyBeanMethods = false)
class McpToolConfig {

    @Bean
    ToolCallbackProvider accountingToolCallbacks(List<McpTools> tools, CompanyContext companyContext,
                                                 CompanyRepository companyRepository) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build()
                .getToolCallbacks();
        return ToolCallbackProvider.from(Arrays.stream(callbacks)
                .map(callback -> new CompanyScopedToolCallback(callback, companyContext, companyRepository))
                .map(McpErrorTranslatingToolCallback::new)
                .toList());
    }
}
