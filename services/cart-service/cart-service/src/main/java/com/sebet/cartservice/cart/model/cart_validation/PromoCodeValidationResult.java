package com.sebet.cartservice.cart.model.cart_validation;

import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.enums.ProductUnavailableReason;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoSelectionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PromoCodeValidationResult(
        String storeId,
        String code,
        ProductUnavailableReason.PromoCodeType type,
        PromoSelectionType selectionType,
        PromoCodeState state,
        boolean selected,
        boolean canBeSelected,
        boolean applied,
        BigDecimal discountValue,
        BigDecimal missingAmountToActivate,
        Instant expiresAt,
        Integer usageLimit,
        Integer usedCount,
        String description,
        List<PromoIssue> issues
) {
}
