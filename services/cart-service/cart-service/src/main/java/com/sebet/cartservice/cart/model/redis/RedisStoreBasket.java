package com.sebet.cartservice.cart.model.redis;

import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.enums.ScheduleType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RedisStoreBasket {

    private String storeId;
    private String addressId;
    private RedisDeliveryQuote deliveryQuote;
    private String selectedDeliveryMethodId;
    private ScheduleType scheduleType = ScheduleType.ASAP;
    private Instant scheduledFor;
    private List<RedisCartItem> items = new ArrayList<>();
    private List<RedisCartPromoCode> promoCodes = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public RedisStoreBasket(String storeId) {
        this.storeId = storeId;
        this.scheduleType = ScheduleType.ASAP;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isScheduled() {
        return ScheduleType.SCHEDULED.equals(scheduleType);
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isQuoteValid() {
        return deliveryQuote != null
                && deliveryQuote.getExpiresAt() != null
                && deliveryQuote.getExpiresAt().isAfter(Instant.now());
    }

    /**
     * Stricter validity check for the checkout path.
     * Requires at least 30 seconds remaining on the quote so the downstream
     * Kafka → order-service → delivery-service fee-lock chain has time to complete.
     */
    public boolean isQuoteValidForCheckout() {
        return deliveryQuote != null
                && deliveryQuote.getExpiresAt() != null
                && deliveryQuote.getExpiresAt().isAfter(Instant.now().plusSeconds(30));
    }

    public void clearDeliveryQuote() {
        this.deliveryQuote = null;
    }

    // --- Item operations ---

    public void addItem(RedisCartItem item) {
        if (items == null) items = new ArrayList<>();
        items.add(item);
    }

    public boolean removeItem(String cartItemId) {
        if (items == null || cartItemId == null) return false;
        boolean removed = items.removeIf(item -> item != null && cartItemId.equals(item.getCartItemId()));
        if (removed) touch();
        return removed;
    }

    public RedisCartItem findItem(String cartItemId) {
        if (items == null || cartItemId == null) return null;
        return items.stream()
                .filter(item -> item != null && cartItemId.equals(item.getCartItemId()))
                .findFirst()
                .orElse(null);
    }

    public RedisCartItem findItemByProductId(String productId) {
        if (items == null || productId == null) return null;
        return items.stream()
                .filter(item -> item != null && productId.equals(item.getProductId()))
                .findFirst()
                .orElse(null);
    }

    // --- Promo code operations ---

    public void upsertPromoCode(String code, PromoCodeState state) {
        if (code == null || code.isBlank() || state == null) return;
        if (promoCodes == null) promoCodes = new ArrayList<>();
        String upper = code.trim().toUpperCase();
        RedisCartPromoCode existing = promoCodes.stream()
                .filter(p -> p != null && upper.equalsIgnoreCase(p.getCode()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            RedisCartPromoCode created = new RedisCartPromoCode(upper);
            created.setState(state);
            if (state == PromoCodeState.SELECTED) {
                created.setSelectedAt(Instant.now());
            }
            promoCodes.add(created);
        } else {
            existing.setState(state);
            if (state == PromoCodeState.SELECTED) {
                existing.setSelectedAt(Instant.now());
            } else {
                existing.setSelectedAt(null);
            }
        }
        touch();
    }

    public boolean removePromoCode(String code) {
        if (promoCodes == null || code == null || code.isBlank()) return false;
        boolean removed = promoCodes.removeIf(p -> p != null && code.equalsIgnoreCase(p.getCode()));
        if (removed) touch();
        return removed;
    }

    public List<String> getSelectedPromoCodeStrings() {
        if (promoCodes == null || promoCodes.isEmpty()) return List.of();
        return promoCodes.stream()
                .filter(p -> p != null && p.getState() == PromoCodeState.SELECTED
                        && p.getCode() != null && !p.getCode().isBlank())
                .map(RedisCartPromoCode::getCode)
                .distinct()
                .toList();
    }
}
