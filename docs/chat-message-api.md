# 채팅 메시지 전송·삭제 (#22)

[이슈 #22](https://github.com/Likelion14-line4-BOXBOX/BE/issues/22)의 TR20/TR25를 `radio.chat`에 구현했다. 사용자가 갱신한 dev `ca00952`에서 `feat/#22-chat-messages`를 분리했다. 기존 회원·참가·메시지·이벤트 테이블과 공통 응답·검증된 Principal을 재사용하며 스키마 변경은 없다.

| API | 성공 | 주요 오류 |
| --- | --- | --- |
| `POST /api/chat-rooms/{roomId}/messages` | 201, 기존 Message DTO | 400 INVALID_REQUEST, 401 UNAUTHORIZED, 403 CHAT_RESTRICTED, 404 CHAT_ROOM_NOT_FOUND, 409 CHAT_ROOM_CLOSED / CLIENT_MESSAGE_CONFLICT |
| `DELETE /api/chat-rooms/{roomId}/messages/{messageId}` | 204, 본문 없음 | 400 INVALID_REQUEST, 401 UNAUTHORIZED, 403 CHAT_RESTRICTED / NOT_MESSAGE_AUTHOR, 404 CHAT_ROOM_NOT_FOUND / MESSAGE_NOT_FOUND |

## 전송·재시도·삭제

전송은 clientMessageId와 content만 받는다. UUID는 전체 8-4-4-4-12 형태의 16진수이며 소문자로 정규화한다. content는 strip 후 비어 있지 않은 1~500 Unicode code point이고 잘못된 surrogate는 거절한다. 정규화한 본문의 UTF-8 SHA-256을 저장한다. ID와 본문 검증 실패는 공통 400이다.

검증된 Principal.name(loginId)의 ACTIVE 회원만 변경할 수 있다. SUSPENDED는 403 CHAT_RESTRICTED, 없는/탈퇴/삭제 계정·미인증·미검증 Bearer는 401이다. 헤더의 임의 사용자 ID를 인증으로 사용하지 않는다.

새 전송은 DB opens_at 이상, closes_at 미만이고 저장 상태가 ARCHIVED가 아닐 때만 허용한다. 방 잠금을 기다린 뒤 시각과 최신 운영 시간을 확인한다. 운영 시간이 끝난 방의 새 요청은 409 CHAT_ROOM_CLOSED다.

같은 방·회원·clientMessageId의 동일 정규화 본문 재시도는 기존 메시지를 201로 반환한다. 메시지 ID/생성 시각과 참가·도장·순번·이벤트는 변하지 않는다. 다른 본문은 409 CLIENT_MESSAGE_CONFLICT다. 다음은 이번 구현 기본값이며 리뷰 시 정책 확인 대상이다.

- 방이 종료돼도 이미 성공한 미삭제 메시지의 동일 재시도는 성공한다.
- 이미 삭제한 UUID의 재사용은 동일 본문도 409 CLIENT_MESSAGE_CONFLICT로 처리한다. 원문을 복원하지 않는다.
- UUID 범위는 방·회원 단위다. 다른 방/다른 회원은 같은 UUID를 독립적으로 사용할 수 있다.

삭제는 방에 속한 메시지인지 확인한 뒤 소유권을 검사한다. 종료 방에서도 본인 메시지를 삭제할 수 있어 기존 조회 canDelete와 일치한다. content=NULL, deleted_at을 저장하고 ID·UUID·해시·생성 시각은 유지한다. 이력 조회는 sender/content=null, isDeleted=true, canDelete=false다. 본인의 재삭제는 204이며 다른 회원은 이미 삭제된 메시지에도 403이다.

## 트랜잭션·참가·이벤트

READ_COMMITTED 트랜잭션에서 회원 → 방 → 메시지 순서로 행을 잠근다. 방을 잠가 다른 회원의 전송도 순번을 잃지 않게 하고 UUID 유일키와 함께 중복 생성을 막는다. 같은 회원의 채팅 변경도 직렬화되므로 높은 처리량의 잠금 세분화는 별도 측정 후 검토한다. 일정/상태를 변경하는 후속 작업도 방 행 잠금과 같은 순번 계약을 따라야 한다.

첫 전송은 참가 행이 없으면 생성하고 first_message_at을 기록한다. 기존 참가 행은 누락된 first_message_at만 채우며 입장 시각·기존 첫 메시지·도장 필드를 보존한다. 자동 도장 지급과 실제 온라인 연결 등록은 하지 않는다.

메시지 생성/삭제, 참가 변경, chat_rooms.stream_sequence 증가와 stream_events 삽입은 한 트랜잭션이다. 이벤트 저장 실패 시 앞선 변경도 롤백된다. 실제 변경에만 순번을 하나 증가시키고 MESSAGE_CREATED/MESSAGE_DELETED를 저장하며 published_at은 NULL이다.

이벤트 JSON은 다음 참조만 보관한다. 삭제 원문·작성자 전용 canDelete는 저장하지 않는다.

```json
{"messageId":"123","roomId":"1","clientMessageId":"ed071fe4-2f53-47d0-ab3c-994bf49ca979"}
```

후속 SSE는 커밋된 순번 이후 참조를 조회하고 현재 메시지 상태·수신자별 canDelete를 조합해야 한다. 생성 이벤트를 처리하기 전에 삭제됐다면 현재 삭제 자리표시자를 전달하며 원문을 재생하지 않는다. 이번 작업에는 SSE 발행·재접속·정리 작업이 없다. TR19의 새 조회 eventCursor는 커밋된 순번을 반영하고 과거 페이지는 첫 순번을 유지한다.

`CHAT_EVENT_RETENTION` / `boxbox.chat.event-retention`은 양수 Duration이며 기본 PT10M이다. 메시지 이벤트 expires_at에 적용하는 AI 구현 기본값이다. 과거 이력에 필요한 SESSION_CHANGED의 보존/정리 정책을 변경하지 않는다.

## 명세 보완 제안과 연동

URL·요청·성공 응답 필드는 현행 계약을 유지했다. TR25의 개별 오류 표에는 NOT_MESSAGE_AUTHOR만 있어, 정지 계정에 대한 **403 CHAT_RESTRICTED 추가**를 제안한다. 이유는 기존 canDelete=false 및 채팅 이용 제한과 변경 권한을 일치시키기 위해서다. FE는 권한 부족과 이용 제한 메시지를 구분해야 하며 BE는 위 코드를 반환한다. Notion과 루트 현행 API 문서는 이번에 수정하지 않았다. 종료 후 재시도·삭제 UUID 재사용의 위 기본값도 명세 명시 대상이다.

## 검증 (2026-11-14 KST)

Java 21, MySQL 8.0.45, 127.0.0.1:13306의 격리 data 경로를 확인하고 새 `boxbox_chat_write_verify`에 전체 ERD 32개를 적용했다. 기존 검증 DB·공통 DB는 변경하지 않았다.

```powershell
$env:RUN_CHAT_WRITE_MYSQL_TESTS = 'true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
Remove-Item Env:RUN_CHAT_WRITE_MYSQL_TESTS
```

선택 테스트는 위 전용 DB의 픽스처를 초기화한다. CHAT_WRITE_MYSQL_USER/CHAT_WRITE_MYSQL_PASSWORD를 지원하며 공통 DB에서 실행하지 않는다. MySQL 잠금 대기 검증에는 performance_schema.data_lock_waits/data_locks 조회 권한도 필요하다.

H2 쓰기 43개 + MySQL 쓰기 44개 + 전용 MySQL 조회 회귀 65개 + 기존 H2 455개 = **607개, 실패·오류 0**, bootJar 성공. 기존 선택 MySQL 클래스는 미실행(XML 메서드 기준 skipped=189)이다. 실제 500 emoji 저장·UUID 재시도/충돌·동시 작성/삭제·순번·롤백·조회 커서와 MySQL 잠금 대기 후 종료 판정을 검증했다. H2가 생성 시각까지 생성키로 반환하는 차이는 ID 열만 요청하도록 보정했다.

같은 JAR·전용 DB·임의 포트 실제 서버에서 HTTP **45개** 통과. 공개 조회/필터/빈 목록/커서/오류·DB 값 대조와 POST/DELETE의 비인증·위조 헤더·미검증 Bearer 401, 잘못된 JSON 400 및 DB 불변을 확인했다. 임시 서버 PID 15728은 실행 경로·JAR·전용 DB를 대조하고 종료했다. 기존 서버·MySQL은 유지했다.

정상 POST 201/DELETE 204와 회원 권한은 서버 Principal 주입 H2/MySQL MockMvc로 검증했다. **실제 로그인 토큰을 사용한 성공 HTTP·공통 DB·FE·SSE 연동은 미검증**이며 런타임 인증 우회는 없다. Postman UI는 조작하지 않았다. push·PR 게시·병합은 사용자가 진행한다.
