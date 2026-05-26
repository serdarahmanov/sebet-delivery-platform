package com.sebet.cartservice.cart.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown when an optimistic-lock CAS save fails because the cart was modified
 * by a concurrent request between the read and the write.
 *
 * <p>Maps to HTTP 409 Conflict. The client should retry the operation.
 */
public class CartVersionConflictException extends ResponseStatusException {

    public CartVersionConflictException() {
        super(HttpStatus.CONFLICT, "Cart was modified concurrently. Please retry.");
    }
}
