# 한국어 표시명 (#61)

팀 라디오·마이 개러지에서 영문 원본을 유지하고 한국어 표시명을 함께 반환한다.

| 응답 대상 | 추가 필드 | 출처 |
| --- | --- | --- |
| MG01·MG02·MG03 최애 팀 | nameKo | teams.name_ko |
| MG01·MG02·MG03 최애 선수 | nameKo, teamNameKo | drivers.name_ko, 소속 teams.name_ko |
| TR17·TR18 채팅방, MG09 도장판 | raceNameKo | races.name_ko |
| TR01 팀 게시판 | nameKo | 연결 팀의 name_ko + “ 게시판” |
| TR02 팀·선수 태그 | nameKo | 팀/선수 ID의 한국어 이름 |
| TR03·TR04·TR22·MG04 게시글 태그 | nameKo | TR02와 동일 |
| TR22 검색 결과 게시판 | boardNameKo | TR01과 동일 |
| HM01 추천 글의 태그 | nameKo | 공유 게시글 DTO를 통해 동일하게 반환 |

한국어가 없으면 새 키를 유지하고 값은 null로 반환한다. 일반 주제 태그도 nameKo=null이며 기존 한글 name을 그대로 표시한다. 부모 최애 객체가 null이면 객체 자체는 기존대로 null이다.

FE는 `nameKo ?? name`, `teamNameKo ?? teamName`, `raceNameKo ?? raceName`, `boardNameKo ?? boardName`으로 표시한다. 이미지 매핑·선택·화면 이동·필터에는 기존 고유 ID와 코드를 사용한다. 한국어 태그 이름도 검색할 수 있으며 기존 태그명 검색·필터·커서는 유지된다.

게시판/태그 원래 이름을 UPDATE하거나 항목을 재생성하지 않는다. 한국어 기준정보가 바뀌면 다음 조회에 반영되며 태그 ID, 게시글 연결과 보관 상태를 유지한다.

## DB 적용 순서

1. 기존 DB는 백업 후 `deploy/db/migrations/061-korean-display-names.sql`을 해당 DB에서 실행한다. MySQL DDL은 암묵적으로 COMMIT하므로 애플리케이션 배포보다 먼저 적용한다. 재실행해도 이미 있는 컬럼은 추가하지 않는다.
2. `deploy/db/korean-name-seed.sql`로 한국어가 NULL인 기존 기준정보만 채운다. 신규 DB는 먼저 `deploy/db/schema.sql`과 팀·선수·경기 기준정보를 적용한다.
3. 백엔드를 배포하고 응답의 한글 값·NULL·원래 이름과 ID를 확인한다.
4. FE가 한국어 우선 표시를 적용하고 기존 캐시를 갱신한다.

이름 시드는 제공된 팀11·선수25 코드와 2026 경기23개의 시즌·라운드·external_key를 기준으로 한다. 다른 시즌이나 알 수 없는 코드는 임의 번역하지 않는다. 기존 한국어 값은 보존하며 표기 수정은 검토한 별도 UPDATE로 한다. 시드의 한글 표기는 이번 구현의 표시명 제안이며 공식 번역본이나 라인업 갱신을 뜻하지 않는다.

OpenF1 동기화가 영문 이름을 갱신하더라도 name_ko를 쓰지 않도록 분리한다. 이번 범위에는 홈/공통/마켓의 독립 응답에 한국어 필드를 확장하는 작업과 세션명 변환은 포함하지 않는다. HM01의 공유 게시글 태그에 추가되는 필드는 위 표에 명시했다.

## 검증 실행

Java21 전체 테스트: `.\gradlew.bat cleanTest test bootJar --console=plain` (`MARKET_PREPARATION_ENABLED=false`). 의존성을 이미 받은 환경에서는 `--offline`을 추가할 수 있다.

격리 MySQL8이 프로젝트 `build/verification/mysql-20261113`의 datadir와 127.0.0.1:13306에서 실행 중일 때 다음 검증을 수행한다. root 비밀번호 없는 로컬 격리 인스턴스만 허용하며 운영/공통 DB에는 연결하지 않는다. 검증용 DB는 실행마다 새로 만들고 자동 삭제하지 않는다.

```powershell
python scripts/verify-korean-names.py --reference-seed 'C:/Users/prett/Documents/카카오톡 받은 파일/boxbox-market-reference-seed.sql' --evidence '../../ai-notes/verification/issue-61-korean-names-http-2026-11-18.json'
```

기존 DB 마이그레이션 반복, 신규/기존 스키마 일치, 원문/관계 보존, 이름 시드 반복·기존 한국어 보존·시즌/키 불일치, 실제 가입/로그인 토큰의 프로필·게시판·태그·게시글·검색·내 활동·채팅방·도장판·오류 응답을 검사한다. 토큰/비밀번호는 증거에서 제외하며 임시 서버는 종료한다.
