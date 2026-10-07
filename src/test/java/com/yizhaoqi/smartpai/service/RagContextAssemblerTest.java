package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RagContextAssemblerTest {
    static RetrievalResult text(String id) {
        var r = new RetrievalResult();
        r.setEntryId(id); r.setDocumentType(EsDocument.DocumentType.TEXT);
        r.setFileMd5("md5"); r.setProcessingGeneration(2L); r.setChunkId(1);
        r.setFileName("paper.pdf"); r.setPageNumber(3); r.setAnchorText("anchor");
        r.setTextContent("Reliable text evidence " + id); return r;
    }
    static RetrievalResult figure(String id) {
        var r = text(id); r.setDocumentType(EsDocument.DocumentType.FIGURE); r.setChunkId(null);
        r.setFigureIndex(2); r.setFigureLabel("Figure 2"); r.setImagePath("private/figures/image.png");
        r.setCaption("Model architecture"); r.setDescription("Encoder connects to decoder");
        r.setOcrText("Encoder Decoder"); r.setBbox(List.of(1d,2d,3d,4d)); return r;
    }
    static RetrievalResponse response(RetrievalResult... results) {
        return new RetrievalResponse(List.of(results), Set.of(), 5, 30);
    }
    @Test void textPreservesIdentityAndPage() {
        var c = new RagContextAssembler().newSession().add(response(text("TEXT:m:2:1")),"q");
        assertTrue(c.text().contains("[1] TEXT")); assertTrue(c.text().contains("第3页"));
        assertEquals("TEXT:m:2:1",c.evidence().get(0).identity());
    }
    @Test void figureUsesSemanticFieldsWithoutStoragePath() {
        var c = new RagContextAssembler().newSession().add(response(figure("F")),"q");
        assertTrue(c.text().contains("Caption: Model architecture"));
        assertTrue(c.text().contains("Description: Encoder connects to decoder"));
        assertTrue(c.text().contains("OCR: Encoder Decoder"));
        assertFalse(c.text().contains("private/figures"));
        assertEquals("private/figures/image.png",c.evidence().get(0).source().getImagePath());
    }
    @Test void mixedKeepsRetrievalOrder() {
        var c = new RagContextAssembler().newSession().add(response(figure("F"),text("T")),"q");
        assertEquals(List.of("F","T"),c.evidence().stream().map(RagContextAssembler.Evidence::identity).toList());
        assertTrue(c.text().indexOf("[1] FIGURE") < c.text().indexOf("[2] TEXT"));
    }
    @Test void emptyIsExplicit() {
        var c = new RagContextAssembler().newSession().add(response(),"q");
        assertTrue(c.empty()); assertTrue(c.text().contains("正常检索")); assertTrue(c.evidence().isEmpty());
    }
    @Test void degradedIsDistinctFromNormalEmpty() {
        var c = new RagContextAssembler().newSession().add(new RetrievalResponse(List.of(text("T")),
                Set.of(RetrievalResult.Channel.VECTOR),5,30),"q");
        assertTrue(c.degraded()); assertFalse(c.empty()); assertTrue(c.text().contains("检索降级"));
    }
    @Test void repeatedInternalSearchReusesAndAppendsNumbers() {
        var s = new RagContextAssembler().newSession();
        s.add(response(text("A"),figure("B")),"first");
        var second = s.add(response(figure("B"),text("C"),text("A")),"next");
        assertEquals(List.of(2,3,1),second.evidence().stream().map(RagContextAssembler.Evidence::number).toList());
        assertEquals(List.of(1,2,3),s.snapshot().evidence().stream().map(RagContextAssembler.Evidence::number).toList());
        assertEquals("first",s.snapshot().evidence().get(0).query());
    }
    @Test void registryDoesNotReplaceEarlierTextOnSameIdentity() {
        var s = new RagContextAssembler().newSession(); s.add(response(text("A")),"q");
        var updated=text("A"); updated.setTextContent("changed");
        assertFalse(s.add(response(updated),"new").text().contains("changed"));
    }
    @Test void nextAnswerHasIndependentNumbering() {
        var a = new RagContextAssembler(); a.newSession().add(response(text("A"),text("B")),"q");
        assertEquals(1,a.newSession().add(response(text("C")),"next").evidence().get(0).number());
    }
    @Test void globalAndPerEntryBudgetsApplyAcrossToolCalls() {
        var s = new RagContextAssembler(1000,400).newSession();
        var a=text("A"); a.setTextContent("a".repeat(2000));
        var b=text("B"); b.setTextContent("b".repeat(2000));
        s.add(response(a),"q"); s.add(response(b,text("C"),text("D")),"q2");
        var c=s.snapshot(); assertTrue(c.text().length()<=1000);
        assertTrue(c.evidence().stream().allMatch(e->e.text().length()<=400));
        assertEquals("A",c.evidence().get(0).identity()); assertFalse(c.text().contains("[4]"));
    }
    @Test void newSearchAtFullBudgetStillReusesRegisteredEvidence() {
        var s=new RagContextAssembler(512,256).newSession(); var a=text("A"); a.setTextContent("a".repeat(900));
        s.add(response(a),"q");
        assertEquals(1,s.add(response(a,text("B")),"q2").evidence().get(0).number());
        assertEquals(1,s.snapshot().evidence().size());
    }
    @Test void blankFigureFieldsAreAllowed() {
        var f=figure("F"); f.setCaption(null); f.setOcrText(" ");
        var c=new RagContextAssembler().newSession().add(response(f),"q");
        assertTrue(c.text().contains("Description:")); assertFalse(c.text().contains("Caption:"));
    }
    @Test void duplicateInputDoesNotDuplicateContext() {
        var c=new RagContextAssembler().newSession().add(response(text("T"),text("T")),"q");
        assertEquals(1,c.evidence().size());
    }
    @Test void invalidBudgetFailsClearly() {
        assertThrows(IllegalArgumentException.class,()->new RagContextAssembler(300,200));
        assertThrows(IllegalArgumentException.class,()->new RagContextAssembler(1000,50));
    }
}
