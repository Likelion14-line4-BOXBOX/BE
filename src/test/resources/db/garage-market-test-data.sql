UPDATE users SET coin_balance=900000 WHERE id=1;
UPDATE users SET coin_balance=250000 WHERE id=2;
INSERT INTO market_rounds(id,race_id,season_year,status,open_at,close_at,publish_at,rule_version,rule_snapshot) VALUES
 (1,1,2026,'OPEN','2026-01-01 00:00:00','2026-12-31 00:00:00',NULL,'test','{}'),
 (4,4,2025,'CLOSED','2025-01-01 00:00:00','2025-12-31 00:00:00',NULL,'test','{}');
INSERT INTO market_stocks(id,round_id,season_year,driver_id,team_id,base_rank,starting_price,current_price,theoretical_price) VALUES
 (1,1,2026,11,1,1,50000,60000,60000),
 (2,1,2026,12,2,2,10000,10000,10000),
 (4,4,2025,11,1,1,10000,99999,99999);
INSERT INTO positions(user_id,stock_id,quantity,total_cost) VALUES (1,1,2,100000),(1,2,0,0),(1,4,3,30000),(2,1,7,350000);
INSERT INTO user_season_baselines(user_id,season_year,starting_assets,joined_at) VALUES
 (1,2026,1000000,'2026-01-01 00:00:00'),(1,2025,2000000,'2025-01-01 00:00:00'),
 (2,2026,500000,'2026-01-01 00:00:00');
INSERT INTO ranking_snapshots(id,season_year,scope,type,status,snapshot_at,published_at) VALUES
 (1,2026,'SEASON','ASSET','PUBLISHED','2026-01-01 00:00:00','2026-01-01 00:00:00'),
 (2,2026,'SEASON','ASSET','BUILDING','2026-01-02 00:00:00','2026-01-02 00:00:00'),
 (3,2026,'SEASON','ASSET','PUBLISHED','2099-01-01 00:00:00','2099-01-01 00:00:00'),
 (4,2025,'SEASON','ASSET','PUBLISHED','2026-01-03 00:00:00','2026-01-03 00:00:00');
INSERT INTO ranking_entries(snapshot_id,user_id,`rank`,total_assets) VALUES
 (1,1,12,1000000),(1,2,25,500000),(2,1,1,1000000),(3,1,2,1000000),(4,1,3,1000000);
