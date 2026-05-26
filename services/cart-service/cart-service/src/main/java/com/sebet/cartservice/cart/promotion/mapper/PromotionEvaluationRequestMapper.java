package com.sebet.cartservice.cart.promotion.mapper;

import com.sebet.cartservice.cart.model.CartIssue;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.dto.PromotionEligibleItemData;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request.PromotionCartItemRequest;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request.PromotionEvaluationRequest;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request.PromotionStoreBasketRequest;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import com.sebet.cartservice.cart.model.store.StoreIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PromotionEvaluationRequestMapper {

    private static final String CURRENCY = "TMT";

    public PromotionEvaluationRequest toRequest(
            RedisCart cart,
            CartValidationResult validationResult,
            Map<String, PromotionEligibleItemData> promotionItemData
    ) {
        if (cart == null) {
            return PromotionEvaluationRequest.empty(null, null);
        }

        /*
         * If the whole cart has a cart-level blocking issue,
         * do not send anything to Promotion Service.
         */
        if (hasBlockingCartIssue(validationResult)) {
            return PromotionEvaluationRequest.empty(cart.getCartId(), cart.getUserId());
        }

        Map<String, PromotionEligibleItemData> safePromotionItemData =
                promotionItemData == null ? Map.of() : promotionItemData;

        Map<String, List<PromotionEligibleItemData>> promotionItemsByStore = safePromotionItemData
                .values()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.storeId() != null)
                .collect(Collectors.groupingBy(PromotionEligibleItemData::storeId));

        List<PromotionStoreBasketRequest> storeBaskets = promotionItemsByStore
                .entrySet()
                .stream()
                .map(entry -> toStoreBasketRequest(
                        cart,
                        entry.getKey(),
                        entry.getValue(),
                        validationResult
                ))
                .filter(Objects::nonNull)
                .toList();

        return new PromotionEvaluationRequest(
                cart.getCartId(),
                cart.getUserId(),
                CURRENCY,
                Instant.now(),
                storeBaskets
        );
    }

    private PromotionStoreBasketRequest toStoreBasketRequest(
            RedisCart cart,
            String storeId,
            List<PromotionEligibleItemData> promotionItems,
            CartValidationResult validationResult
    ) {
        /*
         * StoreBasket = group of items from one store.
         *
         * If the store itself has a blocking issue,
         * none of this store basket should be sent to Promotion Service.
         */
        if (hasBlockingStoreIssue(storeId, validationResult)) {
            return null;
        }

        /*
         * Important:
         * Do NOT check StoreBasketIssue here.
         *
         * MINIMUM_ORDER_NOT_REACHED should still allow promotion evaluation,
         * because item-level promotions can still be shown in the cart.
         */

        List<PromotionEligibleItemData> validPromotionItems = promotionItems == null
                ? List.of()
                : promotionItems.stream()
                .filter(Objects::nonNull)
                .toList();

        /*
         * If after removing invalid items this store basket has no valid items,
         * do not send it to Promotion Service.
         */
        if (validPromotionItems.isEmpty()) {
            return null;
        }

        BigDecimal itemsSubtotal = calculateItemsSubtotal(validPromotionItems);
        BigDecimal deliveryFee = calculateDeliveryFeeForStoreBasket(validationResult, storeId);
        List<String> promoCodes = getPromoCodesForStore(cart, storeId);

        List<PromotionCartItemRequest> promotionCartItems = validPromotionItems
                .stream()
                .map(this::toPromotionCartItemRequest)
                .toList();

        return new PromotionStoreBasketRequest(
                storeId,
                promoCodes,
                itemsSubtotal,
                deliveryFee,
                promotionCartItems
        );
    }

    private PromotionCartItemRequest toPromotionCartItemRequest(
            PromotionEligibleItemData item
    ) {
        return new PromotionCartItemRequest(
                item.cartItemId(),
                item.productId(),
                item.categoryId(),
                item.quantity(),
                item.unit(),
                item.unitPrice(),
                item.lineSubtotal()
        );
    }

    private boolean hasBlockingCartIssue(
            CartValidationResult validationResult
    ) {
        if (validationResult == null) {
            return false;
        }

        return validationResult.getCartIssues()
                .stream()
                .filter(Objects::nonNull)
                .anyMatch(CartIssue::isBlocking);
    }

    private boolean hasBlockingStoreIssue(
            String storeId,
            CartValidationResult validationResult
    ) {
        if (validationResult == null || storeId == null) {
            return false;
        }

        return validationResult.getStoreIssues(storeId)
                .stream()
                .filter(Objects::nonNull)
                .anyMatch(StoreIssue::isBlocking);
    }

    private BigDecimal calculateItemsSubtotal(
            List<PromotionEligibleItemData> items
    ) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return items.stream()
                .filter(Objects::nonNull)
                .map(item -> safe(item.lineSubtotal()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateDeliveryFeeForStoreBasket(
            CartValidationResult validationResult,
            String storeId
    ) {
        return validationResult.getQuotedDeliveryFee(storeId).orElse(BigDecimal.ZERO);
    }

    private List<String> getPromoCodesForStore(
            RedisCart cart,
            String storeId
    ) {
        if (cart == null || storeId == null) {
            return List.of();
        }
        RedisStoreBasket basket = cart.findBasket(storeId);
        if (basket == null) {
            return List.of();
        }
        return basket.getSelectedPromoCodeStrings();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
