package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.enums.ProductUnavailableReason;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_calculation.CartTotalCalculation;
import com.sebet.cartservice.cart.model.cart_calculation.ItemCalculation;
import com.sebet.cartservice.cart.model.cart_calculation.StoreBasketCalculation;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.cart_validation.PromoCodeValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CartCalculationService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public CartCalculationResult calculate(
            RedisCart redisCart,
            CartValidationResult validationResult
    ) {
        List<RedisCartItem> items = getCartItemsOrEmpty(redisCart);

        Map<String, ItemCalculation> itemCalculationsByCartItemId =
                calculateItems(items, validationResult);

        Map<String, StoreBasketCalculation> storeBasketCalculationsByStoreId =
                calculateStoreBaskets(items, validationResult, itemCalculationsByCartItemId);

        CartTotalCalculation cartTotal =
                calculateCartTotal(storeBasketCalculationsByStoreId);

        return new CartCalculationResult(
                itemCalculationsByCartItemId,
                storeBasketCalculationsByStoreId,
                cartTotal
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
        ProductSnapshot product = validationResult.productsByProductId()
                .get(item.getProductId());

        BigDecimal unitPrice = resolveUnitPrice(item, product);
        BigDecimal quantity = safe(item.getQuantity());

        BigDecimal subtotal = unitPrice.multiply(quantity);

        BigDecimal itemDiscountTotal = ZERO;


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

        if (item.getUnitPriceSnapshot() != null) {
            return item.getUnitPriceSnapshot();
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

        BigDecimal itemsSubtotal = storeItems.stream()
                .map(item -> itemCalculationsByCartItemId.get(item.getCartItemId()))
                .map(ItemCalculation::subtotal)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal itemsCount = storeItems.stream()
                .map(RedisCartItem::getQuantity)
                .map(this::safe)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal uniqueItemsCount = BigDecimal.valueOf(storeItems.size());
        BigDecimal itemDiscountTotal = storeItems.stream()
                .map(item -> itemCalculationsByCartItemId.get(item.getCartItemId()))
                .map(ItemCalculation::itemDiscountTotal)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal promoDiscountTotal =
                calculatePromoDiscount(storeId, itemsSubtotal, validationResult);

        BigDecimal deliveryFee =
                resolveDeliveryFee(store);

        BigDecimal freeDeliveryDiscount =
                calculateFreeDeliveryDiscount(
                        storeId,
                        itemsSubtotal,
                        deliveryFee,
                        store,
                        validationResult
                );

        BigDecimal serviceFee = ZERO;
        BigDecimal smallOrderFee = ZERO;

        BigDecimal totalDiscount = itemDiscountTotal.add(promoDiscountTotal);

        BigDecimal basketTotal = itemsSubtotal
                .subtract(itemDiscountTotal)
                .subtract(promoDiscountTotal)
                .add(deliveryFee)
                .subtract(freeDeliveryDiscount)
                .add(serviceFee)
                .add(smallOrderFee);

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
                basketTotal,
                serviceFee,
                smallOrderFee,
                amountToFreeDelivery
        );
    }

    private BigDecimal calculatePromoDiscount(
            String storeId,
            BigDecimal itemsSubtotal,
            CartValidationResult validationResult
    ) {
        PromoCodeValidationResult promoResult =
                validationResult.promoResultsByStoreId().get(storeId);

        if (promoResult == null) {
            return ZERO;
        }

        if (!promoResult.exists() || !promoResult.valid() || !promoResult.applied()) {
            return ZERO;
        }

        if (promoResult.type() == null || promoResult.discountValue() == null) {
            return ZERO;
        }


        return switch (promoResult.type()) {
            case PERCENTAGE -> calculatePercentageDiscount(
                    itemsSubtotal,
                    promoResult.discountValue()
            );

            case FIXED_AMOUNT -> promoResult.discountValue().min(itemsSubtotal);

            case FREE_DELIVERY -> ZERO;

            case BUY_X_PAY_Y -> ZERO;
        };
    }






    private BigDecimal calculatePercentageDiscount(
            BigDecimal amount,
            BigDecimal percentage
    ) {
        return amount
                .multiply(percentage)
                .divide(BigDecimal.valueOf(100),2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveDeliveryFee(StoreSnapshot store) {
        if (store == null || store.deliveryFee() == null) {
            return ZERO;
        }

        return store.deliveryFee();
    }

    private BigDecimal calculateFreeDeliveryDiscount(
            String storeId,
            BigDecimal itemsSubtotal,
            BigDecimal deliveryFee,
            StoreSnapshot store,
            CartValidationResult validationResult
    ) {
        PromoCodeValidationResult promoResult =
                validationResult.promoResultsByStoreId().get(storeId);

        if (promoResult != null
                && promoResult.exists()
                && promoResult.valid()
                && promoResult.applied()
                && promoResult.type() == ProductUnavailableReason.PromoCodeType.FREE_DELIVERY) {
            return deliveryFee;
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

    private CartTotalCalculation calculateCartTotal(
            Map<String, StoreBasketCalculation> basketCalculationsByStoreId
    ) {
        BigDecimal itemsSubtotal = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::itemsSubtotal)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal itemDiscountTotal = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::itemDiscountTotal)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal promoDiscountTotal = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::promoDiscountTotal)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalDiscount = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::totalDiscount)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal deliveryFeeTotal = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::deliveryFee)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal freeDeliveryDiscountTotal = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::freeDeliveryDiscount)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal serviceFeeTotal = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::serviceFee)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal smallOrderFeeTotal = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::smallOrderFee)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal grandTotal = basketCalculationsByStoreId.values().stream()
                .map(StoreBasketCalculation::basketTotal)
                .reduce(ZERO, BigDecimal::add);

        return new CartTotalCalculation(
                itemsSubtotal,
                itemDiscountTotal,
                promoDiscountTotal,
                totalDiscount,
                deliveryFeeTotal,
                freeDeliveryDiscountTotal,
                serviceFeeTotal,
                smallOrderFeeTotal,
                grandTotal
        );
    }

    private BigDecimal safe(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }

        return value;
    }
}
