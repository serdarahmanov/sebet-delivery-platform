package com.sebet.order_service.shared.enums;

public enum ProposalStatus {

    /** Proposal is live and waiting for the customer's response. */
    ACTIVE,

    /**
     * Customer accepted the proposal (ACCEPT_ALL or ACCEPT_WITH_MODIFICATIONS).
     * Waiting for promo service to recalculate discounts and call back.
     */
    ACCEPTED,

    /**
     * Promo service called back with recalculated totals.
     * Order quantities and amounts updated; order transitioned to CONFIRMED.
     */
    APPLIED,

    /**
     * Customer chose CANCEL_ORDER in response to the proposal.
     * Order transitioned to CANCELLED.
     */
    REJECTED,

    /**
     * The active proposal was retracted without cancelling the order.
     * Order returned to CONFIRMED via cancel-active-proposal.
     */
    CANCELLED,

    /**
     * Customer response window expired.
     * Order transitioned to CANCELLED via cancel-proposal-and-order.
     */
    TIMED_OUT,

    /**
     * Order was force-cancelled by an internal system or admin action while the
     * proposal was active (system-cancel or admin-cancel on AWAITING_CUSTOMER_RESPONSE).
     */
    SYSTEM_CANCELLED,

    /**
     * The store cancelled the order while the proposal was still active
     * (store cancel-order on AWAITING_CUSTOMER_RESPONSE).
     */
    STORE_CANCELLED
}
