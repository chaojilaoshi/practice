-- 接口注册表：统一调用方法据此定位目标接口
DROP TABLE IF EXISTS api_config;
CREATE TABLE api_config (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    api_name        VARCHAR(100) NOT NULL COMMENT '接口名(唯一,调用方传这个)',
    service_name    VARCHAR(100)          COMMENT 'Nacos服务名,如 service-b(走服务发现+负载均衡)',
    base_url        VARCHAR(255)          COMMENT '硬直连地址,如 http://192.168.1.20:8082(非空时优先,不走服务发现)',
    path            VARCHAR(255) NOT NULL COMMENT '接口路径,如 /api/order;PATH形态可含{key}占位',
    http_method     VARCHAR(10)  NOT NULL DEFAULT 'POST' COMMENT 'GET/POST/PUT/DELETE',
    param_type      VARCHAR(10)  NOT NULL DEFAULT 'BODY' COMMENT 'BODY/QUERY/PATH',
    invoke_type     VARCHAR(10)  NOT NULL DEFAULT 'DIRECT' COMMENT 'DIRECT/PUBSUB',
    content_type    VARCHAR(50)           DEFAULT 'application/json',
    connect_timeout INT                   DEFAULT 2000 COMMENT '连接超时(ms)',
    read_timeout    INT                   DEFAULT 5000 COMMENT '读取超时(ms)',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    CONSTRAINT uk_api_name UNIQUE (api_name)
);
