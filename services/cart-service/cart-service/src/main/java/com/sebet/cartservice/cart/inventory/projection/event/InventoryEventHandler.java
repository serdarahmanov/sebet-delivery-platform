package com.sebet.cartservice.cart.inventory.projection.event;

import com.sebet.cartservice.cart.inventory.projection.InventoryProjection;
import com.sebet.cartservice.cart.inventory.projection.InventoryProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventHandler {

    private final InventoryProjectionRepository repository;

    @Transactional
    public void handle(InventoryEvent event) {
        if (event == null) {
            log.warn("Received null inventory event. Ignoring.");
            return;
        }

        if (event.eventType() == null || event.data() == null) {
            log.warn("Received invalid inventory event: {}", event);
            return;
        }

        switch (event.eventType()) {
            case "InventoryCreated" -> handleInventoryCreated(event);

            case "StockChanged",
                 "StockDepleted",
                 "StockReplenished",
                 "StockReserved",
                 "StockReleased" -> handleStockSnapshotChanged(event);

            case "InventoryAvailabilityChanged" -> handleInventoryAvailabilityChanged(event);

            default -> log.warn("Unsupported inventory event type received: {}", event.eventType());
        }
    }

    private void handleInventoryCreated(InventoryEvent event) {
        InventoryEventData data = event.data();

        InventoryProjection projection = repository
                .findByProductIdAndStoreId(data.productId(), data.storeId())
                .orElseGet(InventoryProjection::new);

        if (isOldInventoryEvent(projection, data.inventoryVersion())) {
            log.info(
                    "Ignoring old InventoryCreated event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.inventoryVersion(),
                    projection.getInventoryVersion()
            );
            return;
        }

        projection.setProductId(data.productId());
        projection.setStoreId(data.storeId());

        applyInventorySnapshot(projection, data);

        repository.save(projection);
    }

    private void handleStockSnapshotChanged(InventoryEvent event) {
        InventoryEventData data = event.data();

        InventoryProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldInventoryEvent(projection, data.inventoryVersion())) {
            log.info(
                    "Ignoring old inventory stock event. eventType={}, productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    event.eventType(),
                    data.productId(),
                    data.storeId(),
                    data.inventoryVersion(),
                    projection.getInventoryVersion()
            );
            return;
        }

        applyInventorySnapshot(projection, data);

        repository.save(projection);
    }

    private void handleInventoryAvailabilityChanged(InventoryEvent event) {
        InventoryEventData data = event.data();

        InventoryProjection projection = findProjectionOrIgnore(data);

        if (projection == null) {
            return;
        }

        if (isOldInventoryEvent(projection, data.inventoryVersion())) {
            log.info(
                    "Ignoring old InventoryAvailabilityChanged event. productId={}, storeId={}, incomingVersion={}, currentVersion={}",
                    data.productId(),
                    data.storeId(),
                    data.inventoryVersion(),
                    projection.getInventoryVersion()
            );
            return;
        }

        if (data.availableQuantity() != null) {
            projection.setAvailableQuantity(data.availableQuantity());
        }

        if (data.stockStatus() != null) {
            projection.setStockStatus(data.stockStatus());
        }

        if (data.available() != null) {
            projection.setAvailable(data.available());
        }

        projection.setInventoryVersion(data.inventoryVersion());
        projection.setInventoryUpdatedAt(data.inventoryUpdatedAt());

        repository.save(projection);
    }

    private void applyInventorySnapshot(
            InventoryProjection projection,
            InventoryEventData data
    ) {
        projection.setAvailableQuantity(data.availableQuantity());
        projection.setStockStatus(data.stockStatus());
        projection.setAvailable(data.available());
        projection.setInventoryVersion(data.inventoryVersion());
        projection.setInventoryUpdatedAt(data.inventoryUpdatedAt());
    }

    private InventoryProjection findProjectionOrIgnore(InventoryEventData data) {
        if (data.productId() == null || data.storeId() == null) {
            log.warn("Inventory event data does not contain productId or storeId. data={}", data);
            return null;
        }

        return repository
                .findByProductIdAndStoreId(data.productId(), data.storeId())
                .orElseGet(() -> {
                    log.warn(
                            "CartInventoryProjection not found. productId={}, storeId={}. Event ignored.",
                            data.productId(),
                            data.storeId()
                    );
                    return null;
                });
    }

    private boolean isOldInventoryEvent(
            InventoryProjection projection,
            Long incomingInventoryVersion
    ) {
        if (incomingInventoryVersion == null) {
            return false;
        }

        Long currentInventoryVersion = projection.getInventoryVersion();

        return currentInventoryVersion != null
                && incomingInventoryVersion <= currentInventoryVersion;
    }
}