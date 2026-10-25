SELECT count(*) FILTER (WHERE state='PENDING') AS recovery_mail_pending,
    coalesce(max(greatest(extract(epoch FROM now()-available_at),0)) FILTER (WHERE state='PENDING'),0) AS recovery_mail_age,
    count(*) FILTER (WHERE state='FAILED') AS recovery_mail_failed
FROM auth.recovery_mail_outbox
