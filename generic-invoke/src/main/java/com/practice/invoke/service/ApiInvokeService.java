package com.practice.invoke.service;

import com.practice.invoke.config.HttpClientConfig;
import com.practice.invoke.entity.ApiConfig;
import com.practice.invoke.enums.InvokeType;
import com.practice.invoke.enums.ParamType;
import com.practice.invoke.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 统一调用入口（泛化调用）。
 *
 * <p>调用方只需提供「接口名 + 入参」，本方法负责：
 * <ol>
 *   <li>按接口名查 DB 配置（带本地缓存）</li>
 *   <li>拼接目标 URL（服务名走 Nacos 负载均衡 / base_url 硬直连）</li>
 *   <li>按入参形态（BODY/QUERY/PATH）组装请求</li>
 *   <li>发起 HTTP 调用并统一异常处理</li>
 * </ol>
 * 全程不经过 Gateway —— 服务间内部直连。
 */
@Service
public class ApiInvokeService {

    private static final Logger log = LoggerFactory.getLogger(ApiInvokeService.class);

    private final ApiConfigService apiConfigService;
    private final RestTemplate loadBalancedRestTemplate;
    private final RestTemplate plainRestTemplate;

    public ApiInvokeService(ApiConfigService apiConfigService,
                            @Qualifier("loadBalancedRestTemplate") RestTemplate loadBalancedRestTemplate,
                            @Qualifier("plainRestTemplate") RestTemplate plainRestTemplate) {
        this.apiConfigService = apiConfigService;
        this.loadBalancedRestTemplate = loadBalancedRestTemplate;
        this.plainRestTemplate = plainRestTemplate;
    }

    /**
     * 统一调用入口。
     *
     * @param apiName  接口名
     * @param params   入参
     * @param respType 期望返回类型
     */
    public <T> T invoke(String apiName, Map<String, Object> params, Class<T> respType) {
        ApiConfig cfg = apiConfigService.getByApiName(apiName);

        InvokeType invokeType = parseInvokeType(cfg.getInvokeType());
        if (invokeType == InvokeType.PUBSUB) {
            // 预留：走原有订阅-发布链路。这里抛出提示，接入方按需实现。
            throw new BizException("接口 [" + apiName + "] 配置为 PUBSUB，请接入订阅-发布链路实现");
        }

        boolean direct = StringUtils.hasText(cfg.getBaseUrl());
        String host = direct ? cfg.getBaseUrl() : "http://" + cfg.getServiceName();
        if (!direct && !StringUtils.hasText(cfg.getServiceName())) {
            throw new BizException("接口 [" + apiName + "] 既未配置 baseUrl 也未配置 serviceName");
        }

        ParamType paramType = parseParamType(cfg.getParamType());
        String path = cfg.getPath() == null ? "" : cfg.getPath();

        // PATH 形态：用入参替换 path 中的 {key}
        if (paramType == ParamType.PATH && params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                path = path.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(host + path);
        // QUERY 形态：入参拼到 query string
        if (paramType == ParamType.QUERY && params != null) {
            params.forEach((k, v) -> uriBuilder.queryParam(k, v));
        }
        String url = uriBuilder.build().toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(parseContentType(cfg.getContentType()));
        // BODY 形态：入参整体作为请求体；其余形态不带 body
        Object body = paramType == ParamType.BODY ? params : null;
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);

        HttpMethod method = parseHttpMethod(cfg.getHttpMethod());

        // 直连路径按 api 配置的超时新建模板；服务名路径用共享的 @LoadBalanced 模板
        RestTemplate restTemplate = direct
                ? new RestTemplate(HttpClientConfig.buildFactory(cfg.getConnectTimeout(), cfg.getReadTimeout()))
                : loadBalancedRestTemplate;

        // 打印 exchange 的实际入参，便于排查「实际拼出来的请求长什么样」
        log.debug("[invoke] apiName={}, mode={}, exchange(url={}, method={}, headers={}, body={}, respType={})",
                apiName, direct ? "DIRECT(base_url)" : "NACOS(service_name)",
                url, method, headers, body, respType.getSimpleName());

        try {
            ResponseEntity<T> resp = restTemplate.exchange(url, method, entity, respType);
            log.debug("[invoke] apiName={}, status={}, respBody={}", apiName, resp.getStatusCode(), resp.getBody());
            return resp.getBody();
        } catch (RestClientException e) {
            throw new BizException("调用接口失败: apiName=" + apiName + ", url=" + url, e);
        }
    }

    private InvokeType parseInvokeType(String v) {
        if (!StringUtils.hasText(v)) {
            return InvokeType.DIRECT;
        }
        try {
            return InvokeType.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("非法的 invokeType: " + v);
        }
    }

    private ParamType parseParamType(String v) {
        if (!StringUtils.hasText(v)) {
            return ParamType.BODY;
        }
        try {
            return ParamType.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("非法的 paramType: " + v);
        }
    }

    private HttpMethod parseHttpMethod(String v) {
        if (!StringUtils.hasText(v)) {
            return HttpMethod.POST;
        }
        HttpMethod method = HttpMethod.resolve(v.trim().toUpperCase());
        if (method == null) {
            throw new BizException("非法的 httpMethod: " + v);
        }
        return method;
    }

    private MediaType parseContentType(String v) {
        if (!StringUtils.hasText(v)) {
            return MediaType.APPLICATION_JSON;
        }
        return MediaType.parseMediaType(v);
    }
}
