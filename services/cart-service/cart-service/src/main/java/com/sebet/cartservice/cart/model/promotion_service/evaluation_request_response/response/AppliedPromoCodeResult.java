package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response;

import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AppliedPromoCodeResult(
        String code,
        String promotionId,
        String title,
        PromoCodeType type,
        PromoSelectionType selectionType,
        Boolean saved,
        Boolean selected,
        Boolean canBeSelected,
        Boolean applied,
        BigDecimal discountAmount,
        BigDecimal missingAmountToActivate,
        Instant expiresAt,
        Integer usageLimit,
        Integer usedCount,
        String message,
        List<PromoIssue> issues
) {
}
