package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.*;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2 repositories and ES Java HTTP transport. No developer data or paid API calls. */
class RetrievalIntegrationTest {
    static LocalContainerEntityManagerFactoryBean factory;
    static FileContentRepository contents;
    static FileUploadRepository uploads;
    static TransactionTemplate tx;
    HttpServer server;
    RestClientTransport transport;
    HybridSearchService search;
    final ObjectMapper json=new ObjectMapper();
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    List<EsDocument> documents;

    @BeforeAll static void database() {
        factory=new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:retrieval-scope;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileContent.class.getName(),FileUpload.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop",
                "hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet(); tx=new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        var repositories=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        contents=repositories.getRepository(FileContentRepository.class); uploads=repositories.getRepository(FileUploadRepository.class);
    }
    @AfterAll static void closeDatabase() { factory.destroy(); }
    @BeforeEach void setup() throws Exception {
        tx.executeWithoutResult(s -> { uploads.deleteAllInBatch(); contents.deleteAllInBatch(); });
        seed("abc",2,"2","PRIVATE_B",false,FileContent.ProcessingStatus.INDEXED);
        seed("pending",1,"2","PRIVATE_B",false,FileContent.ProcessingStatus.PARSED);
        seed("foreign",1,"9","PRIVATE_other",false,FileContent.ProcessingStatus.INDEXED);
        seed("team",4,"9","TEAM",false,FileContent.ProcessingStatus.INDEXED);
        seed("published",1,"9","PRIVATE_other",true,FileContent.ProcessingStatus.INDEXED);
        documents=List.of(text("current","abc",2L),figure(),text("old","abc",1L),text("legacy","abc",null),
                text("pending","pending",1L),text("foreign","foreign",1L),text("team","team",4L),text("public","published",1L));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/knowledge_base/_search",exchange -> {
            byte[] body;
            try {
                requests.add(json.readTree(exchange.getRequestBody()));
                var hits=documents.stream().map(d -> Map.of("_index","knowledge_base","_id",d.getId(),"_score",1d,"_source",d)).toList();
                body=json.writeValueAsBytes(Map.of("took",1,"timed_out",false,"_shards",Map.of("total",1,"successful",1,"failed",0),
                        "hits",Map.of("hits",hits)));
            } catch (Exception failure) { throw new java.io.IOException(failure); }
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.getResponseHeaders().set("X-Elastic-Product","Elasticsearch");
            exchange.sendResponseHeaders(200,body.length);
            try (var output=exchange.getResponseBody()) { output.write(body); }
            finally { exchange.close(); }
        });
        server.start();
        var rest=RestClient.builder(new HttpHost("127.0.0.1",server.getAddress().getPort(),"http"))
                .setRequestConfigCallback(config -> config.setConnectTimeout(2000).setSocketTimeout(5000)).build();
        transport=new RestClientTransport(rest,new JacksonJsonpMapper(json));
        var embedding=mock(EmbeddingClient.class); when(embedding.embed(anyList(),anyString(),any())).thenReturn(List.of(new float[]{1,2}));
        var users=mock(UserRepository.class); var user=new User(); user.setId(2L); user.setUsername("B");
        when(users.findById(2L)).thenReturn(Optional.of(user)); var orgs=mock(OrgTagCacheService.class);
        when(orgs.getUserEffectiveOrgTags("B")).thenReturn(List.of("TEAM"));
        search=new HybridSearchService();
        ReflectionTestUtils.setField(search,"esClient",new ElasticsearchClient(transport));
        ReflectionTestUtils.setField(search,"embeddingClient",embedding); ReflectionTestUtils.setField(search,"userRepository",users);
        ReflectionTestUtils.setField(search,"orgTagCacheService",orgs); ReflectionTestUtils.setField(search,"fileContentRepository",contents);
        ReflectionTestUtils.setField(search,"fileUploadRepository",uploads);
    }
    @AfterEach void closeHttp() throws Exception { if (transport!=null) transport.close(); if (server!=null) server.stop(0); }
    void seed(String md5,long generation,String user,String org,boolean published,FileContent.ProcessingStatus state) {
        tx.executeWithoutResult(s -> {
            var c=new FileContent(); c.setFileMd5(md5); c.setObjectPath("merged/"+md5); c.setProcessingGeneration(generation);
            c.setProcessingStatus(state); contents.save(c);
            var f=new FileUpload(); f.setFileMd5(md5); f.setUserId(user); f.setOrgTag(org); f.setPublic(published);
            f.setStatus(FileUpload.STATUS_COMPLETED); f.setFileName(md5+".pdf"); uploads.save(f);
        });
    }
    EsDocument text(String id,String md5,Long generation) {
        var d=new EsDocument(); d.setId(id); d.setFileMd5(md5); d.setProcessingGeneration(generation);
        d.setDocumentType(EsDocument.DocumentType.TEXT); d.setTextContent("body for "+md5); d.setChunkId(1); d.setPageNumber(1);
        return d;
    }
    EsDocument figure() {
        var d=RrfFusionTest.figure("figure",1).source(); d.setId("figure"); d.setProcessingGeneration(2L); return d;
    }
    @Test void realRepositoryScopeAndHttpMappingReturnCurrentTextAndFigureOnly() {
        var result=search.retrieveWithPermission("body","2",10);
        assertFalse(result.isDegraded());
        assertEquals(Set.of("current","figure","team","public"),new HashSet<>(result.results().stream().map(RetrievalResult::getEntryId).toList()));
        var figure=result.results().stream().filter(r -> r.getDocumentType()==EsDocument.DocumentType.FIGURE).findFirst().orElseThrow();
        assertEquals(2L,figure.getProcessingGeneration()); assertEquals("abc.pdf",figure.getFileName());
        assertEquals("caption",figure.getCaption()); assertNotNull(figure.getImagePath());
        assertEquals(2,requests.size()); assertTrue(requests.get(0).has("knn")); assertFalse(requests.get(0).has("query"));
        assertFalse(requests.get(1).has("knn"));
        JsonNode knn=requests.get(0).get("knn"); if (knn.isArray()) knn=knn.get(0);
        assertEquals(knn.get("filter"),requests.get(1).get("query").get("bool").get("filter"));
    }
    @Test void publicCompatibilityScopeCannotReturnPrivateOrOrgOnlyFiles() {
        var result=search.retrieve("body",10);
        assertEquals(List.of("published"),result.results().stream().map(RetrievalResult::getFileMd5).toList());
    }
    @Test void parsedAndTombstonedRowsExcludedByActualDerivedQuery() {
        tx.executeWithoutResult(s -> { var c=contents.findByFileMd5("abc").orElseThrow(); c.setDeletedAt(java.time.LocalDateTime.now()); contents.save(c); });
        var rows=contents.findByFileMd5InAndProcessingStatusAndDeletedAtIsNull(List.of("abc","pending","team"),FileContent.ProcessingStatus.INDEXED);
        assertEquals(List.of("team"),rows.stream().map(FileContent::getFileMd5).toList());
    }
    @Test void replacingGenerationRejectsEarlierEsDocumentsUntilNewGenerationIsIndexed() {
        tx.executeWithoutResult(s -> { var c=contents.findByFileMd5("abc").orElseThrow(); c.setProcessingGeneration(3); c.setProcessingStatus(FileContent.ProcessingStatus.PARSED); contents.save(c); });
        assertTrue(search.retrieveWithPermission("body","2",10).results().stream().noneMatch(r -> r.getFileMd5().equals("abc")));
    }
    @Test void removedRelationRejectsStaleEsAccessInBothBranches() {
        tx.executeWithoutResult(s -> uploads.deleteByFileMd5AndUserId("abc","2"));
        assertTrue(search.retrieveWithPermission("body","2",10).results().stream().noneMatch(r -> r.getFileMd5().equals("abc")));
    }
}
