package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.delivery.service.DeliveryFeeResolver;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueCode;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueScope;
import io.micrometer.core.instrument.Timer;
import com.sebet.cartservice.cart.model.StoreBasketIssue;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationContext;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request.PromotionEvaluationRequest;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionEvaluationResponse;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisCartPromoCode;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import com.sebet.cartservice.cart.product.projection.ProductProjection;
import com.sebet.cartservice.cart.product.projection.ProductProjectionRepository;

import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.DegradedReason;
import com.sebet.cartservice.cart.promotion.client.PromotionClient;

import com.sebet.cartservice.cart.promotion.mapper.PromotionEvaluationRequestMapper;
import com.sebet.cartservice.cart.store.projection.StoreProjection;
import com.sebet.cartservice.cart.store.projection.StoreProjectionRepository;
import com.sebet.cartservice.cart.inventory.projection.InventoryProjection;
import com.sebet.cartservice.cart.inventory.projection.InventoryProjectionRepository;
import com.sebet.cartservice.cart.validation.tools.CartValidationAccumulator;
import com.sebet.cartservice.cart.validation.tools.CartValidationLookupContext;
import com.sebet.cartservice.cart.validation.validators.InventoryCartValidator;
import com.sebet.cartservice.cart.validation.validators.ProductCartValidator;
import com.sebet.cartservice.cart.validation.validators.StoreCartValidator;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartValidationService {

    private final ProductCartValidator productCartValidator;
    private final InventoryCartValidator inventoryCartValidator;
    private final StoreCartValidator storeCartValidator;
    private final ProductProjectionRepository productProjectionRepository;
    private final InventoryProjectionRepository inventoryProjectionRepository;
    private final StoreProjectionRepository storeProjectionRepository;

    private final PromotionClient promotionClient;
    private final PromotionEvaluationRequestMapper promotionEvaluationRequestMapper;
    private final PromotionValidationResultApplier promotionValidationResultApplier;
    private final DeliveryFeeResolver deliveryFeeResolver;
    private final CartMetrics cartMetrics;

    // ---------------------------------------------------------------
    // Public API — all paths are in-memory only.
    // The caller (CartService) is responsible for the single CAS save
    // after all mutations and validation are complete.
    // ---------------------------------------------------------------

    public CartValidationContext validate(RedisCart cart) {
        return validateInternal(cart, null);
    }

    public CartValidationContext validate(RedisCart cart, Set<String> affectedStoreIds) {
        return validateInternal(cart, affectedStoreIds);
    }

    /** Semantic alias for {@link #validate(RedisCart, Set)} — kept for read-path clarity. */
    public CartValidationContext validateReadOnly(RedisCart cart) {
        return validateInternal(cart, null);
    }

    /** Semantic alias for {@link #validate(RedisCart, Set)} — kept for read-path clarity. */
    public CartValidationContext validateReadOnly(RedisCart cart, Set<String> affectedStoreIds) {
        return validateInternal(cart, affectedStoreIds);
    }

    /**
     * Checkout-specific validation. Identical to {@link #validate(RedisCart, Set)} except
     * the delivery step uses {@code DeliveryFeeResolver.resolveForCheckout()}, which:
     * <ul>
     *   <li>Makes a single {@code POST /delivery/checkout/quote} covering all scoped baskets
     *       (ASAP and scheduled) instead of separate availability + fee-quote calls.</li>
     *   <li>Adds {@code DELIVERY_NOT_AVAILABLE} as {@code BLOCKING} when the address is
     *       outside the delivery zone.</li>
     *   <li>Adds {@code DELIVERY_FEE_UNAVAILABLE} as {@code BLOCKING} (not WARNING) when
     *       a quote cannot be returned — checkout must not proceed without a fee.</li>
     * </ul>
     */
    public CartValidationContext validateForCheckout(RedisCart cart, Set<String> affectedStoreIds) {
        Timer.Sample timerSample = cartMetrics.startValidationTimer();
        CartValidationAccumulator accumulator = new CartValidationAccumulator();
        CartValidationLookupContext lookupContext = buildLookupContext(cart, affectedStoreIds);

        productCartValidator.validate(cart, accumulator, lookupContext);
        inventoryCartValidator.validate(cart, accumulator, lookupContext);
        storeCartValidator.validate(cart, accumulator, lookupContext);

        deliveryFeeResolver.resolveForCheckout(cart, accumulator.validationResult(), affectedStoreIds);

        PromotionEvaluationResponse promotionResponse = evaluatePromotions(cart, accumulator);

        if (promotionResponse.degraded()) {
            if (cart != null && cart.getStoreBaskets() != null) {
                cart.getStoreBaskets().stream()
                        .filter(Objects::nonNull)
                        .filter(b -> b.getStoreId() != null)
                        .filter(b -> affectedStoreIds == null
                                || affectedStoreIds.isEmpty()
                                || affectedStoreIds.contains(b.getStoreId()))
                        .forEach(b -> accumulator.validationResult().addStoreBasketIssue(
                                b.getStoreId(),
                                new StoreBasketIssue(
                                        StoreBasketIssueCode.PROMOTION_SERVICE_UNAVAILABLE,
                                        IssueSeverity.WARNING,
                                        StoreBasketIssueScope.BASKET,
                                        "Promotions are temporarily unavailable. Discounts may not be applied.",
                                        b.getStoreId(),
                                        null, null, Map.of()
                                )
                        ));
            }
        }

        promotionValidationResultApplier.apply(promotionResponse, accumulator.validationResult());

        CartValidationContext ctx = new CartValidationContext(
                accumulator.validationResult(),
                accumulator.getPromotionItemDataMap(),
                promotionResponse
        );
        boolean blocked = accumulator.validationResult().hasBlockingIssues();
        log.debug("validation_for_checkout_complete cartId={} degraded={} blockingIssues={}",
                cart != null ? cart.getCartId() : null,
                promotionResponse.degraded(),
                blocked);
        cartMetrics.stopValidationTimer(timerSample, blocked);
        return ctx;
    }

    // ---------------------------------------------------------------
    // Targeted delivery-only refresh — used by setScheduledDelivery()
    // rejection path to avoid re-running projection validators and
    // promotion evaluation when only the delivery quote has changed.
    // ---------------------------------------------------------------

    /**
     * Refreshes the delivery fee in {@code existingResult} in-memory without
     * re-running projection validators or promotion evaluation.
     *
     * <p>Call this when cart items and promo codes have not changed since
     * {@code existingResult} was built, but the basket's delivery quote has been
     * cleared or restored and needs to be resolved again. The caller is responsible
     * for persisting the cart via {@code CartRedisRepository.saveIfVersionMatches}.
     *
     * @param cart            the cart whose basket state has been updated
     * @param existingResult  the result produced by the preceding full {@code validate()} call
     * @param affectedStoreIds stores to scope the delivery resolve to
     */
    public void resolveDeliveryFeeOnly(RedisCart cart,
                                       CartValidationResult existingResult,
                                       Set<String> affectedStoreIds) {
        deliveryFeeResolver.resolve(cart, existingResult, affectedStoreIds);
    }

    // ---------------------------------------------------------------
    // Internal implementation
    // ---------------------------------------------------------------

    private CartValidationContext validateInternal(RedisCart cart, Set<String> affectedStoreIds) {
        Timer.Sample timerSample = cartMetrics.startValidationTimer();
        CartValidationAccumulator accumulator = new CartValidationAccumulator();
        CartValidationLookupContext lookupContext = buildLookupContext(cart, affectedStoreIds);

        productCartValidator.validate(cart, accumulator, lookupContext);
        inventoryCartValidator.validate(cart, accumulator, lookupContext);
        storeCartValidator.validate(cart, accumulator, lookupContext);

        deliveryFeeResolver.resolve(cart, accumulator.validationResult(), affectedStoreIds);

        PromotionEvaluationResponse promotionResponse =
                evaluatePromotions(cart, accumulator);

        if (promotionResponse.degraded()) {
            if (cart != null && cart.getStoreBaskets() != null) {
                cart.getStoreBaskets().stream()
                        .filter(Objects::nonNull)
                        .filter(b -> b.getStoreId() != null)
                        .filter(b -> affectedStoreIds == null
                                || affectedStoreIds.isEmpty()
                                || affectedStoreIds.contains(b.getStoreId()))
                        .forEach(b -> accumulator.validationResult().addStoreBasketIssue(
                                b.getStoreId(),
                                new StoreBasketIssue(
                                        StoreBasketIssueCode.PROMOTION_SERVICE_UNAVAILABLE,
                                        IssueSeverity.WARNING,
                                        StoreBasketIssueScope.BASKET,
                                        "Promotions are temporarily unavailable. Discounts may not be applied.",
                                        b.getStoreId(),
                                        null, null, Map.of()
                                )
                        ));
            }
        }

        promotionValidationResultApplier.apply(
                promotionResponse,
                accumulator.validationResult()
        );

        CartValidationContext ctx = new CartValidationContext(
                accumulator.validationResult(),
                accumulator.getPromotionItemDataMap(),
                promotionResponse
        );
        boolean blocked = accumulator.validationResult().hasBlockingIssues();
        log.debug("validation_complete cartId={} degraded={} blockingIssues={}",
                cart != null ? cart.getCartId() : null,
                promotionResponse.degraded(),
                blocked);
        cartMetrics.stopValidationTimer(timerSample, blocked);
        return ctx;
    }

    private PromotionEvaluationResponse evaluatePromotions(
            RedisCart cart,
            CartValidationAccumulator accumulator
    ) {
        if (shouldSkipPromotionEvaluation(cart, accumulator)) {
            return PromotionEvaluationResponse.empty(cart != null ? cart.getCartId() : null);
        }

        PromotionEvaluationRequest request =
                promotionEvaluationRequestMapper.toRequest(
                        cart,
                        accumulator.validationResult(),
                        accumulator.getPromotionItemDataMap()
                );

        if (request.storeBaskets() == null || request.storeBaskets().isEmpty()) {
            return PromotionEvaluationResponse.empty(
                    cart != null ? cart.getCartId() : null
            );
        }

        PromotionEvaluationResponse response =
                promotionClient.evaluatePromotions(request);

        if (response == null) {
            log.warn("PromotionClient returned null for cartId={}. This is a contract violation inside cart-service.", cart.getCartId());
            return PromotionEvaluationResponse.degraded(cart.getCartId(), DegradedReason.INTERNAL);
        }

        return response;
    }

    private boolean shouldSkipPromotionEvaluation(
            RedisCart cart,
            CartValidationAccumulator accumulator
    ) {
        boolean hasPromotionEligibleItems = accumulator != null
                && accumulator.getPromotionItemDataMap() != null
                && !accumulator.getPromotionItemDataMap().isEmpty();

        boolean hasPromoCodes = cart != null
                && cart.getPromoCodes() != null
                && cart.getPromoCodes().stream()
                .filter(Objects::nonNull)
                .filter(promo -> promo.getState() == PromoCodeState.SELECTED)
                .map(RedisCartPromoCode::getCode)
                .anyMatch(code -> code != null && !code.isBlank());

        return !hasPromotionEligibleItems && !hasPromoCodes;
    }

    private CartValidationLookupContext buildLookupContext(RedisCart cart, Set<String> affectedStoreIds) {
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return new CartValidationLookupContext(Map.of(), Map.of(), Map.of());
        }

        List<RedisCartItem> items = cart.getItems().stream()
                .filter(Objects::nonNull)
                .filter(item -> affectedStoreIds == null || affectedStoreIds.isEmpty() || affectedStoreIds.contains(item.getStoreId()))
                .toList();

        Set<String> productIds = items.stream()
                .map(RedisCartItem::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> storeIds = items.stream()
                .map(RedisCartItem::getStoreId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<CartValidationLookupContext.ProductStoreKey, ProductProjection> productByKey = toProductMap(
                productProjectionRepository.findByProductIdInAndStoreIdIn(productIds, storeIds)
        );
        Map<CartValidationLookupContext.ProductStoreKey, InventoryProjection> inventoryByKey = toInventoryMap(
                inventoryProjectionRepository.findByProductIdInAndStoreIdIn(productIds, storeIds)
        );
        Map<String, StoreProjection> storeById = toStoreMap(
                storeProjectionRepository.findByStoreIdIn(storeIds)
        );

        return new CartValidationLookupContext(productByKey, inventoryByKey, storeById);
    }

    private Map<CartValidationLookupContext.ProductStoreKey, ProductProjection> toProductMap(
            Collection<ProductProjection> projections
    ) {
        Map<CartValidationLookupContext.ProductStoreKey, ProductProjection> map = new HashMap<>();
        for (ProductProjection projection : projections) {
            if (projection == null) {
                continue;
            }
            map.put(
                    new CartValidationLookupContext.ProductStoreKey(
                            projection.getProductId(),
                            projection.getStoreId()
                    ),
                    projection
            );
        }
        return map;
    }

    private Map<CartValidationLookupContext.ProductStoreKey, InventoryProjection> toInventoryMap(
            Collection<InventoryProjection> projections
    ) {
        Map<CartValidationLookupContext.ProductStoreKey, InventoryProjection> map = new HashMap<>();
        for (InventoryProjection projection : projections) {
            if (projection == null) {
                continue;
            }
            map.put(
                    new CartValidationLookupContext.ProductStoreKey(
                            projection.getProductId(),
                            projection.getStoreId()
                    ),
                    projection
            );
        }
        return map;
    }

    private Map<String, StoreProjection> toStoreMap(Collection<StoreProjection> stores) {
        Map<String, StoreProjection> map = new HashMap<>();
        for (StoreProjection store : stores) {
            if (store == null || store.getStoreId() == null) {
                continue;
            }
            map.put(store.getStoreId(), store);
        }
        return map;
    }
}
