package com.sebet.order_service.shared.enums;

/**
 * Unit of measure for product quantities across the order-service.
 *
 * Values are intentionally mirrored from the cart-service's {@code ProductUnit}
 * enum so that the payload carried in {@code CheckoutConfirmedEvent} survives
 * deserialisation without a shared library.  Any change to the cart-service enum
 * must be reflected here.
 */
public enum ProductUnit {
    PCS,
    KG,
    GRAM,
    LITER,
    ML,
    PACK
}
