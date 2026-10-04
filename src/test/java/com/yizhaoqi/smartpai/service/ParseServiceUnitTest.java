package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParseService 的单元测试类 (不依赖Spring Context)
 * 保留 LiteParse 命令和专属文本清洗回归；通用切片测试已迁移到 TextChunkerTest
 */
class ParseServiceUnitTest {

    private ParseService parseService;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        // 设置配置值
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);
        ReflectionTestUtils.setField(parseService, "aliyunOcrEnabled", false);
        ReflectionTestUtils.setField(parseService, "aliyunOcrCallbackToken", "");
        ReflectionTestUtils.setField(parseService, "serverPort", 8081);
        ReflectionTestUtils.setField(parseService, "serverContextPath", "");
    }

    @Test
    void testBuildLiteParseCommand_UsesJsonOutputAndOcrOptions() throws Exception {
        ReflectionTestUtils.setField(parseService, "liteParseCommand", "lit");
        ReflectionTestUtils.setField(parseService, "liteParseOcrEnabled", true);
        ReflectionTestUtils.setField(parseService, "liteParseOcrLanguage", "chi_sim+eng");
        ReflectionTestUtils.setField(parseService, "liteParseTessdataPath", "");
        ReflectionTestUtils.setField(parseService, "liteParseMaxPages", 200);
        ReflectionTestUtils.setField(parseService, "liteParseDpi", 180);
        ReflectionTestUtils.setField(parseService, "liteParseNumWorkers", 2);

        Method method = ParseService.class.getDeclaredMethod("buildLiteParseCommand", Path.class, Path.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) method.invoke(parseService, Path.of("/tmp/input.pdf"), Path.of("/tmp/output.json"));

        assertEquals("lit", command.get(0));
        assertTrue(command.contains("parse"));
        assertTrue(command.contains("--format"));
        assertTrue(command.contains("json"));
        assertTrue(command.contains("--output"));
        assertTrue(command.contains(Path.of("/tmp/output.json").toString()));
        assertTrue(command.contains("--ocr-language"));
        assertTrue(command.contains("chi_sim+eng"));
        assertFalse(command.contains("--ocr-server-url"));
        assertTrue(command.contains("--num-workers"));
        assertTrue(command.contains("2"));
        assertFalse(command.contains("--no-ocr"));
        assertFalse(command.contains("--tessdata-path"));
    }

    @Test
    void testBuildLiteParseCommand_AutoUsesInternalAliyunAdapterWhenEnabled() throws Exception {
        ReflectionTestUtils.setField(parseService, "liteParseCommand", "lit");
        ReflectionTestUtils.setField(parseService, "liteParseOcrEnabled", true);
        ReflectionTestUtils.setField(parseService, "liteParseOcrLanguage", "chi_sim");
        ReflectionTestUtils.setField(parseService, "aliyunOcrEnabled", true);
        ReflectionTestUtils.setField(parseService, "aliyunOcrCallbackToken", "token 1");
        ReflectionTestUtils.setField(parseService, "serverPort", 8081);
        ReflectionTestUtils.setField(parseService, "serverContextPath", "");
        ReflectionTestUtils.setField(parseService, "liteParseMaxPages", 200);
        ReflectionTestUtils.setField(parseService, "liteParseDpi", 300);
        ReflectionTestUtils.setField(parseService, "liteParseNumWorkers", 0);

        Method method = ParseService.class.getDeclaredMethod("buildLiteParseCommand", Path.class, Path.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) method.invoke(parseService, Path.of("/tmp/input.pdf"), Path.of("/tmp/output.json"));

        assertTrue(command.contains("--ocr-server-url"));
        assertTrue(command.contains("http://127.0.0.1:8081/api/v1/internal/ocr/liteparse?token=token+1"));
    }

    @Test
    void testNormalizeLiteParseText_CleansChineseOcrSpacingAndPageFooter() throws Exception {
        String text = """
                哥们 明年

                  我 靠 派聪明 拿 到 的 日 常实习

                  学 习 这人 么 快噢直接 用

                  我 把 派 聪明放 第 一 个了，  感 觉 面试 官 都 围绕这
                  问
                  个

                  No. 2 / 37
                """;

        String normalized = normalizeLiteParseText(text);

        assertTrue(normalized.contains("哥们明年"));
        assertTrue(normalized.contains("我靠派聪明拿到的日常实习"));
        assertTrue(normalized.contains("学习这人么快噢直接用"));
        assertTrue(normalized.contains("我把派聪明放第一个了，感觉面试官都围绕这"));
        assertFalse(normalized.contains("No. 2 / 37"));
    }

    @Test
    void testNormalizeLiteParseText_CleansAliyunOcrPageOneOutputBeforeEmbedding() throws Exception {
        String text = """
                    paismart-让天下所有的⾯渣都能逆袭

                ⼤家好，我是⼆哥呀。

                派聪明是 2025年 9 ⽉份上线的，截⽌到⽬前，已经取得了⾮常瞩⽬的成绩，我这⾥晒⼀下哈。


                二哥，目前靠着星球面渣逆袭+rag项目+球友分
                享优质面经侥幸oc了深圳招银网络和合肥科大讯飞
                java，但是十月还想冲一冲大厂，请问有机会吗，其
                实上面两家都是我线下面oc的，我个人也感觉自己
                线下发挥会好一些，想问问历年大中厂在10月国庆
                后还会陆续开展线下双选会的情况吗，谢谢二哥女


                14：26


                那必须有










                No. 1 / 37%
                """;

        String normalized = normalizeLiteParseText(text);

        assertTrue(normalized.contains("派聪明是2025年9⽉份上线的"));
        assertTrue(normalized.contains("二哥，目前靠着星球面渣逆袭+rag项目+球友分"));
        assertTrue(normalized.contains("享优质面经侥幸oc了深圳招银网络和合肥科大讯飞"));
        assertTrue(normalized.contains("那必须有"));
        assertFalse(normalized.contains("No. 1 / 37"));
        assertFalse(normalized.contains("\n\n\n"));
    }

    @Test
    void previewNormalizeLiteParseText_PrintsBeforeAndAfter() throws Exception {
        String text = """
                哥们 明年

                  我 靠 派聪明 拿 到 的 日 常实习

                  学 习 这人 么 快噢直接 用

                  我 把 派 聪明放 第 一 个了，  感 觉 面试 官 都 围绕这
                  问
                  个

                  No. 2 / 37
                """;

        System.out.println("=== LiteParse OCR 清洗前 ===");
        System.out.println(text);
        System.out.println("=== LiteParse OCR 清洗后 ===");
        System.out.println(normalizeLiteParseText(text));
    }

    @Test
    void previewNormalizeLiteParseText_PageOneSample() throws Exception {
        String text = """
                哥，  目 前 靠 着 星球 面 渣 逆 十       项    目
                二                                             十
                                             rag            球友分
                享     面经侥幸                   络
                优质     oc     了 深圳 招 银 网             和 合肥 科大讯 飞
                java， 但 是十月   还 想 冲 一 冲 大  厂 ，请    问有 机 会   吗 ，其
                实 上 面 两家 都 是 我 线 下     的   ， 我 个    人也       自己
                    面oc                                      感觉
                                                  大中厂在     月 国庆
                线 下 发 挥 会好 一  些 ，想 问问 历年       10
                后 还 会 陆续 开展 线 下 双 选 会 的 情况     吗    ，谢 谢 二 哥

                                           14:26

                                               有
                                               有

                                               No. 1 / 37
                """;

        System.out.println("=== LiteParse OCR 第1页清洗前 ===");
        System.out.println(text);
        System.out.println("=== LiteParse OCR 第1页清洗后 ===");
        System.out.println(normalizeLiteParseText(text));
    }

    private String normalizeLiteParseText(String text) throws Exception {
        Method method = ParseService.class.getDeclaredMethod("normalizeLiteParseText", String.class);
        method.setAccessible(true);
        return (String) method.invoke(parseService, text);
    }
}
