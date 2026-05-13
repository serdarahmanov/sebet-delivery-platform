package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.AddCartItemRequest;
import com.sebet.cartservice.cart.dto.ApplyPromoCodeRequest;
import com.sebet.cartservice.cart.dto.CartResponse;
import com.sebet.cartservice.cart.dto.UpdateCartItemRequest;
import com.sebet.cartservice.cart.mapper.RedisCartMapper;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisCartPromoCode;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRedisRepository cartRedisRepository;
    private final CartValidationService cartValidationService;
    private final CartCalculationService cartCalculationService;
    private final RedisCartMapper redisCartMapper;

    public CartResponse getCart(String userId) {
        RedisCart cart = getOrCreateCart(userId);
        return buildResponse(cart);
    }

    public CartResponse addItemToCart(String userId, AddCartItemRequest request) {
        RedisCart cart = getOrCreateCart(userId);

        RedisCartItem existingItem = cart.getItems().stream()
                .filter(item -> request.productId().equals(item.getProductId())
                        && request.storeId().equals(item.getStoreId()))
                .findFirst()
                .orElse(null);

        if (existingItem != null) {
            BigDecimal newQuantity = safe(existingItem.getQuantity()).add(request.quantity());
            existingItem.setQuantity(newQuantity);
            existingItem.touch();
        } else {
            cart.getItems().add(new RedisCartItem(
                    request.productId(),
                    request.storeId(),
                    request.quantity(),
                    null
            ));
        }

        cart.touch();
        cartRedisRepository.save(userId, cart);

        return buildResponse(cart);
    }

    public CartResponse updateCartItemQuantity(
            String userId,
            String cartItemId,
            UpdateCartItemRequest request
    ) {
        RedisCart cart = getOrCreateCart(userId);

        RedisCartItem targetItem = cart.getItems().stream()
                .filter(item -> cartItemId.equals(item.getCartItemId()))
                .findFirst()
                .orElse(null);

        if (targetItem != null) {
            if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                cart.getItems().removeIf(item -> cartItemId.equals(item.getCartItemId()));
            } else {
                targetItem.setQuantity(request.quantity());
                targetItem.touch();
            }
            cart.touch();
            cartRedisRepository.save(userId, cart);
        }

        return buildResponse(cart);
    }

    public CartResponse removeCartItem(String userId, String cartItemId) {
        RedisCart cart = getOrCreateCart(userId);

        boolean removed = cart.getItems().removeIf(item -> cartItemId.equals(item.getCartItemId()));

        if (removed) {
            cart.touch();
            cartRedisRepository.save(userId, cart);
        }

        return buildResponse(cart);
    }

    public CartResponse applyPromoCode(
            String userId,
            String storeId,
            ApplyPromoCodeRequest request
    ) {
        RedisCart cart = getOrCreateCart(userId);

        cart.getPromoCodes().removeIf(promo -> storeId.equals(promo.getStoreId()));
        cart.getPromoCodes().add(new RedisCartPromoCode(storeId, request.promoCode()));

        cart.touch();
        cartRedisRepository.save(userId, cart);

        return buildResponse(cart);
    }

    public CartResponse removePromoCode(String userId, String storeId, String code) {
        RedisCart cart = getOrCreateCart(userId);

        boolean removed = cart.getPromoCodes().removeIf(promo ->
                storeId.equals(promo.getStoreId()) && code.equalsIgnoreCase(promo.getCode())
        );

        if (removed) {
            cart.touch();
            cartRedisRepository.save(userId, cart);
        }

        return buildResponse(cart);
    }

    public CartResponse validateCart(String userId) {
        RedisCart cart = getOrCreateCart(userId);

        CartResponse response = buildResponse(cart);
        cartRedisRepository.save(userId, cart);

        return response;
    }

    public void clearCart(String userId) {
        cartRedisRepository.deleteByUserId(userId);
    }

    public CartResponse clearBasket(String userId, String basketId) {
        RedisCart cart = getOrCreateCart(userId);

        boolean removed = removeBasketItems(cart, basketId);
        if (removed) {
            cart.getPromoCodes().removeIf(promo -> basketMatches(promo.getStoreId(), basketId, cart.getCartId()));
            cart.touch();
            cartRedisRepository.save(userId, cart);
        }

        return buildResponse(cart);
    }

    private RedisCart getOrCreateCart(String userId) {
        return cartRedisRepository.findByUserId(userId)
                .orElseGet(() -> createEmptyCart(userId));
    }

    private RedisCart createEmptyCart(String userId) {
        return new RedisCart(userId);
    }

    private CartResponse buildResponse(RedisCart cart) {
        CartValidationResult cartValidationResult = cartValidationService.validate(cart);
        CartCalculationResult cartCalculationResult = cartCalculationService.calculate(cart, cartValidationResult);
        return redisCartMapper.toCartResponse(cart, cartValidationResult, cartCalculationResult);
    }

    private boolean removeBasketItems(RedisCart cart, String basketId) {
        List<RedisCartItem> items = cart.getItems();
        int before = items.size();

        items.removeIf(item -> basketMatches(item.getStoreId(), basketId, cart.getCartId()));

        return items.size() != before;
    }

    private boolean basketMatches(String storeId, String basketId, String cartId) {
        if (basketId == null) {
            return false;
        }

        if (basketId.equals(storeId)) {
            return true;
        }

        if (cartId == null || storeId == null) {
            return false;
        }

        return basketId.equals(cartId + ":" + storeId);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}