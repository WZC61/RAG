package com.yizhaoqi.smartpai.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EsDocumentTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyExtraMetadataDoesNotBreakReadingTheCurrentAcl() throws Exception {
        EsDocument doc = mapper.readValue("""
                {"fileMd5":"abc","contentType":"text","allowedUserIds":["2"],
                 "allowedOrgTags":["TEAM"],"public":false,"textContent":"body"}
                """, EsDocument.class);
        assertEquals("abc", doc.getFileMd5());
        assertEquals(List.of("2"), doc.getAllowedUserIds());
        assertEquals(List.of("TEAM"), doc.getAllowedOrgTags());
        assertFalse(doc.isPublic());
        assertFalse(mapper.readTree(mapper.writeValueAsString(doc)).has("contentType"));
    }

    @Test
    void publicHasOneExplicitWireNameAndReadsHistoricalAlias() throws Exception {
        EsDocument doc = new EsDocument("id", "md5", 1, "text", 2, "anchor",
                new float[]{1}, "model", "user", "org", true);
        var json = mapper.readTree(mapper.writeValueAsString(doc));
        assertTrue(json.path("public").asBoolean());
        assertFalse(json.has("isPublic"));
        assertTrue(mapper.readValue("{\"public\":true}", EsDocument.class).isPublic());
        assertTrue(mapper.readValue("{\"isPublic\":true}", EsDocument.class).isPublic());
        assertEquals(EsDocument.DocumentType.TEXT, doc.getDocumentType());
    }

    @Test
    void figureMetadataAndLongGenerationRoundTrip() throws Exception {
        EsDocument doc = new EsDocument();
        doc.setDocumentType(EsDocument.DocumentType.FIGURE); doc.setProcessingGeneration(2147483648L);
        doc.setFigureIndex(2); doc.setFigureLabel("Figure 2"); doc.setPageNumber(4);
        doc.setImagePath("figures/md5/1/page-4-figure-2.png"); doc.setBbox(List.of(1.0, 2.0, 3.0, 4.0));
        doc.setCaption("caption"); doc.setDescription("description"); doc.setOcrText("OCR"); doc.setNearbyText("nearby");
        EsDocument restored = mapper.readValue(mapper.writeValueAsString(doc), EsDocument.class);
        assertEquals(doc, restored); assertNull(restored.getChunkId());
    }

    @Test
    void mappingExplicitlyCoversMultimodalIdentityAndResourceFields() throws Exception {
        try (var input = getClass().getResourceAsStream("/es-mappings/knowledge_base.json")) {
            var fields = mapper.readTree(input).path("mappings").path("properties");
            assertEquals("keyword", fields.path("documentType").path("type").asText());
            assertEquals("long", fields.path("processingGeneration").path("type").asText());
            assertEquals("boolean", fields.path("public").path("type").asText());
            assertFalse(fields.has("isPublic"));
            for (String name : List.of("figureIndex", "figureLabel", "imagePath", "bbox", "caption", "description", "ocrText", "nearbyText"))
                assertTrue(fields.has(name), name);
            assertFalse(fields.path("imagePath").path("index").asBoolean());
        }
    }
}
