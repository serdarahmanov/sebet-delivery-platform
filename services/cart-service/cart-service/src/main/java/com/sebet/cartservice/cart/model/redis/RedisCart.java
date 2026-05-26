package com.sebet.cartservice.cart.model.redis;

import com.sebet.cartservice.cart.enums.PromoCodeState;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RedisCart {

    public static final int CURRENT_SCHEMA_VERSION = 4;

    private int schemaVersion;
    private long version = 0;   // optimistic-lock token; incremented by every successful CAS save
    private String userId;
    private String cartId;
    private List<RedisStoreBasket> storeBaskets = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public RedisCart(String userId) {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.cartId = UUID.randomUUID().toString();
        this.userId = userId;
        this.storeBaskets = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    /** Incremented inside {@code CartRedisRepository.saveIfVersionMatches} before serialisation. */
    public void incrementVersion() {
        this.version++;
    }

    // --- Basket management ---

    public RedisStoreBasket findBasket(String storeId) {
        if (storeId == null || storeBaskets == null) return null;
        return storeBaskets.stream()
                .filter(b -> b != null && storeId.equals(b.getStoreId()))
                .findFirst()
                .orElse(null);
    }

    public RedisStoreBasket findOrCreateBasket(String storeId) {
        RedisStoreBasket existing = findBasket(storeId);
        if (existing != null) return existing;
        if (storeBaskets == null) storeBaskets = new ArrayList<>();
        RedisStoreBasket basket = new RedisStoreBasket(storeId);
        storeBaskets.add(basket);
        return basket;
    }

    public boolean removeBasket(String storeId) {
        if (storeId == null || storeBaskets == null) return false;
        return storeBaskets.removeIf(b -> b != null && storeId.equals(b.getStoreId()));
    }

    public void setBasketAddress(String storeId, String addressId) {
        RedisStoreBasket basket = findOrCreateBasket(storeId);
        if (!Objects.equals(basket.getAddressId(), addressId)) {
            basket.clearDeliveryQuote();
        }
        basket.setAddressId(addressId);
        touch();
    }

    public String getBasketAddress(String storeId) {
        RedisStoreBasket basket = findBasket(storeId);
        return basket != null ? basket.getAddressId() : null;
    }

    // --- Item convenience: aggregated read-only view used by validators and calculation ---

    public List<RedisCartItem> getItems() {
        if (storeBaskets == null || storeBaskets.isEmpty()) return List.of();
        return storeBaskets.stream()
                .filter(b -> b != null && b.getItems() != null)
                .flatMap(b -> b.getItems().stream())
                .toList();
    }

    public boolean removeItem(String cartItemId) {
        if (cartItemId == null || storeBaskets == null) return false;
        for (RedisStoreBasket basket : storeBaskets) {
            if (basket != null && basket.removeItem(cartItemId)) {
                return true;
            }
        }
        return false;
    }

    // --- Promo code convenience: aggregated read-only view ---

    public List<RedisCartPromoCode> getPromoCodes() {
        if (storeBaskets == null || storeBaskets.isEmpty()) return List.of();
        return storeBaskets.stream()
                .filter(b -> b != null && b.getPromoCodes() != null)
                .flatMap(b -> b.getPromoCodes().stream())
                .toList();
    }

    // --- Promo code mutations: delegated to the appropriate basket ---

    public void upsertPromoCode(String storeId, String code, PromoCodeState state) {
        findOrCreateBasket(storeId).upsertPromoCode(code, state);
        touch();
    }

    public List<String> getPromoCodesForStore(String storeId) {
        RedisStoreBasket basket = findBasket(storeId);
        if (basket == null || basket.getPromoCodes() == null) return List.of();
        return basket.getPromoCodes().stream()
                .filter(p -> p != null && p.getCode() != null && !p.getCode().isBlank())
                .map(RedisCartPromoCode::getCode)
                .distinct()
                .toList();
    }

    public List<String> getSelectedPromoCodesForStore(String storeId) {
        RedisStoreBasket basket = findBasket(storeId);
        return basket != null ? basket.getSelectedPromoCodeStrings() : List.of();
    }
}
