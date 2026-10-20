package com.example.store.exception;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Maps exceptions to RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Standard Spring MVC exceptions (404 unknown route, 405, 415, type mismatches, ...) are handled by
 * {@link ResponseEntityExceptionHandler}. Spring picks the most specific handler, so the catch-all
 * {@link #handleUnexpected} only applies to exceptions not mapped anywhere else. Responses never carry stack traces,
 * SQL or parser internals; those are logged server-side.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(ReferencedResourceNotFoundException.class)
    public ProblemDetail handleReferencedNotFound(ReferencedResourceNotFoundException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Referenced resource not found", ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return problem(
                HttpStatus.CONFLICT,
                "Data integrity violation",
                "The request conflicts with the current state of data.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
    }

    /**
     * Constraint violations on controller method parameters (e.g. a negative {@code page}), reported with the same
     * {@code errors} shape as request body validation; {@code field} is the request parameter name.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            @NonNull HandlerMethodValidationException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        // A violated return value constraint is a server bug, not a bad request: keep Spring's 500.
        if (ex.getStatusCode().is5xxServerError()) {
            return super.handleHandlerMethodValidationException(ex, headers, status, request);
        }
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> fieldError(
                                String.valueOf(result.getMethodParameter().getParameterName()),
                                error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            @NonNull HttpMessageNotReadableException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        // The default detail would echo Jackson's parser message, which exposes internal type names.
        ProblemDetail body =
                problem(HttpStatus.BAD_REQUEST, "Malformed request body", "The request body is not readable.");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed.");
        body.setProperty("errors", errors);
        return body;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", String.valueOf(message));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
