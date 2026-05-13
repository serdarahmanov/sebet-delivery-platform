package com.sebet.cartservice.cart.product.projection;

import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.product.projection.exceptions.CartProductProjectionNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductProjectionService {

    private final ProductProjectionRepository repository;


    @Transactional(readOnly = true)
    public ProductProjection getRequiredProjection(String productId, String storeId) {
        return repository.findByProductIdAndStoreId(productId, storeId)
                .orElseThrow(() -> new CartProductProjectionNotFoundException(
                        "Product projection not found for productId=" + productId
                                + ", storeId=" + storeId
                ));
    }


    @Transactional(readOnly = true)
    public Map<ProductStoreKey, ProductProjection> getProjectionMapForCartItems(
            Collection<RedisCartItem> items
    ) {
        Set<String> productIds = items.stream()
                .map(RedisCartItem::getProductId)
                .collect(Collectors.toSet());

        Set<String> storeIds = items.stream()
                .map(RedisCartItem::getStoreId)
                .collect(Collectors.toSet());

        List<ProductProjection> projections =
                repository.findByProductIdInAndStoreIdIn(productIds, storeIds);

        Map<ProductStoreKey, ProductProjection> projectionMap = projections.stream()
                .collect(Collectors.toMap(
                        projection -> new ProductStoreKey(
                                projection.getProductId(),
                                projection.getStoreId()
                        ),
                        Function.identity()
                ));

        validateAllCartItemsHaveProjection(items, projectionMap);

        return projectionMap;
    }




    private void validateAllCartItemsHaveProjection(
            Collection<RedisCartItem> items,
            Map<ProductStoreKey, ProductProjection> projectionMap
    ) {
        for (RedisCartItem item : items) {
            ProductStoreKey key = new ProductStoreKey(
                    item.getProductId(),
                    item.getStoreId()
            );

            if (!projectionMap.containsKey(key)) {
                throw new CartProductProjectionNotFoundException(
                        "Product projection not found for productId="
                                + item.getProductId()
                                + ", storeId="
                                + item.getStoreId()
                );
            }
        }
    }











}
