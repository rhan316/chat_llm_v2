package org.dar316.spring_ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AiApiConfig {

    @Bean
    public RestClient aiRestClient(
            @Value("${ai.api.url}") String baseUrl,
            @Value("${ai.api.key}") String apiKey
    ) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(dh -> {
                    dh.setBearerAuth(apiKey);
                    dh.setContentType(MediaType.APPLICATION_JSON);
                })
                .build();
    }
}
