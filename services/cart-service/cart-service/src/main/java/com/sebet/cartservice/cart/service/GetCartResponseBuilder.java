package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.model.redis.RedisDeliveryQuote;
import com.sebet.cartservice.cart.dto.getcart.CartAppliedPromoCodeResponse;
import com.sebet.cartservice.cart.dto.getcart.CartBasketSummaryResponse;
import com.sebet.cartservice.cart.dto.getcart.CartIssueResponse;
import com.sebet.cartservice.cart.dto.getcart.CartItemResponse;
import com.sebet.cartservice.cart.dto.getcart.CartSummaryResponse;
import com.sebet.cartservice.cart.dto.getcart.CartStoreBasketIssueResponse;
import com.sebet.cartservice.cart.dto.getcart.StoreBasketSummaryResponse;
import com.sebet.cartservice.cart.dto.getcart.CartStoreIssueResponse;
import com.sebet.cartservice.cart.model.StoreBasketIssue;
import com.sebet.cartservice.cart.enums.ItemIssuesCode;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_calculation.ItemCalculation;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationContext;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.cart_validation.PromoCodeValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.item.ItemIssue;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import com.sebet.cartservice.cart.model.store.StoreIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class GetCartResponseBuilder {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final EnumSet<ItemIssuesCode> HIDE_ITEM_CODES = EnumSet.of(
            ItemIssuesCode.OUT_OF_STOCK,
            ItemIssuesCode.PRODUCT_UNAVAILABLE,
            ItemIssuesCode.PRODUCT_NOT_FOUND,
            ItemIssuesCode.PRODUCT_INACTIVE,
            ItemIssuesCode.PRODUCT_NOT_SELLABLE,
            ItemIssuesCode.INVENTORY_NOT_FOUND,
            ItemIssuesCode.INVENTORY_QUANTITY_UNKNOWN,
            ItemIssuesCode.INVALID_QUANTITY
    );

    public CartSummaryResponse build(
            RedisCart cart,
            CartValidationContext validationContext,
            CartCalculationResult calculationResult
    ) {
        if (cart == null) {
            return new CartSummaryResponse(null, List.of(), 0, List.of(), null, null);
        }

        var validation = validationContext.validationResult();

        List<StoreBasketSummaryResponse> baskets = new ArrayList<>();
        List<RedisStoreBasket> storeBaskets = cart.getStoreBaskets() != null ? cart.getStoreBaskets() : List.of();
        for (RedisStoreBasket redisBasket : storeBaskets) {
            if (redisBasket == null || redisBasket.getStoreId() == null) continue;
            String storeId = redisBasket.getStoreId();
            List<RedisCartItem> allItems = redisBasket.getItems() != null ? redisBasket.getItems() : List.of();
            StoreSnapshot store = validation.storesByStoreId().get(storeId);

            List<CartStoreIssueResponse> storeIssues = validation.getStoreIssues(storeId).stream()
                    .filter(Objects::nonNull)
                    .map(this::toStoreIssue)
                    .toList();
            List<CartStoreBasketIssueResponse> basketIssues = validation.getStoreBasketIssues(storeId).stream()
                    .filter(Objects::nonNull)
                    .map(this::toStoreBasketIssue)
                    .toList();
            boolean blockingStore = validation.getStoreIssues(storeId).stream().anyMatch(StoreIssue::isBlocking);
            boolean blockingBasket = validation.getStoreBasketIssues(storeId).stream().anyMatch(StoreBasketIssue::isBlocking);
            boolean available = store != null && store.exists() && store.open() && !blockingStore;

            List<CartItemResponse> validItems = new ArrayList<>();
            BigDecimal itemsCount = ZERO;
            BigDecimal subtotalBefore = ZERO;
            BigDecimal itemDiscountTotal = ZERO;
            for (RedisCartItem item : allItems) {
                if (item == null || shouldFilterOut(item, validationContext)) {
                    continue;
                }
                ProductSnapshot product = validation.productsByProductStore().get(
                        new CartValidationResult.ProductStoreKey(item.getProductId(), item.getStoreId()));
                BigDecimal quantity = safe(item.getQuantity());
                BigDecimal unitPrice = product != null && product.currentPrice() != null ? product.currentPrice() : ZERO;
                itemsCount = itemsCount.add(quantity);
                subtotalBefore = subtotalBefore.add(unitPrice.multiply(quantity));
                itemDiscountTotal = itemDiscountTotal.add(resolveItemDiscount(item, calculationResult));
                validItems.add(new CartItemResponse(
                        item.getCartItemId(),
                        item.getProductId(),
                        item.getStoreId(),
                        product != null ? product.name() : null,
                        product != null ? product.brandName() : null,
                        product != null ? product.categoryName() : null,
                        product != null ? product.imageUrl() : null,
                        quantity,
                        product != null && product.unit() != null ? product.unit().name() : null,
                        product != null ? product.quantityStep() : null
                ));
            }

            List<CartAppliedPromoCodeResponse> appliedPromoCodes = mapAppliedPromoCodes(storeId, validation.promoResultsByStoreId());
            BigDecimal promoDiscount = appliedPromoCodes.stream()
                    .map(CartAppliedPromoCodeResponse::discountAmount)
                    .map(this::safe)
                    .reduce(ZERO, BigDecimal::add)
                    .min(subtotalBefore);
            BigDecimal subtotalAfter = subtotalBefore
                    .subtract(itemDiscountTotal)
                    .subtract(promoDiscount)
                    .max(ZERO);

            String estimate = null;
            if (available) {
                estimate = formatEstimate(redisBasket.getDeliveryQuote());
            }

            baskets.add(new StoreBasketSummaryResponse(
                    cart.getCartId() + ":" + storeId,
                    storeId,
                    store != null ? store.name() : null,
                    available,
                    !blockingStore && !blockingBasket && !validItems.isEmpty(),
                    estimate,
                    new CartBasketSummaryResponse(itemsCount, subtotalBefore, subtotalAfter, subtotalAfter),
                    appliedPromoCodes,
                    storeIssues,
                    basketIssues,
                    validItems,
                    cart.getCreatedAt(),
                    cart.getUpdatedAt()
            ));
        }

        List<CartIssueResponse> cartIssues = validation.getCartIssues().stream()
                .map(issue -> new CartIssueResponse(
                        issue.code() != null ? issue.code().name() : null,
                        issue.severity() != null ? issue.severity().name() : null,
                        issue.scope() != null ? issue.scope().name() : null,
                        issue.message(),
                        issue.metadata()
                )).toList();

        return new CartSummaryResponse(
                cart.getCartId(),
                baskets,
                baskets.size(),
                cartIssues,
                cart.getCreatedAt(),
                cart.getUpdatedAt()
        );
    }

    private List<CartAppliedPromoCodeResponse> mapAppliedPromoCodes(
            String storeId,
            Map<String, List<PromoCodeValidationResult>> promoResultsByStoreId
    ) {
        return promoResultsByStoreId.getOrDefault(storeId, List.of()).stream()
                .filter(Objects::nonNull)
                .filter(PromoCodeValidationResult::applied)
                .filter(p -> p.issues() == null || p.issues().isEmpty())
                .map(p -> new CartAppliedPromoCodeResponse(
                        p.code(),
                        p.description(),
                        null,
                        p.type() != null ? p.type().name() : null,
                        safe(p.discountValue())
                ))
                .toList();
    }

    private CartStoreIssueResponse toStoreIssue(StoreIssue issue) {
        return new CartStoreIssueResponse(
                issue.code() != null ? issue.code().name() : null,
                issue.severity() != null ? issue.severity().name() : null,
                "STORE",
                issue.message(),
                issue.metadata()
        );
    }

    private CartStoreBasketIssueResponse toStoreBasketIssue(StoreBasketIssue issue) {
        return new CartStoreBasketIssueResponse(
                issue.code() != null ? issue.code().name() : null,
                issue.severity() != null ? issue.severity().name() : null,
                issue.scope() != null ? issue.scope().name() : null,
                issue.message(),
                issue.metadata()
        );
    }

    private boolean shouldFilterOut(RedisCartItem item, CartValidationContext context) {
        List<ItemIssue> issues = context.validationResult().getItemIssues(item.getCartItemId());
        return issues.stream().anyMatch(issue -> issue != null && issue.code() != null && HIDE_ITEM_CODES.contains(issue.code()));
    }

    private String formatEstimate(RedisDeliveryQuote quote) {
        if (quote == null) return null;
        if (quote.getEtaDisplayLabel() != null && !quote.getEtaDisplayLabel().isBlank()) {
            return quote.getEtaDisplayLabel();
        }
        if (quote.getEtaMin() > 0 && quote.getEtaMax() > 0) {
            return quote.getEtaMin() + "–" + quote.getEtaMax() + " min";
        }
        return null;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal resolveItemDiscount(RedisCartItem item, CartCalculationResult calculationResult) {
        if (item == null || calculationResult == null || calculationResult.itemCalculationsByCartItemId() == null) {
            return ZERO;
        }
        ItemCalculation calculation = calculationResult.itemCalculationsByCartItemId().get(item.getCartItemId());
        if (calculation == null) {
            return ZERO;
        }
        return safe(calculation.itemDiscountTotal());
    }
}
