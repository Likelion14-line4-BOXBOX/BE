-- Align the existing CHECK with the storage types written/read by the market code.
-- Existing allowed values and scope/round_id checks remain valid.
-- Back up ranking_snapshots first. ALTER TABLE commits implicitly in MySQL.
SET @ranking_check_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ranking_snapshots'
      AND CONSTRAINT_NAME='ck_ranking_snapshots_scope' AND CONSTRAINT_TYPE='CHECK'
);
SET @ranking_check_current = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS t
    JOIN information_schema.CHECK_CONSTRAINTS c
      ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME
    WHERE t.TABLE_SCHEMA=DATABASE() AND t.TABLE_NAME='ranking_snapshots'
      AND t.CONSTRAINT_NAME='ck_ranking_snapshots_scope' AND t.ENFORCED='YES'
      AND c.CHECK_CLAUSE LIKE '%TOTAL_ASSETS%' AND c.CHECK_CLAUSE LIKE '%RETURN_RATE%'
);
SET @ranking_check_ddl = IF(@ranking_check_current=1, 'SELECT 1', CONCAT(
    'ALTER TABLE ranking_snapshots ',
    IF(@ranking_check_exists=1, 'DROP CHECK ck_ranking_snapshots_scope, ', ''),
    'ADD CONSTRAINT ck_ranking_snapshots_scope CHECK (',
    '(scope=''ROUND'' AND round_id IS NOT NULL AND type IN (''ASSET'',''ROUND_RETURN'',''RANK_GAIN'',''TOTAL_ASSETS'',''RETURN_RATE'')) OR ',
    '(scope=''SEASON'' AND round_id IS NULL AND type IN (''ASSET'',''SEASON_RETURN'',''TOTAL_ASSETS'',''RETURN_RATE'')))'
));
PREPARE ranking_check_statement FROM @ranking_check_ddl;
EXECUTE ranking_check_statement;
DEALLOCATE PREPARE ranking_check_statement;
