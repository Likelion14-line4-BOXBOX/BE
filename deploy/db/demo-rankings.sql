-- Apply ranking-storage-types.sql first. Run in batch mode without --force.
SET NAMES utf8mb4;
SET time_zone = '+00:00';
SELECT GET_LOCK('boxbox-demo-rankings-2026-20',10) INTO @demo_lock;
CREATE TEMPORARY TABLE demo_ranking_assert (ok INT NOT NULL CHECK(ok=1));
INSERT INTO demo_ranking_assert VALUES(@demo_lock=1);

SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SET @demo_at=UTC_TIMESTAMP(6);
CREATE TEMPORARY TABLE demo_ranking_users (user_id BIGINT UNSIGNED PRIMARY KEY,demo_rank INT NOT NULL,demo_pnl BIGINT NOT NULL);
INSERT INTO demo_ranking_users
SELECT id,
       CASE login_id WHEN 'boxbox1' THEN 1 WHEN 'boxbox2' THEN 2 WHEN 'boxbox3' THEN 3 ELSE 4 END,
       CASE login_id WHEN 'boxbox1' THEN 58000 WHEN 'boxbox2' THEN 37000 WHEN 'boxbox3' THEN 12500 ELSE -8000 END
FROM users WHERE login_id IN ('boxbox1','boxbox2','boxbox3','boxbox4') AND status='ACTIVE' AND deleted_at IS NULL;
INSERT INTO demo_ranking_assert SELECT COUNT(*)=4 FROM demo_ranking_users;
INSERT INTO demo_ranking_assert SELECT COUNT(*)=1 FROM races WHERE season_year=2026 AND round_number=20 AND status='FINISHED' AND scheduled_start_at<@demo_at;

-- A non-trading demo round is excluded from the current-market selector by open_at=NULL.
INSERT INTO market_rounds(race_id,season_year,status,open_at,close_at,publish_at,rule_version,rule_snapshot)
SELECT r.id,r.season_year,'SUSPENDED',NULL,r.scheduled_start_at,DATE_ADD(r.actual_end_at,INTERVAL 24 HOUR),'demo-ranking-only-v1',
       JSON_OBJECT('demo',TRUE,'purpose','RANKING_ONLY','source','explicit demo SQL')
FROM races r WHERE r.season_year=2026 AND r.round_number=20
  AND NOT EXISTS(SELECT 1 FROM market_rounds m WHERE m.race_id=r.id);
SET @demo_round_id=(SELECT m.id FROM market_rounds m JOIN races r ON r.id=m.race_id AND r.season_year=m.season_year WHERE r.season_year=2026 AND r.round_number=20);
INSERT INTO demo_ranking_assert SELECT COUNT(*)=1 FROM market_rounds WHERE id=@demo_round_id
  AND status='SUSPENDED' AND open_at IS NULL AND rule_version='demo-ranking-only-v1'
  AND settlement_status='NOT_STARTED' AND JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.purpose'))='RANKING_ONLY';

CREATE TEMPORARY TABLE demo_ranking_targets(scope VARCHAR(16),metric VARCHAR(24),round_id BIGINT UNSIGNED NULL);
INSERT INTO demo_ranking_targets VALUES
('ROUND','TOTAL_ASSETS',@demo_round_id),('ROUND','RETURN_RATE',@demo_round_id),
('SEASON','TOTAL_ASSETS',NULL),('SEASON','RETURN_RATE',NULL);

-- Keep an existing published/building ranking rather than superseding real data.
INSERT INTO ranking_snapshots(season_year,round_id,scope,type,status,snapshot_at,published_at)
SELECT 2026,t.round_id,t.scope,t.metric,'PUBLISHED',@demo_at,@demo_at
FROM demo_ranking_targets t
WHERE NOT EXISTS(SELECT 1 FROM ranking_snapshots p WHERE p.season_year=2026 AND p.scope=t.scope
  AND p.type=t.metric AND p.status IN ('BUILDING','PUBLISHED'));
CREATE TEMPORARY TABLE demo_ranking_created AS
SELECT s.id FROM ranking_snapshots s JOIN demo_ranking_targets t ON t.scope=s.scope AND t.metric=s.type AND t.round_id<=>s.round_id
WHERE s.season_year=2026 AND s.snapshot_at=@demo_at AND s.published_at=@demo_at;
INSERT INTO ranking_entries(snapshot_id,user_id,`rank`,total_assets,round_pnl,return_rate)
SELECT s.id,u.user_id,u.demo_rank,1000000+u.demo_pnl,u.demo_pnl,u.demo_pnl/10000.00000000
FROM demo_ranking_created s CROSS JOIN demo_ranking_users u;
INSERT INTO demo_ranking_assert SELECT NOT EXISTS(
 SELECT 1 FROM demo_ranking_created c LEFT JOIN ranking_entries e ON e.snapshot_id=c.id GROUP BY c.id HAVING COUNT(e.user_id)<>4
);

-- The operator injects original-row/count preservation guards before COMMIT.
COMMIT;
SET @demo_created_count=(SELECT COUNT(*) FROM demo_ranking_created);
SELECT @demo_round_id AS demo_round_id,@demo_created_count AS snapshots_created,
       (SELECT COUNT(*) FROM ranking_entries e JOIN demo_ranking_created c ON c.id=e.snapshot_id) AS entries_created;
SELECT RELEASE_LOCK('boxbox-demo-rankings-2026-20');
