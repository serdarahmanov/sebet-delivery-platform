package com.sebet.cartservice.cart.checkout.service;

import com.sebet.cartservice.cart.checkout.event.CheckoutConfirmedEvent;
import com.sebet.cartservice.cart.checkout.publisher.CheckoutEventPublisher;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.dto.CheckoutConfirmRequest;
import com.sebet.cartservice.cart.dto.CheckoutConfirmResponse;
import com.sebet.cartservice.cart.dto.CheckoutConfirmResponse.BlockingIssue;
import com.sebet.cartservice.cart.dto.CheckoutInitiateRequest;
import com.sebet.cartservice.cart.dto.CheckoutInitiateResponse;
import com.sebet.cartservice.cart.dto.CheckoutInitiateResponse.CheckoutSummary;
import com.sebet.cartservice.cart.dto.CheckoutInitiateResponse.DeliveryInfo;
import com.sebet.cartservice.cart.dto.CheckoutInitiateResponse.InitiateItem;
import com.sebet.cartservice.cart.dto.CheckoutInitiateResponse.Warning;
import com.sebet.cartservice.cart.enums.ScheduleType;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueCode;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_calculation.ItemCalculation;
import com.sebet.cartservice.cart.model.cart_calculation.StoreBasketCalculation;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult.ProductStoreKey;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisDeliveryQuote;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import com.sebet.cartservice.cart.exception.CartVersionConflictException;
import com.sebet.cartservice.cart.service.*;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class CheckoutService {

    private final CartRedisRepository cartRedisRepository;
    private final CartResponseCacheService cartResponseCacheService;
    private final CartValidationService cartValidationService;
    private final CartCalculationService cartCalculationService;
    private final CheckoutEventPublisher checkoutEventPublisher;
    private final Executor checkoutExecutor;
    private final CartMetrics cartMetrics;
    private final StoreBasketCacheService storeBasketCacheService;

    public CheckoutService(
            CartRedisRepository cartRedisRepository,
            CartResponseCacheService cartResponseCacheService,
            CartValidationService cartValidationService,
            CartCalculationService cartCalculationService,
            CheckoutEventPublisher checkoutEventPublisher,
            @Qualifier("checkoutExecutor") Executor checkoutExecutor,
            CartMetrics cartMetrics,
            StoreBasketCacheService storeBasketCacheService
    ) {
        this.cartRedisRepository = cartRedisRepository;
        this.cartResponseCacheService = cartResponseCacheService;
        this.cartValidationService = cartValidationService;
        this.cartCalculationService = cartCalculationService;
        this.checkoutEventPublisher = checkoutEventPublisher;
        this.checkoutExecutor = checkoutExecutor;
        this.cartMetrics = cartMetrics;
        this.storeBasketCacheService = storeBasketCacheService;
    }

    public CheckoutConfirmResponse confirmCheckout(String userId, String basketId, CheckoutConfirmRequest request) {
        Timer.Sample timerSample = cartMetrics.startCheckoutConfirmTimer();
        try {
            RedisCart cart = cartRedisRepository.findByUserId(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found"));
            long expectedVersion = cart.getVersion();

            String storeId = resolveStoreId(cart, basketId);

            RedisStoreBasket basket = cart.findBasket(storeId);
            if (basket == null || basket.getItems() == null || basket.getItems().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Basket not found or empty: " + basketId);
            }

            String addressId = basket.getAddressId();
            if (addressId == null || addressId.isBlank()) {
                return CheckoutConfirmResponse.rejected(List.of(new BlockingIssue(
                        "STORE_BASKET",
                        StoreBasketIssueCode.DELIVERY_ADDRESS_MISSING.name(),
                        "A delivery address must be set before checkout.",
                        null, storeId
                )));
            }

            // Single future: validateForCheckout handles availability check + fee quote
            // in one unified delivery call. DELIVERY_NOT_AVAILABLE and DELIVERY_FEE_UNAVAILABLE
            // are added as BLOCKING issues inside the validation result — no separate Future A needed.
            CompletableFuture<CartValidationResult> validationFuture = null;
            try {
                validationFuture = CompletableFuture.supplyAsync(() ->
                        cartValidationService.validateForCheckout(cart, Set.of(storeId)).validationResult(),
                        checkoutExecutor);
            } catch (RejectedExecutionException e) {
                log.warn("checkout_confirm_executor_rejected userId={} storeId={} — thread pool saturated",
                        userId, storeId);
                cartMetrics.recordCheckoutConfirmExecutorRejected(storeId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout could not be processed. Please try again.");
            }

            CartValidationResult validationResult;
            try {
                validationResult = validationFuture.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                validationFuture.cancel(true);
                log.warn("Checkout confirmation timed out for basketId={}", basketId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout timed out. Please try again.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout could not be processed. Please try again.");
            } catch (ExecutionException e) {
                log.error("Error during checkout validation for basketId={}", basketId, e.getCause());
                cartMetrics.recordCheckoutConfirmExecutionError(storeId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout could not be processed. Please try again.");
            }

            List<BlockingIssue> blockingIssues = new ArrayList<>();

            // DELIVERY_NOT_AVAILABLE and DELIVERY_FEE_UNAVAILABLE (BLOCKING) are now surfaced
            // through collectBlockingIssues() — no explicit availability / fee-present checks needed.
            collectBlockingIssues(validationResult, storeId, basket, blockingIssues);

            // No separate expiry check needed here. resolveForCheckout uses
            // isQuoteValidForCheckout() which already enforces a 30-second buffer,
            // so any nearly-expired quote is treated as missing and re-fetched above.
            // If the re-fetch fails, DELIVERY_FEE_UNAVAILABLE is added as a blocking
            // issue inside the validation result and surfaced by collectBlockingIssues().

            if (!blockingIssues.isEmpty()) {
                cartMetrics.recordCheckoutRejected(storeId, blockingIssues.get(0).scope());
                cartResponseCacheService.evict(userId);
                storeBasketCacheService.evict(userId, storeId);
                return CheckoutConfirmResponse.rejected(blockingIssues);
            }

            CheckoutConfirmedEvent event = buildEvent(cart, basket, storeId, userId, basketId, validationResult);

            // CAS save BEFORE publishing the event.
            // If the publish were first and the CAS save failed (409 conflict), the
            // CheckoutConfirmed event would already be on Kafka while the basket is still
            // present in Redis — causing order-service to process a phantom order, and a
            // duplicate event on client retry.
            // If the CAS save succeeds but the publish fails, the basket is removed and the
            // client receives 503. The user must re-add items and retry — an acknowledged
            // trade-off until an outbox pattern is introduced.
            cart.removeBasket(storeId);
            cart.touch();
            if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
                throw new CartVersionConflictException();
            }
            cartResponseCacheService.evict(userId);
            storeBasketCacheService.evict(userId, storeId);

            try {
                checkoutEventPublisher.publish(event);
            } catch (Exception e) {
                log.error("Failed to publish checkout event for basketId={}", basketId, e);
                cartMetrics.recordCheckoutKafkaPublishFailed(storeId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout could not be processed. Please try again.");
            }

            log.info("checkout_confirmed userId={} basketId={} storeId={}", userId, basketId, storeId);
            cartMetrics.recordCheckoutConfirmed(storeId);

            return CheckoutConfirmResponse.confirmed();
        } finally {
            cartMetrics.stopCheckoutConfirmTimer(timerSample);
        }
    }

    public CheckoutInitiateResponse initiateCheckout(String userId, String basketId, CheckoutInitiateRequest request) {
        Timer.Sample timerSample = cartMetrics.startCheckoutInitiateTimer();
        try {
            RedisCart cart = cartRedisRepository.findByUserId(userId).orElseThrow(() -> {
                cartMetrics.recordCheckoutInitiateCartNotFound();
                return new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found");
            });
            long expectedVersion = cart.getVersion();

            String storeId;
            try {
                storeId = resolveStoreId(cart, basketId);
            } catch (ResponseStatusException e) {
                cartMetrics.recordCheckoutInitiateBasketNotFound();
                throw e;
            }

            RedisStoreBasket basket = cart.findBasket(storeId);
            if (basket == null) {
                cartMetrics.recordCheckoutInitiateBasketNotFound();
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Basket not found: " + basketId);
            }
            if (basket.getItems() == null || basket.getItems().isEmpty()) {
                cartMetrics.recordCheckoutInitiateBasketEmpty();
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Basket is empty: " + basketId);
            }

            boolean cartDirtied = false;

            if (!Objects.equals(basket.getAddressId(), request.addressId())) {
                cart.setBasketAddress(storeId, request.addressId());
                cartDirtied = true;
            }

            // Snapshot delivery-relevant basket state AFTER the address block.
            // setBasketAddress may have already cleared the quote if the address changed,
            // so the snapshot reflects the correct pre-validation baseline.
            // resolveForCheckout (inside validateForCheckout) may clear or replace the quote
            // and reset the schedule type — we detect mutations by comparing references after
            // validation completes. Reference comparison is intentional: the resolver always
            // creates a new RedisDeliveryQuote on success (setDeliveryQuote) or nulls the field
            // (clearDeliveryQuote), so any mutation produces a different reference.
            RedisDeliveryQuote quoteBefore = basket.getDeliveryQuote();
            ScheduleType scheduleTypeBefore = basket.getScheduleType();

            // Single future: validateForCheckout handles availability check + fee quote
            // in one unified delivery call. DELIVERY_NOT_AVAILABLE and DELIVERY_FEE_UNAVAILABLE
            // are added as BLOCKING issues inside the validation result — no separate Future A needed.
            CompletableFuture<CartValidationResult> validationFuture = null;
            try {
                validationFuture = CompletableFuture.supplyAsync(() ->
                        cartValidationService.validateForCheckout(cart, Set.of(storeId)).validationResult(),
                        checkoutExecutor);
            } catch (RejectedExecutionException e) {
                log.warn("checkout_initiate_executor_rejected userId={} storeId={} — thread pool saturated",
                        userId, storeId);
                cartMetrics.recordCheckoutInitiateExecutorRejected(storeId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout initiation could not be processed. Please try again.");
            }

            CartValidationResult validationResult;
            try {
                validationResult = validationFuture.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                validationFuture.cancel(true);
                log.warn("Checkout initiation timed out for basketId={}", basketId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout timed out. Please try again.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout initiation could not be processed. Please try again.");
            } catch (ExecutionException e) {
                log.error("Error during parallel initiate validation for basketId={}", basketId, e.getCause());
                cartMetrics.recordCheckoutInitiateValidationExecutionError(storeId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Checkout initiation could not be processed. Please try again.");
            }

            // Detect if resolveForCheckout mutated the basket.
            if (basket.getDeliveryQuote() != quoteBefore || basket.getScheduleType() != scheduleTypeBefore) {
                cartDirtied = true;
            }

            // Save only when the cart was actually mutated — regardless of whether there are
            // blocking issues. Address changes, quote updates, and schedule resets are real state
            // corrections that belong in Redis even on a blocked checkout.
            // cart.touch() is deliberately conditional: updatedAt is not bumped when nothing changed
            // (e.g. cached quote reused, same address).
            if (cartDirtied) {
                cart.touch();
                if (!cartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)) {
                    throw new CartVersionConflictException();
                }
                cartResponseCacheService.evict(userId);
                storeBasketCacheService.evict(userId, storeId);
            }

            List<BlockingIssue> blockingIssues = new ArrayList<>();

            // DELIVERY_NOT_AVAILABLE and DELIVERY_FEE_UNAVAILABLE (BLOCKING) are surfaced
            // through collectBlockingIssues() — no explicit availability / fee-present checks needed.
            collectBlockingIssues(validationResult, storeId, basket, blockingIssues);

            if (!blockingIssues.isEmpty()) {
                log.info("checkout_rejected userId={} basketId={} issues={}", userId, basketId, blockingIssues.size());
                cartMetrics.recordCheckoutInitiateBlocked(storeId, resolveBlockingScope(blockingIssues));
                return CheckoutInitiateResponse.blocked(basketId, blockingIssues);
            }

            CartCalculationResult calculationResult =
                    cartCalculationService.calculate(cart, validationResult, Set.of(storeId));

            StoreBasketCalculation basketCalc =
                    calculationResult.storeBasketCalculationsByStoreId().get(storeId);

            List<Warning> warnings = collectWarnings(validationResult, storeId, basket);

            List<InitiateItem> initiateItems = buildInitiateItems(
                    basket, validationResult, calculationResult);

            RedisDeliveryQuote quote = basket.getDeliveryQuote();

            DeliveryInfo deliveryInfo = quote != null
                    ? new DeliveryInfo(
                            new CheckoutInitiateResponse.Fee(quote.getAmount(), quote.getCurrency(), quote.getDisplay()),
                            new CheckoutInitiateResponse.Eta(quote.getEtaMin(), quote.getEtaMax(), quote.getEtaDisplayLabel()))
                    : null;

            CheckoutSummary summary = basketCalc != null
                    ? new CheckoutSummary(
                            basketCalc.itemsSubtotal(),
                            basketCalc.itemDiscountTotal(),
                            basketCalc.promoDiscountTotal(),
                            basketCalc.deliveryFee(),
                            basketCalc.serviceFee(),
                            basketCalc.basketTotal())
                    : null;

            List<String> selectedPromoCodes = basket.getSelectedPromoCodeStrings();

            ScheduleType scheduleType = basket.getScheduleType() != null
                    ? basket.getScheduleType() : ScheduleType.ASAP;

            cartMetrics.recordCheckoutInitiated(storeId);
            return new CheckoutInitiateResponse(
                    "READY",
                    basketId,
                    storeId,
                    request.addressId(),
                    scheduleType,
                    basket.getScheduledFor(),
                    quote != null ? quote.getQuoteId() : null,
                    quote != null ? quote.getExpiresAt() : null,
                    deliveryInfo,
                    initiateItems,
                    selectedPromoCodes,
                    null,
                    warnings.isEmpty() ? null : warnings,
                    summary
            );
        } finally {
            cartMetrics.stopCheckoutInitiateTimer(timerSample);
        }
    }

    private List<Warning> collectWarnings(
            CartValidationResult result,
            String storeId,
            RedisStoreBasket basket
    ) {
        List<Warning> warnings = new ArrayList<>();

        result.getCartIssues().stream()
                .filter(i -> !i.isBlocking())
                .forEach(i -> warnings.add(new Warning("CART", i.code().name(), i.message())));

        result.getStoreIssues(storeId).stream()
                .filter(i -> !i.isBlocking())
                .forEach(i -> warnings.add(new Warning("STORE", i.code().name(), i.message())));

        result.getStoreBasketIssues(storeId).stream()
                .filter(i -> !i.isBlocking())
                .forEach(i -> warnings.add(new Warning("STORE_BASKET", i.code().name(), i.message())));

        if (basket != null && basket.getItems() != null) {
            for (RedisCartItem item : basket.getItems()) {
                result.getItemIssues(item.getCartItemId()).stream()
                        .filter(i -> !i.isBlocking())
                        .forEach(i -> warnings.add(new Warning("ITEM", i.code().name(), i.message())));
            }
        }

        return warnings;
    }

    private List<InitiateItem> buildInitiateItems(
            RedisStoreBasket basket,
            CartValidationResult validationResult,
            CartCalculationResult calculationResult
    ) {
        if (basket == null || basket.getItems() == null) return List.of();

        return basket.getItems().stream()
                .map(item -> {
                    ProductSnapshot snapshot = validationResult.productsByProductStore()
                            .get(new ProductStoreKey(item.getProductId(), item.getStoreId()));
                    ItemCalculation calc = calculationResult.itemCalculationsByCartItemId()
                            .get(item.getCartItemId());
                    return new InitiateItem(
                            item.getCartItemId(),
                            item.getProductId(),
                            snapshot != null ? snapshot.name() : null,
                            snapshot != null ? snapshot.sku() : null,
                            snapshot != null ? snapshot.imageUrl() : null,
                            calc != null ? calc.quantity() : item.getQuantity(),
                            snapshot != null && snapshot.unit() != null ? snapshot.unit().name() : null,
                            calc != null ? calc.unitPrice() : BigDecimal.ZERO,
                            calc != null ? calc.finalTotal() : BigDecimal.ZERO
                    );
                })
                .toList();
    }

    /**
     * Returns the most systemic scope among all blocking issues.
     * Priority: CART > STORE > STORE_BASKET > ITEM
     */
    private String resolveBlockingScope(List<BlockingIssue> issues) {
        List<String> priority = List.of("CART", "STORE", "STORE_BASKET", "ITEM");
        for (String scope : priority) {
            if (issues.stream().anyMatch(i -> scope.equals(i.scope()))) {
                return scope;
            }
        }
        return issues.get(0).scope();
    }

    private String resolveStoreId(RedisCart cart, String basketId) {
        String prefix = cart.getCartId() + ":";
        if (!basketId.startsWith(prefix)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Basket not found: " + basketId);
        }
        return basketId.substring(prefix.length());
    }

    private void collectBlockingIssues(
            CartValidationResult result,
            String storeId,
            RedisStoreBasket basket,
            List<BlockingIssue> issues
    ) {
        result.getCartIssues().stream()
                .filter(i -> i.isBlocking())
                .forEach(i -> issues.add(new BlockingIssue("CART", i.code().name(), i.message(), null, null)));

        result.getStoreIssues(storeId).stream()
                .filter(i -> i.isBlocking())
                .forEach(i -> issues.add(new BlockingIssue("STORE", i.code().name(), i.message(), null, storeId)));

        result.getStoreBasketIssues(storeId).stream()
                .filter(i -> i.isBlocking())
                .forEach(i -> issues.add(new BlockingIssue("STORE_BASKET", i.code().name(), i.message(), null, storeId)));

        if (basket != null && basket.getItems() != null) {
            for (RedisCartItem item : basket.getItems()) {
                result.getItemIssues(item.getCartItemId()).stream()
                        .filter(i -> i.isBlocking())
                        .forEach(i -> issues.add(new BlockingIssue("ITEM", i.code().name(), i.message(),
                                item.getCartItemId(), storeId)));
            }
        }
    }

    private CheckoutConfirmedEvent buildEvent(
            RedisCart cart,
            RedisStoreBasket basket,
            String storeId,
            String userId,
            String basketId,
            CartValidationResult validationResult
    ) {
        Instant now = Instant.now();

        List<CheckoutConfirmedEvent.Item> items = basket.getItems().stream()
                .map(item -> {
                    ProductSnapshot snapshot = validationResult.productsByProductStore()
                            .get(new ProductStoreKey(item.getProductId(), item.getStoreId()));
                    return new CheckoutConfirmedEvent.Item(
                            item.getCartItemId(),
                            item.getProductId(),
                            item.getStoreId(),
                            snapshot != null ? snapshot.sku() : null,
                            snapshot != null ? snapshot.name() : null,
                            snapshot != null && snapshot.unit() != null ? snapshot.unit().name() : null,
                            item.getQuantity(),
                            snapshot != null ? snapshot.currentPrice() : null,
                            snapshot != null ? snapshot.imageUrl() : null
                    );
                })
                .toList();

        ScheduleType scheduleType = basket.getScheduleType() != null
                ? basket.getScheduleType() : ScheduleType.ASAP;

        String activeQuoteId = basket.getDeliveryQuote() != null
                ? basket.getDeliveryQuote().getQuoteId()
                : null;

        CheckoutConfirmedEvent.Data data = new CheckoutConfirmedEvent.Data(
                basketId,
                cart.getCartId(),
                storeId,
                userId,
                basket.getAddressId(),
                activeQuoteId,
                items,
                basket.getSelectedPromoCodeStrings(),
                scheduleType,
                basket.getScheduledFor(),
                now
        );

        return new CheckoutConfirmedEvent(
                UUID.randomUUID().toString(),
                "CheckoutConfirmed",
                cart.getCartId(),
                "Cart",
                now,
                "cart-service",
                data
        );
    }
}
