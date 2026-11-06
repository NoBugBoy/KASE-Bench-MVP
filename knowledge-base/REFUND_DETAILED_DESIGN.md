# 详细设计：订单部分退款

版本：1.4

## 1. 模块边界

建议包结构：

```text
com.acme.commerce
├── api
│   ├── OrderController
│   └── dto
├── application
│   └── RefundApplicationService
├── domain
│   ├── Order
│   ├── Refund
│   ├── OrderStatus
│   ├── RefundStatus
│   └── RefundPolicy
├── gateway
│   ├── PaymentGateway
│   └── InMemoryPaymentGateway
└── repository
    ├── OrderRepository
    └── RefundRepository
```

允许在现有结构上做最小调整，但 Controller 不应承载业务编排，领域层不得依赖 Spring MVC。

## 2. 核心对象

### Order
- `orderId`
- `merchantId`
- `paymentAmountCents: long`
- `paymentChannel`
- `paymentTransactionId`
- `status`
- `createdAt`

成功退款累计从 RefundRepository 汇总，不要在订单对象中维护一个可能与退款记录失真的独立副本。

### Refund
- `refundId`
- `orderId`
- `merchantId`
- `idempotencyKey`
- `requestFingerprint`
- `amountCents`
- `reason`
- `reasonDescription`
- `paymentChannel`
- `paymentTransactionId`
- `status`
- `failureCode`
- `createdAt`
- `updatedAt`

## 3. 应用服务主流程

1. 校验请求基础字段。
2. 按 merchantId + orderId + idempotencyKey 查询幂等记录。
3. 若记录存在：
   - fingerprint 相同：返回原退款结果。
   - fingerprint 不同：抛出 `IDEMPOTENCY_CONFLICT`。
4. 获取订单级互斥锁；锁粒度必须是 orderId，不得使用全局锁。
5. 在锁内再次检查幂等记录，避免并发重复请求穿透。
6. 查询订单并校验 merchantId。
7. 校验订单状态。
8. 汇总该订单状态为 SUCCESS 的退款金额。
9. 计算 refundable = paymentAmount - successfulRefundTotal。
10. 校验本次金额不超过 refundable。
11. 创建 PROCESSING 退款记录并登记幂等键。
12. 调用 PaymentGateway，参数必须来自订单原支付信息。
13. 成功：将退款单置为 SUCCESS，更新订单状态。
14. 失败：将退款单置为 FAILED，保存失败码；不得计入成功退款累计。
15. 在 finally 中释放订单锁。

注意：MVP 使用 JVM 内订单级锁。生产环境应改为数据库条件更新、唯一约束、事务和/或分布式锁，并处理渠道调用与本地状态之间的一致性。本 Benchmark 不要求实现生产级分布式事务。

## 4. 并发与幂等的关键点

- 锁按 orderId 分桶或使用 `ConcurrentHashMap<String, Lock>`。
- 不同订单的请求应可并行执行。
- 锁内必须再次查询幂等键。
- 同一个 idempotencyKey 的请求指纹至少包含 merchantId、orderId、amountCents、reason、reasonDescription。
- 不要将 `operator` 纳入新接口幂等指纹。
- 幂等记录必须在调用渠道前建立，避免渠道已执行但本地没有幂等痕迹。
- 对于本 MVP 的同步网关，渠道调用返回后更新退款状态；若抛出异常，应按失败处理并确保锁释放。

## 5. 订单状态推导

令 `S = 所有 SUCCESS 退款单 amountCents 之和`，`P = paymentAmountCents`：

- S = 0：PAID
- 0 < S < P：PARTIALLY_REFUNDED
- S = P：REFUNDED

任何情况下 S 不得大于 P。

## 6. 支付网关契约

```java
RefundGatewayResult refund(
    String paymentChannel,
    String paymentTransactionId,
    String refundId,
    long amountCents
);
```

网关不得接收调用方传入的 paymentChannel。渠道模拟器支持：
- `ALIPAY`：正常成功
- `WECHAT`：正常成功
- `CARD`：正常成功
- 订单号以 `FAIL-` 开头时模拟渠道失败，返回 `CHANNEL_DECLINED`

## 7. 测试设计建议

- 金额边界：0、负数、恰好可退、超 1 分。
- 多次退款：部分 + 部分 + 最后全额。
- 幂等：相同请求、不同金额、并发相同键。
- 并发：两个不同幂等键争抢同一订单剩余额度。
- 锁释放：渠道失败、网关异常后再次请求。
- 兼容：旧接口请求和响应字段。
- 隔离：不同订单可以并发退款。
