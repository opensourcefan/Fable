package org.fable.config;

import org.fable.service.network.OutboundHttpSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient(HttpClient httpClient) {
        return RestClient.builder()
                .requestFactory(OutboundHttpSupport.buildTimeoutRequestFactory(httpClient, OutboundHttpSupport.DEFAULT_READ_TIMEOUT))
                .build();
    }

    @Bean
    public RestTemplate oidcRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}

