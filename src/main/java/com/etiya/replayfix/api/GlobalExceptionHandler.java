package com.etiya.replayfix.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(
                HttpStatus.BAD_REQUEST
        );
        detail.setTitle("Invalid request");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail internal(Exception exception) {
        ProblemDetail detail = ProblemDetail.forStatus(
                HttpStatus.INTERNAL_SERVER_ERROR
        );
        detail.setTitle("ReplayFix operation failed");
        detail.setDetail(exception.getMessage());
        return detail;
    }
}
