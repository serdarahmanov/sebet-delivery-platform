package com.sebet.cartservice.cart.promotion.client;

import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request.PromotionEvaluationRequest;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionEvaluationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class HttpPromotionClient  implements PromotionClient {

    private final WebClient promotionWebClient;


    public HttpPromotionClient(
            @Qualifier("promotionWebClient") WebClient promotionWebClient
    ) {
        this.promotionWebClient = promotionWebClient;
    }




    @Override
    public PromotionEvaluationResponse evaluatePromotions(PromotionEvaluationRequest request) {
        return promotionWebClient
                .post()
                .uri("/internal/promotions/validate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PromotionEvaluationResponse.class)
                .block();
    }
}
