package com.sebet.cartservice.cart.enums;

public enum ProductUnavailableReason {
    DISABLED_BY_STORE,
    REMOVED_FROM_CATALOG,
    TEMPORARY_UNAVAILABLE;

    public enum PromoCodeType {
        FIXED_AMOUNT,
        PERCENTAGE,
        FREE_DELIVERY,
        BUY_X_PAY_Y
    }
}
