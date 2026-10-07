package com.yizhaoqi.smartpai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.KafkaListenerConfigUtils;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.redisson.api.RedissonClient;

// Explicit properties outrank the local .env and prevent a developer database from being
// selected by its active profile. This test checks wiring, not external services.
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:paismart-context;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "elasticsearch.init.enabled=false", "knowledge.bootstrap.enabled=false", "admin.bootstrap.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1"
})
class SmartPaiApplicationTests {
    @MockitoBean RedissonClient redisson;
    @MockitoBean KafkaAdmin kafkaAdmin;
    @MockitoBean KafkaTemplate<String, Object> kafka;
    @MockitoBean(name = KafkaListenerConfigUtils.KAFKA_LISTENER_ENDPOINT_REGISTRY_BEAN_NAME)
    KafkaListenerEndpointRegistry listeners;

    @Test
    void contextLoads() {
    }

}
