-- #61: 기존 DB 전용. 선택한 DB에서 실행한다. MySQL DDL은 암묵적으로 COMMIT한다.
-- 업무 데이터/영문 이름/고유 ID는 변경하지 않는다. 신규 DB는 deploy/db/schema.sql을 사용한다.
SET NAMES utf8mb4;

SET @korean_name_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='teams' AND column_name='name_ko'),
  'SELECT 1', 'ALTER TABLE teams ADD COLUMN name_ko VARCHAR(100) NULL COMMENT ''한국어 표시명; 미입력 NULL'' AFTER name');
PREPARE korean_name_statement FROM @korean_name_ddl;
EXECUTE korean_name_statement;
DEALLOCATE PREPARE korean_name_statement;

SET @korean_name_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drivers' AND column_name='name_ko'),
  'SELECT 1', 'ALTER TABLE drivers ADD COLUMN name_ko VARCHAR(100) NULL COMMENT ''한국어 표시명; 미입력 NULL'' AFTER name');
PREPARE korean_name_statement FROM @korean_name_ddl;
EXECUTE korean_name_statement;
DEALLOCATE PREPARE korean_name_statement;

SET @korean_name_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='races' AND column_name='name_ko'),
  'SELECT 1', 'ALTER TABLE races ADD COLUMN name_ko VARCHAR(120) NULL COMMENT ''한국어 표시명; 미입력 NULL'' AFTER name');
PREPARE korean_name_statement FROM @korean_name_ddl;
EXECUTE korean_name_statement;
DEALLOCATE PREPARE korean_name_statement;
