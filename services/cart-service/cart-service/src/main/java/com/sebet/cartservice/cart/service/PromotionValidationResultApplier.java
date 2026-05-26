package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.enums.ProductUnavailableReason;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.PromoCodeValidationResult;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.AppliedPromoCodeResult;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionEvaluationResponse;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionItemDiscountResult;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionStoreBasketResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PromotionValidationResultApplier {

    public void apply(PromotionEvaluationResponse response, CartValidationResult validationResult) {
        if (response == null || response.storeBasketResults() == null || validationResult == null) {
            return;
        }

        for (PromotionStoreBasketResult basketResult : response.storeBasketResults()) {
            if (basketResult == null || basketResult.storeId() == null) {
                continue;
            }

            if (basketResult.promoCodeResults() != null) {
                for (AppliedPromoCodeResult appliedPromo : basketResult.promoCodeResults()) {
                    if (appliedPromo == null || appliedPromo.code() == null) {
                        continue;
                    }
                    boolean selected = Boolean.TRUE.equals(appliedPromo.selected());
                    validationResult.putPromoResult(new PromoCodeValidationResult(
                            basketResult.storeId(),
                            appliedPromo.code(),
                            toDomainType(appliedPromo.type()),
                            appliedPromo.selectionType(),
                            selected ? PromoCodeState.SELECTED : PromoCodeState.SAVED,
                            selected,
                            Boolean.TRUE.equals(appliedPromo.canBeSelected()),
                            Boolean.TRUE.equals(appliedPromo.applied()),
                            safe(appliedPromo.discountAmount()),
                            safe(appliedPromo.missingAmountToActivate()),
                            appliedPromo.expiresAt(),
                            appliedPromo.usageLimit(),
                            appliedPromo.usedCount(),
                            appliedPromo.message(),
                            appliedPromo.issues() == null ? java.util.List.of() : appliedPromo.issues()
                    ));
                }
            }

            if (basketResult.itemDiscounts() != null) {
                for (PromotionItemDiscountResult itemDiscount : basketResult.itemDiscounts()) {
                    if (itemDiscount == null || itemDiscount.cartItemId() == null) {
                        continue;
                    }
                    validationResult.addItemDiscount(itemDiscount.cartItemId(), itemDiscount);
                }
            }
        }
    }

    private ProductUnavailableReason.PromoCodeType toDomainType(
            com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoCodeType type
    ) {
        if (type == null) {
            return null;
        }
        return ProductUnavailableReason.PromoCodeType.valueOf(type.name());
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
