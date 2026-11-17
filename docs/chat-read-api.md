# 채팅방·메시지 이력 조회 (#20)

[이슈 #20](https://github.com/Likelion14-line4-BOXBOX/BE/issues/20)의 TR17~TR19를 `com.boxbox.radio.chat`에 구현했다. dev `0f0f6e7`에서 `feat/#20-chat-history`를 분리했다. 기존 common.api·검증된 Principal·회원/경기/채팅 테이블을 재사용하며 API 경로·요청/응답 필드와 전체 ERD를 변경하지 않았다.

| API | 주요 응답 |
| --- | --- |
| `GET /api/chat-rooms` | 방 목록·nextCursor·hasNext |
| `GET /api/chat-rooms/{roomId}` | 방 상태·운영 시각·인원·canSendMessage |
| `GET /api/chat-rooms/{roomId}/messages` | 메시지·timelineMarkers·nextCursor·hasNext·meta.eventCursor |

세 API 모두 비회원 조회가 가능하다. 잘못된 ID/조건/커서는 공통 400 INVALID_REQUEST, 없는 방은 404 CHAT_ROOM_NOT_FOUND다. TR19만 별도 History 메타 DTO로 eventCursor를 포함하며 기존 공통 ApiResponse.Meta와 다른 API의 메타 필드는 유지한다.

## 실행·시즌·일정

Java 21·MySQL 8과 기존 전체 ERD 32개 테이블이 필요하다. DB_URL/DB_USERNAME/DB_PASSWORD는 [게시글 실행 안내](post-api.md)를 따른다. 채팅 조회는 이미지 저장소나 추가 외부 F1 호출을 사용하지 않는다.

```powershell
$env:CHAT_ACTIVE_SEASON = '2026'
.\gradlew.bat bootRun
```

목록의 season 생략은 CHAT_ACTIVE_SEASON(boxbox.chat.active-season)을 사용한다. 2026은 이번 구현 기본값이며 사용자가 확정한 시즌 정책이 아니다. 시즌은 네 자리 연도이고 size는 기본20·1~100이다. status는 SCHEDULED/LIVE/ARCHIVED이며 생략하면 해당 시즌 전체다. 상세에는 별도 쿼리를 받지 않고 이력은 size/cursor만 받는다.

운영 시각은 DB chat_rooms.opens_at/closes_at을 그대로 사용한다. 저장된 ARCHIVED는 우선하며, 그 외에는 opensAt 이전 SCHEDULED, opensAt 이상·closesAt 미만 LIVE, closesAt 이상 ARCHIVED다. DB 상태 갱신이 늦어도 조회 응답의 시각 상태는 계산하지만 GET이 저장 상태·archived_at을 바꾸거나 이벤트를 생성하지 않는다. 잘못되거나 미입력된 일정을 meetings 주말 시각에서 추정하지 않으며 방 준비/일정 입력은 담당 작업이다.

방 목록은 closesAt 내림차순·ID 내림차순이라는 구현 기본값이다. ARCHIVED 필터는 최신 종료 순이다. 일반 목록의 구체적인 정렬은 현행 명세에 별도 지정이 없으며 사용자가 고른 정책으로 기록하지 않는다.

## 회원 상태·권한·인원

기존 BoardViewerResolver의 검증된 Principal.name=loginId 규약을 사용한다. 비회원은 canSendMessage/canDelete=false다. ACTIVE 회원은 LIVE 방에서 전송 가능 표시를 받고, SUSPENDED 회원은 공개 조회가 가능하되 두 표시 모두 false다. 없는/탈퇴/삭제 계정 Principal이나 미검증 Bearer는 401이다. 사용자 ID 헤더로 인증하지 않는다.

canDelete는 TR25의 본인 소유권 규칙에 맞춰 ACTIVE 회원의 미삭제 본인 메시지만 true다. 방이 ARCHIVED여도 본인 메시지의 이 표시는 유지한다. 종료 방의 전송은 차단되며 메시지 삭제 API 자체는 후속 이슈다. 종료 방에서 본인 삭제까지 금지하려면 TR25와 상태 오류 계약을 함께 확정해야 한다.

participantCount는 chat_participants의 누적 고유 회원 수다. onlineCount는 ChatPresenceRegistry에 등록된 현재 연결의 고유 identity 수로, 같은 회원의 여러 탭은 한 명이다. 미래 SSE 계층이 검증된 회원 ID 또는 서버 익명 세션 identity로 connect하고 반환한 Connection을 오류/종료 시 close해야 한다. 오래된 연결의 중복 close가 새 연결을 제거하지 않으며 GET은 연결을 등록하지 않는다.

현재 Registry는 단일 JVM 기반이다. SSE 연결 생산자와 다중 서버 공용 집계는 아직 구현하지 않았다. 연결이 없으면 onlineCount=0이며 누적 참여자 수로 대체하거나 임의 값을 만들지 않는다. 실제 접속 수 연동 완료로 간주하지 않는다.

## 메시지·구분선

이력은 최근 메시지부터 createdAt·ID 내림차순의 평면 배열이다. 오래된 페이지를 읽은 후 FE가 표시 순서에 맞춰 합친다. 삭제 메시지도 행/ID/시각/clientMessageId를 유지하되 sender/content=null, canDelete=false로 마스킹한다. 탈퇴·삭제 발신자는 기존 공개 프로필 규약의 ‘탈퇴한 사용자’/avatar_default다.

실제 구분선은 race_sessions.actual_starts_at/actual_ends_at과 저장된 stream_events의 SESSION_CHANGED만 사용한다. 예정 starts_at/ends_at은 구분선을 만들지 않는다. 기록이 없으면 []다. SESSION_CHANGED payload는 label 필수, sessionId 선택/null, markerId 선택이며 없으면 event_{DB id}를 사용한다. sessionId는 해당 방의 경기 세션이어야 한다. 잘못된 서버 기록은 공통 503 CHAT_HISTORY_UNAVAILABLE이다.

같은 실제 세션 전환이 두 출처에 존재하면 이벤트의 label을 우선하고 ID는 session_{sessionId}_start/end로 통일한다. 이후 이벤트 기록이 추가돼도 같은 구분선의 ID를 유지한다. 상황 구분선은 보존된 이벤트가 있어야 하며 후속 이벤트 보관/정리 작업에서도 이력에 필요한 SESSION_CHANGED를 유지해야 한다. 조회 자체는 만료/정리 작업을 수행하지 않는다.

구분선은 페이지의 가장 오래된 메시지 시각 이상부터 직전 페이지 경계 미만의 범위를 사용한다. 첫 페이지는 첫 조회 시각까지 포함한다. 같은 시각의 메시지가 여러 페이지에 걸려도 구분선을 중복하지 않는다. 마지막 페이지는 더 오래된 실제 기록까지 포함한다. 메시지 없는 방에도 실제 기록이 있으면 구분선만 반환할 수 있다.

## 커서·SSE 연결 계약

방 커서는 상태/시즌/size·첫 조회 시각·방 ID 상한·마지막 종료 시각/ID를 검증한다. 상태 필터와 응답 상태는 해당 순회의 첫 시각 기준이며 새 조회에서 현재 시각으로 갱신한다. 신규 방 유입을 ID 상한으로 제한하지만 일정·저장 상태의 직접 변경까지 전체 페이지에서 불변으로 저장하지 않는다.

메시지 커서는 roomId/size·첫 조회 시각·메시지 ID 상한·첫 이벤트 순번·마지막 메시지 시각/ID를 검증한다. 다른 방/size, 미래 시각·잘못된 위치·현재 방보다 앞선 이벤트 순번은 400이다. 삭제 자리표시자를 유지해 페이지 경계도 유지한다. 이후 새 메시지 유입은 제한하지만 기존 메시지 삭제/발신자 상태·실제 전환 기록의 페이지 사이 변경은 반영될 수 있다.

TR19는 읽기 전용 REPEATABLE_READ에서 방의 stream_sequence와 메시지/구분선을 읽는다. meta.eventCursor는 `evt_{roomId}_{streamSequence}`라는 불투명 문자열이며 과거 페이지도 첫 페이지의 순번을 유지한다. FE는 그대로 Last-Event-ID에 전달한다. 후속 TR21은 이 형식을 검증하고 해당 순번 이후 이벤트를 재전송해야 한다.

후속 전송·삭제·실제 전환 기록 저장은 메시지/세션 변경·chat_rooms.stream_sequence 증가·stream_events 저장을 같은 트랜잭션으로 커밋해야 한다. 이력 페이지만 더 읽다가 최신 순번으로 건너뛰어 새 이벤트를 잃지 않게 하는 계약이다. 실제 SSE 전송·재접속·상태 전환 이벤트 발행은 이번에 구현하지 않았다.

## 검증

```powershell
.\gradlew.bat cleanTest test bootJar --offline --console=plain
```

MySQL 선택 테스트는 기존 data 경로 BE/build/verification/mysql-20261113·13306을 확인한 뒤 새 boxbox_chat_read_verify에 전체 ERD 32개를 적용해 실행했다. CHAT_READ_MYSQL_USER/CHAT_READ_MYSQL_PASSWORD를 지원한다. 관련 데이터 초기화가 있으므로 공통 DB나 다른 검증 DB에서 실행하지 않는다.

```powershell
$env:RUN_CHAT_READ_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_CHAT_READ_MYSQL_TESTS
```

2026-11-14: 채팅 H2 65개+시각/접속 정책 9개+격리 MySQL 66개+기존 H2 381개=521개, 실패·오류 0개. 상태 경계·정지 계정 조회·인원 구분·동시 연결/정리·삭제 마스킹·실제 구분선·동률 페이지·신규 유입·커서 조건·첫 이벤트 순번 유지를 검증했다. MySQL은 다른 트랜잭션이 메시지 삭제·방 순번 변경을 커밋하는 동안에도 이력 본문/이벤트 커서가 같은 스냅샷을 읽는지 확인했다. 기존 MySQL 선택 클래스는 미실행(XML 파라미터 확장 전 메서드 기준 skipped=161)이다. bootJar·diff 검사도 통과했다.

같은 JAR·새 전용 MySQL·임의 포트 실제 서버에서 HTTP 38개와 DB 값 대조를 통과했다. 정상 목록·상세·이력·필터·시즌·빈 결과·커서 전체 순회·마커 중복·UTC·400/401/404와 조회 전후 방/참여·도장/메시지/이벤트/세션 불변을 확인했다. Postman UI는 조작하지 않았다. 임시 서버 PID 39240은 실행 경로·JAR·전용 DB·임의 포트를 확인해 종료했다. 기존 서버·격리 MySQL은 유지했고 다른 검증 DB·공통 DB는 변경하지 않았다.

정상 회원·정지 회원 개인화는 서버 Principal 주입 H2/MySQL MockMvc로 검증했다. 실제 로그인 토큰·공통 DB·FE·실제 SSE 접속 집계/구독 연동은 미검증이다. 런타임 인증 우회는 없으며 전송·삭제·SSE·도장 지급은 후속 이슈다. 원격 push·PR 게시·병합은 사용자가 진행한다.
