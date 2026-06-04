package com.practice.invoke.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 接口注册表：每个接口的目标地址 / 路径 / 方法 / 入参形态都配置在这里，
 * 调用方只需提供 apiName 即可定位到一条配置。
 */
@TableName("api_config")
public class ApiConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口名（唯一），调用方传这个 */
    private String apiName;

    /** Nacos 服务名，如 service-b（走服务发现 + 负载均衡） */
    private String serviceName;

    /** 硬直连地址，如 http://192.168.1.20:8082（非空时优先于 serviceName，不走服务发现） */
    private String baseUrl;

    /** 接口路径，如 /api/order；PATH 形态可含 {key} 占位 */
    private String path;

    /** HTTP 方法：GET/POST/PUT/DELETE */
    private String httpMethod;

    /** 入参形态：BODY/QUERY/PATH */
    private String paramType;

    /** 调用类型：DIRECT/PUBSUB */
    private String invokeType;

    private String contentType;

    private Integer connectTimeout;

    private Integer readTimeout;

    /** 是否启用：1 启用，0 停用 */
    private Integer enabled;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getParamType() {
        return paramType;
    }

    public void setParamType(String paramType) {
        this.paramType = paramType;
    }

    public String getInvokeType() {
        return invokeType;
    }

    public void setInvokeType(String invokeType) {
        this.invokeType = invokeType;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Integer getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Integer connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Integer getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Integer readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }
}
