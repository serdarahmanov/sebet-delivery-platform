package com.sebet.cartservice.cart.store.projection.event;

import com.sebet.cartservice.cart.store.projection.StoreProjection;
import com.sebet.cartservice.cart.store.projection.StoreProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoreEventHandler {

    private final StoreProjectionRepository repository;

    @Transactional
    public void handle(StoreEvent event) {
        if (event == null) {
            log.warn("Received null store event. Ignoring.");
            return;
        }

        if (event.eventType() == null || event.data() == null) {
            log.warn("Received invalid store event: {}", event);
            return;
        }

        switch (event.eventType()) {
            case "StoreCreated" -> handleStoreCreated(event);
            case "StoreUpdated" -> handleStoreUpdated(event);

            case "StoreActivated", "StoreDeactivated" -> handleStoreActiveStatusChanged(event);

            case "StoreOpened", "StoreClosed" -> handleStoreOpenStatusChanged(event);

            case "StoreAcceptingOrdersChanged" -> handleStoreAcceptingOrdersChanged(event);

            case "StoreMinimumOrderChanged" -> handleStoreMinimumOrderChanged(event);

            case "StoreDeliverySettingsChanged" -> handleStoreDeliverySettingsChanged(event);

            default -> log.warn("Unsupported store event type: {}", event.eventType());
        }
    }






    private void handleStoreCreated(StoreEvent event) {
        StoreEventData data = event.data();

        if (data.storeId() == null) {
            log.warn("StoreCreated event missing storeId: {}", event);
            return;
        }

        StoreProjection projection = repository.findById(data.storeId())
                .orElseGet(StoreProjection::new);

        if (isStaleEvent(projection, data)) {
            log.info(
                    "Ignoring stale StoreCreated event for storeId={}, currentVersion={}, eventVersion={}",
                    data.storeId(),
                    projection.getStoreVersion(),
                    data.storeVersion()
            );
            return;
        }

        projection.setStoreId(data.storeId());
        projection.setStoreName(data.storeName());
        projection.setStoreLogoUrl(data.storeLogoUrl());

        projection.setActive(resolveBoolean(data.active(), true));
        projection.setOpen(resolveBoolean(data.open(), false));
        projection.setAcceptingOrders(resolveBoolean(data.acceptingOrders(), false));

        projection.setMinimumOrderAmount(data.minimumOrderAmount());
        projection.setFreeDeliveryThreshold(data.freeDeliveryThreshold());
        projection.setBaseDeliveryFee(data.baseDeliveryFee());

        projection.setEstimatedPreparationMinutes(data.estimatedPreparationMinutes());

        projection.setStoreVersion(data.storeVersion());
        projection.setStoreUpdatedAt(data.storeUpdatedAt());

        repository.save(projection);
    }

    private void handleStoreUpdated(StoreEvent event) {
        StoreEventData data = event.data();

        findProjection(data, event).ifPresent(projection -> {
            if (isStaleEvent(projection, data)) {
                log.info(
                        "Ignoring stale StoreUpdated event for storeId={}, currentVersion={}, eventVersion={}",
                        data.storeId(),
                        projection.getStoreVersion(),
                        data.storeVersion()
                );
                return;
            }

            if (data.storeName() != null) {
                projection.setStoreName(data.storeName());
            }

            if (data.storeLogoUrl() != null) {
                projection.setStoreLogoUrl(data.storeLogoUrl());
            }

            if (data.active() != null) {
                projection.setActive(data.active());
            }

            if (data.open() != null) {
                projection.setOpen(data.open());
            }

            if (data.acceptingOrders() != null) {
                projection.setAcceptingOrders(data.acceptingOrders());
            }

            if (data.estimatedPreparationMinutes() != null) {
                projection.setEstimatedPreparationMinutes(data.estimatedPreparationMinutes());
            }

            applyVersionFields(projection, data);

            repository.save(projection);
        });
    }

    private void handleStoreActiveStatusChanged(StoreEvent event) {
        StoreEventData data = event.data();

        findProjection(data, event).ifPresent(projection -> {
            if (isStaleEvent(projection, data)) {
                log.info(
                        "Ignoring stale active-status event for storeId={}, currentVersion={}, eventVersion={}",
                        data.storeId(),
                        projection.getStoreVersion(),
                        data.storeVersion()
                );
                return;
            }

            if (data.active() != null) {
                projection.setActive(data.active());
            }

            if (data.open() != null) {
                projection.setOpen(data.open());
            }

            if (data.acceptingOrders() != null) {
                projection.setAcceptingOrders(data.acceptingOrders());
            }

            applyVersionFields(projection, data);

            repository.save(projection);
        });
    }

    private void handleStoreOpenStatusChanged(StoreEvent event) {
        StoreEventData data = event.data();

        findProjection(data, event).ifPresent(projection -> {
            if (isStaleEvent(projection, data)) {
                log.info(
                        "Ignoring stale open-status event for storeId={}, currentVersion={}, eventVersion={}",
                        data.storeId(),
                        projection.getStoreVersion(),
                        data.storeVersion()
                );
                return;
            }

            if (data.open() != null) {
                projection.setOpen(data.open());
            }

            if (data.acceptingOrders() != null) {
                projection.setAcceptingOrders(data.acceptingOrders());
            }

            applyVersionFields(projection, data);

            repository.save(projection);
        });
    }

    private void handleStoreAcceptingOrdersChanged(StoreEvent event) {
        StoreEventData data = event.data();

        findProjection(data, event).ifPresent(projection -> {
            if (isStaleEvent(projection, data)) {
                log.info(
                        "Ignoring stale accepting-orders event for storeId={}, currentVersion={}, eventVersion={}",
                        data.storeId(),
                        projection.getStoreVersion(),
                        data.storeVersion()
                );
                return;
            }

            if (data.acceptingOrders() != null) {
                projection.setAcceptingOrders(data.acceptingOrders());
            }

            applyVersionFields(projection, data);

            repository.save(projection);
        });
    }

    private void handleStoreMinimumOrderChanged(StoreEvent event) {
        StoreEventData data = event.data();

        findProjection(data, event).ifPresent(projection -> {
            if (isStaleEvent(projection, data)) {
                log.info(
                        "Ignoring stale minimum-order event for storeId={}, currentVersion={}, eventVersion={}",
                        data.storeId(),
                        projection.getStoreVersion(),
                        data.storeVersion()
                );
                return;
            }

            if (data.minimumOrderAmount() != null) {
                projection.setMinimumOrderAmount(data.minimumOrderAmount());
            }

            applyVersionFields(projection, data);

            repository.save(projection);
        });
    }

    private void handleStoreDeliverySettingsChanged(StoreEvent event) {
        StoreEventData data = event.data();

        findProjection(data, event).ifPresent(projection -> {
            if (isStaleEvent(projection, data)) {
                log.info(
                        "Ignoring stale delivery-settings event for storeId={}, currentVersion={}, eventVersion={}",
                        data.storeId(),
                        projection.getStoreVersion(),
                        data.storeVersion()
                );
                return;
            }

            if (data.minimumOrderAmount() != null) {
                projection.setMinimumOrderAmount(data.minimumOrderAmount());
            }

            if (data.freeDeliveryThreshold() != null) {
                projection.setFreeDeliveryThreshold(data.freeDeliveryThreshold());
            }

            if (data.baseDeliveryFee() != null) {
                projection.setBaseDeliveryFee(data.baseDeliveryFee());
            }

            if (data.estimatedPreparationMinutes() != null) {
                projection.setEstimatedPreparationMinutes(data.estimatedPreparationMinutes());
            }

            applyVersionFields(projection, data);

            repository.save(projection);
        });
    }

    private java.util.Optional<StoreProjection> findProjection(StoreEventData data, StoreEvent event) {
        if (data.storeId() == null) {
            log.warn("Store event missing storeId: {}", event);
            return java.util.Optional.empty();
        }

        java.util.Optional<StoreProjection> projection = repository.findById(data.storeId());

        if (projection.isEmpty()) {
            log.warn(
                    "Store projection not found for storeId={}, eventType={}. Ignoring event.",
                    data.storeId(),
                    event.eventType()
            );
        }

        return projection;
    }

    private boolean isStaleEvent(StoreProjection projection, StoreEventData data) {
        if (projection.getStoreVersion() == null || data.storeVersion() == null) {
            return false;
        }

        return data.storeVersion() <= projection.getStoreVersion();
    }

    private void applyVersionFields(StoreProjection projection, StoreEventData data) {
        if (data.storeVersion() != null) {
            projection.setStoreVersion(data.storeVersion());
        }

        if (data.storeUpdatedAt() != null) {
            projection.setStoreUpdatedAt(data.storeUpdatedAt());
        }
    }

    private Boolean resolveBoolean(Boolean value, Boolean defaultValue) {
        return value != null ? value : defaultValue;
    }






}
