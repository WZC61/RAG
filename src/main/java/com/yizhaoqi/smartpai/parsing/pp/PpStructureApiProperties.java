package com.yizhaoqi.smartpai.parsing.pp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Credentials deliberately excluded from generated toString methods. */
@Getter
@Setter
@ConfigurationProperties(prefix = "paddle.pp-structure")
public class PpStructureApiProperties {
    private boolean enabled = false;
    private String baseUrl = "https://paddleocr.aistudio-app.com";
    private String accessToken = "";
    private String model = "PP-StructureV3";
    private Duration requestTimeout = Duration.ofMinutes(5);
    private Duration pollTimeout = Duration.ofMinutes(10);
    private Duration initialPollInterval = Duration.ofSeconds(3);
    private Duration maxPollInterval = Duration.ofSeconds(15);
    private int resultMaxBytes = 32 * 1024 * 1024;
}
