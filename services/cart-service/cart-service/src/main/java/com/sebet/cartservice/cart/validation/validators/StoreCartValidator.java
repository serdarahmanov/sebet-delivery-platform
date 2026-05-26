package com.sebet.cartservice.cart.validation.validators;


import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.model.store.StoreIssue;
import com.sebet.cartservice.cart.model.store.StoreIssueCode;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.store.projection.StoreProjection;
import com.sebet.cartservice.cart.validation.tools.CartValidationAccumulator;
import com.sebet.cartservice.cart.validation.tools.CartValidationLookupContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class StoreCartValidator {

    public void validate(
            RedisCart cart,
            CartValidationAccumulator accumulator,
            CartValidationLookupContext lookupContext
    ) {
        CartValidationResult result = accumulator.validationResult();
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return;
        }

        Set<String> storeIds = cart.getItems()
                .stream()
                .filter(Objects::nonNull)
                .map(RedisCartItem::getStoreId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, StoreProjection> storeMap = lookupContext == null
                ? Map.of()
                : lookupContext.storeByStoreId();

        for (String storeId : storeIds) {
            if (!storeMap.containsKey(storeId)) {
                result.addStoreIssue(storeId, new StoreIssue(
                        StoreIssueCode.STORE_NOT_FOUND,
                        IssueSeverity.BLOCKING,
                        "Store is no longer available.",
                        storeId,
                        Map.of()
                ));
                continue;
            }
            validateStore(storeId, storeMap.get(storeId), result);
        }
    }

    private void validateStore(
            String storeId,
            StoreProjection store,
            CartValidationResult result
    ) {
        result.addStoreSnapshot(new StoreSnapshot(
                store.getStoreId(),
                store.getStoreName(),
                store.getStoreLogoUrl(),
                true,
                Boolean.TRUE.equals(store.getOpen()) && Boolean.TRUE.equals(store.getAcceptingOrders()),
                store.getMinimumOrderAmount(),
                store.getBaseDeliveryFee(),
                store.getFreeDeliveryThreshold()
        ));

        if (Boolean.FALSE.equals(store.getActive())) {
            result.addStoreIssue(storeId, new StoreIssue(
                    StoreIssueCode.STORE_INACTIVE,
                    IssueSeverity.BLOCKING,
                    "Store is currently inactive.",
                    storeId,
                    Map.of(
                            "storeName", store.getStoreName()
                    )
            ));
        }

        if (Boolean.FALSE.equals(store.getOpen())) {
            result.addStoreIssue(storeId, new StoreIssue(
                    StoreIssueCode.STORE_CLOSED,
                    IssueSeverity.BLOCKING,
                    "Store is currently closed.",
                    storeId,
                    Map.of(
                            "storeName", store.getStoreName()
                    )
            ));
        }

        if (Boolean.FALSE.equals(store.getAcceptingOrders())) {
            result.addStoreIssue(storeId, new StoreIssue(
                    StoreIssueCode.STORE_NOT_ACCEPTING_ORDERS,
                    IssueSeverity.BLOCKING,
                    "Store is not accepting orders right now.",
                    storeId,
                    Map.of(
                            "storeName", store.getStoreName()
                    )
            ));
        }
    }


}
