package com.sebet.order_service.internal.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record UpdateAfterProposalRequest(
        @NotNull UUID proposalId,
        @NotBlank String promoCalculationId,
        @NotBlank String currency,
        @NotNull @DecimalMin("0.00") BigDecimal subtotalAmount,
        @NotNull @DecimalMin("0.00") BigDecimal itemDiscountAmount,
        @NotNull @DecimalMin("0.00") BigDecimal orderDiscountAmount,
        @NotNull @DecimalMin("0.00") BigDecimal deliveryFeeAmount,
        @NotNull @DecimalMin("0.00") BigDecimal serviceFeeAmount,
        @NotNull @DecimalMin("0.00") BigDecimal smallOrderFeeAmount,
        @NotNull @DecimalMin("0.00") BigDecimal totalAmount,
        List<String> selectedPromoCodes,
        @NotEmpty List<@Valid UpdateAfterProposalItemRequest> items
) {
}
