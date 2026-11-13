# 게시글 목록·검색·아카이브 (#12)

[이슈 #12](https://github.com/Likelion14-line4-BOXBOX/BE/issues/12)의 TR03과 TR22를 기존 `com.boxbox.radio.board.post`에 구현했다. 기존 CRUD·DB 스키마·API 명세의 경로와 요청/응답 필드는 변경하지 않았다.

| API | 응답 |
| --- | --- |
| `GET /api/boards/{boardId}/posts` | items, nextCursor, hasNext, totalCount, popularPost |
| `GET /api/posts` | items, totalCount, nextCursor, hasNext. 항목마다 boardName/isArchived 추가 |

## 브랜치·연동

`feat/#12-post-search`는 #10의 `e345ab5`에서 분기했다. PR 대상은 dev이며 선행 [PR #11](https://github.com/Likelion14-line4-BOXBOX/BE/pull/11)이 미병합이면 기반 커밋도 함께 보일 수 있다. 선행 병합 방식에 맞춰 기준과 diff를 정리한 뒤 이 PR을 병합한다. 프로필 #8에는 의존하지 않는다.

기존 [게시글 실행 안내](post-api.md)의 Java 21·MySQL 8·DB_URL/DB_USERNAME/DB_PASSWORD·POST_MEDIA_BASE_URL을 사용한다. 운영 스키마와 데이터는 자동 생성하지 않는다. 추가 테이블·외부 F1 호출·로그인·업로드·댓글/반응 변경 API를 만들지 않았다.

인증 선택 조회이며, 검증된 서버 Principal.name=loginId라는 기존 어댑터 가정을 재사용한다. 실제 인증 담당 규약과 연결해야 한다. 유효한 회원은 기존 LIKE/BOOKMARK 관계로 개인 상태를 계산하며 비회원은 null이다. 미검증 Bearer는 401, 사용자 ID 헤더만 있는 요청은 비회원이다. 정지·탈퇴·삭제·없는 회원을 비회원으로 낮춰 처리하지 않는다.

## 필터·정렬

- q는 strip 후 1~100 코드 포인트이며 제목·본문·태그명을 검색한다. `%`, `_`, `!`는 LIKE 와일드카드가 아닌 입력 문자로 처리하고 SQL 값은 바인딩한다. 검색 대소문자 처리에는 LOWER를 적용하며 최종 문자 비교는 DB collation을 따른다.
- tagId는 실제 태그여야 하고 boardId가 있으면 같은 게시판 태그여야 한다. 위반은 400 INVALID_REQUEST다. 비활성 태그도 검색 조건으로 전달할 수 있지만 ACTIVE 범위에는 포함되지 않는다.
- 활성 게시판의 미삭제 글만 조회한다. 지정 게시판이 없거나 비활성이면 404 BOARD_NOT_FOUND다. 필터에 일치하는 글이 없으면 200/빈 목록이다.
- TR03은 ACTIVE 목록이다. TR22의 ACTIVE는 태그 없음/활성 태그, ARCHIVE는 비활성 태그 글이다. 아카이브 원문과 과거 태그명을 보존하며 삭제 글 보관함으로 사용하지 않는다.
- LATEST는 생성 시각·ID 내림차순, OLDEST는 둘 다 오름차순이다. POPULAR는 좋아요 수 내림차순, COMMENTS는 유효 댓글 수 내림차순이며 동률은 생성 시각·ID 내림차순이다. 삭제 댓글은 집계하지 않는다.
- period는 ALL/DAY/WEEK/MONTH/YEAR이며 최초 조회 UTC 시각에서 하루·일주일·달력 기준 한 달·일 년을 뺀 시각 이후를 포함한다. 첫 요청에서 생략하면 ALL이다.
- size는 기본 20, 1~100이다. 알 수 없는 조건, 잘못된 enum/ID/size/q/cursor는 공통 400 응답이다.

## 요약·인기 글

기존 상세와 동일하게 작성자 공개 프로필·태그·첫 유효 첨부 썸네일·댓글/좋아요/스크랩 수·저장 조회수를 반환한다. 미리보기는 기존 구현의 앞 100 코드 포인트다. 전체 검색에서는 PostSummary를 중첩하지 않고 boardName/isArchived를 같은 항목에 추가한다.

자유·입문자 게시판의 popularPost는 같은 필터에 해당하는 글 중 좋아요 최다, 동률이면 최신 시각·ID 우선이다. 팀 게시판과 빈 결과에는 null이다. 인기 글은 items에서 제거하지 않는다. totalCount는 커서 이후 남은 수가 아닌 동일 검색 조건 전체 수다.

조회수 증가용 상세 서비스를 목록 조합에 호출하지 않는다. 목록·인기 미리보기는 조회수·수정 시각·중복 조회 테이블을 변경하지 않는 읽기 전용 트랜잭션이다. 한 응답 안에서는 REPEATABLE_READ로 집계/페이지/인기 글의 읽기 일관성을 유지한다. 집계와 회원별 상태는 페이지 SQL의 서브쿼리로 읽고 항목마다 별도 Repository 호출을 하지 않는다.

첨부 조회 주소가 필요한 반환 항목에서 POST_MEDIA_BASE_URL/저장 키가 잘못돼 있으면 기존과 같은 503 MEDIA_SERVICE_UNAVAILABLE이다. 다음 페이지 존재 확인용 추가 한 행의 썸네일은 해석하지 않는다. URL 생성은 실제 파일 업로드·저장소 조회 완료와 다르다.

## 커서

페이지는 정렬 값·생성 시각·ID의 keyset 방식이다. 커서에는 버전·검색 조건 지문·첫 조회 시각·첫 조회의 글 ID 상한·마지막 정렬 위치를 담는다. 이를 함께 검증하며 endpoint/boardId/scope/q/tagId/sort/period/size를 바꾸면 400이다. q의 앞뒤 공백과 숫자 ID 표기는 정규화한다. 마지막 페이지는 hasNext=false/nextCursor=null이다.

첫 시각과 ID 상한을 다음 페이지에서도 유지해, 같은 시각으로 기록된 후속 AUTO_INCREMENT 글도 제외한다. 커서 경계 글이 삭제돼도 토큰의 정렬 위치로 다음 페이지를 조회한다. 커서는 위치 정보이며 서명된 권한 증명이 아니다. 매 요청의 인증·검색 범위는 별도로 검사하고 모든 SQL 값은 바인딩한다.

전체 페이지를 하나의 영구 DB 스냅샷으로 보존하지는 않는다. 페이지 사이 기존 글의 좋아요/댓글 수·내용·태그·삭제 상태가 바뀌면 정렬/검색 결과와 totalCount가 달라질 수 있다. 정렬 점수가 변하는 동안의 완전한 순위 고정은 별도 스냅샷 계약과 저장 구조가 필요한 범위다.

## 검증

기본 H2 실행:

```powershell
.\gradlew.bat cleanTest test --offline --console=plain
```

MySQL 검색 테스트는 기존 CRUD 검증 DB를 초기화하지 않고, 별도의 `127.0.0.1:13306/boxbox_search_verify`를 사용한다. 비어 있는 로컬 격리 DB에 루트 `docs/erd/schema.mysql.all.sql`을 먼저 적용한다. 공통 DB에서 실행하지 않는다. 픽스처는 전용 DB의 관련 테이블을 초기화한다.

```powershell
$env:RUN_POST_SEARCH_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_POST_SEARCH_MYSQL_TESTS
```

POST_MYSQL_USER/POST_MYSQL_PASSWORD로 전용 DB 계정을 설정할 수 있다. 기존 로컬 격리 환경의 기본값은 root/빈 비밀번호다.

2026-11-13: H2 검색 63개+격리 MySQL 검색 63개+기존 게시판/CRUD/컨텍스트 H2 97개=223개 통과, bootJar 성공. 기존 CRUD MySQL 선택 클래스는 이번 실행에서 생략했다. 4종 정렬·동률·검색·기간·아카이브·페이지 경계·같은 시각의 새 글 제외·조건이 바뀐 커서·개인 반응·썸네일·읽기 전용 동작을 검증했다.

같은 JAR/격리 MySQL 서버의 실제 HTTP 29개도 통과했다. 페이지 전체 순서·합계와 DB를 대조하고 목록 요청이 글/조회수/수정 시각/dedup을 바꾸지 않는 것을 확인했다. 최근 하루의 UTC 경계 안팎도 확인했다. 이미지는 합성 키와 example.test 주소로 URL 생성만 확인했다. 실제 토큰 개인화·CM09 업로드/저장소·공통 DB·FE 연결은 미검증이며 Postman UI는 조작하지 않았다.
