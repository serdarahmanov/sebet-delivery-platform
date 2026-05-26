package com.sebet.cartservice.cart.delivery.service;

import com.sebet.cartservice.cart.delivery.client.DeliveryAvailabilityClient;
import com.sebet.cartservice.cart.delivery.dto.DeliveryCheckoutQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryCheckoutQuoteResponse;
import com.sebet.cartservice.cart.delivery.dto.DeliveryCheckoutQuoteResponse.BasketCheckoutResult;
import com.sebet.cartservice.cart.delivery.dto.DeliveryFeeQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryFeeQuoteResponse;
import com.sebet.cartservice.cart.delivery.dto.DeliveryFeeQuoteResponse.AvailableDeliveryOption;
import com.sebet.cartservice.cart.delivery.dto.DeliveryFeeQuoteResponse.BasketFeeResult;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.ScheduleType;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueCode;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueScope;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.model.StoreBasketIssue;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult.ProductStoreKey;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisDeliveryOption;
import com.sebet.cartservice.cart.model.redis.RedisDeliveryQuote;
import com.sebet.cartservice.cart.delivery.dto.DeliveryScheduleQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryScheduleQuoteResponse;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryFeeResolver {

    private final DeliveryAvailabilityClient deliveryAvailabilityClient;
    private final CartMetrics cartMetrics;

    @Value("${app.fees.currency:TMT}")
    private String currency;

    // ---------------------------------------------------------------
    // Standard resolve — used by every non-checkout validate() call.
    // Adds DELIVERY_FEE_UNAVAILABLE as WARNING when fee is missing.
    // ---------------------------------------------------------------

    /**
     * Resolves delivery fees for all scoped baskets, updating the cart object in-memory only.
     * The caller is responsible for persisting the cart via
     * {@code CartRedisRepository.saveIfVersionMatches} after all mutations are complete.
     */
    public void resolve(RedisCart cart, CartValidationResult result, Set<String> affectedStoreIds) {
        if (cart == null || cart.getStoreBaskets() == null || cart.getStoreBaskets().isEmpty()) {
            return;
        }

        List<RedisStoreBasket> scopedBaskets = cart.getStoreBaskets().stream()
                .filter(b -> b != null && b.getStoreId() != null)
                .filter(b -> affectedStoreIds == null || affectedStoreIds.isEmpty()
                        || affectedStoreIds.contains(b.getStoreId()))
                .toList();

        for (RedisStoreBasket basket : scopedBaskets) {
            if (basket.getAddressId() == null || basket.getAddressId().isBlank()) {
                result.addStoreBasketIssue(basket.getStoreId(), new StoreBasketIssue(
                        StoreBasketIssueCode.DELIVERY_ADDRESS_MISSING,
                        IssueSeverity.WARNING,
                        StoreBasketIssueScope.DELIVERY_ADDRESS,
                        "No delivery address set for this basket.",
                        basket.getStoreId(),
                        null, null, Map.of()
                ));
            }
        }

        List<RedisStoreBasket> basketsWithAddress = scopedBaskets.stream()
                .filter(b -> b.getAddressId() != null && !b.getAddressId().isBlank())
                .toList();

        if (basketsWithAddress.isEmpty()) return;

        List<RedisStoreBasket> cached = new ArrayList<>();
        List<RedisStoreBasket> asapNeedsFetch = new ArrayList<>();
        List<RedisStoreBasket> scheduledNeedsFetch = new ArrayList<>();

        for (RedisStoreBasket basket : basketsWithAddress) {
            if (basket.isQuoteValid()) {
                cached.add(basket);
            } else if (basket.isScheduled()) {
                if (isScheduledSlotStale(basket)) {
                    log.info("scheduled_slot_expired storeId={} scheduledFor={} — resetting to ASAP",
                            basket.getStoreId(), basket.getScheduledFor());
                    basket.setScheduleType(ScheduleType.ASAP);
                    basket.setScheduledFor(null);
                    basket.clearDeliveryQuote();
                    result.addStoreBasketIssue(basket.getStoreId(), new StoreBasketIssue(
                            StoreBasketIssueCode.SCHEDULED_SLOT_EXPIRED,
                            IssueSeverity.WARNING,
                            StoreBasketIssueScope.DELIVERY,
                            "Your scheduled delivery slot has passed. Switched to standard delivery.",
                            basket.getStoreId(),
                            null, null, Map.of()
                    ));
                    asapNeedsFetch.add(basket);
                } else {
                    scheduledNeedsFetch.add(basket);
                }
            } else {
                asapNeedsFetch.add(basket);
            }
        }

        for (RedisStoreBasket basket : cached) {
            result.putQuotedDeliveryFee(basket.getStoreId(), basket.getDeliveryQuote().getAmount());
            cartMetrics.recordDeliveryQuoteCacheHit(basket.getStoreId());
        }

        if (!asapNeedsFetch.isEmpty()) {
            fetchAsapQuotes(cart, asapNeedsFetch, result);
        }

        for (RedisStoreBasket basket : scheduledNeedsFetch) {
            fetchScheduledQuote(basket, result);
        }
    }

    // ---------------------------------------------------------------
    // Checkout resolve — used only by validateForCheckout().
    // Single unified HTTP call for all baskets (ASAP + scheduled).
    // Adds DELIVERY_NOT_AVAILABLE as BLOCKING when address is out of zone.
    // Adds DELIVERY_FEE_UNAVAILABLE as BLOCKING (not warning) when fee
    // cannot be determined (checkout must not proceed without a quote).
    // ---------------------------------------------------------------

    /**
     * Checkout-specific delivery resolve. Makes a single
     * {@code POST /delivery/checkout/quote} call covering all scoped baskets
     * regardless of schedule type.
     *
     * <p>Differences from {@link #resolve}:
     * <ul>
     *   <li>{@code DELIVERY_NOT_AVAILABLE} is added as {@code BLOCKING} (not just a missing fee).</li>
     *   <li>{@code DELIVERY_FEE_UNAVAILABLE} is added as {@code BLOCKING} (checkout cannot proceed
     *       without a confirmed quote).</li>
     *   <li>ASAP and scheduled baskets are batched into one HTTP call.</li>
     * </ul>
     */
    public void resolveForCheckout(RedisCart cart, CartValidationResult result, Set<String> affectedStoreIds) {
        if (cart == null || cart.getStoreBaskets() == null || cart.getStoreBaskets().isEmpty()) {
            return;
        }

        List<RedisStoreBasket> scopedBaskets = cart.getStoreBaskets().stream()
                .filter(b -> b != null && b.getStoreId() != null)
                .filter(b -> affectedStoreIds == null || affectedStoreIds.isEmpty()
                        || affectedStoreIds.contains(b.getStoreId()))
                .toList();

        for (RedisStoreBasket basket : scopedBaskets) {
            if (basket.getAddressId() == null || basket.getAddressId().isBlank()) {
                result.addStoreBasketIssue(basket.getStoreId(), new StoreBasketIssue(
                        StoreBasketIssueCode.DELIVERY_ADDRESS_MISSING,
                        IssueSeverity.WARNING,
                        StoreBasketIssueScope.DELIVERY_ADDRESS,
                        "No delivery address set for this basket.",
                        basket.getStoreId(),
                        null, null, Map.of()
                ));
            }
        }

        List<RedisStoreBasket> basketsWithAddress = scopedBaskets.stream()
                .filter(b -> b.getAddressId() != null && !b.getAddressId().isBlank())
                .toList();

        if (basketsWithAddress.isEmpty()) return;

        List<RedisStoreBasket> cached = new ArrayList<>();
        List<RedisStoreBasket> needsFetch = new ArrayList<>();

        for (RedisStoreBasket basket : basketsWithAddress) {
            if (basket.isQuoteValidForCheckout()) {
                cached.add(basket);
            } else {
                if (basket.isScheduled() && isScheduledSlotStale(basket)) {
                    log.info("scheduled_slot_expired storeId={} scheduledFor={} — resetting to ASAP",
                            basket.getStoreId(), basket.getScheduledFor());
                    basket.setScheduleType(ScheduleType.ASAP);
                    basket.setScheduledFor(null);
                    basket.clearDeliveryQuote();
                    result.addStoreBasketIssue(basket.getStoreId(), new StoreBasketIssue(
                            StoreBasketIssueCode.SCHEDULED_SLOT_EXPIRED,
                            IssueSeverity.WARNING,
                            StoreBasketIssueScope.DELIVERY,
                            "Your scheduled delivery slot has passed. Switched to standard delivery.",
                            basket.getStoreId(),
                            null, null, Map.of()
                    ));
                }
                needsFetch.add(basket);
            }
        }

        for (RedisStoreBasket basket : cached) {
            result.putQuotedDeliveryFee(basket.getStoreId(), basket.getDeliveryQuote().getAmount());
            cartMetrics.recordDeliveryQuoteCacheHit(basket.getStoreId());
        }

        if (!needsFetch.isEmpty()) {
            fetchCheckoutQuotes(cart, needsFetch, result);
        }
    }

    // ---------------------------------------------------------------
    // Standard ASAP fetch (non-checkout)
    // ---------------------------------------------------------------

    private void fetchAsapQuotes(RedisCart cart, List<RedisStoreBasket> baskets, CartValidationResult result) {
        List<DeliveryFeeQuoteRequest.BasketItem> basketItems = baskets.stream()
                .map(basket -> new DeliveryFeeQuoteRequest.BasketItem(
                        basket.getStoreId(),
                        basket.getAddressId(),
                        computeOrderValue(basket, result)
                ))
                .toList();

        DeliveryFeeQuoteResponse response;
        try {
            response = deliveryAvailabilityClient.getFeeQuote(
                    new DeliveryFeeQuoteRequest(cart.getUserId(), currency, basketItems));
        } catch (Exception e) {
            log.warn("Unexpected error fetching ASAP fee quotes for cartId={}", cart.getCartId(), e);
            response = new DeliveryFeeQuoteResponse(List.of());
        }

        Map<String, BasketFeeResult> resultByStoreId =
                (response != null && response.results() != null)
                        ? response.results().stream()
                                .filter(r -> r != null && r.storeId() != null)
                                .collect(Collectors.toMap(BasketFeeResult::storeId, r -> r, (a, b) -> a))
                        : Map.of();

        for (RedisStoreBasket basket : baskets) {
            BasketFeeResult feeResult = resultByStoreId.get(basket.getStoreId());
            applyAsapQuote(basket, feeResult, result);
        }
    }

    private void applyAsapQuote(RedisStoreBasket basket, BasketFeeResult feeResult, CartValidationResult result) {
        if (feeResult != null && feeResult.fee() != null && feeResult.fee().amount() != null) {
            List<RedisDeliveryOption> options = mapOptions(feeResult.availableOptions());

            String selectedMethodId = resolveSelectedMethod(basket, options);
            basket.setSelectedDeliveryMethodId(selectedMethodId);

            RedisDeliveryOption activeOption = findOption(options, selectedMethodId);
            BigDecimal activeFee     = activeOption != null ? activeOption.getFee()      : feeResult.fee().amount();
            String     activeQuoteId = activeOption != null ? activeOption.getQuoteId()  : feeResult.quoteId();
            Instant    activeExpiry  = activeOption != null ? activeOption.getExpiresAt(): feeResult.expiresAt();

            basket.setDeliveryQuote(new RedisDeliveryQuote(
                    activeQuoteId,
                    activeFee,
                    feeResult.fee().currency(),
                    feeResult.fee().display(),
                    etaMin(feeResult),
                    etaMax(feeResult),
                    etaLabel(feeResult),
                    activeExpiry,
                    Instant.now(),
                    options
            ));
            result.putQuotedDeliveryFee(basket.getStoreId(), activeFee);
            cartMetrics.recordDeliveryQuoteFetched(basket.getStoreId(), "asap");
            return;
        }

        log.warn("No ASAP fee quote returned for storeId={}, addressId={}", basket.getStoreId(), basket.getAddressId());
        if (basket.getDeliveryQuote() != null) basket.clearDeliveryQuote();
        result.putQuotedDeliveryFee(basket.getStoreId(), null);
        addFeeUnavailableWarning(basket.getStoreId(), result);
        cartMetrics.recordDeliveryQuoteFetchFailed(basket.getStoreId(), "asap");
    }

    // ---------------------------------------------------------------
    // Standard scheduled fetch (non-checkout)
    // ---------------------------------------------------------------

    private void fetchScheduledQuote(RedisStoreBasket basket, CartValidationResult result) {
        DeliveryScheduleQuoteResponse response;
        try {
            response = deliveryAvailabilityClient.getScheduledQuote(new DeliveryScheduleQuoteRequest(
                    basket.getAddressId(),
                    basket.getStoreId(),
                    basket.getScheduledFor(),
                    computeOrderValue(basket, result),
                    currency
            ));
        } catch (Exception e) {
            log.warn("Unexpected error fetching scheduled quote for storeId={}", basket.getStoreId(), e);
            response = new DeliveryScheduleQuoteResponse(null, null, 0, null, "Unexpected error");
        }

        if (response != null && response.isAccepted()) {
            basket.setDeliveryQuote(new RedisDeliveryQuote(
                    response.quoteId(),
                    response.fee().amount(),
                    response.fee().currency(),
                    response.fee().display(),
                    0, 0, null,
                    response.expiresAt(),
                    Instant.now(),
                    List.of()
            ));
            result.putQuotedDeliveryFee(basket.getStoreId(), response.fee().amount());
            cartMetrics.recordDeliveryQuoteFetched(basket.getStoreId(), "scheduled");
            return;
        }

        log.warn("Scheduled quote rejected for storeId={}, reason={}", basket.getStoreId(),
                response != null ? response.rejectionReason() : "null response");
        if (basket.getDeliveryQuote() != null) basket.clearDeliveryQuote();
        result.putQuotedDeliveryFee(basket.getStoreId(), null);
        addFeeUnavailableWarning(basket.getStoreId(), result);
        cartMetrics.recordDeliveryQuoteFetchFailed(basket.getStoreId(), "scheduled");
    }

    // ---------------------------------------------------------------
    // Unified checkout fetch (resolveForCheckout path)
    // ---------------------------------------------------------------

    private void fetchCheckoutQuotes(RedisCart cart, List<RedisStoreBasket> baskets, CartValidationResult result) {
        List<DeliveryCheckoutQuoteRequest.BasketItem> basketItems = baskets.stream()
                .map(basket -> new DeliveryCheckoutQuoteRequest.BasketItem(
                        basket.getStoreId(),
                        basket.getAddressId(),
                        computeOrderValue(basket, result),
                        basket.isScheduled() ? ScheduleType.SCHEDULED : ScheduleType.ASAP,
                        basket.isScheduled() ? basket.getScheduledFor() : null
                ))
                .toList();

        DeliveryCheckoutQuoteResponse response;
        try {
            response = deliveryAvailabilityClient.getCheckoutQuote(
                    new DeliveryCheckoutQuoteRequest(cart.getUserId(), currency, basketItems));
        } catch (Exception e) {
            log.warn("Unexpected error fetching checkout quotes for cartId={}", cart.getCartId(), e);
            response = new DeliveryCheckoutQuoteResponse(List.of());
        }

        Map<String, BasketCheckoutResult> resultByStoreId =
                (response != null && response.results() != null)
                        ? response.results().stream()
                                .filter(r -> r != null && r.storeId() != null)
                                .collect(Collectors.toMap(BasketCheckoutResult::storeId, r -> r, (a, b) -> a))
                        : Map.of();

        for (RedisStoreBasket basket : baskets) {
            applyCheckoutResult(basket, resultByStoreId.get(basket.getStoreId()), result);
        }
    }

    private void applyCheckoutResult(
            RedisStoreBasket basket,
            BasketCheckoutResult basketResult,
            CartValidationResult result
    ) {
        if (basketResult == null) {
            log.warn("No checkout quote result for storeId={}", basket.getStoreId());
            if (basket.getDeliveryQuote() != null) basket.clearDeliveryQuote();
            result.putQuotedDeliveryFee(basket.getStoreId(), null);
            addFeeUnavailableBlocking(basket.getStoreId(), result);
            cartMetrics.recordDeliveryQuoteFetchFailed(basket.getStoreId(),
                    basket.isScheduled() ? "scheduled" : "asap");
            return;
        }

        if (!basketResult.available()) {
            log.warn("Delivery not available for storeId={}, addressId={}, reason={}",
                    basket.getStoreId(), basket.getAddressId(), basketResult.unavailableReason());
            if (basket.getDeliveryQuote() != null) basket.clearDeliveryQuote();
            result.putQuotedDeliveryFee(basket.getStoreId(), null);
            result.addStoreBasketIssue(basket.getStoreId(), new StoreBasketIssue(
                    StoreBasketIssueCode.DELIVERY_NOT_AVAILABLE,
                    IssueSeverity.BLOCKING,
                    StoreBasketIssueScope.DELIVERY_ADDRESS,
                    basketResult.unavailableReason() != null
                            ? basketResult.unavailableReason()
                            : "Delivery is not available to this address.",
                    basket.getStoreId(), null, null, Map.of()
            ));
            return;
        }

        if (basketResult.availableOptions() == null || basketResult.availableOptions().isEmpty()) {
            // Address is in zone but no quote returned — slot rejected or fee could not be computed.
            log.warn("No delivery options in checkout quote for storeId={}", basket.getStoreId());
            if (basket.getDeliveryQuote() != null) basket.clearDeliveryQuote();
            result.putQuotedDeliveryFee(basket.getStoreId(), null);
            addFeeUnavailableBlocking(basket.getStoreId(), result);
            cartMetrics.recordDeliveryQuoteFetchFailed(basket.getStoreId(),
                    basket.isScheduled() ? "scheduled" : "asap");
            return;
        }

        List<RedisDeliveryOption> options = mapCheckoutOptions(basketResult.availableOptions());
        String selectedMethodId = resolveSelectedMethod(basket, options);
        basket.setSelectedDeliveryMethodId(selectedMethodId);

        RedisDeliveryOption activeOption = findOption(options, selectedMethodId);
        if (activeOption == null) {
            activeOption = options.get(0); // fallback to first option if selected method not found
        }

        basket.setDeliveryQuote(new RedisDeliveryQuote(
                activeOption.getQuoteId(),
                activeOption.getFee(),
                activeOption.getCurrency(),
                activeOption.getFeeDisplay(),
                activeOption.getEtaMin() != null ? activeOption.getEtaMin() : 0,
                activeOption.getEtaMax() != null ? activeOption.getEtaMax() : 0,
                activeOption.getEtaDisplayLabel(),
                activeOption.getExpiresAt(),
                Instant.now(),
                options
        ));
        result.putQuotedDeliveryFee(basket.getStoreId(), activeOption.getFee());
        cartMetrics.recordDeliveryQuoteFetched(basket.getStoreId(),
                basket.isScheduled() ? "scheduled" : "asap");
    }

    // ---------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------

    /** Maps options from the standard getFeeQuote response (non-checkout path). */
    private List<RedisDeliveryOption> mapOptions(List<AvailableDeliveryOption> options) {
        if (options == null) return List.of();
        return options.stream()
                .filter(o -> o != null && o.methodId() != null)
                .map(o -> new RedisDeliveryOption(
                        o.methodId(),
                        o.label(),
                        o.fee(),           // fee amount (bare BigDecimal from old response)
                        null,              // currency — not in DeliveryFeeQuoteResponse options
                        null,              // feeDisplay — not in DeliveryFeeQuoteResponse options
                        o.etaMinutes(),    // etaMin
                        o.etaMinutes(),    // etaMax (same value; old response has no range)
                        null,              // etaDisplayLabel — not in DeliveryFeeQuoteResponse options
                        o.quoteId(),
                        o.expiresAt()
                ))
                .toList();
    }

    /** Maps options from the unified getCheckoutQuote response (checkout path). */
    private List<RedisDeliveryOption> mapCheckoutOptions(
            List<DeliveryCheckoutQuoteResponse.CheckoutDeliveryOption> options
    ) {
        if (options == null) return List.of();
        return options.stream()
                .filter(o -> o != null && o.methodId() != null)
                .map(o -> new RedisDeliveryOption(
                        o.methodId(),
                        o.label(),
                        o.fee() != null ? o.fee().amount() : null,
                        o.fee() != null ? o.fee().currency() : null,
                        o.fee() != null ? o.fee().display() : null,
                        o.etaMinutes() != null ? o.etaMinutes().min() : null,
                        o.etaMinutes() != null ? o.etaMinutes().max() : null,
                        o.etaDisplayLabel(),
                        o.quoteId(),
                        o.expiresAt()
                ))
                .toList();
    }

    private String resolveSelectedMethod(RedisStoreBasket basket, List<RedisDeliveryOption> options) {
        String current = basket.getSelectedDeliveryMethodId();
        if (current == null) return "standard";
        boolean stillAvailable = options.stream().anyMatch(o -> current.equals(o.getMethodId()));
        return stillAvailable ? current : "standard";
    }

    private RedisDeliveryOption findOption(List<RedisDeliveryOption> options, String methodId) {
        if (options == null || methodId == null) return null;
        return options.stream()
                .filter(o -> methodId.equals(o.getMethodId()))
                .findFirst()
                .orElse(null);
    }

    private void addFeeUnavailableWarning(String storeId, CartValidationResult result) {
        result.addStoreBasketIssue(storeId, new StoreBasketIssue(
                StoreBasketIssueCode.DELIVERY_FEE_UNAVAILABLE,
                IssueSeverity.WARNING,
                StoreBasketIssueScope.DELIVERY,
                "Delivery fee could not be determined. Please try again.",
                storeId, null, null, Map.of()
        ));
    }

    /** Blocking variant — used only in the checkout path. */
    private void addFeeUnavailableBlocking(String storeId, CartValidationResult result) {
        result.addStoreBasketIssue(storeId, new StoreBasketIssue(
                StoreBasketIssueCode.DELIVERY_FEE_UNAVAILABLE,
                IssueSeverity.BLOCKING,
                StoreBasketIssueScope.DELIVERY,
                "Delivery fee could not be determined. Please try again.",
                storeId, null, null, Map.of()
        ));
    }

    private boolean isScheduledSlotStale(RedisStoreBasket basket) {
        return basket.getScheduledFor() == null
                || !basket.getScheduledFor().isAfter(Instant.now());
    }

    private BigDecimal computeOrderValue(RedisStoreBasket basket, CartValidationResult result) {
        if (basket.getItems() == null) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (RedisCartItem item : basket.getItems()) {
            if (item == null) continue;
            ProductSnapshot snapshot = result.productsByProductStore()
                    .get(new ProductStoreKey(item.getProductId(), item.getStoreId()));
            BigDecimal price = (snapshot != null && snapshot.currentPrice() != null)
                    ? snapshot.currentPrice() : BigDecimal.ZERO;
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            total = total.add(price.multiply(qty));
        }
        return total;
    }

    private int etaMin(BasketFeeResult r) {
        return (r.deliveryEta() != null && r.deliveryEta().etaMinutes() != null)
                ? r.deliveryEta().etaMinutes().min() : 0;
    }

    private int etaMax(BasketFeeResult r) {
        return (r.deliveryEta() != null && r.deliveryEta().etaMinutes() != null)
                ? r.deliveryEta().etaMinutes().max() : 0;
    }

    private String etaLabel(BasketFeeResult r) {
        return r.deliveryEta() != null ? r.deliveryEta().displayLabel() : null;
    }
}
