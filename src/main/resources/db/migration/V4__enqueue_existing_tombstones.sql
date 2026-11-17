-- Earlier soft deletions must enter the erasure workflow too; retain at least 24 hours after upgrade.
INSERT INTO erasure_jobs(video_id, not_before)
SELECT id, now() + interval '24 hours'
FROM videos
WHERE deleted
ON CONFLICT(video_id) DO NOTHING;
