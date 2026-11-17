# AWS 배포와 main 자동 재배포

대상은 이슈 #46이다. `main`에 반영된 코드만 자동 배포한다. 기본 브랜치는 `dev`로 유지해도 된다. `workflow_dispatch` 수동 실행 버튼은 워크플로 파일이 기본 브랜치에도 있어야 표시되므로, 일반 기능 흐름대로 이 파일도 dev에 반영하거나 사용자가 기본 브랜치를 main으로 바꿀 수 있다. 수동 실행을 dev로 선택하면 테스트만 수행하며 배포하지 않는다.

## 구성

- 서울 리전 EC2(t3.medium, 4GB) 한 대에서 Java 21 Docker 이미지 실행
- 별도 RDS MySQL 8.4.11(db.t4g.micro, 암호화 gp3 20GB, 7일 백업, 삭제 보호)
- EC2와 RDS 모두 비공개 서브넷. EC2에 SSH·공개 IPv4 없음
- CloudFront VPC origin → EC2. 제공되는 `https://….cloudfront.net` 주소로 도메인 구매 없이 HTTPS 사용
- API 캐시·압축 비활성화, Authorization·Origin·쿠키·쿼리 전달. SSE 15초 heartbeat보다 긴 60초 origin read timeout. 전체 응답 완료 시간 제한은 추가하지 않음
- EC2의 패키지 설치·ECR·SSM·외부 API 접근은 NAT Gateway 한 대 사용
- 비밀값은 Secrets Manager가 생성. GitHub에는 장기 AWS 키·DB 비밀번호·JWT 키를 저장하지 않음

이는 단일 앱 서버 구성이다. 여러 대로 늘리기 전에 현재 메모리 접속자 집계·요청 제한의 공유 방식을 검토한다. 호스트 장애의 자동 대체나 무중단 배포를 제공하는 구성은 아니다. 재배포 전 후보 컨테이너의 DB 연동 조회를 확인하고, 실제 포트 교체 후 실패하면 기존 컨테이너를 다시 시작한다. 교체 중 짧은 중단과 SSE 재연결이 발생할 수 있다.

## 최초 인프라 생성

`deploy/aws.yaml`을 먼저 검토한다. 새 자원의 요금과 IAM 역할 생성 범위를 확인한 뒤 실행한다. 기존 스택이나 DB를 임의로 가져오거나 초기화하지 않는다. 기본 EC2/RDS 크기와 단일 NAT는 팀 개발용 시작값이며 부하 검증 결과가 아니다.

AWS CLI가 로그인된 서울 리전 CloudShell 또는 로컬에서 실행한다.

```bash
aws ec2 describe-managed-prefix-lists --region ap-northeast-2 \
  --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing \
  --query 'PrefixLists[0].PrefixListId' --output text

aws cloudformation validate-template --template-body file://deploy/aws.yaml
```

확인한 prefix list ID를 `CloudFrontPrefixListId` 파라미터로 전달한다. 기존 GitHub OIDC provider가 있으면 그 ARN을 `ExistingOidcProviderArn`에 지정하고, 없을 때만 비워 둔다. `AllowedOrigins`는 경로·후행 슬래시 없는 정확한 FE Origin의 쉼표 목록이다. 현재 FE 배포 주소가 없어 localhost:5173/3000과 127.0.0.1만 기본 허용한다. 공개 와일드카드는 허용하지 않는다.

CloudFormation 변경 세트로 생성 목록을 검토한 후 실행한다. `CAPABILITY_IAM`은 EC2 실행 역할·main 배포 역할·GitHub OIDC provider를 생성하는 데 필요하다. GitHub 역할은 이 저장소의 main subject만 허용한다. GitHub의 이름 기반 subject와 불변 ID 기반 subject를 각각 정확히 제한한다. PR·다른 저장소·dev는 허용하지 않는다.

스택 삭제 시에도 RDS 삭제 보호·DB 스냅샷·비밀값/ECR/S3 보존 정책 때문에 자원이 남거나 삭제가 거부될 수 있다. 서비스 종료는 별도 정리 작업이며 스택 삭제만으로 요금이 모두 중단되지 않는다. EC2 UserData나 환경 파라미터 변경만으로 실행 중 컨테이너가 갱신되는 것도 아니다. 호스트 교체/배포를 계획하고 적용한다.

## 최초 DB와 서버 실행

`deploy/db/schema.sql`은 프로젝트 루트 `docs/erd/schema.mysql.all.sql`의 2026-11-17 배포 스냅샷이다. 이슈 #46 시점에 BE 저장소 밖에 있는 전체32개 테이블을 배포 패키지에 포함했다. 이후 DB 구조 변경은 별도 마이그레이션으로 검토한다. 앱 재배포 시 이 파일을 다시 실행하지 않는다.

최초 운영자만 다음을 수행한다.

1. 스택 완료·EC2 UserData 완료(`/etc/boxbox/config.json`)·SSM Online 확인
2. `./gradlew clean test bootJar` 후 `python3 deploy/package.py` 실행
3. AWS CLI 로그인이 가능한 Docker 실행 환경에서 아래 명령 실행

```bash
export AWS_DEFAULT_REGION=ap-northeast-2
export AWS_STACK_NAME=boxbox-prod
export RELEASE_REVISION="$(git rev-parse HEAD)"
python3 deploy/publish.py --initialize-db
```

처음에만 `InitializeDocument`로 비어 있는 DB의 스키마와 기존 게시판 시드를 적용하고 별도 `boxbox_app` 계정에 SELECT/INSERT/UPDATE/DELETE만 부여한다. 일부 테이블만 있거나 다른 구조이면 자동 보정하지 않고 실패한다. 테스트 회원·로컬 계정·팀/선수·경기/채팅방/결과를 복사하거나 임의 생성하지 않는다. 팀/선수의 검증된 기준정보를 담당자가 입력하면 기존 게시판 시드를 다시 수동 실행해 팀 게시판/태그를 연결할 수 있다. 그 전에는 자유/입문자 게시판과 빈 기준정보가 정상이다.

GitHub 역할은 DB 초기화 문서를 실행할 권한이 없다. RDS 관리자 비밀값도 GitHub에 전달되지 않는다. EC2 역할은 초기화 및 실행 비밀값을 읽을 수 있지만, 컨테이너에는 앱 계정과 서명 키만 전달한다. IMDSv2 hop limit 1로 컨테이너에서 인스턴스 자격증명을 가져가는 것을 제한한다. 호스트의 환경 파일은 0600으로 만들고 Docker 실행 뒤 제거한다. Docker 관리자 권한은 컨테이너 환경을 읽을 수 있으므로 운영자 접근을 제한한다.

JDBC는 RDS CA를 가져온 JVM truststore와 `sslMode=VERIFY_IDENTITY`를 사용한다. 새 이미지 빌드 시 AWS CA bundle을 갱신한다. JWT 키는 재배포 시 재생성하지 않는다.

## GitHub Actions 연결

GitHub 저장소 Settings → Secrets and variables → Actions → Variables에 다음 **비밀이 아닌 설정값**을 저장한다.

- `AWS_STACK_NAME`: 생성한 스택 이름
- `AWS_DEPLOY_ROLE_ARN`: 스택 Outputs의 GitHubRoleArn

워크플로가 main에 들어오면 main push/병합으로 다음이 순차 실행된다.

1. Java21 자동 테스트·bootJar, 배포 실패 복구 테스트, CloudFormation lint
2. 테스트한 JAR만 Docker context에 넣어 이미지 빌드
3. OIDC 임시 AWS 인증, 커밋 SHA의 불변 ECR 이미지 게시
4. 재현 가능한 release archive를 S3의 커밋별 경로에 최초1회 게시
5. SSM 문서에 검증된 SHA·image digest만 전달하고 EC2의 준비 완료까지 대기
6. CloudFront HTTPS `/api/boards`의 상태·응답 구조 확인

같은 커밋 재실행은 기존 이미지/동일 artifact를 재사용한다. 내용이 다른 artifact로 덮어쓰지 않는다. 배포는 동시에 실행하지 않으며 진행 중 배포를 강제 취소하지 않는다. PR에서는 AWS 배포 단계를 실행하지 않고 OIDC 신뢰도 PR ref를 허용하지 않는다. 임의 원격 명령 실행 문서는 GitHub 역할에 허용하지 않는다.

ECR/S3의 기존 커밋과 SSM 배포 문서로 이전 정상 SHA/digest를 재배포할 수 있다. 최초 실행 실패에는 되돌릴 이전 컨테이너가 없고, DB 스키마는 이미지 복구로 되돌리지 않는다. EC2가 성공한 뒤 외부 HTTPS 검사가 실패한 경우 네트워크/CloudFront 문제일 수 있으므로 무조건 DB나 이미지를 되돌리지 말고 해당 계층을 확인한다.

## 실제 FE 확인

API 요청·응답 계약은 유지한다. 로그인 refresh 쿠키와 익명 채팅 세션 쿠키는 Secure/SameSite=None으로 전달한다. FE는 credentials: include를 사용한다. 브라우저의 제3자 쿠키 차단은 서버의 SameSite=None만으로 해제되지 않는다. 실제 FE 주소가 준비되면 정확한 CORS Origin과 쿠키 저장/재발급·로그아웃을 브라우저에서 확인하고, 필요하면 FE 프록시 또는 같은 사이트의 도메인 구성을 사용한다.

SSE는 기존 fetch 스트림·Bearer·Last-Event-ID 계약을 사용한다. 실제 DB에 검증된 경기/채팅방 시각이 입력되어야 운영 상태와 참여 도장을 검증할 수 있다. 배포를 위해 가짜 LIVE 경기나 일정을 만들지 않는다. 최신 main의 마켓 외부 호출은 이번 작업에서 바꾸지 않았고 MARKET_PREPARATION_ENABLED=false를 유지한다. 마켓 외부 연동·스케줄은 담당자가 별도 확인한다.

참고: [GitHub OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [CloudFront VPC origins](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html), [RDS MySQL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_MySQL.html).
