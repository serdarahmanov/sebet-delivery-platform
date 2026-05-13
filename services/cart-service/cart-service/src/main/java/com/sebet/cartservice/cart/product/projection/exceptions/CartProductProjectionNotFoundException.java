package com.sebet.cartservice.cart.product.projection.exceptions;

public class CartProductProjectionNotFoundException extends RuntimeException{
    public CartProductProjectionNotFoundException(String message) {
        super(message);
    }
}
