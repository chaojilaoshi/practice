package com.practice.invoke.controller;

import com.practice.invoke.dto.Result;
import com.practice.invoke.exception.BizException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：把业务异常转成统一返回结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Object> handleBiz(BizException e) {
        return Result.fail(e.getMessage());
    }
}
