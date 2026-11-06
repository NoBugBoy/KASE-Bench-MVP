# 数据库设计（逻辑模型）

本 MVP 使用内存 Repository，但数据模型必须能映射到以下关系表。

## t_order

| 字段 | 类型 | 约束 |
|---|---|---|
| order_id | varchar(64) | PK |
| merchant_id | varchar(32) | NOT NULL |
| payment_amount_cents | bigint | NOT NULL |
| payment_channel | varchar(16) | NOT NULL |
| payment_transaction_id | varchar(64) | NOT NULL |
| status | varchar(32) | NOT NULL |
| created_at | timestamp | NOT NULL |

## t_refund

| 字段 | 类型 | 约束 |
|---|---|---|
| refund_id | varchar(64) | PK |
| order_id | varchar(64) | NOT NULL, index |
| merchant_id | varchar(32) | NOT NULL |
| idempotency_key | varchar(64) | NOT NULL |
| request_fingerprint | varchar(128) | NOT NULL |
| amount_cents | bigint | NOT NULL |
| reason | varchar(32) | NOT NULL |
| reason_description | varchar(200) | NULL |
| payment_channel | varchar(16) | NOT NULL |
| payment_transaction_id | varchar(64) | NOT NULL |
| status | varchar(16) | NOT NULL |
| failure_code | varchar(64) | NULL |
| created_at | timestamp | NOT NULL |
| updated_at | timestamp | NOT NULL |

唯一约束：`(merchant_id, order_id, idempotency_key)`。

成功累计查询：按 `order_id` 聚合 `status = 'SUCCESS'` 的 `amount_cents`。

注意：不能将 PROCESSING 或 FAILED 计入已退款金额。
