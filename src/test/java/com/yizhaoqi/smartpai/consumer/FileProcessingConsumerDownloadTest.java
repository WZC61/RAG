package com.yizhaoqi.smartpai.consumer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.parsing.description.FigureDescriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock HTTP streams only: verify lifecycle and actual Consumer log/exception diagnostics. */
class FileProcessingConsumerDownloadTest {
    private static final String URL = "https://storage.invalid/merged/md5?X-Amz-Signature=private-signature";
    private static final String BOS = "https://bos.invalid/result?authorization=bce-auth-v1/private-token";
    private final ParseService parse = mock(ParseService.class);
    private final VectorizationService vectors = mock(VectorizationService.class);
    private final HttpURLConnection connection = mock(HttpURLConnection.class);
    private InputStream response;
    private InputStream error;
    private FileProcessingConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        response = spy(new ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        error = mock(InputStream.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(response);
        when(connection.getErrorStream()).thenReturn(error);
        consumer = new FileProcessingConsumer(parse, vectors, mock(DocumentService.class), url -> {
            assertEquals(URL, url.toString()); // The actual signed request URL is untouched.
            return connection;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 206})
    void successfulStreamRemainsUsableUntilCallerClosesIt(int status) throws Exception {
        when(connection.getResponseCode()).thenReturn(status);
        InputStream result = consumer.downloadFileFromStorage(URL);
        verify(connection, never()).disconnect();
        assertEquals("content", new String(result.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        verify(response, never()).close();
        result.close();
        result.close(); // Legacy parser and Consumer may both close the same stream.
        verify(response, times(1)).close();
        verify(connection, times(1)).disconnect();
        verify(error, never()).close();
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void failedHttpStatusClosesErrorBodyAndDisconnects(int status) throws Exception {
        when(connection.getResponseCode()).thenReturn(status);
        assertThrows(IOException.class, () -> consumer.downloadFileFromStorage(URL));
        verify(error).close();
        verify(connection).disconnect();
        verify(connection, never()).getInputStream();
    }

    @Test
    void inputStreamFailureClosesAvailableErrorBodyAndDisconnects() throws Exception {
        when(connection.getInputStream()).thenThrow(new IOException("Cannot open " + URL));
        IOException failure = assertThrows(IOException.class, () -> consumer.downloadFileFromStorage(URL));
        assertRedacted(stack(failure));
        verify(error).close();
        verify(connection).disconnect();
    }

    @Test
    void statusReadFailureStillDisconnects() throws Exception {
        when(connection.getResponseCode()).thenThrow(new IOException("Cannot connect " + URL));
        assertThrows(IOException.class, () -> consumer.downloadFileFromStorage(URL));
        verify(error).close();
        verify(connection).disconnect();
    }

    @Test
    void missingErrorBodyStillDisconnects() throws Exception {
        when(connection.getResponseCode()).thenReturn(500);
        when(connection.getErrorStream()).thenReturn(null);
        assertThrows(IOException.class, () -> consumer.downloadFileFromStorage(URL));
        verify(connection).disconnect();
    }

    @Test
    void errorBodyCloseFailureDoesNotPreventDisconnectOrExposeUrls() throws Exception {
        when(connection.getResponseCode()).thenReturn(500);
        doThrow(new IOException("Closing " + BOS)).when(error).close();
        IOException failure = assertThrows(IOException.class, () -> consumer.downloadFileFromStorage(URL));
        assertRedacted(stack(failure));
        verify(connection).disconnect();
    }

    @Test
    void successBodyCloseFailureStillDisconnects() throws Exception {
        doThrow(new IOException("close failed")).when(response).close();
        InputStream result = consumer.downloadFileFromStorage(URL);
        assertThrows(IOException.class, result::close);
        verify(connection).disconnect();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consumerLogsOnlyIdentitiesAndFrameworkExceptionHasNoPresignedUrl(boolean fails) throws Exception {
        FileContentProcessingService contents = mock(FileContentProcessingService.class);
        when(contents.checkpoint("md5", 1)).thenReturn(FileContent.ProcessingStatus.MERGED, FileContent.ProcessingStatus.PARSED);
        FigureDescriptionService descriptions = mock(FigureDescriptionService.class);
        when(descriptions.describe("md5", 1)).thenReturn(true);
        ReflectionTestUtils.setField(consumer, "figureDescriptions", descriptions);
        FileUploadRepository files = mock(FileUploadRepository.class);
        FileUpload access = new FileUpload(); access.setUserId("1"); access.setOrgTag("TEAM");
        when(files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1")).thenReturn(Optional.of(access));
        ReflectionTestUtils.setField(consumer, "contentProcessing", contents);
        ReflectionTestUtils.setField(consumer, "files", files);
        when(vectors.vectorizeWithUsage("md5", 1L, "1", "TEAM", false, "1"))
                .thenReturn(new VectorizationService.VectorizationUsageResult(1, 1, "model"));
        var nonPdf = mock(com.yizhaoqi.smartpai.parsing.NonPdfDocumentParsingService.class);
        ReflectionTestUtils.setField(consumer, "nonPdfParsingService", nonPdf);
        when(nonPdf.parseAndPersist(anyString(), anyLong(), any(), any())).thenReturn(true);
        if (fails) doThrow(new IOException("Resource " + BOS, new IOException("Original " + URL)))
                .when(nonPdf).parseAndPersist(anyString(), anyLong(), any(), any());
        FileProcessingTask task = new FileProcessingTask("md5", URL, "file.txt", null, null, false,
                FileProcessingTask.TASK_TYPE_PROCESS_CONTENT, "1");
        task.setObjectPath("merged/md5"); task.setProcessingGeneration(1L); task.setEventId("PROCESS_CONTENT:md5:1");
        Logger logger = (Logger) LoggerFactory.getLogger(FileProcessingConsumer.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>(); capture.start(); logger.addAppender(capture);
        try {
            if (fails) assertRedacted(stack(assertThrows(RuntimeException.class, () -> consumer.processTask(task))));
            else {
                consumer.processTask(task);
                verify(contents).indexed(eq("md5"), eq(1L), any());
            }
            StringBuilder messages = new StringBuilder();
            for (ILoggingEvent event : capture.list) {
                messages.append(event.getFormattedMessage());
                if (event.getThrowableProxy() != null)
                    messages.append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            assertTrue(messages.toString().contains("eventId=PROCESS_CONTENT:md5:1"));
            assertTrue(messages.toString().contains("objectPath=merged/md5"));
            assertRedacted(messages.toString());
            verify(connection).disconnect();
            verify(response).close();
        } finally {
            logger.detachAppender(capture); capture.stop();
        }
    }

    private static String stack(Throwable failure) {
        StringWriter text = new StringWriter(); failure.printStackTrace(new PrintWriter(text)); return text.toString();
    }

    private static void assertRedacted(String text) {
        assertFalse(text.contains("X-Amz-Signature"));
        assertFalse(text.contains("authorization=bce-auth-v1"));
        assertFalse(text.contains(URL)); assertFalse(text.contains(BOS));
        assertFalse(text.contains("private-signature")); assertFalse(text.contains("private-token"));
    }
}
