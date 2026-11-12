# 게시판·태그 조회 (#6)

[이슈 #6](https://github.com/Likelion14-line4-BOXBOX/BE/issues/6) · [현행 API 명세](https://app.notion.com/p/41574200449d80268ca8d65d1a6fc631)

이 작업은 TR01·TR02만 구현한다. 로그인·회원가입·게시글 CRUD·공통 기준정보 API는 각각 담당 작업과 연결한다. 기존 경기 API는 수정하지 않는다. API 경로·필드·응답 계약 변경은 없다.

## DB 준비

Java 21, MySQL 8을 사용한다. 실행 전에 현행 공통 ERD의 스키마를 적용해야 한다.

- `boards`, `board_tags`: 이번 작업의 JPA 모델이다. 신규 생성용 SQL은 `src/main/resources/db/board-schema.sql`이다. 현행 전체 ERD SQL로 이미 만들었다면 다시 만들 필요가 없다.
- `teams`, `drivers`: 공통 기준정보 담당이 DB에 직접 입력한다. `teams`/`drivers`를 먼저 만든 다음 게시판·태그를 생성한다.
- `posts`: 게시판별 글 수를 실제 집계하므로 공통 ERD의 `posts` 테이블이 필요하다. 아직 게시글 작성 API가 없어도 빈 테이블을 생성해야 한다. 게시판·태그 다음에 생성한다.
- `users`: 회원 요청의 활성 상태·최애 팀을 조회한다. 현행 `login_id`, `favorite_team_id`, `status`, `deleted_at` 컬럼을 사용한다.

애플리케이션은 `ddl-auto=validate`로 게시판·태그 매핑을 확인하며 스키마나 데이터를 자동 변경하지 않는다. `users`/`posts`는 네이티브 조회 대상이므로 공통 ERD와 컬럼 일치도 확인해야 한다. 팀·선수의 가짜 운영 데이터를 생성하지 않는다.

다음 환경변수를 설정하고 실행한다. 비밀값은 Git에 저장하지 않는다.

| 환경변수 | 의미 | 기본값 |
| --- | --- | --- |
| `DB_URL` | JDBC URL | `jdbc:mysql://localhost:3306/boxbox?connectionTimeZone=UTC&characterEncoding=UTF-8` |
| `DB_USERNAME` | DB 사용자 | `boxbox` |
| `DB_PASSWORD` | DB 비밀번호 | 빈 값. 실제 환경에서 설정 |

```powershell
.\gradlew.bat bootRun
```

기준정보와 스키마를 준비한 후 `src/main/resources/db/board-seed.sql`을 수동 실행한다. 팀·선수의 기존 ID/이름을 참조해 팀 게시판과 팀/선수 태그를 만들고, 자유·입문자 게시판과 화면명세에 제시된 주제 태그를 추가한다. 자유·입문자 표시 순서 1000/1001은 이번 초기값이며 DB에서 조정할 수 있다. 재실행 시 같은 게시판/태그를 추가하거나 운영자가 바꾼 이름·활성 여부를 덮어쓰지 않는다. 운영 중 여러 프로세스가 동시에 실행하는 마이그레이션 도구는 아니다.

## 조회 동작

| API | 동작 |
| --- | --- |
| `GET /api/boards` | 활성 게시판을 `display_order`, `id` 순으로 조회 |
| `GET /api/boards?type=TEAM` | TEAM/BEGINNER/FREE 분류 필터 |
| `GET /api/boards?teamId=1` | 특정 팀 필터. type이 없으면 해당 팀 게시판 조회 |
| `GET /api/boards/1/tags` | 게시판 태그를 `display_order`, `id` 순으로 조회 |

ID는 ERD의 정수 PK를 문자열로 반환한다. 요청의 ID도 이 문자열을 사용한다. 이름이나 예시용 `b001`/`t001`을 PK로 저장하지 않는다.

- 목록은 `data.items`, 메타 정보는 `meta.requestId`/`meta.serverTime`에 반환한다. 개인 상태와 팀 ID가 null이어도 필드를 생략하지 않는다.
- `postCount`는 해당 게시판에서 `deleted_at IS NULL`인 게시글 수다. 게시판당 개별 집계 쿼리를 반복하지 않는다.
- 게시판이나 태그가 없을 때 목록은 `[]`다. 존재하지 않거나 비활성인 게시판의 태그 요청은 404 `BOARD_NOT_FOUND`다.
- 태그의 `isActive`를 그대로 반환한다. FE는 글 작성 시 활성 태그만 선택할 수 있게 표시하고, 비활성 태그를 사용한 과거 글은 아카이브 기능에서 조회한다.
- 잘못된 분류·ID, FREE/BEGINNER와 teamId의 조합은 400 `INVALID_REQUEST`다.

## 인증 담당과 연결할 지점

사용자는 인증을 다른 팀원이 담당한다고 확인했다. 이 작업은 토큰 발급/서명 검증을 구현하지 않는다.

인증 연동 지점은 `BoardViewerResolver`다. 현재 어댑터는 인증 계층이 토큰 검증 후 설정한 `HttpServletRequest.getUserPrincipal()`을 사용하고, `Principal.getName()`을 `users.login_id`로 조회한다. **name=loginId는 이번 어댑터의 연결 가정이며 인증 담당과 합의된 최종 규약은 아니다.** 인증 담당이 userId 기반 Principal을 제공하면 이 어댑터와 회원 조회를 그 규약에 맞춘다.

- 인증 정보가 없으면 비회원으로 조회하고 `isMyTeam=null`이다.
- 활성 회원이면 DB의 최애 팀과 비교해 true/false를 반환한다. 최애 팀이 없는 회원과 자유·입문자 게시판은 false다.
- 존재하지 않거나 정지/탈퇴/삭제된 회원은 401 `UNAUTHORIZED`다.
- Authorization 헤더만 있고 검증된 Principal이 없으면 401이다. Bearer 문자열이나 클라이언트가 보낸 사용자 ID 헤더를 신뢰하지 않는다.

따라서 인증 담당의 실제 필터가 연결되기 전에는 로그인 토큰을 사용한 회원 HTTP 호출이 완료됐다고 볼 수 없다. 회원별 응답은 통합 테스트에서 서버 Principal과 실제 테스트 DB를 연결해 검증했다.

## 검증

```powershell
.\gradlew.bat test
```

테스트는 `test` 프로필의 H2(MySQL 모드)와 테스트 전용 공통 테이블을 사용한다. 운영 MySQL 접속이나 OpenF1 호출 없이 HTTP 요청 처리 → 서비스 → JPA/네이티브 조회 → JSON 응답을 검증한다. 서버 Principal을 테스트에 주입하는 것은 토큰 검증 테스트를 뜻하지 않는다.

`BoardApiTests`는 비회원/회원, 최애 미설정, 정지·탈퇴·삭제 회원, 검증되지 않은 인증 헤더, 세 종류 게시판 필터, 표시 순서, 실제 글 수와 논리 삭제, 빈 목록, 없는 게시판, 태그 범위/활성 상태와 잘못된 요청을 검증한다. `BoardSeedTests`는 초기값 생성·재실행·기존 이름 보존을 검증한다.

H2 결과는 MySQL 엔진 실행·운영 DDL/FK·인증 담당 필터의 검증을 대체하지 않는다. 이 작업 환경의 Docker 엔진이 실행 중이지 않아 실제 MySQL 검증은 수행하지 않았다. 공통 DB와 인증 필터를 연결한 뒤 동일한 조회를 확인해야 한다.

Spring Boot의 외부 DB 설정과 서버 Principal 전달 방식은 [공식 DB 문서](https://docs.spring.io/spring-boot/reference/data/sql.html), [Spring MVC 메서드 인자 문서](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/arguments.html)를 참고했다.
