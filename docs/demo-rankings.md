# 데모 랭킹 입력

기존 boxbox1~4 계정과 2026년 20라운드 경기로 라운드/시즌 자산·수익률 랭킹을 만든다. 사용자 잔액·거래·포지션·정산 계산값은 변경하지 않는다. 원래 제공 SQL은 마켓 라운드 부재와 구형 CHECK 때문에 입력0건이었고, 아래 두 SQL로 준비/입력을 분리했다.

1. DB를 선택하고 기존 ranking_snapshots 정의와 대상 데이터를 백업한다.
2. `deploy/db/migrations/ranking-storage-types.sql`을 실행한다. 현재 코드의 TOTAL_ASSETS/RETURN_RATE 저장 타입을 허용하며 기존 ASSET/ROUND_RETURN/SEASON_RETURN/RANK_GAIN 값과 scope/round_id 제약은 유지한다. MySQL ALTER TABLE은 암묵적으로 COMMIT된다.
3. `deploy/db/demo-rankings.sql`을 batch mode로 실행한다. mysql `--force`는 사용하지 않는다.

데모 계정4명 ACTIVE·미삭제, 20라운드 FINISHED·입력된 과거 본선 예정 시각을 검증한다. 누락된 마켓 연결은 SUSPENDED·open_at=NULL·demo-ranking-only-v1로 만든다. 현재 마켓 선택과 거래/자동 정산 대상에서 제외된다. close_at은 저장된 본선 예정 시각, publish_at은 실제 종료 시각이 있을 때 해당 시각+24시간이다. 기존 마켓 행이 데모 전용 조건과 다르면 중단한다.

랭킹은 ROUND/SEASON × TOTAL_ASSETS/RETURN_RATE 총4개와 계정별 항목16개다. 순위1~4의 데모 손익은 58000/37000/12500/-8000, 자산은 100만원+손익, 수익률은 5.8/3.7/1.25/-0.8%다. 실제 지갑·정산 자산을 계산한 결과가 아닌 화면 확인용 값이다. 기존 공개/생성 중 랭킹은 덮어쓰지 않고 같은 SQL을 재실행해도 중복 생성하지 않는다.

2026-11-18 운영 반영 시 별도 검증 도구로 원래 마켓/랭킹 행의 모든 컬럼, 나머지29개 테이블건수와 회원 잔액을 COMMIT 전에 확인했다. root0700 `/opt/boxbox/demo-rankings-ready-20261118-approved`에 root0600 백업·검토 SQL·결과를 보관했다. 마켓1/스냅샷4/항목16 추가, 기존값·현재21라운드·재실행 불변을 확인했다. 앱 재배포는 하지 않았다.

외부 API 계약은 유지한다. RM12의 요청 type은 ASSET/ROUND_RETURN/SEASON_RETURN이며 DB 저장 타입과 구분한다. `/api/rankings` 기본 조회, 라운드/시즌 자산·수익률, roundId 필터·커서·오류 응답을 확인한다.
