package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue;

import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.ItemDiscountType;

import java.util.Map;

public record PromoIssue(
        ItemDiscountType.PromoCodeIssueCode code,
        IssueSeverity severity,
        IssueScope scope,
        String message,
        String promoCode,
        Map< String, Object> metadata
) {
}
