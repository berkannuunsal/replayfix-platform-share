package com.etiya.replayfix.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(
            GlobalExceptionHandler.class
    );

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
    ProblemDetail internal(Exception exception, HttpServletRequest request) {
        String endpointPath = request == null ? "" : request.getRequestURI();
        if (endpointPath.endsWith("/source/suspect-change-analysis")) {
            log.error(
                    "Source suspect change analysis endpoint exception endpointPath={} caseId={} exceptionClass={} exceptionMessage={}",
                    endpointPath,
                    caseIdFromPath(endpointPath),
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception
            );
        }
        ProblemDetail detail = ProblemDetail.forStatus(
                HttpStatus.INTERNAL_SERVER_ERROR
        );
        detail.setTitle("ReplayFix operation failed");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    private String caseIdFromPath(String endpointPath) {
        if (endpointPath == null || endpointPath.isBlank()) {
            return "";
        }
        String marker = "/api/v1/cases/";
        int start = endpointPath.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int caseIdStart = start + marker.length();
        int caseIdEnd = endpointPath.indexOf('/', caseIdStart);
        if (caseIdEnd < 0) {
            return endpointPath.substring(caseIdStart);
        }
        return endpointPath.substring(caseIdStart, caseIdEnd);
    }
}
