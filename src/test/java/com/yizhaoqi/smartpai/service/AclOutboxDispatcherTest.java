package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.consumer.FileProcessingDltRecoverer;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository;
import org.junit.jupiter.api.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AclOutboxDispatcherTest {
    @Test @SuppressWarnings("unchecked") void aclIsSentWithFileKeyWithoutSignedUrlAndMarkedSent() {
        var mapper = new ObjectMapper(); var repo = mock(ProcessingOutboxRepository.class);
        var state = mock(ProcessingOutboxStatusService.class); var kafka = mock(KafkaTemplate.class);
        var config = mock(KafkaConfig.class); var uploads = mock(UploadService.class);
        var event = AclChangedPayload.event(mapper, "abc", "A"); event.setId(7L);
        when(repo.findByStatusOrderByIdAsc(any(), any())).thenReturn(List.of(event));
        when(config.getFileProcessingTopic()).thenReturn("processing");
        when(kafka.executeInTransaction(any())).thenAnswer(call -> call.<KafkaOperations.OperationsCallback<String,Object,Boolean>>getArgument(0).doInOperations(kafka));
        new ProcessingOutboxDispatcher(repo, state, kafka, config, mapper, uploads).dispatchPending();
        var task = ArgumentCaptor.forClass(FileProcessingTask.class); verify(kafka).send(eq("processing"), eq("abc"), task.capture());
        assertTrue(task.getValue().hasValidAclIdentity()); assertNull(task.getValue().getFilePath()); assertNull(task.getValue().getProcessingGeneration());
        assertEquals("A", task.getValue().getUserId()); verify(state).markSent(7L); verifyNoInteractions(uploads);
    }
    @Test @SuppressWarnings("unchecked") void oneAclSendFailureDoesNotStopBatch() {
        var mapper = new ObjectMapper(); var repo = mock(ProcessingOutboxRepository.class); var state = mock(ProcessingOutboxStatusService.class);
        var kafka = mock(KafkaTemplate.class); var config = mock(KafkaConfig.class);
        var a = AclChangedPayload.event(mapper,"abc",null); a.setId(1L); var b = AclChangedPayload.event(mapper,"def",null); b.setId(2L);
        when(repo.findByStatusOrderByIdAsc(any(),any())).thenReturn(List.of(a,b));
        when(kafka.executeInTransaction(any())).thenThrow(new IllegalStateException("broker down")).thenReturn(true);
        new ProcessingOutboxDispatcher(repo,state,kafka,config,mapper,mock(UploadService.class)).dispatchPending();
        verify(state).recordFailure(eq(1L),anyString()); verify(state).markSent(2L); verify(state,never()).markSent(1L);
    }
    @Test void aclDltRequeuesAfterConfirmedPublicationAndNeverMarksContentFailed() {
        var publish = mock(ConsumerRecordRecoverer.class); var content = mock(FileContentProcessingService.class); var acl = mock(SharedContentAclService.class);
        var task = new FileProcessingTask(); task.setTaskType("ACL_CHANGED"); task.setFileMd5("abc"); task.setEventId("ACL_CHANGED:test");
        var record = new ConsumerRecord<>("processing",0,1L,"abc",task); var failure = new IllegalStateException("ES down");
        new FileProcessingDltRecoverer(publish, content, acl).accept(record,failure);
        var order=inOrder(publish,acl); order.verify(publish).accept(record,failure); order.verify(acl).retryAfterDlt(task); verifyNoInteractions(content);
    }
    @Test void unconfirmedDltDoesNotCreateAnotherAclEvent() {
        var publish=mock(ConsumerRecordRecoverer.class); var acl=mock(SharedContentAclService.class);
        doThrow(new IllegalStateException("Kafka down")).when(publish).accept(any(),any());
        var task=new FileProcessingTask(); task.setTaskType("ACL_CHANGED"); task.setFileMd5("abc"); task.setEventId("ACL_CHANGED:test");
        assertThrows(Exception.class,()->new FileProcessingDltRecoverer(publish,mock(FileContentProcessingService.class),acl)
                .accept(new ConsumerRecord<>("processing",0,1L,"abc",task),new IllegalStateException())); verifyNoInteractions(acl);
    }
}
