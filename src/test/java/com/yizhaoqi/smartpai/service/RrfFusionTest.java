package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.entity.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RrfFusionTest {
    final RrfFusion fusion = new RrfFusion();
    static Hit<EsDocument> text(String id, String md5, int chunk, Integer page, String content) {
        EsDocument d = new EsDocument(); d.setDocumentType(EsDocument.DocumentType.TEXT);
        d.setFileMd5(md5); d.setProcessingGeneration(3L); d.setChunkId(chunk); d.setPageNumber(page);
        d.setTextContent(content); d.setAnchorText("anchor");
        return Hit.of(h -> h.id(id).index("knowledge_base").score(999d).source(d));
    }
    static Hit<EsDocument> figure(String id, int index) {
        EsDocument d = new EsDocument(); d.setDocumentType(EsDocument.DocumentType.FIGURE);
        d.setFileMd5("abc"); d.setProcessingGeneration(3L); d.setPageNumber(1); d.setFigureIndex(index);
        d.setFigureLabel("Figure " + index); d.setImagePath("figures/abc/3/figure.png");
        d.setBbox(List.of(1d, 2d, 3d, 4d)); d.setCaption("caption"); d.setDescription("description");
        d.setOcrText("ocr"); d.setTextContent("caption\n\ndescription\n\nocr");
        return Hit.of(h -> h.id(id).index("knowledge_base").score(.001d).source(d));
    }
    @Test void sharedIdSumsReciprocalRanksRegardlessOfOriginalScores() {
        var a = text("a", "a", 1, 1, "alpha"); var b = text("b", "b", 1, 1, "beta");
        var results = fusion.fuse(List.of(a, b), List.of(b, a), 10);
        assertEquals(2, results.size());
        assertEquals(1d/61 + 1d/62, results.get(0).getRrfScore(), 1e-12);
        assertEquals("a", results.get(0).getEntryId()); // deterministic tie
        assertEquals(1, results.get(0).getVectorRank()); assertEquals(2, results.get(0).getBm25Rank());
        assertEquals(Set.of(RetrievalResult.Channel.VECTOR, RetrievalResult.Channel.BM25), results.get(0).getMatchedChannels());
    }
    @Test void sharedEvidenceOutranksSingleChannelRankOne() {
        var a = text("a", "a", 1, 1, "a"); var b = text("b", "b", 1, 1, "b"); var c = text("c", "c", 1, 1, "c");
        assertEquals("b", fusion.fuse(List.of(a,b), List.of(c,b), 1).get(0).getEntryId());
    }
    @Test void repeatedIdWithinOneBranchOnlyContributesOnce() {
        var a = text("a", "a", 1, 1, "a");
        var results = fusion.fuse(List.of(a,a,a), List.of(), 10);
        assertEquals(1, results.size()); assertEquals(1d/61, results.get(0).getRrfScore());
    }
    @Test void differentEsIdsWithSameBusinessIdentityAreDeduplicated() {
        var results = fusion.fuse(List.of(text("a","abc",1,1,"first"), text("b","abc",1,1,"second")), List.of(), 5);
        assertEquals(1, results.size());
    }
    @Test void identicalTextWithDifferentChunkIdsIsDeduplicatedOnSamePage() {
        var results = fusion.fuse(List.of(text("a","abc",1,1,"hello world"), text("b","abc",2,1,"hello\nworld")), List.of(), 5);
        assertEquals(1, results.size());
    }
    @Test void samePageTextDoesNotCrowdOutOtherPagesOrFigure() {
        var results = fusion.fuse(List.of(text("a","abc",1,1,"one"), text("b","abc",2,1,"two"),
                text("c","abc",3,1,"three"), text("d","abc",4,2,"four"), figure("f",1)), List.of(), 4);
        assertEquals(List.of("a","b","d","f"), results.stream().map(RetrievalResult::getEntryId).toList());
    }
    @Test void unknownPageDoesNotCapEntireTikaDocumentAtTwoChunks() {
        assertEquals(3, fusion.fuse(List.of(text("a","abc",1,null,"one"), text("b","abc",2,null,"two"),
                text("c","abc",3,null,"three")), List.of(), 5).size());
    }
    @Test void differentFiguresRemainIndependentEvenWithSameText() {
        assertEquals(2, fusion.fuse(List.of(figure("f1",1), figure("f2",2)), List.of(), 5).size());
    }
    @Test void figureMetadataAndNullChunkIdSurviveFusionAndLegacyAdapter() {
        var result = fusion.fuse(List.of(figure("f",1)), List.of(), 5).get(0);
        result.setFileName("paper.pdf");
        var response = new RetrievalResponse(List.of(result), Set.of(RetrievalResult.Channel.BM25), 5, 30);
        var old = response.toSearchResults().get(0);
        assertEquals(EsDocument.DocumentType.FIGURE, old.getDocumentType()); assertNull(old.getChunkId());
        assertEquals(3L, old.getProcessingGeneration()); assertEquals(1, old.getPageNumber());
        assertEquals("Figure 1", old.getFigureLabel()); assertEquals(1, old.getFigureIndex());
        assertEquals("figures/abc/3/figure.png", old.getImagePath()); assertEquals(List.of(1d,2d,3d,4d), old.getBbox());
        assertEquals("caption", old.getCaption()); assertEquals("description", old.getDescription()); assertEquals("ocr", old.getOcrText());
        assertEquals("paper.pdf", old.getFileName()); assertEquals("VECTOR_ONLY", old.getRetrievalMode());
        assertTrue(old.isDegraded()); assertEquals(result.getRrfScore(), old.getScore());
    }
    @Test void textMetadataSurvivesFusion() {
        var result = fusion.fuse(List.of(text("id","abc",7,2,"content")), List.of(), 5).get(0);
        assertEquals(EsDocument.DocumentType.TEXT, result.getDocumentType()); assertEquals(7, result.getChunkId());
        assertEquals("anchor", result.getAnchorText()); assertEquals(2, result.getPageNumber());
    }
    @Test void malformedHitsAreIgnored() {
        var blank = text("a", "a", 1, 1, "  ");
        var noSource = Hit.<EsDocument>of(h -> h.id("b").index("knowledge_base"));
        assertTrue(fusion.fuse(List.of(blank, noSource), List.of(), 5).isEmpty());
    }
    @Test void topKIsAppliedAfterFusionAndDeduplication() {
        var a=text("a","a",1,1,"a"); var b=text("b","b",1,1,"b");
        assertEquals(1, fusion.fuse(List.of(a,b), List.of(), 1).size());
        assertTrue(fusion.fuse(List.of(), List.of(), 10).isEmpty());
    }
}
