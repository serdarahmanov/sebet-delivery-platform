package com.sebet.cartservice.cart.validation.validators;

import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.ItemIssuesCode;
import com.sebet.cartservice.cart.enums.StockStatus;
import com.sebet.cartservice.cart.inventory.projection.InventoryProjection;
import com.sebet.cartservice.cart.model.item.ItemIssue;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.validation.tools.CartValidationAccumulator;
import com.sebet.cartservice.cart.validation.tools.CartValidationLookupContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class InventoryCartValidator {

    public void validate(
            RedisCart cart,
            CartValidationAccumulator accumulator,
            CartValidationLookupContext lookupContext
    ) {
        CartValidationResult result = accumulator.validationResult();
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return;
        }

        Map<CartValidationLookupContext.ProductStoreKey, InventoryProjection> inventoryMap =
                lookupContext == null ? Map.of() : lookupContext.inventoryByProductStore();

        for (RedisCartItem item : cart.getItems()) {
            if (item == null) {
                continue;
            }
            CartValidationLookupContext.ProductStoreKey itemKey =
                    new CartValidationLookupContext.ProductStoreKey(item.getProductId(), item.getStoreId());
            if (!inventoryMap.containsKey(itemKey)) {
                result.addItemIssue(item.getCartItemId(), new ItemIssue(
                        ItemIssuesCode.INVENTORY_NOT_FOUND,
                        IssueSeverity.BLOCKING,
                        IssueScope.ITEM,
                        "Inventory information is not available for this product.",
                        Map.of()
                ));
                continue;
            }
            validateItemInventory(item, inventoryMap, result);
        }
    }

    private void validateItemInventory(
            RedisCartItem item,
            Map<CartValidationLookupContext.ProductStoreKey, InventoryProjection> inventoryMap,
            CartValidationResult result
    ) {
        if (item == null) {
            return;
        }

        CartValidationLookupContext.ProductStoreKey key =
                new CartValidationLookupContext.ProductStoreKey(item.getProductId(), item.getStoreId());

        InventoryProjection inventory = inventoryMap.get(key);

        result.updateProductInventorySnapshot(
                item.getProductId(),
                item.getStoreId(),
                inventory.getAvailableQuantity(),
                inventory.getStockStatus(),
                Boolean.TRUE.equals(inventory.getAvailable())
        );

        if (Boolean.FALSE.equals(inventory.getAvailable())) {
            result.addItemIssue(item.getCartItemId(), new ItemIssue(
                    ItemIssuesCode.PRODUCT_NOT_FOUND,
                 IssueSeverity.BLOCKING,
                    IssueScope.ITEM,
                    "Product is currently unavailable.",
                    Map.of()
            ));
        }

        if (inventory.getStockStatus() == StockStatus.OUT_OF_STOCK) {
            result.addItemIssue(item.getCartItemId(), new ItemIssue(
                    ItemIssuesCode.OUT_OF_STOCK,
                    IssueSeverity.BLOCKING,
                    IssueScope.ITEM,
                    "Product is out of stock.",
                    Map.of()
            ));
            return;
        }

        BigDecimal requestedQuantity = item.getQuantity();
        BigDecimal availableQuantity = inventory.getAvailableQuantity();

        if (requestedQuantity == null || availableQuantity == null) {
            result.addItemIssue(item.getCartItemId(), new ItemIssue(
                    ItemIssuesCode.INVENTORY_QUANTITY_UNKNOWN,
                    IssueSeverity.BLOCKING,
                    IssueScope.ITEM,
                    "Available quantity could not be verified." ,
                    Map.of()
            ));
            return;
        }

        if (requestedQuantity.compareTo(availableQuantity) > 0) {
            result.addItemIssue(item.getCartItemId(), new ItemIssue(
                    ItemIssuesCode.INSUFFICIENT_STOCK,
                    IssueSeverity.BLOCKING,
                    IssueScope.ITEM,
                    "Requested quantity is higher than available stock.",

                    Map.of(
                            "requestedQuantity", requestedQuantity,
                            "availableQuantity", availableQuantity
                    )
            ));
        }
    }

}
