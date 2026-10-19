package com.example.accountingapp.presentation.mcp.journal;

import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accountingapp.application.journal.CorrectJournalCommand;
import com.example.accountingapp.application.journal.CorrectJournalUseCase;
import com.example.accountingapp.application.journal.PostJournalCommand;
import com.example.accountingapp.application.journal.PostJournalUseCase;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.presentation.JournalLineRequest;
import com.example.accountingapp.presentation.JournalResponse;
import com.example.accountingapp.presentation.Today;
import com.example.accountingapp.presentation.controller.journal.JournalController.CorrectionResponse;
import com.example.accountingapp.presentation.controller.journal.JournalController.JournalIdResponse;
import com.example.accountingapp.presentation.mcp.McpTools;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 仕訳の記帳・赤黒訂正と、仕訳帳の照会。 */
@Component
@Validated
public class JournalTools implements McpTools {

    private static final String LINES_DESCRIPTION = """
            仕訳の行(2行以上)。各行は accountCode(科目コード)、side(DEBIT: 借方 / CREDIT: 貸方)、amount(円、正の整数)。
            借方合計と貸方合計は一致している必要がある。""";

    private final PostJournalUseCase postJournalUseCase;
    private final CorrectJournalUseCase correctJournalUseCase;
    private final StatementQueryService statementQueryService;
    private final Today today;

    public JournalTools(PostJournalUseCase postJournalUseCase, CorrectJournalUseCase correctJournalUseCase,
                        StatementQueryService statementQueryService, Today today) {
        this.postJournalUseCase = postJournalUseCase;
        this.correctJournalUseCase = correctJournalUseCase;
        this.statementQueryService = statementQueryService;
        this.today = today;
    }

    @Tool(name = "post_journal", description = """
            仕訳を記帳する。日付を含む OPEN の会計期間に記帳される。
            種類は NORMAL(通常)か ADJUSTING(手入力の決算整理。経過勘定・貯蔵品など)。""")
    @PreAuthorize(BOOKKEEPER)
    public JournalIdResponse postJournal(
            @ToolParam(description = "日付(yyyy-MM-dd)。省略すると今日", required = false) LocalDate date,
            @ToolParam(description = "摘要") @NotBlank String description,
            @ToolParam(description = "種類。省略すると NORMAL", required = false) JournalType type,
            @ToolParam(description = LINES_DESCRIPTION) @NotNull @Size(min = 2) List<@Valid JournalLineRequest> lines) {
        JournalId id = postJournalUseCase.post(new PostJournalCommand(
                today.orToday(date), description, type == null ? JournalType.NORMAL : type,
                JournalLineRequest.toCommands(lines)));
        return new JournalIdResponse(id.value());
    }

    @Tool(name = "correct_journal", description = """
            記帳済みの仕訳を赤黒で訂正する。元の仕訳を打ち消す取消仕訳(赤)を記帳し、正しい仕訳(黒)を指定すればそれも記帳する。
            正しい仕訳の摘要・行を省略すると取消だけを行う。訂正は訂正日を含む OPEN の期に記帳される。""")
    @PreAuthorize(BOOKKEEPER)
    public CorrectionResponse correctJournal(
            @ToolParam(description = "訂正する元の仕訳の ID") @NotNull UUID journalId,
            @ToolParam(description = "訂正日(yyyy-MM-dd)。省略すると今日", required = false) LocalDate date,
            @ToolParam(description = "正しい仕訳の摘要。取消だけなら省略", required = false) String correctDescription,
            @ToolParam(description = "正しい仕訳の種類。省略すると NORMAL", required = false) JournalType correctType,
            @ToolParam(description = "正しい仕訳の行。取消だけなら省略。" + LINES_DESCRIPTION, required = false)
            List<@Valid JournalLineRequest> correctLines) {
        Optional<CorrectJournalCommand.CorrectedJournal> correct = correctLines == null || correctLines.isEmpty()
                ? Optional.empty()
                : Optional.of(new CorrectJournalCommand.CorrectedJournal(
                        correctDescription, correctType == null ? JournalType.NORMAL : correctType,
                        JournalLineRequest.toCommands(correctLines)));
        CorrectJournalUseCase.Result result = correctJournalUseCase.correct(
                new CorrectJournalCommand(new JournalId(journalId), today.orToday(date), correct));
        return new CorrectionResponse(result.reversalId().value(),
                result.correctJournalId() == null ? null : result.correctJournalId().value());
    }

    @Tool(name = "get_journal", description = "仕訳を1つ返す。reversed は赤黒で取り消し済みか。")
    @PreAuthorize(VIEWER)
    public JournalResponse getJournal(@ToolParam(description = "仕訳の ID") @NotNull UUID journalId) {
        return JournalResponse.of(statementQueryService.journal(journalId), statementQueryService.isReversed(journalId));
    }

    @Tool(name = "list_journals", description = """
            仕訳帳。会計期間に記帳されたすべての仕訳(開始・再振替・通常・決算整理・損益振替)を返す。""")
    @PreAuthorize(VIEWER)
    public List<JournalResponse> listJournals(@ToolParam(description = "会計期間の ID") @NotNull UUID periodId) {
        Set<JournalId> reversed = statementQueryService.reversedJournalIds(periodId);
        return statementQueryService.journals(periodId).stream()
                .map(posted -> JournalResponse.of(posted, reversed.contains(posted.id())))
                .toList();
    }
}
