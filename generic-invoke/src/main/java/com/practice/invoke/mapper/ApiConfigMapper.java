package com.practice.invoke.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.practice.invoke.entity.ApiConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口配置 Mapper。继承 MyBatis-Plus BaseMapper，自带 CRUD。
 */
@Mapper
public interface ApiConfigMapper extends BaseMapper<ApiConfig> {
}
