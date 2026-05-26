package com.sebet.cartservice.cart.mapper;


import com.sebet.cartservice.cart.dto.Cart;
import com.sebet.cartservice.cart.enums.FreeDeliveryReason;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.ItemDiscountType;
import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.enums.ScheduleType;
import com.sebet.cartservice.cart.model.CartDeliveryAddress;
import com.sebet.cartservice.cart.model.CartDeliveryOption;
import com.sebet.cartservice.cart.model.CartDeliveryQuote;
import com.sebet.cartservice.cart.model.redis.RedisDeliveryQuote;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import com.sebet.cartservice.cart.model.CartIssue;
import com.sebet.cartservice.cart.model.CartItem;
import com.sebet.cartservice.cart.model.CartItemDiscount;
import com.sebet.cartservice.cart.model.CartPromoCodeResponse;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoCodeType;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionItemDiscountResult;
import com.sebet.cartservice.cart.model.StoreBasketDeliverySummary;
import com.sebet.cartservice.cart.model.StoreBasketIssue;
import com.sebet.cartservice.cart.model.StoreBasketSummary;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_calculation.ItemCalculation;
import com.sebet.cartservice.cart.model.cart_calculation.StoreBasketCalculation;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.cart_validation.PromoCodeValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.item.ItemIssue;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisCartPromoCode;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Component
public class RedisCartMapper {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public Cart toCart(
            RedisCart redisCart,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult
    ) {
        return toCart(redisCart, validationResult, calculationResult, null);
    }

    public Cart toCart(
            RedisCart redisCart,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult,
            Set<String> affectedStoreIds
    ) {
        String cartId = redisCart != null ? redisCart.getCartId() : null;
        Instant createdAt = redisCart != null ? redisCart.getCreatedAt() : null;
        Instant updatedAt = redisCart != null ? redisCart.getUpdatedAt() : null;

        List<RedisStoreBasket> baskets = redisCart == null || redisCart.getStoreBaskets() == null
                ? List.of()
                : redisCart.getStoreBaskets();
        if (affectedStoreIds != null && !affectedStoreIds.isEmpty()) {
            baskets = baskets.stream()
                    .filter(b -> b != null && affectedStoreIds.contains(b.getStoreId()))
                    .toList();
        }

        List<StoreBasket> storeBaskets = baskets.stream()
                .filter(b -> b != null && b.getStoreId() != null)
                .map(b -> mapStoreBasket(
                        redisCart,
                        cartId,
                        b.getStoreId(),
                        b.getItems() != null ? b.getItems() : List.of(),
                        createdAt,
                        updatedAt,
                        validationResult,
                        calculationResult
                ))
                .toList();

        List<CartIssue> cartIssues = validationResult == null
                ? List.of()
                : validationResult.getCartIssues();

        return new Cart(
                cartId,
                storeBaskets,
                BigDecimal.valueOf(storeBaskets.size()),
                cartIssues,
                createdAt,
                updatedAt
        );
    }

    private StoreBasket mapStoreBasket(
            RedisCart redisCart,
            String cartId,
            String storeId,
            List<RedisCartItem> redisItems,
            Instant createdAt,
            Instant updatedAt,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult
    ) {
        StoreSnapshot store = validationResult == null
                ? null
                : validationResult.storesByStoreId().get(storeId);

        StoreBasketCalculation basketCalculation = calculationResult == null
                ? null
                : calculationResult.storeBasketCalculationsByStoreId().get(storeId);

        List<CartItem> items = redisItems.stream()
                .map(item -> mapCartItem(item, validationResult, calculationResult, updatedAt))
                .toList();

        List<StoreBasketIssue> issues = validationResult == null
                ? List.of()
                : validationResult.storeBasketIssuesByStoreId().getOrDefault(storeId, List.of());

        List<com.sebet.cartservice.cart.model.store.StoreIssue> storeIssues = validationResult == null
                ? List.of()
                : validationResult.getStoreIssues(storeId);

        List<CartPromoCodeResponse> promoCodes = mapBasketPromoCodes(storeId, redisCart, validationResult);

        String addressId = redisCart != null ? redisCart.getBasketAddress(storeId) : null;

        com.sebet.cartservice.cart.model.redis.RedisStoreBasket redisBasket =
                redisCart != null ? redisCart.findBasket(storeId) : null;
        CartDeliveryQuote deliveryQuote = mapDeliveryQuote(redisBasket);
        List<CartDeliveryOption> availableDeliveryOptions = mapDeliveryOptions(redisBasket);
        String selectedDeliveryMethodId = redisBasket != null ? redisBasket.getSelectedDeliveryMethodId() : null;

        ScheduleType scheduleType = redisBasket != null && redisBasket.getScheduleType() != null
                ? redisBasket.getScheduleType() : ScheduleType.ASAP;

        return new StoreBasket(
                cartId + ":" + storeId,
                storeId,
                store != null ? store.name() : null,
                store != null && store.exists() && store.open(),
                canStoreBasketCheckout(issues, items),
                addressId,
                deliveryQuote,
                selectedDeliveryMethodId,
                availableDeliveryOptions,
                scheduleType,
                redisBasket != null ? redisBasket.getScheduledFor() : null,
                mapBasketSummary(basketCalculation),
                promoCodes,
                issues,
                storeIssues,
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
        ProductSnapshot product = validationResult == null
                ? null
                : validationResult.productsByProductStore().get(
                        new CartValidationResult.ProductStoreKey(redisItem.getProductId(), redisItem.getStoreId()));

        ItemCalculation calculation = calculationResult == null
                ? null
                : calculationResult.itemCalculationsByCartItemId().get(redisItem.getCartItemId());

        List<ItemIssue> issues = validationResult == null
                ? List.of()
                : validationResult.itemIssuesByCartItemId().getOrDefault(redisItem.getCartItemId(), List.of());

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
                toItemDiscounts(redisItem.getCartItemId(), validationResult, calculation != null ? calculation.unitPrice() : ZERO),
                product != null && product.available(),
                product != null && product.availableQuantity() != null ? product.availableQuantity().intValue() : null,
                product != null ? product.stockStatus() : null,
                !hasBlockingItemIssues(issues),
                issues,
                redisItem.getAddedAt(),
                redisItem.getUpdatedAt() != null ? redisItem.getUpdatedAt() : cartUpdatedAt
        );
    }

    private StoreBasketSummary mapBasketSummary(StoreBasketCalculation calculation) {
        if (calculation == null) {
            return new StoreBasketSummary(
                    ZERO, 0, ZERO, ZERO, ZERO, ZERO, ZERO, null, ZERO, null
            );
        }

        BigDecimal deliveryFee = calculation.deliveryFee(); // null means fee is unknown
        BigDecimal deliveryDiscount = safe(calculation.freeDeliveryDiscount());
        BigDecimal deliveryAfter = deliveryFee != null
                ? deliveryFee.subtract(deliveryDiscount).max(ZERO)
                : null;

        StoreBasketDeliverySummary deliverySummary = new StoreBasketDeliverySummary(
                deliveryFee,
                deliveryAfter,
                deliveryDiscount,
                calculation.freeDeliveryReason() != null
                        ? calculation.freeDeliveryReason()
                        : FreeDeliveryReason.THRESHOLD_NOT_REACHED
        );

        return new StoreBasketSummary(
                safe(calculation.itemsCount()),
                calculation.uniqueItemsCount(),
                safe(calculation.itemsSubtotal()),
                safe(calculation.itemDiscountTotal()),
                safe(calculation.promoDiscountTotal()),
                deliveryDiscount,
                safe(calculation.totalDiscount()),
                deliverySummary,
                safe(calculation.serviceFee()),
                calculation.basketTotal() // null if delivery fee unknown
        );
    }

    private List<CartPromoCodeResponse> mapBasketPromoCodes(
            String storeId,
            RedisCart redisCart,
            CartValidationResult validationResult
    ) {
        java.util.Map<String, PromoCodeValidationResult> byCode = new java.util.HashMap<>();
        java.util.Map<String, PromoCodeState> statesByCode = new java.util.HashMap<>();

        if (redisCart != null) {
            RedisStoreBasket basket = redisCart.findBasket(storeId);
            if (basket != null && basket.getPromoCodes() != null) {
                for (RedisCartPromoCode promo : basket.getPromoCodes()) {
                    if (promo == null || promo.getCode() == null) continue;
                    statesByCode.put(promo.getCode().toUpperCase(), promo.getState() == null ? PromoCodeState.SAVED : promo.getState());
                }
            }
        }

        if (validationResult != null) {
            for (PromoCodeValidationResult promo : validationResult.promoResultsByStoreId().getOrDefault(storeId, List.of())) {
                if (promo != null && promo.code() != null) {
                    byCode.put(promo.code().toUpperCase(), promo);
                }
            }
        }

        for (String code : statesByCode.keySet()) {
            byCode.putIfAbsent(code, new PromoCodeValidationResult(
                    storeId,
                    code,
                    null,
                    null,
                    statesByCode.get(code),
                    statesByCode.get(code) == PromoCodeState.SELECTED,
                    false,
                    false,
                    ZERO,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of()
            ));
        }

        return byCode.values().stream().map(promo -> {
            PromoCodeState state = statesByCode.getOrDefault(
                    promo.code() == null ? "" : promo.code().toUpperCase(),
                    promo.state() == null ? PromoCodeState.SAVED : promo.state()
            );
            boolean selected = state == PromoCodeState.SELECTED;
            return new CartPromoCodeResponse(
                    promo.code(),
                    promo.description(),
                    null,
                    toResponseType(promo.type()),
                    promo.selectionType(),
                    state,
                    selected,
                    promo.canBeSelected(),
                    promo.applied(),
                    safe(promo.discountValue()),
                    promo.missingAmountToActivate(),
                    promo.expiresAt(),
                    promo.usageLimit(),
                    promo.usedCount(),
                    null,
                    promo.issues()
            );
        }).toList();
    }

    private List<CartItemDiscount> toItemDiscounts(
            String cartItemId,
            CartValidationResult validationResult,
            BigDecimal unitPrice
    ) {
        if (validationResult == null) {
            return List.of();
        }
        return validationResult.getItemDiscounts(cartItemId).stream()
                .filter(r -> r.discounts() != null)
                .flatMap(r -> r.discounts().stream())
                .filter(java.util.Objects::nonNull)
                .map(d -> new CartItemDiscount(
                        toItemDiscountType(d.type()),
                        d.name(),
                        safe(d.discountAmount()),
                        unitPrice,
                        unitPrice.subtract(safe(d.discountAmount())).max(ZERO),
                        null,
                        null
                ))
                .toList();
    }

    private ItemDiscountType toItemDiscountType(PromoCodeType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case PERCENTAGE -> ItemDiscountType.PERCENTAGE;
            case FIXED_AMOUNT -> ItemDiscountType.FIXED_AMOUNT;
            case BUY_X_PAY_Y -> ItemDiscountType.BUY_X_PAY_Y;
            case FREE_DELIVERY -> null;
        };
    }

    private boolean canStoreBasketCheckout(
            List<StoreBasketIssue> storeIssues,
            List<CartItem> items
    ) {
        boolean hasBlockingStoreIssue = storeIssues != null
                && storeIssues.stream().anyMatch(StoreBasketIssue::isBlocking);
        if (hasBlockingStoreIssue) {
            return false;
        }

        boolean hasBlockingItem = items != null
                && items.stream().anyMatch(item -> Boolean.FALSE.equals(item.isValid()));
        if (hasBlockingItem) {
            return false;
        }

        return true;
    }

    private com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoCodeType toResponseType(
            com.sebet.cartservice.cart.enums.ProductUnavailableReason.PromoCodeType type
    ) {
        if (type == null) {
            return null;
        }
        return com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoCodeType.valueOf(type.name());
    }

    private CartDeliveryQuote mapDeliveryQuote(RedisStoreBasket basket) {
        if (basket == null) return null;
        RedisDeliveryQuote q = basket.getDeliveryQuote();
        if (q == null) return null;
        return new CartDeliveryQuote(
                q.getQuoteId(),
                q.getExpiresAt(),
                new CartDeliveryQuote.Fee(q.getAmount(), q.getCurrency(), q.getDisplay()),
                new CartDeliveryQuote.Eta(q.getEtaMin(), q.getEtaMax(), q.getEtaDisplayLabel())
        );
    }

    private List<CartDeliveryOption> mapDeliveryOptions(RedisStoreBasket basket) {
        if (basket == null) return List.of();
        RedisDeliveryQuote q = basket.getDeliveryQuote();
        if (q == null || q.getAvailableOptions() == null) return List.of();
        return q.getAvailableOptions().stream()
                .filter(o -> o != null && o.getMethodId() != null)
                .map(o -> new CartDeliveryOption(
                        o.getMethodId(),
                        o.getLabel(),
                        o.getEtaMin(),
                        o.getEtaMax(),
                        o.getEtaDisplayLabel(),
                        o.getFee(),
                        o.getCurrency(),
                        o.getFeeDisplay()
                ))
                .toList();
    }

    private boolean hasBlockingItemIssues(List<ItemIssue> issues) {
        return issues != null && issues.stream().anyMatch(ItemIssue::isBlocking);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}
