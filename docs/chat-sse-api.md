# 실시간 채팅 SSE 구독 (#24)

[이슈 #24](https://github.com/Likelion14-line4-BOXBOX/BE/issues/24)의 TR21 `GET /api/chat-rooms/{roomId}/events`를 `radio.chat`에 구현했다. #20의 이력 커서·접속 집계와 #22의 원자적 메시지/이벤트 저장을 재사용한다. 다른 API의 URL·요청·응답, ERD, 로그인 구현은 변경하지 않는다.

## 연결과 재접속

1. TR19 `GET /api/chat-rooms/{roomId}/messages`로 이력과 `meta.eventCursor`를 받는다.
2. `Accept: text/event-stream`, `Last-Event-ID: {eventCursor}`로 구독한다. 회원은 인증 담당 계층이 검증한 Principal을 사용한다. 미검증 Bearer나 임의 사용자 헤더로 인증하지 않는다.
3. SSE `id`를 마지막 처리 위치로 보관한다. 영속 이벤트는 순번대로 적용하고 eventId, 메시지는 messageId, 세션 구분선은 markerId로 재수신을 처리한다.
4. 끊기면 마지막 처리 `id`로 재연결한다. `RESET_REQUIRED`이면 기존 연결을 닫고 TR19 첫 페이지를 다시 조회한 후 새 `meta.eventCursor`로 연결한다. 오래된 페이지 커서의 eventCursor로 새 동기화를 시작하지 않는다.

Last-Event-ID가 없으면 연결 시점의 최신 순번부터 시작한다. 처음 연결할 때 REST 이력을 먼저 받아야 기존 메시지와 연결 사이의 공백을 재전송받을 수 있다. `STREAM_READY`는 검증된 시작 위치를 id로 보내므로 아직 새 메시지를 받지 않은 연결도 재접속 기준을 얻는다. 재전송할 중간 이벤트 하나라도 만료/누락됐다면 최신 위치로 건너뛰지 않고 RESET_REQUIRED 후 종료한다. 잘못된 형식·다른 방·미래 순번은 연결 전 400이다.

회원은 동일 회원 ID, 비회원은 서버 HttpSession ID로 여러 탭을 중복 제외한다. FE는 동일 브라우저 세션 쿠키를 함께 보내야 비회원 탭이 같은 사용자로 집계된다. 검증된 회원 구독에 별도 익명 세션을 만들지 않는다. SSE 구독은 chat_participants·첫 메시지·도장을 생성/변경하지 않는다.

브라우저 기본 EventSource에 사용자 지정 Authorization/Last-Event-ID 헤더를 직접 설정하는 기능은 없다. 현행 Bearer 계약을 사용할 FE는 fetch 스트림 클라이언트로 위 헤더와 쿠키를 전달하고 SSE 프레임을 파싱해야 한다. 토큰을 URL 쿼리에 넣지 않는다. 인증 담당이 추후 쿠키 인증을 제공하면 해당 계약에 맞춰 연결할 수 있다.

## 이벤트 계약 보완안 / 구현 기본값

아래는 기존 TR21의 추상적인 이벤트 설명을 구체화한 구현 기본값이다. 사용자가 선택한 정책으로 기록하지 않는다. Notion과 루트 현행 API 문서를 이번 작업에서 수정하지 않았다. FE는 이벤트 유형별로 payload를 처리하고 연결 종료 제어 이벤트를 구분해야 한다.

데이터 프레임은 `eventId`, `occurredAt`(UTC), `payload`를 사용하며 공통 JSON envelope는 사용하지 않는다.

| event | payload | SSE id / 재전송 |
| --- | --- | --- |
| STREAM_READY | roomId | 검증된 시작 위치. 새 영속 이벤트를 만들지 않음 |
| MESSAGE_CREATED | TR19/TR20의 메시지 객체 전체 | evt_{roomId}_{sequence} / 재전송 |
| MESSAGE_DELETED | 같은 메시지 객체, sender/content=null, isDeleted=true, canDelete=false | 영속 id / 재전송 |
| ROOM_STATUS_CHANGED | roomId, status, opensAt, closesAt | 영속 id / 재전송 |
| SESSION_CHANGED | markerId, sessionId(null 가능), label, occurredAt | 영속 id / 재전송 |
| ONLINE_COUNT_CHANGED | roomId, onlineCount | id 없음 / 연결마다 현재값 |
| RESET_REQUIRED | roomId, reason=EVENT_CURSOR_EXPIRED, historyUrl | id 없음 / 종료, REST 재조회 |
| RECONNECT_REQUIRED | roomId, reason=CONNECTION_LIFETIME | id 없음 / 종료, 마지막 id로 재연결 |
| AUTH_REQUIRED | roomId, code=UNAUTHORIZED | id 없음 / 종료, 재인증 |
| STREAM_ERROR | roomId, code=CHAT_STREAM_UNAVAILABLE | id 없음 / 종료, 재시도 또는 REST 재조회 |

id 없는 제어 이벤트의 data.eventId는 마지막 처리 위치를 반복한다. 이를 새 영속 이벤트로 중복 제거하지 않는다. 재접속 커서는 SSE의 id로만 갱신한다. heartbeat는 `:heartbeat` 주석이며 id/data가 없다. 연결 시작 시 retry=3000ms를 안내한다.

메시지는 전송 시 현재 DB 상태와 수신자 권한을 조합한다. 생성 이후 삭제된 메시지를 재전송하면 MESSAGE_CREATED도 삭제 자리표시자이며 원문·발신자는 노출하지 않는다. ACTIVE 본인만 canDelete=true이고 정지 회원은 공개 구독이 가능하되 false다. 탈퇴 발신자는 기존 공개 프로필 규약을 따른다. stream_events에는 원문·수신자별 canDelete를 저장하지 않는다.

## 상태·실제 세션·전달 보장

연결마다 가상 스레드가 커밋된 DB 이벤트를 순번순으로 최대 100개씩 읽는다. 방 잠금과 READ_COMMITTED 트랜잭션에서 상태/이벤트를 확인하고, 커밋 후 잠금을 놓은 뒤 네트워크로 보낸다. 쓰기 트랜잭션의 미커밋 이벤트나 롤백 이벤트는 전달하지 않는다. 수신 실패 시 전달 위치를 건너뛰지 않는다. 재접속으로 중복 수신할 수 있으므로 FE의 id 기반 처리가 필요하다. published_at을 전체 수신자의 전달 완료 표식으로 사용하지 않는다.

접속 중인 방의 DB 운영 시각을 확인해 저장 상태를 SCHEDULED/LIVE/ARCHIVED로 맞추고 상태 변경과 순번/이벤트를 같은 트랜잭션에 저장한다. SCHEDULED도 구독 가능하다. ARCHIVED가 되면 남은 이벤트와 상태 전환을 전송한 뒤 연결을 끝내며 새 구독은 409 CHAT_ROOM_CLOSED다. FE는 입력을 비활성화하고 이후 삭제 반영은 이력을 재조회한다. 구독자가 없는 방은 GET의 시각 기반 상태 계산을 유지하며 별도 전역 상태 스케줄러를 추가하지 않았다.

DB의 actual_starts_at/actual_ends_at만 SESSION_CHANGED로 기록한다. 예정 시각·meetings 시각으로 추정하지 않는다. 방 잠금 아래 markerId 또는 동일 세션/실제 시각의 기존 이벤트를 확인해 중복 생성을 막고 이력과 같은 session_{id}_start/end ID를 사용한다. 기존 상황 이벤트도 전달한다. 과거 실제 전환을 뒤늦게 발견하면 새 순번에 실제 occurredAt을 담는다. 이력에서 이미 본 markerId는 중복 표시하지 않는다. SESSION_CHANGED는 이력 원본이므로 만료 시각이 지나도 이 작업에서 삭제하지 않는다. 별도 보존/정리 작업은 이력·재접속 계약을 지켜야 한다.

## 실행과 제한

기존 Java 21·MySQL 8·전체 ERD 32개를 사용한다. DB_URL/DB_USERNAME/DB_PASSWORD는 기존 실행 안내와 같다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| CHAT_EVENT_RETENTION | PT10M | 기존 메시지 및 새 전환 이벤트의 재전송 기간 |
| CHAT_STREAM_POLL_INTERVAL | PT0.25S | 연결별 DB 확인 간격 |
| CHAT_STREAM_HEARTBEAT | PT15S | 연결 감지용 주석 간격 |
| CHAT_STREAM_LIFETIME | PT5M | 최대 연결 시간, 이후 재접속 |
| CHAT_STREAM_MAX_CONNECTIONS | 256 | JVM별 동시 연결 한도 |

시간/연결 수는 AI 구현 기본값이다. 연결 한도 초과는 503 CHAT_STREAM_BUSY다. 연결 전 오류는 SSE Accept에도 기존 application/json 오류로 반환한다. 없는 방은 404, 잘못된 요청·커서는 400, 인증 오류는 401이다. 연결 후 HTTP 상태를 바꿀 수 없으므로 제어 이벤트 후 종료한다. 응답은 Cache-Control: no-store와 X-Accel-Buffering: no를 설정하며 배포 프록시의 실제 버퍼링/타임아웃은 별도 검증이 필요하다.

접속 집계와 연결 한도는 단일 JVM이다. 다중 서버 전체 인원 집계·공유 세션·부하 성능은 미검증이다. 연결별 DB polling 비용도 운영 규모에 맞춰 조정해야 한다. 가상 스레드는 느린 수신자가 다른 연결을 막지 않게 하며 완료/오류/타임아웃/서버 종료 때 각 연결 토큰과 슬롯을 한 번 정리한다. IOException의 서블릿 async 종료는 컨테이너에 맡긴다([Spring MVC 문서](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)).

인증 담당의 실제 로그인·토큰 검증은 아직 연동 대기다. 서버 Principal.name=loginId 규약을 사용하고 연결 중 계정 탈퇴/정지는 재확인한다. 현재 Principal에 토큰 만료 시각이 없으므로 기존 연결의 실제 토큰 만료 시각 차단을 검증/구현했다고 주장하지 않는다. 5분 연결 수명은 재검증 기회이며 토큰 만료 검증의 대체가 아니다. 인증 계층이 만료/취소를 전달하는 규약을 제공하면 해당 시각에 종료하도록 연동해야 한다. 인증 우회는 추가하지 않았다.

## 검증

선택 MySQL 테스트는 **boxbox_chat_sse_verify 전용 DB**의 합성 픽스처를 초기화한다. 공통 DB나 기존 다른 이슈의 검증 DB에서 실행하지 않는다. MySQL 선택 변수는 CHAT_SSE_MYSQL_USER/CHAT_SSE_MYSQL_PASSWORD다.

```powershell
$env:RUN_CHAT_SSE_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_CHAT_SSE_MYSQL_TESTS
```

2026-11-14 KST, Java 21에서 위 명령으로 **SSE H2 34개 + 연결 수명/정리 10개 + SSE MySQL 36개 + 쓰기 MySQL 회귀 43개 + 조회 MySQL 회귀 65개 + 기존 H2 498개 = 686개**, 실패·오류 0, bootJar 성공. 다른 선택 MySQL 클래스는 미실행(XML 테스트 항목 skipped=240)이다. 메시지/이벤트 커밋·롤백 가시성은 MySQL data_lock_waits에서 실제 방 잠금 대기를 확인해 검증했다. 시각 전환·외부 조기 보관 시 종료 이벤트·실제 구분선 중복·권한 변경·삭제 마스킹·커서 누락/만료·100개 배치·느린 연결 격리·오류/타임아웃/종료 정리를 확인했다. H2/MySQL 테스트 안에서도 임의 포트의 실제 Tomcat HTTP 스트림을 검증했다.

기존 격리 MySQL이 꺼져 있어 기존 data 경로 BE/build/verification/mysql-20261113과 비어 있는 13306 포트를 확인해 재시작했다. MySQL 8.0.45의 실제 datadir를 재확인하고 새 boxbox_chat_sse_verify에 전체 ERD 32개를 적용했다. 다른 검증 DB·공통 DB는 변경하지 않았다.

같은 JAR·위 전용 DB·별도 임의 포트의 수동 실행 검증에서도 **56개 HTTP/SSE 검사항목**을 통과했다. 공개 조회/빈 목록/오류·위조 인증 거절과 JSON 오류, 쿠키를 공유하는 비회원 탭의 고유 인원 수, 새 커밋/삭제/삭제 후 생성 재전송, 실제 세션 구분선, 만료 커서 RESET_REQUIRED와 ARCHIVED 전환/409·DB 원자성·참가/도장 불변을 대조했다. 수동 스트림 검증의 메시지 생산자는 전용 DB의 명시적인 커밋 SQL 픽스처이며 인증된 메시지 전송 HTTP로 주장하지 않는다. 정상 회원 쓰기/개인화는 Principal을 주입한 테스트에서 검증했다. 실제 로그인 토큰·토큰 만료 시각 연동·공통 DB·FE·다중 서버·배포 프록시는 미검증이다. Postman UI는 조작하지 않았다.
