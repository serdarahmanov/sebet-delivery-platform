package com.sebet.order_service.order.event;

import java.math.BigDecimal;
import java.util.List;

public record OrderProposalAcceptedEventData(
        String orderId,
        String customerId,
        String storeId,
        String proposalId,
        String globalDecision,
        List<ItemDecisionData> itemDecisions,
        String proposedItemsJson,
        List<OriginalItemData> originalItems,
        OriginalPricingData originalPricing,
        List<String> selectedPromoCodes,
        String respondedAt
) {

    public record ItemDecisionData(
            String productId,
            String action,
            BigDecimal customQuantity
    ) {}

    public record OriginalItemData(
            String productId,
            String productName,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPriceAmount,
            BigDecimal grossAmount,
            BigDecimal discountAmount
    ) {}

    public record OriginalPricingData(
            BigDecimal subtotalAmount,
            BigDecimal itemDiscountAmount,
            BigDecimal orderDiscountAmount,
            BigDecimal deliveryFeeAmount,
            BigDecimal serviceFeeAmount,
            BigDecimal smallOrderFeeAmount,
            BigDecimal totalAmount,
            String currency
    ) {}
}
