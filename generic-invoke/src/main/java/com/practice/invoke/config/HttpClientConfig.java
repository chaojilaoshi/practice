package com.practice.invoke.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端配置：
 * - loadBalancedRestTemplate：带 @LoadBalanced，URL 写服务名（http://service-b）时由 Nacos 解析 + 负载均衡。
 * - plainRestTemplate：普通模板，用于 base_url 硬直连（不需要服务发现）。
 */
@Configuration
public class HttpClientConfig {

    /** 默认连接超时（ms），可被 api_config 覆盖（仅直连路径按配置生效） */
    private static final int DEFAULT_CONNECT_TIMEOUT = 2000;
    private static final int DEFAULT_READ_TIMEOUT = 5000;

    @Bean
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate() {
        return new RestTemplate(buildFactory(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT));
    }

    @Bean
    public RestTemplate plainRestTemplate() {
        return new RestTemplate(buildFactory(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT));
    }

    public static SimpleClientHttpRequestFactory buildFactory(Integer connectTimeout, Integer readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout != null ? readTimeout : DEFAULT_READ_TIMEOUT);
        return factory;
    }
}
