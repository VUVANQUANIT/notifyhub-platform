# ADR-0001: Dùng Kafka cho domain events và RabbitMQ cho task delivery

- Trạng thái: Accepted
- Ngày: 2026-08-23

## Context

NotifyHub vừa cần phát event cho nhiều subscriber vừa cần giao task gửi email/SMS cho worker. Hai loại traffic có semantics khác nhau.

## Decision

- Kafka-compatible broker dùng cho domain event có thể replay:
  `CampaignCreated`, `NotificationSent`, `NotificationFailed`.
- RabbitMQ dùng cho command/task cần worker xử lý:
  `SendEmailTask`, `SendSmsTask`, `RetryNotificationTask`.
- Consumer phải idempotent vì delivery mặc định được thiết kế theo hướng at-least-once.
- Event có envelope chứa `eventId`, `eventType`, `eventVersion`, `tenantId`, `occurredAt` và `correlationId`.

## Consequences

### Positive

- Học được hai messaging model khác nhau trong cùng một business flow.
- Reporting và audit có thể replay Kafka event.
- Notification worker có acknowledgement, prefetch, retry và DLQ rõ ràng.

### Negative

- Local environment có thêm chi phí RAM và vận hành.
- Phải quản lý hai bộ config, monitoring và failure mode.
- Không được publish cùng một business action vào hai broker tùy tiện; boundary phải được ghi rõ trong code và README.

## Rejected alternatives

- Chỉ dùng RabbitMQ: đơn giản hơn nhưng không thể hiện tốt replay, partition và consumer group.
- Chỉ dùng Kafka: làm task queue được, nhưng không tận dụng rõ semantics acknowledgement/prefetch/DLQ của RabbitMQ.
