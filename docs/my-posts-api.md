# 내 작성글·좋아요·스크랩 목록 (#18)

[이슈 #18](https://github.com/Likelion14-line4-BOXBOX/BE/issues/18)의 MG04 `GET /api/me/posts`를 `com.boxbox.garage.activity`에 구현했다. PR #17까지 반영된 dev `7af30f9`에서 `feat/#18-my-posts`를 분리했다.

## 요청·응답

로그인이 필수다. filter는 AUTHORED/LIKED/BOOKMARKED 중 하나를 반드시 전달한다. size는 기본 20, 1~100이다. cursor는 첫 조회에 생략하고 다음 페이지에서 서버가 반환한 값을 그대로 사용한다. 지원하지 않는 필터·파라미터·페이지 크기·커서는 공통 400 INVALID_REQUEST다.

```http
GET /api/me/posts?filter=BOOKMARKED&size=20
Authorization: Bearer <검증 가능한 access token>
```

200 응답의 data는 items/nextCursor/hasNext만 포함한다. 빈 목록은 items=[], nextCursor=null, hasNext=false다. totalCount/popularPost나 새 요청 필드를 추가하지 않았다. 항목은 기존 게시글 요약 DTO를 사용해 작성자·태그·미리보기·첫 첨부 URL·반응/댓글/조회수 집계·생성 시각·개인 반응 상태를 반환한다.

본인 작성글 또는 본인이 해당 유형으로 반응한 글만 조회한다. 타인의 활동 범위를 지정하는 userId는 받지 않는다. 삭제 글과 비활성 게시판 글은 제외한다. 비활성 태그의 아카이브 글은 내 활동에 남기며 과거 태그명을 유지한다. 탈퇴·삭제 작성자의 공개 표시는 기존 규약의 ‘탈퇴한 사용자’/avatar_default다. 미리보기는 기존처럼 최대 100 코드 포인트다.

## 정렬·커서

현재 MG04는 구체적인 정렬 기준을 지정하지 않는다. 이번 구현 기본값은 AUTHORED=게시글 생성 시각 내림차순, LIKED/BOOKMARKED=해당 반응 최초 등록 시각 내림차순이며 동률은 게시글 ID 내림차순이다. 응답 createdAt은 정렬 유형과 관계없이 게시글 생성 시각이다. 사용자가 별도로 확정한 정책으로 간주하지 않는다.

커서는 버전·현재 회원·필터·size·첫 조회 시각·게시글 ID 상한·마지막 활동 시각/글 ID를 검증한다. 다른 회원·필터·size의 커서는 400이다. 실제 조회의 회원 ID는 검증한 Principal로 찾은 DB 회원에서 가져오며 커서 값을 인증 수단으로 사용하지 않는다. 페이지마다 계정 상태도 다시 확인한다.

첫 조회 시각보다 늦은 활동과 ID 상한을 넘는 새 글은 후속 페이지에 넣지 않는다. 활동 시각은 마이크로초 단위로 저장하고 조회 상한과 같은 시각까지 포함해 방금 등록한 반응이 빠지는 것을 방지한다. 기존 반응 쓰기도 DATETIME(6) 정밀도로 절삭해 반올림 때문에 조회 상한을 넘지 않게 했다.

삭제된 경계 행은 없어져도 마지막 시각/ID로 계속 조회한다. 반응 취소는 다음 요청에서 빠지고, 취소 후 재등록은 새 활동 시각을 가진다. 새로 등록한 반응은 첫 페이지를 새로 조회해 확인한다. 페이지 사이 본문·반응 수·삭제 상태까지 불변 스냅샷으로 저장하지 않는다. 첫 상한과 정확히 같은 마이크로초의 추가 반응이나 과거 시각으로 직접 입력된 데이터까지 최초 회원 목록으로 고정하는 계약은 아니다.

## 기존 기능과의 연결

기존 users/posts/post_reactions와 인덱스를 사용하며 엔티티·테이블·카운터를 추가하지 않았다. radio.post.PostSummaryReader를 추출해 기존 게시글 검색과 garage의 요약 필드·첨부 URL 규약을 공유한다. 기존 검색의 필터·정렬·성공 응답은 유지한다.

내 활동 조회는 읽기 전용 REPEATABLE_READ 트랜잭션 안에서 회원·활동 ID 페이지·요약 목록을 읽는다. 항목별 추가 Repository 호출이나 게시글 상세 서비스를 호출하지 않는다. 추가 한 행으로 hasNext만 판단하고 실제 응답에 포함되는 글의 요약/이미지만 조회한다. 본문·수정 시각·조회수·dedup·회원·반응 관계를 변경하지 않는다.

## 실행·인증 의존성

Java 21·MySQL 8과 기존 전체 스키마가 필요하다. DB_URL/DB_USERNAME/DB_PASSWORD와 POST_MEDIA_BASE_URL은 [게시글 실행 안내](post-api.md)를 따른다. 이미지 없는 목록은 이미지 설정 없이 조회할 수 있고 실제 표시할 첨부 URL 설정이 잘못되면 기존 공통 503 MEDIA_SERVICE_UNAVAILABLE을 반환한다.

```powershell
.\gradlew.bat bootRun
```

인증 담당이 검증 후 설정한 Principal.name=loginId 가정을 기존 프로필과 동일하게 사용한다. 미인증·없는/정지/탈퇴/삭제 계정은 기존 조회 규약대로 401 UNAUTHORIZED다. 헤더의 사용자 ID나 검증되지 않은 Bearer 문자열을 회원으로 해석하지 않으며 로그인·토큰 검증·런타임 테스트 인증 우회를 추가하지 않았다.

MG04의 URL·요청·응답 필드는 기존 명세대로이며 Notion과 루트 현행 명세 파일은 수정하지 않았다. FE는 필터별 목록을 새로 시작하고 다른 필터/회원/size에 이전 커서를 재사용하지 않아야 한다. 인증·CM09 실제 저장소·공통 DB·FE 연결의 검증 범위는 실행 결과와 별도로 기록한다.

## 검증 실행

```powershell
.\gradlew.bat cleanTest test bootJar --offline --console=plain
```

내 활동 전용 MySQL 테스트는 `127.0.0.1:13306/boxbox_my_posts_verify`에 전체 ERD 32개 테이블을 준비한 후 선택 실행한다. 관련 데이터를 초기화하므로 공통 DB에서 실행하지 않는다. MY_POSTS_MYSQL_USER/MY_POSTS_MYSQL_PASSWORD를 지원한다. 기존 요약 조회 추출의 MySQL 회귀 검증에는 별도 boxbox_search_verify DB의 기존 검색 테스트를 함께 실행한다.

```powershell
$env:RUN_MY_POSTS_MYSQL_TESTS = 'true'
$env:RUN_POST_SEARCH_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_MY_POSTS_MYSQL_TESTS
Remove-Item Env:RUN_POST_SEARCH_MYSQL_TESTS
```

2026-11-13 실제 실행: H2 내 활동 51개+기존 H2 회귀 330개=381개, 실패·오류 0개. bootJar와 diff 검사도 통과했다. 본인 범위/계정 상태/세 필터/동률/빈 결과/잘못된 요청/커서 재사용·변조/새 글·반응 상한/삭제 경계/취소·재등록/코드 포인트/첨부 URL/조회 시 DB 불변과 기존 검색 응답을 검증했다.

격리 MySQL DB 준비 명령은 자동 승인 검토가 두 번 모두 기한 내 끝나지 않아 실행되지 않았다. 명시적 승인을 요청한 상태이며 MySQL 테스트와 실제 서버 HTTP 검증은 아직 실행하지 않았다. 준비 스크립트는 로컬 build/verification/issue-18-mysql-setup.ps1, HTTP 검증 스크립트는 build/verification/issue-18-http.ps1에 있다. 기존 검증 DB와 공통 DB는 이번 턴에 변경하지 않았다.

정상 회원 요청은 서버 Principal을 주입한 H2 MockMvc·서비스 테스트로 확인한 것이며 실제 로그인 토큰 HTTP 성공 조회·공통 DB·FE 연결은 미검증이다. 검증용 런타임 인증 경로는 추가하지 않았다. MySQL·HTTP가 확인되면 실제 결과를 추가하고 PR 초안의 검증 상태도 갱신해야 한다.
