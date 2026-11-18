-- BOXBOX ERD v4 | MySQL 8.0.16+ | 2026-11-12 KST
-- Proposal; apply only to an empty selected database. No live DB changed.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 팀 기본정보 | CM04 통합 기준정보; MG01~03, RM03~04, TR01~02. DB 직접 입력; 외부 호출 없음
CREATE TABLE `teams` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `code` VARCHAR(40) NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `name_ko` VARCHAR(100) NULL COMMENT '한국어 표시명; 미입력 NULL. 외부 동기화에서 덮어쓰지 않음',
  `description` VARCHAR(1000) NOT NULL DEFAULT '',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `stats_season_year` SMALLINT UNSIGNED NULL COMMENT '직접 입력한 성적의 기준 시즌; 외부 자동 갱신 없음',
  `championship_rank` SMALLINT UNSIGNED NULL COMMENT '기준 시점의 검증 입력값; 미입력 NULL, 실시간 성적 아님',
  `championship_points` DECIMAL(8,2) NULL COMMENT '기준 시점의 검증 입력값; 미입력 NULL, 실시간 성적 아님',
  `display_order` SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  `stats_as_of` DATETIME(6) NULL COMMENT '직접 입력한 성적의 기준 시각. 성적이 있으면 필수',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_teams_1` (`code`),
  CONSTRAINT `ck_teams_stats_source` CHECK ((`championship_rank` IS NULL AND `championship_points` IS NULL) OR (`stats_as_of` IS NOT NULL AND `stats_season_year` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='팀 기본정보';

-- 그랑프리 | CM01~02, HM01. 외부 meetings 목록 1종 + DB 직접 입력 본선/라운드 설정
CREATE TABLE `races` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `season_year` SMALLINT UNSIGNED NOT NULL COMMENT '시즌 연도; 별도 시즌 마스터 없음',
  `round_number` SMALLINT UNSIGNED NOT NULL,
  `name` VARCHAR(120) NOT NULL,
  `name_ko` VARCHAR(120) NULL COMMENT '한국어 표시명; 미입력 NULL. 외부 동기화에서 덮어쓰지 않음',
  `country_name` VARCHAR(80) NOT NULL,
  `circuit_name` VARCHAR(120) NOT NULL,
  `weekend_starts_at` DATETIME(6) NOT NULL,
  `weekend_ends_at` DATETIME(6) NOT NULL,
  `scheduled_start_at` DATETIME(6) NULL COMMENT 'DB에 직접 입력한 본선 예정 시각; meetings의 주말 시각으로 대체하지 않음. 미입력 거래 보류',
  `actual_end_at` DATETIME(6) NULL COMMENT '확인 후 직접 입력한 본선 실제 종료. 미입력 정산/다음 개장 보류',
  `status` VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED',
  `external_key` VARCHAR(80) NULL COMMENT '유일한 외부 수집 OpenF1 meeting_key 매핑',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `circuit_key` VARCHAR(80) NOT NULL COMMENT 'OpenF1 circuit_key 문자열; FE 서킷 이미지 매핑 키',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_races_1` (`season_year`, `round_number`),
  UNIQUE KEY `uq_races_2` (`id`, `season_year`),
  UNIQUE KEY `uq_races_3` (`external_key`),
  KEY `ix_races_1` (`season_year`, `scheduled_start_at`),
  CONSTRAINT `ck_races_status` CHECK (`status` IN ('SCHEDULED','LIVE','FINISHED','CANCELLED')),
  CONSTRAINT `ck_races_weekend` CHECK (`weekend_ends_at` > `weekend_starts_at`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='그랑프리';

-- 선수 기본정보 | CM04 통합 기준정보; MG01~03, RM03~04, TR01~02. DB 직접 입력; 외부 호출 없음
CREATE TABLE `drivers` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `code` VARCHAR(40) NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `name_ko` VARCHAR(100) NULL COMMENT '한국어 표시명; 미입력 NULL. 외부 동기화에서 덮어쓰지 않음',
  `description` VARCHAR(1000) NOT NULL DEFAULT '',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `stats_season_year` SMALLINT UNSIGNED NULL COMMENT '직접 입력한 성적의 기준 시즌; 외부 자동 갱신 없음',
  `championship_rank` SMALLINT UNSIGNED NULL COMMENT '기준 시점의 검증 입력값; 미입력 NULL, 실시간 성적 아님',
  `championship_points` DECIMAL(8,2) NULL COMMENT '기준 시점의 검증 입력값; 미입력 NULL, 실시간 성적 아님',
  `team_id` BIGINT UNSIGNED NULL COMMENT '고정 DB 소속; 외부 자동 이적/라인업 수집 없음',
  `car_number` SMALLINT UNSIGNED NULL,
  `stats_as_of` DATETIME(6) NULL COMMENT '직접 입력한 성적의 기준 시각. 성적이 있으면 필수',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_drivers_1` (`code`),
  CONSTRAINT `fk_drivers_1` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `ck_drivers_stats_source` CHECK ((`championship_rank` IS NULL AND `championship_points` IS NULL) OR (`stats_as_of` IS NOT NULL AND `stats_season_year` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='선수 기본정보';

-- 게시판 | TR01
CREATE TABLE `boards` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `code` VARCHAR(80) NOT NULL,
  `type` VARCHAR(16) NOT NULL,
  `team_id` BIGINT UNSIGNED NULL,
  `name` VARCHAR(100) NOT NULL,
  `display_order` SMALLINT UNSIGNED NOT NULL,
  `is_active` BOOLEAN NOT NULL DEFAULT TRUE,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_boards_1` (`code`),
  UNIQUE KEY `uq_boards_2` (`team_id`),
  CONSTRAINT `fk_boards_1` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `ck_boards_type` CHECK (`type` IN ('TEAM','FREE','BEGINNER')),
  CONSTRAINT `ck_boards_team` CHECK ((`type`='TEAM' AND `team_id` IS NOT NULL) OR (`type` IN ('FREE','BEGINNER') AND `team_id` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='게시판';

-- 그랑프리 세부 세션 | CM02, HM01, TR19~21. 예정 일정은 OpenF1 sessions에서 동기화; 실제 전환은 별도로 기록
CREATE TABLE `race_sessions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `race_id` BIGINT UNSIGNED NOT NULL,
  `type` VARCHAR(24) NOT NULL,
  `session_number` TINYINT UNSIGNED NOT NULL DEFAULT 1,
  `name` VARCHAR(100) NOT NULL,
  `starts_at` DATETIME(6) NOT NULL,
  `ends_at` DATETIME(6) NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `actual_starts_at` DATETIME(6) NULL COMMENT '확인된 실제 세션 전환 시각; 예정 시각으로 채우지 않음',
  `actual_ends_at` DATETIME(6) NULL COMMENT '확인된 실제 세션 전환 시각; 예정 시각으로 채우지 않음',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_race_sessions_1` (`race_id`, `type`, `session_number`),
  UNIQUE KEY `uq_race_sessions_2` (`id`, `race_id`),
  KEY `ix_race_sessions_1` (`race_id`, `starts_at`),
  CONSTRAINT `fk_race_sessions_1` FOREIGN KEY (`race_id`) REFERENCES `races` (`id`),
  CONSTRAINT `ck_race_sessions_type` CHECK (`type` IN ('PRACTICE','QUALIFYING','SPRINT_QUALIFYING','SPRINT','RACE'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='그랑프리 세부 세션';

-- 레이스 공용 채팅방 | TR17~21; onlineCount는 실시간 연결 상태에서 계산
CREATE TABLE `chat_rooms` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `race_id` BIGINT UNSIGNED NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
  `opens_at` DATETIME(6) NOT NULL,
  `closes_at` DATETIME(6) NOT NULL,
  `archived_at` DATETIME(6) NULL,
  `stream_sequence` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_chat_rooms_1` (`race_id`),
  UNIQUE KEY `uq_chat_rooms_2` (`id`, `race_id`),
  KEY `ix_chat_rooms_1` (`status`, `closes_at`, `id`),
  CONSTRAINT `fk_chat_rooms_1` FOREIGN KEY (`race_id`) REFERENCES `races` (`id`),
  CONSTRAINT `ck_chat_rooms_status` CHECK (`status` IN ('SCHEDULED','LIVE','ARCHIVED')),
  CONSTRAINT `ck_chat_rooms_time` CHECK (`closes_at` > `opens_at`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='레이스 공용 채팅방';

-- 레이스 마켓 라운드 | RM01~02, RM13; 거래 수량/가격 상한 컬럼 없음
CREATE TABLE `market_rounds` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `race_id` BIGINT UNSIGNED NOT NULL,
  `season_year` SMALLINT UNSIGNED NOT NULL COMMENT '시즌 연도; 별도 시즌 마스터 없음',
  `status` VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED',
  `open_at` DATETIME(6) NULL,
  `close_at` DATETIME(6) NOT NULL,
  `publish_at` DATETIME(6) NULL,
  `ranking_published_at` DATETIME(6) NULL,
  `rule_version` VARCHAR(40) NOT NULL,
  `rule_snapshot` JSON NOT NULL COMMENT '미확정 보너스/환산 정책을 버전과 함께 고정',
  `status_version` BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `stream_sequence` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `settlement_version` INT UNSIGNED NOT NULL DEFAULT 1,
  `settlement_status` VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED',
  `official_result_version` INT UNSIGNED NULL,
  `settlement_started_at` DATETIME(6) NULL,
  `settlement_completed_at` DATETIME(6) NULL,
  `settlement_error` VARCHAR(1000) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_market_rounds_1` (`race_id`),
  UNIQUE KEY `uq_market_rounds_2` (`id`, `season_year`),
  CONSTRAINT `fk_market_rounds_1` FOREIGN KEY (`race_id`, `season_year`) REFERENCES `races` (`id`, `season_year`),
  CONSTRAINT `ck_market_rounds_status` CHECK (`status` IN ('SCHEDULED','OPEN','CLOSED','AWAITING_RESULT','SETTLING','SETTLED','SUSPENDED','CANCELLED')),
  CONSTRAINT `ck_market_rounds_time` CHECK (`open_at` IS NULL OR `close_at` > `open_at`),
  CONSTRAINT `ck_market_rounds_settlement_version` CHECK (`settlement_version`>0),
  CONSTRAINT `ck_market_rounds_settlement_status` CHECK (`settlement_status` IN ('NOT_STARTED','PENDING','PROCESSING','COMPLETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='레이스 마켓 라운드';

-- 회원·자체 로그인·프로필·지갑 | AU01~04, MG01~03, RM06~11; 회원/지갑/최초 지급을 같은 트랜잭션으로 생성
CREATE TABLE `users` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `login_id` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '자체 로그인 아이디. 앱에서 앞뒤 공백 제거·소문자 정규화 및 영문/숫자/_ 형식 검증',
  `password_hash` VARCHAR(255) NOT NULL COMMENT '단방향 비밀번호 해시; 원문 저장/응답 금지. 비밀번호 변경은 인증 서비스에서 처리',
  `nickname` VARCHAR(20) NOT NULL,
  `bio` VARCHAR(100) NOT NULL DEFAULT '',
  `avatar_asset_id` VARCHAR(80) NOT NULL DEFAULT 'avatar_default' COMMENT 'FE 이미지 프리셋 ID만 저장; 서버 허용 코드 목록으로 검증. 이미지 FK/URL 없음',
  `favorite_team_id` BIGINT UNSIGNED NULL,
  `favorite_driver_id` BIGINT UNSIGNED NULL,
  `show_market_assets` BOOLEAN NOT NULL DEFAULT FALSE,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `account_type` VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '기본 자체 가입 회원; 데모 계정도 아이디/비밀번호로 인증',
  `onboarding_completed` BOOLEAN NOT NULL DEFAULT FALSE,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `deleted_at` DATETIME(6) NULL COMMENT '논리 삭제; 응답에서 본문/작성자 숨김',
  `coin_balance` BIGINT NOT NULL DEFAULT 0,
  `wallet_version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_users_1` (`nickname`),
  UNIQUE KEY `uq_users_2` (`login_id`),
  CONSTRAINT `fk_users_1` FOREIGN KEY (`favorite_team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `fk_users_2` FOREIGN KEY (`favorite_driver_id`) REFERENCES `drivers` (`id`),
  CONSTRAINT `ck_users_nickname` CHECK (CHAR_LENGTH(`nickname`) BETWEEN 2 AND 20),
  CONSTRAINT `ck_users_favorite` CHECK (`favorite_driver_id` IS NULL OR `favorite_team_id` IS NOT NULL),
  CONSTRAINT `ck_users_status` CHECK (`status` IN ('ACTIVE','SUSPENDED','WITHDRAWN')),
  CONSTRAINT `ck_users_kind` CHECK (`account_type` IN ('DEMO','NORMAL')),
  CONSTRAINT `ck_users_balance` CHECK (`coin_balance` >= 0),
  CONSTRAINT `ck_users_login_length` CHECK (CHAR_LENGTH(`login_id`) BETWEEN 4 AND 20),
  CONSTRAINT `ck_users_password_hash` CHECK (CHAR_LENGTH(`password_hash`)>0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='회원·자체 로그인·프로필·지갑';

-- 게시판별 단일 말머리 | TR02, TR22; 비활성 태그 글은 아카이브 조회
CREATE TABLE `board_tags` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `board_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(80) NOT NULL,
  `tag_group` VARCHAR(16) NOT NULL,
  `driver_id` BIGINT UNSIGNED NULL,
  `is_active` BOOLEAN NOT NULL DEFAULT TRUE,
  `display_order` SMALLINT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_board_tags_1` (`board_id`, `name`),
  UNIQUE KEY `uq_board_tags_2` (`board_id`, `id`),
  CONSTRAINT `fk_board_tags_1` FOREIGN KEY (`board_id`) REFERENCES `boards` (`id`),
  CONSTRAINT `fk_board_tags_2` FOREIGN KEY (`driver_id`) REFERENCES `drivers` (`id`),
  CONSTRAINT `ck_board_tags_group` CHECK (`tag_group` IN ('TEAM','DRIVER','TOPIC'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='게시판별 단일 말머리';

-- 공식 경기 결과 버전 | CM02, RM11. DB 직접 입력/검증한 결과; 추가 결과/챔피언십 수집 없음
CREATE TABLE `race_results` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `race_id` BIGINT UNSIGNED NOT NULL,
  `season_year` SMALLINT UNSIGNED NOT NULL COMMENT '시즌 연도; 별도 시즌 마스터 없음',
  `driver_id` BIGINT UNSIGNED NOT NULL,
  `team_id` BIGINT UNSIGNED NOT NULL COMMENT '경기 당시 소속 고정',
  `result_version` INT UNSIGNED NOT NULL,
  `finish_rank` SMALLINT UNSIGNED NULL,
  `classification_status` VARCHAR(24) NOT NULL,
  `official_points` DECIMAL(8,2) NULL COMMENT 'DB에 검증 입력한 포인트; 누락 NULL. 보너스 공식/포인트 미준비시 정산 보류',
  `result_status` VARCHAR(24) NOT NULL DEFAULT 'PROVISIONAL',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `input_source` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '추가 외부 결과 수집 없음',
  `recorded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `verified_at` DATETIME(6) NULL COMMENT '검증 완료시 기록. FINAL이면 필수',
  `source_note` VARCHAR(1000) NULL COMMENT '입력/검증 근거 요약; 비밀값 제외',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_race_results_1` (`race_id`, `driver_id`, `result_version`),
  CONSTRAINT `fk_race_results_1` FOREIGN KEY (`race_id`, `season_year`) REFERENCES `races` (`id`, `season_year`),
  CONSTRAINT `fk_race_results_2` FOREIGN KEY (`driver_id`) REFERENCES `drivers` (`id`),
  CONSTRAINT `fk_race_results_3` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `ck_race_results_rank` CHECK (`finish_rank` IS NULL OR `finish_rank` > 0),
  CONSTRAINT `ck_race_results_version` CHECK (`result_version` > 0),
  CONSTRAINT `ck_race_results_classification` CHECK (`classification_status` IN ('RUNNING','FINISHED','DNF','DNS','DSQ')),
  CONSTRAINT `ck_race_results_status` CHECK (`result_status` IN ('PROVISIONAL','FINAL')),
  CONSTRAINT `ck_race_results_input_source` CHECK (`input_source`='MANUAL'),
  CONSTRAINT `ck_race_results_verified` CHECK (`result_status`<>'FINAL' OR `verified_at` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='공식 경기 결과 버전';

-- 라운드별 선수 종목 | RM03~04, RM13
CREATE TABLE `market_stocks` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `round_id` BIGINT UNSIGNED NOT NULL,
  `season_year` SMALLINT UNSIGNED NOT NULL COMMENT '시즌 연도; 별도 시즌 마스터 없음',
  `driver_id` BIGINT UNSIGNED NOT NULL,
  `team_id` BIGINT UNSIGNED NOT NULL COMMENT '개장 당시 소속 고정',
  `base_rank` SMALLINT UNSIGNED NOT NULL,
  `starting_price` BIGINT NOT NULL,
  `current_price` BIGINT NOT NULL,
  `theoretical_price` DECIMAL(28,8) NOT NULL COMMENT '연속 변동 계산용 제안; 표시/체결은 정수 코인',
  `price_version` BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `total_buy_quantity` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `settlement_price` BIGINT NULL,
  `position_score` INT NULL,
  `result_bonus` INT NULL,
  `final_score` INT NULL,
  `settlement_version` INT UNSIGNED NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_market_stocks_1` (`round_id`, `driver_id`),
  UNIQUE KEY `uq_market_stocks_2` (`id`, `round_id`),
  KEY `ix_market_stocks_1` (`round_id`, `base_rank`, `id`),
  CONSTRAINT `fk_market_stocks_1` FOREIGN KEY (`round_id`, `season_year`) REFERENCES `market_rounds` (`id`, `season_year`),
  CONSTRAINT `fk_market_stocks_2` FOREIGN KEY (`driver_id`) REFERENCES `drivers` (`id`),
  CONSTRAINT `fk_market_stocks_3` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`),
  CONSTRAINT `ck_market_stocks_price` CHECK (`starting_price` > 0 AND `current_price` > 0 AND `theoretical_price` > 0),
  CONSTRAINT `ck_market_stocks_rank` CHECK (`base_rank` > 0),
  CONSTRAINT `ck_market_stocks_final_score` CHECK (`final_score` IS NULL OR `final_score` BETWEEN -30 AND 40),
  CONSTRAINT `ck_market_stocks_settlement_price` CHECK (`settlement_price` IS NULL OR `settlement_price`>0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='라운드별 선수 종목';

-- 랭킹 집계 기준점 | RM12; 커서가 snapshot_id를 고정
CREATE TABLE `ranking_snapshots` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `season_year` SMALLINT UNSIGNED NOT NULL COMMENT '시즌 연도; 별도 시즌 마스터 없음',
  `round_id` BIGINT UNSIGNED NULL,
  `scope` VARCHAR(16) NOT NULL,
  `type` VARCHAR(24) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'BUILDING',
  `snapshot_at` DATETIME(6) NOT NULL,
  `published_at` DATETIME(6) NULL,
  `previous_snapshot_id` BIGINT UNSIGNED NULL,
  PRIMARY KEY (`id`),
  KEY `ix_ranking_snapshots_1` (`season_year`, `scope`, `type`, `status`, `snapshot_at`, `id`),
  CONSTRAINT `fk_ranking_snapshots_1` FOREIGN KEY (`round_id`, `season_year`) REFERENCES `market_rounds` (`id`, `season_year`),
  CONSTRAINT `fk_ranking_snapshots_2` FOREIGN KEY (`previous_snapshot_id`) REFERENCES `ranking_snapshots` (`id`),
  CONSTRAINT `ck_ranking_snapshots_scope` CHECK ((`scope`='ROUND' AND `round_id` IS NOT NULL AND `type` IN ('ASSET','ROUND_RETURN','RANK_GAIN')) OR (`scope`='SEASON' AND `round_id` IS NULL AND `type` IN ('ASSET','SEASON_RETURN'))),
  CONSTRAINT `ck_ranking_snapshots_status` CHECK (`status` IN ('BUILDING','PUBLISHED','SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='랭킹 집계 기준점';

-- SSE 재전송 이벤트 | TR21, RM13; 업무 변경과 동일 트랜잭션 삽입, 사용자별 권한은 전송 시 계산
CREATE TABLE `stream_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `room_id` BIGINT UNSIGNED NULL,
  `round_id` BIGINT UNSIGNED NULL,
  `stream_sequence` BIGINT UNSIGNED NOT NULL,
  `event_type` VARCHAR(40) NOT NULL,
  `payload` JSON NOT NULL,
  `occurred_at` DATETIME(6) NOT NULL,
  `published_at` DATETIME(6) NULL,
  `expires_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_stream_events_1` (`room_id`, `stream_sequence`),
  UNIQUE KEY `uq_stream_events_2` (`round_id`, `stream_sequence`),
  KEY `ix_stream_events_1` (`published_at`, `id`),
  KEY `ix_stream_events_2` (`expires_at`),
  CONSTRAINT `fk_stream_events_1` FOREIGN KEY (`room_id`) REFERENCES `chat_rooms` (`id`),
  CONSTRAINT `fk_stream_events_2` FOREIGN KEY (`round_id`) REFERENCES `market_rounds` (`id`),
  CONSTRAINT `ck_stream_events_scope` CHECK ((`room_id` IS NOT NULL AND `round_id` IS NULL) OR (`room_id` IS NULL AND `round_id` IS NOT NULL)),
  CONSTRAINT `ck_stream_events_sequence` CHECK (`stream_sequence`>0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SSE 재전송 이벤트';

-- 리프레시 토큰 이력 | AU03~04; 토큰 회전 시 이전 행 보존
CREATE TABLE `auth_sessions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `user_id` BIGINT UNSIGNED NOT NULL,
  `family_id` CHAR(36) NOT NULL,
  `refresh_token_hash` BINARY(32) NOT NULL COMMENT '원문 토큰 저장 금지',
  `expires_at` DATETIME(6) NOT NULL,
  `rotated_at` DATETIME(6) NULL,
  `revoked_at` DATETIME(6) NULL,
  `replaced_by_id` BIGINT UNSIGNED NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_auth_sessions_1` (`refresh_token_hash`),
  KEY `ix_auth_sessions_1` (`user_id`, `expires_at`),
  KEY `ix_auth_sessions_2` (`family_id`),
  CONSTRAINT `fk_auth_sessions_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_auth_sessions_2` FOREIGN KEY (`replaced_by_id`) REFERENCES `auth_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='리프레시 토큰 이력';

-- 회원 시즌 기준 자산 | RM08, RM12; 가입시점/시즌 유지 정책 미확정
CREATE TABLE `user_season_baselines` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `season_year` SMALLINT UNSIGNED NOT NULL COMMENT '시즌 연도; 별도 시즌 마스터 없음',
  `starting_assets` BIGINT NULL COMMENT '시즌 수익률 분모 미확정이면 NULL',
  `joined_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`user_id`, `season_year`),
  CONSTRAINT `fk_user_season_baselines_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_user_season_baselines_assets` CHECK (`starting_assets` IS NULL OR `starting_assets` >= 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='회원 시즌 기준 자산';

-- 게시글 | TR03~07, TR22, MG04; 반응 수는 관계 테이블 집계
CREATE TABLE `posts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `board_id` BIGINT UNSIGNED NOT NULL,
  `author_id` BIGINT UNSIGNED NOT NULL,
  `tag_id` BIGINT UNSIGNED NULL COMMENT 'NULL=전체; 배열/중간 테이블 불필요',
  `title` VARCHAR(20) NOT NULL,
  `content` LONGTEXT NULL,
  `view_count` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `deleted_at` DATETIME(6) NULL COMMENT '논리 삭제; 응답에서 본문/작성자 숨김',
  PRIMARY KEY (`id`),
  KEY `ix_posts_1` (`board_id`, `created_at`, `id`),
  KEY `ix_posts_2` (`board_id`, `tag_id`, `created_at`, `id`),
  KEY `ix_posts_3` (`author_id`, `created_at`, `id`),
  CONSTRAINT `fk_posts_1` FOREIGN KEY (`board_id`) REFERENCES `boards` (`id`),
  CONSTRAINT `fk_posts_2` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_posts_3` FOREIGN KEY (`board_id`, `tag_id`) REFERENCES `board_tags` (`board_id`, `id`),
  CONSTRAINT `ck_posts_title` CHECK (CHAR_LENGTH(TRIM(`title`)) BETWEEN 1 AND 20),
  CONSTRAINT `ck_posts_content` CHECK (`deleted_at` IS NOT NULL OR (`content` IS NOT NULL AND CHAR_LENGTH(TRIM(`content`)) > 0))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='게시글';

-- 채팅 참여·자동 도장 | TR17~21, MG09; LIVE 채팅방 참여(JOIN)로 지급·room→race 조회; 메시지 작성 불필요·삭제 후 유지; 사용자 수동 변경 불가
CREATE TABLE `chat_participants` (
  `room_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `first_joined_at` DATETIME(6) NOT NULL,
  `last_joined_at` DATETIME(6) NOT NULL,
  `first_message_at` DATETIME(6) NULL,
  `stamp_recorded_at` DATETIME(6) NULL,
  `stamp_trigger_type` VARCHAR(16) NULL,
  PRIMARY KEY (`room_id`, `user_id`),
  KEY `ix_chat_participants_1` (`user_id`, `first_joined_at`),
  CONSTRAINT `fk_chat_participants_1` FOREIGN KEY (`room_id`) REFERENCES `chat_rooms` (`id`),
  CONSTRAINT `fk_chat_participants_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_chat_participants_stamp` CHECK ((`stamp_recorded_at` IS NULL AND `stamp_trigger_type` IS NULL) OR (`stamp_recorded_at` IS NOT NULL AND `stamp_trigger_type` IS NOT NULL AND `stamp_trigger_type` IN ('JOIN','MESSAGE')))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='채팅 참여·자동 도장';

-- 종목 가격 차트 집계 | RM05; 1m/5m/1h/1d 재생성 가능한 읽기 모델
CREATE TABLE `stock_price_candles` (
  `stock_id` BIGINT UNSIGNED NOT NULL,
  `interval_seconds` INT UNSIGNED NOT NULL,
  `bucket_at` DATETIME(6) NOT NULL,
  `open_price` BIGINT NOT NULL,
  `high_price` BIGINT NOT NULL,
  `low_price` BIGINT NOT NULL,
  `close_price` BIGINT NOT NULL,
  `volume` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`stock_id`, `interval_seconds`, `bucket_at`),
  CONSTRAINT `fk_stock_price_candles_1` FOREIGN KEY (`stock_id`) REFERENCES `market_stocks` (`id`),
  CONSTRAINT `ck_stock_price_candles_interval` CHECK (`interval_seconds` IN (60,300,3600,86400)),
  CONSTRAINT `ck_stock_price_candles_prices` CHECK (`low_price` > 0 AND `high_price` >= `low_price` AND `open_price` BETWEEN `low_price` AND `high_price` AND `close_price` BETWEEN `low_price` AND `high_price`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='종목 가격 차트 집계';

-- 매수 매도 견적 | RM06; 견적은 잔고 예약 아님
CREATE TABLE `trade_quotes` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `user_id` BIGINT UNSIGNED NOT NULL,
  `stock_id` BIGINT UNSIGNED NOT NULL,
  `side` VARCHAR(8) NOT NULL,
  `quantity` BIGINT UNSIGNED NOT NULL,
  `unit_prices` JSON NOT NULL,
  `total_amount` BIGINT NOT NULL,
  `fee` BIGINT NOT NULL DEFAULT 0,
  `average_unit_price` DECIMAL(28,8) NOT NULL,
  `price_after` BIGINT NOT NULL,
  `price_version` BIGINT UNSIGNED NOT NULL,
  `expires_at` DATETIME(6) NOT NULL,
  `used_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_trade_quotes_1` (`id`, `user_id`, `stock_id`),
  KEY `ix_trade_quotes_1` (`expires_at`),
  CONSTRAINT `fk_trade_quotes_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_trade_quotes_2` FOREIGN KEY (`stock_id`) REFERENCES `market_stocks` (`id`),
  CONSTRAINT `ck_trade_quotes_side` CHECK (`side` IN ('BUY','SELL')),
  CONSTRAINT `ck_trade_quotes_amount` CHECK (`quantity` > 0 AND `total_amount` > 0 AND `fee` >= 0),
  CONSTRAINT `ck_trade_quotes_units` CHECK (JSON_TYPE(`unit_prices`)='ARRAY' AND JSON_LENGTH(`unit_prices`)=`quantity`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='매수 매도 견적';

-- 현재 보유 수량 및 원가 | RM08; 거래/정산과 같은 트랜잭션에서 갱신
CREATE TABLE `positions` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `stock_id` BIGINT UNSIGNED NOT NULL,
  `quantity` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `total_cost` BIGINT NOT NULL DEFAULT 0,
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  PRIMARY KEY (`user_id`, `stock_id`),
  CONSTRAINT `fk_positions_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_positions_2` FOREIGN KEY (`stock_id`) REFERENCES `market_stocks` (`id`),
  CONSTRAINT `ck_positions_cost` CHECK (`total_cost` >= 0 AND (`quantity`>0 OR `total_cost`=0))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='현재 보유 수량 및 원가';

-- 라운드 기준 자산·회원 정산 | RM08, RM10~12; 첫 참여시 PENDING 기준 자산 저장, 정산시 같은 행 완료. 정정은 새 버전
CREATE TABLE `user_settlements` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `round_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `round_start_assets` BIGINT NULL,
  `ending_assets` BIGINT NULL,
  `round_pnl` BIGINT NULL,
  `settlement_proceeds` BIGINT NULL,
  `settlement_pnl` BIGINT NULL,
  `return_rate` DECIMAL(20,8) NULL,
  `round_rank` INT UNSIGNED NULL,
  `season_rank` INT UNSIGNED NULL,
  `season_return_rate` DECIMAL(20,8) NULL,
  `completed_at` DATETIME(6) NULL,
  `published_at` DATETIME(6) NULL,
  `settlement_version` INT UNSIGNED NOT NULL DEFAULT 1,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `joined_at` DATETIME(6) NOT NULL,
  `rule_snapshot` JSON NULL COMMENT '정산 시작 전 규칙/결과 출처를 고정; 과거 버전 보존',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_settlements_1` (`round_id`, `user_id`, `settlement_version`),
  UNIQUE KEY `uq_user_settlements_2` (`id`, `user_id`, `round_id`, `settlement_version`),
  KEY `ix_user_settlements_1` (`user_id`, `completed_at`, `id`),
  CONSTRAINT `fk_user_settlements_1` FOREIGN KEY (`round_id`) REFERENCES `market_rounds` (`id`),
  CONSTRAINT `fk_user_settlements_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_user_settlements_version` CHECK (`settlement_version`>0),
  CONSTRAINT `ck_user_settlements_status` CHECK (`status` IN ('PENDING','COMPLETED','FAILED','SUPERSEDED')),
  CONSTRAINT `ck_user_settlements_baseline` CHECK (`round_start_assets` IS NULL OR `round_start_assets`>=0),
  CONSTRAINT `ck_user_settlements_assets` CHECK ((`ending_assets` IS NULL OR `ending_assets`>=0) AND (`settlement_proceeds` IS NULL OR `settlement_proceeds`>=0)),
  CONSTRAINT `ck_user_settlements_complete` CHECK (`status`<>'COMPLETED' OR (`ending_assets` IS NOT NULL AND `round_pnl` IS NOT NULL AND `settlement_proceeds` IS NOT NULL AND `settlement_pnl` IS NOT NULL AND `completed_at` IS NOT NULL AND `rule_snapshot` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='라운드 기준 자산·회원 정산';

-- 랭킹 스냅샷 회원 순위 | RM12, MG03; 동률 가능하여 rank는 UNIQUE 아님
CREATE TABLE `ranking_entries` (
  `snapshot_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `rank` INT UNSIGNED NOT NULL,
  `total_assets` BIGINT NOT NULL,
  `round_pnl` BIGINT NULL,
  `return_rate` DECIMAL(20,8) NULL,
  `rank_gain` INT NULL,
  PRIMARY KEY (`snapshot_id`, `user_id`),
  KEY `ix_ranking_entries_1` (`snapshot_id`, `rank`, `user_id`),
  KEY `ix_ranking_entries_2` (`user_id`, `snapshot_id`),
  CONSTRAINT `fk_ranking_entries_1` FOREIGN KEY (`snapshot_id`) REFERENCES `ranking_snapshots` (`id`),
  CONSTRAINT `fk_ranking_entries_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_ranking_entries_rank` CHECK (`rank`>0 AND `total_assets`>=0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='랭킹 스냅샷 회원 순위';

-- 업로드 이미지 | CM09, TR04~06; 사용자 첨부 사진만 저장. 업로드 후 소유권 확인하여 연결; 최대 5장
CREATE TABLE `media_files` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `owner_id` BIGINT UNSIGNED NOT NULL,
  `object_key` VARCHAR(255) NOT NULL,
  `purpose` VARCHAR(24) NOT NULL DEFAULT 'POST_IMAGE',
  `content_type` VARCHAR(80) NOT NULL,
  `size_bytes` BIGINT UNSIGNED NOT NULL,
  `width` INT UNSIGNED NOT NULL,
  `height` INT UNSIGNED NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'UPLOADED',
  `expires_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `deleted_at` DATETIME(6) NULL COMMENT '논리 삭제; 응답에서 본문/작성자 숨김',
  `post_id` BIGINT UNSIGNED NULL,
  `position` TINYINT UNSIGNED NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_media_files_1` (`object_key`),
  UNIQUE KEY `uq_media_files_2` (`post_id`, `position`),
  KEY `ix_media_files_1` (`status`, `expires_at`),
  CONSTRAINT `fk_media_files_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_media_files_2` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`),
  CONSTRAINT `ck_media_files_size` CHECK (`size_bytes` > 0 AND `width` > 0 AND `height` > 0),
  CONSTRAINT `ck_media_files_purpose` CHECK (`purpose`='POST_IMAGE'),
  CONSTRAINT `ck_media_files_status` CHECK (`status` IN ('UPLOADED','ATTACHED','DELETED')),
  CONSTRAINT `ck_media_files_attachment` CHECK ((`post_id` IS NULL AND `position` IS NULL) OR (`post_id` IS NOT NULL AND `position` IS NOT NULL AND `position` BETWEEN 1 AND 5)),
  CONSTRAINT `ck_media_files_attached` CHECK (`status`<>'ATTACHED' OR `post_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='업로드 이미지';

-- 댓글 및 한 단계 답글 | TR08~11; 부모가 루트이고 미삭제인지 서비스에서 검사
CREATE TABLE `comments` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `post_id` BIGINT UNSIGNED NOT NULL,
  `author_id` BIGINT UNSIGNED NOT NULL,
  `parent_id` BIGINT UNSIGNED NULL,
  `content` VARCHAR(1000) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  `deleted_at` DATETIME(6) NULL COMMENT '논리 삭제; 응답에서 본문/작성자 숨김',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_comments_1` (`post_id`, `id`),
  KEY `ix_comments_1` (`post_id`, `created_at`, `id`),
  KEY `ix_comments_2` (`parent_id`, `created_at`, `id`),
  CONSTRAINT `fk_comments_1` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`),
  CONSTRAINT `fk_comments_2` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_comments_3` FOREIGN KEY (`post_id`, `parent_id`) REFERENCES `comments` (`post_id`, `id`),
  CONSTRAINT `ck_comments_content` CHECK (`deleted_at` IS NOT NULL OR (`content` IS NOT NULL AND CHAR_LENGTH(TRIM(`content`)) > 0))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='댓글 및 한 단계 답글';

-- 게시글 조회 중복 방지 | TR04 조회수 기술 초안; 마지막 집계부터 10분
CREATE TABLE `post_view_dedup` (
  `post_id` BIGINT UNSIGNED NOT NULL,
  `visitor_hash` BINARY(32) NOT NULL COMMENT '회원/익명 세션 식별자의 해시; IP 원문 불필요',
  `last_counted_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`post_id`, `visitor_hash`),
  KEY `ix_post_view_dedup_1` (`last_counted_at`),
  CONSTRAINT `fk_post_view_dedup_1` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='게시글 조회 중복 방지';

-- 채팅 메시지 | TR19~21, TR25; 같은 clientMessageId의 본문 해시 비교
CREATE TABLE `chat_messages` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `room_id` BIGINT UNSIGNED NOT NULL,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `client_message_id` CHAR(36) NOT NULL,
  `request_hash` BINARY(32) NOT NULL,
  `content` VARCHAR(500) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `deleted_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_chat_messages_1` (`room_id`, `sender_id`, `client_message_id`),
  KEY `ix_chat_messages_1` (`room_id`, `created_at`, `id`),
  CONSTRAINT `fk_chat_messages_1` FOREIGN KEY (`room_id`, `sender_id`) REFERENCES `chat_participants` (`room_id`, `user_id`),
  CONSTRAINT `ck_chat_messages_content` CHECK (`deleted_at` IS NOT NULL OR (`content` IS NOT NULL AND CHAR_LENGTH(TRIM(`content`)) > 0))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='채팅 메시지';

-- 주문 요청과 확정 체결 | RM07, RM09; 실패 응답도 보존하며 같은 키+본문 최초 응답 재사용. 정산 주문은 quote 없음
CREATE TABLE `trade_orders` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `user_id` BIGINT UNSIGNED NOT NULL,
  `stock_id` BIGINT UNSIGNED NULL,
  `quote_id` BIGINT UNSIGNED NULL,
  `idempotency_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `request_hash` BINARY(32) NULL,
  `request_body` JSON NULL,
  `type` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `quantity` BIGINT UNSIGNED NULL,
  `unit_prices` JSON NULL,
  `total_amount` BIGINT NULL,
  `fee` BIGINT NULL,
  `allocated_cost` BIGINT NULL,
  `realized_pnl` BIGINT NULL,
  `cash_balance_after` BIGINT NULL,
  `holding_quantity_after` BIGINT UNSIGNED NULL,
  `price_after` BIGINT NULL,
  `price_version_after` BIGINT UNSIGNED NULL,
  `response_status` SMALLINT UNSIGNED NULL,
  `response_body` JSON NULL,
  `executed_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_trade_orders_1` (`user_id`, `idempotency_key`),
  UNIQUE KEY `uq_trade_orders_2` (`id`, `user_id`, `stock_id`),
  UNIQUE KEY `uq_trade_orders_3` (`id`, `user_id`),
  KEY `ix_trade_orders_1` (`user_id`, `status`, `executed_at`, `id`),
  KEY `ix_trade_orders_2` (`quote_id`),
  CONSTRAINT `fk_trade_orders_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_trade_orders_2` FOREIGN KEY (`stock_id`) REFERENCES `market_stocks` (`id`),
  CONSTRAINT `fk_trade_orders_3` FOREIGN KEY (`quote_id`, `user_id`, `stock_id`) REFERENCES `trade_quotes` (`id`, `user_id`, `stock_id`),
  CONSTRAINT `ck_trade_orders_type` CHECK (`type` IN ('BUY','SELL','SETTLEMENT')),
  CONSTRAINT `ck_trade_orders_status` CHECK (`status` IN ('PENDING','EXECUTED','FAILED')),
  CONSTRAINT `ck_trade_orders_request` CHECK (`type`='SETTLEMENT' OR (`idempotency_key` IS NOT NULL AND `request_hash` IS NOT NULL AND `request_body` IS NOT NULL)),
  CONSTRAINT `ck_trade_orders_filled` CHECK (`status`<>'EXECUTED' OR (`stock_id` IS NOT NULL AND `quantity` IS NOT NULL AND `quantity`>0 AND `unit_prices` IS NOT NULL AND `total_amount` IS NOT NULL AND `total_amount`>0 AND `fee` IS NOT NULL AND `fee`>=0 AND `executed_at` IS NOT NULL AND `cash_balance_after` IS NOT NULL AND `cash_balance_after`>=0 AND `holding_quantity_after` IS NOT NULL)),
  CONSTRAINT `ck_trade_orders_quote` CHECK (`status`<>'EXECUTED' OR `type`='SETTLEMENT' OR `quote_id` IS NOT NULL),
  CONSTRAINT `ck_trade_orders_units` CHECK (`unit_prices` IS NULL OR (JSON_TYPE(`unit_prices`)='ARRAY' AND JSON_LENGTH(`unit_prices`)=`quantity`))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='주문 요청과 확정 체결';

-- 글 좋아요·스크랩 | TR12~15, MG04
CREATE TABLE `post_reactions` (
  `post_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `type` VARCHAR(16) NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`post_id`, `user_id`, `type`),
  KEY `ix_post_reactions_1` (`user_id`, `type`, `created_at`, `post_id`),
  CONSTRAINT `fk_post_reactions_1` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`),
  CONSTRAINT `fk_post_reactions_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_post_reactions_type` CHECK (`type` IN ('LIKE','BOOKMARK'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='글 좋아요·스크랩';

-- 댓글 좋아요 | TR12~15, TR23~24, MG04
CREATE TABLE `comment_likes` (
  `comment_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`comment_id`, `user_id`),
  KEY `ix_comment_likes_1` (`user_id`, `created_at`, `comment_id`),
  CONSTRAINT `fk_comment_likes_1` FOREIGN KEY (`comment_id`) REFERENCES `comments` (`id`),
  CONSTRAINT `fk_comment_likes_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='댓글 좋아요';

-- 게시글 댓글 메시지 신고 | TR16; 운영 제재 정책은 미확정, 자동 삭제 아님
CREATE TABLE `reports` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `reporter_id` BIGINT UNSIGNED NOT NULL,
  `target_type` VARCHAR(24) NOT NULL,
  `post_id` BIGINT UNSIGNED NULL,
  `comment_id` BIGINT UNSIGNED NULL,
  `message_id` BIGINT UNSIGNED NULL,
  `reason` VARCHAR(24) NOT NULL,
  `description` VARCHAR(1000) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 변경 시각; 애플리케이션 갱신',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_reports_1` (`reporter_id`, `post_id`, `reason`),
  UNIQUE KEY `uq_reports_2` (`reporter_id`, `comment_id`, `reason`),
  UNIQUE KEY `uq_reports_3` (`reporter_id`, `message_id`, `reason`),
  CONSTRAINT `fk_reports_1` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_reports_2` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`),
  CONSTRAINT `fk_reports_3` FOREIGN KEY (`comment_id`) REFERENCES `comments` (`id`),
  CONSTRAINT `fk_reports_4` FOREIGN KEY (`message_id`) REFERENCES `chat_messages` (`id`),
  CONSTRAINT `ck_reports_target` CHECK ((`target_type`='POST' AND `post_id` IS NOT NULL AND `comment_id` IS NULL AND `message_id` IS NULL) OR (`target_type`='COMMENT' AND `post_id` IS NULL AND `comment_id` IS NOT NULL AND `message_id` IS NULL) OR (`target_type`='CHAT_MESSAGE' AND `post_id` IS NULL AND `comment_id` IS NULL AND `message_id` IS NOT NULL)),
  CONSTRAINT `ck_reports_reason` CHECK (`reason` IN ('ABUSE','SPAM','INAPPROPRIATE','OTHER')),
  CONSTRAINT `ck_reports_description` CHECK (`reason`<>'OTHER' OR (`description` IS NOT NULL AND CHAR_LENGTH(TRIM(`description`))>0)),
  CONSTRAINT `ck_reports_status` CHECK (`status` IN ('RECEIVED','REVIEWED','DISMISSED','ACTIONED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='게시글 댓글 메시지 신고';

-- 코인 증감 원장 | AU01, RM07~11; 최초 지급/정산 중복 방지 키, 수정 대신 상쇄 원장
CREATE TABLE `coin_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK, AUTO_INCREMENT',
  `user_id` BIGINT UNSIGNED NOT NULL,
  `order_id` BIGINT UNSIGNED NULL,
  `event_key` VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `entry_type` VARCHAR(24) NOT NULL,
  `amount_delta` BIGINT NOT NULL,
  `balance_after` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_coin_ledger_1` (`user_id`, `event_key`),
  UNIQUE KEY `uq_coin_ledger_2` (`order_id`),
  KEY `ix_coin_ledger_1` (`user_id`, `created_at`, `id`),
  CONSTRAINT `fk_coin_ledger_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_coin_ledger_2` FOREIGN KEY (`order_id`, `user_id`) REFERENCES `trade_orders` (`id`, `user_id`),
  CONSTRAINT `ck_coin_ledger_amount` CHECK (`amount_delta` <> 0 AND `balance_after` >= 0),
  CONSTRAINT `ck_coin_ledger_type` CHECK (`entry_type` IN ('INITIAL_GRANT','BUY','SELL','SETTLEMENT','CORRECTION')),
  CONSTRAINT `ck_coin_ledger_sign` CHECK ((`entry_type`='BUY' AND `amount_delta`<0) OR (`entry_type` IN ('INITIAL_GRANT','SELL','SETTLEMENT') AND `amount_delta`>0) OR `entry_type`='CORRECTION')
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='코인 증감 원장';

-- 회원 종목별 청산 이력 | RM11; 계산·가격·결과 버전을 행에 보존. 동일 사용자/종목/버전 중복 청산 차단
CREATE TABLE `user_settlement_items` (
  `user_settlement_id` BIGINT UNSIGNED NOT NULL,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `stock_id` BIGINT UNSIGNED NOT NULL,
  `quantity` BIGINT UNSIGNED NOT NULL,
  `total_cost` BIGINT NOT NULL,
  `proceeds` BIGINT NOT NULL,
  `pnl` BIGINT NOT NULL,
  `round_id` BIGINT UNSIGNED NOT NULL,
  `settlement_version` INT UNSIGNED NOT NULL,
  `race_result_id` BIGINT UNSIGNED NULL,
  `base_rank` SMALLINT UNSIGNED NOT NULL,
  `finish_rank` SMALLINT UNSIGNED NULL,
  `position_score` INT NOT NULL,
  `result_bonus` INT NOT NULL,
  `final_score` INT NOT NULL,
  `starting_price` BIGINT NOT NULL,
  `settlement_price` BIGINT NOT NULL,
  PRIMARY KEY (`user_settlement_id`, `stock_id`),
  UNIQUE KEY `uq_user_settlement_items_1` (`order_id`),
  UNIQUE KEY `uq_user_settlement_items_2` (`round_id`, `user_id`, `stock_id`, `settlement_version`),
  CONSTRAINT `fk_user_settlement_items_1` FOREIGN KEY (`user_settlement_id`, `user_id`, `round_id`, `settlement_version`) REFERENCES `user_settlements` (`id`, `user_id`, `round_id`, `settlement_version`),
  CONSTRAINT `fk_user_settlement_items_2` FOREIGN KEY (`stock_id`, `round_id`) REFERENCES `market_stocks` (`id`, `round_id`),
  CONSTRAINT `fk_user_settlement_items_3` FOREIGN KEY (`order_id`, `user_id`, `stock_id`) REFERENCES `trade_orders` (`id`, `user_id`, `stock_id`),
  CONSTRAINT `fk_user_settlement_items_4` FOREIGN KEY (`race_result_id`) REFERENCES `race_results` (`id`),
  CONSTRAINT `ck_user_settlement_items_amount` CHECK (`quantity`>0 AND `total_cost`>=0 AND `proceeds`>0 AND `pnl`=`proceeds`-`total_cost`),
  CONSTRAINT `ck_user_settlement_items_score` CHECK (`final_score` BETWEEN -30 AND 40),
  CONSTRAINT `ck_user_settlement_items_price` CHECK (`starting_price`>0 AND `settlement_price`>0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='회원 종목별 청산 이력';
