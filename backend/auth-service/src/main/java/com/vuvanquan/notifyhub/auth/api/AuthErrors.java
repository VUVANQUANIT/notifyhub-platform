package com.vuvanquan.notifyhub.auth.api;

import com.vuvanquan.notifyhub.auth.application.AuthFailure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class AuthErrors {
    @ExceptionHandler(com.vuvanquan.notifyhub.auth.control.ControlFailure.class)
    ResponseEntity<ProblemDetail> control(com.vuvanquan.notifyhub.auth.control.ControlFailure error) {
        return ResponseEntity.status(error.status()).header("Retry-After", Long.toString(error.retryAfter()))
                .body(ProblemDetail.forStatusAndDetail(error.status(), error.getMessage()));
    }
    @ExceptionHandler(AuthFailure.class) ResponseEntity<ProblemDetail> auth(AuthFailure error) {
        var response = ResponseEntity.status(error.status());
        if (error.status() == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(ProblemDetail.forStatusAndDetail(error.status(), error.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class) ProblemDetail invalid(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail malformed(Exception error) {
        // Never echo request bodies: they may contain passwords or refresh credentials.
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
    }
}
