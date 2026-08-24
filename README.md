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

English | [简体中文](README.zh-CN.md)

Java 8 library implementation of OpenPAYGO Token v2.3 and OpenPAYGO Metrics v1.0-rc1.

- Author: dyzmj
- Project date: 2026-08-24

## Requirements and Coordinates

- Java 8 or later
- Maven 3.6.3 or later
- Sole runtime dependency: Fastjson2

```xml
<dependency>
  <groupId>com.goldcard.paygo</groupId>
  <artifactId>openpaygo-java</artifactId>
  <version>1.0.1</version>
</dependency>
```

Build locally:

```sh
mvn clean test
mvn package
```

## Generate a Token

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

When `startingCode` is omitted, it is generated from the secret key. A token is always a
fixed-width string. Do not convert it to a numeric type, because doing so would discard leading
zeroes.

## Decode a Token

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

`INVALID` and `ALREADY_USED` are normal protocol results and do not throw exceptions. Invalid
formats, invalid keys, and out-of-range parameters throw `IllegalArgumentException`.

Out-of-order token support must be selected explicitly:

- `TokenState.strict(count)` accepts only newer tokens and does not retain usage records for older
  tokens.
- `TokenState.unordered(count, usedCounts)` accepts unused, older Add Time tokens within the policy
  window.

Use `TokenValidationPolicy` to configure normal token jumps, out-of-order tokens, and the Counter
Sync window. Resetting the counter to zero is disabled by default and should only be enabled
explicitly for maintenance workflows.

## Build a Metrics Request

Metrics dynamic values use `LinkedHashMap<String, Object>` and `List<Object>`. Field insertion
order affects protocol signatures, so do not use unordered maps.

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

Accepted JSON values are strings, booleans, finite numbers, `null`, maps, and lists. Arbitrary
POJOs, maps with non-string keys, `NaN`, and infinity are rejected.

## Validate Metrics and Build a Response

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

The core library does not send HTTP requests, access databases, or automatically persist updated
Token or Request state. These responsibilities belong to the caller.

## Thread Safety

- Token requests, results, states, policies, Metrics Data Formats, and authentication policies are
  immutable.
- `MetricsRequestBuilder` and `MetricsResponseHandler` represent a single workflow. They are not
  thread-safe and must not be shared across requests.

## Intentional Differences from the Python Implementation

1. Extended tokens support only Add Time and Set Time. Extended Disable PAYG and Counter Sync are
   rejected because they cannot be distinguished from the valid values 998 and 999.
2. An empty out-of-order count collection means "enabled with no usage records" and does not
   degrade to `None` as it does in the Python implementation.
3. The Counter Sync window is configured explicitly. The high-risk permanent reset at count zero
   is disabled by default.
4. Metrics Simple Auth is rejected by default and must be enabled explicitly with
   `MetricsAuthPolicy.allowSimpleAuth(true)`.
5. Metrics replay validation uses only fields actually signed by the selected authentication
   method.
6. When both an absolute expiration timestamp and remaining seconds are requested, both fields are
   included in the response.
7. Remaining seconds are calculated as `expiration - now`, correcting the Python behavior that
   returns zero for future timestamps.
8. Token length, character set, and Metrics JSON types are validated strictly. Implicit object
   serialization is not supported.

These differences and related architectural decisions are recorded in the project documentation.
