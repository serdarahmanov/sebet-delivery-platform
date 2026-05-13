package com.sebet.cartservice.cart.controller;


import com.sebet.cartservice.cart.dto.AddCartItemRequest;
import com.sebet.cartservice.cart.dto.ApplyPromoCodeRequest;
import com.sebet.cartservice.cart.dto.CartResponse;
import com.sebet.cartservice.cart.dto.UpdateCartItemRequest;
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

    @GetMapping
    public ResponseEntity<CartResponse>  getCart(
            @RequestHeader("X-User-Id") String userId

    ){
        CartResponse response = cartService.getCart(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping ("/items")
    public ResponseEntity<CartResponse>  addItemToCart( @Valid @RequestBody AddCartItemRequest request,
                                                        @RequestHeader("X-User-Id") String userId){
        CartResponse response = cartService.addItemToCart(userId,request);
        return ResponseEntity.ok(response);
    }


    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<CartResponse> updateCartItemQuantity(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String cartItemId, @Valid @RequestBody UpdateCartItemRequest request){
        CartResponse response = cartService.updateCartItemQuantity(userId,cartItemId,request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<CartResponse> removerCartItem(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String cartItemId){
        CartResponse response = cartService.removeCartItem(userId,cartItemId);
        return ResponseEntity.ok(response);
    }

    /**
     * Apply promo code to one store basket
     *
     * POST /api/cart/store-baskets/{storeId}/promo-codes
     */
    @PostMapping("/store-baskets/{storeId}/promo-codes")
    public ResponseEntity<CartResponse> applyPromoCode(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String storeId,
                                                       @Valid @RequestBody ApplyPromoCodeRequest request){
        CartResponse response = cartService.applyPromoCode(userId,storeId, request);
        return ResponseEntity.ok(response);
    }



    /**
     * Remove promo code from one store basket
     *
     * DELETE /api/cart/store-baskets/{storeId}/promo-codes/{code}
     */
    @DeleteMapping("/store-baskets/{storeId}/promo-codes/{code}")
    public ResponseEntity<CartResponse> removePromoCode(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String storeId, @PathVariable String code){
        CartResponse response = cartService.removePromoCode(userId,storeId, code);
        return ResponseEntity.ok(response);
    }


    @PostMapping("/validate")
    public ResponseEntity<CartResponse> validateCart( @RequestHeader("X-User-Id") String userId){
        CartResponse response = cartService.validateCart(userId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping()
    public ResponseEntity<Void> deleteCart( @RequestHeader("X-User-Id") String userId){
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/store-baskets/{basketId}")
    public ResponseEntity<CartResponse> clearBasket(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String basketId){

        CartResponse response = cartService.clearBasket(userId,basketId);
        return ResponseEntity.ok(response);
    }


}
