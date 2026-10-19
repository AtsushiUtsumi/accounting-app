package com.example.accountingapp.presentation.mcp;

import com.example.accounting.model.consolidation.CompanyId;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.CompanyContext;
import com.example.accountingapp.application.port.CompanyRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * すべてのツールに、操作する会社を選ぶ省略可能な引数 {@value #PARAMETER} を足す。指定すればその会社として
 * ツールを実行し、省略すれば REST と同じく、リクエストヘッダー {@code X-Company-Id} の会社か既定の会社になる。
 * MCP はステートレスなので、会社を切り替える状態は持たず、呼び出しごとに指定する。
 */
class CompanyScopedToolCallback implements ToolCallback {

    static final String PARAMETER = "companyId";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolCallback delegate;
    private final CompanyContext companyContext;
    private final CompanyRepository companyRepository;
    private final ToolDefinition definition;

    CompanyScopedToolCallback(ToolCallback delegate, CompanyContext companyContext, CompanyRepository companyRepository) {
        this.delegate = delegate;
        this.companyContext = companyContext;
        this.companyRepository = companyRepository;
        this.definition = withCompanyParameter(delegate.getToolDefinition());
    }

    private static ToolDefinition withCompanyParameter(ToolDefinition original) {
        ObjectNode schema = (ObjectNode) JSON.readTree(original.inputSchema());
        JsonNode existing = schema.get("properties");
        ObjectNode properties = existing instanceof ObjectNode node ? node : schema.putObject("properties");
        if (properties.has(PARAMETER)) {
            throw new IllegalStateException("tool " + original.name() + " already has a parameter named " + PARAMETER);
        }
        properties.putObject(PARAMETER)
                .put("type", "string")
                .put("description", "操作する会社の ID(list_companies で確認する)。省略すると既定の会社");
        return ToolDefinition.builder()
                .name(original.name())
                .description(original.description())
                .inputSchema(JSON.writeValueAsString(schema))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        JsonNode input = toolInput == null || toolInput.isBlank() ? null : JSON.readTree(toolInput);
        if (!(input instanceof ObjectNode arguments) || !arguments.hasNonNull(PARAMETER)) {
            return delegate.call(toolInput, toolContext);
        }
        CompanyId company = parse(arguments.remove(PARAMETER).asString());
        companyRepository.findById(company)
                .orElseThrow(() -> new ResourceNotFoundException("company not found: " + company.value()));
        String rest = JSON.writeValueAsString(arguments);
        return companyContext.callAs(company, () -> delegate.call(rest, toolContext));
    }

    private static CompanyId parse(String value) {
        try {
            return new CompanyId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new InvalidCommandException(PARAMETER + " must be a company ID (UUID): " + value);
        }
    }
}
