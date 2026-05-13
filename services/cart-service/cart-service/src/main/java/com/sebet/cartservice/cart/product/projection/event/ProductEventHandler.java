package com.sebet.cartservice.cart.product.projection.event;


import com.sebet.cartservice.cart.product.projection.ProductProjection;
import com.sebet.cartservice.cart.product.projection.ProductProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventHandler {

    private final ProductProjectionRepository repository;

    @Transactional
    public void handle(ProductEvent event) {





        switch (event.eventType()) {
            case "ProductCreated" -> handleProductCreated(event);
            case "ProductUpdated" -> handleProductUpdated(event);
            case "ProductActivated" -> handleProductStatusChanged(event);
            case "ProductDeactivated" -> handleProductStatusChanged(event);
            case "ProductDeleted" -> handleProductDeleted(event);
            case "ProductImageChanged" -> handleProductImageChanged(event);
            case "ProductQuantityRulesChanged" -> handleQuantityRulesChanged(event);
            case "ProductSellableStatusChanged", "ProductSellableChanged" -> handleSellableChanged(event);
            case "ProductPriceChanged" -> handlePriceChanged(event);
            case "ProductOriginalPriceChanged" -> handleOriginalPriceChanged(event);
            case "ProductPriceRemoved" -> handlePriceRemoved(event);
            case "ProductCategoryChanged" -> handleCategoryChanged(event);

            default -> throw new IllegalArgumentException(
                    "Unsupported product event type: " + event.eventType()
            );
        }
    }





    private void handleProductCreated(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = repository
                .findByProductIdAndStoreId(data.productId(), data.storeId())
                .orElseGet(ProductProjection::new);

        if (isOldProductEvent(projection, data.productVersion())) {
            log.info(
                    "Ignoring old ProductCreated event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.productVersion(),
                    projection.getProductVersion()
            );
            return;
        }

        projection.setProductId(data.productId());
        projection.setStoreId(data.storeId());

        projection.setSku(data.sku());
        projection.setName(data.name());
        projection.setBrandName(data.brandName());

        projection.setCategoryId(data.categoryId());
        projection.setCategoryName(data.categoryName());

        projection.setImageUrl(data.imageUrl());

        projection.setUnit(data.unit());
        projection.setMinQuantity(data.minQuantity());
        projection.setMaxQuantity(data.maxQuantity());
        projection.setQuantityStep(data.quantityStep());

        projection.setUnitPrice(data.unitPrice());
        projection.setOriginalUnitPrice(data.originalUnitPrice());

        projection.setActive(data.active());
        projection.setSellable(data.sellable());

        projection.setProductVersion(data.productVersion());
        projection.setPriceVersion(data.priceVersion());

        projection.setProductUpdatedAt(data.productUpdatedAt());
        projection.setPriceUpdatedAt(data.priceUpdatedAt());

        repository.save(projection);
    }


    private void handleProductUpdated(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldProductEvent(projection, data.productVersion())) {
            log.info(
                    "Ignoring old ProductUpdated event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.productVersion(),
                    projection.getProductVersion()
            );
            return;
        }

        if (data.sku() != null) {
            projection.setSku(data.sku());
        }

        if (data.name() != null) {
            projection.setName(data.name());
        }

        if (data.brandName() != null) {
            projection.setBrandName(data.brandName());
        }

        if (data.categoryId() != null) {
            projection.setCategoryId(data.categoryId());
        }

        if (data.categoryName() != null) {
            projection.setCategoryName(data.categoryName());
        }

        if (data.imageUrl() != null) {
            projection.setImageUrl(data.imageUrl());
        }

        if (data.unit() != null) {
            projection.setUnit(data.unit());
        }

        if (data.active() != null) {
            projection.setActive(data.active());
        }

        if (data.sellable() != null) {
            projection.setSellable(data.sellable());
        }

        projection.setProductVersion(data.productVersion());
        projection.setProductUpdatedAt(data.productUpdatedAt());

        repository.save(projection);
    }


    private void handleProductStatusChanged(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldProductEvent(projection, data.productVersion())) {
            log.info(
                    "Ignoring old product status event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.productVersion(),
                    projection.getProductVersion()
            );
            return;
        }

        if (data.active() != null) {
            projection.setActive(data.active());
        }

        if (data.sellable() != null) {
            projection.setSellable(data.sellable());
        }

        projection.setProductVersion(data.productVersion());
        projection.setProductUpdatedAt(data.productUpdatedAt());

        repository.save(projection);
    }



    private void handleProductDeleted(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldProductEvent(projection, data.productVersion())) {
            log.info(
                    "Ignoring old ProductDeleted event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.productVersion(),
                    projection.getProductVersion()
            );
            return;
        }

        projection.setActive(false);
        projection.setSellable(false);

        projection.setProductVersion(data.productVersion());
        projection.setProductUpdatedAt(data.productUpdatedAt());

        repository.save(projection);
    }


    private void handleProductImageChanged(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldProductEvent(projection, data.productVersion())) {
            log.info(
                    "Ignoring old ProductImageChanged event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.productVersion(),
                    projection.getProductVersion()
            );
            return;
        }

        projection.setImageUrl(data.imageUrl());

        projection.setProductVersion(data.productVersion());
        projection.setProductUpdatedAt(data.productUpdatedAt());

        repository.save(projection);
    }

    private void handleQuantityRulesChanged(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldProductEvent(projection, data.productVersion())) {
            log.info(
                    "Ignoring old ProductQuantityRulesChanged event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.productVersion(),
                    projection.getProductVersion()
            );
            return;
        }

        if (data.unit() != null) {
            projection.setUnit(data.unit());
        }

        if (data.minQuantity() != null) {
            projection.setMinQuantity(data.minQuantity());
        }

        if (data.maxQuantity() != null) {
            projection.setMaxQuantity(data.maxQuantity());
        }

        if (data.quantityStep() != null) {
            projection.setQuantityStep(data.quantityStep());
        }

        projection.setProductVersion(data.productVersion());
        projection.setProductUpdatedAt(data.productUpdatedAt());

        repository.save(projection);
    }

    private void handleSellableChanged(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldProductEvent(projection, data.productVersion())) {
            log.info(
                    "Ignoring old ProductSellableStatusChanged event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.productVersion(),
                    projection.getProductVersion()
            );
            return;
        }

        projection.setSellable(data.sellable());

        projection.setProductVersion(data.productVersion());
        projection.setProductUpdatedAt(data.productUpdatedAt());

        repository.save(projection);
    }

    private void handlePriceChanged(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldPriceEvent(projection, data.priceVersion())) {
            log.info(
                    "Ignoring old ProductPriceChanged event. productId={}, storeId={}, incomingPriceVersion={}, currentPriceVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.priceVersion(),
                    projection.getPriceVersion()
            );
            return;
        }

        projection.setUnitPrice(data.unitPrice());

        if (data.originalUnitPrice() != null) {
            projection.setOriginalUnitPrice(data.originalUnitPrice());
        }

        projection.setPriceVersion(data.priceVersion());
        projection.setPriceUpdatedAt(data.priceUpdatedAt());

        repository.save(projection);
    }

    private void handleOriginalPriceChanged(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldPriceEvent(projection, data.priceVersion())) {
            log.info(
                    "Ignoring old ProductOriginalPriceChanged event. productId={}, storeId={}, incomingPriceVersion={}, currentPriceVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.priceVersion(),
                    projection.getPriceVersion()
            );
            return;
        }

        projection.setOriginalUnitPrice(data.originalUnitPrice());

        projection.setPriceVersion(data.priceVersion());
        projection.setPriceUpdatedAt(data.priceUpdatedAt());

        repository.save(projection);
    }

    private void handlePriceRemoved(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldPriceEvent(projection, data.priceVersion())) {
            log.info(
                    "Ignoring old ProductPriceRemoved event. productId={}, storeId={}, incomingPriceVersion={}, currentPriceVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.priceVersion(),
                    projection.getPriceVersion()
            );
            return;
        }

        projection.setUnitPrice(null);
        projection.setOriginalUnitPrice(null);
        projection.setSellable(false);

        projection.setPriceVersion(data.priceVersion());
        projection.setPriceUpdatedAt(data.priceUpdatedAt());

        repository.save(projection);
    }

    private void handleCategoryChanged(ProductEvent event) {
        ProductEventData data = event.data();

        ProductProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldProductEvent(projection, data.productVersion())) {
            log.info(
                    "Ignoring old ProductCategoryChanged event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.productVersion(),
                    projection.getProductVersion()
            );
            return;
        }

        projection.setCategoryId(data.categoryId());
        projection.setCategoryName(data.categoryName());

        projection.setProductVersion(data.productVersion());
        projection.setProductUpdatedAt(data.productUpdatedAt());

        repository.save(projection);
    }


















//    HELPER FUNCTIONS
//
//

    private ProductProjection findProjectionOrIgnore(ProductEventData data) {
        if (data.productId() == null || data.storeId() == null) {
            log.warn("Product event data does not contain productId or storeId. data={}", data);
            return null;
        }

        return repository
                .findByProductIdAndStoreId(data.productId(), data.storeId())
                .orElseGet(() -> {
                    log.warn(
                            "CartProductProjection not found. productId={}, storeId={}. Event ignored.",
                            data.productId(),
                            data.storeId()
                    );
                    return null;
                });
    }



    private boolean isOldProductEvent(ProductProjection projection, Long incomingProductVersion) {
        if (incomingProductVersion == null) {
            return false;
        }

        Long currentProductVersion = projection.getProductVersion();

        return currentProductVersion != null && incomingProductVersion <= currentProductVersion;
    }

    private boolean isOldPriceEvent(ProductProjection projection, Long incomingPriceVersion) {
        if (incomingPriceVersion == null) {
            return false;
        }

        Long currentPriceVersion = projection.getPriceVersion();

        return currentPriceVersion != null && incomingPriceVersion <= currentPriceVersion;
    }

}
