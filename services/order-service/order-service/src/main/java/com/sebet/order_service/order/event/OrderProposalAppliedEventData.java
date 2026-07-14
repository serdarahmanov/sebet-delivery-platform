package com.sebet.order_service.order.event;

import java.math.BigDecimal;
import java.util.List;

public record OrderProposalAppliedEventData(
        String orderId,
        String customerId,
        String storeId,
        String proposalId,
        String promoCalculationId,
        BigDecimal subtotalAmount,
        BigDecimal itemDiscountAmount,
        BigDecimal orderDiscountAmount,
        BigDecimal deliveryFeeAmount,
        BigDecimal serviceFeeAmount,
        BigDecimal smallOrderFeeAmount,
        BigDecimal totalAmount,
        String currency,
        List<String> selectedPromoCodes,
        List<ItemData> items,
        String appliedAt
) {

    public record ItemData(
            String productId,
            String productName,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPriceAmount,
            BigDecimal grossAmount,
            BigDecimal discountAmount,
            BigDecimal netAmount
    ) {
    }
}
