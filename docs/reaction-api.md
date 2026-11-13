# 게시글·댓글 좋아요와 스크랩 (#16)

[이슈 #16](https://github.com/Likelion14-line4-BOXBOX/BE/issues/16)의 TR12~TR15, TR23~TR24를 `com.boxbox.radio.board.reaction`에 구현했다. 선행 검색·댓글 PR이 병합된 dev `d7786e3`에서 분기한 `feat/#16-reactions`이며 PR 대상은 dev다.

| API | 성공 응답 |
| --- | --- |
| `PUT /api/posts/{postId}/like` | 200, postId/likedByMe/likeCount |
| `DELETE /api/posts/{postId}/like` | 204, 본문 없음 |
| `PUT /api/posts/{postId}/bookmark` | 200, postId/bookmarkedByMe/bookmarkCount |
| `DELETE /api/posts/{postId}/bookmark` | 204, 본문 없음 |
| `PUT /api/comments/{commentId}/like` | 200, commentId/likedByMe/likeCount |
| `DELETE /api/comments/{commentId}/like` | 204, 본문 없음 |

요청 본문·쿼리는 필요 없다. 문자열 양의 정수 ID와 기존 공통 응답/예외 처리를 사용한다. 잘못된 ID는 400 INVALID_REQUEST다.

## 실행·인증

Java 21·MySQL 8과 기존 스키마가 필요하다. 루트 `docs/erd/erdcloud-import.sql`의 post_reactions/comment_likes를 재사용하며 DB 구조·카운터 컬럼·이미지 API를 추가하지 않았다. DB_URL/DB_USERNAME/DB_PASSWORD는 [게시글 실행 안내](post-api.md)를 따른다. 반응 API 자체에는 이미지 저장소가 필요 없다.

```powershell
.\gradlew.bat bootRun
```

인증 담당이 검증 후 설정하는 Principal.name=loginId 가정을 기존 BoardViewerResolver와 함께 사용한다. 사용자 헤더·Bearer 문자열을 회원으로 해석하지 않는다. 인증 필터·로그인·토큰 발급은 구현하지 않았다.

모든 변경은 로그인 필수이며 없는/탈퇴/삭제 계정은 401 UNAUTHORIZED다. 정지 계정은 등록·취소 모두 403 REACTION_RESTRICTED다. 본인/타인 글과 댓글에 반응할 수 있지만 다른 회원의 반응을 취소할 수 없다.

## 재시도·조회·삭제

- 같은 회원/대상/유형의 PUT은 관계와 최초 created_at을 보존하고 현재 전체 개수와 true를 반환한다. DELETE는 본인 관계만 지우며 이미 미등록이어도 204다.
- 좋아요와 스크랩은 서로 독립이다. post_reactions의 복합 PK(post_id,user_id,type), comment_likes의 복합 PK(comment_id,user_id)를 유지한다.
- 게시글 상세·목록·검색과 댓글 목록은 이미 이 테이블을 집계한다. 별도 카운터를 동기화하거나 기존 조회 계약을 변경하지 않는다. 반응 요청은 글/댓글 본문·수정 시각·조회수·조회 중복 제외 기록을 변경하지 않는다.
- 없는/삭제 글·비활성 게시판의 글은 404 POST_NOT_FOUND다. 없는/삭제 댓글 또는 삭제 글·비활성 게시판에 속한 댓글은 404 COMMENT_NOT_FOUND다. 이 대상의 DELETE도 404이며 숨겨진 관계를 임의로 지우지 않는다.
- 비활성 태그의 아카이브 글과 대댓글에도 반응할 수 있다. 삭제 부모의 살아 있는 답글은 유지하며 반응할 수 있다. 삭제 댓글의 기존 좋아요 관계는 보존되고 조회에서는 0/false(비회원 null)로 마스킹된다.

## 동시성

기존 글/댓글 변경과 같은 회원 → 활성 게시판을 확인하는 게시글 JOIN → 댓글 순서로 잠근다. 댓글의 post_id는 변경 불가 값으로 먼저 읽고 잠금 안에서 글·댓글의 접근 가능 상태를 다시 확인한다. 등록·취소·집계가 한 트랜잭션 안에서 실행되며 실패하면 롤백한다.

READ_COMMITTED를 명시해 댓글의 글 ID 조회 뒤 잠금을 기다리더라도 집계가 과거 스냅샷에 머물지 않게 했다. 같은 대상 변경은 잠금으로 직렬화하고 복합 PK로 유일성을 보장한다. 응답 개수는 해당 트랜잭션의 처리 직후 값이며 응답 이후 다른 요청이 바꿀 수 있다. 기존 JOIN 잠금 방식에 따라 같은 게시판의 변경도 서로 대기할 수 있다.

반응이 먼저 커밋되면 이후 삭제 시 관계는 보존되고 공개 조회에서 숨겨진다. 대상 삭제가 먼저 커밋되면 이후 반응 등록·취소는 404다. 런타임 인증 우회나 가짜 로그인 경로를 추가하지 않았다.

## 명세 보완 제안

API 경로·요청·성공 응답은 기존 명세대로다. 현재 TR12~TR15 개별 Status에는 403 항목이 없지만 공통 규칙에 403이 있고 TR23/24에는 이용 제한이 명시돼 있다. 이슈의 이용 제한 처리와 맞춰 여섯 API 모두 정지 계정은 403 REACTION_RESTRICTED로 구현했다.

보완안은 TR12~TR15 상태표에 `403 REACTION_RESTRICTED — 이용 제한`을 추가하는 것이다. FE는 정지 계정의 등록·취소 실패 메시지를 처리하고 BE는 해당 상태를 일관되게 거절한다. Notion 페이지와 루트 현행 명세 파일은 이번에 수정하지 않았으며 제안과 실제 문서 변경을 구분한다.

## 검증

전용 H2 테스트와 기존 회귀 테스트를 실행할 수 있다.

```powershell
.\gradlew.bat test --offline --console=plain
```

MySQL 선택 테스트는 로컬 `127.0.0.1:13306/boxbox_reaction_verify`만 사용한다. 비어 있는 격리 DB에 전체 ERD 32개 테이블을 적용해야 한다. 테스트가 이 DB의 데이터를 초기화하므로 공통 DB에서 실행하지 않는다. REACTION_MYSQL_USER/REACTION_MYSQL_PASSWORD를 지원하며 이번 격리 환경은 root/빈 비밀번호였다. MySQL 전용 잠금 대기 검증에는 performance_schema.data_locks/data_lock_waits 읽기 권한이 필요하다.

```powershell
$env:RUN_REACTION_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_REACTION_MYSQL_TESTS
```

2026-11-13: H2 반응 48개+격리 MySQL 반응 49개+기존 H2 회귀 282개=379개, 실패·오류 0개. 미인증/계정 상태/대상 삭제·아카이브/조회 상태/UTC 등록 시각/재시도/동시 등록·취소·삭제 경합을 검증했다. MySQL은 다른 트랜잭션이 글 잠금을 잡은 동안 시작된 댓글 반응이 잠금 대기 후 최신 전체 개수를 반환하는지도 검증했다. 기존 프로필·게시글·검색·댓글의 MySQL 선택 클래스는 실행하지 않았다(파라미터 확장 전 메서드 기준 skipped=98).

동일 JAR·별도 실제 서버에서 HTTP 32개와 DB 값 대조를 통과했다. 공개 글·댓글 반응 집계/인기 정렬/빈 목록/미인증·위조 헤더·미검증 Bearer/잘못된 ID/없는 조회 대상과 거절 전후 DB 불변을 확인했다. 최초 상세 조회 이후 목록·거절 요청은 조회수/dedup도 유지했다. Postman UI는 조작하지 않았다.

정상 회원의 등록·취소(200/204), 정지 계정(403), 삭제 대상(404)과 개인화는 서버 Principal을 주입한 H2/MySQL MockMvc·서비스 통합 테스트로 검증했다. 실제 로그인 토큰으로 HTTP 성공 변경·공통 DB·FE 연결은 미검증이며 인증 담당과 연결해야 한다. 원격 push·PR 게시·병합은 사용자가 진행한다.
