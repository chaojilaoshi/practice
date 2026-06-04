-- 演示数据：base_url 指向本机的 DemoProviderController，便于本地自测整条链路。
-- 生产环境应把 base_url 留空、改用 service_name（Nacos 服务名）。

-- BODY 形态：POST /provider/order，入参作为 JSON body
INSERT INTO api_config (api_name, service_name, base_url, path, http_method, param_type, invoke_type, content_type, connect_timeout, read_timeout, enabled)
VALUES ('createOrder', NULL, 'http://localhost:8080', '/provider/order', 'POST', 'BODY', 'DIRECT', 'application/json', 2000, 5000, 1);

-- QUERY 形态：GET /provider/user?id=xxx
INSERT INTO api_config (api_name, service_name, base_url, path, http_method, param_type, invoke_type, content_type, connect_timeout, read_timeout, enabled)
VALUES ('getUser', NULL, 'http://localhost:8080', '/provider/user', 'GET', 'QUERY', 'DIRECT', 'application/json', 2000, 5000, 1);

-- PATH 形态：GET /provider/product/{code}
INSERT INTO api_config (api_name, service_name, base_url, path, http_method, param_type, invoke_type, content_type, connect_timeout, read_timeout, enabled)
VALUES ('getProduct', NULL, 'http://localhost:8080', '/provider/product/{code}', 'GET', 'PATH', 'DIRECT', 'application/json', 2000, 5000, 1);

-- 生产示例（走 Nacos 服务名，需开启 nacos.discovery）：
-- INSERT INTO api_config (api_name, service_name, base_url, path, http_method, param_type, invoke_type, enabled)
-- VALUES ('createOrderProd', 'service-b', NULL, '/api/order', 'POST', 'BODY', 'DIRECT', 1);
