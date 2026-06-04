# generic-invoke —— 统一泛化调用骨架

场景：接口路径/目标地址都配置在数据库表里，平时生产调用走「订阅-发布」链路。本模块提供一个**统一调用入口**：调用方只传 **「接口名 + 入参」**，由统一方法查表、拼地址、发请求、拿结果。全程**不经过 Gateway**（服务间内部直连）。

技术栈：Spring Boot 2.6.13 + Spring Cloud 2021.0.5 + Spring Cloud Alibaba 2021.0.5.0（Nacos）+ MyBatis-Plus + H2（demo），JDK8。

## 核心思路

```
调用方  POST /api/invoke  { apiName, params }
   │
   ▼
ApiInvokeService.invoke(apiName, params, respType)
   1. ApiConfigService 按 apiName 查 DB 配置（本地缓存）
   2. 拼 URL：base_url 硬直连 / service_name 走 Nacos 负载均衡
   3. 按 param_type 组装请求：BODY / QUERY / PATH
   4. RestTemplate 发起调用 + 统一异常处理
   │
   ▼
目标服务（另一个系统，Nacos 直连，不走 Gateway）
```

## 关键类

| 类 | 职责 |
|----|------|
| `entity/ApiConfig` | 接口注册表实体（对应 `api_config`） |
| `service/ApiConfigService` | 查配置 + 本地缓存（`refresh()` 清缓存） |
| `service/ApiInvokeService` | **统一调用入口**（核心） |
| `config/HttpClientConfig` | `@LoadBalanced` 与普通两个 `RestTemplate` |
| `controller/InvokeController` | 对外 `/api/invoke` 入口 |
| `controller/DemoProviderController` | 演示用「被调用方」，自测整条链路 |

## 配置表 `api_config`

| 字段 | 说明 |
|------|------|
| `api_name` | 接口名（唯一），调用方传这个 |
| `service_name` | Nacos 服务名（走服务发现 + 负载均衡） |
| `base_url` | 硬直连地址（非空时优先于 `service_name`，不走服务发现） |
| `path` | 接口路径；`PATH` 形态可含 `{key}` 占位 |
| `http_method` | GET/POST/PUT/DELETE |
| `param_type` | BODY / QUERY / PATH |
| `invoke_type` | DIRECT（直连）/ PUBSUB（走原订阅-发布，预留扩展点） |
| `connect_timeout` / `read_timeout` | 超时（ms，直连路径按配置生效） |
| `enabled` | 1 启用 / 0 停用 |

## 本地运行（无需 Nacos / MySQL）

```bash
cd generic-invoke
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 mvn spring-boot:run
```

demo 用 H2 内存库 + 内置 `DemoProviderController`，启动数据见 `data.sql`。

### 自测（三种入参形态）

```bash
# BODY：POST /provider/order
curl -s -X POST http://localhost:8080/api/invoke \
  -H 'Content-Type: application/json' \
  -d '{"apiName":"createOrder","params":{"sku":"A100","qty":2}}'

# QUERY：GET /provider/user?id=7
curl -s -X POST http://localhost:8080/api/invoke \
  -H 'Content-Type: application/json' \
  -d '{"apiName":"getUser","params":{"id":"7"}}'

# PATH：GET /provider/product/{code}
curl -s -X POST http://localhost:8080/api/invoke \
  -H 'Content-Type: application/json' \
  -d '{"apiName":"getProduct","params":{"code":"P-9"}}'
```

## 接入生产

1. 数据源换成 MySQL（pom 加 `mysql-connector-java`，改 `application.yml` 连接串）。
2. `application.yml` 里 `spring.cloud.nacos.discovery.enabled: true`，配置 `server-addr / namespace / group`（**两个系统必须一致**）。
3. `api_config` 里 `base_url` 留空、填 `service_name`（对方 `spring.application.name`），即走服务名 + 负载均衡直连。
4. `PUBSUB` 类型的接口在 `ApiInvokeService` 的预留分支接入你的订阅-发布实现。
