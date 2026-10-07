package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FigureDescriptionConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(FigureDescriptionConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void startupWithoutKeyCreatesClientAndFailsClearlyOnlyWhenInvoked() {
        context.run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(FigureDescriptionClient.class);
            FigureDescriptionProperties properties = c.getBean(FigureDescriptionProperties.class);
            assertThat(properties.getApiKey()).isEmpty();
            assertThat(properties.getModel()).isEqualTo("qwen3-vl-flash");
            assertThat(properties.getRequestTimeout().toSeconds()).isEqualTo(90);
            assertThat(properties.getMaxImageBytes()).isEqualTo(10 * 1024 * 1024);
            assertThrows(IOException.class, () -> c.getBean(FigureDescriptionClient.class)
                    .describe(new byte[]{1}, "image/jpeg", null, null, null));
        });
    }

    @Test
    void bindsEnvironmentKeyAndIndependentLimitsWithoutPrintingSecrets() {
        context.withPropertyValues("DASHSCOPE_API_KEY=mock-secret", "file.parsing.figure-description.api-key=${DASHSCOPE_API_KEY:}",
                "file.parsing.figure-description.endpoint=http://127.0.0.1/chat/completions",
                "file.parsing.figure-description.model=configured-model", "file.parsing.figure-description.request-timeout=12s",
                "file.parsing.figure-description.max-image-bytes=1024", "file.parsing.figure-description.max-description-length=500",
                "file.parsing.figure-description.max-context-chars=3000", "file.parsing.figure-description.max-response-bytes=2048")
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(FigureDescriptionClient.class);
                    FigureDescriptionProperties properties = c.getBean(FigureDescriptionProperties.class);
                    assertThat(properties.getApiKey()).isEqualTo("mock-secret");
                    assertThat(properties.getModel()).isEqualTo("configured-model");
                    assertThat(properties.getRequestTimeout().toSeconds()).isEqualTo(12);
                    assertThat(properties.getMaxImageBytes()).isEqualTo(1024);
                    assertThat(properties.getMaxDescriptionLength()).isEqualTo(500);
                    assertThat(properties.getMaxContextChars()).isEqualTo(3000);
                    assertThat(properties.getMaxResponseBytes()).isEqualTo(2048);
                    assertThat(properties.toString()).doesNotContain("mock-secret");
                });
    }
}
