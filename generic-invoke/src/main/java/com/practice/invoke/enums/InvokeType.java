package com.practice.invoke.enums;

/**
 * 调用类型：决定统一调用方法走哪条链路。
 */
public enum InvokeType {
    /** 同步直连 HTTP（不走 Gateway）。生产用服务名 + Nacos 负载均衡，联调可写死地址。 */
    DIRECT,
    /** 走原有的订阅-发布流程（异步/ESB）。此处仅预留扩展点。 */
    PUBSUB
}
