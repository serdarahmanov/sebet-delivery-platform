package com.sebet.cartservice.cart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class CheckoutExecutorConfig {

    @Bean(name = "checkoutExecutor")
    public Executor checkoutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("checkout-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setTaskDecorator(new MdcTaskDecorator());
        // Explicit AbortPolicy: throws RejectedExecutionException when all threads are busy
        // and the queue is full. CheckoutService catches this specifically and returns 503.
        // CallerRunsPolicy is intentionally avoided — it would tie up the HTTP request thread
        // for the full duration of checkout validation under load.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
