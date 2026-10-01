package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedissonConfigTest {
    @Test
    void reusesResolvedConnectionCredentialsDatabaseAndTimeoutsWithoutConnecting() {
        RedisConnectionDetails connection = mock(RedisConnectionDetails.class);
        when(connection.getStandalone()).thenReturn(RedisConnectionDetails.Standalone.of("localhost", 6380, 2));
        when(connection.getUsername()).thenReturn("app");
        when(connection.getPassword()).thenReturn("p@ss");
        RedisProperties properties = new RedisProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setTimeout(Duration.ofSeconds(3));
        Config config = new RedissonConfig().buildConfig(connection, properties);
        var server = config.useSingleServer();
        assertEquals("redis://localhost:6380", server.getAddress());
        assertEquals(2, server.getDatabase());
        assertEquals("app", server.getUsername());
        assertEquals("p@ss", server.getPassword());
        assertEquals(2000, server.getConnectTimeout());
        assertEquals(3000, server.getTimeout());
        assertTrue(config.isLazyInitialization());
    }

    @Test
    void respectsSslUrlAndIpv6Host() {
        RedisConnectionDetails connection = mock(RedisConnectionDetails.class);
        when(connection.getStandalone()).thenReturn(RedisConnectionDetails.Standalone.of("::1", 6379));
        RedisProperties properties = new RedisProperties();
        properties.setUrl("rediss://[::1]:6379");
        var config = new RedissonConfig().buildConfig(connection, properties);
        assertEquals("rediss://[::1]:6379", config.useSingleServer().getAddress());
    }

    @Test
    void unsupportedTopologyFailsExplicitlyInsteadOfUsingUnrelatedStandaloneRedis() {
        RedisConnectionDetails connection = mock(RedisConnectionDetails.class);
        assertThrows(IllegalArgumentException.class,
                () -> new RedissonConfig().buildConfig(connection, new RedisProperties()));
    }
}
