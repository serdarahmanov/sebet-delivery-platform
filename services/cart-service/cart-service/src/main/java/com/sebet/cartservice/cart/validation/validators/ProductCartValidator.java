package com.sebet.cartservice.cart.validation.validators;

import com.sebet.cartservice.cart.enums.ItemIssuesCode;
import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.cart_issue.CartIssueCode;
import com.sebet.cartservice.cart.model.CartIssue;
import com.sebet.cartservice.cart.model.item.ItemIssue;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.dto.PromotionEligibleItemData;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.product.projection.ProductProjection;
import com.sebet.cartservice.cart.validation.tools.CartValidationAccumulator;
import com.sebet.cartservice.cart.validation.tools.CartValidationLookupContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ProductCartValidator {

    public void validate(
            RedisCart cart,
            CartValidationAccumulator accumulator,
            CartValidationLookupContext lookupContext
    ) {
        CartValidationResult result = accumulator.validationResult();
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            result.addCartIssue(new CartIssue(
                    CartIssueCode.CART_EMPTY,
                    IssueSeverity.BLOCKING,
                    IssueScope.CART,
                    "Cart is empty.",
                    Map.of()
            ));
            return;
        }

        Map<CartValidationLookupContext.ProductStoreKey, ProductProjection> projectionMap =
                lookupContext == null ? Map.of() : lookupContext.productByProductStore();

        for (RedisCartItem item : cart.getItems()) {
            if (item == null) {
                continue;
            }
            CartValidationLookupContext.ProductStoreKey itemKey =
                    new CartValidationLookupContext.ProductStoreKey(item.getProductId(), item.getStoreId());
            if (!projectionMap.containsKey(itemKey)) {
                result.addItemIssue(item.getCartItemId(), new ItemIssue(
                        ItemIssuesCode.PRODUCT_NOT_FOUND,
                        IssueSeverity.BLOCKING,
                        IssueScope.ITEM,
                        "Product is no longer available.",
                        Map.of()
                ));
                continue;
            }
            validateItemProduct(item, projectionMap, accumulator);
        }
    }

    private void validateItemProduct(RedisCartItem item,
                                     Map<CartValidationLookupContext.ProductStoreKey, ProductProjection> projectionMap,
                                     CartValidationAccumulator accumulator
    ) {
        CartValidationResult result = accumulator.validationResult();

        if (item == null || item.getCartItemId() == null) {
            return;
        }

        if (item.getProductId() == null || item.getStoreId() == null) {
            result.addItemIssue(item.getCartItemId(), new ItemIssue(
                    ItemIssuesCode.PRODUCT_NOT_FOUND,
                    IssueSeverity.BLOCKING,
                    IssueScope.ITEM,
                    "Product information is missing.",
                    Map.of()
            ));
            return;
        }

        CartValidationLookupContext.ProductStoreKey key =
                new CartValidationLookupContext.ProductStoreKey(item.getProductId(), item.getStoreId());

        ProductProjection product = projectionMap.get(key);

        result.addProductSnapshot(new ProductSnapshot(
                product.getProductId(),
                product.getSku(),
                product.getStoreId(),
                product.getName(),
                product.getBrandName(),
                product.getCategoryName(),
                product.getImageUrl(),
                product.getUnit(),
                product.getMinQuantity(),
                product.getMaxQuantity(),
                product.getQuantityStep(),
                product.getUnitPrice(),
                product.getOriginalUnitPrice(),
                null,
                null,
                true,
                Boolean.TRUE.equals(product.getActive()) && Boolean.TRUE.equals(product.getSellable())
        ));

        if (Boolean.FALSE.equals(product.getActive())) {
            result.addItemIssue(item.getCartItemId(), new ItemIssue(
                    ItemIssuesCode.PRODUCT_NOT_FOUND,
                    IssueSeverity.BLOCKING,
                    IssueScope.ITEM,
                    "Product is inactive.",
                    Map.of()
            ));
        }

        if (Boolean.FALSE.equals(product.getSellable())) {
            result.addItemIssue(item.getCartItemId(), new ItemIssue(
                    ItemIssuesCode.PRODUCT_NOT_SELLABLE,
                    IssueSeverity.BLOCKING,
                    IssueScope.ITEM,
                    "Product cannot be sold right now.",
                    Map.of()
            ));
        }

        if (item.getQuantity() == null) {
            result.addItemIssue(item.getCartItemId(), new ItemIssue(
                    ItemIssuesCode.INVALID_QUANTITY,
                    IssueSeverity.BLOCKING,
                    IssueScope.ITEM,
                    "Quantity is required.",
                    Map.of()
            ));
        } else {
            if (product.getMinQuantity() != null &&
                    item.getQuantity().compareTo(product.getMinQuantity()) < 0) {
                result.addItemIssue(item.getCartItemId(), new ItemIssue(
                        ItemIssuesCode.QUANTITY_BELOW_MINIMUM,
                        IssueSeverity.BLOCKING,
                        IssueScope.ITEM,
                        "Quantity is below minimum allowed quantity.",
                        Map.of(
                                "minQuantity", product.getMinQuantity(),
                                "requestedQuantity", item.getQuantity()
                        )
                ));
            }

            if (product.getMaxQuantity() != null &&
                    item.getQuantity().compareTo(product.getMaxQuantity()) > 0) {
                result.addItemIssue(item.getCartItemId(), new ItemIssue(
                        ItemIssuesCode.QUANTITY_ABOVE_MAXIMUM,
                        IssueSeverity.BLOCKING,
                        IssueScope.ITEM,
                        "Quantity is above maximum allowed quantity.",
                        Map.of(
                                "maxQuantity", product.getMaxQuantity(),
                                "requestedQuantity", item.getQuantity()
                        )
                ));
            }
        }

        /*
         * Only after product validation finishes, add promotion-ready data.
         */
        if (!hasBlockingItemIssue(item.getCartItemId(), result)) {
            addPromotionEligibleItemData(item, product, accumulator);
        }
    }

    private void addPromotionEligibleItemData(
            RedisCartItem item,
            ProductProjection product,
            CartValidationAccumulator accumulator
    ) {
        BigDecimal quantity = safe(item.getQuantity());
        BigDecimal unitPrice = safe(product.getUnitPrice());
        BigDecimal lineSubtotal = unitPrice.multiply(quantity);

        PromotionEligibleItemData data = new PromotionEligibleItemData(
                item.getCartItemId(),
                item.getProductId(),
                item.getStoreId(),
                product.getCategoryId(),
                quantity,
                product.getUnit(),
                unitPrice,
                lineSubtotal
        );

        accumulator.putPromotionItemData(data);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean hasBlockingItemIssue(
            String cartItemId,
            CartValidationResult result
    ) {
        return result.getItemIssues(cartItemId)
                .stream()
                .anyMatch(ItemIssue::isBlocking);
    }

}
