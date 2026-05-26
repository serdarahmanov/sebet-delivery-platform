package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.Cart;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueCode;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueScope;
import com.sebet.cartservice.cart.mapper.RedisCartMapper;
import com.sebet.cartservice.cart.model.StoreBasketIssue;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_calculation.StoreBasketCalculation;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationContext;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DefaultCartResponseBuilder implements CartResponseBuilder {

    private final CartValidationService cartValidationService;
    private final CartCalculationService cartCalculationService;
    private final RedisCartMapper redisCartMapper;

    @Override
    public Cart build(RedisCart cart) {
        return buildInternal(cart, null);
    }

    @Override
    public Cart build(RedisCart cart, Set<String> affectedStoreIds) {
        return buildInternal(cart, affectedStoreIds);
    }

    @Override
    public Cart buildReadOnly(RedisCart cart, Set<String> affectedStoreIds) {
        return buildInternal(cart, affectedStoreIds);
    }

    private Cart buildInternal(RedisCart cart, Set<String> affectedStoreIds) {
        CartValidationContext validationContext = cartValidationService.validate(cart, affectedStoreIds);

        CartCalculationResult calculationResult = cartCalculationService.calculate(
                cart,
                validationContext.validationResult(),
                affectedStoreIds
        );

        applyMinimumOrderChecks(validationContext.validationResult(), calculationResult);

        return redisCartMapper.toCart(
                cart,
                validationContext.validationResult(),
                calculationResult,
                affectedStoreIds
        );
    }

    private void applyMinimumOrderChecks(
            CartValidationResult validationResult,
            CartCalculationResult calculationResult
    ) {
        for (Map.Entry<String, StoreBasketCalculation> entry :
                calculationResult.storeBasketCalculationsByStoreId().entrySet()) {
            String storeId = entry.getKey();
            StoreBasketCalculation basket = entry.getValue();
            StoreSnapshot store = validationResult.storesByStoreId().get(storeId);

            if (store == null || store.minimumOrderAmount() == null) {
                continue;
            }

            BigDecimal itemsSubtotal = basket.itemsSubtotal() != null ? basket.itemsSubtotal() : BigDecimal.ZERO;
            if (itemsSubtotal.compareTo(store.minimumOrderAmount()) < 0) {
                validationResult.addStoreBasketIssue(storeId, new StoreBasketIssue(
                        StoreBasketIssueCode.MINIMUM_ORDER_NOT_REACHED,
                        IssueSeverity.BLOCKING,
                        StoreBasketIssueScope.BASKET,
                        "Minimum order amount has not been reached.",
                        storeId,
                        store.minimumOrderAmount(),
                        itemsSubtotal,
                        Map.of()
                ));
            }
        }
    }
}
