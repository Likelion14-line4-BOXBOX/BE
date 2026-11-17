# 게시글 작성·상세·수정·삭제 (#10)

[이슈 #10](https://github.com/Likelion14-line4-BOXBOX/BE/issues/10)의 TR04~TR07을 `com.boxbox.radio.post`에 구현했다.

| API | 성공 응답 |
| --- | --- |
| `GET /api/posts/{postId}` | 200, 상세·작성자·태그·첨부·반응·권한 |
| `POST /api/boards/{boardId}/posts` | 201, postId/boardId/createdAt |
| `PATCH /api/posts/{postId}` | 200, postId/updatedAt |
| `DELETE /api/posts/{postId}` | 204, 본문 없음 |

경로·요청/응답 필드는 현행 명세를 유지한다. ID는 기존 게시판처럼 양의 BIGINT 문자열이다. 예시 p001 등의 문자열을 실제 DB PK로 해석하지 않는다. 회원·팀·선수 엔티티 및 로그인·업로드 API를 추가하지 않았다.

## 선행 브랜치와 병합

`feat/#10-post-crud`는 #6의 `638ecf8`에서 분기했다. 프로필 #8 변경은 포함하지 않는다. #7 미병합 중에도 개발·로컬 검증·커밋은 진행할 수 있다. PR 초안의 대상은 dev이며 선행 변경이 함께 보일 수 있다. #7이 dev에 반영된 후 실제 병합 방식에 맞춰 게시글 커밋의 기준을 정리하고 diff를 확인한 뒤 이 PR을 병합한다. 임시 base를 #6으로 바꾸려면 병합 담당과 리뷰 전용 운영을 합의해야 한다.

## DB·실행 설정

Java 21, MySQL 8, 현행 ERD의 users/boards/board_tags/posts/media_files/comments/post_reactions/post_view_dedup가 필요하다. 기존 게시판 엔티티를 위한 teams/drivers도 필요하다. 루트 `docs/erd/schema.mysql.all.sql`이 전체 스키마 원본이며, BE가 운영 DB를 자동 생성하지 않는다. 기존 DB 연결 환경변수는 [게시판 실행 안내](board-api.md)를 따른다.

DATETIME은 UTC LocalDateTime으로 저장하고 UTC Instant로 응답한다. JDBC·JVM의 기본 시간대에 맡기지 않는다.

- `POST_MEDIA_BASE_URL`: CM09 담당 저장소의 이미지 조회 기준 URL. object_key의 각 경로 조각을 인코딩해 결합한다. 실제 저장소의 공개 URL/서명 방식은 담당자와 맞춰야 한다. 미설정·잘못된 키인 상태에서 첨부가 있는 글을 조회하면 503 MEDIA_SERVICE_UNAVAILABLE이며 조회수도 롤백한다. 임의의 이미지 API URL을 만들지 않는다.
- `POST_VIEW_WINDOW`: 동일 회원/익명 세션의 조회수 재집계 간격. 기본 `PT10M`은 ERD의 기술 초안에 따른 구현 기본값이며 확정된 사용자 정책 수치가 아니다. 양수 Duration만 허용한다.

```powershell
.\gradlew.bat bootRun
```

## 인증·권한

기존 BoardViewerResolver가 반환한 검증된 서버 Principal.name을 users.login_id로 조회한다. 이 연결 규약은 인증 담당과의 최종 합의가 필요하다. 사용자 ID 헤더나 Bearer 문자열만으로 인증하지 않는다.

GET은 비회원에게도 허용한다. 회원의 반응 상태는 관계 테이블에서 계산하고 비회원에게는 null을 반환한다. 작성자만 canEdit/canDelete=true다. 삭제·탈퇴 계정이나 잘못된 인증은 401이다. 정지 계정의 작성·변경은 403 WRITING_RESTRICTED, 타인 글 변경은 403 NOT_POST_AUTHOR다. 활성 게시판의 미삭제 글만 상세·변경 대상으로 삼으며 없으면 404 POST_NOT_FOUND다.

로그인·토큰 검증은 별도 담당이다. 통합 테스트의 Principal 주입은 실제 로그인 토큰 검증이 아니다.

## 저장·첨부 규칙

- 제목은 strip 후 1~20 Unicode 코드 포인트, 본문은 공백만 불가이며 기획상 글자 수 상한을 추가하지 않는다. 제목·본문·배열의 null 및 알 수 없는 필드는 400이다.
- 태그는 해당 게시판의 활성 태그 0~1개다. 기존 비활성 태그는 수정 요청에서 태그를 생략하면 유지하고 상세에도 표시한다.
- 사진은 중복 없는 ID 0~5개다. 소유자·목적 POST_IMAGE·삭제/만료/연결 상태를 검증한다. 새 첨부는 UPLOADED, 기존 첨부는 현재 글에 ATTACHED인 파일만 허용한다. 실제 파일 형식/바이트 검증과 업로드는 CM09 담당이다.
- PATCH 생략은 유지, 빈 배열은 태그/첨부 해제다. 빈 객체는 updatedAt도 유지한다. 게시판·작성자는 변경할 수 없다.
- 회원 → 게시글 → 첨부 행을 잠근다. 첨부 검증은 ID 순서로 잠그며 같은 업로드의 동시 선점을 차단한다. 순서 교체는 기존 연결을 일시 해제한 후 재배치해 UNIQUE(post_id,position) 충돌을 피한다. 실패 시 글과 첨부 변경을 모두 롤백한다.
- 연결한 파일은 ATTACHED로 바꾸고 업로드 대기 만료를 해제한다. 수정에서 빠진 파일은 UPLOADED로 돌린다. 미첨부 정리와 저장소 파일 삭제는 업로드 담당 수명주기와 연결해야 한다.
- 삭제는 posts.deleted_at 기록·content 제거와 연결 이미지 DELETED/연결 해제를 함께 처리한다. 댓글/반응 관계를 물리 삭제하지 않는다. 상세에서 404, 기존 게시판 글 수 집계에서도 제외된다. 검색·내 활동 API는 후속 이슈다.

## 상세·조회수

댓글 수는 미삭제 댓글만, 좋아요·스크랩은 기존 관계 테이블을 집계한다. 반응/댓글 생성 API를 이번 이슈에 추가하지 않는다. 미리보기는 앞 100 코드 포인트라는 구현 기본값이며 본문을 자르지 않는다. 탈퇴 작성자는 공개 표시를 기본 프로필과 ‘탈퇴한 사용자’로 처리한다.

익명은 서버 HTTP 세션 ID, 회원은 DB 회원 ID를 구분해 SHA-256 해시로 저장한다. 원문 IP·인증 토큰을 저장하지 않는다. 같은 방문자의 재조회는 마지막 집계 시각부터 설정 간격 안에서는 증가시키지 않는다. 게시글 행 잠금으로 동시에 도착한 조회를 직렬화하고 dedup도 현재 값으로 잠금 조회한다. 조회로 updatedAt은 변경하지 않는다. 쿠키를 지우거나 다른 기기를 쓰는 익명 방문자까지 동일인으로 판별하는 방식은 아니다. FE가 익명 세션을 유지하려면 쿠키를 함께 보내야 하며, 별도 출처의 쿠키/CORS 설정은 연동 시 확인한다.

## 검증 실행

```powershell
.\gradlew.bat cleanTest test --offline --console=plain
```

기본 실행은 H2 전용 게시글 스키마를 사용하며 기존 게시판 테스트 스키마를 바꾸지 않는다. H2와 MySQL의 보충 문자 길이 차이 때문에 H2의 제목 물리 길이는 여유를 두고, 실제 VARCHAR(20)에 20개 이모지가 저장되는지는 MySQL에서 검증한다.

MySQL 테스트는 `127.0.0.1:13306/boxbox_post_verify` 전용이다. 비어 있는 로컬 격리 DB에 전체 ERD를 먼저 적용해야 하며 공통 DB에서 실행하면 안 된다. 테스트 픽스처는 해당 전용 DB의 게시글 관련 데이터를 초기화한다.

```powershell
$env:RUN_POST_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_POST_MYSQL_TESTS
```

필요하면 POST_MYSQL_USER/POST_MYSQL_PASSWORD를 환경에 설정한다. 기본은 기존 로컬 격리 인스턴스의 root/빈 비밀번호이며 운영 설정이 아니다.

2026-11-13: H2 게시글 57개·기존 게시판/컨텍스트 40개·격리 MySQL 게시글 58개, 총 155개 통과. 수정/조회수/첨부 선점 동시성·첨부 순서 교체·롤백·UTC 왕복·Unicode 경계를 포함한다. bootJar 빌드도 성공했다.

동일 JAR와 격리 MySQL을 사용한 실제 HTTP 16개도 통과했다. 상세/빈 첨부·태그, 세션 조회수 중복 제외, 삭제/비활성/없는 글 404, 잘못된 ID/JSON 400, 검증되지 않은 인증/변경 401, 기존 게시판 필터/빈 목록을 확인했다. 응답 집계와 DB 값이 같고 거절된 변경 요청 전후 제목·본문·삭제 상태가 유지됐다. 이미지 조회 URL은 합성 키와 example.test 기준 주소로 검증했다. 실제 저장소의 이미지 파일을 조회한 결과가 아니다.

정상 회원 작성·수정·삭제는 서버 Principal을 주입한 H2/MySQL 통합 테스트로 검증했다. 실제 로그인 토큰을 사용하는 HTTP 성공 호출·CM09 파일 업로드/저장소·공통 DB·FE 연결은 미검증이다. Postman UI는 이번 작업에서 조작하지 않았다.
