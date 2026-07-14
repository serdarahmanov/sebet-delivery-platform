package com.sebet.order_service.internal.dto.response;

public record UpdateAfterProposalResponse(
        String orderId,
        String status,
        String proposalId,
        String appliedAt
) {
}
