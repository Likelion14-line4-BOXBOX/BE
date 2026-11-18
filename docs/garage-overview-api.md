# 유저 개러지 종합 조회 (#30)

[이슈 #30](https://github.com/Likelion14-line4-BOXBOX/BE/issues/30)의 MG03 `GET /api/users/{userId}/garage`를 구현했다. 공개 프로필·최애·저장된 도장 요약과 마켓 조회 결과를 조합한다. [현재 명세](https://app.notion.com/p/41574200449d8180b4dcf09bd7906d8b)를 따른다.

## 공개 범위

| 조회 조건 | marketSummary | profile.showMarketAssets |
| --- | --- | --- |
| 본인 | 소유자의 실제 요약; 공개 설정과 무관 | 실제 Boolean; false도 반환 |
| 타인·자산 공개 | 대상 유저의 실제 요약 | 필드 생략 |
| 타인·자산 비공개 | null; 마켓 서비스 호출 없음 | 필드 생략 |

공개 프로필은 userId/nickname/bio/avatarAssetId/favoriteTeam/favoriteDriver만 포함하고 본인에게만 공개 설정을 더한다. 현금·비밀번호·로그인 ID·지갑 버전·계정 속성·createdAt/onboardingCompleted를 그대로 전달하지 않는다. 최애 미설정과 DB 미입력 차량 번호는 명세대로 null이다.

인증은 선택이다. 비회원도 공개 프로필·최애·저장된 도장 요약과 소유자가 공개한 자산을 조회할 수 있다. 비회원의 isOwner는 false이며 본인 전용 공개 설정은 생략한다. 인증 정보가 있으면 인증 계층이 검증한 Principal.name=loginId를 사용한다. ACTIVE/SUSPENDED 미삭제 회원의 조회를 허용하며 유효하지 않은 토큰·없는/탈퇴/삭제 요청자는401, 대상은404다. 임의 사용자 헤더/Bearer를 인증으로 사용하지 않는다. userId는 양의 정수 문자열이고 조회 조건은 받지 않는다. 잘못된 ID/조건은400이다.

도장은 기존 RaceStampService의 기본 활성 시즌(CHAT_ACTIVE_SEASON)을 사용한다. 저장된 자동 도장의 레이스 수와 DB 전체 경기 수를 반환하며 메시지 작성만 있는 기록을 도장으로 세지 않는다. 채팅방 참여 지급·삭제/퇴장 후 유지 정책을 그대로 조합하고 이 조회로 참여/도장/메시지/이벤트를 변경하지 않는다.

## 마켓 연결

`MarketGarageSummaryReader`가 `GarageMarketSummaryReader`를 구현하고 기존 `MarketPortfolioService.summary(userId, now)`에 연결한다. 요청자의 loginId로 다른 회원을 가장하지 않고, 공개 범위를 확인한 뒤 대상 userId로 읽는다. `/api/me/portfolio`와 시즌 자산·수익률·순위 계산을 공유하며 마켓 거래/정산/랭킹 생성 작업은 추가하지 않는다.

```java
public interface GarageMarketSummaryReader {
    Optional<GarageMarketSummary> find(long userId);
}
```

반환형은 `GarageMarketSummary(BigInteger totalAssets, BigDecimal returnRate, Long rank, Instant snapshotAt)`다. JSON의 필드 이름과 숫자 형식은 유지하고, 합산 자산이나 unsigned 순위가 기존 Java 숫자 범위를 넘어도 잘라내지 않는다. FE에서 큰 금액까지 정확히 다루려면 JSON 정수의 정밀도를 보존하는 처리가 필요하다.

| 필드 | 연결 기준 |
| --- | --- |
| totalAssets | 포트폴리오의 현재 라운드 보유 수량 × 현재 가격 + 실제 현금. 다른 라운드 보유량과 수량 0은 제외 |
| returnRate | 해당 라운드의 시즌 기준 자산 대비 수익률. 포트폴리오의 seasonReturnRate와 같으며 라운드 수익률과 구분 |
| rank | 해당 시즌의 최근 공개 완료 총자산 순위. 공개 시각이 미래인 집계·BUILDING·다른 시즌 제외. 최근 집계에 회원이 없으면 null |
| snapshotAt | 현재 자산 평가 시각. rank의 집계 시각과 다를 수 있음 |

순위 조회는 실제 스키마의 `ASSET`과 기존 마켓 코드의 `TOTAL_ASSETS`를 모두 읽는다. `PUBLISHED` 상태만 허용하며 순위를 직접 계산하지 않는다. 마켓 정산 생산자가 쓰는 유형과 스키마 CHECK의 차이를 변경하거나 새 집계를 생성하는 작업은 이 연결 범위에 포함하지 않는다.

거래한 적 없거나 보유 종목이 없는 회원도 정상 조회 대상이다. 실제 잔액을 반영하고 순위 미산정은 rank=null, 시즌 기준 자산 미입력·0은 returnRate=null이다. 현재 라운드가 없으면 포트폴리오와 같이 실제 현금과 null 수익률/순위를 반환한다. 거래 이력 없음 자체를 Optional.empty로 반환하지 않는다. 개러지 금액 평가에는 선수 표시 정보를 조합하지 않으므로 미입력 차량 번호로 자산 조회가 실패하지 않는다.

Optional.empty는 조회/평가 원본을 제공할 수 없는 기술적 상태를 뜻한다. adapter는 마켓 DB 읽기 실패를 empty로 전달하며 GarageService가503 MARKET_SUMMARY_UNAVAILABLE를 반환한다. 타인 비공개는 마켓 조회를 건너뛰므로 장애 중에도200/null이다. 고정 자산이나 임시 순위를 반환하지 않는다.

조합은 REPEATABLE_READ 읽기 전용 트랜잭션이다. 프로필/공개 설정/도장과 마켓 조회가 같은 DataSource·트랜잭션에 참여한다. 조회 중 다른 트랜잭션이 잔액·가격·공개 설정·도장을 바꿔도 진행 중 응답은 같은 과거 상태를 읽고 다음 요청은 새 상태를 읽는다.

## 비회원 공개 조회 수정·검증 (2026-11-19 KST)

- MG03·MG09를 인증 선택으로 변경했다. 토큰 없이 공개 프로필·최애·저장된 도장을 조회하며, 공개 자산만 반환한다. 비회원의 isOwner=false 및 본인 전용 필드 생략, 제공한 토큰의 검증, 내 프로필 조회·수정의 인증 필수 정책은 유지한다.
- `gradlew test --tests 'com.boxbox.garage.*' --tests 'com.boxbox.auth.*' bootJar --offline --console=plain`: 267개 통과, 실패/오류0, 선택 MySQL107개 미실행. 별도로 MySQL8.0.45·확인된 격리 경로/13306의 새 검증 DB와 실제 JAR에서 가입·로그인 포함 HTTP23요청·34항목 통과. 비회원/본인/타인·공개/비공개·최애·도장·빈 시즌·400/401/404·위조 헤더 및 사용자/잔액/참여 불변을 확인했다.
- FE는 순위 요약 대신 대상 userId의 MG03으로 상세를 조회한다. RM12 요청/응답 매핑도 현행 계약에 맞췄다. Chrome에서 실제 랭킹→프로필 이동, 최애·공개/비공개 자산·도장 표시, 메시지/팔로우 클릭 시 로그인 모달과 닫은 뒤 프로필 유지, 오류 콘솔 없음 확인. 로그인 화면 연결 및 회원 메시지/팔로우 기능 구현은 기존 FE 미연결 상태다.
- 현행 Notion MG03/MG09 사용자 속성·인증·처리 규칙과 공통 규칙, 로컬 명세를 동기화했다. URL·응답 필드·DB 구조는 유지하며 운영 DB 변경·배포는 수행하지 않았다.

## 실제 마켓·인증 검증 (2026-11-18 KST)

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
$env:MARKET_PREPARATION_ENABLED='false'
$env:RUN_GARAGE_MYSQL_TESTS='true'
$env:GARAGE_MYSQL_URL='jdbc:mysql://127.0.0.1:13306/boxbox_garage_market_verify?connectionTimeZone=UTC&characterEncoding=UTF-8'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
```

- 전체 **838개 통과**, 실패/오류0. MG03 H2 43개·격리 MySQL39개 포함. 다른 선택 MySQL396개는 미실행이다.
- MySQL8.0.45의 확인된 격리 데이터 경로에 새 `boxbox_garage_market_verify`·`boxbox_garage_market_http_verify`를 만들고 `deploy/db/schema.sql` 32개 테이블을 적용했다.
- 실제 adapter·계산·DB로 대상 회원 구분, 시즌 수익률, 공개 완료 순위, 차량 번호 미입력, 미참여·기준 자산 미입력/0, 큰 숫자의 손실 없는 JSON, 읽기 불변과 동시 변경 스냅샷을 검증했다.
- 같은 JAR의 임시 서버에 실제 가입·로그인 Bearer로 **HTTP 28요청·45항목 통과**. `/api/me/portfolio`의 시즌 값과 MG03 응답 일치, 공개/비공개, 빈 시즌, 오류400/401/404, 마켓 전용 DB 장애503 및 비공개 우회200을 확인했다. 임시 Java 서버는 종료했다.
- 현재 MG03 명세에 시즌 수익률·시즌 총자산 순위·현재 평가 시각 기준을 구체화했다. 경로/필드 이름/JSON 숫자 형식은 유지한다.

검증 데이터는 전용 DB의 합성 데이터다. 공통 DB·실제 FE 브라우저·배포는 미검증이다. 외부 마켓 준비/정산 자동화는 검증 실행에서 비활성화했다. Postman UI는 이번에 조작하지 않았으며 실제 HTTP 클라이언트로 상태 코드와 JSON을 확인했다.

## 이전 독립 구현 검증 (2026-11-15 KST)

```powershell
$env:RUN_GARAGE_MYSQL_TESTS='true'
.\gradlew.bat cleanTest test bootJar --offline --console=plain
```

- 신규 H2 36개+신규 격리 MySQL31개+기존 회귀668개=**735개 통과**, 실패/오류0. 다른 기능의 선택 MySQL367개는 미실행이다.
- MySQL8.0.45·127.0.0.1:13306의 기존 격리 데이터 경로를 확인하고 새 boxbox_garage_verify에 전체 ERD32개를 적용했다. 런타임 DDL과 공통 DB는 변경하지 않았다.
- 본인/타인·공개/비공개·필드 생략·null·미참여 회원 정상 결과·미연결 서비스503·빈 시즌·오류·읽기 불변을 검증했다. 공개 여부 판단 전에 마켓을 호출하지 않으며 타인 비공개는 provider가 없어도 조회된다.
- MySQL에서 조회 중 프로필/공개 설정/도장이 함께 변경돼도 진행 중 응답은 일관된 과거 상태를, 다음 응답은 커밋된 새 상태를 읽는 것을 확인했다.
- 같은 JAR·전용 DB 실제 HTTP **18요청·36항목** 통과: 공개 채팅 조회/필터/빈 목록·DB 대조, MG03 미인증/위조 헤더/Bearer401·405, 도장401·DB 불변, 테스트 provider 미포함을 확인했다.

당시 회원 MG03 200/503은 Principal 주입 MockMvc와 mock reader를 사용했고 실제 마켓 provider·실토큰 HTTP는 미검증이었다. 이 제한은 위 18일 마켓·인증 검증으로 보완했다. 공통 DB·FE·배포는 별도다.
