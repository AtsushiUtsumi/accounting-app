package com.example.accountingapp.presentation.mcp;

import com.example.accounting.exception.AccountingException;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.method.MethodValidationException;
import tools.jackson.core.JacksonException;

import java.util.stream.Collectors;

/**
 * ツールの例外を、AI エージェントに返すエラーメッセージに変換する。REST の {@code ApiExceptionHandler} と同じ
 * {@code code} を先頭に付ける(例: {@code [ACCOUNTING_RULE_VIOLATION] debit and credit totals must match})。
 * MCP サーバーは例外のメッセージを、そのままエラーのツール結果({@code isError: true})にする。
 */
class McpErrorTranslatingToolCallback implements ToolCallback {

    private static final Logger LOG = LoggerFactory.getLogger(McpErrorTranslatingToolCallback.class);

    private final ToolCallback delegate;

    McpErrorTranslatingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
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
        try {
            return delegate.call(toolInput, toolContext);
        }
        catch (RuntimeException e) {
            Throwable cause = e instanceof ToolExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new ToolExecutionException(getToolDefinition(), new IllegalStateException(messageOf(cause), cause));
        }
    }

    private String messageOf(Throwable e) {
        return switch (e) {
            case AccountingException ex -> "[ACCOUNTING_RULE_VIOLATION] " + ex.getMessage();
            case ResourceNotFoundException ex -> "[NOT_FOUND] " + ex.getMessage();
            case ConcurrencyConflictException ex -> "[CONCURRENT_UPDATE] " + ex.getMessage();
            case OperationNotAllowedException ex -> "[OPERATION_NOT_ALLOWED] " + ex.getMessage();
            case InvalidCommandException ex -> "[INVALID_REQUEST] " + ex.getMessage();
            case ConstraintViolationException ex -> "[INVALID_REQUEST] " + ex.getConstraintViolations().stream()
                    .map(McpErrorTranslatingToolCallback::describe)
                    .sorted()
                    .collect(Collectors.joining(", "));
            case MethodValidationException ex -> "[INVALID_REQUEST] " + ex.getAllErrors().stream()
                    .map(error -> String.valueOf(error.getDefaultMessage()))
                    .collect(Collectors.joining(", "));
            case JacksonException ex -> "[INVALID_REQUEST] the arguments could not be read: " + ex.getOriginalMessage();
            case DataIntegrityViolationException ex -> "[DATA_INTEGRITY_VIOLATION] the request conflicts with stored data";
            case AccessDeniedException ex -> "[FORBIDDEN] your role is not allowed to use this tool";
            default -> {
                LOG.error("MCP tool {} failed", getToolDefinition().name(), e);
                yield "[INTERNAL_ERROR] an unexpected error occurred";
            }
        };
    }

    /** {@code post.arg1[0].amount: must be greater than 0} のパスから、メソッド名を除いて返す。 */
    private static String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int dot = path.indexOf('.');
        return (dot < 0 ? path : path.substring(dot + 1)) + ": " + violation.getMessage();
    }
}
