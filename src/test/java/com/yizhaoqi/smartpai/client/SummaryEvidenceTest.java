package com.yizhaoqi.smartpai.client;

import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.*;
import com.yizhaoqi.smartpai.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SummaryEvidenceTest {
    RagContextAssembler assembler=new RagContextAssembler(1000,400);
    UsageQuotaService quota=mock(UsageQuotaService.class);
    DeepSeekClient client=new DeepSeekClient("http://127.0.0.1:1","","model",new AiProperties(),quota,
            mock(ModelProviderConfigService.class),assembler);
    RetrievalResult text(String id) {
        var r=new RetrievalResult();r.setEntryId(id);r.setDocumentType(EsDocument.DocumentType.TEXT);
        r.setTextContent("evidence "+id);return r;
    }
    @Test void summaryPromptUsesOriginalSourceNumbersAndInsufficiencyRule() {
        var session=assembler.newSession();session.add(new RetrievalResponse(List.of(text("A")),Set.of(),5,30),"q");
        var selected=session.add(new RetrievalResponse(List.of(text("B")),Set.of(),5,30),"topic");
        var messages=client.buildSummaryMessages("topic",selected);
        assertTrue(messages.get(1).get("content").contains("[2] TEXT"));
        assertFalse(messages.get(1).get("content").contains("[1]"));
        assertTrue(messages.get(0).get("content").contains("不重新编号"));
        assertTrue(messages.get(0).get("content").contains("资料不足"));
        assertTrue(messages.get(0).get("content").contains("两处 N 相同"));
    }
    @Test void emptySummaryDoesNotCallModelAndKeepsDegradationWarning() {
        var context=assembler.newSession().add(new RetrievalResponse(List.of(),Set.of(RetrievalResult.Channel.VECTOR),5,30),"q");
        List<String> chunks=new ArrayList<>();var result=client.summarizeContext("1","q",context,chunks::add);
        assertTrue(result.contains("检索降级"));assertEquals(List.of(result),chunks);verifyNoInteractions(quota);
    }
    @Test void legacySummaryListStillWorksForEmptyInputWithoutHttp() {
        assertTrue(client.summarize("1","q",List.of()).contains("无法生成"));verifyNoInteractions(quota);
    }
}
