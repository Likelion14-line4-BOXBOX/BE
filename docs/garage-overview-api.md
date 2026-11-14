# 유저 개러지 종합 조회 (#30)

[이슈 #30](https://github.com/Likelion14-line4-BOXBOX/BE/issues/30)의 MG03 `GET /api/users/{userId}/garage`를 구현했다. 공개 프로필·최애·저장된 도장 요약과 마켓 조회 결과를 조합한다. [현재 명세](https://app.notion.com/p/41574200449d8180b4dcf09bd7906d8b)를 따른다.

## 공개 범위

| 조회 조건 | marketSummary | profile.showMarketAssets |
| --- | --- | --- |
| 본인 | 소유자의 실제 요약; 공개 설정과 무관 | 실제 Boolean; false도 반환 |
| 타인·자산 공개 | 대상 유저의 실제 요약 | 필드 생략 |
| 타인·자산 비공개 | null; 마켓 서비스 호출 없음 | 필드 생략 |

공개 프로필은 userId/nickname/bio/avatarAssetId/favoriteTeam/favoriteDriver만 포함하고 본인에게만 공개 설정을 더한다. 현금·비밀번호·로그인 ID·지갑 버전·계정 속성·createdAt/onboardingCompleted를 그대로 전달하지 않는다. 최애 미설정과 DB 미입력 차량 번호는 명세대로 null이다.

인증 계층이 검증한 Principal.name=loginId를 사용한다. ACTIVE/SUSPENDED 미삭제 회원의 조회를 허용하며 없는/탈퇴/삭제 요청자는401, 대상은404다. 임의 사용자 헤더/Bearer를 인증으로 사용하지 않는다. userId는 양의 정수 문자열이고 조회 조건은 받지 않는다. 잘못된 ID/조건은400이다.

도장은 기존 RaceStampService의 기본 활성 시즌(CHAT_ACTIVE_SEASON)을 사용한다. 저장된 자동 도장의 레이스 수와 DB 전체 경기 수를 반환하며 메시지 작성만 있는 기록을 도장으로 세지 않는다. 채팅방 참여 지급·삭제/퇴장 후 유지 정책을 그대로 조합하고 이 조회로 참여/도장/메시지/이벤트를 변경하지 않는다.

## 마켓 담당 구현 후 연결할 부분

현재 마켓 구현은 없다. 개러지의 프로필·도장·공개 범위는 구현했으며 **실제 마켓 결과 연결은 남아 있다.** 마켓 HTTP API 전체가 선행 조건은 아니다. 담당자의 읽기 서비스를 호출하는 adapter 1개가 아래 인터페이스를 구현해 Spring Bean으로 등록되면 GarageService가 이를 사용한다. 이 계약대로 제공하면 개러지 응답/공개 정책 코드를 다시 작성할 필요는 없다.

```java
public interface GarageMarketSummaryReader {
    Optional<GarageMarketSummary> find(long userId);
}
```

반환형은 `GarageMarketSummary(long totalAssets, BigDecimal returnRate, Integer rank, Instant snapshotAt)`다. 총자산·수익률·순위는 마켓 담당의 동일 평가 기준 결과를 전달하며 개러지는 가격/수익률/랭킹을 재계산하지 않는다. 평가 범위와 데이터 원본은 실제 마켓 조회 계약에 맞춰 adapter에서 연결한다.

거래한 적 없거나 보유 종목이 없는 회원도 정상 조회 대상이다. 평가 가능한 실제 잔액을 반영한 요약을 반환하고 순위 미산정은 rank=null, 수익률 분모0은 returnRate=null이다. 거래 이력 없음 자체를 Optional.empty로 반환하지 않는다.

Optional.empty는 조회/평가 원본을 제공할 수 없는 기술적 상태를 뜻한다. 현재처럼 provider Bean이 아직 연결되지 않은 경우도 자산 요약이 필요한 조회는503 MARKET_SUMMARY_UNAVAILABLE가 된다. 타인 비공개는 마켓 조회를 건너뛰므로200/null이다. 별도 마켓 상태 필드·고정 자산·임시 순위·계산 로직을 추가하지 않았다.

조합은 REPEATABLE_READ 읽기 전용 트랜잭션이다. 동일 DB의 프로필/공개 설정/도장 조회는 같은 스냅샷을 사용하고 마켓 결과에는 제공자의 snapshotAt을 유지한다. 실제 마켓 서비스의 읽기 계약·예외 매핑·평가 시점 연결은 연동 시 확인한다.

## 검증 (2026-11-15 KST)

```powershell
$env:RUN_GARAGE_MYSQL_TESTS='true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
```

- 신규 H2 36개+신규 격리 MySQL31개+기존 회귀668개=**735개 통과**, 실패/오류0. 다른 기능의 선택 MySQL367개는 미실행이다.
- MySQL8.0.45·127.0.0.1:13306의 기존 격리 데이터 경로를 확인하고 새 boxbox_garage_verify에 전체 ERD32개를 적용했다. 런타임 DDL과 공통 DB는 변경하지 않았다.
- 본인/타인·공개/비공개·필드 생략·null·미참여 회원 정상 결과·미연결 서비스503·빈 시즌·오류·읽기 불변을 검증했다. 공개 여부 판단 전에 마켓을 호출하지 않으며 타인 비공개는 provider가 없어도 조회된다.
- MySQL에서 조회 중 프로필/공개 설정/도장이 함께 변경돼도 진행 중 응답은 일관된 과거 상태를, 다음 응답은 커밋된 새 상태를 읽는 것을 확인했다.
- 같은 JAR·전용 DB 실제 HTTP **18요청·36항목** 통과: 공개 채팅 조회/필터/빈 목록·DB 대조, MG03 미인증/위조 헤더/Bearer401·405, 도장401·DB 불변, 테스트 provider 미포함을 확인했다.

회원 MG03 200/503은 Principal 주입 H2/MySQL MockMvc로 검증했다. 준비된 마켓 결과는 src/test의 mock reader를 사용했으며 src/main에 고정 응답을 넣지 않았다. 실제 마켓 provider·실토큰 HTTP·공통 DB·FE는 미검증이다. 마켓 연동이 남아 있어 전체 #30 완료/서비스 통합 완료와 구분한다.
