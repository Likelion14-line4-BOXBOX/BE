# 게시글·댓글·채팅 신고 (#26)

[이슈 #26](https://github.com/Likelion14-line4-BOXBOX/BE/issues/26)의 TR16 `POST /api/reports`를 `com.boxbox.radio.report`에 구현했다. dev `698fdce`에서 `feat/#26-reports`를 분리했다. 미병합 SSE PR #25의 코드·커밋을 이 브랜치에 섞지 않았으며 기존 브랜치의 문서 커밋도 보존했다.

## 요청·응답

인증 담당이 검증한 Principal.name=loginId로 신고자를 확인한다. 요청으로 reporterId를 받거나 미검증 Bearer·사용자 ID 헤더로 회원을 처리하지 않는다.

| 요청 필드 | 규칙 |
| --- | --- |
| targetType | POST / COMMENT / CHAT_MESSAGE, 필수 |
| targetId | 해당 유형의 양의 정수 ID 문자열, 필수 |
| reason | SPAM / ABUSE / INAPPROPRIATE / OTHER, 필수 |
| description | 생략 가능, 앞뒤 공백 제거 후 1000 Unicode code point 이하, 명시적 null 불가 |

OTHER는 공백이 아닌 상세 설명이 필요하다. 그 외 사유에서 상세 설명을 생략하거나 공백만 보내면 DB에는 null로 저장한다. 허용되지 않은 본문 필드와 조회 파라미터는 400이다. 성공은 201이며 공통 envelope의 data에 reportId(String), status=RECEIVED를 반환한다.

| 상태 | 코드·조건 |
| --- | --- |
| 400 | INVALID_REQUEST — 입력 형식·enum·ID·길이·OTHER 상세 설명 오류 |
| 401 | UNAUTHORIZED — 미인증·없는/탈퇴/삭제 계정 |
| 403 | REPORTING_RESTRICTED — 정지 계정 |
| 404 | REPORT_TARGET_NOT_FOUND — 없는/삭제된 대상·삭제 글/비활성 게시판의 글·댓글 |
| 409 | ALREADY_REPORTED — 동일 회원·대상·사유 중복 |

## 대상과 중복 기준

게시글은 활성 게시판의 미삭제 글, 댓글은 해당 글의 미삭제 댓글이어야 한다. 비활성 태그가 붙은 글, 삭제 부모 아래의 살아 있는 답글, 탈퇴 작성자의 남아 있는 콘텐츠는 신고할 수 있다. 채팅 메시지는 방이 종료됐어도 미삭제이면 신고 가능하다.

기존 reports의 세 UNIQUE(reporter_id, 대상 FK, reason)를 그대로 사용한다. 같은 회원이 같은 대상·사유로 다시 보내면 상세 설명·처리 상태·시각을 덮어쓰지 않고 409다. 다른 사유·다른 회원·다른 대상 유형의 동일 숫자 ID는 별개다. 처리 완료된 신고도 중복 기준에 남는다. 대상 유효성을 먼저 확인하므로 신고 뒤 대상이 삭제됐으면 재요청은 404다.

이 기준과 description 정규화·REPORTING_RESTRICTED를 [현행 TR16](https://app.notion.com/p/41574200449d817ca35ff2b6d572dbf4) 및 루트 docs/api-implementation에 반영했다. 기존 경로·요청 필드·성공 응답 필드·전체 ERD는 유지했다. FE는 계정 제한/대상 없음/중복 사유를 구분한다.

## 저장·동시성

READ_COMMITTED 트랜잭션에서 신고자 → 게시글 → 댓글 또는 신고자 → 방 → 메시지 순으로 잠근다. 기존 전송·수정·삭제 API와 같은 순서이며 부모 ID를 선조회해도 대상 잠금을 얻은 뒤 삭제/비활성 상태를 다시 확인한다. 같은 회원의 동시 신고는 직렬화하고 DB 유일성 위반도 409로 변환한다. 대상 FK 한 개만 저장한다.

신고 접수는 reports 외의 원문·반응·조회수·계정·채팅 참가·도장·stream_events를 변경하지 않는다. 신고 횟수에 따른 자동 삭제·정지·관리자 심사 API를 추가하지 않았다.

## 실행·검증

Java 21·MySQL 8·루트 전체 ERD 32개 테이블을 사용한다. 런타임 DB_URL/DB_USERNAME/DB_PASSWORD 설정은 기존 API 실행 안내와 같다. 공통 DB와 다른 검증 DB는 초기화하지 않는다.

선택 MySQL 테스트는 **boxbox_report_verify 전용 DB**만 사용하며 픽스처를 초기화한다. REPORT_MYSQL_USER/REPORT_MYSQL_PASSWORD를 지원한다. 잠금 대기 검증에는 performance_schema.data_lock_waits/data_locks 조회 권한도 필요하다.

```powershell
$env:RUN_REPORT_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_REPORT_MYSQL_TESTS
```

2026-11-15 KST 최종 실행: **신고 H2 73 + 신고 MySQL 79 + 기존 H2 498 = 650개**, 실패·오류 0, bootJar 성공. 다른 선택 MySQL 클래스는 미실행(XML skipped=240)이다. 기존 격리 MySQL 8.0.45의 datadir=BE/build/verification/mysql-20261113·13306을 확인한 뒤 새 boxbox_report_verify에 전체 ERD를 적용했다.

1000개 이모지 저장, 입력/인증/대상별 검증, FK 선택, 다른 사유/회원 독립성, 중복·동시 신고, 실패/외부 트랜잭션 롤백과 원문/반응/참가/도장/이벤트 불변을 검증했다. 실제 기존 삭제 API를 별도 트랜잭션에서 실행하고 MySQL 잠금 대기를 확인해 삭제 커밋 시 신고 404, 롤백 시 신고 접수를 확인했다. H2와 MySQL에서 같은 API 테스트를 수행했다.

동일 JAR·전용 DB·임의 포트의 실제 HTTP **33개 요청/62개 검사항목**도 통과했다. 공개 목록·필터·빈 결과·이미지·삭제 댓글·이력 커서를 DB와 대조하고 신고의 비인증/위조 헤더/미검증 Bearer 401, 잘못된 JSON/조회 조건 400, 메서드 405 및 DB 불변을 확인했다. 검증 스크립트의 조회 중복 키는 바이너리를 HEX로 읽도록 보정한 후 전체를 다시 통과했다. 임시 Java PID 21420은 경로/JAR/포트 소유자를 확인하고 종료했다. 기존 서버와 격리 MySQL은 유지했다.

정상 201·중복 409·계정 제한 403은 Principal 주입 MockMvc로 검증했다. **실제 로그인 토큰을 사용한 정상 HTTP·공통 DB·FE 연동은 미검증**이며 인증 우회를 추가하지 않았다. 로그인·토큰 검증은 다른 담당 작업이다. Postman UI 조작이나 Git 원격 쓰기는 하지 않았다.
