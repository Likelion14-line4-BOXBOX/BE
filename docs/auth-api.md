# 자체 인증 API 실행·연동

AU01~04와 기존 회원 API의 Bearer 인증을 구현한다. 일반 `loginId`는 trim·소문자화 후 영문/숫자/_ 4~20자, 가입 비밀번호는 유니코드 문자 수 기준8~64자다. 비밀번호 원문을 변환하거나 조합 조건을 강제하지 않는다. 로그인 입력은1~64자이며 가입 최소 길이와 구분한다.

## 실행 설정

운영 DDL은 프로젝트 루트 `docs/erd/erdcloud-import.sql`의 기존32개 테이블을 사용한다. 인증은 `users`, `coin_ledger`, `auth_sessions`를 사용하며 애플리케이션이 스키마를 생성하지 않는다. 회원·초기 잔액·최초 지급 원장은 한 트랜잭션으로 저장한다.

- `AUTH_JWT_SECRET`: 최소32바이트의 난수를 Base64로 인코딩한 값. 미설정·잘못된 값은 기동 실패다. 아래 테스트 전용 키를 운영에 복사하지 않는다.
- `AUTH_ALLOWED_ORIGINS`: 경로·후행 슬래시 없는 정확한 FE Origin을 쉼표로 구분한다. 기본값은 localhost/127.0.0.1의5173·3000 포트다.
- `AUTH_COOKIE_SAME_SITE`: 기본 Lax. 교차 사이트 배포는 None과 HTTPS를 함께 사용한다. 쿠키는 항상 HttpOnly·Secure·host-only·Path=/api/auth다.
- `AUTH_REFRESH_LIFETIME`: 기본 P14D. 회전 후에도 해당 로그인 세션의 최초 만료 시각을 유지한다.
- `AUTH_INITIAL_COINS`: 기본1000000. 가입당 최초 지급1회이며 로그인·재발급에서 추가 지급하지 않는다.
- `AUTH_RATE_SIGNUP` / `AUTH_RATE_LOGIN_IP` / `AUTH_RATE_LOGIN_ACCOUNT` / `AUTH_RATE_REFRESH`: 1분당 기본10/30/10/60회다. 아이디·IP 기준 제한은 서버 프로세스의 메모리에서 공유한다. 재시작 시 초기화되며 다중 인스턴스 배포 전에는 공용 저장소나 게이트웨이 제한을 연결해야 한다. 임의의 X-Forwarded-For는 신뢰하지 않는다.
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: 사용할 MySQL 연결 정보. 검증용 DB와 공통 DB를 구분한다.

PowerShell에서 서명 키를 출력하거나 파일에 남기지 않고 현재 실행 환경에 설정하는 예:

```powershell
$authSigningBytes = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Fill($authSigningBytes)
$env:AUTH_JWT_SECRET = [Convert]::ToBase64String($authSigningBytes)
$env:AUTH_ALLOWED_ORIGINS = 'http://localhost:5173'
.\gradlew.bat bootRun
```

실제 배포에서는 서버 재시작 후에도 같은 비밀 설정을 주입한다. 키를 바꾸면 기존 access token은 거절된다. 테스트 프로필의 공개 키는 `src/test/resources`에만 있고 실행 JAR에는 포함하지 않는다.

## FE·클라이언트 흐름

1. `POST /api/auth/signup`에 loginId/password/nickname을 보낸다. 성공201 후 로그인 화면으로 이동한다.
2. `POST /api/auth/login`에 loginId/password를 보낸다. 응답 accessToken은 Bearer 헤더에 사용하며 refreshToken은 응답 JSON에 포함하지 않는다. 브라우저는 credentials: include로 쿠키를 받는다.
3. `GET /api/me` 등 기존 API에 `Authorization: Bearer <accessToken>`을 보낸다. 검증된 Principal.name은 loginId다. access token은 HS256 서명·발급자·대상·사용자 ID·발급/만료 시각을 검증하며15분 유효하다. 발급자 `https://boxbox.local`은 고정 식별자이며 외부 호출 주소로 사용하지 않는다.
4. `POST /api/auth/refresh`는 본문/조회 파라미터 없이 쿠키로 호출한다. 쿠키가 있는 refresh/logout 요청에는 등록된 Origin이 필요하다. Postman 같은 클라이언트도 이 헤더를 함께 보낸다. FE는 여러 재발급 요청을 하나로 모은다.
5. refresh 재사용 시 해당 로그인 세션 계열을 폐기하고401을 반환한다. 이 폐기는 오류 응답과 함께 롤백되지 않는다. 다른 로그인 계열은 유지한다. 세션 검증401은 쿠키를 지우며403/429에는 기존 쿠키를 유지한다.
6. `POST /api/auth/logout`은 현재 refresh 계열과 쿠키를 폐기한다. 쿠키 없음/이미 로그아웃은204다. 발급된 access token은 원래15분 만료까지 유효하며 추가 재발급을 차단한다.

브라우저의 Secure 쿠키 사용은 HTTPS와 배포 출처 설정을 맞춰 확인해야 한다. 실제 FE의 쿠키 저장·자동 전송·라우팅은 이번 서버 HTTP 검증과 별도다.

## 실시간 채팅

TR21은 fetch 기반 SSE 요청에 Bearer 헤더를 전달한다. 연결 중 만료 시 AUTH_REQUIRED를 보내고 연결·접속 집계를 해제한다. FE는 재발급 후 다시 구독한다. 참여 저장 잠금 대기 중 만료되면 새 도장을 지급하지 않으며, 이미 획득한 JOIN 도장은 유지한다.

## 검증

```powershell
$env:RUN_AUTH_MYSQL_TESTS = 'true'
$env:RUN_STAMP_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
```

선택형 MySQL 테스트는127.0.0.1:13306의 전용 `boxbox_auth_verify`, `boxbox_stamp_verify`를 사용한다. 기존 ERD를 미리 적용하고 공통 DB에서 실행하지 않는다. `AUTH_MYSQL_USER/PASSWORD`, `STAMP_MYSQL_USER/PASSWORD`로 전용 연결 자격 증명을 설정할 수 있다.

2026-11-15 검증: H2·격리 MySQL과 기존 회귀838개 통과, 실패/오류0. 다른 기능의 선택형 MySQL334개는 실행하지 않았다. 별도 `boxbox_auth_http_verify_v2`·MySQL8.0.45·전체ERD32개·같은 JAR에서 실제 HTTP/SSE57요청·89항목을 검증했다. 가입/중복/해시·잔액/원장 대조, 실토큰 프로필 조회·수정, 변조/만료/필수 클레임 누락, refresh 회전/재사용/다른 계열 보존/로그아웃, CORS/CSRF/요청 제한, 공개 필터·빈 목록·오류, LIVE 참여 도장·SCHEDULED 만료 무지급을 확인했다.

공통 DB·실제 FE는 미검증이다. #30 MG03의 마켓 provider 미연결은 별도 후속 범위이며 이 인증 기능에 포함하지 않는다.
