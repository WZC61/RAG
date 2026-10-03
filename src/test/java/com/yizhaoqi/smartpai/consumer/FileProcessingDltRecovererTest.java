package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.service.FileContentProcessingService;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Standard DLT publisher and error handler, with Kafka transport mocked (no Embedded Kafka). */
class FileProcessingDltRecovererTest {
    private KafkaTemplate<String, Object> kafka;
    private final FileContentRepository rows = mock(FileContentRepository.class);
    private final FileContentProcessingService contents = spy(new FileContentProcessingService(rows));
    private FileContent content;
    private FileProcessingTask task;
    private ConsumerRecord<String, Object> record;
    private ConsumerRecordRecoverer recoverer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        ProducerFactory<String, Object> producers = mock(ProducerFactory.class);
        when(producers.getConfigurationProperties()).thenReturn(Map.of("delivery.timeout.ms", 1000));
        when(kafka.getProducerFactory()).thenReturn(producers);
        when(kafka.isTransactional()).thenReturn(true);
        when(kafka.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaOperations.OperationsCallback<String, Object, Object> callback = invocation.getArgument(0);
            return callback.doInOperations(kafka);
        });
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, Object> sent = invocation.getArgument(0);
            var metadata = new RecordMetadata(new TopicPartition(sent.topic(), sent.partition()), 0, 0, 0L, 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(sent, metadata));
        });
        content = new FileContent();
        content.setFileMd5("md5"); content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        when(rows.findForUpdate("md5")).thenReturn(Optional.of(content));
        task = new FileProcessingTask();
        task.setTaskType(FileProcessingTask.TASK_TYPE_PROCESS_CONTENT);
        task.setFileMd5("md5"); task.setObjectPath("merged/md5");
        task.setProcessingGeneration(1L); task.setEventId("PROCESS_CONTENT:md5:1");
        record = new ConsumerRecord<>("file-processing-topic1", 2, 7, "md5", task);
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "fileProcessingDltTopic", "file-processing-dlt");
        recoverer = config.fileProcessingRecoverer(kafka, contents);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exhaustedRetriesPublishToOriginalPartitionThenMarkCurrentGenerationFailed() {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(0, 4));
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        Exception failure = new IllegalStateException("vectorization exhausted");
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertFalse(handler.handleOne(failure, record, consumer, container));
            assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
            verify(kafka, never()).send(any(ProducerRecord.class));
        }
        assertTrue(handler.handleOne(failure, record, consumer, container));
        assertEquals(FileContent.ProcessingStatus.FAILED, content.getProcessingStatus());
        assertEquals("vectorization exhausted", content.getProcessingError());
        ArgumentCaptor<ProducerRecord<String, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        var order = inOrder(kafka, contents);
        order.verify(kafka).send(sent.capture());
        order.verify(contents).failed("md5", 1, failure);
        assertEquals("file-processing-dlt", sent.getValue().topic());
        assertEquals(2, sent.getValue().partition());
        assertEquals("md5", sent.getValue().key());
        assertSame(task, sent.getValue().value());
        verify(kafka).executeInTransaction(any());
    }

    @Test
    void dltSendFailureNeverMarksContentFailed() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")));
        assertThrows(RuntimeException.class, () -> recoverer.accept(record, new IllegalStateException("business failed")));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        verify(contents, never()).failed(anyString(), anyLong(), any());
    }

    @Test
    void dltTransactionCommitFailureNeverMarksContentFailedEvenAfterSendAck() {
        doAnswer(invocation -> {
            KafkaOperations.OperationsCallback<String, Object, Object> callback = invocation.getArgument(0);
            callback.doInOperations(kafka);
            throw new IllegalStateException("DLT transaction commit failed");
        }).when(kafka).executeInTransaction(any());
        assertThrows(RuntimeException.class, () -> recoverer.accept(record, new IllegalStateException("business failed")));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        verify(contents, never()).failed(anyString(), anyLong(), any());
    }

    @Test
    void oldGenerationDltCannotFailTheCurrentGeneration() {
        content.setProcessingGeneration(2);
        recoverer.accept(record, new IllegalStateException("old generation"));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
    }

    @Test
    void dltCannotOverwriteAlreadyIndexedContent() {
        content.setProcessingStatus(FileContent.ProcessingStatus.INDEXED);
        recoverer.accept(record, new IllegalStateException("late failure"));
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
    }

    @Test
    void malformedTaskStillPublishesButNeverUpdatesAnyContent() {
        task.setEventId("invalid");
        recoverer.accept(record, new IllegalStateException("invalid identity"));
        verify(kafka).send(any(ProducerRecord.class));
        verifyNoInteractions(rows);
    }

    @Test
    void absentContentDoesNotPreventDltRecovery() {
        when(rows.findForUpdate("md5")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> recoverer.accept(record, new IllegalStateException("missing content")));
        verify(kafka).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deserializationFailurePublishesOriginalBytesWithoutChangingBusinessState() {
        byte[] malformed = "{bad-json".getBytes(StandardCharsets.UTF_8);
        var headers = new RecordHeaders();
        try (var deserializer = new ErrorHandlingDeserializer<FileProcessingTask>()) {
            deserializer.configure(Map.of(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class,
                    JsonDeserializer.VALUE_DEFAULT_TYPE, FileProcessingTask.class.getName()), false);
            assertNull(deserializer.deserialize(record.topic(), headers, malformed));
        }
        ConsumerRecord<String, Object> broken = new ConsumerRecord<>(record.topic(), 2, 8, "md5", null);
        headers.forEach(header -> broken.headers().add(header));
        recoverer.accept(broken, new IllegalStateException("deserialization failed"));
        ArgumentCaptor<ProducerRecord<String, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertArrayEquals(malformed, (byte[]) sent.getValue().value());
        verifyNoInteractions(rows);
    }

    @Test
    void failureToPersistTerminalStateIsNotSwallowedAfterDltPublication() {
        doThrow(new IllegalStateException("database unavailable")).when(contents).failed(anyString(), anyLong(), any());
        assertThrows(RuntimeException.class, () -> recoverer.accept(record, new IllegalStateException("business failed")));
        verify(kafka).send(any(ProducerRecord.class));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
    }
}
