package com.sebet.order_service.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserIdInterceptor userIdInterceptor;
    private final StoreIdInterceptor storeIdInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Customer-facing endpoints — require X-User-Id
        registry.addInterceptor(userIdInterceptor)
                .addPathPatterns("/api/v1/orders/**");

        // Store-facing endpoints — require X-Store-Id
        registry.addInterceptor(storeIdInterceptor)
                .addPathPatterns("/api/v1/store/**");
    }
}
