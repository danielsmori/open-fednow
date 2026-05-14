package io.openfednow.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Global validation error handler for ISO 20022 message endpoints.
 *
 * <p>Translates {@link MethodArgumentNotValidException} (triggered when a
 * {@code @Valid @RequestBody} fails bean validation) into a structured
 * RFC 9457 Problem Detail response listing each field-level constraint violation.
 *
 * <p>Example response body:
 * <pre>{@code
 * {
 *   "type": "about:blank",
 *   "title": "Bad Request",
 *   "status": 400,
 *   "detail": "ISO 20022 message validation failed",
 *   "fieldErrors": [
 *     { "field": "endToEndId",              "rejectedValue": "",       "message": "must not be blank" },
 *     { "field": "debtorAgentRoutingNumber", "rejectedValue": "NOTNUM", "message": "must be a 9-digit ABA routing number" }
 *   ]
 * }
 * }</pre>
 */
@RestControllerAdvice
public class ValidationErrorHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.<String, Object>of(
                        "field", e.getField(),
                        "rejectedValue", e.getRejectedValue() == null ? "" : e.getRejectedValue(),
                        "message", e.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "ISO 20022 message validation failed");
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }
}
