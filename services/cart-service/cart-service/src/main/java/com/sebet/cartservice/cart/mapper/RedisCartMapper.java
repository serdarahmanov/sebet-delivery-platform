package com.sebet.cartservice.cart.mapper;

import com.sebet.cartservice.cart.dto.CartResponse;
import com.sebet.cartservice.cart.enums.FreeDeliveryReason;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.model.*;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_calculation.ItemCalculation;
import com.sebet.cartservice.cart.model.cart_calculation.StoreBasketCalculation;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.cart_validation.PromoCodeValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RedisCartMapper {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public CartResponse toCartResponse(
            RedisCart redisCart,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult
    ) {
        String cartId = resolveCartId(redisCart);
        Instant createdAt = resolveCreatedAt(redisCart);
        Instant updatedAt = resolveUpdatedAt(redisCart);

        List<RedisCartItem> items = getCartItemsOrEmpty(redisCart);

        Map<String, List<RedisCartItem>> itemsByStoreId =
                groupItemsByStoreId(items);

        List<CartStoreBasket> storeBaskets =
                mapStoreBaskets(
                        cartId,
                        createdAt,
                        updatedAt,
                        itemsByStoreId,
                        validationResult,
                        calculationResult
                );

        List<CartIssue> cartIssues =
                validationResult.cartIssues() == null
                        ? List.of()
                        : validationResult.cartIssues();

        BigDecimal totalBasketsCount =
                calculateTotalBasketsCount(storeBaskets);

        return new CartResponse(
                cartId,
                storeBaskets,
                totalBasketsCount,
                cartIssues,
                createdAt,
                updatedAt
        );
    }







    private List<CartStoreBasket> mapStoreBaskets(
            String cartId,
            Instant createdAt,
            Instant updatedAt,
            Map<String, List<RedisCartItem>> itemsByStoreId,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult
    ) {
        return itemsByStoreId.entrySet()
                .stream()
                .map(entry -> mapStoreBasket(
                        cartId,
                        entry.getKey(),
                        entry.getValue(),
                        createdAt,
                        updatedAt,
                        validationResult,
                        calculationResult
                ))
                .toList();
    }

    private CartStoreBasket mapStoreBasket(
            String cartId,
            String storeId,
            List<RedisCartItem> redisItems,
            Instant createdAt,
            Instant updatedAt,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult
    ) {
        StoreSnapshot store =
                validationResult.storesByStoreId().get(storeId);

        StoreBasketCalculation basketCalculation =
                calculationResult.storeBasketCalculationsByStoreId().get(storeId);

        List<CartItem> items = redisItems.stream()
                .map(redisItem -> mapCartItem(
                        redisItem,
                        validationResult,
                        calculationResult,
                        updatedAt
                ))
                .toList();

        StoreBasketSummary summary =
                mapBasketSummary(basketCalculation);

        StoreBasketPromoCode promoCode =
                mapBasketPromoCode(storeId, validationResult, calculationResult);

        List<StoreBasketIssue> issues =
                validationResult.storeBasketIssuesByStoreId()
                        .getOrDefault(storeId, List.of());

        boolean isAvailable = isStoreAvailable(store);

        boolean canCheckout =
                canStoreBasketCheckout(issues, items, promoCode);

        return new CartStoreBasket(
                buildBasketId(cartId, storeId),
                storeId,
                store != null ? store.name() : null,
                isAvailable,
                canCheckout,
                null,
                summary,
                promoCode,
                issues,
                items,
                createdAt,
                updatedAt
        );
    }

    private CartItem mapCartItem(
            RedisCartItem redisItem,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult,
            Instant cartUpdatedAt
    ) {
        ProductSnapshot product =
                validationResult.productsByProductId()
                        .get(redisItem.getProductId());

        ItemCalculation calculation =
                calculationResult.itemCalculationsByCartItemId()
                        .get(redisItem.getCartItemId());

        List<CartItemIssues> issues =
                validationResult.itemIssuesByCartItemId()
                        .getOrDefault(redisItem.getCartItemId(), List.of());

        boolean available =
                product != null && product.exists() && product.available();

        boolean isValid =
                !hasBlockingItemIssues(issues);

        return new CartItem(
                redisItem.getCartItemId(),
                redisItem.getProductId(),
                product != null ? product.sku() : null,

                redisItem.getStoreId(),

                product != null ? product.name() : null,
                product != null ? product.brandName() : null,
                product != null ? product.categoryName() : null,
                product != null ? product.imageUrl() : null,

                calculation != null ? calculation.quantity() : redisItem.getQuantity(),

                product != null ? product.unit() : null,
                product != null ? product.minQuantity() : null,
                product != null ? product.maxQuantity() : null,
                product != null ? product.quantityStep() : null,

                calculation != null ? calculation.unitPrice() : ZERO,
                product != null ? product.originalPrice() : null,

                calculation != null ? calculation.subtotal() : ZERO,
                calculation != null ? calculation.itemDiscountTotal() : ZERO,
                calculation != null ? calculation.finalTotal() : ZERO,

                List.of(), // discounts, later for item-level campaigns

                available,
                product != null && product.availableQuantity() != null
                        ? product.availableQuantity().intValue()
                        : null,
                product != null ? product.stockStatus() : null,

                isValid,
                issues,

                redisItem.getAddedAt(),
                redisItem.getUpdatedAt() != null
                        ? redisItem.getUpdatedAt()
                        : cartUpdatedAt
        );
    }

    private StoreBasketSummary mapBasketSummary(
            StoreBasketCalculation calculation
    ) {
        if (calculation == null) {
            return new StoreBasketSummary(
                    ZERO,
                    ZERO,
                    ZERO,
                    ZERO,
                    ZERO,
                    ZERO,
                    ZERO,
                    null,
                    ZERO,
                    ZERO,
                    ZERO
            );
        }

        BigDecimal deliveryFeeBeforeDiscount = safe(calculation.deliveryFee());
        BigDecimal deliveryDiscountTotal = safe(calculation.freeDeliveryDiscount());
        BigDecimal deliveryFee = deliveryFeeBeforeDiscount.subtract(deliveryDiscountTotal).max(ZERO);

        FreeDeliveryReason freeDeliveryReason = deliveryDiscountTotal.compareTo(ZERO) > 0
                ? FreeDeliveryReason.THRESHOLD_REACHED
                : FreeDeliveryReason.THRESHOLD_NOT_REACHED;

        StoreBasketDeliverySummary deliverySummary = new StoreBasketDeliverySummary(
                deliveryFeeBeforeDiscount,
                deliveryFee,
                deliveryDiscountTotal,
                freeDeliveryReason
        );

        return new StoreBasketSummary(
                safe(calculation.itemsCount()),
                safe(calculation.uniqueItemsCount()),
                safe(calculation.itemsSubtotal()),
                safe(calculation.itemDiscountTotal()),
                safe(calculation.promoDiscountTotal()),
                deliveryDiscountTotal,
                safe(calculation.totalDiscount()),
                deliverySummary,
                safe(calculation.serviceFee()),
                safe(calculation.smallOrderFee()),
                safe(calculation.basketTotal())
        );
    }

    private StoreBasketPromoCode mapBasketPromoCode(
            String storeId,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult
    ) {
        PromoCodeValidationResult promoResult =
                validationResult.promoResultsByStoreId().get(storeId);

        if (promoResult == null) {
            return null;
        }

        StoreBasketCalculation basketCalculation =
                calculationResult.storeBasketCalculationsByStoreId().get(storeId);

        BigDecimal discountTotal =
                basketCalculation != null
                        ? basketCalculation.promoDiscountTotal()
                        : ZERO;

        return new StoreBasketPromoCode(
                promoResult.code(),
                promoResult.applied(),
                promoResult.type(),
                discountTotal,
                promoResult.description(),
                promoResult.issues()
        );
    }

    private Map<String, List<RedisCartItem>> groupItemsByStoreId(
            List<RedisCartItem> items
    ) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }

        return items.stream()
                .collect(Collectors.groupingBy(RedisCartItem::getStoreId));
    }

    private BigDecimal calculateTotalBasketsCount(
            List<CartStoreBasket> storeBaskets
    ) {
        if (storeBaskets == null) {
            return ZERO;
        }

        return BigDecimal.valueOf(storeBaskets.size());
    }

    private boolean isStoreAvailable(StoreSnapshot store) {
        return store != null && store.exists() && store.open();
    }

    private boolean canStoreBasketCheckout(
            List<StoreBasketIssue> storeIssues,
            List<CartItem> items,
            StoreBasketPromoCode promoCode
    ) {
        if (hasBlockingStoreBasketIssues(storeIssues)) {
            return false;
        }

        boolean hasBlockingItem = items.stream()
                .anyMatch(item -> Boolean.FALSE.equals(item.isValid()));

        if (hasBlockingItem) {
            return false;
        }

        if (promoCode != null && promoCode.issues() != null) {
            return promoCode.issues()
                    .stream()
                    .noneMatch(issue -> issue.severity() == IssueSeverity.BLOCKING);
        }

        return true;
    }

    private boolean hasBlockingItemIssues(List<CartItemIssues> issues) {
        if (issues == null) {
            return false;
        }

        return issues.stream()
                .anyMatch(issue -> issue.severity() == IssueSeverity.BLOCKING);
    }

    private boolean hasBlockingStoreBasketIssues(List<StoreBasketIssue> issues) {
        if (issues == null) {
            return false;
        }

        return issues.stream()
                .anyMatch(issue -> issue.severity() == IssueSeverity.BLOCKING);
    }

    private String buildBasketId(String cartId, String storeId) {
        return cartId + ":" + storeId;
    }

    private String resolveCartId(RedisCart redisCart) {
        if (redisCart == null || redisCart.getCartId() == null) {
            return null;
        }

        return redisCart.getCartId();
    }



    private Instant resolveCreatedAt(RedisCart redisCart) {
        if (redisCart == null) {
            return null;
        }

        if (redisCart.getCreatedAt() != null) {
            return redisCart.getCreatedAt();
        }

        return redisCart.getUpdatedAt();
    }



    private Instant resolveUpdatedAt(RedisCart redisCart) {
        if (redisCart == null) {
            return null;
        }

        return redisCart.getUpdatedAt();
    }


    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }    private List<RedisCartItem> getCartItemsOrEmpty(RedisCart redisCart) {
        if (redisCart == null || redisCart.getItems() == null) {
            return List.of();
        }

        return redisCart.getItems();
    }
}