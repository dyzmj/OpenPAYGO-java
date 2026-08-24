<p align="center">
    <em>OpenPAYGO is an Open Source ecosystem to enable pay-as-you-go functionality in new devices and products.</em>
</p>
<p align="center">
  <img
    alt="Project Status"
    src="https://img.shields.io/badge/Project%20Status-beta-orange"
  >
  <img
    alt="GitHub Workflow Status"
    src="https://img.shields.io/github/actions/workflow/status/EnAccess/OpenPAYGO-js/.github/workflows/check.yaml"
  >
  <a href="https://github.com/dyzmj/OpenPAYGO-java/blob/main/LICENSE" target="_blank">
    <img
      alt="License"
      src="https://img.shields.io/github/license/dyzmj/openpaygo-java"
    >
  </a>
</p>

# OpenPAYGO Java Library

[English](README.md) | 简体中文

OpenPAYGO Token v2.3 和 OpenPAYGO Metrics v1.0-rc1 的 Java 8 类库实现。

- Author: dyzmj
- Project date: 2026-08-24

## 环境与坐标

- Java 8 或更高版本
- Maven 3.6.3 或更高版本
- 唯一运行时依赖：Fastjson2

```xml
<dependency>
  <groupId>com.goldcard.paygo</groupId>
  <artifactId>openpaygo-java</artifactId>
  <version>1.0.1</version>
</dependency>
```

本地构建：

```sh
mvn clean test
mvn package
```

## 生成 Token

```java
import com.goldcard.paygo.OpenPaygo;
import com.goldcard.paygo.token.TokenGenerationRequest;
import com.goldcard.paygo.token.TokenGenerationResult;
import com.goldcard.paygo.token.TokenType;
import java.math.BigDecimal;

TokenGenerationRequest request = TokenGenerationRequest.builder()
    .secretKey("dac86b1a29ab82edc5fbbc41ec9530f6")
    .currentTokenCount(1)
    .tokenType(TokenType.ADD_TIME)
    .activationValue(BigDecimal.valueOf(7))
    .build();

TokenGenerationResult result = OpenPaygo.generateToken(request);
String token = result.getToken();
long updatedTokenCount = result.getUpdatedTokenCount();
```

`startingCode` 未指定时由密钥生成。Token 始终是固定长度字符串，调用方不得转换为数值，否则会丢失前导零。

## 解码 Token

```java
import com.goldcard.paygo.token.TokenDecodeRequest;
import com.goldcard.paygo.token.TokenDecodeResult;
import com.goldcard.paygo.token.TokenState;
import com.goldcard.paygo.token.TokenStatus;

TokenState state = TokenState.unordered(1, null);
TokenDecodeResult decoded = OpenPaygo.decodeToken(TokenDecodeRequest.builder()
    .token(token)
    .secretKey("dac86b1a29ab82edc5fbbc41ec9530f6")
    .tokenState(state)
    .build());

if (decoded.getStatus() == TokenStatus.VALID) {
    state = decoded.getUpdatedState().get();
}
```

`INVALID` 和 `ALREADY_USED` 是正常业务结果，不抛异常。格式错误、密钥错误和越界参数抛出 `IllegalArgumentException`。

乱序支持必须显式选择：

- `TokenState.strict(count)`：只接受更新 Token，不保存旧 Token 使用记录。
- `TokenState.unordered(count, usedCounts)`：允许策略窗口内尚未使用的旧 Add Time Token。

可通过 `TokenValidationPolicy` 调整普通跳转、乱序及 Counter Sync 窗口。计数零重置默认关闭，只有维护流程应显式启用。

## 构建 Metrics Request

Metrics 动态值使用 `LinkedHashMap<String, Object>` 和 `List<Object>`。字段插入顺序会影响协议签名，不要使用无序 Map。

```java
import com.goldcard.paygo.metrics.AuthMethod;
import com.goldcard.paygo.metrics.MetricsDataFormat;
import com.goldcard.paygo.metrics.MetricsRequestBuilder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

Map<String, Object> formatMap = new LinkedHashMap<String, Object>();
formatMap.put("id", 42);
formatMap.put("data_order", Arrays.asList("token_count", "firmware_version"));
formatMap.put("historical_data_order", Arrays.asList("battery_voltage"));
formatMap.put("historical_data_interval", 60);
MetricsDataFormat format = MetricsDataFormat.of(formatMap);

Map<String, Object> data = new LinkedHashMap<String, Object>();
data.put("token_count", 3);
data.put("firmware_version", "1.2.3");

String payload = new MetricsRequestBuilder("DEVICE-001")
    .dataFormat(format)
    .timestamp(1700000000L)
    .requestCount(7)
    .data(data)
    .historicalData(java.util.Collections.<Map<String, Object>>emptyList())
    .secretKey("dac86b1a29ab82edc5fbbc41ec9530f6")
    .authMethod(AuthMethod.RECURSIVE_DATA_AUTH)
    .buildCondensedPayload();
```

接受的 JSON 值包括字符串、布尔值、有限数字、`null`、Map 和 List。任意 POJO、非字符串 Map 键、`NaN` 和 Infinity 会被拒绝。

## 验证 Metrics 并生成响应

```java
import com.goldcard.paygo.metrics.MetricsDeviceParameters;
import com.goldcard.paygo.metrics.MetricsResponseHandler;
import java.time.Instant;

MetricsResponseHandler handler = new MetricsResponseHandler(payload);
handler.setDeviceParameters(MetricsDeviceParameters.builder()
    .secretKey("dac86b1a29ab82edc5fbbc41ec9530f6")
    .dataFormat(format)
    .lastRequestCount(6L)
    .lastRequestTimestamp(1699999999L)
    .build());

if (!handler.isAuthValid()) {
    throw new IllegalStateException(handler.validateAuth().getReason().name());
}

Map<String, Object> simpleMetrics = handler.getSimpleMetrics();
if (handler.expectsTokenAnswer()) {
    handler.addTokensToAnswer(Arrays.asList("001234567"));
}
if (handler.expectsTimeAnswer()) {
    handler.addTimeToAnswer(Instant.ofEpochSecond(1700003600L));
}
String responsePayload = handler.buildAnswerPayload();
```

核心库不发送 HTTP 请求、不访问数据库，也不自动持久化更新后的 Token/Request 状态，这些由调用方负责。

## 线程安全

- Token 请求、结果、状态、策略以及 Metrics Data Format/认证策略是不可变对象。
- `MetricsRequestBuilder` 和 `MetricsResponseHandler` 是单次流程对象，不保证线程安全，不应跨请求共享。

## 与 Python 实现的有意差异

1. 扩展 Token 只允许 Add Time 和 Set Time。扩展 Disable PAYG/Counter Sync 与合法值 998/999 无法区分，因此直接拒绝。
2. 空的乱序计数集合表示“已启用但尚无记录”，不会像 Python 实现一样退化为 `None`。
3. Counter Sync 窗口显式配置；高风险的 count 0 永久重置默认关闭。
4. Metrics Simple Auth 默认拒绝，必须通过 `MetricsAuthPolicy.allowSimpleAuth(true)` 显式允许。
5. Metrics 重放校验只使用当前认证方式实际签名的字段。
6. 同时请求绝对到期时间和剩余秒数时，响应同时返回两个字段。
7. 剩余秒数按 `expiration - now` 计算，修复 Python 对未来时间返回零的问题。
8. Token 长度、字符集和 Metrics JSON 类型采用严格校验，不接受隐式对象序列化。

上述差异与架构决定记录在项目文档中。
