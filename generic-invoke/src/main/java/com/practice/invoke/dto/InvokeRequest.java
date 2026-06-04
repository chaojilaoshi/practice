package com.practice.invoke.dto;

import java.util.Map;

/**
 * 统一调用入参：调用方 HTTP 调用 /api/invoke 时的请求体。
 */
public class InvokeRequest {

    /** 接口名，对应 api_config.api_name */
    private String apiName;

    /** 入参（任意 JSON 结构）。BODY 形态整体作为请求体，QUERY/PATH 形态按 key 取值 */
    private Map<String, Object> params;

    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
