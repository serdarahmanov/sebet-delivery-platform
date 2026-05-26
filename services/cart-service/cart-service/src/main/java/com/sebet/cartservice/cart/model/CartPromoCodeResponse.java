package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoCodeType;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoSelectionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CartPromoCodeResponse(
        String code,
        String title,
        String promotionId,
        PromoCodeType type,
        PromoSelectionType selectionType,
        PromoCodeState state,
        boolean selected,
        boolean canBeSelected,
        boolean applied,
        BigDecimal discountAmount,
        BigDecimal missingAmountToActivate,
        Instant expiresAt,
        Integer usageLimit,
        Integer usedCount,
        String message,
        List<PromoIssue> issues
) {
}
