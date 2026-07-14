package com.sebet.order_service.internal.dto.request;

import com.sebet.order_service.shared.enums.ProductUnit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateAfterProposalItemRequest(
        @NotBlank String productId,
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
        @NotNull ProductUnit unit,
        @NotNull @DecimalMin("0.00") BigDecimal unitPriceAmount,
        @NotNull @DecimalMin("0.00") BigDecimal grossAmount,
        @NotNull @DecimalMin("0.00") BigDecimal discountAmount,
        @NotNull @DecimalMin("0.00") BigDecimal netAmount
) {
}
