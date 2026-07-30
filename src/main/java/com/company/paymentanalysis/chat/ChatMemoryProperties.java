package com.company.paymentanalysis.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.memory")
public record ChatMemoryProperties(
        boolean redisEnabled,
        String keyPrefix,
        int ttlDays,
        int maxConversations) {
}
