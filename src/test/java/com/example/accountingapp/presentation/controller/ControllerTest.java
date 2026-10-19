package com.example.accountingapp.presentation.controller;

import com.example.accounting.exception.AccountingException;
import com.example.accounting.model.account.Side;
import com.example.accounting.model.account.StandardAccounts;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.statement.TrialBalance;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.JournalLineCommand;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.closing.ClosePeriodUseCase;
import com.example.accountingapp.application.closing.ReverseAccrualsUseCase;
import com.example.accountingapp.application.closing.RunAdjustingEntriesUseCase;
import com.example.accountingapp.application.journal.CorrectJournalUseCase;
import com.example.accountingapp.application.journal.PostJournalCommand;
import com.example.accountingapp.application.journal.PostJournalUseCase;
import com.example.accountingapp.application.port.UserAccountRepository;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.application.user.ApiKeyUseCase;
import com.example.accountingapp.infrastructure.security.SecurityConfig;
import com.example.accountingapp.presentation.Today;
import com.example.accountingapp.presentation.controller.closing.ClosingController;
import com.example.accountingapp.presentation.controller.journal.JournalController;
import com.example.accountingapp.presentation.controller.statement.StatementController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/** DTO の変換、例外の HTTP 変換(CLAUDE.md §8)、ロールによる認可(§9)。 */
@WebMvcTest(controllers = {JournalController.class, ClosingController.class, StatementController.class})
@Import({SecurityConfig.class, Today.class, ControllerTest.FixedClock.class})
class ControllerTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-30T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
        }
    }

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

    private static final String SALE = """
            {"description": "商品の掛け売上", "lines": [
              {"accountCode": "102", "side": "DEBIT", "amount": 280000},
              {"accountCode": "401", "side": "CREDIT", "amount": 280000}]}
            """;

    private MockMvcTester.MockMvcRequestBuilder postJournal(String json) {
        return mvc.post().uri("/journals").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void postJournal_convertsTheRequestAndDefaultsTheDateToToday() {
        JournalId id = JournalId.newId();
        given(postJournalUseCase.post(any())).willReturn(id);

        assertThat(postJournal(SALE)).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.journalId").isEqualTo(id.value().toString());

        ArgumentCaptor<PostJournalCommand> captor = ArgumentCaptor.forClass(PostJournalCommand.class);
        verify(postJournalUseCase).post(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PostJournalCommand(
                LocalDate.of(2026, 6, 30), "商品の掛け売上", JournalType.NORMAL, List.of(
                new JournalLineCommand("102", Side.DEBIT, 280_000),
                new JournalLineCommand("401", Side.CREDIT, 280_000))));
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void accountingException_is422WithTheMessageInDetail() {
        given(postJournalUseCase.post(any())).willThrow(new AccountingException("debit total (1) must equal credit total (2)"));

        assertThat(postJournal(SALE)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .hasPathSatisfying("$.detail", detail -> detail.assertThat().isEqualTo("debit total (1) must equal credit total (2)"))
                .hasPathSatisfying("$.code", code -> code.assertThat().isEqualTo("ACCOUNTING_RULE_VIOLATION"));
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void notFound_is404() {
        given(postJournalUseCase.post(any())).willThrow(new ResourceNotFoundException("no accounting period contains 2030-01-01"));

        assertThat(postJournal(SALE)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void concurrencyConflictAndOperationNotAllowed_are409() {
        given(postJournalUseCase.post(any())).willThrow(new ConcurrencyConflictException("period was closed"));
        assertThat(postJournal(SALE)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("CONCURRENT_UPDATE");

        willThrow(new OperationNotAllowedException("already reversed")).given(postJournalUseCase).post(any());
        assertThat(postJournal(SALE)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("OPERATION_NOT_ALLOWED");
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void malformedRequest_is400() {
        assertThat(postJournal("""
                {"description": "", "lines": [{"accountCode": "101", "side": "DEBIT", "amount": 0}]}
                """)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unauthenticatedRequest_is401() {
        assertThat(postJournal(SALE)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** 画面(静的ファイル)は認証なしで取得できる。 */
    @Test
    void screenIsServedWithoutAuthentication() {
        assertThat(mvc.get().uri("/index.html")).hasStatus(HttpStatus.OK);
        assertThat(mvc.get().uri("/app/main.js")).hasStatus(HttpStatus.OK);
    }

    /** 画面からの呼び出しでは、ブラウザの認証ダイアログが出ないよう WWW-Authenticate を付けない。 */
    @Test
    void unauthenticatedScreenRequestHasNoBasicChallenge() {
        assertThat(mvc.get().uri("/periods").header("X-Requested-With", "XMLHttpRequest"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .doesNotContainHeader("WWW-Authenticate");
        assertThat(mvc.get().uri("/periods"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .containsHeader("WWW-Authenticate");
    }

    @Test
    @WithMockUser(roles = SecurityConfig.VIEWER)
    void viewerCannotPost() {
        assertThat(postJournal(SALE)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    @WithMockUser(roles = SecurityConfig.BOOKKEEPER)
    void onlyApproverCanClose() {
        UUID periodId = UUID.randomUUID();
        assertThat(mvc.post().uri("/periods/{id}/close", periodId)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    @WithMockUser(roles = SecurityConfig.APPROVER)
    void approverCanCloseAndAlsoDoBookkeeperOperations() {
        UUID periodId = UUID.randomUUID();
        UUID nextId = UUID.randomUUID();
        given(closePeriodUseCase.close(periodId)).willReturn(new ClosePeriodUseCase.Result(
                new com.example.accounting.model.period.AccountingPeriodId(nextId), Money.ofYen(-20_000)));

        assertThat(mvc.post().uri("/periods/{id}/close", periodId)).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.nextPeriodId", v -> v.assertThat().isEqualTo(nextId.toString()))
                .hasPathSatisfying("$.netIncome", v -> v.assertThat().isEqualTo(-20000));

        given(postJournalUseCase.post(any())).willReturn(JournalId.newId());
        assertThat(postJournal(SALE)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    @WithMockUser(roles = SecurityConfig.VIEWER)
    void trialBalance_amountsAreJsonIntegers() {
        UUID periodId = UUID.randomUUID();
        TrialBalance trialBalance = TrialBalance.of(List.of(Journal.of(JournalId.newId(), LocalDate.of(2026, 4, 1),
                "出資による開業", JournalType.NORMAL, List.of(
                        JournalLine.debit(StandardAccounts.CASH, Money.ofYen(600_000)),
                        JournalLine.credit(StandardAccounts.CAPITAL_STOCK, Money.ofYen(600_000))))));
        given(statementQueryService.trialBalance(eq(periodId), eq(false))).willReturn(trialBalance);

        assertThat(mvc.get().uri("/periods/{id}/trial-balance", periodId)).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.debitTotal", v -> v.assertThat().isEqualTo(600000))
                .hasPathSatisfying("$.balanced", v -> v.assertThat().isEqualTo(true))
                .hasPathSatisfying("$.rows[0].accountCode", v -> v.assertThat().isEqualTo("101"))
                .hasPathSatisfying("$.rows[0].accountName", v -> v.assertThat().isEqualTo("現金"))
                .hasPathSatisfying("$.rows[0].balance", v -> v.assertThat().isEqualTo(600000));
    }
}
