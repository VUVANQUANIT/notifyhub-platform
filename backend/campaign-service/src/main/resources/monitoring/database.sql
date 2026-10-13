WITH campaigns AS (
    SELECT count(*) FILTER (WHERE status='RUNNING') AS running,
        coalesce(max(extract(epoch FROM now()-started_at)) FILTER (WHERE status='RUNNING'),0) AS running_age
    FROM campaign.campaigns
), outbox AS (
    SELECT count(*) AS outbox_pending, coalesce(max(extract(epoch FROM now()-occurred_at)),0) AS outbox_age
    FROM campaign.outbox_events WHERE published_at IS NULL
), tasks AS (
    SELECT count(*) AS tasks_pending, coalesce(max(extract(epoch FROM now()-created_at)),0) AS tasks_age
    FROM campaign.delivery_tasks WHERE published_at IS NULL
), dispatch AS (
    SELECT count(*) AS dispatch_pending, coalesce(max(extract(epoch FROM now()-occurred_at)),0) AS dispatch_age
    FROM campaign.dispatch_jobs WHERE completed_at IS NULL
)
SELECT * FROM campaigns CROSS JOIN outbox CROSS JOIN tasks CROSS JOIN dispatch
