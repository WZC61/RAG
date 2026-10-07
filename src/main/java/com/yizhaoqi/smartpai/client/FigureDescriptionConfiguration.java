package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Independent from chat routing and deliberately usable without credentials at startup. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FigureDescriptionProperties.class)
public class FigureDescriptionConfiguration {
    @Bean
    public FigureDescriptionClient figureDescriptionClient(FigureDescriptionProperties properties, ObjectMapper mapper) {
        return new FigureDescriptionClient(properties, mapper);
    }
}
