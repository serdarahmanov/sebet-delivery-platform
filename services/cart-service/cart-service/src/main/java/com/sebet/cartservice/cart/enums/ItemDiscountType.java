package com.sebet.cartservice.cart.enums;

public enum ItemDiscountType {
    FIXED_AMOUNT,
    PERCENTAGE,
    BUY_X_PAY_Y,
    SALE_PRICE;

    public enum PromoCodeIssueCode {
        PROMO_CODE_INVALID,
        PROMO_CODE_EXPIRED,
        PROMO_CODE_NOT_STARTED,
        PROMO_CODE_ALREADY_APPLIED,
        PROMO_CODE_ALREADY_USED,
        PROMO_CODE_USAGE_LIMIT_REACHED,
        PROMO_CODE_NOT_APPLICABLE,
        PROMO_CODE_NOT_STACKABLE,
        ONLY_ONE_PROMO_CODE_ALLOWED,
        MINIMUM_SPEND_NOT_MET,
        REQUIRED_PRODUCT_NOT_FOUND,
        REQUIRED_QUANTITY_NOT_MET,
        CART_EMPTY
    }
}
