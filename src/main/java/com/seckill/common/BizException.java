package com.seckill.common;

import lombok.Getter;

/**
 * 业务异常。携带业务码，由全局异常处理器统一转成返回结构。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(500, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
