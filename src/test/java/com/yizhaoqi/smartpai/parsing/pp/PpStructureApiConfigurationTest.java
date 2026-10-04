package com.yizhaoqi.smartpai.parsing.pp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PpStructureApiConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PpStructureApiConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledByDefaultNeedsNoTokenAndCreatesNoClient() {
        context.run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(PpStructureApiClient.class);
            assertThat(c.getBean(PpStructureApiProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    void enabledWithoutTokenFailsLocally() {
        context.withPropertyValues("paddle.pp-structure.enabled=true").run(c -> assertThat(c).hasFailed());
    }

    @Test
    void bindsEnvironmentPlaceholderAndDurationsWithoutMakingRequests() {
        context.withPropertyValues("paddle.pp-structure.enabled=true", "PADDLEOCR_ACCESS_TOKEN=mock-token",
                "paddle.pp-structure.access-token=${PADDLEOCR_ACCESS_TOKEN:}",
                "paddle.pp-structure.request-timeout=12s", "paddle.pp-structure.result-max-bytes=1024")
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(PpStructureApiClient.class);
                    var p = c.getBean(PpStructureApiProperties.class);
                    assertThat(p.getAccessToken()).isEqualTo("mock-token");
                    assertThat(p.getRequestTimeout().toSeconds()).isEqualTo(12);
                    assertThat(p.getResultMaxBytes()).isEqualTo(1024);
                    assertThat(p.toString()).doesNotContain("mock-token");
                });
    }

    @Test
    void rejectsInvalidTimingConfiguration() {
        context.withPropertyValues("paddle.pp-structure.enabled=true", "paddle.pp-structure.access-token=mock-token",
                "paddle.pp-structure.poll-timeout=0s").run(c -> assertThat(c).hasFailed());
    }
}
