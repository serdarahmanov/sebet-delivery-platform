package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.Cart;
import com.sebet.cartservice.cart.model.redis.RedisCart;

import java.util.Set;

public interface CartResponseBuilder {

    /** Validates the full cart and builds a response. All delivery-quote changes are in-memory only. */
    Cart build(RedisCart cart);

    /** Validates the scoped baskets and builds a response. All delivery-quote changes are in-memory only. */
    Cart build(RedisCart cart, Set<String> affectedStoreIds);

    /** Read-only path (GET endpoints) — semantically identical to {@link #build(RedisCart, Set)}. */
    Cart buildReadOnly(RedisCart cart, Set<String> affectedStoreIds);
}
