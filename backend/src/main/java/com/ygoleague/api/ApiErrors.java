package com.ygoleague.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns the {@link ResponseStatusException}s our services throw (404, 409, 410, ...) into
 * {@code {"message": "..."}} with the same status, so the frontend can show the reason. Only
 * those deliberate, user-facing reasons are returned; other exceptions keep Spring's default
 * error response, which doesn't expose exception messages.
 */
@RestControllerAdvice
public class ApiErrors {

    public record ErrorBody(String message) {}

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorBody(e.getReason()));
    }
}
