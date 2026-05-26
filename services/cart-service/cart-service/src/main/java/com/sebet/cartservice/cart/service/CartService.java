package com.sebet.cartservice.cart.service;


import com.sebet.cartservice.cart.dto.AddCartItemRequest;
import com.sebet.cartservice.cart.dto.ApplyPromoCodeRequest;
import com.sebet.cartservice.cart.dto.ApplyPromoCodeSelectionRequest;
import com.sebet.cartservice.cart.dto.ApplyPromoCodesRequest;
import com.sebet.cartservice.cart.dto.BatchCartItemRequest;
import com.sebet.cartservice.cart.dto.BatchUpsertCartItemsRequest;
import com.sebet.cartservice.cart.dto.BatchUpsertResponse;
import com.sebet.cartservice.cart.dto.Cart;
import com.sebet.cartservice.cart.dto.ClaimPromoCodeRequest;
import com.sebet.cartservice.cart.dto.SetBasketAddressRequest;
import com.sebet.cartservice.cart.dto.SetDeliveryMethodRequest;
import com.sebet.cartservice.cart.dto.UpdateCartItemRequest;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.enums.ScheduleType;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueCode;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueScope;
import com.sebet.cartservice.cart.exception.CartVersionConflictException;
import com.sebet.cartservice.cart.mapper.RedisCartMapper;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.model.CartPromoCodeResponse;
import com.sebet.cartservice.cart.model.StoreBasketIssue;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationContext;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisCartPromoCode;
import com.sebet.cartservice.cart.model.redis.RedisDeliveryOption;
import com.sebet.cartservice.cart.model.redis.RedisDeliveryQuote;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoCodeIssueCode;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoSelectionType;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;



@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRedisRepository cartRedisRepository;
    private final CartResponseCacheService cartResponseCacheService;
    private final CartResponseBuilder cartResponseBuilder;
    private final CartMetrics cartMetrics;
    private final StoreBasketCacheService storeBasketCacheService;
    private final CartValidationService cartValidationService;
    private final CartCalculationService cartCalculationService;
    private final RedisCartMapper redisCartMapper;

    public Cart validateCart(String userId) {
        RedisCart cart = getOrCreateCart(userId);
        return cartResponseBuilder.build(cart);
    }

    public StoreBasket getStoreBasket(String userId, String basketId) {

        RedisCart cart = cartRedisRepository.findByUserId(userId).orElseThrow(() -> {
            cartMetrics.recordGetBasketCartNotFound();
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found");
        });

        String storeId = resolveStoreIdFromBasketId(cart, basketId);
        RedisStoreBasket redisBasket = storeId != null ? cart.findBasket(storeId) : null;

        if (storeId == null || redisBasket == null) {
            cartMetrics.recordGetBasketNotFound();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Basket not found: " + basketId);
        }

        if (redisBasket.getItems() == null || redisBasket.getItems().isEmpty()) {
            cartMetrics.recordGetBasketEmpty();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Basket is empty: " + basketId);
        }

        Optional<StoreBasket> cached = storeBasketCacheService.getIfPresent(userId, storeId);
        if (cached.isPresent()) {
            return cached.get();
        }

        StoreBasket basket = buildStoreBasketResponseReadOnly(userId, storeId, cart);
        storeBasketCacheService.cache(userId, storeId, basket);
        return basket;
    }

    public StoreBasket deletePromoCode(String userId, String storeId, String code) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        RedisStoreBasket basket = cart.findBasket(storeId);
        if (basket != null && code != null) {
            boolean changed = basket.removePromoCode(code);
            if (changed) {
                cart.touch();
                if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
                    throw new CartVersionConflictException();
                }
                cartResponseCacheService.evict(userId);
                storeBasketCacheService.evict(userId, storeId);
            }
        }
        return buildStoreBasketResponse(userId, storeId, cart);
    }

    public Cart applyPromoCode(String userId, String storeId, @Valid ApplyPromoCodeRequest request) {
        claimPromoCode(userId, storeId, new ClaimPromoCodeRequest(request.promoCode()));
        RedisCart cart = cartRedisRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Cart not found after promo claim"));
        return cartResponseBuilder.build(cart);
    }

    public StoreBasket claimPromoCode(String userId, String storeId, @Valid ClaimPromoCodeRequest request) {
        return claimPromoCodeInternal(userId, storeId, request);
    }

    private StoreBasket claimPromoCodeInternal(String userId, String storeId, ClaimPromoCodeRequest request) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        RedisStoreBasket redisBasket = cart.findOrCreateBasket(storeId);
        List<RedisCartPromoCode> originalPromoCodes = redisBasket.getPromoCodes().stream()
                .map(p -> p == null ? null
                        : new RedisCartPromoCode(p.getCode(), p.getState(), p.getClaimedAt(), p.getSelectedAt()))
                .collect(Collectors.toCollection(ArrayList::new));
        var originalUpdatedAt = cart.getUpdatedAt();

        cart.upsertPromoCode(storeId, request.code(), PromoCodeState.SELECTED);
        StoreBasket candidateBasket = buildStoreBasketResponse(userId, storeId, cart);
        CartPromoCodeResponse clickedPromo = findPromo(candidateBasket, request.code());
        if (clickedPromo == null || isClaimInvalid(clickedPromo)) {
            redisBasket.setPromoCodes(originalPromoCodes);
            cart.setUpdatedAt(originalUpdatedAt);
            cartMetrics.recordPromoCodeClaimed(storeId, "invalid");
            return candidateBasket;
        }

        cart.upsertPromoCode(storeId, clickedPromo.code(), PromoCodeState.SAVED);
        if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
            throw new CartVersionConflictException();
        }
        cartResponseCacheService.evict(userId);
        storeBasketCacheService.evict(userId, storeId);
        log.info("promo_claimed userId={} storeId={} code={}", userId, storeId, clickedPromo.code());
        cartMetrics.recordPromoCodeClaimed(storeId, "success");
        return toClaimSavedBasket(candidateBasket, clickedPromo.code());
    }

    public StoreBasket applyPromoCodes(String userId, String storeId, @Valid ApplyPromoCodesRequest request) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        RedisStoreBasket redisBasket = cart.findOrCreateBasket(storeId);

        Map<String, RedisCartPromoCode> claimedByCode = redisBasket.getPromoCodes().stream()
                .filter(p -> p != null && p.getCode() != null)
                .collect(Collectors.toMap(p -> p.getCode().toUpperCase(), p -> p, (a, b) -> b));

        for (ApplyPromoCodeSelectionRequest selection : request.promoCodes()) {
            if (selection == null || selection.code() == null) {
                continue;
            }
            if (!claimedByCode.containsKey(selection.code().toUpperCase())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Promo code was not claimed: " + selection.code());
            }
        }

        for (ApplyPromoCodeSelectionRequest selection : request.promoCodes()) {
            if (selection == null || selection.code() == null) {
                continue;
            }
            RedisCartPromoCode promo = claimedByCode.get(selection.code().toUpperCase());
            if (promo == null) {
                continue;
            }
            if (Boolean.TRUE.equals(selection.selected())) {
                promo.setState(PromoCodeState.SELECTED);
                promo.setSelectedAt(java.time.Instant.now());
            } else {
                promo.setState(PromoCodeState.SAVED);
                promo.setSelectedAt(null);
            }
        }

        StoreBasket responseBasket = buildStoreBasketResponse(userId, storeId, cart);
        normalizeFromBasketPromoCards(cart, storeId, responseBasket);
        cart.touch();
        if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
            throw new CartVersionConflictException();
        }
        cartResponseCacheService.evict(userId);
        storeBasketCacheService.evict(userId, storeId);
        return responseBasket;
    }

    public StoreBasket removeCartItem(String userId, String cartItemId) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        String storeId = cart.getItems().stream()
                .filter(item -> item != null && cartItemId.equals(item.getCartItemId()))
                .map(RedisCartItem::getStoreId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart item not found: " + cartItemId));
        boolean removed = cart.removeItem(cartItemId);
        if (removed) {
            cart.touch();
            if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
                throw new CartVersionConflictException();
            }
            cartResponseCacheService.evict(userId);
            storeBasketCacheService.evict(userId, storeId);
            log.debug("item_removed userId={} cartItemId={}", userId, cartItemId);
            cartMetrics.recordItemRemoved(storeId);
        }
        return buildStoreBasketResponse(userId, storeId, cart);
    }

    public StoreBasket updateCartItemQuantity(String userId, String cartItemId, @Valid UpdateCartItemRequest request) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        boolean changed = false;
        String affectedStoreId = null;
        for (RedisCartItem item : cart.getItems()) {
            if (item != null && cartItemId.equals(item.getCartItemId())) {
                affectedStoreId = item.getStoreId();
                if (item.getQuantity() == null || item.getQuantity().compareTo(request.quantity()) != 0) {
                    item.setQuantity(request.quantity());
                    item.touch();
                    changed = true;
                }
                break;
            }
        }
        if (affectedStoreId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart item not found: " + cartItemId);
        }
        if (changed) {
            cart.touch();
            if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
                throw new CartVersionConflictException();
            }
            cartResponseCacheService.evict(userId);
            storeBasketCacheService.evict(userId, affectedStoreId);
        }
        return buildStoreBasketResponse(userId, affectedStoreId, cart);
    }

    public Cart addItemToCart(String userId, @Valid AddCartItemRequest request) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        RedisStoreBasket basket = cart.findOrCreateBasket(request.storeId());
        basket.addItem(new RedisCartItem(request.productId(), request.storeId(), request.quantity()));
        cart.touch();
        if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
            throw new CartVersionConflictException();
        }
        cartResponseCacheService.evict(userId);
        storeBasketCacheService.evict(userId, request.storeId());
        log.debug("item_added userId={} productId={} storeId={} quantity={}",
                userId, request.productId(), request.storeId(), request.quantity());
        cartMetrics.recordItemAdded(request.storeId());
        return cartResponseBuilder.build(cart);
    }

    public BatchUpsertResponse batchUpsertItems(String userId, @Valid BatchUpsertCartItemsRequest request) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();

        Map<String, List<BatchCartItemRequest>> requestItemsByStore = request.items().stream()
                .collect(Collectors.groupingBy(BatchCartItemRequest::storeId));

        for (Map.Entry<String, List<BatchCartItemRequest>> entry : requestItemsByStore.entrySet()) {
            String storeId = entry.getKey();
            RedisStoreBasket basket = cart.findOrCreateBasket(storeId);

            for (BatchCartItemRequest incoming : entry.getValue()) {
                RedisCartItem existingItem = basket.findItemByProductId(incoming.productId());
                if (existingItem != null) {
                    if (existingItem.getQuantity() == null
                            || existingItem.getQuantity().compareTo(incoming.quantity()) != 0) {
                        existingItem.setQuantity(incoming.quantity());
                        existingItem.touch();
                    }
                } else {
                    basket.addItem(new RedisCartItem(incoming.productId(), storeId, incoming.quantity()));
                }
            }

            cartMetrics.recordItemsBatchUpserted(storeId);
            storeBasketCacheService.evict(userId, storeId);
        }

        cart.touch();
        if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
            throw new CartVersionConflictException();
        }
        cartResponseCacheService.evict(userId);

        List<String> basketIds = requestItemsByStore.keySet().stream()
                .map(storeId -> cart.getCartId() + ":" + storeId)
                .toList();
        return new BatchUpsertResponse(basketIds);
    }

    public void clearBasket(String userId, String basketId) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        String storeId = resolveStoreIdFromBasketId(cart, basketId);
        boolean removed = storeId != null && cart.removeBasket(storeId);
        if (removed) {
            cart.touch();
            if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
                throw new CartVersionConflictException();
            }
            cartResponseCacheService.evict(userId);
            storeBasketCacheService.evict(userId, storeId);
            cartMetrics.recordBasketCleared(storeId);
        }
    }

    public StoreBasket setBasketAddress(String userId, String storeId, @Valid SetBasketAddressRequest request) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        cart.setBasketAddress(storeId, request.addressId());
        if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
            throw new CartVersionConflictException();
        }
        cartResponseCacheService.evict(userId);
        storeBasketCacheService.evict(userId, storeId);
        return buildStoreBasketResponse(userId, storeId, cart);
    }

    public StoreBasket setDeliveryMethod(String userId, String storeId, @Valid SetDeliveryMethodRequest request) {
        RedisCart cart = getOrCreateCart(userId);
        long expectedVersion = cart.getVersion();
        RedisStoreBasket basket = cart.findBasket(storeId);
        if (basket == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Basket not found for store: " + storeId);
        }

        String methodId = request.methodId();

        if ("scheduled".equals(methodId)) {
            return setScheduledDelivery(userId, storeId, cart, basket, request, expectedVersion);
        } else {
            return setAsapDelivery(userId, storeId, cart, basket, methodId, expectedVersion);
        }
    }

    private StoreBasket setScheduledDelivery(String userId, String storeId, RedisCart cart,
                                              RedisStoreBasket basket, SetDeliveryMethodRequest request,
                                              long expectedVersion) {
        if (request.scheduledFor() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduledFor is required when selecting scheduled delivery.");
        }
        Instant scheduledForInstant = request.scheduledFor().toInstant();
        if (!scheduledForInstant.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduledFor must be a future date and time.");
        }
        if (basket.getAddressId() == null || basket.getAddressId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A delivery address must be set before scheduling.");
        }

        // Snapshot the existing ASAP quote before clearing it.
        // If the scheduled slot is rejected we restore it (if it has enough TTL left)
        // to avoid a second round-trip to the delivery service.
        RedisDeliveryQuote savedAsapQuote = basket.getDeliveryQuote();

        basket.setScheduleType(ScheduleType.SCHEDULED);
        basket.setScheduledFor(scheduledForInstant);
        basket.clearDeliveryQuote();

        // Run full validation — DeliveryFeeResolver fetches the scheduled quote in-memory.
        // Projection validators and promotion evaluation also run here.
        CartValidationContext validationContext =
                cartValidationService.validate(cart, Set.of(storeId));
        CartValidationResult validationResult = validationContext.validationResult();

        boolean feeObtained = validationResult.getQuotedDeliveryFee(storeId)
                .map(fee -> fee != null)
                .orElse(false);

        if (!feeObtained) {
            // Graceful fallback — reset to ASAP; add a user-visible warning.
            // Projection validators and promotion evaluation are NOT re-run: items and
            // promo codes have not changed, so the first validation result is still correct.
            // Only the delivery fee needs to be refreshed via resolveDeliveryFeeOnly().
            log.warn("scheduled_slot_rejected userId={} storeId={} scheduledFor={} — falling back to ASAP",
                    userId, storeId, scheduledForInstant);
            basket.setScheduleType(ScheduleType.ASAP);
            basket.setScheduledFor(null);

            if (isAsapQuoteUsable(savedAsapQuote)) {
                // Restore the pre-attempt ASAP quote — DeliveryFeeResolver sees it as
                // cached (isQuoteValid() == true) and skips the HTTP call entirely.
                log.debug("scheduled_slot_rejected_asap_quote_restored userId={} storeId={} quoteExpiresAt={}",
                        userId, storeId, savedAsapQuote.getExpiresAt());
                basket.setDeliveryQuote(savedAsapQuote);
            } else {
                // Saved quote is absent, expired, or too close to expiry — fetch a fresh one.
                log.debug("scheduled_slot_rejected_asap_quote_stale userId={} storeId={} — fetching fresh ASAP quote",
                        userId, storeId);
                basket.clearDeliveryQuote();
            }

            // Refresh only the delivery fee in the existing result — no DB or promotion calls.
            cartValidationService.resolveDeliveryFeeOnly(cart, validationResult, Set.of(storeId));

            validationResult.addStoreBasketIssue(storeId, new StoreBasketIssue(
                    StoreBasketIssueCode.SCHEDULED_SLOT_UNAVAILABLE,
                    IssueSeverity.WARNING,
                    StoreBasketIssueScope.DELIVERY,
                    "The requested scheduled delivery slot is not available. Switched to standard delivery.",
                    storeId,
                    null, null, Map.of()
            ));
        }

        CartCalculationResult calculationResult =
                cartCalculationService.calculate(cart, validationResult, Set.of(storeId));

        cart.touch();
        if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
            throw new CartVersionConflictException();
        }
        cartResponseCacheService.evict(userId);
        storeBasketCacheService.evict(userId, storeId);

        log.info("delivery_method_set userId={} storeId={} methodId=scheduled scheduledFor={}",
                userId, storeId, scheduledForInstant);
        Cart partial = redisCartMapper.toCart(cart, validationResult, calculationResult, Set.of(storeId));
        return requireBasket(partial, storeId);
    }

    private StoreBasket setAsapDelivery(String userId, String storeId, RedisCart cart,
                                        RedisStoreBasket basket, String methodId, long expectedVersion) {
        // Clear any previously scheduled slot
        if (basket.isScheduled()) {
            basket.setScheduleType(ScheduleType.ASAP);
            basket.setScheduledFor(null);
            basket.clearDeliveryQuote();
        }

        RedisDeliveryQuote quote = basket.getDeliveryQuote();
        if (quote == null || quote.getAvailableOptions() == null || quote.getAvailableOptions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No delivery options available. Set a delivery address first.");
        }
        RedisDeliveryOption selected = quote.getAvailableOptions().stream()
                .filter(o -> o != null && methodId.equals(o.getMethodId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Delivery method not available: " + methodId));

        basket.setSelectedDeliveryMethodId(methodId);
        quote.setQuoteId(selected.getQuoteId());
        quote.setAmount(selected.getFee());
        quote.setExpiresAt(selected.getExpiresAt());

        cart.touch();
        if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
            throw new CartVersionConflictException();
        }
        cartResponseCacheService.evict(userId);
        storeBasketCacheService.evict(userId, storeId);
        log.info("delivery_method_set userId={} storeId={} methodId={}", userId, storeId, methodId);
        return buildStoreBasketResponse(userId, storeId, cart);
    }

    /**
     * Returns true when a previously saved ASAP quote is safe to restore after a
     * scheduled slot rejection.
     *
     * <p>The quote must be non-null, carry an expiry timestamp, and have at least
     * {@code MIN_ASAP_QUOTE_TTL_SECONDS} seconds remaining so the user has enough
     * time to review and complete checkout before the quote expires.
     */
    private static final long MIN_ASAP_QUOTE_TTL_SECONDS = 60;

    private boolean isAsapQuoteUsable(RedisDeliveryQuote quote) {
        if (quote == null || quote.getExpiresAt() == null) {
            return false;
        }
        Instant threshold = Instant.now().plusSeconds(MIN_ASAP_QUOTE_TTL_SECONDS);
        return quote.getExpiresAt().isAfter(threshold);
    }

    public void clearCart(String userId) {
        cartRedisRepository.deleteByUserId(userId);
        cartResponseCacheService.evict(userId);
        log.info("cart_cleared userId={}", userId);
    }

    private RedisCart getOrCreateCart(String userId) {
        Optional<RedisCart> existing = cartRedisRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        RedisCart created = new RedisCart(userId);
        if (!cartRedisRepository.saveIfVersionMatches(userId, created, 0)) {
            // Another concurrent request created the cart first — read theirs.
            return cartRedisRepository.findByUserId(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Cart creation conflict could not be resolved"));
        }
        return created;
    }

    private String resolveStoreIdFromBasketId(RedisCart cart, String basketId) {
        if (cart == null || basketId == null || basketId.isBlank()) return null;
        String prefix = cart.getCartId() + ":";
        if (!basketId.startsWith(prefix)) return null;
        String storeId = basketId.substring(prefix.length());
        return storeId.isBlank() ? null : storeId;
    }

    private CartPromoCodeResponse findPromo(StoreBasket basket, String requestedCode) {
        if (basket == null || basket.promoCodes() == null || requestedCode == null) {
            return null;
        }
        for (CartPromoCodeResponse promo : basket.promoCodes()) {
            if (promo != null && promo.code() != null && requestedCode.equalsIgnoreCase(promo.code())) {
                return promo;
            }
        }
        return null;
    }

    private boolean isClaimInvalid(CartPromoCodeResponse promo) {
        if (promo == null) {
            return true;
        }
        if (promo.issues() == null) {
            return false;
        }
        return promo.issues().stream()
                .filter(java.util.Objects::nonNull)
                .map(issue -> issue.code())
                .anyMatch(code -> code == PromoCodeIssueCode.PROMO_CODE_INVALID
                        || code == PromoCodeIssueCode.PROMO_CODE_EXPIRED
                        || code == PromoCodeIssueCode.PROMO_CODE_NOT_STARTED
                        || code == PromoCodeIssueCode.PROMO_CODE_ALREADY_USED
                        || code == PromoCodeIssueCode.PROMO_CODE_USAGE_LIMIT_REACHED);
    }

    private void normalizeFromBasketPromoCards(RedisCart cart, String storeId, StoreBasket basket) {
        if (basket == null || basket.promoCodes() == null) {
            return;
        }
        RedisStoreBasket redisBasket = cart.findBasket(storeId);
        if (redisBasket == null) return;

        Map<String, CartPromoCodeResponse> byCode = basket.promoCodes().stream()
                .filter(p -> p != null && p.code() != null)
                .collect(Collectors.toMap(p -> p.code().toUpperCase(), p -> p, (a, b) -> b));

        for (RedisCartPromoCode promo : redisBasket.getPromoCodes()) {
            if (promo == null || promo.getCode() == null) {
                continue;
            }
            CartPromoCodeResponse mapped = byCode.get(promo.getCode().toUpperCase());
            if (mapped == null) {
                continue;
            }
            PromoCodeState state = mapped.selected() ? PromoCodeState.SELECTED : PromoCodeState.SAVED;
            promo.setState(state);
            if (state == PromoCodeState.SELECTED) {
                promo.setSelectedAt(java.time.Instant.now());
            } else {
                promo.setSelectedAt(null);
            }
        }
    }

    private StoreBasket requireBasket(Cart response, String storeId) {

        if (response == null || response.storeBaskets() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Store basket not found");
        }
        return response.storeBaskets().stream()
                .filter(b -> b != null && storeId.equals(b.storeId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store basket not found"));
    }

    /** Mutation path — delivery quotes resolved in-memory; cart persisted by the caller's CAS save. */
    private StoreBasket buildStoreBasketResponse(String userId, String storeId, RedisCart cart) {
        Cart partial = cartResponseBuilder.build(cart, Set.of(storeId));
        return requireBasket(partial, storeId);
    }

    /** GET path — delivery quotes computed in-memory, never written to Redis. */
    private StoreBasket buildStoreBasketResponseReadOnly(String userId, String storeId, RedisCart cart) {
        Cart partial = cartResponseBuilder.buildReadOnly(cart, Set.of(storeId));
        return requireBasket(partial, storeId);
    }

    private StoreBasket toClaimSavedBasket(StoreBasket basket, String code) {
        if (basket == null || basket.promoCodes() == null || code == null) {
            return basket;
        }
        List<CartPromoCodeResponse> adjusted = basket.promoCodes().stream()
                .map(promo -> {
                    if (promo == null || promo.code() == null || !code.equalsIgnoreCase(promo.code())) {
                        return promo;
                    }
                    return new CartPromoCodeResponse(
                            promo.code(),
                            promo.title(),
                            promo.promotionId(),
                            promo.type(),
                            promo.selectionType(),
                            PromoCodeState.SAVED,
                            false,
                            promo.canBeSelected(),
                            false,
                            BigDecimal.ZERO,
                            promo.missingAmountToActivate(),
                            promo.expiresAt(),
                            promo.usageLimit(),
                            promo.usedCount(),
                            promo.message(),
                            promo.issues()
                    );
                })
                .toList();
        return new StoreBasket(
                basket.basketId(),
                basket.storeId(),
                basket.storeName(),
                basket.isAvailable(),
                basket.canCheckout(),
                basket.addressId(),
                basket.deliveryQuote(),
                basket.selectedDeliveryMethodId(),
                basket.availableDeliveryOptions(),
                basket.scheduleType(),
                basket.scheduledFor(),
                basket.summary(),
                adjusted,
                basket.issues(),
                basket.storeIssues(),
                basket.items(),
                basket.createdAt(),
                basket.updatedAt()
        );
    }
}
