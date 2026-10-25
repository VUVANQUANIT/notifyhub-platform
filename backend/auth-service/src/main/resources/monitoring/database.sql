SELECT count(*) FILTER (WHERE state='PENDING') AS recovery_mail_pending,
    coalesce(max(greatest(extract(epoch FROM now()-available_at),0)) FILTER (WHERE state='PENDING'),0) AS recovery_mail_age,
    count(*) FILTER (WHERE state='FAILED') AS recovery_mail_failed,
    (SELECT count(*) FROM auth.audit_outbox WHERE published_at IS NULL) AS audit_pending,
    (SELECT coalesce(max(greatest(extract(epoch FROM now()-available_at),0)),0) FROM auth.audit_outbox WHERE published_at IS NULL) AS audit_age,
    (SELECT count(*) FROM auth.invitation_mail_outbox WHERE state='PENDING') AS invitation_pending,
    (SELECT coalesce(max(greatest(extract(epoch FROM now()-available_at),0)),0) FROM auth.invitation_mail_outbox WHERE state='PENDING') AS invitation_age
FROM auth.recovery_mail_outbox
