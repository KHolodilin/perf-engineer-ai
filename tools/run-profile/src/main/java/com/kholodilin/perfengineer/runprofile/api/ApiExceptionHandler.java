package com.kholodilin.perfengineer.runprofile.api;

import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.perfengineer.runprofile.application.RequestRejectedException;
import com.kholodilin.perfengineer.runprofile.application.RunNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RequestRejectedException.class)
    ResponseEntity<ErrorBody> rejected(RequestRejectedException exception) {
        return ResponseEntity.status(exception.httpStatus())
                .body(new ErrorBody(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorBody> conflict(IdempotencyConflictException exception) {
        return ResponseEntity.status(409)
                .body(new ErrorBody("IDEMPOTENCY_CONFLICT", "Idempotency key was already used with a different request"));
    }

    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<ErrorBody> missing(RunNotFoundException exception) {
        return ResponseEntity.status(404).body(new ErrorBody("RUN_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorBody> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", "Request is invalid"));
    }
}
