WITH deliveries AS (
    SELECT count(*) FILTER (WHERE channel='EMAIL' AND status='PENDING') AS email_pending,
        count(*) FILTER (WHERE channel='EMAIL' AND status='RETRY_PENDING') AS email_retry_pending,
        count(*) FILTER (WHERE channel='EMAIL' AND status='SENT') AS email_sent,
        count(*) FILTER (WHERE channel='EMAIL' AND status='FAILED') AS email_failed,
        count(*) FILTER (WHERE channel='SMS' AND status='PENDING') AS sms_pending,
        count(*) FILTER (WHERE channel='SMS' AND status='RETRY_PENDING') AS sms_retry_pending,
        count(*) FILTER (WHERE channel='SMS' AND status='SENT') AS sms_sent,
        count(*) FILTER (WHERE channel='SMS' AND status='FAILED') AS sms_failed
    FROM notification.deliveries
), outbox AS (
    SELECT count(*) AS result_outbox_pending, coalesce(max(extract(epoch FROM now()-occurred_at)),0) AS result_outbox_age
    FROM notification.result_outbox WHERE published_at IS NULL
), handoffs AS (
    SELECT count(*) AS handoffs_pending, coalesce(max(greatest(extract(epoch FROM now()-available_at),0)),0) AS handoffs_age
    FROM notification.delivery_handoffs WHERE published_at IS NULL
)
SELECT * FROM deliveries CROSS JOIN outbox CROSS JOIN handoffs
