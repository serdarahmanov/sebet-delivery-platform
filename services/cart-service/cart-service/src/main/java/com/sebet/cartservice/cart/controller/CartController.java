package com.sebet.cartservice.cart.controller;


import com.sebet.cartservice.cart.checkout.service.CheckoutService;
import com.sebet.cartservice.cart.dto.AddCartItemRequest;
import com.sebet.cartservice.cart.dto.ApplyPromoCodesRequest;
import com.sebet.cartservice.cart.dto.BatchUpsertCartItemsRequest;
import com.sebet.cartservice.cart.dto.BatchUpsertResponse;
import com.sebet.cartservice.cart.dto.CheckoutConfirmRequest;
import com.sebet.cartservice.cart.dto.CheckoutConfirmResponse;
import com.sebet.cartservice.cart.dto.CheckoutInitiateRequest;
import com.sebet.cartservice.cart.dto.CheckoutInitiateResponse;
import com.sebet.cartservice.cart.dto.SetDeliveryMethodRequest;
import com.sebet.cartservice.cart.dto.ClaimPromoCodeRequest;
import com.sebet.cartservice.cart.dto.SetBasketAddressRequest;
import com.sebet.cartservice.cart.dto.UpdateCartItemRequest;
import com.sebet.cartservice.cart.dto.getcart.CartSummaryResponse;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.service.CartResponseCacheService;
import com.sebet.cartservice.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;
    private final CartResponseCacheService cartResponseCacheService;
    private final CheckoutService checkoutService;

    @GetMapping
    public ResponseEntity<CartSummaryResponse>  getCart(
            @RequestHeader("X-User-Id") String userId

    ){
        CartSummaryResponse response = cartResponseCacheService.getOrBuildCartSummaryResponse(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping ("/items")
    public ResponseEntity<com.sebet.cartservice.cart.dto.Cart>  addItemToCart( @Valid @RequestBody AddCartItemRequest request,
                                                        @RequestHeader("X-User-Id") String userId){
        com.sebet.cartservice.cart.dto.Cart response = cartService.addItemToCart(userId,request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/items/batch")
    public ResponseEntity<BatchUpsertResponse> batchUpsertItems(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody BatchUpsertCartItemsRequest request
    ) {
        return ResponseEntity.ok(cartService.batchUpsertItems(userId, request));
    }


    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<StoreBasket> updateCartItemQuantity(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String cartItemId, @Valid @RequestBody UpdateCartItemRequest request){
        return ResponseEntity.ok(cartService.updateCartItemQuantity(userId, cartItemId, request));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<StoreBasket> removerCartItem(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String cartItemId){
        return ResponseEntity.ok(cartService.removeCartItem(userId, cartItemId));
    }

    @PatchMapping("/store-baskets/{storeId}/address")
    public ResponseEntity<StoreBasket> setBasketAddress(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String storeId,
            @Valid @RequestBody SetBasketAddressRequest request) {
        return ResponseEntity.ok(cartService.setBasketAddress(userId, storeId, request));
    }

    @PostMapping("/store-baskets/{storeId}/promo-codes/claim")
    public ResponseEntity<StoreBasket> claimPromoCode(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String storeId,
            @Valid @RequestBody ClaimPromoCodeRequest request) {
        return ResponseEntity.ok(cartService.claimPromoCode(userId, storeId, request));
    }

    @PostMapping("/store-baskets/{storeId}/promo-codes/apply")
    public ResponseEntity<StoreBasket> applyPromoCodes(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String storeId,
            @Valid @RequestBody ApplyPromoCodesRequest request) {
        return ResponseEntity.ok(cartService.applyPromoCodes(userId, storeId, request));
    }

    @DeleteMapping("/store-baskets/{storeId}/promo-codes/{code}")
    public ResponseEntity<StoreBasket> removePromoCode(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String storeId, @PathVariable String code){
        return ResponseEntity.ok(cartService.deletePromoCode(userId, storeId, code));
    }


    @PostMapping("/validate")
    public ResponseEntity<com.sebet.cartservice.cart.dto.Cart> validateCart( @RequestHeader("X-User-Id") String userId){
        com.sebet.cartservice.cart.dto.Cart response = cartService.validateCart(userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/store-baskets/{basketId}")
    public ResponseEntity<StoreBasket> getStoreBasket(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String basketId
    ) {
        return ResponseEntity.ok(cartService.getStoreBasket(userId, basketId));
    }

    @PatchMapping("/store-baskets/{storeId}/delivery-method")
    public ResponseEntity<StoreBasket> setDeliveryMethod(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String storeId,
            @Valid @RequestBody SetDeliveryMethodRequest request) {
        return ResponseEntity.ok(cartService.setDeliveryMethod(userId, storeId, request));
    }

    @PostMapping("/store-baskets/{basketId}/checkout/initiate")
    public ResponseEntity<CheckoutInitiateResponse> initiateCheckout(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String basketId,
            @Valid @RequestBody CheckoutInitiateRequest request
    ) {
        return ResponseEntity.ok(checkoutService.initiateCheckout(userId, basketId, request));
    }

    @PostMapping("/store-baskets/{basketId}/checkout/confirm")
    public ResponseEntity<CheckoutConfirmResponse> confirmCheckout(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String basketId,
            @Valid @RequestBody CheckoutConfirmRequest request
    ) {
        return ResponseEntity.ok(checkoutService.confirmCheckout(userId, basketId, request));
    }

    @DeleteMapping()
    public ResponseEntity<Void> deleteCart( @RequestHeader("X-User-Id") String userId){
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/store-baskets/{basketId}")
    public ResponseEntity<Void> clearBasket(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String basketId){
        cartService.clearBasket(userId, basketId);
        return ResponseEntity.noContent().build();
    }


}
