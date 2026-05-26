package com.sebet.cartservice.cart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.cartservice.cart.enums.ScheduleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutInitiateResponse(
        String status,
        String basketId,
        String storeId,
        String addressId,
        ScheduleType scheduleType,
        Instant scheduledFor,
        String feeQuoteId,
        Instant feeQuoteExpiresAt,
        DeliveryInfo delivery,
        List<InitiateItem> items,
        List<String> selectedPromoCodes,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CheckoutConfirmResponse.BlockingIssue> blockingIssues,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Warning> warnings,
        CheckoutSummary summary
) {
    public record DeliveryInfo(Fee fee, Eta eta) {}

    public record Fee(BigDecimal amount, String currency, String display) {}

    public record Eta(int min, int max, String displayLabel) {}

    public record InitiateItem(
            String cartItemId,
            String productId,
            String name,
            String sku,
            String imageUrl,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {}

    public record Warning(String scope, String code, String message) {}

    public record CheckoutSummary(
            BigDecimal itemsSubtotal,
            BigDecimal itemDiscountTotal,
            BigDecimal promoDiscountTotal,
            BigDecimal deliveryFee,
            BigDecimal serviceFee,
            BigDecimal grandTotal
    ) {}

    public static CheckoutInitiateResponse blocked(
            String basketId,
            List<CheckoutConfirmResponse.BlockingIssue> issues
    ) {
        return new CheckoutInitiateResponse(
                "BLOCKED", basketId, null, null, null, null,
                null, null, null, null, null, issues, null, null
        );
    }
}
