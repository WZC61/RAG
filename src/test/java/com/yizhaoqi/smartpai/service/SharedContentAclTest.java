package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.repository.*;
import com.yizhaoqi.smartpai.consumer.FileProcessingConsumer;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.parsing.DocumentParsingService;
import com.yizhaoqi.smartpai.parsing.NonPdfDocumentParsingService;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunk;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunker;
import com.yizhaoqi.smartpai.parsing.description.FigureDescriptionService;
import com.yizhaoqi.smartpai.parsing.persistence.*;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL constraints and Spring transaction proxies; external storage/index are deterministic fakes. */
class SharedContentAclTest {
    static LocalContainerEntityManagerFactoryBean factory;
    static JpaTransactionManager manager;
    static TransactionTemplate tx;
    static FileUploadRepository files;
    static FileContentRepository contents;
    static ProcessingOutboxRepository outbox;
    static DocumentVectorRepository texts;
    static DocumentFigureRepository figures;
    static ChunkInfoRepository chunks;
    final ObjectMapper mapper = new ObjectMapper();
    ElasticsearchService search;
    MinioClient storage;
    SharedContentAclService acl;
    UploadCompletionService completion;
    Map<String, EsDocument> indexed;
    Set<String> objects;

    @BeforeAll static void database() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:shared-acl;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileUpload.class.getName(), FileContent.class.getName(),
                ProcessingOutbox.class.getName(), DocumentVector.class.getName(), DocumentFigure.class.getName(), ChunkInfo.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")); factory.afterPropertiesSet();
        manager = new JpaTransactionManager(factory.getObject()); tx = new TransactionTemplate(manager);
        var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        repositories.addRepositoryProxyPostProcessor((proxy, info) -> proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())));
        files = repositories.getRepository(FileUploadRepository.class); contents = repositories.getRepository(FileContentRepository.class);
        outbox = repositories.getRepository(ProcessingOutboxRepository.class); texts = repositories.getRepository(DocumentVectorRepository.class);
        figures = repositories.getRepository(DocumentFigureRepository.class); chunks = repositories.getRepository(ChunkInfoRepository.class);
    }
    @AfterAll static void close() { factory.destroy(); }
    @SuppressWarnings("unchecked") static <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) proxy.getProxy();
    }
    @BeforeEach void setup() throws Exception {
        tx.executeWithoutResult(s -> { outbox.deleteAllInBatch(); chunks.deleteAllInBatch(); texts.deleteAllInBatch(); figures.deleteAllInBatch(); files.deleteAllInBatch(); contents.deleteAllInBatch(); });
        search = mock(ElasticsearchService.class); storage = mock(MinioClient.class);
        indexed = new ConcurrentHashMap<>(); objects = ConcurrentHashMap.newKeySet();
        objects.addAll(List.of("merged/abc", "figures/abc/1/page-1-figure-1.png"));
        when(storage.listObjects(any())).thenAnswer(call -> {
            var prefix = call.<ListObjectsArgs>getArgument(0).prefix();
            return objects.stream().filter(key -> key.startsWith(prefix)).map(key -> {
                var item = mock(io.minio.messages.Item.class); when(item.objectName()).thenReturn(key);
                return new io.minio.Result<>(item);
            }).toList();
        });
        doAnswer(call -> { objects.remove(call.<RemoveObjectArgs>getArgument(0).object()); return null; }).when(storage).removeObject(any());
        doAnswer(call -> {
            String md5 = call.getArgument(0); ContentAcl value = call.getArgument(1);
            indexed.values().stream().filter(d -> md5.equals(d.getFileMd5())).forEach(value::apply); return null;
        }).when(search).replaceAcl(anyString(), any());
        doAnswer(call -> { indexed.values().removeIf(d -> call.getArgument(0).equals(d.getFileMd5())); return null; }).when(search).deleteByFileMd5(anyString());
        doAnswer(call -> { call.<List<EsDocument>>getArgument(0).forEach(d -> indexed.put(d.getId(), d)); return null; }).when(search).bulkIndex(anyList());
        acl = proxy(new SharedContentAclService(files, contents, outbox, texts, figures, chunks, search, storage, mapper, proxy(new ContentCleanupCheckpointService(contents, files))));
        completion = proxy(new UploadCompletionService(files, contents, outbox, mapper));
    }
    FileUpload relation(String user, String org, boolean published, int status) {
        return tx.execute(s -> { var f = new FileUpload(); f.setFileMd5("abc"); f.setUserId(user); f.setOrgTag(org);
            f.setPublic(published); f.setFileName("paper.pdf"); f.setTotalSize(10L); f.setStatus(status); return files.saveAndFlush(f); });
    }
    FileContent content() { return contents.findByFileMd5("abc").orElseThrow(); }
    void indexState() {
        tx.executeWithoutResult(s -> { var c = content(); c.setProcessingStatus(FileContent.ProcessingStatus.INDEXED); contents.saveAndFlush(c); });
        for (EsDocument.DocumentType type : EsDocument.DocumentType.values()) {
            var d = new EsDocument(); d.setId(type + ":abc:1:1"); d.setFileMd5("abc"); d.setDocumentType(type); indexed.put(d.getId(), d);
        }
    }
    long events(String type) { return outbox.findAll().stream().filter(e -> type.equals(e.getEventType())).count(); }

    @Test void firstCompletionCreatesDurableAclTriggerInSameTransaction() throws Exception {
        relation("A", null, false, FileUpload.STATUS_MERGING); completion.complete("A", "abc");
        assertEquals(1, events("PROCESS_CONTENT")); assertEquals(1, events("ACL_CHANGED"));
        assertEquals(FileUpload.STATUS_COMPLETED, files.findAll().get(0).getStatus());
        completion.complete("A", "abc"); assertEquals(2, outbox.count());
    }
    @Test void indexedInstantUploadCreatesOnlyAclEventAndAddsBothOwners() throws Exception {
        relation("A", null, false, FileUpload.STATUS_MERGING); completion.complete("A", "abc"); indexState(); outbox.deleteAll();
        relation("B", "ORG_B", false, FileUpload.STATUS_UPLOADING); completion.completeInstantUpload("B", "abc");
        assertEquals(0, events("PROCESS_CONTENT")); assertEquals(1, events("ACL_CHANGED"));
        acl.reconcile("abc", null);
        indexed.values().forEach(d -> { assertEquals(List.of("A", "B"), d.getAllowedUserIds()); assertEquals(List.of("ORG_B"), d.getAllowedOrgTags()); assertFalse(d.isPublic()); });
    }
    @Test void aggregationIncludesOwnerOrgAndPublicButNotPrivateTagOrIncompleteUpload() {
        var a = relation("A", "PRIVATE_A", false, 1); var b = relation("B", "TEAM", false, 1);
        var c = relation("C", "TEAM", true, 1); var d = relation("D", "SECRET", true, 0);
        assertEquals(new ContentAcl(List.of("A", "B", "C"), List.of("TEAM"), true), ContentAcl.from(List.of(d, c, b, a)));
        assertEquals(List.of("abc"), files.findAuthorizedContentIds("A", List.of()));
        assertEquals(List.of("abc"), files.findAuthorizedContentIds("other", List.of("TEAM")));
    }
    @Test void privateTagCannotGrantAnotherUserAccess() {
        relation("A", "PRIVATE_A", false, 1);
        assertTrue(files.findAuthorizedContentIds("B", List.of("PRIVATE_A")).isEmpty());
        assertTrue(files.findPublicContentIds().isEmpty());
        assertEquals(List.of("abc"), files.findAuthorizedContentIds("A", List.of()));
    }
    @Test void unfinishedPublicRelationCannotExposeAnotherOwnersMergedFile() {
        relation("A", null, false, 1); relation("B", "TEAM", true, 0);
        assertTrue(files.findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc("abc").isEmpty());
        assertTrue(files.findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc("paper.pdf").isEmpty());
        assertTrue(files.findAuthorizedContentIds("other", List.of("TEAM")).isEmpty());
    }
    @Test void deleteOneOwnerPreservesAllSharedDataAndRebuildsAcl() throws Exception {
        relation("A", "ORG_A", true, 2); completion.complete("A", "abc"); indexState(); relation("B", null, false, 1);
        acl.reconcile("abc", null); acl.deleteReference("abc", "A"); acl.reconcile("abc", "A");
        assertEquals(1, files.count()); assertEquals("B", files.findAll().get(0).getUserId());
        assertTrue(objects.contains("merged/abc")); assertEquals(2, indexed.size()); assertNull(content().getDeletedAt());
        indexed.values().forEach(d -> { assertEquals(List.of("B"), d.getAllowedUserIds()); assertTrue(d.getAllowedOrgTags().isEmpty()); assertFalse(d.isPublic()); });
        verify(search, never()).deleteByFileMd5(any());
    }
    @Test void pendingUploadAlsoProtectsSharedStorageWithoutGrantingPermissions() throws Exception {
        relation("A", null, true, 2); completion.complete("A", "abc"); indexState(); relation("B", "TEAM", true, 0);
        acl.deleteReference("abc", "A"); acl.reconcile("abc", "A");
        assertTrue(objects.contains("merged/abc")); assertEquals(new ContentAcl(List.of(), List.of(), false), ContentAcl.from(files.findAll()));
        assertTrue(files.findPublicContentIds().isEmpty());
    }
    @Test void lastDeletionCleansSharedArtifactsAndRetainsAdvancedGeneration() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); indexState();
        tx.executeWithoutResult(s -> {
            var text = new DocumentVector(); text.setFileMd5("abc"); text.setChunkId(1); text.setTextContent("body"); text.setUserId("A"); texts.save(text);
            var fig = new DocumentFigure(); fig.setFileMd5("abc"); fig.setProcessingGeneration(1L); fig.setPageNumber(1); fig.setFigureIndex(1); fig.setImagePath("figures/abc/1/page-1-figure-1.png"); figures.save(fig);
        });
        acl.deleteReference("abc", "A"); acl.reconcile("abc", "A");
        assertTrue(objects.isEmpty()); assertTrue(indexed.isEmpty()); assertEquals(0, texts.count()); assertEquals(0, figures.count());
        assertNotNull(content().getDeletedAt()); assertEquals(2, content().getProcessingGeneration());
        acl.reconcile("abc", "A"); assertEquals(2, content().getProcessingGeneration());
    }
    @Test void deletedContentReuploadUsesNewGenerationAndRejectsDelayedOldTask() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); acl.deleteReference("abc", "A"); acl.reconcile("abc", "A");
        relation("B", null, false, 2); objects.add("merged/abc"); completion.complete("B", "abc");
        assertEquals(2, events("PROCESS_CONTENT")); assertNull(content().getDeletedAt());
        var states = new FileContentProcessingService(contents, outbox);
        assertNull(states.checkpoint("abc", 1)); assertEquals(FileContent.ProcessingStatus.MERGED, states.checkpoint("abc", 2));
    }
    @Test void delayedDeleteEventRechecksReferencesInsteadOfDeletingNewOwner() throws Exception {
        relation("A", null, true, 2); completion.complete("A", "abc"); indexState(); acl.deleteReference("abc", "A");
        relation("B", null, false, 1); acl.reconcile("abc", "A");
        assertTrue(objects.contains("merged/abc")); assertEquals(List.of("B"), indexed.values().iterator().next().getAllowedUserIds());
    }
    @Test void permissionChangeAndOutboxCommitTogetherAndSameValueIsIdempotent() throws Exception {
        relation("A", "TEAM", true, 2); completion.complete("A", "abc"); indexState(); outbox.deleteAll();
        acl.changePermissions("abc", "A", null, false); acl.changePermissions("abc", "A", null, false);
        assertEquals(1, outbox.count()); acl.reconcile("abc", null);
        indexed.values().forEach(d -> { assertFalse(d.isPublic()); assertTrue(d.getAllowedOrgTags().isEmpty()); });
        assertTrue(files.findAuthorizedContentIds("B", List.of("TEAM")).isEmpty());
    }
    @Test void legacyStatusBackfillNeverRestoresRevokedPermissions() {
        var before=relation("A","TEAM",true,1);
        acl.changePermissions("abc","A",null,false);
        files.backfillLegacyStatus(before.getId(),FileUpload.VECTORIZATION_STATUS_COMPLETED,null);
        var after=files.findById(before.getId()).orElseThrow();assertFalse(after.isPublic());assertNull(after.getOrgTag());
        assertEquals(FileUpload.VECTORIZATION_STATUS_COMPLETED,after.getVectorizationStatus());
    }
    @Test void cannotDeleteOrChangeAnotherUsersRelation() {
        relation("A", null, false, 1);
        assertThrows(Exception.class, () -> acl.deleteReference("abc", "B"));
        assertThrows(Exception.class, () -> acl.changePermissions("abc", "B", null, true)); assertEquals(1, files.count());
    }
    @Test void outboxFailureRollsBackReferenceDeletionAndPermissionMutation() {
        relation("A", "TEAM", true, 1);
        var failed = mock(ProcessingOutboxRepository.class); doThrow(new IllegalStateException("DB failure")).when(failed).saveAndFlush(any());
        var service = proxy(new SharedContentAclService(files, contents, failed, texts, figures, chunks, search, storage, mapper, proxy(new ContentCleanupCheckpointService(contents, files))));
        assertThrows(Exception.class, () -> service.deleteReference("abc", "A")); assertEquals(1, files.count());
        assertThrows(Exception.class, () -> service.changePermissions("abc", "A", null, false)); assertTrue(files.findAll().get(0).isPublic());
        verifyNoInteractions(storage, search);
    }
    @Test void failedExternalCleanupKeepsDatabaseArtifactsForRetry() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); indexState(); acl.deleteReference("abc", "A");
        doThrow(new IOException("MinIO down")).when(storage).removeObject(any());
        assertThrows(IOException.class, () -> acl.reconcile("abc", "A"));
        assertNotNull(content().getDeletedAt()); assertEquals(2, content().getProcessingGeneration());
        assertEquals(FileContent.ProcessingStatus.FAILED, content().getProcessingStatus());
        doAnswer(call -> { objects.remove(call.<RemoveObjectArgs>getArgument(0).object()); return null; }).when(storage).removeObject(any());
        acl.reconcile("abc", "A"); assertNotNull(content().getDeletedAt()); assertTrue(objects.isEmpty());
    }
    @Test void checkpointFailureStopsAllExternalDeletion() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); indexState(); acl.deleteReference("abc", "A");
        var failed = mock(FileContentRepository.class);
        when(failed.findForUpdate("abc")).thenAnswer(call -> contents.findForUpdate("abc"));
        when(failed.saveAndFlush(any())).thenAnswer(call -> {
            contents.saveAndFlush(call.getArgument(0)); throw new IllegalStateException("checkpoint commit failed");
        });
        var guarded = proxy(new SharedContentAclService(files, contents, outbox, texts, figures, chunks, search, storage,
                mapper, proxy(new ContentCleanupCheckpointService(failed, files))));
        assertThrows(IllegalStateException.class, () -> guarded.reconcile("abc", "A"));
        assertNull(content().getDeletedAt()); assertEquals(1, content().getProcessingGeneration());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
        verifyNoInteractions(storage, search);
    }
    @Test void partialCleanupWithMergedStillPresentInstantUploadStartsNewContentEvent() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); indexState(); acl.deleteReference("abc", "A");
        doAnswer(call -> { indexed.clear(); throw new IOException("ES response failed after deletion"); })
                .when(search).deleteByFileMd5("abc");
        assertThrows(IOException.class, () -> acl.reconcile("abc", "A"));
        assertTrue(objects.contains("merged/abc")); assertTrue(indexed.isEmpty());
        fakeUploadStorage();
        assertEquals(FileUpload.STATUS_COMPLETED, uploadService().initializeUpload("abc", 10, "paper.pdf", null, false, "B").getStatus());
        assertEquals(2, content().getProcessingGeneration()); assertNull(content().getDeletedAt());
        assertEquals(FileContent.ProcessingStatus.MERGED, content().getProcessingStatus());
        assertTrue(outbox.findByEventId("PROCESS_CONTENT:abc:2").isPresent());
        acl.reconcile("abc", "A"); assertTrue(objects.contains("merged/abc"));
        verify(search, times(1)).deleteByFileMd5("abc");
    }
    @Test void failedPartialCleanupConcurrentReuploadRebuildsAndDelayedCleanupCannotDeleteNewGeneration() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); indexState();
        tx.executeWithoutResult(s -> {
            var fig = new DocumentFigure(); fig.setFileMd5("abc"); fig.setProcessingGeneration(1L);
            fig.setPageNumber(1); fig.setFigureIndex(1); fig.setImagePath("figures/abc/1/page-1-figure-1.png"); figures.save(fig);
        });
        acl.deleteReference("abc", "A");
        var partiallyDeleted = new CountDownLatch(1); var releaseFailure = new CountDownLatch(1);
        var initInspected = new CountDownLatch(1);
        doAnswer(call -> {
            String key = call.<RemoveObjectArgs>getArgument(0).object();
            if (key.equals("figures/abc/1/page-1-figure-1.png")) {
                partiallyDeleted.countDown();
                if (!releaseFailure.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
                throw new IOException("Figure deletion failed after ES and merged removal");
            }
            objects.remove(key); return null;
        }).when(storage).removeObject(any());
        fakeUploadStorage();
        doAnswer(call -> {
            initInspected.countDown(); return fakeStat(call.getArgument(0));
        }).when(storage).statObject(any());
        var uploads = uploadService();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var cleanup = pool.submit(() -> { acl.reconcile("abc", "A"); return null; });
            assertTrue(partiallyDeleted.await(10, TimeUnit.SECONDS));
            assertTrue(indexed.isEmpty()); assertFalse(objects.contains("merged/abc"));
            assertNotNull(content().getDeletedAt()); assertEquals(2, content().getProcessingGeneration());
            assertEquals(FileContent.ProcessingStatus.FAILED, content().getProcessingStatus());
            var init = pool.submit(() -> uploads.initializeUpload("abc", 10, "paper.pdf", null, false, "B"));
            assertTrue(initInspected.await(10, TimeUnit.SECONDS));
            assertFalse(init.isDone(), "New reference waits for the shared-resource deletion guard");
            releaseFailure.countDown();
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class, () -> cleanup.get(10, TimeUnit.SECONDS)).getCause());
            assertEquals(FileUpload.STATUS_UPLOADING, init.get(10, TimeUnit.SECONDS).getStatus());
        } finally { releaseFailure.countDown(); pool.shutdownNow(); }

        byte[] pdf = "%PDF-1.7\nX".getBytes(StandardCharsets.US_ASCII);
        uploads.uploadChunk("abc", 0, pdf.length, "paper.pdf",
                new org.springframework.mock.web.MockMultipartFile("file", "paper.pdf", "application/pdf", pdf),
                null, false, "B", org.apache.commons.codec.digest.DigestUtils.md5Hex(pdf));
        assertEquals(1, files.updateStatusIfCurrent(files.findAll().get(0).getId(), FileUpload.STATUS_UPLOADING, FileUpload.STATUS_MERGING));
        uploads.mergeChunks("abc", "paper.pdf", "B");
        assertEquals(FileContent.ProcessingStatus.MERGED, content().getProcessingStatus());
        assertNull(content().getDeletedAt()); assertTrue(objects.contains("merged/abc"));
        assertTrue(outbox.findByEventId("PROCESS_CONTENT:abc:2").isPresent());

        var persistence = proxy(new ParsedArtifactPersistenceService(contents, texts, figures, mapper));
        var pdfParser = mock(DocumentParsingService.class);
        when(pdfParser.parseAndPersist(eq("abc"), eq(2L), any(), any())).thenAnswer(call -> {
            String image = "figures/abc/2/page-1-figure-1.png"; objects.add(image);
            return persistence.persist("abc", 2, List.of(new TextChunk(1, 1, "rebuilt body", "rebuilt body")),
                    List.of(new StoredFigureArtifact(1, 1, "Figure 1", image, List.of(0, 0, 10, 10), "new caption", "OCR", "body")),
                    call.getArgument(3));
        });
        var descriptions = mock(FigureDescriptionService.class);
        when(descriptions.describe("abc", 2)).thenAnswer(call -> {
            tx.executeWithoutResult(s -> figures.findByFileMd5("abc").forEach(f -> { f.setDescription("rebuilt diagram description"); figures.save(f); }));
            return true;
        });
        var consumer = contentConsumer(pdfParser, descriptions);
        Path source = Files.createTempFile(Path.of("target"), "a3-race-", ".pdf");
        try {
            Files.write(source, pdf);
            consumer.processTask(contentTask(source, 2));
            assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
            assertEquals(Set.of("TEXT:abc:2:1", "FIGURE:abc:2:1:1"), indexed.keySet());
            assertEquals(1, texts.count()); assertEquals(2L, figures.findByFileMd5("abc").get(0).getProcessingGeneration());
            assertTrue(objects.contains("figures/abc/2/page-1-figure-1.png"));
            assertEquals(FileUpload.STATUS_COMPLETED, files.findAll().get(0).getStatus());
            Set<String> validObjects = Set.copyOf(objects); Set<String> validIds = Set.copyOf(indexed.keySet());
            acl.reconcile("abc", "A"); // Delayed old cleanup event must only repair current ACL.
            consumer.processTask(contentTask(source, 1)); // Delayed old processing is also inert.
            assertEquals(validObjects, objects); assertEquals(validIds, indexed.keySet());
            assertEquals(2, content().getProcessingGeneration()); assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
            indexed.values().forEach(d -> assertEquals(List.of("B"), d.getAllowedUserIds()));
            verify(search, times(1)).deleteByFileMd5("abc");
            verify(storage, times(1)).removeObject(argThat(a -> "merged/abc".equals(a.object())));
        } finally { Files.deleteIfExists(source); }
    }

    @Test void modernNonPdfConsumerUsesRealTikaAtomicCommitThenTextEmbeddingAndIndexed() throws Exception {
        relation("B", null, false, 2); completion.complete("B", "abc");
        var parser = new ParseService(); ReflectionTestUtils.setField(parser, "textChunker", new TextChunker(32, 4, 1));
        ReflectionTestUtils.setField(parser, "bufferSize", 8192); ReflectionTestUtils.setField(parser, "parentChunkSize", 1024);
        ReflectionTestUtils.setField(parser, "maxMemoryThreshold", 1.0);
        var nonPdf = proxy(new NonPdfDocumentParsingService(parser,
                proxy(new ParsedArtifactPersistenceService(contents, texts, figures, mapper))));
        var pdfParser = mock(DocumentParsingService.class); var descriptions = mock(FigureDescriptionService.class);
        when(descriptions.describe("abc", 1)).thenReturn(true);
        var consumer = contentConsumer(pdfParser, descriptions); ReflectionTestUtils.setField(consumer, "nonPdfParsingService", nonPdf);
        Path source = Files.createTempFile(Path.of("target"), "a2-tika-", ".txt");
        try {
            Files.writeString(source, "First paragraph.\n\nSecond paragraph. More real extracted text.");
            consumer.processTask(contentTask(source, 1));
            assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
            assertFalse(indexed.isEmpty()); assertEquals(texts.count(), indexed.size()); assertEquals(0, figures.count());
            indexed.values().forEach(d -> { assertEquals(EsDocument.DocumentType.TEXT, d.getDocumentType()); assertNull(d.getPageNumber()); });
            verifyNoInteractions(pdfParser);
            Set<String> ids = Set.copyOf(indexed.keySet()); consumer.processTask(contentTask(source, 1)); assertEquals(ids, indexed.keySet());
        } finally { Files.deleteIfExists(source); }
    }

    private StatObjectResponse fakeStat(StatObjectArgs args) throws Exception {
        if (!objects.contains(args.object())) throw new io.minio.errors.ErrorResponseException(
                new io.minio.messages.ErrorResponse("NoSuchKey", "missing", "uploads", args.object(), "resource", "request", "host"), null, "");
        var stat = mock(StatObjectResponse.class); when(stat.size()).thenReturn(10L); return stat;
    }
    private void fakeUploadStorage() throws Exception {
        when(storage.statObject(any())).thenAnswer(call -> fakeStat(call.getArgument(0)));
        when(storage.putObject(any())).thenAnswer(call -> { objects.add(call.<PutObjectArgs>getArgument(0).object()); return null; });
        when(storage.composeObject(any())).thenAnswer(call -> { objects.add(call.<ComposeObjectArgs>getArgument(0).object()); return null; });
        when(storage.getPresignedObjectUrl(any())).thenReturn("https://storage.test/merged/abc");
    }
    private UploadService uploadService() throws Exception {
        var service = new UploadService(); ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "fileContentRepository", contents); ReflectionTestUtils.setField(service, "chunkInfoRepository", chunks);
        ReflectionTestUtils.setField(service, "transactionManager", manager); ReflectionTestUtils.setField(service, "minioClient", storage);
        ReflectionTestUtils.setField(service, "uploadCompletionService", completion);
        var redisson = mock(org.redisson.api.RedissonClient.class); var lock = mock(org.redisson.api.RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock); when(lock.tryLock(30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true); ReflectionTestUtils.setField(service, "redissonClient", redisson);
        return service;
    }
    private FileProcessingTask contentTask(Path source, long generation) {
        var task = new FileProcessingTask("abc", source.toAbsolutePath().toString(), "paper.pdf", null, null, false,
                FileProcessingTask.TASK_TYPE_PROCESS_CONTENT, "B");
        task.setProcessingGeneration(generation); task.setEventId("PROCESS_CONTENT:abc:" + generation); task.setObjectPath("merged/abc"); return task;
    }
    private FileProcessingConsumer contentConsumer(DocumentParsingService pdfParser, FigureDescriptionService descriptions) throws Exception {
        var embedding = mock(EmbeddingClient.class);
        when(embedding.embedWithUsage(anyList(), anyString(), any())).thenAnswer(call -> {
            List<String> inputs = call.getArgument(0);
            return new EmbeddingClient.EmbeddingUsageResult(inputs.stream().map(input -> new float[]{1, 2}).toList(), inputs.size() * 10, "model");
        });
        var vectors = new VectorizationService(); ReflectionTestUtils.setField(vectors, "embeddingClient", embedding);
        ReflectionTestUtils.setField(vectors, "elasticsearchService", search); ReflectionTestUtils.setField(vectors, "documentVectorRepository", texts);
        ReflectionTestUtils.setField(vectors, "documentFigureRepository", figures); ReflectionTestUtils.setField(vectors, "fileContentRepository", contents);
        ReflectionTestUtils.setField(vectors, "fileUploadRepository", files);
        ReflectionTestUtils.setField(vectors, "contentIndexWriter", proxy(new ContentIndexWriter(contents, files, search)));
        var consumer = new FileProcessingConsumer(mock(ParseService.class), vectors, mock(DocumentService.class));
        ReflectionTestUtils.setField(consumer, "contentProcessing", proxy(new FileContentProcessingService(contents, outbox)));
        ReflectionTestUtils.setField(consumer, "files", files); ReflectionTestUtils.setField(consumer, "parsingService", pdfParser);
        ReflectionTestUtils.setField(consumer, "figureDescriptions", descriptions); return consumer;
    }
    @Test void failedAclProjectionRetriesCurrentDatabasePermissions() throws Exception {
        relation("A", "TEAM", true, 2); completion.complete("A", "abc"); indexState();
        doThrow(new IllegalStateException("ES down")).when(search).replaceAcl(anyString(), any());
        assertThrows(Exception.class, () -> acl.reconcile("abc", null)); acl.changePermissions("abc", "A", null, false);
        doAnswer(call -> { indexed.values().forEach(call.<ContentAcl>getArgument(1)::apply); return null; }).when(search).replaceAcl(anyString(), any());
        acl.reconcile("abc", null); assertFalse(indexed.values().iterator().next().isPublic());
    }
    @Test void consumerRoutesAclWithoutParsingModelsOrChangingIndexedState() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); indexState();
        ParseService parse = mock(ParseService.class); VectorizationService vectors = mock(VectorizationService.class);
        var consumer = new FileProcessingConsumer(parse, vectors, mock(DocumentService.class)); ReflectionTestUtils.setField(consumer, "contentAcl", acl);
        var task = new FileProcessingTask(); task.setFileMd5("abc"); task.setEventId("ACL_CHANGED:test"); task.setTaskType("ACL_CHANGED");
        consumer.processTask(task); consumer.processTask(task);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus()); verifyNoInteractions(parse, vectors);
    }
    @Test void completedIndexCommitsFinalAclRepairEventWithState() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); outbox.deleteAll();
        var states = proxy(new FileContentProcessingService(contents, outbox)); states.parsed("abc", 1);
        states.indexed("abc", 1, new VectorizationService.VectorizationUsageResult(10, 2, "model"));
        assertEquals(1, events("ACL_CHANGED")); assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
    }
    @Test void finalAclOutboxFailureRollsBackIndexed() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc");
        var failed = mock(ProcessingOutboxRepository.class); doThrow(new IllegalStateException("DB down")).when(failed).saveAndFlush(any());
        var states = proxy(new FileContentProcessingService(contents, failed)); states.parsed("abc", 1);
        assertThrows(Exception.class, () -> states.indexed("abc", 1, new VectorizationService.VectorizationUsageResult(10, 1, "model")));
        assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
    }
    @Test void indexWriterUsesBothUsersAndPreventsWriteAfterLastCleanup() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); relation("B", "TEAM", true, 1);
        proxy(new FileContentProcessingService(contents, outbox)).parsed("abc", 1);
        var writer = proxy(new ContentIndexWriter(contents, files, search));
        var d = new EsDocument(); d.setId("TEXT:abc:1:1"); d.setFileMd5("abc"); writer.write("abc", 1, List.of(d));
        assertEquals(List.of("A", "B"), d.getAllowedUserIds()); assertTrue(d.isPublic());
        acl.deleteReference("abc", "A"); acl.deleteReference("abc", "B"); acl.reconcile("abc", "B");
        assertThrows(IllegalStateException.class, () -> writer.write("abc", 1, List.of(d))); assertTrue(indexed.isEmpty());
    }
    @Test void aclDltCreatesNewPendingReconciliationWithoutTerminatingContent() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); indexState(); acl.deleteReference("abc", "A");
        var event = outbox.findAll().stream().filter(e -> "A".equals(e.getUserId())).findFirst().orElseThrow();
        var task = new FileProcessingTask(); task.setEventId(event.getEventId()); task.setFileMd5("abc"); task.setTaskType("ACL_CHANGED");
        long count = outbox.count(); acl.retryAfterDlt(task); assertEquals(count + 1, outbox.count());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
        var retry = outbox.findAll().get(outbox.findAll().size() - 1); assertEquals(ProcessingOutbox.Status.PENDING, retry.getStatus()); assertEquals("A", retry.getUserId());
    }
    @Test void concurrentNewInitWaitsForCleanupAndRechecksMissingObject() throws Exception {
        relation("A", null, false, 2); completion.complete("A", "abc"); indexState(); acl.deleteReference("abc", "A");
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var inspected = new CountDownLatch(1);
        doAnswer(call -> {
            String key = call.<RemoveObjectArgs>getArgument(0).object();
            if (key.equals("merged/abc")) { held.countDown(); if (!release.await(10,TimeUnit.SECONDS)) throw new IOException("test cleanup timeout"); }
            objects.remove(key); return null;
        }).when(storage).removeObject(any());
        when(storage.statObject(any())).thenAnswer(call -> {
            inspected.countDown();
            if (!objects.contains("merged/abc")) throw new io.minio.errors.ErrorResponseException(
                    new io.minio.messages.ErrorResponse("NoSuchKey","missing","uploads","merged/abc","resource","request","host"),null,"");
            var stat=mock(StatObjectResponse.class); when(stat.size()).thenReturn(10L); return stat;
        });
        var uploads=new UploadService(); ReflectionTestUtils.setField(uploads,"fileUploadRepository",files);
        ReflectionTestUtils.setField(uploads,"fileContentRepository",contents); ReflectionTestUtils.setField(uploads,"transactionManager",manager);
        ReflectionTestUtils.setField(uploads,"minioClient",storage); ReflectionTestUtils.setField(uploads,"uploadCompletionService",completion);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var cleanup=pool.submit(()->{ acl.reconcile("abc","A"); return null; }); assertTrue(held.await(10,TimeUnit.SECONDS));
            var init=pool.submit(()->uploads.initializeUpload("abc",10L,"paper.pdf",null,false,"B"));
            assertTrue(inspected.await(10,TimeUnit.SECONDS)); release.countDown(); cleanup.get(10,TimeUnit.SECONDS);
            assertEquals(FileUpload.STATUS_UPLOADING,init.get(10,TimeUnit.SECONDS).getStatus());
            assertNotNull(content().getDeletedAt()); assertEquals(1,files.count()); assertFalse(objects.contains("merged/abc"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void aclMutationWaitsForBulkThenProjectionRemovesTheOldGrant() throws Exception {
        relation("A","TEAM",true,2); completion.complete("A","abc"); proxy(new FileContentProcessingService(contents,outbox)).parsed("abc",1);
        var held=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{ held.countDown(); if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test bulk timeout");
            call.<List<EsDocument>>getArgument(0).forEach(d->indexed.put(d.getId(),d));return null; }).when(search).bulkIndex(anyList());
        var writer=proxy(new ContentIndexWriter(contents,files,search));var d=new EsDocument();d.setId("TEXT:abc:1:1");d.setFileMd5("abc");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var bulk=pool.submit(()->{writer.write("abc",1,List.of(d));return null;});assertTrue(held.await(10,TimeUnit.SECONDS));
            var revoke=pool.submit(()->{acl.changePermissions("abc","A",null,false);return null;});
            release.countDown();bulk.get(10,TimeUnit.SECONDS);revoke.get(10,TimeUnit.SECONDS);acl.reconcile("abc",null);
            assertFalse(indexed.get(d.getId()).isPublic());assertTrue(indexed.get(d.getId()).getAllowedOrgTags().isEmpty());
        } finally {release.countDown();pool.shutdownNow();}
    }
}
