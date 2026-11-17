# 레이스 자동 도장과 시즌 도장판 (#28)

[이슈 #28](https://github.com/Likelion14-line4-BOXBOX/BE/issues/28)의 MG09 `GET /api/users/{userId}/race-stamps`와 TR21 실시간 참여 연동을 구현했다. 지급 기준은 **채팅방 참여**이며 메시지 작성은 필요하지 않다. [MG09 명세](https://app.notion.com/p/41574200449d8150b09ce9e175ddaebf), [TR21 명세](https://app.notion.com/p/41574200449d816b96cacb381130bebd)를 같은 정책으로 수정했다.

## 참여와 지급

- 검증된 회원의 LIVE SSE 연결에서 STREAM_READY 전송이 수용된 뒤 참여를 기록한다. 첫 메시지 전송을 기다리지 않는다.
- SCHEDULED 연결은 유지 중 LIVE가 되면 기록한다. 비회원·종료된 방의 이력 조회·단순 목록/상세 조회·연결 실패·만료 커서의 RESET_REQUIRED는 지급 경로를 실행하지 않는다.
- ChatStreamService에서 회원→방 순으로 잠근 뒤 계정과 현재 운영 시각을 재확인한다. 연결 준비나 잠금 대기 중 탈퇴/삭제·방 종료가 발생하면 새 도장을 기록하지 않는다.
- 조회 가능한 ACTIVE/SUSPENDED 미삭제 회원을 참여자로 인정한다. SUSPENDED의 메시지 전송 제한을 해제하지 않는다.
- 기존 chat_participants의 stamp_recorded_at과 stamp_trigger_type=JOIN을 사용한다. 방별 회원 PK와 레이스별 채팅방 UNIQUE로 경기별 중복을 막는다. 재연결·중복 탭은 최초 참여/도장/메시지 시각을 유지하며 last_joined_at만 갱신한다.
- 메시지만 전송하는 API는 기존대로 최초 메시지 시각을 저장하며 도장은 지급하지 않는다. 정상 채팅 화면에서는 SSE 참여가 지급 경로다. first_message_at만 있는 과거 기록을 자동 도장으로 간주하거나 소급 생성하지 않는다.
- 메시지 삭제와 퇴장 후에도 도장은 유지한다. 기존에 저장된 자동 도장의 시각/유형도 덮어쓰지 않는다.
- 도장 저장은 참여 트랜잭션으로 처리한다. 실패하면 참여/도장 변경이 함께 롤백되고 스트림은 기존 STREAM_ERROR 경로로 종료한다. 재연결 시 다시 시도할 수 있다. 도장을 위한 새 메시지·SSE 이벤트·수동 변경 API는 추가하지 않았다.

## MG09 조회

검증된 Principal.name=loginId를 사용하며 임의 헤더나 Bearer 문자열을 사용자 ID로 해석하지 않는다. 요청자 미인증·없음·탈퇴·삭제는 401이다. 본인/타인의 도장판을 조회할 수 있고 자산 공개 설정과 무관하다. 대상 유저 없음·탈퇴·삭제는 404 USER_NOT_FOUND다.

userId는 양의 정수 문자열이다. season은 선택이며 1000~9999 네 자리 연도만 받는다. 생략 시 기존 CHAT_ACTIVE_SEASON/boxbox.chat.active-season 설정(기본 2026)을 사용한다. 지원하지 않는 조회 조건과 잘못된 ID/시즌은 400 INVALID_REQUEST다.

시즌 DB 경기 전체를 round_number,id 오름차순으로 조회한다. 채팅방이 없는 경기와 도장이 없는 경기도 포함하고 recordType/source/recordedAt을 모두 null로 반환한다. 저장된 도장은 WATCHED/CHAT/UTC 기록 시각으로 반환한다. watchedCount는 해당 시즌 도장 레이스 수, totalRaces는 해당 시즌 DB 경기 수다. 경기 없는 시즌은 두 값 0과 items=[]를 반환한다. 고정 이미지나 외부 F1 호출을 추가하지 않았다.

조회는 REPEATABLE_READ 읽기 전용 트랜잭션으로 대상 유저·경기·도장 상태를 조회하며 참여/도장/메시지/이벤트를 변경하지 않는다. 기존 ERD의 테이블·키·컬럼을 그대로 사용한다.

## 검증 (2026-11-15 KST)

```powershell
$env:RUN_STAMP_MYSQL_TESTS='true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
```

격리 MySQL 8.0.45, 127.0.0.1:13306의 새 boxbox_stamp_verify DB에 전체 ERD 32개를 적용했다. 기존 격리 서버의 데이터 경로와 DB 이름을 확인했으며 공통 DB와 다른 기능 검증 DB는 사용하지 않았다. MySQL 선택 테스트는 위 환경 변수가 true일 때만 실행된다. STAMP_MYSQL_USER/PASSWORD로 검증 계정을 지정할 수 있다.

- 신규 H2 53개 + 신규 MySQL 61개 + 기존 H2 615개 = **729개 통과**, 실패/오류 0. 다른 기능의 선택 MySQL 테스트 334개는 미실행이다.
- 메시지 없는 정상 입장·인증된 SSE 컨트롤러·예정→LIVE·익명/이력/실패/만료 커서 제외·탈퇴 경계·중복 탭·재연결·메시지 삭제 후 유지·시즌/타인/빈 목록/null·오류 응답을 검증했다.
- MySQL에서 실제 방 종료·회원 탈퇴의 잠금 대기를 관찰했다. 커밋/롤백 후 상태 재확인, 메시지 전송과 참여의 동시 실행, 미커밋/롤백 도장의 조회 차단을 검증했다.
- 같은 JAR와 전용 DB의 실제 HTTP/SSE **26요청·47항목** 통과: 공개 채팅 조회·필터·빈 목록·DB 집계 대조, 비회원 SSE·연결 해제, MG09 미인증/위조 헤더/Bearer 401, 400/404/405/409와 DB 불변을 확인했다.

회원 SSE 지급과 MG09 200은 Principal 주입 H2/MySQL MockMvc 및 실제 Hub 흐름의 검증이다. 실제 토큰을 사용한 HTTP 성공·토큰 만료·공통 DB·FE 연결은 미검증이며 런타임 인증 우회는 추가하지 않았다. SSE 연결/접속 수는 기존 단일 JVM 기반을 유지한다.
