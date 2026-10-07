package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagPromptTest {
    LlmProviderRouter router=new LlmProviderRouter(new AiProperties(),mock(RateLimitService.class),
            mock(UsageQuotaService.class),mock(ModelProviderConfigService.class),new ObjectMapper());
    @Test void proactiveContextDoesNotForceRedundantToolOrCertainAnswer() {
        var messages=router.buildReActMessages("q","[1] TEXT | paper\nevidence",List.of());
        String system=messages.get(0).get("content").toString();
        assertTrue(system.contains("服务端已主动检索"));assertTrue(system.contains("资料不足以确定"));
        assertTrue(system.contains("引用实际使用的证据编号"));assertFalse(system.contains("每一个用户问题都必须"));
        assertTrue(system.contains("句末引用只输出 [N]"));
        assertTrue(system.contains("不要自行补写来源标签或页码"));
        assertFalse(system.contains("只要 search_knowledge 返回了片段，必须"));assertTrue(system.contains("[1] TEXT"));
    }
    @Test void historyIsBoundedAndPastReferencesAreScopedToPastAnswer() {
        List<Map<String,String>> history=new ArrayList<>();
        for(int i=0;i<10;i++) history.add(Map.of("role","assistant","content","old [1]"+"x".repeat(2000)));
        var messages=router.buildReActMessages("q","[1] TEXT new",history);
        assertEquals(8,messages.size());
        assertTrue(messages.get(0).get("content").toString().contains("引用属于此前回答"));
        assertTrue(messages.subList(1,7).stream().allMatch(m->m.get("content").toString().length()<=803));
    }
    @Test void pastAnswerCitationAnnotationsAreRemovedOnlyFromModelHistory() {
        var history=List.of(Map.of("role","assistant","content","Past fact [9] (来源#9: old.pdf | 第2页)。"),
                Map.of("role","user","content","问题中的 [9] 是输入值"));
        var messages=router.buildReActMessages("q","[1] TEXT | new.pdf\ncurrent evidence",history);
        assertFalse(messages.get(1).get("content").toString().contains("[9]"));
        assertFalse(messages.get(1).get("content").toString().contains("来源#"));
        assertTrue(messages.get(2).get("content").toString().contains("[9]"));
        assertTrue(history.get(0).get("content").contains("来源#9"));
        assertTrue(messages.get(0).get("content").toString().contains("每个引用须由该编号当前证据正文支持"));
    }
}
