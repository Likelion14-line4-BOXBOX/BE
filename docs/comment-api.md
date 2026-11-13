# 댓글·대댓글 (#14)

[이슈 #14](https://github.com/Likelion14-line4-BOXBOX/BE/issues/14)의 TR08~TR11을 `com.boxbox.radio.board.comment`에 구현했다. 기존 게시글·공통 응답·Principal 규약과 DB 테이블을 재사용하며 API 명세의 경로·요청/응답 필드는 변경하지 않았다.

| API | 성공 응답 |
| --- | --- |
| `GET /api/posts/{postId}/comments` | 200, items/nextCursor/hasNext |
| `POST /api/posts/{postId}/comments` | 201, 생성한 댓글 전체 필드 |
| `PATCH /api/comments/{commentId}` | 200, commentId/content/updatedAt |
| `DELETE /api/comments/{commentId}` | 204, 응답 본문 없음 |

## 기반·실행

`feat/#14-comments`는 #10의 `e345ab5`에서 분기했다. 선행 [PR #11](https://github.com/Likelion14-line4-BOXBOX/BE/pull/11)에 의존하며 PR 대상은 dev다. 선행 병합 후 실제 merge/squash/rebase 방식에 맞춰 기준과 diff를 정리한다. 댓글 구현은 #12 검색 코드에 의존하지 않는다. #12 브랜치와 PR #13은 별도로 보존했다.

Java 21·MySQL 8과 기존 users/boards/posts/comments/comment_likes가 필요하다. 기존 게시판/게시글 모델이 사용하는 나머지 테이블도 준비한다. 전체 스키마는 프로젝트 루트의 `docs/erd/schema.mysql.all.sql`이며 운영 스키마를 자동 생성/수정하지 않는다.

[게시글 실행 안내](post-api.md)의 DB_URL/DB_USERNAME/DB_PASSWORD를 재사용한다. 댓글 자체는 이미지 저장소에 의존하지 않는다. 정상 게시글 상세의 이미지 검증에는 기존 POST_MEDIA_BASE_URL 설정이 필요할 수 있다.

```powershell
.\gradlew.bat bootRun
```

## 인증·입력·권한

기존 BoardViewerResolver가 반환한 검증된 서버 Principal.name=loginId 가정을 사용한다. 인증 담당과 최종 규약을 맞춰야 하며 로그인·토큰 검증을 구현하지 않았다. 사용자 ID 헤더·Bearer 문자열을 사용자로 해석하지 않는다.

- 비회원 조회가 가능하고 likedByMe=null이다. 회원은 comment_likes 관계에서 상태를 읽는다. 존재하지 않거나 탈퇴/삭제된 계정은 401이며 정지 계정의 조회도 기존 게시글 규약대로 401이다.
- 쓰기는 인증 필수다. 정지 계정은 403 WRITING_RESTRICTED, 타인 댓글 변경은 403 NOT_COMMENT_AUTHOR다.
- content는 strip 후 1~1000 코드 포인트다. 공백만/잘못된 타입/null/1001자/잘못된 Unicode는 400이다. 원래 본문 대신 정리한 문자열을 저장한다.
- POST의 parentId 생략/null은 최상위 댓글이다. 문자열 양의 정수 ID만 받으며 부모는 같은 글의 미삭제 최상위 댓글이어야 한다. 없는/다른 글/삭제 부모는 404 COMMENT_NOT_FOUND, 대댓글의 대댓글은 400 INVALID_REQUEST다.
- PATCH는 content 필수이며 그 외 필드는 받지 않는다. 작성자·게시글·부모를 변경할 수 없다. 삭제되거나 접근할 수 없는 댓글은 수정/삭제할 수 없다.
- 활성 게시판의 미삭제 글에만 댓글 조회/작성이 가능하다. 조회/작성 대상 글이 없으면 404 POST_NOT_FOUND다. 삭제 글·비활성 게시판에 속한 댓글의 수정/삭제는 404 COMMENT_NOT_FOUND다.

## 삭제·집계·동시성

삭제는 content=NULL, deleted_at/updated_at 기록으로 처리한다. 작성자 ID와 댓글/좋아요 관계는 DB에 보존하지만 응답에서는 삭제 댓글의 author/content를 null, isDeleted=true, likeCount=0으로 마스킹한다. 회원의 삭제 댓글 likedByMe는 false, 비회원은 null이다. TR08의 삭제 자리표시자 규칙에 따라 답글 없는 댓글과 삭제 답글도 자리표시자로 유지한다.

부모 삭제로 기존 답글을 삭제하거나 다른 부모로 옮기지 않는다. replyCount는 직접 연결된 미삭제 답글 수이고 대댓글은 0이다. 삭제 부모도 살아 있는 답글 수를 반환한다. 게시글 commentCount는 기존 쿼리가 미삭제 댓글을 집계하므로 별도 카운터를 중복 저장하지 않는다. 댓글 좋아요 등록/취소 API는 다음 이슈다.

쓰기는 회원 → 게시글 → 댓글 순서로 잠그고 부모·삭제·작성자 상태를 현재 읽기로 확인한다. 부모 삭제와 답글 생성은 같은 글 잠금으로 직렬화된다. 답글이 먼저 커밋되면 부모 삭제 후에도 유지되고, 부모 삭제가 먼저면 답글 생성은 404다. 수정/삭제 경합에서도 삭제 댓글을 되살리지 않는다. 실패는 트랜잭션 전체를 롤백한다.

DATETIME은 UTC LocalDateTime으로 저장·조회하고 ISO UTC로 응답한다. JVM 기본 시간대에 맡기지 않는다.

## 목록·커서

명세의 평면 배열과 parentId를 유지한다. 조회 순서는 생성 시각·ID 오름차순이라는 구현 기본값이며, size는 댓글/답글/자리표시자 행 수 기준으로 기본 20, 1~100이다. 답글이 부모와 다른 페이지에 있을 수 있어 FE는 parentId로 연결한다. 부모별 중첩 배열이나 스레드 단위 페이지 계약을 추가하지 않았다.

커서는 버전·postId·size·첫 조회 시각·댓글 ID 상한·마지막 생성 시각/ID를 포함한다. 게시글/size 변경, 잘못된 커서는 400이다. 마지막 페이지와 빈 목록은 hasNext=false/nextCursor=null이다. 시각과 ID 상한을 함께 유지해 같은 시각에 생성된 후속 AUTO_INCREMENT 댓글도 제외한다. 커서 경계 댓글이 삭제돼도 자리표시자/정렬 위치를 유지한다.

커서는 위치 정보이며 인증 증명이 아니다. 매 요청의 인증과 게시글 접근 상태를 다시 확인한다. 새 댓글의 페이지 유입은 제한하지만 기존 댓글 수정·삭제·좋아요·답글 집계는 각 요청의 DB 상태를 반영한다. 전체 페이지의 영구 스냅샷을 저장하지 않는다.

조회는 읽기 전용 REPEATABLE_READ이며 댓글과 집계를 한 SQL로 읽는다. 항목별 추가 조회나 게시글 상세 서비스를 호출하지 않고 조회수/dedup/게시글 시각을 변경하지 않는다.

## 검증 실행

```powershell
.\gradlew.bat cleanTest test --offline --console=plain
```

H2 댓글 전용 스키마는 기존 post-schema에 댓글 ID·부모 FK·좋아요 관계를 보강한다. 기존 테스트 스키마/픽스처를 수정하지 않았다. H2는 보충 문자 길이를 UTF-16으로 계산하므로 테스트 물리 길이에 여유를 두고 실제 VARCHAR(1000)의 1000개 이모지 저장은 MySQL에서 검증한다.

MySQL 테스트는 `127.0.0.1:13306/boxbox_comment_verify` 전용이다. 비어 있는 로컬 격리 DB에 전체 ERD를 먼저 적용한다. 테스트는 이 전용 DB의 관련 데이터를 초기화하므로 공통 DB에서 실행하지 않는다. COMMENT_MYSQL_USER/COMMENT_MYSQL_PASSWORD를 설정할 수 있으며 기존 격리 환경의 기본값은 root/빈 비밀번호다.

```powershell
$env:RUN_COMMENT_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_COMMENT_MYSQL_TESTS
```

2026-11-13: H2 댓글 62개+격리 MySQL 댓글 62개+기존 게시판/CRUD/컨텍스트 H2 97개=221개 통과. 부모/권한/삭제/본문/Unicode/UTC/페이지와 삭제·답글·수정 경합을 검증했고 bootJar도 성공했다. 기존 게시글 MySQL 선택 클래스는 이번에 실행하지 않았다. #12 검색 코드는 이 브랜치에 포함하지 않았으며 선행 병합 후 통합 검증해야 한다.

동일 JAR·격리 MySQL 서버의 실제 HTTP 26개도 통과했다. 목록·삭제 부모/답글·커서 전체 순서·빈 목록·400/401/404와 기존 게시글 상세의 댓글 수를 DB와 대조했다. 댓글 목록과 거절된 변경 요청 전후 댓글/좋아요/게시글 상태가 같았고 게시글 조회수는 최초 상세 호출 한 번만 증가했다. Postman UI는 조작하지 않았다.

정상 회원 작성·수정·삭제/개인화는 서버 Principal을 주입한 H2/MySQL 통합 테스트로 검증했다. 실제 로그인 토큰을 사용하는 HTTP 성공 호출·공통 DB·FE 연결은 미검증이며 인증 담당과 연결해야 한다.
