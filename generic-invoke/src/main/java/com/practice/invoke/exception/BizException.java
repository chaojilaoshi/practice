package com.practice.invoke.exception;

/**
 * 业务异常：统一调用过程中的可预期错误（接口未配置、调用失败等）。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
    }
}
