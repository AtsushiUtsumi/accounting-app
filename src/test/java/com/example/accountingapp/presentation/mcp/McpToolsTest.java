package com.example.accountingapp.presentation.mcp;

import com.example.accounting.exception.AccountingException;
import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.account.Side;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriodId;
import com.example.accountingapp.application.JournalLineCommand;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.closing.ClosePeriodUseCase;
import com.example.accountingapp.application.closing.ReverseAccrualsUseCase;
import com.example.accountingapp.application.closing.RunAdjustingEntriesUseCase;
import com.example.accountingapp.application.journal.CorrectJournalUseCase;
import com.example.accountingapp.application.journal.PostJournalCommand;
import com.example.accountingapp.application.journal.PostJournalUseCase;
import com.example.accountingapp.application.master.CompanyUseCase;
import com.example.accountingapp.application.port.CompanyContext;
import com.example.accountingapp.application.port.CompanyRepository;
import com.example.accountingapp.application.port.CompanyRepository.Company;
import com.example.accountingapp.application.port.UserAccountRepository;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.application.user.ApiKeyUseCase;
import com.example.accountingapp.infrastructure.company.RequestCompanyContext;
import com.example.accountingapp.infrastructure.security.SecurityConfig;
import com.example.accountingapp.presentation.Today;
import com.example.accountingapp.presentation.mcp.closing.ClosingTools;
import com.example.accountingapp.presentation.mcp.journal.JournalTools;
import com.example.accountingapp.presentation.mcp.master.MeTools;
import com.example.accountingapp.presentation.mcp.statement.StatementTools;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * MCP の {@code /mcp} に JSON-RPC を送り、ツールの引数の変換、例外のエラー結果への変換、ロールによる認可を確かめる
 * (REST の {@code ControllerTest} に対応する)。
 */
@WebMvcTest(useDefaultFilters = false)
@ImportAutoConfiguration({McpServerJsonMapperAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
        StatelessToolCallbackConverterAutoConfiguration.class, McpServerStatelessWebMvcAutoConfiguration.class})
@Import({SecurityConfig.class, Today.class, McpToolsTest.FixedClock.class, McpToolConfig.class, RequestCompanyContext.class,
        JournalTools.class, ClosingTools.class, StatementTools.class, MeTools.class})
class McpToolsTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-30T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    PostJournalUseCase postJournalUseCase;
    @MockitoBean
    CorrectJournalUseCase correctJournalUseCase;
    @MockitoBean
    StatementQueryService statementQueryService;
    @MockitoBean
    RunAdjustingEntriesUseCase runAdjustingEntriesUseCase;
    @MockitoBean
    ClosePeriodUseCase closePeriodUseCase;
    @MockitoBean
    ReverseAccrualsUseCase reverseAccrualsUseCase;
    /** 認証のユーザーの検索(SecurityConfig)が使う。テストは @WithMockUser で認証する。 */
    @MockitoBean
    UserAccountRepository userAccountRepository;
    /** MCP の API キー認証(SecurityConfig)が使う。 */
    @MockitoBean
    ApiKeyUseCase apiKeyUseCase;
    @MockitoBean
    CompanyUseCase companyUseCase;
    @MockitoBean
    CompanyRepository companyRepository;

    @Autowired
    CompanyContext companyContext;

    private static final String SALE = """
            {"description": "商品の掛け売上", "lines": [
              {"accountCode": "102", "side": "DEBIT", "amount": 280000},
              {"accountCode": "401", "side": "CREDIT", "amount": 280000}]}
            """;

    private MvcTestResult rpc(String method, String params) {
        return mvc.post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("""
                        {"jsonrpc": "2.0", "id": 1, "method": "%s", "params": %s}
                        """.formatted(method, params))
                .exchange();
    }

    /** ツールを呼び、JSON-RPC の result(CallToolResult)を返す。 */
    private JsonNode callTool(String name, String arguments) {
        MvcTestResult result = rpc("tools/call", """
                {"name": "%s", "arguments": %s}
                """.formatted(name, arguments));
        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.has("error")).as("JSON-RPC error: %s", body).isFalse();
        return body.get("result");
    }

    /** ツールの引数に、操作する会社(companyId)を足す。 */
    private static String withCompany(String arguments, UUID companyId) {
        return "{\"companyId\": \"" + companyId + "\", " + arguments.strip().substring(1);
    }

    private static String text(JsonNode callToolResult) {
        return callToolResult.get("content").get(0).get("text").asString();
    }

    private static boolean isError(JsonNode callToolResult) {
        return callToolResult.path("isError").asBoolean(false);
    }

    @Test
    @WithMockUser(roles = SecurityConfig.VIEWER)
    void toolsAreListedWithTheirInputSchema() {
        MvcTestResult result = rpc("tools/list", "{}");
        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode tools = JSON.readTree(result.getResponse().getContentAsByteArray()).get("result").get("tools");

        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.get("name").asString()));
        assertThat(names).contains("post_journal", "correct_journal", "close_period", "get_trial_balance", "who_am_i");

        JsonNode postJournal = tools.valueStream()
                .filter(tool -> tool.get("name").asString().equals("post_journal"))
                .findFirst().orElseThrow();
        List<String> required = new ArrayList<>();
        postJournal.get("inputSchema").get("required").forEach(node -> required.add(node.asString()));
        assertThat(required).containsExactlyInAnyOrder("description", "lines");
        // 操作する会社を選ぶ引数は、すべてのツールに省略可能な引数として足される
        assertThat(tools.valueStream()).allSatisfy(tool ->
                assertThat(tool.get("inputSchema").get("properties").has("companyId")).as(tool.get("name").asString()).isTrue());
        assertThat(required).doesNotContain("companyId");
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void companyId_runsTheToolAsThatCompany() {
        CompanyId subsidiary = CompanyId.newId();
        given(companyRepository.findById(subsidiary)).willReturn(Optional.of(new Company(subsidiary, "子会社")));
        AtomicReference<CompanyId> companySeenByUseCase = new AtomicReference<>();
        given(postJournalUseCase.post(any())).willAnswer(invocation -> {
            companySeenByUseCase.set(companyContext.current());
            return JournalId.newId();
        });

        JsonNode result = callTool("post_journal", withCompany(SALE, subsidiary.value()));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(companySeenByUseCase).hasValue(subsidiary);
        // 呼び出しが終われば元の会社(既定の会社)に戻る
        assertThat(companyContext.current()).isEqualTo(CompanyRepository.DEFAULT_COMPANY);
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void unknownCompanyId_isNotFound() {
        JsonNode result = callTool("post_journal", withCompany(SALE, UUID.randomUUID()));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).startsWith("[NOT_FOUND] company not found");
        verify(postJournalUseCase, never()).post(any());
    }

    @Test
    @WithMockUser(username = "suzuki", roles = SecurityConfig.BOOKKEEPER)
    void postJournal_convertsTheArgumentsAndDefaultsTheDateToToday() {
        JournalId id = JournalId.newId();
        AtomicReference<String> userSeenByUseCase = new AtomicReference<>();
        given(postJournalUseCase.post(any())).willAnswer(invocation -> {
            // 監査列(created_by)のために、ツールがリクエストと同じスレッドで認証済みのまま動くこと
            userSeenByUseCase.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return id;
        });

        JsonNode result = callTool("post_journal", SALE);

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(JSON.readTree(text(result)).get("journalId").asString()).isEqualTo(id.value().toString());
        ArgumentCaptor<PostJournalCommand> captor = ArgumentCaptor.forClass(PostJournalCommand.class);
        verify(postJournalUseCase).post(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PostJournalCommand(
                LocalDate.of(2026, 6, 30), "商品の掛け売上", JournalType.NORMAL, List.of(
                new JournalLineCommand("102", Side.DEBIT, 280_000),
                new JournalLineCommand("401", Side.CREDIT, 280_000))));
        assertThat(userSeenByUseCase).hasValue("suzuki");
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void accountingException_isAnErrorResultWithTheCode() {
        given(postJournalUseCase.post(any())).willThrow(new AccountingException("debit total (1) must equal credit total (2)"));

        JsonNode result = callTool("post_journal", SALE);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("[ACCOUNTING_RULE_VIOLATION] debit total (1) must equal credit total (2)");
    }

    @Test
    @WithMockUser(roles = SecurityConfig.VIEWER)
    void notFound_isAnErrorResultWithTheCode() {
        UUID journalId = UUID.randomUUID();
        given(statementQueryService.journal(journalId)).willThrow(new ResourceNotFoundException("journal not found"));

        JsonNode result = callTool("get_journal", """
                {"journalId": "%s"}
                """.formatted(journalId));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("[NOT_FOUND] journal not found");
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void malformedArguments_areRejectedBeforeTheUseCase() {
        JsonNode result = callTool("post_journal", """
                {"description": "", "lines": [{"accountCode": "101", "side": "DEBIT", "amount": 0}]}
                """);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).startsWith("[INVALID_REQUEST] ")
                .contains("description", "lines[0].amount");
        verify(postJournalUseCase, never()).post(any());
    }

    @Test
    void unauthenticatedRequest_is401() {
        assertThat(rpc("tools/list", "{}")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @WithMockUser(roles = SecurityConfig.VIEWER)
    void viewerCannotPost() {
        JsonNode result = callTool("post_journal", SALE);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).startsWith("[FORBIDDEN]");
        verify(postJournalUseCase, never()).post(any());
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void onlyApproverCanClose() {
        JsonNode result = callTool("close_period", """
                {"periodId": "%s"}
                """.formatted(UUID.randomUUID()));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).startsWith("[FORBIDDEN]");
        verify(closePeriodUseCase, never()).close(any());
    }

    @Test
    @WithMockUser(roles = SecurityConfig.APPROVER)
    void approverCanCloseAndAlsoDoBookkeeperOperations() {
        UUID periodId = UUID.randomUUID();
        UUID nextId = UUID.randomUUID();
        given(closePeriodUseCase.close(periodId)).willReturn(
                new ClosePeriodUseCase.Result(new AccountingPeriodId(nextId), Money.ofYen(-20_000)));

        JsonNode closed = callTool("close_period", """
                {"periodId": "%s"}
                """.formatted(periodId));
        assertThat(isError(closed)).as(text(closed)).isFalse();
        JsonNode body = JSON.readTree(text(closed));
        assertThat(body.get("nextPeriodId").asString()).isEqualTo(nextId.toString());
        assertThat(body.get("netIncome").asLong()).isEqualTo(-20_000);

        given(postJournalUseCase.post(any())).willReturn(JournalId.newId());
        assertThat(isError(callTool("post_journal", SALE))).isFalse();
    }

    @Test
    @WithMockUser(username = "tanaka", roles = SecurityConfig.BOOKKEEPER)
    void whoAmI_returnsTheReachableRoles() {
        given(companyUseCase.current()).willReturn(new Company(CompanyRepository.DEFAULT_COMPANY, "既定の会社"));
        JsonNode body = JSON.readTree(text(callTool("who_am_i", "{}")));

        assertThat(body.get("name").asString()).isEqualTo("tanaka");
        List<String> roles = new ArrayList<>();
        body.get("roles").forEach(role -> roles.add(role.asString()));
        assertThat(roles).containsExactly("BOOKKEEPER", "VIEWER");
        assertThat(body.get("company").get("name").asString()).isEqualTo("既定の会社");
    }
}
