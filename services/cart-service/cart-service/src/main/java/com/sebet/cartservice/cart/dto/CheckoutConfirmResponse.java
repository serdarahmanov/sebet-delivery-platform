package com.sebet.cartservice.cart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutConfirmResponse(
        String status,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<BlockingIssue> blockingIssues
) {
    public record BlockingIssue(
            String scope,
            String code,
            String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) String cartItemId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String storeId
    ) {}

    public static CheckoutConfirmResponse confirmed() {
        return new CheckoutConfirmResponse("CONFIRMED", List.of());
    }

    public static CheckoutConfirmResponse rejected(List<BlockingIssue> issues) {
        return new CheckoutConfirmResponse("REJECTED", issues);
    }
}
