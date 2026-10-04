package com.yizhaoqi.smartpai.parsing.pp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.parsing.PpStructureResultMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PpStructureApiProperties.class)
public class PpStructureApiConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "paddle.pp-structure", name = "enabled", havingValue = "true")
    public PpStructureApiClient ppStructureApiClient(PpStructureApiProperties properties, ObjectMapper json) {
        return new PpStructureApiClient(properties, json, new PpStructureResultDecoder(json),
                new PpStructureResultMapper(json));
    }
}
