WITH results AS (
    SELECT tenant_id,campaign_id,count(*) AS delivered FROM reporting.delivery_reports GROUP BY tenant_id,campaign_id
)
SELECT count(*) FILTER (WHERE c.status='RUNNING') AS running,
    count(*) FILTER (WHERE c.expected IS NULL) AS unknown_expected,
    coalesce(sum(greatest(c.expected-coalesce(r.delivered,0),0)),0) AS pending
FROM reporting.campaign_reports c LEFT JOIN results r USING (tenant_id,campaign_id)
