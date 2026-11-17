# 내 프로필 조회·수정 (#8)

[이슈 #8](https://github.com/Likelion14-line4-BOXBOX/BE/issues/8) · [MG01](https://app.notion.com/p/41574200449d816a81a0eb31c0cf2d9b) · [MG02](https://app.notion.com/p/41574200449d810ebc0df86d20323b03)

MG01 `GET /api/me`와 MG02 `PATCH /api/me`를 `com.boxbox.garage.profile`에 구현했다. 기존 `common.api` 응답·오류 형식을 사용한다. API 경로·필드 계약은 유지하며, 형식·길이·소속 오류는 공통 규칙의 400 `INVALID_REQUEST`다.

## DB와 실행

Java 21·MySQL 8과 현행 공통 ERD의 `users`, `teams`, `drivers`가 필요하다. 기존 게시판 기능이 사용하는 테이블도 필요하다. 이번 작업에서 회원·팀·선수 엔티티나 테이블을 중복 생성하지 않으며 `JdbcTemplate`으로 기존 컬럼을 사용한다. 애플리케이션은 운영 스키마·기준 데이터를 자동 생성하지 않는다.

프로필은 `users`의 nickname/bio/avatar_asset_id/favorite_team_id/favorite_driver_id/show_market_assets/created_at/onboarding_completed를 읽는다. 팀·선수의 이름·차량 번호·championship_rank는 고정 DB 입력값이며 외부 API를 호출하지 않는다. 순위 미입력 값은 null이다. UTC로 저장한 DATETIME을 UTC ISO 8601로 반환한다.

DB 연결 설정은 [게시판 실행 안내](board-api.md)의 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 재사용한다.

```powershell
.\gradlew.bat bootRun
```

## 인증 연결

검증된 서버 `HttpServletRequest.getUserPrincipal().getName()`을 `users.login_id`로 찾는다. name=loginId는 기존 게시판과 동일한 어댑터 가정이며 인증 담당과 최종 규약을 맞춰야 한다. 인증이 없거나 계정이 없거나 정지·탈퇴·삭제 상태이면 401 `UNAUTHORIZED`다. 클라이언트의 사용자 ID 헤더나 Bearer 문자열 자체를 신뢰하지 않는다.

로그인·토큰 검증은 다른 담당 작업이다. 실제 인증 필터가 연결되기 전에는 정상 회원의 실제 토큰 HTTP 호출을 검증할 수 없다. 테스트의 서버 Principal 주입은 토큰 인증 검증을 뜻하지 않는다.

## PATCH 규칙

- 허용 필드는 nickname, bio, avatarAssetId, favoriteTeamId, favoriteDriverId, showMarketAssets다. 그 외 필드는 400이다.
- 생략한 필드는 유지한다. 빈 객체는 변경 없이 현재 프로필을 반환한다.
- nickname은 trim 후 2~20자, bio는 0~100자다. bio의 빈 문자열은 지우기다. 두 필드와 showMarketAssets의 null은 허용하지 않는다. 문자열·Boolean 타입을 임의로 변환하지 않는다.
- favoriteTeamId/favoriteDriverId의 null은 해제다. ID는 기존 API처럼 양의 정수 PK 문자열이다. 예시용 t001/d001을 실제 PK로 해석하지 않는다.
- 선수는 선택한 팀 소속이어야 한다. 기존 최애 선수가 있는 상태에서 팀을 변경·해제하려면 같은 요청에 favoriteDriverId를 재선택하거나 null로 보내야 한다. 팀만 지정하고 선수는 미설정인 상태도 허용한다.
- 존재하지 않는 팀·선수는 각각 404 TEAM_NOT_FOUND/DRIVER_NOT_FOUND다. 닉네임 중복은 409 NICKNAME_TAKEN이다. 닉네임 비교는 실제 DB collation과 UNIQUE 제약을 따른다. 삭제·정지 회원의 닉네임도 현행 UNIQUE 제약에 포함된다.
- 회원 행을 먼저 잠근 후 현재 값과 PATCH를 합친다. 같은 회원의 동시 부분 수정에서 다른 필드 변경이 유실되지 않도록 한다. 닉네임의 사전 검사 이후 발생하는 경합도 DB UNIQUE 제약으로 막는다.
- 저장은 하나의 트랜잭션이다. 로그인 ID·비밀번호 해시·계정 상태·가입 시각·지갑·onboarding_completed는 수정하지 않는다. onboardingCompleted는 현재 저장된 값을 GET에서 조회하며 완료 판정 조건은 이번 명세에 없다.
- GET은 createdAt/onboardingCompleted를 포함하고, PATCH 성공 응답은 명세대로 수정한 프로필 필드만 반환한다.

## 이미지 프리셋

기본 프리셋은 ERD의 `avatar_default`다. avatarAssetId의 null은 이 값으로 초기화한다. 실제 FE 코드에는 선수 SVG 파일이 있으나 프로필 프리셋 ID 매핑이 없어 사용자 지시에 따라 기본값으로 구현했다. 이미지 파일·URL·카탈로그 테이블은 추가하지 않았다.

FE의 AVATAR/TEAM/DRIVER 프리셋 코드가 정해지면 `PROFILE_ALLOWED_AVATAR_IDS`에 허용 ID를 쉼표로 구분해 설정한다. 예시 ID를 임의로 운영 목록에 등록하지 않는다. 기본값은 항상 허용하며 설정되지 않은 다른 ID는 400이다. 기존 DB 값을 조회하거나 다른 필드만 수정할 때 기존 프리셋 ID를 강제로 바꾸지는 않는다.

## 검증 실행

```powershell
.\gradlew.bat cleanTest test --offline --console=plain
```

기본 테스트는 H2(MySQL 모드)를 사용한다. ProfileApiTests의 60개 검증은 정상 조회·생략/null·기본 프리셋·중복·소속/형식/길이 오류·미인증/비활성 계정·민감 필드 제외·DB 저장값·동시 부분 수정을 다룬다. 기존 게시판/시드/컨텍스트 40개도 함께 실행한다.

격리 MySQL 테스트는 기본적으로 생략한다. 로컬 `127.0.0.1:13306` 인스턴스에 **검증 전용 `boxbox_profile_verify` DB**를 새로 만들고 현행 전체 ERD를 먼저 적용해야 한다. 이 DB의 데이터는 테스트마다 초기화한다. 공통 DB나 실제 사용자 DB를 이 이름으로 연결하면 안 된다. URL은 테스트 코드에 위 전용 주소로 고정되어 있다.

```powershell
$env:RUN_PROFILE_MYSQL_TESTS = 'true'
$env:PROFILE_MYSQL_USER = 'root'
# 비밀번호가 필요한 로컬 인스턴스는 PROFILE_MYSQL_PASSWORD도 환경변수로 설정한다.
.\gradlew.bat cleanTest test --offline --console=plain
Remove-Item Env:RUN_PROFILE_MYSQL_TESTS
```

ProfileMySqlTests는 동일한 60개 시나리오를 실제 MySQL에 적용하고 20개 이모지 저장·대소문자/악센트 비교·서로 다른 회원의 동일 닉네임 동시 선점을 추가 검증한다. H2의 VARCHAR 길이와 collation은 MySQL과 완전히 같지 않으므로 이 세 가지는 MySQL에서 확인한다. 정상 응답 검증은 MockMvc와 주입한 서버 Principal을 사용하며 실제 로그인/토큰 검증과 구분한다.

2026-11-13 Java 21에서 위 MySQL 실행 옵션으로 총 163개(H2 프로필 60·기존 40·MySQL 프로필 63)가 실패·오류 없이 통과했다. MySQL 8.0.45의 격리 인스턴스에 전용 DB를 새로 만들고 전체 ERD 32개 테이블을 적용했다. 기존 공통 DB나 다른 검증 DB의 데이터는 변경하지 않았다.

동일 코드의 실제 서버를 `127.0.0.1:8081`에 실행해 HTTP 8개를 확인했다. 프로필의 인증 없는 GET/PATCH·위조 사용자 헤더·검증되지 않은 Bearer는 401, 잘못된 JSON은 400이었으며 공통 오류 JSON을 반환했다. 기존 게시판의 빈 목록은 200/[], 없는 게시판 태그는 404였다. 전후 DB 조회로 프로필·지갑 값이 유지된 것을 확인했다. 실제 인증된 회원 HTTP 성공 호출과 공통 DB·FE 연동은 미검증이다.
