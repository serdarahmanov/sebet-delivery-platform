package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.config.CartFeeProperties;
import com.sebet.cartservice.cart.enums.FreeDeliveryReason;
import com.sebet.cartservice.cart.enums.ProductUnavailableReason;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_calculation.ItemCalculation;
import com.sebet.cartservice.cart.model.cart_calculation.StoreBasketCalculation;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.cart_validation.PromoCodeValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartCalculationService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final CartFeeProperties feeProperties;

    public CartCalculationResult calculate(
            RedisCart redisCart,
            CartValidationResult validationResult
    ) {
        return calculate(redisCart, validationResult, null);
    }

    public CartCalculationResult calculate(
            RedisCart redisCart,
            CartValidationResult validationResult,
            Set<String> affectedStoreIds
    ) {
        List<RedisCartItem> items = getCartItemsOrEmpty(redisCart);
        if (affectedStoreIds != null && !affectedStoreIds.isEmpty()) {
            items = items.stream()
                    .filter(item -> item != null && affectedStoreIds.contains(item.getStoreId()))
                    .toList();
        }

        Map<String, ItemCalculation> itemCalculationsByCartItemId =
                calculateItems(items, validationResult);

        Map<String, StoreBasketCalculation> storeBasketCalculationsByStoreId =
                calculateStoreBaskets(items, validationResult, itemCalculationsByCartItemId);

        return new CartCalculationResult(
                itemCalculationsByCartItemId,
                storeBasketCalculationsByStoreId
        );
    }




    private List<RedisCartItem> getCartItemsOrEmpty(RedisCart redisCart) {
        if (redisCart == null || redisCart.getItems() == null) {
            return List.of();
        }

        return redisCart.getItems();
    }




    private Map<String, ItemCalculation> calculateItems(
            List<RedisCartItem> items,
            CartValidationResult validationResult
    ) {
        Map<String, ItemCalculation> result = new HashMap<>();

        for (RedisCartItem item : items) {
            ItemCalculation itemCalculation =
                    calculateSingleItem(item, validationResult);

            result.put(item.getCartItemId(), itemCalculation);
        }

        return result;
    }





    private ItemCalculation calculateSingleItem(
            RedisCartItem item,
            CartValidationResult validationResult
    ) {
        ProductSnapshot product = validationResult.productsByProductStore()
                .get(new CartValidationResult.ProductStoreKey(item.getProductId(), item.getStoreId()));

        BigDecimal unitPrice = resolveUnitPrice(item, product);
        BigDecimal quantity = safe(item.getQuantity());

        BigDecimal subtotal = unitPrice.multiply(quantity);

        BigDecimal itemDiscountTotal = validationResult.getItemDiscounts(item.getCartItemId())
                .stream()
                .filter(r -> r.discounts() != null)
                .flatMap(r -> r.discounts().stream())
                .map(d -> safe(d.discountAmount()))
                .reduce(ZERO, BigDecimal::add)
                .min(subtotal);

        BigDecimal finalTotal = subtotal.subtract(itemDiscountTotal);

        return new ItemCalculation(
                item.getCartItemId(),
                item.getProductId(),
                item.getStoreId(),
                unitPrice,
                quantity,
                subtotal,
                itemDiscountTotal,
                finalTotal
        );
    }





    private BigDecimal resolveUnitPrice(
            RedisCartItem item,
            ProductSnapshot product
    ) {
        if (product != null && product.currentPrice() != null) {
            return product.currentPrice();
        }

        return ZERO;
    }

    private Map<String, StoreBasketCalculation> calculateStoreBaskets(
            List<RedisCartItem> items,
            CartValidationResult validationResult,
            Map<String, ItemCalculation> itemCalculationsByCartItemId
    ) {
        Map<String, List<RedisCartItem>> itemsByStoreId = items.stream()
                .collect(Collectors.groupingBy(RedisCartItem::getStoreId));

        Map<String, StoreBasketCalculation> result = new HashMap<>();

        for (Map.Entry<String, List<RedisCartItem>> entry : itemsByStoreId.entrySet()) {
            String storeId = entry.getKey();
            List<RedisCartItem> storeItems = entry.getValue();

            StoreBasketCalculation basketCalculation =
                    calculateSingleStoreBasket(
                            storeId,
                            storeItems,
                            validationResult,
                            itemCalculationsByCartItemId
                    );

            result.put(storeId, basketCalculation);
        }

        return result;
    }

    private StoreBasketCalculation calculateSingleStoreBasket(
            String storeId,
            List<RedisCartItem> storeItems,
            CartValidationResult validationResult,
            Map<String, ItemCalculation> itemCalculationsByCartItemId
    ) {
        StoreSnapshot store = validationResult.storesByStoreId().get(storeId);

        BigDecimal itemsSubtotal = ZERO;
        BigDecimal itemsCount = ZERO;
        BigDecimal itemDiscountTotal = ZERO;
        int uniqueItemsCount = 0;

        for (RedisCartItem storeItem : storeItems) {
            if (storeItem == null) {
                continue;
            }
            uniqueItemsCount++;
            itemsCount = itemsCount.add(safe(storeItem.getQuantity()));

            ItemCalculation itemCalculation = itemCalculationsByCartItemId.get(storeItem.getCartItemId());
            if (itemCalculation == null) {
                continue;
            }
            itemsSubtotal = itemsSubtotal.add(safe(itemCalculation.subtotal()));
            itemDiscountTotal = itemDiscountTotal.add(safe(itemCalculation.itemDiscountTotal()));
        }

        BigDecimal promoDiscountTotal =
                calculatePromoDiscount(storeId, itemsSubtotal, validationResult);

        BigDecimal deliveryFee =
                resolveDeliveryFee(storeId, validationResult);

        BigDecimal freeDeliveryDiscount = deliveryFee != null
                ? calculateFreeDeliveryDiscount(storeId, itemsSubtotal, deliveryFee, store, validationResult)
                : ZERO;

        FreeDeliveryReason freeDeliveryReason = deliveryFee != null
                ? resolveFreeDeliveryReason(storeId, freeDeliveryDiscount, validationResult)
                : null;

        BigDecimal serviceFee = safe(feeProperties.getServiceFee());

        BigDecimal totalDiscount = itemDiscountTotal.add(promoDiscountTotal);

        BigDecimal basketTotal = deliveryFee != null
                ? itemsSubtotal
                        .subtract(itemDiscountTotal)
                        .subtract(promoDiscountTotal)
                        .add(deliveryFee)
                        .subtract(freeDeliveryDiscount)
                        .add(serviceFee)
                : null;

        BigDecimal amountToFreeDelivery =
                calculateAmountToFreeDelivery(
                        itemsSubtotal,
                        store,
                        freeDeliveryDiscount
                );

        return new StoreBasketCalculation(
                storeId,
                itemsCount,
                uniqueItemsCount,
                itemsSubtotal,
                itemDiscountTotal,
                promoDiscountTotal,
                totalDiscount,
                deliveryFee,
                freeDeliveryDiscount,
                freeDeliveryReason,
                basketTotal,
                serviceFee,
                amountToFreeDelivery
        );
    }

    private BigDecimal calculatePromoDiscount(
            String storeId,
            BigDecimal itemsSubtotal,
            CartValidationResult validationResult
    ) {
        List<PromoCodeValidationResult> promoResults =
                validationResult.promoResultsByStoreId().getOrDefault(storeId, List.of());

        if (promoResults.isEmpty()) {
            return ZERO;
        }

        BigDecimal total = ZERO;
        for (PromoCodeValidationResult promoResult : promoResults) {
            if (promoResult == null || !promoResult.applied()) {
                continue;
            }
            if (promoResult.type() == null || promoResult.discountValue() == null) {
                continue;
            }

            BigDecimal discount = switch (promoResult.type()) {
                case PERCENTAGE -> calculatePercentageDiscount(
                        itemsSubtotal,
                        promoResult.discountValue()
                );
                case FIXED_AMOUNT -> promoResult.discountValue().min(itemsSubtotal);
                case FREE_DELIVERY -> ZERO;
                case BUY_X_PAY_Y -> ZERO;
            };
            total = total.add(safe(discount));
        }
        return total.min(itemsSubtotal).max(ZERO);
    }






    private BigDecimal calculatePercentageDiscount(
            BigDecimal amount,
            BigDecimal percentage
    ) {
        return amount
                .multiply(percentage)
                .divide(BigDecimal.valueOf(100),2, RoundingMode.HALF_UP);
    }

    private FreeDeliveryReason resolveFreeDeliveryReason(
            String storeId,
            BigDecimal freeDeliveryDiscount,
            CartValidationResult validationResult
    ) {
        if (freeDeliveryDiscount.compareTo(ZERO) <= 0) {
            return FreeDeliveryReason.THRESHOLD_NOT_REACHED;
        }
        boolean promoGranted = validationResult.promoResultsByStoreId()
                .getOrDefault(storeId, List.of()).stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(p -> p.applied()
                        && p.type() == ProductUnavailableReason.PromoCodeType.FREE_DELIVERY);
        return promoGranted
                ? FreeDeliveryReason.PROMO_CODE_FREE_DELIVERY
                : FreeDeliveryReason.THRESHOLD_REACHED;
    }

    private BigDecimal resolveDeliveryFee(String storeId, CartValidationResult validationResult) {
        return validationResult.getQuotedDeliveryFee(storeId).orElse(null);
    }

    private BigDecimal calculateFreeDeliveryDiscount(
            String storeId,
            BigDecimal itemsSubtotal,
            BigDecimal deliveryFee,
            StoreSnapshot store,
            CartValidationResult validationResult
    ) {
        List<PromoCodeValidationResult> promoResults =
                validationResult.promoResultsByStoreId().getOrDefault(storeId, List.of());

        boolean freeDeliveryApplied = promoResults.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(promoResult -> promoResult.applied()
                        && promoResult.type() == ProductUnavailableReason.PromoCodeType.FREE_DELIVERY);
        if (freeDeliveryApplied) {
            return safe(deliveryFee);
        }

        if (store == null || store.freeDeliveryThreshold() == null) {
            return ZERO;
        }

        if (itemsSubtotal.compareTo(store.freeDeliveryThreshold()) >= 0) {
            return deliveryFee;
        }

        return ZERO;
    }

    private BigDecimal calculateAmountToFreeDelivery(
            BigDecimal itemsSubtotal,
            StoreSnapshot store,
            BigDecimal freeDeliveryDiscount
    ) {
        if (freeDeliveryDiscount.compareTo(ZERO) > 0) {
            return ZERO;
        }

        if (store == null || store.freeDeliveryThreshold() == null) {
            return ZERO;
        }

        BigDecimal remaining = store.freeDeliveryThreshold().subtract(itemsSubtotal);

        if (remaining.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        return remaining;
    }

    private BigDecimal safe(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }

        return value;
    }
}
