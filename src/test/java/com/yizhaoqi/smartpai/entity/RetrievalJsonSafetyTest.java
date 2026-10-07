package com.yizhaoqi.smartpai.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ChatHandler;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalJsonSafetyTest {
    @Test void internalImagePathIsNotPartOfSearchOrRetrievalJson() throws Exception {
        var mapper = new ObjectMapper();
        var figure = new RetrievalResult();
        figure.setEntryId("FIGURE:abc:1:4:1"); figure.setFileMd5("abc");
        figure.setDocumentType(EsDocument.DocumentType.FIGURE); figure.setProcessingGeneration(1L);
        figure.setPageNumber(4); figure.setFigureIndex(1); figure.setImagePath("figures/private.jpg");
        var response = new RetrievalResponse(java.util.List.of(figure), Set.of(), 5, 30);
        var search = response.toSearchResults().get(0);
        for (Object dto : java.util.List.of(figure, search, response)) {
            String json = mapper.writeValueAsString(dto);
            assertFalse(json.contains("imagePath")); assertFalse(json.contains("private.jpg"));
            assertTrue(json.contains("figureIndex")); assertTrue(json.contains("processingGeneration"));
        }
        assertEquals("figures/private.jpg", figure.getImagePath());
        assertEquals("figures/private.jpg", search.getImagePath());
    }
    @Test void referenceRecordCannotDeserializeOrSerializeAnInternalPath() throws Exception {
        var mapper = new ObjectMapper();
        var reference = mapper.convertValue(Map.of("fileMd5", "abc", "imagePath", "private/key",
                "documentType", "FIGURE", "pageNumber", 4, "figureIndex", 1), ChatHandler.ReferenceInfo.class);
        assertNull(reference.imagePath());
        assertFalse(mapper.writeValueAsString(reference).contains("imagePath"));
        assertEquals(1, reference.figureIndex());
    }
}
