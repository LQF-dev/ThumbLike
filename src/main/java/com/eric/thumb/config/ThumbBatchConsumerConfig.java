package com.eric.thumb.config;

import org.apache.pulsar.client.api.BatchReceivePolicy;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.pulsar.annotation.PulsarListenerConsumerBuilderCustomizer;

import java.util.concurrent.TimeUnit;

/**
 * 批量消费者配置 - 专门用于非热点数据的大批量处理
 * 
 * 配置特点：
 * 1. 大批量：1000条/批，最大化批处理效率
 * 2. 长超时：30秒，允许更长等待时间以积累更大批次
 * 3. 专门优化：针对非热点数据的特性进行调优
 * 
 * @author Eric
 */
@Configuration
public class ThumbBatchConsumerConfig {

    /**
     * 批量消费者配置
     * 专门处理非热点数据，追求吞吐量而非延迟
     */
    @Bean("batchConsumerConfig")
    public PulsarListenerConsumerBuilderCustomizer<Object> batchConsumerConfig() {
        return new PulsarListenerConsumerBuilderCustomizer<Object>() {
            @Override
            public void customize(ConsumerBuilder<Object> consumerBuilder) {
                consumerBuilder.batchReceivePolicy(
                        BatchReceivePolicy.builder()
                                // 大批量：1000条/批，适合非热点数据的批量处理
                                .maxNumMessages(1000)
                                // 长超时：30秒，允许积累更大批次
                                .timeout(30, TimeUnit.SECONDS)
                                // 大缓冲：减少网络往返
                                .maxNumBytes(10 * 1024 * 1024) // 10MB
                                .build()
                );
                
                // 设置接收队列大小
                consumerBuilder.receiverQueueSize(2000);
                
                // 设置消费者名称
                consumerBuilder.consumerName("thumb-batch-consumer");
            }
        };
    }

    /**
     * 热点数据消费者配置（单条处理）
     * 追求低延迟而非吞吐量
     */
    @Bean("hotConsumerConfig")
    public PulsarListenerConsumerBuilderCustomizer<Object> hotConsumerConfig() {
        return new PulsarListenerConsumerBuilderCustomizer<Object>() {
            @Override
            public void customize(ConsumerBuilder<Object> consumerBuilder) {
                // 热点数据不使用批量接收，追求低延迟
                consumerBuilder.receiverQueueSize(100);
                consumerBuilder.consumerName("thumb-hot-consumer");
            }
        };
    }
} 