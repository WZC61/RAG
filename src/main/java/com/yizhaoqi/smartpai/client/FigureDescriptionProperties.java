package com.yizhaoqi.smartpai.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** No generated toString: credentials must never be included in diagnostics. */
@Getter
@Setter
@ConfigurationProperties(prefix = "file.parsing.figure-description")
public class FigureDescriptionProperties {
    private String endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private String apiKey = "";
    private String model = "qwen3-vl-flash";
    private Duration requestTimeout = Duration.ofSeconds(90);
    private int maxImageBytes = 10 * 1024 * 1024;
    private int maxDescriptionLength = 1000;
    private int maxContextChars = 12000;
    private int maxResponseBytes = 64 * 1024;
}
