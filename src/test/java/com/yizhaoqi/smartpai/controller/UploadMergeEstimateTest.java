package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import io.minio.GetObjectResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadMergeEstimateTest {
    private UploadService uploads;
    private ParseService parse;
    private FileContentRepository contents;
    private UploadController controller;

    @BeforeEach
    void setUp() throws Exception {
        uploads = mock(UploadService.class);
        parse = mock(ParseService.class);
        contents = mock(FileContentRepository.class);
        FileUploadRepository files = mock(FileUploadRepository.class);
        FileUpload file = new FileUpload();
        file.setId(1L); file.setFileMd5("md5"); file.setUserId("1"); file.setStatus(FileUpload.STATUS_UPLOADING);
        when(files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1")).thenReturn(Optional.of(file));
        when(files.updateStatusIfCurrent(1L, FileUpload.STATUS_UPLOADING, FileUpload.STATUS_MERGING)).thenReturn(1);
        when(uploads.mergeChunks(eq("md5"), anyString(), eq("1"))).thenReturn("https://storage.invalid/merged");
        controller = new UploadController(uploads);
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);
        ReflectionTestUtils.setField(controller, "fileContentRepository", contents);
        ReflectionTestUtils.setField(controller, "parseService", parse);
    }

    @Test
    void actualPdfSkipsFullLiteParseEstimateRegardlessOfFilename() throws Exception {
        source("%PDF-1.7\nfake");
        var response = controller.mergeFile(new UploadController.MergeRequest("md5", "document.txt"), "1");
        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(parse, contents);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertFalse(data.containsKey("estimatedEmbeddingTokens"));
        assertFalse(data.containsKey("estimatedChunkCount"));
    }

    @Test
    void nonPdfKeepsExistingEstimateAndStreamStartsAtBeginning() throws Exception {
        source("non-PDF text");
        when(parse.estimateEmbeddingUsage(any())).thenAnswer(invocation -> {
            java.io.InputStream input = invocation.getArgument(0);
            assertEquals("non-PDF text", new String(input.readAllBytes(), StandardCharsets.US_ASCII));
            return new ParseService.EmbeddingEstimate(42, 2);
        });
        var response = controller.mergeFile(new UploadController.MergeRequest("md5", "document.pdf"), "1");
        assertEquals(200, response.getStatusCode().value());
        verify(contents).updateEstimates("md5", 42L, 2);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals(42L, data.get("estimatedEmbeddingTokens"));
        assertEquals(2, data.get("estimatedChunkCount"));
    }

    private void source(String content) throws Exception {
        GetObjectResponse response = new GetObjectResponse(new Headers.Builder().build(), "uploads", "", "merged/md5",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII)));
        when(uploads.getMergedFileStream("md5")).thenReturn(response);
    }
}
