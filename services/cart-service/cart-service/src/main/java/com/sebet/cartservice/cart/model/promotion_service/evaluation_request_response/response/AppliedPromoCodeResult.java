package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response;

import com.sebet.cartservice.cart.enums.ProductUnavailableReason;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;

import java.math.BigDecimal;
import java.util.List;

public record AppliedPromoCodeResult(
        String code,
                                     Boolean applied,

                                     String promotionId,
ProductUnavailableReason.PromoCodeType type,

                                     BigDecimal discountAmount,
                                     String message,

                                     List<PromoIssue> issues) {
}
