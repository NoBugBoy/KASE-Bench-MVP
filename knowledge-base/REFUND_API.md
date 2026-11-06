# API 规格：订单退款

Base path：`/api/v1`  
Content-Type：`application/json`

## 1. 新增部分退款接口

`POST /api/v1/orders/{orderId}/refunds`

### Request

```json
{
  "merchantId": "M1001",
  "amountCents": 3000,
  "reason": "CUSTOMER_REQUEST",
  "reasonDescription": "客户退回其中一件商品",
  "idempotencyKey": "refund-2026-000001"
}
```

字段说明：

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| merchantId | string | 是 | 1–32 |
| amountCents | integer | 是 | > 0，单位分 |
| reason | string | 是 | 需求定义的枚举 |
| reasonDescription | string | 否 | 最长 200；OTHER 必填 |
| idempotencyKey | string | 是 | 8–64 |

### Success response：HTTP 200

```json
{
  "refundId": "RF2026000001",
  "orderId": "O10001",
  "merchantId": "M1001",
  "amountCents": 3000,
  "status": "SUCCESS",
  "orderStatus": "PARTIALLY_REFUNDED",
  "totalRefundedCents": 3000,
  "refundableCents": 7000,
  "paymentChannel": "ALIPAY",
  "createdAt": "2026-11-04T10:00:00Z"
}
```

### 业务错误

| HTTP | code | 含义 |
|---|---|---|
| 404 | ORDER_NOT_FOUND | 订单不存在 |
| 403 | MERCHANT_MISMATCH | 商户与订单不匹配 |
| 409 | ORDER_NOT_REFUNDABLE | 订单状态不允许退款 |
| 409 | REFUND_AMOUNT_EXCEEDED | 超出可退金额 |
| 409 | REFUND_IN_PROGRESS | 该订单已有退款流程执行中 |
| 409 | IDEMPOTENCY_CONFLICT | 同一幂等键对应不同请求 |
| 422 | INVALID_REFUND_REQUEST | 请求参数或业务字段不合法 |
| 502 | PAYMENT_CHANNEL_FAILED | 支付渠道退款失败 |

错误响应：

```json
{
  "code": "REFUND_AMOUNT_EXCEEDED",
  "message": "Refund amount exceeds refundable amount",
  "requestId": "req-123"
}
```

## 2. 查询订单可退摘要

`GET /api/v1/orders/{orderId}/refundable?merchantId=M1001`

响应：

```json
{
  "orderId": "O10001",
  "paymentAmountCents": 10000,
  "totalRefundedCents": 3000,
  "refundableCents": 7000,
  "orderStatus": "PARTIALLY_REFUNDED",
  "paymentChannel": "ALIPAY"
}
```

## 3. 旧接口（必须兼容）

`POST /api/v1/orders/{orderId}/refund`

旧请求：

```json
{
  "merchantId": "M1001",
  "operator": "support-agent-01"
}
```

旧响应字段必须保留：

```json
{
  "orderId": "O10001",
  "status": "REFUNDED",
  "refundedAmount": 100.00
}
```

说明：
- `refundedAmount` 单位为元，保留两位小数。
- 旧接口退还当前剩余可退金额。
- 若此前部分退款 30.00 元，旧接口本次应退 70.00 元。
- 不允许删除或重命名旧响应字段。
