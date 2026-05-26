package com.sebet.cartservice.cart.delivery.client;

import com.sebet.cartservice.cart.delivery.dto.DeliveryAvailabilityRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryAvailabilityResponse;
import com.sebet.cartservice.cart.delivery.dto.DeliveryCheckoutQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryCheckoutQuoteResponse;
import com.sebet.cartservice.cart.delivery.dto.DeliveryFeeQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryFeeQuoteResponse;
import com.sebet.cartservice.cart.delivery.dto.DeliveryScheduleQuoteRequest;
import com.sebet.cartservice.cart.delivery.dto.DeliveryScheduleQuoteResponse;

public interface DeliveryAvailabilityClient {
    DeliveryAvailabilityResponse checkAvailability(DeliveryAvailabilityRequest request);
    DeliveryFeeQuoteResponse getFeeQuote(DeliveryFeeQuoteRequest request);
    DeliveryScheduleQuoteResponse getScheduledQuote(DeliveryScheduleQuoteRequest request);

    /**
     * Unified checkout quote: checks delivery availability AND fetches fee quotes
     * for all baskets (ASAP and scheduled) in a single HTTP call.
     *
     * <p>Per-basket result carries {@code available} flag plus the full option list.
     * If {@code available == false}, {@code availableOptions} is empty.
     * If {@code available == true} but {@code availableOptions} is empty, the
     * requested slot was rejected (scheduled) or fee could not be computed.
     */
    DeliveryCheckoutQuoteResponse getCheckoutQuote(DeliveryCheckoutQuoteRequest request);
}
