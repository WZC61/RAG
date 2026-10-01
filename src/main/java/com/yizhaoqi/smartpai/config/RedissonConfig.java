package com.yizhaoqi.smartpai.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Reuse Boot's resolved Redis connection (including URL credentials and database). */
@Configuration
public class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(RedisConnectionDetails connection, RedisProperties properties) {
        return Redisson.create(buildConfig(connection, properties));
    }

    Config buildConfig(RedisConnectionDetails connection, RedisProperties properties) {
        RedisConnectionDetails.Standalone standalone = connection.getStandalone();
        if (standalone == null || connection.getCluster() != null || connection.getSentinel() != null) {
            throw new IllegalArgumentException("Merge Redisson client currently requires standalone Redis");
        }
        if (properties.getSsl().getBundle() != null) {
            throw new IllegalArgumentException("Merge Redisson client does not support Redis SSL bundles");
        }
        Config config = new Config();
        // Redis outages are reported by the merge operation rather than during application startup.
        config.setLazyInitialization(true);
        boolean ssl = properties.getSsl().isEnabled()
                || (properties.getUrl() != null && properties.getUrl().startsWith("rediss://"));
        String host = standalone.getHost();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        SingleServerConfig server = config.useSingleServer()
                .setAddress((ssl ? "rediss://" : "redis://") + host + ":" + standalone.getPort())
                .setDatabase(standalone.getDatabase());
        if (StringUtils.hasText(connection.getUsername())) server.setUsername(connection.getUsername());
        if (StringUtils.hasText(connection.getPassword())) server.setPassword(connection.getPassword());
        if (properties.getConnectTimeout() != null) {
            server.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        }
        if (properties.getTimeout() != null) {
            server.setTimeout(Math.toIntExact(properties.getTimeout().toMillis()));
        }
        return config;
    }
}
