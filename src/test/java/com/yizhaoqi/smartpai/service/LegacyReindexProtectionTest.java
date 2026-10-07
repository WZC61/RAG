package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyReindexProtectionTest {
    @Test void delayedUploadChecksBothContentAndSharedRelations() {
        var service=new DocumentService();var contents=mock(FileContentRepository.class);var files=mock(FileUploadRepository.class);
        ReflectionTestUtils.setField(service,"fileContents",contents);ReflectionTestUtils.setField(service,"fileUploadRepository",files);
        when(contents.findByFileMd5("content")).thenReturn(Optional.of(new FileContent()));
        when(files.countByFileMd5("shared")).thenReturn(2L);
        assertTrue(service.isLegacyUploadSuperseded("content"));
        assertTrue(service.isLegacyUploadSuperseded("shared"));
        assertFalse(service.isLegacyUploadSuperseded("legacy"));
    }
    @Test void contentLevelReindexAndRetryAreRejectedBeforeDestructiveWork() {
        var service=new DocumentService();var contents=mock(FileContentRepository.class);var files=mock(FileUploadRepository.class);
        var search=mock(ElasticsearchService.class);var parse=mock(ParseService.class);var vectors=mock(DocumentVectorRepository.class);
        ReflectionTestUtils.setField(service,"fileContents",contents);ReflectionTestUtils.setField(service,"fileUploadRepository",files);
        ReflectionTestUtils.setField(service,"elasticsearchService",search);ReflectionTestUtils.setField(service,"parseService",parse);
        ReflectionTestUtils.setField(service,"documentVectorRepository",vectors);
        when(contents.findByFileMd5("abc")).thenReturn(Optional.of(new FileContent()));
        assertThrows(com.yizhaoqi.smartpai.exception.CustomException.class,()->service.reindexDocument("abc","A"));
        assertThrows(com.yizhaoqi.smartpai.exception.CustomException.class,()->service.enqueueAsyncVectorizationRetry("abc","A"));
        verifyNoInteractions(files,search,parse,vectors);
    }
    @Test void legacyFileWithSeveralReferencesIsAlsoProtected() {
        var service=new DocumentService();var contents=mock(FileContentRepository.class);var files=mock(FileUploadRepository.class);
        ReflectionTestUtils.setField(service,"fileContents",contents);ReflectionTestUtils.setField(service,"fileUploadRepository",files);
        when(files.countByFileMd5("abc")).thenReturn(2L);
        assertThrows(com.yizhaoqi.smartpai.exception.CustomException.class,()->service.reindexDocument("abc","A"));
        verify(files,never()).findFirstByFileMd5OrderByCreatedAtDesc(any());
    }
}
