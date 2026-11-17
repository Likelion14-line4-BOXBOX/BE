# AWS 단기 시연 배포와 main 자동 재배포

이슈 #46. 사용자는 2026-11-17 일주일 정도의 연동/시연과 월 기본 $12~20 예산을 선택했다. 최종 구성은 서울 AWS Lightsail 2GB 한 대에서 Spring·MySQL·Nginx를 실행하는 방식이다. 앞서 검토한 EC2/RDS/NAT/CloudFront/S3/Secrets Manager 구성은 사용하지 않는다. GitHub 반영은 이번 #46에 한해 사용자에게 push·PR 게시·main 병합 실행 승인을 받았다.

## 구성과 비용

- Ubuntu24.04, Lightsail small_3_0(2GB/2vCPU/IPv4 포함), 월 최대 $12의 시간 과금. 168시간이면 서버 기본 약 $2.8이며 세금·트래픽 초과·ECR 이미지 저장 등은 별도다.
- 고정 IPv4를 인스턴스에 연결한다. 인터넷에는 SSH22·ACME80·HTTPS443만 열고, 앱8080은 호스트 loopback에만 공개한다. MySQL3306은 호스트에 공개하지 않는다.
- MySQL8.4는 Docker volume boxbox-mysql-data에 저장한다. Spring 재배포/롤백에서 DB 컨테이너·볼륨을 삭제하거나 스키마를 재실행하지 않는다.
- 앱1GB·MySQL512MB·Nginx96MB 제한과 호스트1GB swap을 시작값으로 사용한다. 소수 인원 시연용이며 대규모 부하/고가용성을 보장하지 않는다. 재배포 때 기존 앱을 멈추고 새 앱을 시작하므로 짧은 중단과 SSE 재연결이 있다.
- 외부 HTTPS는 Let's Encrypt의 공개 IP 인증서로 제공한다. 도메인/DNS 서비스가 필요 없다. IP 인증서는 약160시간으로 일주일보다 짧아 12시간마다 갱신 확인하고 Nginx를 다시 읽게 한다. 발급 전 운영자가 CA 약관 동의 범위를 확인해야 한다.
- 동일 호스트의 비공개 Docker MySQL 통신은 비TLS이며 API 인터넷 구간은 HTTPS다. MySQL 앱 계정에는 SELECT/INSERT/UPDATE/DELETE만 부여한다. DB/JWT 비밀값은 최초1회 생성한 호스트 /etc/boxbox/secrets.json(0600)에 유지하고 GitHub/로그/릴리스 파일에 넣지 않는다. Docker 관리자는 환경을 읽을 수 있으므로 관리자 접속을 제한한다.

## 최초 배포

1. deploy/aws.yaml 변경 세트 검토 후 서울 리전에 생성한다. GitHub OIDC provider가 이미 있으면 ExistingOidcProviderArn을 지정한다. InstanceName 기본은 boxbox-demo다. API 주소는 스택 Outputs의 ApiUrl이다.
2. 생성된 호스트의 UserData 완료를 확인한다. AWS CLI가 인증된 Docker/Linux 환경에서 `bash gradlew clean test bootJar` 후 `python3 deploy/package.py`를 실행한다.
3. CA 약관 동의까지 확인한 운영자만 다음을 실행한다.

```bash
export AWS_DEFAULT_REGION=ap-northeast-2
export AWS_STACK_NAME=boxbox-demo
export RELEASE_REVISION="$(git rev-parse HEAD)"
python3 deploy/publish.py --initialize-db
```

최초 작업은 비어 있는 DB에만 deploy/db/schema.sql과 기존 게시판 시드를 적용한다. schema.sql은 프로젝트 루트 docs/erd/schema.mysql.all.sql의 2026-11-17 스냅샷32개 테이블이다. 일부 테이블만 있으면 자동 복구하지 않는다. 로컬 계정·테스트 데이터·팀/선수·경기/결과를 복사하거나 임의 생성하지 않는다. 검증된 팀/선수 기준정보는 담당자가 입력하고 기존 게시판 시드로 팀 게시판/태그를 연결한다. 그 전에는 자유/입문자 게시판과 빈 팀 목록이 정상이다.

## GitHub 연결

저장소 Actions Variables에 `AWS_STACK_NAME`과 스택 Outputs의 `AWS_DEPLOY_ROLE_ARN`(GitHubRoleArn 값)을 설정한다. 비밀값이 아닌 구성값이다. main 반영 시 다음이 실행된다.

1. Java21 테스트·JAR 빌드, 배포 복구 테스트, CloudFormation lint, 실제 Docker/MySQL HTTP/SSE 검증
2. 이 저장소 main만 허용한 OIDC 임시 AWS 인증
3. 커밋 SHA의 불변 ECR 이미지 게시
4. Lightsail API에서 임시 SSH 인증서와 호스트 공개키 수신
5. AWS가 제공한 호스트 키를 고정해 엄격 검증한 SSH/SCP로 릴리스 전송·체크섬 대조
6. 일시 ECR 인증으로 호스트에서 image digest를 내려받아 앱 교체, 준비 실패 시 이전 앱 복구
7. 공개 HTTPS /api/boards 상태와 JSON 확인

장기 AWS 키나 SSH 개인키를 GitHub Secrets에 저장하지 않는다. CI가 받는 임시 SSH 접속은 해당 서버의 관리 권한이므로 main 반영 권한을 관리한다. PR/dev는 AWS 역할을 맡을 수 없고 배포 단계도 실행하지 않는다. 일상 재배포는 --initialize-db를 호출하지 않으며 GitHub 환경에서는 이 초기화 옵션을 거부한다. main의 신뢰된 코드 자체는 서버 관리 권한을 가지므로 IAM으로 특정 셸 명령만 제한하는 구조는 아니다.

기본 브랜치 dev를 유지해도 main push 자동 배포는 동작한다. 수동 Run workflow 버튼은 워크플로 파일이 기본 브랜치에도 있어야 표시된다. 수동으로 dev를 선택하면 테스트만 수행한다.

## FE와 종료

업무 API 요청·응답 계약은 유지한다. 현재 AllowedOrigins는 localhost/127.0.0.1의5173·3000 및 사용자 지정 https://project-boxbox.vercel.app이다. FE 주소가 바뀌면 정확한 Origin을 호스트 config.json에 반영하고 재배포한다. credentials: include와 Secure/SameSite=None 쿠키의 실제 브라우저 저장·회전·로그아웃을 별도 확인한다. 제3자 쿠키 차단은 SameSite만으로 해제되지 않아 필요하면 FE 개발 프록시/같은 사이트 도메인 구성을 사용한다.

SSE는 기존 fetch/Bearer/Last-Event-ID 계약이며 Nginx 버퍼링/캐시를 끄고 읽기 제한360초를 적용한다. 실제 경기/방 데이터 없이는 운영 LIVE SSE를 검증했다고 주장하지 않는다. MARKET_PREPARATION_ENABLED=false를 유지하고 팀원의 마켓 외부 호출 정책은 이번 범위에서 변경하지 않는다.

일주일 뒤 자동 삭제하지 않는다. Lightsail은 중지만 해도 요금이 계속 발생한다. 종료 요청을 받으면 필요한 DB 덤프·수동 스냅샷을 보관하고, 인스턴스 삭제·고정 IP 해제·ECR/기타 저장 자원을 따로 정리한다. 스택은 DB 실수 삭제를 막으려고 Lightsail/고정 IP/ECR을 Retain하므로 스택 삭제만으로 과금이 끝나지 않는다. 단일 서버 고장/삭제 시 데이터와 비밀값을 함께 잃을 수 있어 필요한 시연 데이터는 종료 전 별도로 보관한다.

참고: https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html · https://docs.aws.amazon.com/lightsail/2016-11-28/api-reference/API_GetInstanceAccessDetails.html · https://letsencrypt.org/2026/03/11/shorter-certs-certbot
