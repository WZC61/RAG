package com.yizhaoqi.smartpai.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.*;
import org.apache.kafka.common.header.internals.RecordHeaders;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.service.FileContentProcessingService;
import java.util.Map;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaConfigTest {
    private AnnotationConfigApplicationContext context(Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.kafka.bootstrap-servers", "localhost:9092",
                "spring.kafka.topic.file-processing", "file-processing-topic1",
                "spring.kafka.topic.dlt", "file-processing-dlt",
                "spring.kafka.consumer.group-id", "file-processing-group",
                "spring.kafka.consumer.properties.spring.json.trusted.packages", "com.yizhaoqi.smartpai.model"));
        properties.putAll(overrides);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(FileContentProcessingService.class, () -> mock(FileContentProcessingService.class));
        context.registerBean(com.yizhaoqi.smartpai.service.SharedContentAclService.class,
                () -> mock(com.yizhaoqi.smartpai.service.SharedContentAclService.class));
        context.register(KafkaConfig.class);
        context.refresh(); // No KafkaAdmin, listener registration or connection to a broker in this context.
        return context;
    }

    @Test
    void explicitOffsetPollAndDeserializerConfigurationIsApplied() {
        try (var context = context(Map.of())) {
            var config = context.getBean(KafkaConfig.class);
            var props = config.consumerFactory().getConfigurationProperties();
            assertEquals(false, props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
            assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
            assertEquals("read_committed", props.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
            assertEquals(1, props.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
            assertEquals(1800000, props.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
            assertEquals(ErrorHandlingDeserializer.class, props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
            assertEquals(JsonDeserializer.class, props.get(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void factoryUsesThreeRecordConsumersWithoutConsumerTransactionAndTopicsMatch() {
        try (var context = context(Map.of())) {
            var config = context.getBean(KafkaConfig.class);
            var factory = (ConcurrentKafkaListenerContainerFactory<String, Object>) context.getBean("kafkaListenerContainerFactory");
            var container = factory.createContainer("file-processing-topic1");
            assertEquals(3, container.getConcurrency());
            assertEquals(ContainerProperties.AckMode.RECORD, container.getContainerProperties().getAckMode());
            assertTrue(container.getContainerProperties().isSyncCommits());
            assertNull(container.getContainerProperties().getKafkaAwareTransactionManager());
            assertFalse(factory.isBatchListener());
            assertEquals(3, config.fileProcessingNewTopic().numPartitions());
            assertEquals(3, config.fileProcessingDltNewTopic().numPartitions());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrencyAndPollIntervalCanBeOverriddenThroughSpringProperties() {
        try (var context = context(Map.of("spring.kafka.listener.concurrency", "2",
                "spring.kafka.consumer.properties.max.poll.interval.ms", "2400000",
                "spring.kafka.topic.partitions", "4"))) {
            var factory = (ConcurrentKafkaListenerContainerFactory<String, Object>) context.getBean("kafkaListenerContainerFactory");
            assertEquals(2, factory.createContainer("file-processing-topic1").getConcurrency());
            var config = context.getBean(KafkaConfig.class);
            assertEquals(2400000, config.consumerFactory().getConfigurationProperties().get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
            assertEquals(4, config.fileProcessingDltNewTopic().numPartitions());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void producerKeepsMalformedBytesRawAndNormalTasksJson() {
        try (var context = context(Map.of())) {
            var factory = (DefaultKafkaProducerFactory<String, Object>) context.getBean("producerFactory");
            var serializer = factory.getValueSerializer();
            byte[] malformed = "{bad-json".getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(malformed, serializer.serialize("file-processing-dlt", new RecordHeaders(), malformed));
            FileProcessingTask task = new FileProcessingTask();
            task.setFileMd5("md5");
            String json = new String(serializer.serialize("file-processing-topic1", new RecordHeaders(), task), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"fileMd5\":\"md5\""));
        }
    }

    @Test
    void malformedJsonReturnsErrorHeaderInsteadOfThrowingAtPollDeserialization() {
        try (var context = context(Map.of()); var deserializer = new ErrorHandlingDeserializer<FileProcessingTask>()) {
            var props = context.getBean(KafkaConfig.class).consumerFactory().getConfigurationProperties();
            deserializer.configure(props, false);
            var headers = new RecordHeaders();
            assertNull(deserializer.deserialize("file-processing-topic1", headers, "{bad-json".getBytes(StandardCharsets.UTF_8)));
            assertNotNull(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER));
        }
    }

    @Test
    void dltDeclarationKeepsAtLeastThreePartitionsEvenWithLegacyOverride() {
        try (var context = context(Map.of("spring.kafka.topic.partitions", "1"))) {
            assertEquals(3, context.getBean(KafkaConfig.class).fileProcessingDltNewTopic().numPartitions());
        }
    }
    @Test
    void consumerOnlyReadsCommittedKafkaTransactions() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "fileProcessingGroupId", "file-processing-group");
        ReflectionTestUtils.setField(config, "trustedPackages", "com.yizhaoqi.smartpai.model");
        var factory = (DefaultKafkaConsumerFactory<String, Object>) config.consumerFactory();
        assertEquals("read_committed", factory.getConfigurationProperties().get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
    }
}
