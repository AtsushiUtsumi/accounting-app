package com.example.accountingapp.presentation.controller;

import com.example.accounting.exception.AccountingException;
import com.example.accountingapp.application.ConcurrencyConflictException;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 例外を ProblemDetail(RFC 9457)に変換する(CLAUDE.md §8)。入力の形式エラー(Bean Validation)は
 * 親クラスが 400 にする。
 *
 * <p>ライブラリの例外は今は {@link AccountingException} 1種類で、メッセージは英語の開発者向け文言。
 * 画面向けの日本語メッセージへの変換は未決(§12 の未決事項10)のため、{@code detail} にそのまま入れ、
 * 画面が振り分けに使えるよう {@code code} を付ける。
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountingException.class)
    ProblemDetail handleAccountingRuleViolation(AccountingException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNTING_RULE_VIOLATION", e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    ProblemDetail handleConcurrencyConflict(ConcurrencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", e.getMessage());
    }

    @ExceptionHandler(OperationNotAllowedException.class)
    ProblemDetail handleOperationNotAllowed(OperationNotAllowedException e) {
        return problem(HttpStatus.CONFLICT, "OPERATION_NOT_ALLOWED", e.getMessage());
    }

    @ExceptionHandler(InvalidCommandException.class)
    ProblemDetail handleInvalidCommand(InvalidCommandException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    /** DB の制約違反(主キーの重複など)。ほかの操作との競合として扱う。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException e) {
        return problem(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION", "the request conflicts with stored data");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
