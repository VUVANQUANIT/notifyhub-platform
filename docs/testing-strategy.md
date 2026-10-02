# Testing Strategy

## 1. Mục tiêu

NotifyHub áp dụng Test-Driven Development (TDD) cho các business rule quan trọng, đặc biệt là Campaign domain. Test được dùng để mô tả hành vi mong muốn trước khi lựa chọn chi tiết cài đặt.

Chu trình phát triển:

1. **Red**: viết một test nhỏ cho một hành vi và xác nhận test thất bại vì hành vi chưa được cài đặt.
2. **Green**: viết lượng production code tối thiểu để test chạy thành công.
3. **Refactor**: cải thiện tên, cấu trúc và thiết kế trong khi giữ toàn bộ test thành công.

Không cần áp dụng TDD máy móc cho mọi dòng code. Domain rule nên được phát triển test-first; cấu hình framework và hạ tầng được bảo vệ bằng smoke test hoặc integration test phù hợp.

## 2. Tình trạng hiện tại

Project **đủ nền tảng cấu trúc để bắt đầu viết test**, vì:

- Backend sử dụng Maven multi-module và Java 21.
- `campaign-service` là một module độc lập, phù hợp để chứa domain code và unit test.
- Campaign flow, state machine và các invariant đã được mô tả trong `docs/domain/campaign-domain.md`.
- Maven Surefire được quản lý thông qua Spring Boot parent và sẽ tự chạy các test đúng quy ước tên.

Các phần nền tảng đã được triển khai:

- Campaign Service có `spring-boot-starter-test`, unit test domain và CSV parser.
- `CampaignApiIT` chạy HTTP thật với PostgreSQL Testcontainers: migration, mapping, tenant isolation, idempotency, concurrency, constraints, scheduled activation và atomic outbox rollback.
- `CampaignSecurityIT` kiểm tra JWT, scope, chữ ký sai, token hết hạn và thiếu tenant.
- Surefire chạy `*Test`; Failsafe chạy `*IT` trong `verify`. Docker phải hoạt động; integration test không tự skip khi thiếu Docker.

## 3. Thiết lập kiểm thử

Trong `backend/campaign-service/pom.xml` đã khai báo test dependency:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

Dependency này cung cấp JUnit Jupiter, AssertJ, Mockito và Spring Test. Unit test thuần domain không cần khởi động Spring context và không nên dùng `@SpringBootTest`.

Vị trí test đầu tiên:

```text
backend/campaign-service/src/test/java/
└── com/vuvanquan/notifyhub/campaign/domain/CampaignTest.java
```

Chạy riêng test của Campaign Service:

```powershell
mvn -f backend/pom.xml -pl campaign-service test
```

Chạy toàn bộ kiểm tra backend:

```powershell
mvn -f backend/pom.xml clean verify
```

## 4. Các invariant đã được bao phủ

Unit test đi từ invariant nhỏ đến state transition:

1. Campaign mới được tạo ở trạng thái `DRAFT`.
2. Tên và nội dung rỗng bị từ chối.
3. Campaign không có recipient không được start.
4. Campaign có recipient import đang `PROCESSING` không được start.
5. Start để gửi ngay chuyển `DRAFT` sang `RUNNING`.
6. Start với thời điểm tương lai chuyển `DRAFT` sang `SCHEDULED`.
7. Campaign đã start không được sửa hoặc start lần hai.
8. Campaign `SCHEDULED` đến hạn chuyển sang `RUNNING`.
9. Campaign chỉ được chuyển sang `COMPLETED` hoặc `FAILED` từ trạng thái hợp lệ.

Ví dụ test đầu tiên, dùng như mô tả hành vi chứ chưa phải API bắt buộc của domain model:

```java
class CampaignTest {

    @Test
    void new_campaign_starts_as_draft() {
        Campaign campaign = Campaign.create(/* valid values */);

        assertThat(campaign.status()).isEqualTo(CampaignStatus.DRAFT);
    }
}
```

Sau khi viết test, phải chạy test và nhìn thấy nó thất bại vì production code chưa tồn tại. Tiếp theo mới tạo API domain nhỏ nhất làm test thành công.

## 5. Các tầng kiểm thử

### Unit test

- Phạm vi: aggregate, value object, state machine và domain service.
- Không dùng Spring context, database, Kafka hoặc RabbitMQ.
- Chạy nhanh và là lớp test đầu tiên được triển khai.

### Persistence integration test

- Phạm vi: JPA mapping, Flyway migration, repository, unique constraint và tenant isolation.
- Dùng PostgreSQL Testcontainers thay vì H2 để hành vi gần production.
- Đã triển khai trong `CampaignApiIT`; database test tách biệt với database Compose local.

### Messaging integration test

- Phạm vi: outbox publisher, Kafka event, RabbitMQ task, retry, acknowledgement và idempotency.
- `MessagingPipelineIT` đã kiểm tra PostgreSQL, Kafka và RabbitMQ: routing email/SMS, batching,
  concurrent publishing, duplicate replay, broker outage, unroutable returns, rollback sau ack và DLT.
- `NotificationWorkerIT` kiểm tra PostgreSQL/RabbitMQ/MailHog thật: SMTP, simulated SMS,
  deduplication, bounded retry, DLQ, provider outage, rollback/requeue và confirm recovery.

### End-to-end test

- Phạm vi: một happy path quan trọng từ tạo campaign đến kết quả delivery.
- Số lượng ít, chỉ thêm khi các service đã có business workflow chạy được.

## 6. Definition of Done cho mỗi business rule

Một business rule được xem là hoàn thành khi:

- Có test mô tả happy path và các trường hợp bị từ chối quan trọng.
- Test đã từng thất bại vì đúng lý do trước khi production code được thêm.
- Production code không phụ thuộc framework nếu rule thuộc domain core.
- Toàn bộ test liên quan chạy thành công.
- `mvn -f backend/pom.xml clean verify` thành công và thực sự chạy test.

## 7. Bước tiếp theo

Application service, JPA/Flyway adapter, REST API và pipeline Outbox → Kafka → RabbitMQ đã được triển khai.
Chạy `./mvnw clean verify` (Windows: `.\mvnw.cmd`) từ `backend` để chạy unit và integration test với broker thật.
Notification Worker đã có email MailHog/SMS giả lập, delivery state, duplicate task,
manual acknowledgement, bounded retry và DLQ. Bước tiếp theo là phát delivery-result events,
tổng hợp Campaign completion và Reporting; SMTP acceptance không bảo đảm mailbox delivery.
