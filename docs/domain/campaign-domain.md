# Campaign Domain

## 1. Mục tiêu và phạm vi

Tài liệu này chốt nghiệp vụ cho luồng MVP:

```text
Create Campaign -> Import Recipients -> Start Campaign -> Send Notification
```

Phạm vi gồm campaign gửi qua một kênh `EMAIL` hoặc `SMS`, import người nhận từ file và gửi bất đồng bộ. Các chức năng hủy campaign đang chạy, chạy lại campaign đã kết thúc, phê duyệt nhiều cấp và cá nhân hóa template nâng cao chưa thuộc MVP.

## 2. Tác nhân

- `Campaign Operator`: tạo campaign, import recipients và start campaign trong tenant mà mình có quyền.
- `System Scheduler`: kích hoạt campaign đã lên lịch khi đến thời điểm gửi.
- `Delivery Worker`: nhận task và thực hiện gửi qua provider.

Mọi thao tác và dữ liệu đều phải nằm trong phạm vi một `tenantId`. Người dùng của tenant này không được đọc hoặc thay đổi campaign của tenant khác.

## 3. Luồng nghiệp vụ chính

### 3.1. Create Campaign

**Điều kiện trước**

- Người dùng đã đăng nhập.
- Người dùng có quyền tạo campaign trong tenant hiện tại.

**Dữ liệu đầu vào**

- Tên campaign.
- Kênh gửi: `EMAIL` hoặc `SMS`.
- Nội dung gửi.
- Tiêu đề email nếu kênh là `EMAIL`.
- Thời điểm gửi dự kiến, có thể để trống để gửi ngay khi start.
- `idempotencyKey` của request.

**Quy tắc**

- Tên campaign không được để trống.
- Nội dung gửi không được để trống.
- Email bắt buộc có tiêu đề; SMS không sử dụng tiêu đề.
- Thời điểm gửi dự kiến không được nằm trong quá khứ.
- Cùng `tenantId` và `idempotencyKey` phải trả về cùng một campaign, không tạo bản ghi trùng.

**Kết quả**

- Tạo `Campaign` ở trạng thái `DRAFT`.
- Phát sinh domain event `CampaignCreated`.

### 3.2. Import Recipients

**Điều kiện trước**

- Campaign tồn tại trong tenant hiện tại.
- Campaign đang ở trạng thái `DRAFT`.
- Không có import batch nào của campaign đang ở trạng thái `PROCESSING`.

**Dữ liệu đầu vào**

- File CSV.
- Với `EMAIL`: mỗi dòng phải có trường `email`.
- Với `SMS`: mỗi dòng phải có trường `phoneNumber`.
- Các cột còn lại được giữ làm biến cá nhân hóa cho recipient.

**Quy tắc**

- File được xử lý thành một `RecipientImport` riêng để theo dõi tiến trình.
- Email được chuẩn hóa về chữ thường và bỏ khoảng trắng đầu/cuối.
- Số điện thoại được chuẩn hóa về định dạng E.164.
- Recipient trùng địa chỉ nhận trong cùng campaign chỉ được giữ một bản ghi hợp lệ.
- Dòng không hợp lệ không làm hỏng toàn bộ batch; hệ thống lưu lý do lỗi của từng dòng và tiếp tục xử lý các dòng còn lại.
- Chỉ một import batch được xử lý tại một thời điểm cho mỗi campaign.
- Import lại được phép khi campaign còn `DRAFT`. Recipient mới hợp lệ được hợp nhất vào danh sách hiện có theo địa chỉ nhận đã chuẩn hóa.

**Kết quả**

- Import batch kết thúc với tổng số dòng, số hợp lệ, số trùng và số không hợp lệ.
- Các recipient hợp lệ trở thành `CampaignRecipient` của campaign.
- Phát sinh `RecipientImportCompleted` hoặc `RecipientImportRejected`.

### 3.3. Start Campaign

**Điều kiện trước**

- Campaign đang ở trạng thái `DRAFT`.
- Người dùng có quyền start campaign trong tenant hiện tại.
- Không có recipient import đang `PROCESSING`.
- Campaign có ít nhất một recipient hợp lệ.
- Nội dung campaign vẫn hợp lệ với kênh gửi.

**Quy tắc**

- Sau khi start, nội dung và danh sách recipient không được thay đổi.
- Nếu không có thời điểm gửi hoặc thời điểm gửi đã đến, campaign chuyển sang `RUNNING`.
- Nếu thời điểm gửi nằm trong tương lai, campaign chuyển sang `SCHEDULED`.
- Khi scheduler kích hoạt campaign đến hạn, `SCHEDULED` chuyển sang `RUNNING`.
- Lệnh start lặp lại với cùng `idempotencyKey` không được tạo thêm lần chạy hoặc task gửi.
- Khi chuyển sang `RUNNING`, hệ thống ghi `CampaignStarted` và outbox record trong cùng transaction.

**Kết quả**

- `CampaignStarted` được publish từ outbox lên Kafka.
- Dispatcher tạo đúng một `SendNotificationTask` cho mỗi recipient hợp lệ và gửi task vào RabbitMQ.

### 3.4. Send Notification

**Điều kiện trước**

- Task chứa `notificationId`, `campaignId`, `tenantId`, kênh, địa chỉ nhận và nội dung đã render.
- Campaign đã ở trạng thái `RUNNING` tại thời điểm tạo task.

**Quy tắc**

- `notificationId` là khóa idempotency; cùng một notification không được tạo side effect gửi thành công hai lần.
- Worker chỉ acknowledge RabbitMQ message sau khi kết quả xử lý đã được lưu.
- Lỗi tạm thời được retry theo backoff và giới hạn retry đã cấu hình.
- Lỗi vĩnh viễn hoặc hết số lần retry chuyển notification sang `FAILED` và task sang dead-letter queue.
- Gửi thành công phát sinh `NotificationSent`; thất bại cuối cùng phát sinh `NotificationFailed`.
- Một số recipient thất bại không làm toàn bộ campaign chuyển sang `FAILED`.
- Khi tất cả notification của campaign đã có kết quả cuối cùng `SENT` hoặc `FAILED`, campaign chuyển sang `COMPLETED`.
- Campaign chỉ chuyển sang `FAILED` khi lỗi ở cấp campaign khiến hệ thống không thể tạo hoặc tiếp tục kế hoạch gửi sau khi đã retry cơ chế điều phối.

## 4. Trạng thái Campaign

### 4.1. Danh sách trạng thái

| Trạng thái | Ý nghĩa | Cho phép sửa nội dung/import |
|---|---|---|
| `DRAFT` | Campaign đang được chuẩn bị | Có |
| `SCHEDULED` | Đã khóa dữ liệu và chờ đến thời điểm gửi | Không |
| `RUNNING` | Đang tạo task hoặc gửi notification | Không |
| `COMPLETED` | Mọi notification đã có kết quả cuối cùng | Không |
| `FAILED` | Campaign gặp lỗi điều phối không thể phục hồi | Không |

### 4.2. Chuyển trạng thái hợp lệ

```text
DRAFT --schedule--> SCHEDULED --due time--> RUNNING --all notifications terminal--> COMPLETED
  |                                         |
  +---------------start now---------------->+--unrecoverable campaign error--------> FAILED
```

| Từ | Sang | Điều kiện |
|---|---|---|
| `DRAFT` | `SCHEDULED` | Start hợp lệ và thời điểm gửi nằm trong tương lai |
| `DRAFT` | `RUNNING` | Start hợp lệ và gửi ngay |
| `SCHEDULED` | `RUNNING` | Đã đến thời điểm gửi |
| `RUNNING` | `COMPLETED` | Tất cả notification đã `SENT` hoặc `FAILED` |
| `RUNNING` | `FAILED` | Lỗi cấp campaign không thể phục hồi |

Không có chuyển trạng thái ngược trong MVP. Muốn gửi lại campaign đã `COMPLETED` hoặc `FAILED` phải tạo campaign mới từ bản sao.

## 5. Domain model

### 5.1. Bounded Context: Campaign Management

#### Aggregate `Campaign`

`Campaign` là Aggregate Root và chịu trách nhiệm bảo vệ các invariant:

- Campaign luôn thuộc đúng một tenant.
- Chỉ `DRAFT` được sửa hoặc nhận thêm recipient.
- Chỉ campaign hợp lệ và có recipient mới được start.
- Start chỉ xảy ra một lần.
- Chuyển trạng thái phải đúng state machine.

Thuộc tính chính:

- `CampaignId id`
- `TenantId tenantId`
- `CampaignName name`
- `Channel channel`
- `MessageContent content`
- `Schedule schedule`
- `CampaignStatus status`
- `UserId createdBy`
- `Instant createdAt`
- `Instant startedAt`
- `long version`

Campaign không chứa collection toàn bộ recipient vì danh sách có thể rất lớn. Khi start, application service cung cấp một `CampaignReadiness` snapshot cho aggregate kiểm tra invariant.

#### Aggregate `RecipientImport`

Theo dõi một lần import file độc lập với vòng đời Campaign.

Thuộc tính chính:

- `RecipientImportId id`
- `CampaignId campaignId`
- `TenantId tenantId`
- `ImportStatus status`: `PENDING`, `PROCESSING`, `COMPLETED`, `REJECTED`
- `ImportSummary summary`
- `UserId requestedBy`
- `Instant createdAt`
- `Instant completedAt`

#### Aggregate `CampaignRecipient`

Mỗi recipient là một aggregate nhỏ để có thể import và xử lý số lượng lớn mà không phải load hoặc khóa toàn bộ Campaign.

Thuộc tính chính:

- `CampaignRecipientId id`
- `CampaignId campaignId`
- `TenantId tenantId`
- `Destination destination`
- `PersonalizationData personalizationData`
- `RecipientImportId sourceImportId`

Ràng buộc duy nhất theo `(tenantId, campaignId, normalizedDestination)`.

Các dòng import không hợp lệ không trở thành `CampaignRecipient`; chúng được lưu thành kết quả lỗi gắn với `RecipientImport` để người dùng tải báo cáo lỗi.

### 5.2. Bounded Context: Delivery

#### Aggregate `Notification`

`Notification` là Aggregate Root đại diện cho việc gửi đến một recipient.

Thuộc tính chính:

- `NotificationId id`
- `TenantId tenantId`
- `CampaignId campaignId`
- `CampaignRecipientId recipientId`
- `Destination destination`
- `RenderedMessage message`
- `NotificationStatus status`: `PENDING`, `PROCESSING`, `SENT`, `RETRY_WAIT`, `FAILED`
- `int attemptCount`
- `ProviderMessageId providerMessageId`
- `Instant sentAt`

#### Entity `DeliveryAttempt`

`DeliveryAttempt` nằm trong Notification aggregate và ghi lại từng lần gọi provider:

- `DeliveryAttemptId id`
- Số thứ tự lần thử.
- Thời điểm bắt đầu và kết thúc.
- Kết quả.
- Mã lỗi provider.
- Lỗi tạm thời hay vĩnh viễn.

Notification quyết định có được retry hay đã thất bại cuối cùng; bên ngoài không được sửa trực tiếp `attemptCount` hoặc trạng thái.

### 5.3. Value Objects

| Value Object | Trách nhiệm |
|---|---|
| `CampaignId`, `RecipientImportId`, `CampaignRecipientId`, `NotificationId` | Định danh có kiểu, không dùng UUID/String lẫn lộn |
| `TenantId`, `UserId` | Thể hiện rõ tenant ownership và actor |
| `CampaignName` | Chuẩn hóa và kiểm tra tên không rỗng |
| `Channel` | `EMAIL` hoặc `SMS` |
| `MessageContent` | Kiểm tra subject/body theo channel |
| `Schedule` | Thời điểm gửi và timezone đã chuẩn hóa về UTC |
| `Destination` | Kiểu tổng quát của `EmailAddress` hoặc `PhoneNumber` đã chuẩn hóa |
| `ImportSummary` | Tổng dòng, hợp lệ, trùng và không hợp lệ |
| `CampaignReadiness` | Kết quả kiểm tra import đang chạy và số recipient hợp lệ |
| `PersonalizationData` | Map dữ liệu cá nhân hóa bất biến |
| `RenderedMessage` | Nội dung cuối cùng được gửi, không phụ thuộc template thay đổi sau đó |
| `IdempotencyKey` | Chống xử lý lặp command từ client |

Value Object không có identity, bất biến và tự kiểm tra tính hợp lệ ngay khi được tạo.

## 6. Domain events và commands

### Domain events trên Kafka

- `CampaignCreated`
- `RecipientImportCompleted`
- `RecipientImportRejected`
- `CampaignScheduled`
- `CampaignStarted`
- `NotificationSent`
- `NotificationFailed`
- `CampaignCompleted`
- `CampaignFailed`

Mỗi event sử dụng envelope đã thống nhất gồm `eventId`, `eventType`, `eventVersion`, `tenantId`, `occurredAt` và `correlationId`.

### Task commands trên RabbitMQ

- `SendEmailTask`
- `SendSmsTask`
- `RetryNotificationTask`

Task là yêu cầu thực hiện công việc, không phải domain event. Việc một task được nhận không có nghĩa notification đã gửi thành công.

## 7. Quyết định thiết kế cần giữ khi triển khai

- Không tạo một JPA relationship chứa toàn bộ recipients bên trong `Campaign`.
- Không dùng chung JPA entity hoặc repository giữa Campaign và Delivery context.
- Domain model không phụ thuộc Spring, JPA, Kafka hoặc RabbitMQ.
- Database schema được thiết kế sau domain model và thuộc riêng từng service.
- `CampaignStarted` và outbox record phải được commit cùng thay đổi trạng thái Campaign.
- Mọi consumer và command handler đều phải idempotent vì hệ thống sử dụng delivery at-least-once.
