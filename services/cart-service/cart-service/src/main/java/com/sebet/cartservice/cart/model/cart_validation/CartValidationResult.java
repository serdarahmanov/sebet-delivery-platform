package com.sebet.cartservice.cart.model.cart_validation;

import com.sebet.cartservice.cart.model.CartIssue;
import com.sebet.cartservice.cart.model.StoreBasketIssue;
import com.sebet.cartservice.cart.model.item.ItemIssue;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionItemDiscountResult;
import com.sebet.cartservice.cart.model.store.StoreIssue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CartValidationResult {

    public record ProductStoreKey(String productId, String storeId) {}

    private final List<CartIssue> cartIssues = new ArrayList<>();
    private final Map<String, List<StoreIssue>> storeIssues = new HashMap<>();
    private final Map<String, List<StoreBasketIssue>> storeBasketIssues = new HashMap<>();
    private final Map<String, List<ItemIssue>> itemIssues = new HashMap<>();

    private final Map<ProductStoreKey, ProductSnapshot> productsByProductStore = new HashMap<>();
    private final Map<String, StoreSnapshot> storesByStoreId = new HashMap<>();
    private final Map<String, List<PromoCodeValidationResult>> promoResultsByStoreId = new HashMap<>();
    private final Map<String, List<PromotionItemDiscountResult>> itemDiscountsByCartItemId = new HashMap<>();
    private final Map<String, BigDecimal> quotedDeliveryFees = new HashMap<>();

    private final Map<ProductStoreKey, ProductSnapshot> productsByProductStoreView =
            Collections.unmodifiableMap(productsByProductStore);
    private final Map<String, StoreSnapshot> storesByStoreIdView =
            Collections.unmodifiableMap(storesByStoreId);
    private final Map<String, List<PromoCodeValidationResult>> promoResultsByStoreIdView =
            Collections.unmodifiableMap(promoResultsByStoreId);

    public void addCartIssue(CartIssue issue) {
        if (issue == null) {
            return;
        }
        cartIssues.add(issue);
    }

    public void addStoreIssue(String storeId, StoreIssue issue) {
        if (storeId == null || issue == null) {
            return;
        }
        storeIssues.computeIfAbsent(storeId, key -> new ArrayList<>()).add(issue);
    }

    public void addStoreBasketIssue(String storeId, StoreBasketIssue issue) {
        if (storeId == null || issue == null) {
            return;
        }
        storeBasketIssues.computeIfAbsent(storeId, key -> new ArrayList<>()).add(issue);
    }

    public void addItemIssue(String cartItemId, ItemIssue issue) {
        if (cartItemId == null || issue == null) {
            return;
        }
        itemIssues.computeIfAbsent(cartItemId, key -> new ArrayList<>()).add(issue);
    }

    public void addProductSnapshot(ProductSnapshot snapshot) {
        if (snapshot == null || snapshot.productId() == null || snapshot.storeId() == null) {
            return;
        }
        productsByProductStore.put(new ProductStoreKey(snapshot.productId(), snapshot.storeId()), snapshot);
    }

    public void updateProductInventorySnapshot(
            String productId,
            String storeId,
            java.math.BigDecimal availableQuantity,
            com.sebet.cartservice.cart.enums.StockStatus stockStatus,
            boolean available
    ) {
        if (productId == null || storeId == null) {
            return;
        }
        ProductStoreKey key = new ProductStoreKey(productId, storeId);
        ProductSnapshot current = productsByProductStore.get(key);
        if (current == null) {
            return;
        }
        productsByProductStore.put(key, new ProductSnapshot(
                current.productId(),
                current.sku(),
                current.storeId(),
                current.name(),
                current.brandName(),
                current.categoryName(),
                current.imageUrl(),
                current.unit(),
                current.minQuantity(),
                current.maxQuantity(),
                current.quantityStep(),
                current.currentPrice(),
                current.originalPrice(),
                availableQuantity,
                stockStatus,
                current.exists(),
                available
        ));
    }

    public void addStoreSnapshot(StoreSnapshot snapshot) {
        if (snapshot == null || snapshot.storeId() == null) {
            return;
        }
        storesByStoreId.put(snapshot.storeId(), snapshot);
    }

    public void addItemDiscount(String cartItemId, PromotionItemDiscountResult discount) {
        if (cartItemId == null || discount == null) {
            return;
        }
        itemDiscountsByCartItemId.computeIfAbsent(cartItemId, k -> new ArrayList<>()).add(discount);
    }

    public List<PromotionItemDiscountResult> getItemDiscounts(String cartItemId) {
        return List.copyOf(itemDiscountsByCartItemId.getOrDefault(cartItemId, List.of()));
    }

    public void putPromoResult(PromoCodeValidationResult promoResult) {
        if (promoResult == null || promoResult.storeId() == null) {
            return;
        }
        List<PromoCodeValidationResult> existing = promoResultsByStoreId
                .computeIfAbsent(promoResult.storeId(), ignored -> new ArrayList<>());
        existing.removeIf(it -> it != null && it.code() != null && it.code().equalsIgnoreCase(promoResult.code()));
        existing.add(promoResult);
    }

    public List<CartIssue> getCartIssues() {
        return List.copyOf(cartIssues);
    }

    public List<StoreIssue> getStoreIssues(String storeId) {
        return List.copyOf(storeIssues.getOrDefault(storeId, List.of()));
    }

    public List<StoreBasketIssue> getStoreBasketIssues(String storeId) {
        return List.copyOf(storeBasketIssues.getOrDefault(storeId, List.of()));
    }

    public List<ItemIssue> getItemIssues(String cartItemId) {
        return List.copyOf(itemIssues.getOrDefault(cartItemId, List.of()));
    }

    public Map<String, List<StoreIssue>> getAllStoreIssues() {
        return copyMap(storeIssues);
    }

    public Map<String, List<StoreBasketIssue>> getAllStoreBasketIssues() {
        return copyMap(storeBasketIssues);
    }

    public Map<String, List<ItemIssue>> getAllItemIssues() {
        return copyMap(itemIssues);
    }

    public Map<ProductStoreKey, ProductSnapshot> productsByProductStore() {
        return productsByProductStoreView;
    }

    public Map<String, StoreSnapshot> storesByStoreId() {
        return storesByStoreIdView;
    }

    public Map<String, List<PromoCodeValidationResult>> promoResultsByStoreId() {
        return promoResultsByStoreIdView;
    }

    public Map<String, List<StoreBasketIssue>> storeBasketIssuesByStoreId() {
        return getAllStoreBasketIssues();
    }

    public Map<String, List<ItemIssue>> itemIssuesByCartItemId() {
        return getAllItemIssues();
    }

    public boolean hasIssues() {
        return !cartIssues.isEmpty()
                || !storeIssues.isEmpty()
                || !storeBasketIssues.isEmpty()
                || !itemIssues.isEmpty();
    }

    public boolean hasBlockingIssues() {
        return cartIssues.stream().anyMatch(CartIssue::isBlocking)
                || storeIssues.values().stream().flatMap(List::stream).anyMatch(StoreIssue::isBlocking)
                || storeBasketIssues.values().stream().flatMap(List::stream).anyMatch(StoreBasketIssue::isBlocking)
                || itemIssues.values().stream().flatMap(List::stream).anyMatch(ItemIssue::isBlocking);
    }

    public void putQuotedDeliveryFee(String storeId, BigDecimal fee) {
        if (storeId == null) return;
        quotedDeliveryFees.put(storeId, fee);
    }

    public Optional<BigDecimal> getQuotedDeliveryFee(String storeId) {
        if (!quotedDeliveryFees.containsKey(storeId)) return Optional.empty();
        return Optional.ofNullable(quotedDeliveryFees.get(storeId));
    }

    public boolean isDeliveryFeeQuoted(String storeId) {
        return quotedDeliveryFees.containsKey(storeId);
    }

    public boolean isValid() {
        return !hasBlockingIssues();
    }

    private <T> Map<String, List<T>> copyMap(Map<String, List<T>> source) {
        Map<String, List<T>> copy = new HashMap<>();
        for (Map.Entry<String, List<T>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }
}
