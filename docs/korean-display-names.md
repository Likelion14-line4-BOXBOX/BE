# 한국어 표시명 (#61)

기존 이름 필드가 한국어를 우선 반환한다. 번역이 NULL 또는 공백이면 기존 이름을 사용하므로 FE는 기존 name/teamName/raceName/boardName/driverName을 그대로 표시한다. 이전 #61의 nameKo 계열 응답 필드는 제거했다.

| 응답 대상 | 표시 필드 | 보존 원문 필드 |
| --- | --- | --- |
| CM01·CM02 경기, HM01 nextRace | name | nameEn |
| CM02 sessions, HM01 nextSession | name | nameEn |
| CM04 팀·선수, MG01·MG02·MG03 최애 | name | nameEn |
| MG01·MG02·MG03 최애 선수 소속 팀 | teamName | teamNameEn |
| TR17·TR18 채팅방, MG09 도장판, HM01 liveChatRoom | raceName | raceNameEn |
| TR01 게시판, TR02 태그, TR03·TR04·TR22·MG04·HM01 게시글 태그 | name | DB 원문 보존 |
| TR22·HM01 게시판 | boardName | DB 원문 보존 |
| RM01·RM02·RM10·HM01 마켓 경기 | raceName | DB 원문 보존 |
| RM03·RM04·RM08 선수·소속 팀 | name, teamName | DB 원문 보존 |
| RM11 정산 선수 | driverName | DB/정산 스냅샷 원문 보존 |

nameEn 계열은 저장된 원문을 반환한다. 이름 값 대신 고유 ID·코드로 이미지 매핑·선택·이동·필터를 처리한다. 부모 최애/경기/세션 객체가 없으면 기존대로 null이다. 일반 주제 태그의 name은 그대로 유지한다. 팀 게시판은 연결 팀의 한글 이름에 “ 게시판”을 붙이며 번역이 없으면 게시판의 기존 이름을 반환한다. 태그 원문과 한국어 이름을 모두 검색할 수 있다.

세션은 종류·번호로 프리 프랙티스 1~3, 예선, 스프린트 예선, 스프린트, 본선으로 변환한다. 이미 한글인 이름과 알 수 없는 종류/번호는 원문을 유지한다. TR19·TR21의 자동 구분선도 같은 규칙을 쓰고 사용자 지정 상황 문구는 유지한다. 저장된 세션/이벤트 원문·실제 전환 시각·markerId는 바꾸지 않는다.

정산 선수의 표시명은 현재 한글 표기를 우선한다. 번역이 없으면 정산 시 고정한 원문, 그 기록도 없으면 DB 원문을 반환한다. 계산 규칙·스냅샷·가격·수량·손익은 변경하지 않는다.

## DB 적용과 배포

DB name은 원문이며 nullable teams.name_ko, drivers.name_ko, races.name_ko에 한국어를 저장한다. 원문을 한글로 덮어쓰거나 팀/선수/게시판/태그를 재생성하지 않는다. 기존 OpenF1 동기화는 name_ko를 갱신하지 않는다.

1. 기존 DB를 백업하고 deploy/db/migrations/061-korean-display-names.sql을 실행한다. MySQL DDL은 암묵적으로 COMMIT되므로 앱보다 먼저 적용한다. 이미 있는 컬럼은 재추가하지 않는다.
2. deploy/db/korean-name-seed.sql을 실행한다. 신규 DB는 schema.sql과 기준정보를 먼저 적용한다. NULL인 번역만 채우며 기존 한글은 보존한다.
3. 백엔드를 배포하고 기존 이름 필드의 한글/원문 대체 표시·En 원문·ID를 확인한다.
4. FE가 캐시한 기존 기준정보를 갱신한다. 기존 표시 필드에 별도 Ko 대체 표시 로직은 필요하지 않다.

시드는 제공된 팀11·선수25의 코드와 2026 경기23개의 시즌·라운드·external_key를 기준으로 한다. 다른 시즌/알 수 없는 코드는 임의 번역하지 않는다. 표기 수정은 검토한 별도 UPDATE로 한다. 한글 표기는 프로젝트 표시명이며 라인업 갱신을 뜻하지 않는다.

2026-11-18 운영 MySQL8.4.13에 컬럼3개와 팀11·선수25·경기23의 한글 입력을 완료했다. 원래 기준/게시판/태그 값과 전체32테이블건수 보존 및 재실행 불변을 확인했다. 운영 백업·검토 SQL·결과는 /opt/boxbox/korean-names-61-20261118-display에 보관했다. 이 작업은 앱 재배포와 구분한다.

## 검증

Java21 전체 테스트는 MARKET_PREPARATION_ENABLED=false에서 아래 명령으로 실행한다.

    .\gradlew.bat cleanTest test bootJar --offline --console=plain

격리 MySQL8의 datadir가 프로젝트 build/verification/mysql-20261113, 포트가 127.0.0.1:13306일 때 아래 검증을 실행한다. root 비밀번호 없는 해당 격리 인스턴스만 허용하며 실행마다 새 DB를 만든다.

    python scripts/verify-korean-names.py --reference-seed 'C:/Users/prett/Documents/카카오톡 받은 파일/boxbox-market-reference-seed.sql' --evidence '../../ai-notes/verification/issue-61-display-name-http-expanded-2026-11-18.json'

기존/신규 DB·마이그레이션/시드 반복·원문/관계 보존과 실제 가입/로그인 토큰의 프로필·개러지·게시판·태그·게시글·검색·채팅방·도장판·홈·경기/세션·기준정보·마켓/보유자산/정산 응답을 검사한다. 정상·필터·빈 목록·오류·NULL/공백 대체 표시를 확인하고 토큰/비밀번호는 증거에서 제외한다. 임시 서버는 종료하며 검증 DB는 자동 삭제하지 않는다.
