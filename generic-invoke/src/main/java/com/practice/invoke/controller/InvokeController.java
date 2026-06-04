package com.practice.invoke.controller;

import com.practice.invoke.dto.InvokeRequest;
import com.practice.invoke.dto.Result;
import com.practice.invoke.service.ApiConfigService;
import com.practice.invoke.service.ApiInvokeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一调用入口 Controller：调用方只需 POST 接口名 + 入参。
 */
@RestController
@RequestMapping("/api/invoke")
public class InvokeController {

    private final ApiInvokeService apiInvokeService;
    private final ApiConfigService apiConfigService;

    public InvokeController(ApiInvokeService apiInvokeService, ApiConfigService apiConfigService) {
        this.apiInvokeService = apiInvokeService;
        this.apiConfigService = apiConfigService;
    }

    /**
     * 统一调用：根据 apiName 查表并转发到目标接口，返回目标接口结果。
     */
    @PostMapping
    public Result<Object> invoke(@RequestBody InvokeRequest request) {
        Object data = apiInvokeService.invoke(request.getApiName(), request.getParams(), Object.class);
        return Result.ok(data);
    }

    /**
     * 刷新接口配置缓存（配置表变更后调用）。
     */
    @PostMapping("/refresh")
    public Result<String> refresh() {
        apiConfigService.refresh();
        return Result.ok("refreshed");
    }
}
