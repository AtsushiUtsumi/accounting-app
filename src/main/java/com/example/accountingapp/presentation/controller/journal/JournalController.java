package com.example.accountingapp.presentation.controller.journal;

import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accountingapp.application.journal.CorrectJournalCommand;
import com.example.accountingapp.application.journal.CorrectJournalUseCase;
import com.example.accountingapp.application.journal.PostJournalCommand;
import com.example.accountingapp.application.journal.PostJournalUseCase;
import com.example.accountingapp.application.statement.StatementQueryService;
import com.example.accountingapp.presentation.JournalResponse;
import com.example.accountingapp.presentation.JournalLineRequest;
import com.example.accountingapp.presentation.Today;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
public class JournalController {

    private final PostJournalUseCase postJournalUseCase;
    private final CorrectJournalUseCase correctJournalUseCase;
    private final StatementQueryService statementQueryService;
    private final Today today;

    public JournalController(PostJournalUseCase postJournalUseCase, CorrectJournalUseCase correctJournalUseCase,
                             StatementQueryService statementQueryService, Today today) {
        this.postJournalUseCase = postJournalUseCase;
        this.correctJournalUseCase = correctJournalUseCase;
        this.statementQueryService = statementQueryService;
        this.today = today;
    }

    /** 記帳の入力。日付を省略すると今日。種類を省略すると NORMAL。 */
    public record PostJournalRequest(LocalDate date, @NotBlank String description, JournalType type,
                                     @NotNull @Size(min = 2) List<@Valid JournalLineRequest> lines) {
    }

    public record JournalIdResponse(UUID journalId) {
    }

    @PostMapping("/journals")
    @ResponseStatus(HttpStatus.CREATED)
    public JournalIdResponse post(@Valid @RequestBody PostJournalRequest request) {
        JournalId id = postJournalUseCase.post(new PostJournalCommand(
                today.orToday(request.date()), request.description(),
                request.type() == null ? JournalType.NORMAL : request.type(),
                JournalLineRequest.toCommands(request.lines())));
        return new JournalIdResponse(id.value());
    }

    /** 赤黒訂正の入力。{@code correctJournal} を省略すると取消のみ。 */
    public record CorrectionRequest(LocalDate date, @Valid CorrectedJournalRequest correctJournal) {
    }

    public record CorrectedJournalRequest(@NotBlank String description, JournalType type,
                                          @NotNull @Size(min = 2) List<@Valid JournalLineRequest> lines) {
    }

    public record CorrectionResponse(UUID reversalId, UUID correctJournalId) {
    }

    @PostMapping("/journals/{id}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionResponse correct(@PathVariable UUID id, @Valid @RequestBody CorrectionRequest request) {
        Optional<CorrectJournalCommand.CorrectedJournal> correct = Optional.ofNullable(request.correctJournal())
                .map(c -> new CorrectJournalCommand.CorrectedJournal(
                        c.description(), c.type() == null ? JournalType.NORMAL : c.type(),
                        JournalLineRequest.toCommands(c.lines())));
        CorrectJournalUseCase.Result result = correctJournalUseCase.correct(
                new CorrectJournalCommand(new JournalId(id), today.orToday(request.date()), correct));
        return new CorrectionResponse(result.reversalId().value(),
                result.correctJournalId() == null ? null : result.correctJournalId().value());
    }

    @GetMapping("/journals/{id}")
    public JournalResponse get(@PathVariable UUID id) {
        return JournalResponse.of(statementQueryService.journal(id), statementQueryService.isReversed(id));
    }

    /** 仕訳帳。 */
    @GetMapping("/periods/{id}/journals")
    public List<JournalResponse> journalsOf(@PathVariable UUID id) {
        Set<JournalId> reversed = statementQueryService.reversedJournalIds(id);
        return statementQueryService.journals(id).stream()
                .map(posted -> JournalResponse.of(posted, reversed.contains(posted.id())))
                .toList();
    }
}
