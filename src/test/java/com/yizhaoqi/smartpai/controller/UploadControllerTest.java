package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.OrganizationTag;
import org.apache.commons.codec.digest.DigestUtils;
import com.yizhaoqi.smartpai.model.UploadInitRequest;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.FileTypeValidationService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadControllerTest {

    @Mock
    private UploadService uploadService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private KafkaConfig kafkaConfig;

    @Mock
    private UserService userService;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private FileTypeValidationService fileTypeValidationService;

    @Mock
    private ParseService parseService;

    private UploadController uploadController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadController = new UploadController(uploadService, kafkaTemplate);
        ReflectionTestUtils.setField(uploadController, "kafkaConfig", kafkaConfig);
        ReflectionTestUtils.setField(uploadController, "userService", userService);
        ReflectionTestUtils.setField(uploadController, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadController, "fileTypeValidationService", fileTypeValidationService);
        ReflectionTestUtils.setField(uploadController, "parseService", parseService);
        when(fileTypeValidationService.getSupportedFileTypes()).thenReturn(Set.of("pdf"));
    }

    @ParameterizedTest
    @ValueSource(ints = {FileUpload.STATUS_UPLOADING, FileUpload.STATUS_COMPLETED})
    void initReturnsUploadDecisionWithoutCallingChunkMergeOrKafka(int uploadStatus) throws Exception {
        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(true);
        FileUpload file = new FileUpload();
        file.setId(10L);
        file.setStatus(uploadStatus);
        when(uploadService.initializeUpload("md5", 1024L, "test.pdf", "TEAM_A", false, "1")).thenReturn(file);

        var response = uploadController.initializeUpload(new UploadInitRequest("md5", "test.pdf", 1024L, 1, "TEAM_A", false), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals(uploadStatus == FileUpload.STATUS_COMPLETED, data.get("instantUpload"));
        assertEquals(uploadStatus == FileUpload.STATUS_UPLOADING, data.get("needsUpload"));
        assertEquals(uploadStatus, data.get("status"));
        verify(uploadService, never()).uploadChunk(anyString(), anyInt(), anyLong(), anyString(), any(), anyString(), anyBoolean(), anyString(), anyString());
        verify(uploadService, never()).mergeChunks(anyString(), anyString(), anyString());
        verifyNoInteractions(kafkaTemplate, parseService);
    }

    @Test
    void instantInitCannotBypassOrganizationSizeLimit() {
        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        OrganizationTag org = new OrganizationTag();
        org.setUploadMaxSizeBytes(512L);
        when(userService.getOrganizationTag("TEAM_A")).thenReturn(org);

        var response = uploadController.initializeUpload(new UploadInitRequest("md5", "test.pdf", 1024L, 1, "TEAM_A", false), "1");

        assertEquals(413, response.getStatusCode().value());
        verifyNoInteractions(uploadService);
    }

    @Test
    void initResolvesPrimaryOrganizationAndUsesAuthenticatedUser() {
        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(true);
        when(userService.getUserPrimaryOrg("1")).thenReturn("TEAM_A");
        when(uploadService.initializeUpload("md5", 1024L, "test.pdf", "TEAM_A", false, "1"))
                .thenReturn(new FileUpload());

        assertEquals(200, uploadController.initializeUpload(
                new UploadInitRequest("md5", "test.pdf", 1024L, null, null, false), "1").getStatusCode().value());

        verify(uploadService).initializeUpload("md5", 1024L, "test.pdf", "TEAM_A", false, "1");
    }

    @Test
    void initReportsStorageUnavailableWithoutFallingBackToChunks() {
        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(true);
        when(uploadService.initializeUpload(anyString(), anyLong(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new CustomException("存储不可用", HttpStatus.SERVICE_UNAVAILABLE));

        assertEquals(503, uploadController.initializeUpload(
                new UploadInitRequest("md5", "test.pdf", 1024L, 1, "TEAM_A", false), "1").getStatusCode().value());
    }

    @Test
    void initValidatesFileMetadataBeforeServiceCall() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(uploadController).build();
        mvc.perform(post("/api/v1/upload/init").requestAttr("userId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileMd5\":\"invalid\",\"fileName\":\"\",\"totalSize\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(uploadService);
    }

    @Test
    void testUploadChunkRejectsOversizedFileForNonAdmin() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());
        OrganizationTag orgTag = new OrganizationTag();
        orgTag.setTagId("TEAM_A");
        orgTag.setUploadMaxSizeBytes(1024L * 1024L);

        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(false);
        when(userService.getOrganizationTag("TEAM_A")).thenReturn(orgTag);

        var response = uploadController.uploadChunk(
                "md5",
                0,
                DigestUtils.md5Hex("demo"),
                2L * 1024 * 1024,
                "test.pdf",
                1,
                "TEAM_A",
                false,
                file,
                "1"
        );

        assertEquals(413, response.getStatusCode().value());
        assertEquals(413, response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("不超过"));
        verify(uploadService, never()).uploadChunk(anyString(), anyInt(), anyLong(), anyString(), any(), anyString(), anyBoolean(), anyString(), anyString());
    }

    @Test
    void testUploadChunkAllowsAdminToBypassOrgLimit() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(true);
        when(uploadService.getUploadedChunks("md5", "1")).thenReturn(List.of(0));
        when(uploadService.getTotalChunks("md5", "1")).thenReturn(1);

        var response = uploadController.uploadChunk(
                "md5",
                0,
                DigestUtils.md5Hex("demo"),
                20L * 1024 * 1024,
                "test.pdf",
                1,
                "TEAM_A",
                false,
                file,
                "1"
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("chunkIndex", 0), response.getBody().get("data"));
        verify(uploadService).uploadChunk("md5", 0, 20L * 1024 * 1024, "test.pdf", file, "TEAM_A", false, "1", DigestUtils.md5Hex("demo"));
        verify(userService, never()).getOrganizationTag(anyString());
    }

    @Test
    void testUploadChunkRejectsWhenLaterChunkExceedsOrgLimitEvenIfTotalSizeIsUnderreported() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());
        OrganizationTag orgTag = new OrganizationTag();
        orgTag.setTagId("TEAM_A");
        orgTag.setUploadMaxSizeBytes(5L * 1024 * 1024L);

        when(userService.isAdminUser("1")).thenReturn(false);
        when(userService.getOrganizationTag("TEAM_A")).thenReturn(orgTag);

        var response = uploadController.uploadChunk(
                "md5",
                1,
                DigestUtils.md5Hex("demo"),
                1024L,
                "test.pdf",
                2,
                "TEAM_A",
                false,
                file,
                "1"
        );

        assertEquals(413, response.getStatusCode().value());
        verify(uploadService, never()).uploadChunk(anyString(), anyInt(), anyLong(), anyString(), any(), anyString(), anyBoolean(), anyString(), anyString());
    }

    @Test
    void statusReturnsCurrentUsersFileInformationForSharedMd5() {
        for (String userId : List.of("1", "2")) {
            FileUpload file = new FileUpload();
            file.setFileName("user" + userId + ".pdf");
            file.setUserId(userId);
            when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", userId))
                    .thenReturn(Optional.of(file));
            when(uploadService.getUploadedChunks("md5", userId)).thenReturn(List.of(0));
            when(uploadService.getTotalChunks("md5", userId)).thenReturn(1);

            var response = uploadController.getUploadStatus("md5", userId);

            assertEquals(200, response.getStatusCode().value());
            Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
            assertEquals(List.of(0), data.get("uploadedChunks"));
            assertEquals(false, data.containsKey("progress"));
            assertEquals("user" + userId + ".pdf", ((Map<?, ?>) response.getBody().get("data")).get("fileName"));
            verify(uploadService).getUploadedChunks("md5", userId);
        }
        verify(fileUploadRepository, never()).findFirstByFileMd5OrderByCreatedAtDesc(anyString());
    }

    @Test
    void testMergeFileReturnsExistingResultWhenAlreadyCompleted() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setFileName("test.pdf");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(uploadService.generateMergedObjectUrl("md5")).thenReturn("https://example.com/merged/md5");

        var response = uploadController.mergeFile(new UploadController.MergeRequest("md5", "test.pdf"), "1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("文件已完成合并", response.getBody().get("message"));
        assertEquals("https://example.com/merged/md5", ((Map<?, ?>) response.getBody().get("data")).get("object_url"));
        verify(uploadService, never()).mergeChunks(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }
}
