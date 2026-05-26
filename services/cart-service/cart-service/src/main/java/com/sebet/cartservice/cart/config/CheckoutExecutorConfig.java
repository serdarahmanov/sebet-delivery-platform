package com.sebet.cartservice.cart.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class CheckoutExecutorConfig {

    private final MeterRegistry meterRegistry;

    public CheckoutExecutorConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

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

        // Bind the underlying ThreadPoolExecutor to Micrometer so queue depth and
        // saturation are visible in Prometheus. Exposes:
        //   checkout.executor.pool.size    — live thread count
        //   checkout.executor.pool.core    — core size (4)
        //   checkout.executor.pool.max     — max size (16)
        //   checkout.executor.active       — threads currently executing a task
        //   checkout.executor.queued       — tasks waiting in the 100-slot queue
        //   checkout.executor.queue.remaining — free slots left in the queue
        //   checkout.executor.completed.tasks.total — throughput counter
        ExecutorServiceMetrics.monitor(
                meterRegistry,
                executor.getThreadPoolExecutor(),
                "checkout.executor",
                List.of()
        );

        return executor;
    }
}
