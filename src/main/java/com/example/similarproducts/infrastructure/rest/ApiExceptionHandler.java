package com.example.similarproducts.infrastructure.rest;

import com.example.similarproducts.application.MissingProductException;
import com.example.similarproducts.infrastructure.http.UpstreamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SimilarProductsController.class)
public final class ApiExceptionHandler {

    @ExceptionHandler(MissingProductException.class)
    ResponseEntity<Void> missingDetail() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(UpstreamException.class)
    ResponseEntity<Void> upstream(UpstreamException error) {
        return ResponseEntity.status(switch (error.kind()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case TRANSPORT, OVERLOADED -> HttpStatus.SERVICE_UNAVAILABLE;
            case HTTP_STATUS, INVALID_PAYLOAD -> HttpStatus.BAD_GATEWAY;
        }).build();
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Void> unexpected(RuntimeException error) {
        return ResponseEntity.status(error instanceof ErrorResponse response
                ? response.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
