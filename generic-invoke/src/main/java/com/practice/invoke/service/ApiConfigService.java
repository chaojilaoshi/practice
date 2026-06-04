package com.practice.invoke.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.practice.invoke.entity.ApiConfig;
import com.practice.invoke.exception.BizException;
import com.practice.invoke.mapper.ApiConfigMapper;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 接口配置读取 + 本地缓存。
 * 避免每次调用都查库；配置变更时调用 {@link #refresh()} 清缓存即可。
 */
@Service
public class ApiConfigService {

    private final ApiConfigMapper apiConfigMapper;

    /** apiName -> ApiConfig 本地缓存 */
    private final ConcurrentMap<String, ApiConfig> cache = new ConcurrentHashMap<>();

    public ApiConfigService(ApiConfigMapper apiConfigMapper) {
        this.apiConfigMapper = apiConfigMapper;
    }

    /**
     * 按接口名获取启用的配置，命中缓存优先。
     */
    public ApiConfig getByApiName(String apiName) {
        ApiConfig cached = cache.get(apiName);
        if (cached != null) {
            return cached;
        }
        ApiConfig loaded = apiConfigMapper.selectOne(
                new LambdaQueryWrapper<ApiConfig>()
                        .eq(ApiConfig::getApiName, apiName)
                        .eq(ApiConfig::getEnabled, 1));
        if (loaded == null) {
            throw new BizException("接口未配置或已停用: " + apiName);
        }
        cache.put(apiName, loaded);
        return loaded;
    }

    /**
     * 清空缓存。配置表变更后调用（可由定时任务 / 配置变更事件触发）。
     */
    public void refresh() {
        cache.clear();
    }
}
