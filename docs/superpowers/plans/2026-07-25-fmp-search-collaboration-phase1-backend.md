# Find-My-Pet 함께 찾기 — Phase 1 백엔드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실종 소식마다 수색그룹을 자동으로 만들고, 개인과 팀이 승인 흐름을 거쳐 그 그룹에 참여하며, 보호자가 한 번의 종료로 전체 수색을 일관되게 끝낼 수 있는 백엔드를 `animal` 서비스에 구현한다.

**Architecture:** 수색그룹 권한은 어떤 테이블에도 복사하지 않고 `보호자 ∪ 활성 직접멤버 ∪ 활성 팀지원의 활성 팀원 − 활성 차단` 합집합을 단일 native 쿼리로 매번 계산한다(`SearchGroupAccessResolver`). 실종 소식 상태를 바꿀 수 있는 모든 경로(`POST /post`, `PUT /post`, `PATCH /post/renewal-status`, `DELETE /post/{id}`, `POST /search-groups/{id}/end`)는 단일 `SearchLifecycleService` 를 통과해, 어떤 API 로도 그룹 보관을 우회할 수 없게 한다. 멱등성은 별도 idempotency 저장소 없이 자연키 UNIQUE + `UPDATE ... WHERE status = :expected` 조건부 전이로 확보한다.

**Tech Stack:** Kotlin 1.9.25 · Spring Boot 3.2.4 (Web MVC) · JDK 17 · MySQL 8.4 · Hibernate 6 / Spring Data JPA · QueryDSL 5.0.0 · `NamedParameterJdbcTemplate` (권한 read model) · Flyway 10.19.0 · Testcontainers `mysql:8.4` · `org.woo:http:0.2.1` 응답 래퍼 · `org.woo:domain-auth:0.2.2` Passport 인증.

**설계 근거 문서:** `docs/superpowers/specs/2026-07-25-fmp-search-collaboration-design.md` (승인됨, 2026-07-25). 이 계획은 그 문서의 **§4.1 1단계(그룹·팀·승인·마이페이지)** 백엔드만 다룬다. 지도(§4.2)와 채팅(§4.3), 그리고 프런트엔드는 별도 계획서로 진행한다.

**명시적 범위 제외:**
- 설계 §3.7/§11 **카카오톡 공유**는 프런트엔드 `ShareButtons` 재사용이므로 백엔드 작업이 없다. 유일한 백엔드 의존물은 공개 CTA 엔드포인트 `GET /posts/{postId}/search-group`(Task 4)이며, 공유 링크 방문자가 현재 참여 정책에 따라 즉시 참여/신청 CTA 를 받는 것이 그 응답의 `viewerAction` 이다.
- 설계 §16.6 **sitemap 제외 / noindex** 는 `find-my-pet-frontend` 범위다.
- 설계 §14.1 의 7단계 중 **4(지도 fan-out 중단)** 는 phase 2, **5(채팅 읽기 전용 전환)** 는 phase 3 에서 완성된다. phase 1 은 나머지 5단계와, 그 두 단계가 읽을 `search_group.status = ARCHIVED` 상태를 확정한다.
- 설계 §9 이벤트 중 `SIGHTING_CREATED`(phase 2), `CHAT_MENTIONED`·`GROUP_SYSTEM_EVENT`(phase 3)는 **enum 상수만 이번에 선언**하고 발행하지 않는다. 롤링 배포 중 구버전 replica 가 모르는 타입 문자열을 만나면 알림 목록 전체가 500 이 되기 때문이다.

---

## Global Constraints

이 절의 규칙은 **모든 태스크의 요구사항에 암묵적으로 포함**된다. 각 항목은 실제 코드에서 file:line 으로 검증된 사실에 근거한다.

1. **새 의존성 추가 금지.** 특히 `spring-boot-starter-validation` 은 classpath 에 없다 — `@Valid` / `@field:NotBlank` 는 **아무것도 검증하지 않는다**. 입력 길이·공백 검증은 서비스 코드에서 명시적으로 하고 `BusinessException(ErrorCode.INVALID_COLLABORATION_INPUT)` 을 던진다.
2. **`suspend fun` 에 `@Transactional` 을 붙이지 않는다.** `PostService.registerPost`(PostService.kt:50)의 `@Transactional` 은 `withContext(Dispatchers.IO)` 블록을 감싸지 못한다 — 트랜잭션은 코루틴 작업 전에 이미 커밋된다. DB 작업 단위는 항상 non-suspend 빈에 둔다.
3. **새 멤버십/연결 엔티티에 `@SQLDelete` 를 붙이지 않는다**: `SearchGroupMember`, `SearchGroupUserBlock`, `SearchGroupTeam`, `TeamMember`, `SearchGroupEvent`. soft-delete 된 행은 UNIQUE 자연키 슬롯을 영구히 점유해 재가입을 불가능하게 만든다(`post_bookmark` 에 이미 존재하는 실제 버그: V7:29 + PostBookmark.kt:18,20 + BookmarkService.kt:24-27). 생명주기는 `status` 전이로만 표현한다.
4. **BaseEntity 를 상속하는 모든 새 테이블은 `created_at` / `updated_at` / `deleted_at` 세 컬럼을 반드시 갖는다.** 컬럼이 없으면 Hibernate 의 모든 SELECT 가 `Unknown column 'deleted_at'` 으로 죽는다. 이 사고는 이미 한 번 났고 `V10__add_deleted_at_to_abandoned_animal.sql` 이 그 수습이다. 멤버십 테이블도 컬럼은 갖되 값은 항상 NULL 로 둔다.
5. **상태 전이는 항상 `UPDATE ... WHERE id = :id AND status = :expected` 조건부 업데이트.** JDBC URL 에 `useAffectedRows` 가 없어 Connector/J 가 `CLIENT_FOUND_ROWS` 로 동작하므로 `INSERT ... ON DUPLICATE KEY UPDATE` 의 영향 행 수는 "변경됨" 을 뜻하지 않는다. 조건부 UPDATE 는 목표 상태를 술어에서 배제하므로 0행이 항상 "이미 다른 상태" 로 명확하다. 알림은 **1행이 실제로 바뀐 경우에만** 발행한다.
6. **`@Transactional` 안에서 duplicate-key 를 catch 해 재조회하지 않는다.** 트랜잭션이 rollback-only 로 오염되어 커밋 시 `UnexpectedRollbackException` → 500 이 된다. 자연키 선조회 + 조건부 전이로 duplicate 자체가 나지 않게 한다.
7. **엔티티 id 는 반드시 `repository.save(...)` 반환값에서 읽는다.** `BaseEntity` 는 필드 초기화(JUG UUIDv7)와 `@UuidGenerator(TIME)`(Hibernate UUIDv1) 두 생성기를 동시에 갖는다(BaseEntity.kt:21-24).
8. **`PostRepository.findById` 를 새 코드에서 쓰지 않는다.** `Post` 는 `@SQLDelete` 만 있고 `@SQLRestriction` 이 없어 soft-delete 된 글이 그대로 조회·변경된다(Post.kt:19-22, PostService.kt:238). Task 2 에서 추가하는 `findByIdAndDeletedAtIsNull(id: UUID): Post?` 만 쓴다. 기존 `Post` 엔티티에 `@SQLRestriction` 을 추가하지 않는다 — FULLTEXT native 쿼리와 QueryDSL projection 이 이미 `deleted_at IS NULL` 을 직접 쓰고 있어 배포된 검색 동작이 바뀐다.
9. **새 컨트롤러에서 `passport.requireUserContext()` 를 직접 호출하지 않는다.** 표시 이름은 `runCatching { passport.requireUserContext().userName.toString() }.getOrNull()`(SightingController.kt:40 패턴)로 얻어 멤버십 행의 `user_name` 에 비정규화 저장한다. 읽기 경로가 auth 서비스를 타지 않게 한다.
10. **권한 판정에 `passport.role` 을 쓰지 않는다.** `Role.ROLE_ADMIN` 은 서비스 전체 관리자 권한이며 설계 §2 는 제품 역할로서의 admin 을 금지한다. `SearchGroupAccessResolver` 는 `userId: UUID` 만 받는다. `GroupRole` / `TeamRole` 에 ADMIN 값을 두지 않는다.
11. **모든 신규 엔드포인트는 `@PublicEndPoint` 또는 `@AuthenticationUser` 중 하나를 반드시 갖는다.** `PassportInterceptor` 가 `/api/**` 에 default-deny 로 걸려 있어 둘 다 없으면 403 이다(PassportInterceptor.kt:53-54). `@AuthenticationUser` 의 `isRequired` 기본값은 **true**. 공개 + 선택적 인증은 `@PublicEndPoint` + `@AuthenticationUser(isRequired = false) passport: Passport?` 조합(PostController.kt:94-104).
12. **이 백엔드는 401 을 내지 않는다.** 미인증은 403 `FORBIDDEN` 이다. 401 은 게이트웨이 소관(설계 §5.4). 공개 CTA 응답은 상태코드가 아니라 본문의 `viewerAction = LOGIN_REQUIRED` 로 로그인 필요를 알린다.
13. **모든 child mutation 은 부모 id 를 포함한 전체 tuple 로 조회한다**(설계 §16.1). 리포지토리 메서드 시그니처 자체가 `findByIdAndGroupId(id, groupId)` 형태여서, 다른 그룹의 `membershipId` 를 넣으면 null 이 나오도록 강제한다.
14. **사용자 노출 문구는 설계 §2 어휘만 사용한다**: 수색그룹 / 보호자 / 팀장 / 팀원 / 함께 찾기 / 확인할 요청 / 수색 종료 / 우리 팀의 지원 종료. `admin`, `관리자`, `그룹장` 금지.
15. **알림 제목·본문·로그·메트릭 label 에 좌표, 전화번호, 차단 사유를 넣지 않는다**(설계 §9, §16.5). 차단 사유는 보호자 전용 `GET /search-groups/{groupId}/blocks` 응답에만 존재한다.
16. **멱등성은 자연키 UNIQUE + 조건부 상태 전이로 달성한다.** phase 1 에는 `Idempotency-Key` 헤더도 dedup 테이블도 만들지 않는다. 설계 §18 이 client request ID 를 요구하는 대상(가입·승인·지원 연결·메시지 작성) 중 phase 1 항목은 모두 자연키를 가지며, 실제로 클라이언트 생성 ID 가 필요한 것은 phase 3 의 채팅 메시지뿐이다.
17. **Flyway 실패는 전체 배포를 막는다.** `FlywayConfig` 가 부팅마다 `repair()` → `migrate()` 를 돌기 때문에(FlywayConfig.kt:9-14) 실패한 V12 는 이후 모든 배포를 차단한다. MySQL 은 DDL 트랜잭션이 없으므로 V12 는 CREATE → ALTER → 백필 순서로 배치해 위험한 문장을 마지막에 둔다.
18. **테스트는 `classpath:db/migration,classpath:db/testsupport` 를 함께 로드해야 한다.** `V2__migrate_post.sql` 이 0바이트라(운영 `post` 는 Flyway 이전에 만들어졌다) 운영 체인만으로는 깨끗한 DB 가 만들어지지 않고 V3 에서 실패한다. `SearchFulltextIT.kt:179` 가 선례다.
19. **새 IT 는 `@Transactional(propagation = Propagation.NOT_SUPPORTED)` 를 유지한다**(SearchFulltextIT.kt:46 선례). 테스트 트랜잭션이 살아 있으면 커밋 타임 제약 위반과 실제 동시성이 감춰진다.
20. **커밋 규약**: `feat(search-group): ...`, `feat(team): ...`, `fix(post): ...`, `test(search-group): ...`, `chore(ci): ...`, `docs(prd): ...`. 브랜치는 `develop` 에서 분기하고, `develop` push 는 자동으로 이미지 빌드 → 배포 dispatch 를 태운다(약 5.5분).

---

## File Structure

### 신규 패키지

```
src/main/kotlin/com/park/animal/
├── searchgroup/
│   ├── SearchGroupController.kt              # 공개 CTA·그룹 상세·수색 종료·활동 (Task 4)
│   │                                         #  + 참여 정책 핸들러 (Task 6 이 추가)
│   ├── SearchGroupMembershipController.kt    # 직접 참여 생명주기 6종
│   ├── SearchGroupBlockController.kt         # 보호자 차단 3종
│   ├── SearchGroupTeamSupportController.kt   # 팀 지원 연결 5종
│   ├── SearchGroupService.kt                 # 공개 CTA·상세·종료·활동 (Task 4) + 정책 변경 (Task 6)
│   ├── SearchGroupMembershipService.kt
│   ├── SearchGroupBlockService.kt
│   ├── SearchGroupTeamSupportService.kt
│   ├── SearchLifecycleService.kt             # 수색 생명주기 단일 진입점 (설계 §14.1)
│   ├── SearchGroupEventRecorder.kt           # 감사 기록 (설계 §20)
│   ├── GroupNotificationPublisher.kt         # 수신자 계산 · 중복 제거 · 한국어 문구 (설계 §9)
│   ├── access/
│   │   ├── GroupAccess.kt                    # GroupAccess · GroupRole · AccessSource
│   │   └── SearchGroupAccessResolver.kt      # 유일한 권한 판정 진입점
│   ├── entity/
│   │   ├── SearchGroup.kt
│   │   ├── SearchGroupMember.kt
│   │   ├── SearchGroupUserBlock.kt
│   │   ├── SearchGroupTeam.kt
│   │   ├── SearchGroupEvent.kt
│   │   └── SearchGroupEnums.kt               # JoinPolicy · SearchGroupStatus · ArchivedReason
│   │                                         #  · SearchGroupMemberStatus · SearchGroupTeamStatus
│   │                                         #  · SearchGroupEventType
│   ├── repository/
│   │   ├── SearchGroupRepository.kt
│   │   ├── SearchGroupMemberRepository.kt
│   │   ├── SearchGroupUserBlockRepository.kt
│   │   ├── SearchGroupTeamRepository.kt
│   │   ├── SearchGroupEventRepository.kt
│   │   └── SearchGroupAccessQueryRepository.kt   # NamedParameterJdbcTemplate 권한 read model
│   └── dto/
│       └── SearchGroupDtos.kt                # 요청·응답 DTO 전부 (한 파일, 기존 NotificationDtos 패턴)
├── team/
│   ├── TeamController.kt
│   ├── TeamMembershipController.kt
│   ├── TeamService.kt
│   ├── TeamMembershipService.kt
│   ├── entity/{Team.kt, TeamMember.kt, TeamEnums.kt}
│   ├── repository/{TeamRepository.kt, TeamMemberRepository.kt}
│   └── dto/TeamDtos.kt
├── searchhub/
│   ├── SearchHubController.kt                # GET /api/v1/me/search-hub
│   ├── SearchHubService.kt
│   └── dto/SearchHubDtos.kt
└── post/
    └── PostWriteService.kt                   # 신규: non-suspend @Transactional 등록 단위작업
```

책임 경계 원칙: **함께 바뀌는 것을 함께 둔다.** 컨트롤러는 리소스별로 쪼개고(한 클래스에 20개 엔드포인트를 넣지 않는다), DTO 는 도메인당 한 파일로 모은다(기존 `NotificationDtos.kt` 관례). 권한 판정은 `access/` 한 곳에만 존재하며 서비스는 그 결과(`GroupAccess`)만 읽는다.

### 수정되는 기존 파일

| 파일 | 변경 |
|---|---|
| `.github/workflows/ci.yml` | 백엔드 테스트 job 추가 (Task 0) |
| `common/http/error/ErrorCode.kt` | 함께 찾기 ErrorCode 11개 추가 (Task 1) |
| `common/http/error/GlobalExceptionController.kt` | `HttpMessageNotReadableException` · `MissingRequestHeaderException` 매핑 추가 (Task 1) |
| `notification/repository/NotificationRepository.kt` | 정렬 tiebreaker (Task 1) |
| `notification/NotificationService.kt` | 정렬 tiebreaker (Task 1), `createStructured*` 추가 (Task 5) |
| `notification/entity/Notification.kt` | 구조화 컨텍스트 5필드 (Task 5) |
| `notification/entity/NotificationType.kt` | 상수 20개 추가 (Task 5) |
| `notification/dto/NotificationDtos.kt` | 응답 4필드 추가 (Task 5) |
| `post/repository/PostRepository.kt` | `findByIdAndDeletedAtIsNull` 추가 (Task 2) |
| `post/PostService.kt` | 생명주기 위임, `getPostEntity` 교체, `@Transactional` 제거 (Task 4) |
| `post/PostController.kt` | `joinPolicy` 파라미터 (Task 4) |
| `post/dto/RegisterPostCommand.kt` | `joinPolicy` 필드 (Task 4) |
| `src/main/resources/application.yml` | Hibernate JDBC 배치 설정 (Task 5) |
| `prd/find-my-pet/requirements.md`, `api-spec.md` | 동기화 (Task 12) |

### 신규 테스트

```
src/test/kotlin/com/park/animal/
├── support/CollaborationFixtures.kt          # 공용 픽스처 (Task 11 에서 작성, 앞 태스크는 자체 헬퍼 사용 후 이관)
├── common/http/error/GlobalExceptionMappingTest.kt      # 기존 파일 확장 (Task 1)
├── searchgroup/
│   ├── SearchGroupMigrationIT.kt             # Task 2
│   ├── SearchGroupAccessResolverIT.kt        # Task 3
│   ├── SearchLifecycleIT.kt                  # Task 4
│   ├── GroupNotificationPublisherTest.kt     # Task 5 (단위)
│   ├── SearchGroupMembershipIT.kt            # Task 6
│   ├── SearchGroupBlockIT.kt                 # Task 7
│   ├── SearchGroupTeamSupportIT.kt           # Task 9
│   ├── SearchGroupPermissionMatrixIT.kt      # Task 11
│   ├── SearchGroupIdorIT.kt                  # Task 11
│   ├── SearchGroupConcurrencyIT.kt           # Task 11
│   └── SearchGroupPrivacyIT.kt               # Task 11
├── team/TeamIT.kt                            # Task 8
└── searchhub/SearchHubIT.kt                  # Task 10
```

---

## 태스크 순서와 의존성

```
Task 0 (CI 테스트 게이트)  ← 반드시 먼저. 설계 §19.4: 테스트를 건너뛴 빌드는 완료가 아니다.
   ↓
Task 1 (ErrorCode·예외 매핑)
   ↓
Task 2 (V12 + 엔티티 + 리포지토리)      ← 리포지토리 시그니처는 여기서 한 번만 확정된다.
   ↓                                      Task 6~9 는 재정의하지 않고 그대로 쓴다.
Task 3 (권한 판정 + 거부 메트릭)
   ↓
Task 4 (생명주기 + SearchGroupService/Controller + PostService 리팩터)
   ↓
Task 5 (알림 구조화 + endSearch 알림 구조화 전환)
   ↓
   ├──→ Task 6 (직접 참여 + 참여 정책)
   ├──→ Task 7 (보호자 차단)
   └──→ Task 8 (팀 · 팀 멤버십 · 팀장 이전 · 팀 보관) ──→ Task 9 (팀 지원 연결)
                                                              ↓
                                    Task 10 (마이페이지 허브)  [Task 6·8·9 이후]
                                                              ↓
                                    Task 11 (권한 행렬 · IDOR · 동시성 · 개인정보 IT)
                                                              ↓
                                    Task 12 (PRD · api-spec 동기화)
```

순서에서 물러설 수 없는 지점:

- **Task 0 이 먼저다.** CI 가 테스트를 돌리지 않는 상태에서 쓴 테스트는 전부 죽은 코드다(설계 §19.4).
- **Task 2 가 리포지토리의 유일한 정의처다.** 뒤 태스크가 리포지토리를 "전체 교체" 하면 Task 2 의 마이그레이션 IT 가 컴파일 실패하며 그 시점부터 전체 테스트가 무너진다.
- **Task 4 가 `SearchGroupController` / `SearchGroupService` 를 만든다.** Task 6 의 `PATCH /join-policy` 는 그 클래스에 핸들러를 더하는 Modify 이므로 Task 4 없이는 성립하지 않는다.
- **Task 5 가 `endSearch` 의 알림을 구조화 컨텍스트로 바꾼다.** Task 11 의 `WHERE group_id = ?` 검증이 이것에 의존한다.

---
### Task 0: CI 백엔드 테스트 게이트

설계 §19.4 는 "테스트를 건너뛴 빌드 성공은 기능 완료로 인정하지 않는다" 고 못박는다. 현재 `.github/workflows/ci.yml:41` 은 `./gradlew clean build -x test` 라 **백엔드 테스트가 CI 에서 한 번도 실행된 적이 없다**(F1). 이후 모든 태스크(1~12)의 IT 는 이 job 이 없으면 로컬에서만 도는 장식이 된다. 그래서 Task 0 이 맨 앞이다.

이 태스크는 TDD 사이클이 아니다(프로덕션 코드를 만들지 않는다). "워크플로 수정 → 로컬 전체 테스트 확인 → 게이트 스크립트가 실제로 빨간불을 낼 수 있는지 로컬 역검증 → 커밋/푸시 → GitHub Actions 결과는 **사용자 확인이 필요한 외부 게이트**" 순서로 진행한다.

GitHub Actions 실행 결과를 이 계획서 안에서 자동으로 확인하지 않는다. 이 개발 환경에는 `gh` CLI 가 없고 `$GITHUB_TOKEN` 도 설정돼 있지 않아 `api.github.com` 호출이 인증 없이 나가면 404/403 JSON 을 받고, 그 JSON 을 파싱하는 python 이 `KeyError` 스택트레이스로 죽는다. 그러면 "CI 가 실패했다" 와 "검증 명령이 애초에 돌지 않았다" 를 구분할 수 없다. 자동 검증을 흉내내는 대신 **로컬에서 확실히 검증 가능한 것(테스트 실행 + 게이트 스크립트 로직)만 스텝으로 두고, GitHub 쪽 확인은 사용자에게 명시적으로 요청**한다.

**Files:**
- Modify: `.github/workflows/ci.yml:11-41` (기존 `build` job 은 한 글자도 바꾸지 않고, `jobs:` 블록 끝에 `test` job 추가)

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - GitHub Actions job `test` — `runs-on: ubuntu-latest`, JDK 17(temurin), `./gradlew test --refresh-dependencies --stacktrace -Pgpr.user=PARKPARKWOO -Pgpr.key=${{ secrets.GIT_PASSWORD }}`
  - 워크플로 내부 게이트 스텝 `Assert integration tests actually executed` — `build/test-results/test/TEST-com.park.animal.search.SearchFulltextIT.xml` 이 존재하고 `tests="N>0" failures="0" errors="0" skipped="0"` 인지 검사. Task 2~11 이 추가하는 모든 IT 가 이 job 에서 실행된다는 보증.
  - 아티팩트 `backend-test-results` — `build/test-results/test` + `build/reports/tests/test`
  - 외부 게이트 1건(사용자 작업): `develop` branch protection 의 required status check 에 `Test` 등록

**자격증명 규칙 (CI 와 로컬이 다르다 — 헷갈리면 401 이 난다):**
`animal/gradle.properties` 는 `.gitignore:42` 로 커밋되지 않는 로컬 전용 파일이고, 그 안에 GitHub Packages 자격증명(`gpr.user`/`gpr.key`)이 이미 들어 있다. 따라서
- **로컬 실행에는 `-Pgpr.*` 플래그를 절대 붙이지 않는다.** `build.gradle.kts:33-34` 는 `project.findProperty("gpr.user") ?: System.getenv(...)` 인데, `-Pgpr.key=$GITHUB_TOKEN` 에서 `$GITHUB_TOKEN` 이 미설정이면 **빈 문자열**이 프로퍼티로 세팅되고, 빈 문자열은 non-null 이라 `?:` 폴백이 발동하지 않는다. 결과는 `gradle.properties` 값을 덮어쓴 빈 비밀번호 → GitHub Packages 401.
- **CI job 에는 반드시 붙인다.** 러너에는 `gradle.properties` 가 없으므로 `${{ secrets.GIT_PASSWORD }}` 로 주입하는 것이 유일한 경로이고, 기존 `build` job 이 이미 같은 방식을 쓴다.

**Docker 전제 근거 (반드시 유지):** `SearchFulltextIT` 와 앞으로 추가될 `SearchGroupMigrationIT` 는 Testcontainers `MySQLContainer("mysql:8.4")` 를 쓴다. Testcontainers 는 Docker daemon 을 요구한다. GitHub 호스티드 `ubuntu-latest` 러너 이미지에는 Docker Engine 이 사전 설치되어 daemon 이 떠 있으므로 `services:` 블록이나 별도 셋업 액션 없이 그대로 동작한다. 반대로 이 job 을 self-hosted 러너나 `macos-latest` / `windows-latest` 로 옮기면 Docker 가 없어 **무조건 실패**한다. 그래서 `runs-on` 위에 그 사실을 주석으로 고정하고, 실제로 daemon 이 살아있는지 확인하는 `docker version` 스텝을 테스트 실행 앞에 둔다(실패 시 "테스트 실패" 가 아니라 "환경 문제" 로 즉시 구분된다).

---

- [ ] **Step 1: `.github/workflows/ci.yml` 에 `test` job 추가**

파일 전체를 아래 내용으로 교체한다. `build` job 은 기존 그대로이고 `test` job 만 새로 붙는다.

```yaml
name: Ci with Gradle build test

on:
  pull_request:
  push:
  workflow_dispatch:

permissions:
  contents: read
  packages: read

jobs:
  build:
    name: Build
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: 17
          distribution: adopt

      - name: Cache Gradle packages
        uses: actions/cache@v2
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      #  To install packages associated with other private repositories that GITHUB_TOKEN can't access, use a personal access token (classic) with repository scope
      - name: Build and Test with Gradle
        run: ./gradlew clean build -x test --refresh-dependencies --stacktrace -Pgpr.user=PARKPARKWOO -Pgpr.key=${{ secrets.GIT_PASSWORD }}

      - name: Cleanup Gradle Cache
        if: ${{ always() }}
        run: |
          rm -f ~/.gradle/caches/modules-2/modules-2.lock
          rm -f ~/.gradle/caches/modules-2/gc.properties

  # 설계 19.4 CI 게이트: build job 이 `-x test` 로 테스트를 건너뛰므로 백엔드 테스트를 실제로
  # 실행하는 별도 job 을 둔다. build job 은 이미지 빌드 경로(cd.yml)와 동일한 형태라 그대로 유지한다.
  #
  # Testcontainers 전제:
  #   SearchFulltextIT / SearchGroupMigrationIT 는 MySQLContainer("mysql:8.4") 를 띄우므로
  #   Docker daemon 이 반드시 필요하다. GitHub 호스티드 ubuntu-latest 러너 이미지에는 Docker Engine 이
  #   사전 설치되어 daemon 이 기동돼 있어 별도 services 설정 없이 동작한다.
  #   이 job 을 self-hosted / macos-latest / windows-latest 로 옮기면 Docker 가 없어 무조건 실패한다.
  #
  # 자격증명:
  #   gradle.properties 는 .gitignore 대상이라 러너에 존재하지 않는다. 따라서 CI 에서는
  #   -Pgpr.* 플래그로 secrets 를 주입해야 한다. 반대로 로컬에서는 gradle.properties 가 있으므로
  #   같은 플래그를 붙이면 빈 값이 자격증명을 덮어써 401 이 난다 — 로컬 실행 시 붙이지 말 것.
  test:
    name: Test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: 17
          distribution: temurin

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-test-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: |
            ${{ runner.os }}-gradle-test-
            ${{ runner.os }}-gradle-

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      # 테스트 실패와 "Docker 가 없어서 못 돎" 을 로그에서 즉시 구분하기 위한 사전 확인.
      - name: Verify Docker daemon (Testcontainers precondition)
        run: |
          docker version
          docker info --format 'ServerVersion={{.ServerVersion}}'

      - name: Run backend tests
        run: ./gradlew test --refresh-dependencies --stacktrace -Pgpr.user=PARKPARKWOO -Pgpr.key=${{ secrets.GIT_PASSWORD }}

      # 게이트: Gradle 이 "성공" 을 리턴해도 테스트가 0건 실행됐거나 skip 됐으면 완료로 인정하지 않는다.
      # SearchFulltextIT 는 이 레포에서 Docker 를 실제로 쓰는 유일한 기존 IT 이므로 카나리아로 쓴다.
      - name: Assert integration tests actually executed
        if: ${{ always() }}
        run: |
          XML=build/test-results/test/TEST-com.park.animal.search.SearchFulltextIT.xml
          if [ ! -f "$XML" ]; then
            echo "::error::$XML 없음 — Testcontainers IT 가 실행되지 않았다."
            exit 1
          fi
          head -c 400 "$XML"
          grep -qE 'tests="[1-9][0-9]*"' "$XML" || { echo "::error::실행된 테스트 0건"; exit 1; }
          grep -q 'failures="0"' "$XML" || { echo "::error::IT 실패"; exit 1; }
          grep -q 'errors="0"'   "$XML" || { echo "::error::IT 에러"; exit 1; }
          grep -q 'skipped="0"'  "$XML" || { echo "::error::IT skip 됨 — 게이트 무력화"; exit 1; }

      - name: Upload test results
        if: ${{ always() }}
        uses: actions/upload-artifact@v4
        with:
          name: backend-test-results
          path: |
            build/test-results/test
            build/reports/tests/test
          retention-days: 7

      - name: Cleanup Gradle Cache
        if: ${{ always() }}
        run: |
          rm -f ~/.gradle/caches/modules-2/modules-2.lock
          rm -f ~/.gradle/caches/modules-2/gc.properties
```

주의 두 가지.
1. 새 job 은 `actions/checkout@v4` / `setup-java@v4` / `cache@v4` / `upload-artifact@v4` 를 쓴다. 기존 build job 의 `cache@v2`·`checkout@v3` 는 GitHub 가 축소·폐기 중인 버전이라 새 job 에 복사하지 않는다. build job 자체는 cd.yml 과 대칭이라 이 태스크 범위에서 손대지 않는다.
2. `distribution: temurin` — 기존 `adopt` 는 Temurin 으로 리다이렉트되는 레거시 별칭이다. 새 job 만 정식 이름을 쓴다.

---

- [ ] **Step 2: 로컬에서 `./gradlew test` 가 통과하는지 먼저 확인 (Docker Desktop 실행 필요)**

CI 에 올리기 전에 현재 테스트 3종(`SearchFulltextIT`, `SearchHybridFallbackTest`, `GlobalExceptionMappingTest`)이 실제로 초록인지 확인한다. 여기서 깨지면 CI job 문제가 아니라 기존 테스트 문제다.

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --stacktrace
```

Expected: PASS — `BUILD SUCCESSFUL`.

이어서 카나리아 XML 이 실제로 생성됐는지 본다.

Run:
```bash
head -c 400 /Users/park/Desktop/project/animal/build/test-results/test/TEST-com.park.animal.search.SearchFulltextIT.xml; echo
```

Expected: 아래 형태로 시작한다(`time` 등 뒤쪽 속성값은 실행마다 다르다).
```
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.park.animal.search.SearchFulltextIT" tests="5" skipped="0" failures="0" errors="0" ...
```

`JAVA_HOME` 을 지정하는 이유: 이 머신의 기본 JDK 는 23 이고, 그 위에서는 kapt 가 깨진다. `-Pgpr.*` 플래그는 붙이지 않는다(위 "자격증명 규칙" 참조 — 붙이면 GitHub Packages 401).

Docker daemon 이 안 떠 있으면 `Could not find a valid Docker environment` 로 실패한다 — 그건 환경 문제이므로 Docker Desktop 을 켜고 재실행한다.

---

- [ ] **Step 3: 게이트 스크립트가 실제로 "빨간불" 을 낼 수 있는지 로컬에서 역검증**

통과만 확인하면 게이트가 항상 초록인 no-op 일 수도 있다. Step 1 워크플로 스텝과 **동일한 4개 검사**(파일 존재 / tests>0 / failures=0 / errors=0 / skipped=0)를 로컬 함수로 재현해, 깨진 XML 픽스처마다 정확히 어떤 검사가 걸리는지 확인한다. GitHub 에 아무것도 푸시하지 않고 게이트 로직 자체를 검증할 수 있다.

Run:
```bash
cd /Users/park/Desktop/project/animal && GATE=$(mktemp -d) && check() { XML="$1"; [ -f "$XML" ] || { echo "MISSING"; return 1; }; grep -qE 'tests="[1-9][0-9]*"' "$XML" || { echo "ZERO"; return 1; }; grep -q 'failures="0"' "$XML" || { echo "FAILURES"; return 1; }; grep -q 'errors="0"' "$XML" || { echo "ERRORS"; return 1; }; grep -q 'skipped="0"' "$XML" || { echo "SKIPPED"; return 1; }; echo "OK"; } && printf '%s\n' '<testsuite name="x" tests="0" skipped="0" failures="0" errors="0"></testsuite>' > "$GATE/zero.xml" && printf '%s\n' '<testsuite name="x" tests="5" skipped="0" failures="1" errors="0"></testsuite>' > "$GATE/fail.xml" && printf '%s\n' '<testsuite name="x" tests="5" skipped="0" failures="0" errors="1"></testsuite>' > "$GATE/err.xml" && printf '%s\n' '<testsuite name="x" tests="5" skipped="2" failures="0" errors="0"></testsuite>' > "$GATE/skip.xml" && for f in nofile.xml zero.xml fail.xml err.xml skip.xml; do printf '%-12s ' "$f"; check "$GATE/$f"; done; printf '%-12s ' real; check build/test-results/test/TEST-com.park.animal.search.SearchFulltextIT.xml; rm -rf "$GATE"
```

Expected:
```
nofile.xml   MISSING
zero.xml     ZERO
fail.xml     FAILURES
err.xml      ERRORS
skip.xml     SKIPPED
real         OK
```

다섯 개의 깨진 픽스처가 모두 서로 다른 이유로 걸리고 Step 2 가 만든 진짜 XML 만 `OK` 여야 한다. 하나라도 `OK` 가 나오면 워크플로의 해당 `grep` 조건이 무력하다는 뜻이므로 Step 1 의 게이트 스텝을 고친 뒤 이 스텝을 다시 돈다.

---

- [ ] **Step 4: 커밋 후 develop 에 푸시**

```bash
cd /Users/park/Desktop/project/animal && git add .github/workflows/ci.yml && git commit -m "chore(ci): 백엔드 테스트 job 추가 — Testcontainers IT 실행 게이트

설계 19.4: 테스트를 건너뛴 빌드 성공을 완료로 인정하지 않는다.
기존 build job(clean build -x test)은 유지하고 ubuntu-latest(Docker 사전 설치)에서
./gradlew test 를 도는 별도 test job 을 추가한다.
SearchFulltextIT 결과 XML 을 카나리아로 검사해 '0건 실행/skip' 상태를 실패로 만든다." && git push origin develop
```

`develop` 푸시는 `cd.yml`(이미지 빌드 + 배포 dispatch)도 함께 트리거한다. 워크플로 파일만 바뀌었으므로 이미지 태그가 새로 생기지만, 서비스 코드 변경이 없어 배포 영향은 없다.

---

- [ ] **Step 5: 외부 게이트 2건을 사용자에게 보고하고 확인을 요청**

여기서 코드로 할 수 있는 일은 끝났다. 남은 두 가지는 이 환경에서 검증할 수 없다 — `gh` CLI 가 설치돼 있지 않고 `$GITHUB_TOKEN` 도 없어 `api.github.com` 인증 호출이 불가능하며, branch protection 변경은 레포 관리자 권한이 필요하다. 아래 두 문장을 그대로 사용자에게 전달하고, **1번 답을 받은 뒤** Task 1 로 넘어간다(테스트가 CI 에서 실제로 돌지 않으면 Task 1~12 의 IT 가 전부 장식이 되므로, 여기서 한 번은 사람의 확인을 받는다).

> **(1) 확인 요청 — 답을 주셔야 다음 태스크로 갑니다.**
> `https://github.com/Team-Park/find-my-pet-backend/actions?query=branch%3Adevelop` 에서 방금 푸시로 돈 `Ci with Gradle build test` 실행을 열어 아래 3가지를 확인하고 알려주세요.
> - `Test` job 이 job 목록에 **보이는지** (안 보이면 YAML 파싱 실패입니다)
> - `Test` job 의 결론이 **success** 인지, 그리고 그 안의 `Assert integration tests actually executed` 스텝 로그가 `<testsuite name="com.park.animal.search.SearchFulltextIT" tests="5" skipped="0" failures="0" errors="0"` 로 시작하는 한 줄을 찍었는지
> - 실행 하단 Artifacts 에 **`backend-test-results`** 가 올라와 있는지
>
> `Test` job 이 `Verify Docker daemon` 스텝에서 죽었다면 러너가 GitHub 호스티드 `ubuntu-latest` 가 아니라는 뜻이니 그 로그를 알려주세요.

> **(2) 설정 요청 — 지금 못 하셔도 계획은 계속 진행합니다.**
> `Test` job 실패가 머지를 **차단**하려면 `Team-Park/find-my-pet-backend` → Settings → Branches → `develop` 규칙의 Required status checks 에 **`Test`** 를 추가해 주세요. 추가 전까지는 테스트 실패가 빨간불로만 보이고 머지를 막지는 못합니다.

---

### Task 1: 예외·에러코드 기반 정비

Task 2 이후 모든 서비스가 던질 `BusinessException(ErrorCode.*)` 의 어휘와 HTTP 상태를 먼저 확정한다. 동시에 지금 500 으로 새는 두 종류의 바인딩 예외(F9/F10)와 알림 목록 정렬 tiebreaker(F20)를 함께 고친다. 셋 다 "함께 찾기" 가 새로 만들 요청 형태(JSON body 를 받는 POST/PATCH, 그룹 알림 대량 생성)에서 곧바로 터질 것들이다.

**이미 있는 것을 다시 만들지 않는다.** `ErrorCode.kt:55-57` 에 `NOT_FOUND_ROUTE`(404) 와 `MISSING_PARAMETER`(400) 가 **이미 선언돼 있고**, `GlobalExceptionController.kt:7-8` 에 `HttpStatus`·`ResponseEntity` import 도 **이미 있다**. 이 태스크가 추가하는 것은 "함께 찾기" ErrorCode **11개**와 import **2줄**뿐이다. 기존 상수를 다시 쓰면 Kotlin 이 `Conflicting declarations` 로, import 를 다시 쓰면 `Conflicting import` 로 컴파일을 거부한다.

**`MISSING_PARAMETER` 와 `INVALID_COLLABORATION_INPUT` 의 사용 경계 (Task 3~12 전체에 적용되는 규약).** 둘 다 400 이지만 발생 계층이 다르고, 이 경계는 Task 12 의 api-spec 이 그대로 문서화한다.
- **`MISSING_PARAMETER` = 요청이 컨트롤러 메서드 시그니처에 바인딩되지 못한 경우.** 필수 query/path 파라미터 누락, `UUID`/enum 문자열 변환 실패(예: `joinPolicy=WHATEVER`), 깨진 JSON, Kotlin non-null 필드 누락, 필수 헤더 누락. 전부 Spring 이 던지는 예외를 `GlobalExceptionController.missingParameter` 가 잡아 자동으로 만든다. **서비스 코드가 이 코드를 직접 `throw` 하는 일은 없다.**
- **`INVALID_COLLABORATION_INPUT` = 바인딩은 성공했는데 서비스 계층의 명시적 검증에 걸린 경우.** 팀 이름 길이(2~30자) 위반, 팀 설명 길이 초과, 차단 사유 길이 초과처럼 값 자체는 타입이 맞지만 도메인 규칙을 어긴 입력. 이 레포에는 `spring-boot-starter-validation` 이 없어(F11) `@Valid`/`@field:NotBlank` 가 무동작이므로, 이런 검증은 서비스가 `require`-스타일 코드로 직접 수행하고 이 코드를 `throw` 한다.
- 따라서 "잘못된 enum 값을 보냈다" 는 **항상** `MISSING_PARAMETER` 이지 `INVALID_COLLABORATION_INPUT` 이 아니다. 반대로 "이름이 1글자다" 는 **항상** `INVALID_COLLABORATION_INPUT` 이다.

**Files:**
- Modify: `src/main/kotlin/com/park/animal/common/http/error/ErrorCode.kt:58-59` (`UNKNOWN_ERROR` 앞에 11개 추가. 55~57행은 손대지 않는다)
- Modify: `src/main/kotlin/com/park/animal/common/http/error/GlobalExceptionController.kt:8-9, 52-56` (import 2줄 추가 + `@ExceptionHandler` 목록 확장)
- Modify: `src/main/kotlin/com/park/animal/notification/repository/NotificationRepository.kt:13-16` (파생 쿼리명에 `IdDesc` tiebreaker 추가)
- Modify: `src/main/kotlin/com/park/animal/notification/NotificationService.kt:10, 56-67` (`Sort` import 제거 + 새 파생 쿼리 호출)
- Test: `src/test/kotlin/com/park/animal/common/http/error/GlobalExceptionMappingTest.kt` (기존 파일 확장)
- Test: `src/test/kotlin/com/park/animal/notification/NotificationOrderingTest.kt` (신규)

**Interfaces:**
- Consumes: Task 0 의 `test` job (여기서 추가한 테스트가 CI 에서 실제로 돈다)
- Produces:
  - `ErrorCode.NOT_FOUND_SEARCH_GROUP` / `NOT_FOUND_SEARCH_GROUP_MEMBERSHIP` / `NOT_FOUND_TEAM` / `NOT_FOUND_TEAM_MEMBERSHIP` / `NOT_FOUND_TEAM_SUPPORT` (404), `SEARCH_GROUP_ACCESS_DENIED` (403), `SEARCH_GROUP_STATE_CONFLICT` (409), `SEARCH_ALREADY_ENDED` (410), `TEAM_LEADER_REQUIRED` (403), `TEAM_LEADER_CANNOT_LEAVE` (409), `INVALID_COLLABORATION_INPUT` (400) — Task 3~11 전부가 소비. `INVALID_COLLABORATION_INPUT` 은 위 "사용 경계" 규약대로 **서비스 계층 명시 검증 실패에만** 쓴다.
  - `GlobalExceptionController.missingParameter(e: Exception, request: HttpServletRequest): ResponseEntity<FailedApiResponseBody>` — 처리 대상에 `HttpMessageNotReadableException`, `MissingRequestHeaderException` 추가. 잘못된 enum/UUID 문자열과 깨진 JSON 은 여기서 400 `MISSING_PARAMETER` 가 되므로 하류 태스크는 이를 위해 별도 검증 코드를 두지 않는다.
  - **`NotificationRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId: UUID, pageable: Pageable): Page<Notification>`** — 이것이 **개명된 최종 이름**이다. 기존 `findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc` 는 **이 태스크에서 사라진다**. Task 5(`GroupNotificationFanoutIT` 의 `countFor`/`구조화 컨텍스트가 알림 행에 저장된다`)와 Task 10 을 포함해 알림 목록을 읽는 모든 하류 코드·테스트는 **반드시 `...OrderByCreatedAtDescIdDesc` 를 호출한다.** 옛 이름을 쓰면 Spring Data 가 빈 생성 시점에 메서드를 찾지 못해 컨텍스트 로딩 자체가 실패한다.

---

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/common/http/error/GlobalExceptionMappingTest.kt` 를 아래 내용으로 **교체**한다(기존 2개 테스트는 그대로 보존하고 5개 추가).

```kotlin
package com.park.animal.common.http.error

import com.park.animal.common.http.error.exception.BusinessException
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 라이브에서 관측된 오매핑 회귀 방지 (2026-07-04):
 * - 존재하지 않는 경로(NoResourceFoundException) → 500 UNKNOWN_ERROR 로 응답하던 것을 404 로
 * - 필수 파라미터 누락(MissingServletRequestParameterException) → 500 → 400 으로
 *
 * 2026-07-25 확장 (함께 찾기 phase 1):
 * - 잘못된 JSON 본문 / Kotlin non-null 필드 누락(HttpMessageNotReadableException) → 500 → 400
 * - 필수 헤더 누락(MissingRequestHeaderException, ServletRequestBindingException 하위라
 *   기존 핸들러에 걸리지 않았다) → 500 → 400
 * - 함께 찾기 ErrorCode 는 설계 15 표(404/403/409/410/400)를 그대로 쓴다. 기존 legacy
 *   NOT_FOUND_* 가 전부 400 인 관례를 의도적으로 깨는 것이므로 상태값을 테스트로 고정한다.
 *
 * 사용 경계: 바인딩/역직렬화 실패는 여기서 MISSING_PARAMETER(400) 가 된다.
 * INVALID_COLLABORATION_INPUT(400) 은 서비스 계층 명시 검증 실패 전용이며 컨트롤러 진입 전에는
 * 절대 발생하지 않는다.
 */
class GlobalExceptionMappingTest {
    private val controller = GlobalExceptionController()
    private val request =
        mock<HttpServletRequest>().also {
            whenever(it.requestURI).thenReturn("/some/path")
        }

    /** MissingRequestHeaderException 생성에 필요한 MethodParameter 용 더미 시그니처. */
    @Suppress("unused")
    fun headerBindingStub(groupId: String): String = groupId

    private fun headerParameter(): MethodParameter =
        MethodParameter(
            GlobalExceptionMappingTest::class.java.getDeclaredMethod("headerBindingStub", String::class.java),
            0,
        )

    @Test
    fun `존재하지 않는 경로는 404 NOT_FOUND_ROUTE`() {
        val e = NoResourceFoundException(HttpMethod.GET, "user/me")

        val res = controller.noResourceFound(e, request)

        assertEquals(HttpStatus.NOT_FOUND, res.statusCode)
        assertEquals(ErrorCode.NOT_FOUND_ROUTE.name, res.body!!.code)
    }

    @Test
    fun `필수 파라미터 누락은 400 MISSING_PARAMETER`() {
        val e = MissingServletRequestParameterException("q", "String")

        val res = controller.missingParameter(e, request)

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode)
        assertEquals(ErrorCode.MISSING_PARAMETER.name, res.body!!.code)
    }

    @Test
    fun `잘못된 JSON 본문은 400 MISSING_PARAMETER`() {
        val e =
            HttpMessageNotReadableException(
                "JSON parse error: Instantiation of [simple type, class RegisterMembershipRequest] value failed",
                mock<HttpInputMessage>(),
            )

        val res = controller.missingParameter(e, request)

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode)
        assertEquals(ErrorCode.MISSING_PARAMETER.name, res.body!!.code)
    }

    @Test
    fun `필수 헤더 누락은 400 MISSING_PARAMETER`() {
        val e = MissingRequestHeaderException("X-Passport", headerParameter())

        val res = controller.missingParameter(e, request)

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode)
        assertEquals(ErrorCode.MISSING_PARAMETER.name, res.body!!.code)
    }

    @Test
    fun `함께 찾기 ErrorCode 는 설계 15 표의 HTTP 상태를 쓴다`() {
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_SEARCH_GROUP.httpCode)
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP.httpCode)
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_TEAM.httpCode)
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP.httpCode)
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_TEAM_SUPPORT.httpCode)
        assertEquals(HttpStatus.FORBIDDEN, ErrorCode.SEARCH_GROUP_ACCESS_DENIED.httpCode)
        assertEquals(HttpStatus.CONFLICT, ErrorCode.SEARCH_GROUP_STATE_CONFLICT.httpCode)
        assertEquals(HttpStatus.GONE, ErrorCode.SEARCH_ALREADY_ENDED.httpCode)
        assertEquals(HttpStatus.FORBIDDEN, ErrorCode.TEAM_LEADER_REQUIRED.httpCode)
        assertEquals(HttpStatus.CONFLICT, ErrorCode.TEAM_LEADER_CANNOT_LEAVE.httpCode)
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_COLLABORATION_INPUT.httpCode)

        // 바인딩 실패용 400 과 서비스 검증 실패용 400 은 서로 다른 코드로 유지한다.
        // 프론트가 "형식이 틀림" 과 "값이 규칙 위반" 을 구분해 안내할 수 있어야 한다.
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_PARAMETER.httpCode)
        assertFalse(ErrorCode.MISSING_PARAMETER == ErrorCode.INVALID_COLLABORATION_INPUT)

        // legacy 400 관례는 소급 변경하지 않는다 (프론트 계약 파손 방지).
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.NOT_FOUND_POST.httpCode)
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.NOT_FOUND_NOTIFICATION.httpCode)
    }

    @Test
    fun `함께 찾기 ErrorCode 문구에 admin 어휘가 없다`() {
        val collaborationCodes =
            listOf(
                ErrorCode.NOT_FOUND_SEARCH_GROUP,
                ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP,
                ErrorCode.NOT_FOUND_TEAM,
                ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP,
                ErrorCode.NOT_FOUND_TEAM_SUPPORT,
                ErrorCode.SEARCH_GROUP_ACCESS_DENIED,
                ErrorCode.SEARCH_GROUP_STATE_CONFLICT,
                ErrorCode.SEARCH_ALREADY_ENDED,
                ErrorCode.TEAM_LEADER_REQUIRED,
                ErrorCode.TEAM_LEADER_CANNOT_LEAVE,
                ErrorCode.INVALID_COLLABORATION_INPUT,
            )

        collaborationCodes.forEach {
            assertFalse(
                it.message.lowercase().contains("admin"),
                "${it.name} 문구에 admin 이 들어갔다. 설계 2 제품 언어 위반: ${it.message}",
            )
            assertFalse(it.message.isBlank(), "${it.name} 문구가 비어 있다")
        }
    }

    @Test
    fun `종료된 수색은 410 SEARCH_ALREADY_ENDED 로 응답한다`() {
        val res = controller.businessException(BusinessException(ErrorCode.SEARCH_ALREADY_ENDED), request)

        assertEquals(HttpStatus.GONE, res.statusCode)
        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED.name, res.body!!.code)
        assertEquals("종료된 수색이에요. 이전 기록만 확인할 수 있어요.", res.body!!.message)
    }
}
```

그리고 `src/test/kotlin/com/park/animal/notification/NotificationOrderingTest.kt` 를 신규 작성한다. DB 없이 파생 쿼리 이름 자체를 Spring Data 의 `PartTree` 로 파싱해 tiebreaker 존재를 고정한다(`PartTree` 는 프로퍼티 경로도 검증하므로 오타 난 파생 쿼리명은 여기서 바로 터진다).

```kotlin
package com.park.animal.notification

import com.park.animal.notification.entity.Notification
import com.park.animal.notification.repository.NotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.PartTree
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F20 회귀 방지.
 *
 * notification.created_at 은 V7 에서 초 정밀도 TIMESTAMP 로 만들어졌고 목록은 offset
 * 페이지네이션이다. 정렬 키가 createdAt 하나뿐이면 같은 초에 들어온 알림들의 순서가
 * 페이지마다 달라져 경계에서 중복/누락이 난다. 함께 찾기는 한 액션에서 그룹 전체에
 * 알림을 fanout 하므로(설계 9) 동일 초 다건이 상시 발생한다.
 *
 * DB 없이 파생 쿼리 이름을 파싱해 tiebreaker(id DESC)가 살아 있는지 고정한다.
 * 개명된 이름은 Task 5·10 을 포함한 모든 하류 코드가 그대로 호출해야 하는 계약이므로
 * 옛 이름이 남아 있지 않은 것까지 함께 확인한다.
 */
class NotificationOrderingTest {
    private val queryMethodName = "findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc"
    private val legacyMethodName = "findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc"

    @Test
    fun `알림 목록 파생 쿼리는 createdAt DESC 뒤에 id DESC tiebreaker 를 갖는다`() {
        val tree = PartTree(queryMethodName, Notification::class.java)

        val orders = tree.sort.toList()

        assertEquals(listOf("createdAt", "id"), orders.map { it.property })
        assertTrue(orders.all { it.direction == Sort.Direction.DESC }, "두 정렬 키 모두 DESC 여야 한다")
    }

    @Test
    fun `NotificationRepository 가 그 파생 쿼리를 선언한다`() {
        val declared = NotificationRepository::class.java.methods.map { it.name }

        assertTrue(
            queryMethodName in declared,
            "리포지토리 메서드명이 바뀌면 tiebreaker 검증이 무의미해진다. 선언된 메서드: $declared",
        )
        assertFalse(
            legacyMethodName in declared,
            "tiebreaker 없는 옛 이름이 남아 있으면 하류 태스크가 그걸 계속 호출한다. 선언된 메서드: $declared",
        )
    }
}
```

---

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.common.http.error.GlobalExceptionMappingTest" --tests "com.park.animal.notification.NotificationOrderingTest"
```

Expected: FAIL — `> Task :compileTestKotlin FAILED` 로 컴파일 단계에서 멈춘다. 에러 목록에 `GlobalExceptionMappingTest.kt` 의 아래 11개 심볼이 각각 `Unresolved reference` 로 보고된다.

```
NOT_FOUND_SEARCH_GROUP
NOT_FOUND_SEARCH_GROUP_MEMBERSHIP
NOT_FOUND_TEAM
NOT_FOUND_TEAM_MEMBERSHIP
NOT_FOUND_TEAM_SUPPORT
SEARCH_GROUP_ACCESS_DENIED
SEARCH_GROUP_STATE_CONFLICT
SEARCH_ALREADY_ENDED
TEAM_LEADER_REQUIRED
TEAM_LEADER_CANNOT_LEAVE
INVALID_COLLABORATION_INPUT
```

`NotificationOrderingTest` 는 컴파일 자체는 되지만(리포지토리 인터페이스 타입만 참조한다) 컴파일이 통째로 실패하므로 실행되지 않는다. Step 3 의 ErrorCode 만 먼저 넣고 다시 돌리면 `NotificationRepository 가 그 파생 쿼리를 선언한다` 가

```
AssertionError: 리포지토리 메서드명이 바뀌면 tiebreaker 검증이 무의미해진다. 선언된 메서드: [findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc, ...]
```

로 실패한다 — 현재 메서드명이 `...OrderByCreatedAtDesc` 이기 때문이다.

---

- [ ] **Step 3: 최소 구현**

**3-1. `src/main/kotlin/com/park/animal/common/http/error/ErrorCode.kt`**

58행(빈 줄)과 59행 `UNKNOWN_ERROR` 사이에 아래 블록 **하나만** 삽입한다. 55~57행의 `// http 공통` 주석과 `NOT_FOUND_ROUTE` / `MISSING_PARAMETER` 두 상수는 **이미 존재하므로 절대 다시 쓰지 않는다** — 다시 쓰면 `Conflicting declarations: NOT_FOUND_ROUTE` 로 컴파일이 실패한다. 이 스텝이 파일에 추가하는 줄은 아래 12개 상수와 주석뿐이다.

```kotlin
    // 함께 찾기 (수색그룹/팀) — 설계 15.
    // 주의: 위쪽 legacy NOT_FOUND_* 는 전부 400 이지만, 이 블록은 설계 15 표를 지키기 위해
    // 의도적으로 404/409/410 을 쓴다. 기존 코드의 400 을 소급 변경하지 않는다(프론트 계약 파손).
    // 문구는 설계 2 제품 언어만 사용한다 — admin/좌표/전화번호/차단 사유 금지.
    //
    // 400 두 종류의 경계:
    //   MISSING_PARAMETER          = 요청이 컨트롤러 시그니처에 바인딩되지 못함(파라미터/헤더 누락,
    //                                UUID·enum 변환 실패, 깨진 JSON, Kotlin non-null 필드 누락).
    //                                GlobalExceptionController 가 자동 생성하며 서비스가 직접 던지지 않는다.
    //   INVALID_COLLABORATION_INPUT = 바인딩은 성공했으나 서비스 계층 명시 검증에 걸림
    //                                (팀 이름 길이 2~30자 위반, 설명·사유 길이 초과 등).
    //                                이 레포에는 spring-boot-starter-validation 이 없어 @Valid 가
    //                                무동작이므로 서비스가 직접 검사하고 이 코드를 던진다.
    NOT_FOUND_SEARCH_GROUP("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    NOT_FOUND_SEARCH_GROUP_MEMBERSHIP("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    NOT_FOUND_TEAM("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    NOT_FOUND_TEAM_MEMBERSHIP("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    NOT_FOUND_TEAM_SUPPORT("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    SEARCH_GROUP_ACCESS_DENIED("현재 이 수색그룹을 이용할 권한이 없어요.", HttpStatus.FORBIDDEN, WARN),
    SEARCH_GROUP_STATE_CONFLICT("처리할 수 없는 상태예요. 최신 상태를 다시 불러와 주세요.", HttpStatus.CONFLICT, WARN),
    SEARCH_ALREADY_ENDED("종료된 수색이에요. 이전 기록만 확인할 수 있어요.", HttpStatus.GONE, WARN),
    TEAM_LEADER_REQUIRED("팀장만 할 수 있는 작업이에요.", HttpStatus.FORBIDDEN, WARN),
    TEAM_LEADER_CANNOT_LEAVE("팀장 권한을 다른 팀원에게 넘긴 뒤에 나갈 수 있어요.", HttpStatus.CONFLICT, WARN),
    INVALID_COLLABORATION_INPUT("입력값을 다시 확인해 주세요.", HttpStatus.BAD_REQUEST, WARN),
```

삽입 후 파일의 55행부터 마지막 상수까지가 아래 형태여야 한다(확인용, 다시 입력하지 말 것).

```
55:    // http 공통 — 라우트/파라미터 오류를 500 으로 흘리지 않기 위한 명시 매핑
56:    NOT_FOUND_ROUTE(...)
57:    MISSING_PARAMETER(...)
58:
59:    // 함께 찾기 (수색그룹/팀) — 설계 15.
   :    ... (위 12개)
   :
   :    UNKNOWN_ERROR("알 수 없는 에러", HttpStatus.INTERNAL_SERVER_ERROR, ERROR),
   : }
```

**3-2. `src/main/kotlin/com/park/animal/common/http/error/GlobalExceptionController.kt`** — import **2줄만** 추가한다.

현재 7~13행은 아래와 같고, `HttpStatus`(7행) 와 `ResponseEntity`(8행) 는 **이미 있으므로 다시 쓰지 않는다**(다시 쓰면 중복 import 로 컴파일 에러).

```kotlin
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
```

여기에 두 줄을 알파벳 순서 자리에 끼워 넣어 아래 상태로 만든다 — 8행 `ResponseEntity` 다음에 `http.converter.HttpMessageNotReadableException`, 9행 `MissingServletRequestParameterException` 앞에 `MissingRequestHeaderException`.

```kotlin
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
```

그리고 52~56행 핸들러를 교체한다.

```kotlin
    /**
     * 요청 바인딩 실패 4종을 한 곳에서 400 [ErrorCode.MISSING_PARAMETER] 로 내린다.
     *
     * - MissingServletRequestParameterException: 필수 query/form 파라미터 누락
     * - MethodArgumentTypeMismatchException: UUID/enum 등 타입 변환 실패.
     *   `joinPolicy=WHATEVER` 처럼 enum 에 없는 문자열이 오는 경우가 여기다.
     * - HttpMessageNotReadableException: 깨진 JSON, 또는 Kotlin non-null 필드 누락으로
     *   Jackson 이 인스턴스화에 실패한 경우. 핸들러가 없어 500 으로 떨어지고 있었다(F9).
     *   이 레포에는 spring-boot-starter-validation 이 없어 @Valid 가 무동작이므로,
     *   본문 형태 오류를 400 으로 만드는 유일한 지점이 여기다.
     * - MissingRequestHeaderException: 필수 헤더 누락. ServletRequestBindingException 하위라
     *   MissingServletRequestParameterException 핸들러에 걸리지 않아 역시 500 이었다(F10).
     *
     * 경계 규약: 여기서 처리하는 것은 "요청이 컨트롤러 시그니처에 **바인딩되지 못한**" 경우뿐이다.
     * 바인딩은 됐지만 값이 도메인 규칙을 어긴 경우(팀 이름 길이 등)는 서비스가
     * [ErrorCode.INVALID_COLLABORATION_INPUT] 을 던진다. 두 코드를 섞지 않는다 —
     * 프론트가 "형식이 틀림" 과 "값이 규칙 위반" 을 다르게 안내한다.
     */
    @ExceptionHandler(
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
    )
    fun missingParameter(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> = respond(ErrorCode.MISSING_PARAMETER, e, request)
```

**3-3. `src/main/kotlin/com/park/animal/notification/repository/NotificationRepository.kt`** — 13~16행의 파생 쿼리를 아래로 교체한다(메서드명이 바뀌므로 옛 이름은 남기지 않는다).

```kotlin
    /**
     * 알림 목록. created_at 은 V7 에서 초 정밀도 TIMESTAMP 라 동일 초 다건이 흔하고
     * (함께 찾기는 한 액션에서 그룹 전체에 fanout 한다) 목록은 offset 페이지네이션이다.
     * createdAt 단독 정렬이면 페이지 경계에서 중복/누락이 나므로 id DESC 를 tiebreaker 로 둔다(F20).
     *
     * 호출 측 규칙 두 가지.
     * 1. Pageable 에 Sort 를 넣지 않는다 — 메서드명 정렬과 중복되어 ORDER BY 가 두 번 붙는다.
     * 2. 이 이름(`...OrderByCreatedAtDescIdDesc`)이 계약이다. 알림 목록을 읽는 모든 코드·테스트가
     *    이 이름을 쓴다. 옛 이름 `...OrderByCreatedAtDesc` 는 더 이상 존재하지 않는다.
     */
    fun findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
        userId: UUID,
        pageable: Pageable,
    ): Page<Notification>
```

**3-4. `src/main/kotlin/com/park/animal/notification/NotificationService.kt`** — `import org.springframework.data.domain.Sort` (10행) 를 삭제하고 `list` (56~67행) 를 교체한다.

```kotlin
    @Transactional(readOnly = true)
    fun list(
        userId: UUID,
        size: Int,
        offset: Int,
    ): List<NotificationResponse> {
        val pageSize = size.coerceAtLeast(1)
        // 정렬은 파생 쿼리명(createdAt DESC, id DESC)이 담당한다. Pageable 에 Sort 를 실으면
        // 같은 ORDER BY 절이 중복 생성된다.
        val page = PageRequest.of(offset / pageSize, pageSize)
        return notificationRepository
            .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId, page)
            .content
            .map(NotificationResponse::from)
    }
```

---

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.common.http.error.GlobalExceptionMappingTest" --tests "com.park.animal.notification.NotificationOrderingTest"
```
Expected: PASS — `BUILD SUCCESSFUL`, 7 + 2 = 9 tests.

이어서 전체 스위트가 깨지지 않았는지 확인한다(Docker 필요).

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test
```
Expected: PASS — `BUILD SUCCESSFUL`.

`-Pgpr.*` 플래그를 붙이지 않는 이유는 Task 0 의 "자격증명 규칙" 과 같다: `$GITHUB_TOKEN` 이 비어 있으면 `gradle.properties` 의 실제 자격증명을 빈 문자열로 덮어써 GitHub Packages 401 이 난다.

주의: 파생 쿼리 이름이 실제로 Spring Data 에 의해 해석 가능한지는 컨텍스트를 띄우는 테스트에서만 최종 확인된다. `NotificationOrderingTest` 가 `PartTree` 로 같은 파서를 태워 프로퍼티 경로(`createdAt`, `id`)를 검증하므로 사실상 동등하고, Task 2 의 `SearchGroupMigrationIT`(`@DataJpaTest` 는 앱의 모든 리포지토리를 부팅한다)가 한 번 더 컨텍스트 로딩으로 확인한다.

---

- [ ] **Step 5: 커밋**

```bash
cd /Users/park/Desktop/project/animal && git add src/main/kotlin/com/park/animal/common/http/error/ErrorCode.kt src/main/kotlin/com/park/animal/common/http/error/GlobalExceptionController.kt src/main/kotlin/com/park/animal/notification/repository/NotificationRepository.kt src/main/kotlin/com/park/animal/notification/NotificationService.kt src/test/kotlin/com/park/animal/common/http/error/GlobalExceptionMappingTest.kt src/test/kotlin/com/park/animal/notification/NotificationOrderingTest.kt && git commit -m "fix(http): 본문·헤더 바인딩 예외 400 매핑 + 함께 찾기 ErrorCode 11종 + 알림 정렬 tiebreaker

- HttpMessageNotReadableException(F9) / MissingRequestHeaderException(F10) 이
  핸들러에 없어 500 으로 떨어지던 것을 400 MISSING_PARAMETER 로 매핑.
  이 레포에는 spring-boot-starter-validation 이 없어(F11) 본문 형태 오류를
  400 으로 만드는 지점이 여기뿐이다.
- 설계 15 표에 맞춰 수색그룹/팀 ErrorCode 11개 추가(404/403/409/410/400).
  기존 NOT_FOUND_ROUTE/MISSING_PARAMETER 는 이미 있어 재선언하지 않는다.
  legacy NOT_FOUND_* 의 400 관례도 소급 변경하지 않는다.
  400 경계: 바인딩 실패=MISSING_PARAMETER, 서비스 검증 실패=INVALID_COLLABORATION_INPUT.
- notification 목록 정렬에 id DESC tiebreaker 추가(F20). created_at 이 초 정밀도라
  동일 초 다건이 offset 페이지 경계에서 중복/누락됐다.
  파생 쿼리명이 findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc 로 바뀐다."
```

---

### Task 2: V12 마이그레이션 + 엔티티/리포지토리 + 마이그레이션 IT

계약 §6 의 DDL 을 그대로 `V12__add_search_group_and_team.sql` 로 넣고, 그 위에 7개 엔티티와 리포지토리를 얹는다. **여기서 정의하는 리포지토리 시그니처가 Task 3~11 전부의 데이터 접근 계약이며, 이후 태스크는 리포지토리 파일을 재정의하거나 전체 교체하지 않고 그대로 사용한다.** 그래서 이 태스크는 (1) 모든 child 조회가 부모 id 를 함께 받고(설계 §16.1 IDOR), (2) 상태 변경을 `UPDATE ... WHERE id = :id AND <parent> = :parent AND status = :expected` 조건부 전이로만 노출하며, (3) 비관적 락(`@Lock(PESSIMISTIC_WRITE)` / `FOR UPDATE`)을 **도입하지 않는다**(계약 F23 — 레포에 선례가 0개다. 동시성 안전성은 조건부 UPDATE 의 영향 행 수 0/1 이 보장한다).

**Files:**
- Create: `src/main/resources/db/migration/V12__add_search_group_and_team.sql`
- Create: `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupEnums.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroup.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupMember.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupUserBlock.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupTeam.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupEvent.kt`
- Create: `src/main/kotlin/com/park/animal/team/entity/TeamEnums.kt`
- Create: `src/main/kotlin/com/park/animal/team/entity/Team.kt`
- Create: `src/main/kotlin/com/park/animal/team/entity/TeamMember.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupRepository.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupMemberRepository.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupUserBlockRepository.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupTeamRepository.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupEventRepository.kt`
- Create: `src/main/kotlin/com/park/animal/team/repository/TeamRepository.kt`
- Create: `src/main/kotlin/com/park/animal/team/repository/TeamMemberRepository.kt`
- Modify: `src/main/kotlin/com/park/animal/post/repository/PostRepository.kt` (30행 `PostQueryRepository {` 과 31행 `@Modifying` 사이에 `findByIdAndDeletedAtIsNull` 만 삽입)
- Test: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupMigrationIT.kt`

**Interfaces:**
- Consumes:
  - Task 1 의 `ErrorCode.NOT_FOUND_SEARCH_GROUP` 외 11종 (이 태스크에서는 아직 던지지 않는다 — Task 3 부터)
  - Task 1 의 `NotificationRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc` (이 태스크의 `@DataJpaTest` 가 부팅 시 파생 쿼리 해석을 검증한다)
  - Task 0 의 `test` job (`SearchGroupMigrationIT` 가 CI 에서 실행되는 근거)
- Produces — enum:
  - `JoinPolicy`, `SearchGroupStatus`, `ArchivedReason`, `SearchGroupMemberStatus`, `SearchGroupTeamStatus`, `SearchGroupEventType`, `TeamStatus`, `TeamRole`, `TeamMemberStatus`
- Produces — entity:
  - `SearchGroup`, `SearchGroupMember`, `SearchGroupUserBlock`, `SearchGroupTeam`, `SearchGroupEvent`, `Team`, `TeamMember`
- Produces — 리포지토리 **최종 시그니처** (Task 3~11 은 이 목록만 쓰고, 이 파일들을 다시 쓰지 않는다):
  - `SearchGroupRepository.findByIdAndDeletedAtIsNull(id: UUID): SearchGroup?`
  - `SearchGroupRepository.findByPostIdAndDeletedAtIsNull(postId: UUID): SearchGroup?`
  - `SearchGroupRepository.existsByPostId(postId: UUID): Boolean`
  - `SearchGroupRepository.archiveIfStatus(groupId: UUID, expected: SearchGroupStatus, next: SearchGroupStatus, reason: ArchivedReason, archivedAt: LocalDateTime, archivedBy: UUID?): Int`
  - `SearchGroupRepository.updateJoinPolicyFrom(groupId: UUID, expected: JoinPolicy, next: JoinPolicy, activeStatus: SearchGroupStatus, now: LocalDateTime): Int`
  - `SearchGroupMemberRepository.findByGroupIdAndUserId(groupId: UUID, userId: UUID): SearchGroupMember?`
  - `SearchGroupMemberRepository.findByIdAndGroupId(id: UUID, groupId: UUID): SearchGroupMember?`
  - `SearchGroupMemberRepository.findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID): List<SearchGroupMember>`
  - `SearchGroupMemberRepository.findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(groupId: UUID, status: SearchGroupMemberStatus): List<SearchGroupMember>`
  - `SearchGroupMemberRepository.findAllByUserIdAndStatus(userId: UUID, status: SearchGroupMemberStatus): List<SearchGroupMember>`
  - `SearchGroupMemberRepository.countByGroupIdAndStatus(groupId: UUID, status: SearchGroupMemberStatus): Long`
  - `SearchGroupMemberRepository.transition(membershipId: UUID, groupId: UUID, expected: SearchGroupMemberStatus, next: SearchGroupMemberStatus, decidedBy: UUID?, occurredAt: LocalDateTime): Int`
  - `SearchGroupMemberRepository.activate(membershipId: UUID, groupId: UUID, expected: SearchGroupMemberStatus, decidedBy: UUID?, occurredAt: LocalDateTime): Int`
  - `SearchGroupUserBlockRepository.findByGroupIdAndUserId(groupId: UUID, userId: UUID): SearchGroupUserBlock?`
  - `SearchGroupUserBlockRepository.findAllByGroupIdAndUnblockedAtIsNullOrderByBlockedAtDescIdDesc(groupId: UUID): List<SearchGroupUserBlock>`
  - `SearchGroupUserBlockRepository.countByGroupIdAndUserIdAndUnblockedAtIsNull(groupId: UUID, userId: UUID): Long`
  - `SearchGroupUserBlockRepository.reactivate(blockId: UUID, groupId: UUID, blockedBy: UUID, occurredAt: LocalDateTime): Int`
  - `SearchGroupUserBlockRepository.deactivate(blockId: UUID, groupId: UUID, occurredAt: LocalDateTime): Int`
  - `SearchGroupTeamRepository.findByGroupIdAndTeamId(groupId: UUID, teamId: UUID): SearchGroupTeam?`
  - `SearchGroupTeamRepository.findByIdAndGroupId(id: UUID, groupId: UUID): SearchGroupTeam?`
  - `SearchGroupTeamRepository.findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID): List<SearchGroupTeam>`
  - `SearchGroupTeamRepository.findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(groupId: UUID, status: SearchGroupTeamStatus): List<SearchGroupTeam>`
  - `SearchGroupTeamRepository.findAllByTeamIdAndStatus(teamId: UUID, status: SearchGroupTeamStatus): List<SearchGroupTeam>` (Task 8 의 팀 보관이 팀의 ACTIVE 지원 연결을 일괄 회수할 때 쓴다)
  - `SearchGroupTeamRepository.countByGroupIdAndStatus(groupId: UUID, status: SearchGroupTeamStatus): Long`
  - `SearchGroupTeamRepository.transition(supportId: UUID, groupId: UUID, expected: SearchGroupTeamStatus, next: SearchGroupTeamStatus, decidedBy: UUID?, occurredAt: LocalDateTime): Int`
  - `SearchGroupTeamRepository.activate(supportId: UUID, groupId: UUID, expected: SearchGroupTeamStatus, decidedBy: UUID?, activatedAt: LocalDateTime): Int`
  - `SearchGroupEventRepository.findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID, pageable: Pageable): Page<SearchGroupEvent>`
  - `TeamRepository.findByIdAndDeletedAtIsNull(id: UUID): Team?`
  - `TeamRepository.searchByName(q: String, status: TeamStatus, pageable: Pageable): Page<Team>`
  - `TeamRepository.findAllByCreatedByAndStatus(createdBy: UUID, status: TeamStatus): List<Team>`
  - `TeamRepository.archiveIfStatus(teamId: UUID, expected: TeamStatus, next: TeamStatus, occurredAt: LocalDateTime): Int`
  - `TeamMemberRepository.findByTeamIdAndUserId(teamId: UUID, userId: UUID): TeamMember?`
  - `TeamMemberRepository.findByIdAndTeamId(id: UUID, teamId: UUID): TeamMember?`
  - `TeamMemberRepository.findFirstByTeamIdAndRoleAndStatus(teamId: UUID, role: TeamRole, status: TeamMemberStatus): TeamMember?`
  - `TeamMemberRepository.findAllByTeamIdAndStatus(teamId: UUID, status: TeamMemberStatus): List<TeamMember>` (Task 5 의 `GroupNotificationPublisher.notifyTeamMembers` 가 소비한다)
  - `TeamMemberRepository.findAllByTeamIdOrderByCreatedAtDescIdDesc(teamId: UUID): List<TeamMember>`
  - `TeamMemberRepository.findAllByUserIdAndStatus(userId: UUID, status: TeamMemberStatus): List<TeamMember>`
  - `TeamMemberRepository.countByTeamIdAndStatus(teamId: UUID, status: TeamMemberStatus): Long`
  - `TeamMemberRepository.transition(membershipId: UUID, teamId: UUID, expected: TeamMemberStatus, next: TeamMemberStatus, decidedBy: UUID?, occurredAt: LocalDateTime): Int`
  - `TeamMemberRepository.activate(membershipId: UUID, teamId: UUID, expected: TeamMemberStatus, decidedBy: UUID?, occurredAt: LocalDateTime): Int`
  - `TeamMemberRepository.changeRole(membershipId: UUID, teamId: UUID, expected: TeamRole, next: TeamRole, occurredAt: LocalDateTime): Int`
  - `PostRepository.findByIdAndDeletedAtIsNull(id: UUID): Post?`

---

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupMigrationIT.kt`.

핵심 설계: 백필은 "V12 실행 시점에 존재하는 post" 를 대상으로 하므로, 컨테이너에 **V1~V11 만 먼저 적용하고 post 를 심은 뒤** V12 를 적용해야 검증이 성립한다. `@DynamicPropertySource` 메서드는 컨테이너 기동 후 · ApplicationContext 생성 전에 정확히 한 번 호출되므로 여기서 V11 까지 스테이징 + 시딩을 하고, **V12 는 애플리케이션 자신의 Flyway 설정이 적용**하게 둔다(운영과 동일 경로 검증 + V12 가 깨지면 컨텍스트 로딩 실패로 즉시 드러남).

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V12(함께 찾기 스키마) 마이그레이션 검증 — 운영과 동일한 mysql:8.4 + Flyway 전체 체인.
 *
 * 백필은 "V12 실행 시점의 post 스냅샷" 을 대상으로 하므로, 컨테이너에 V1~V11 만 먼저 적용하고
 * post 를 심은 뒤 V12 를 돌려야 검증이 성립한다. @DynamicPropertySource 는 컨테이너 기동 후,
 * ApplicationContext 생성 전에 정확히 한 번 실행되므로 그 안에서 V11 스테이징과 시딩을 끝낸다.
 * V12 는 일부러 적용하지 않고 애플리케이션 자신의 Flyway 오토컨피그가 올리게 둔다 —
 * 운영과 같은 경로로 검증되고, V12 가 깨지면 컨텍스트 로딩 실패로 즉시 드러난다.
 *
 * 트랜잭션 래핑을 끄는 이유(NOT_SUPPORTED, SearchFulltextIT 와 동일):
 * UNIQUE 제약 위반과 조건부 UPDATE 의 영향 행 수는 실제 커밋 경계에서만 관측 가능하다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig::class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SearchGroupMigrationIT {
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var teamRepository: TeamRepository

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.javaObjectType) ?: -1L

    private fun collationOf(
        table: String,
        column: String,
    ): String? =
        jdbcTemplate.queryForObject(
            """
            SELECT COLLATION_NAME FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '$table' AND COLUMN_NAME = '$column'
            """.trimIndent(),
            String::class.java,
        )

    @Test
    @Order(1)
    fun `V1부터 V12까지 전체 체인이 깨끗한 컨테이너에서 통과한다`() {
        assertEquals(0L, count("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"), "실패한 마이그레이션이 남아 있다")
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = 1"),
            "V12 가 적용되지 않았다",
        )

        assertEquals(
            7L,
            count(
                """
                SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME IN ('search_group', 'search_group_member', 'search_group_user_block',
                                      'team', 'team_member', 'search_group_team', 'search_group_event')
                """.trimIndent(),
            ),
            "V12 가 만들어야 할 테이블 7개가 다 없다",
        )

        // BaseEntity 매핑상 deleted_at 이 없으면 모든 SELECT 가 Unknown column 으로 죽는다(F7).
        assertEquals(
            7L,
            count(
                """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'deleted_at'
                   AND TABLE_NAME IN ('search_group', 'search_group_member', 'search_group_user_block',
                                      'team', 'team_member', 'search_group_team', 'search_group_event')
                """.trimIndent(),
            ),
            "신규 테이블에 deleted_at 이 빠졌다",
        )

        assertEquals(
            5L,
            count(
                """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'
                   AND COLUMN_NAME IN ('actor_user_id', 'actor_name', 'post_id', 'group_id', 'team_id')
                """.trimIndent(),
            ),
            "notification 구조화 컨텍스트 컬럼 5개가 없다",
        )
    }

    @Test
    @Order(2)
    fun `백필은 삭제되지 않은 SEARCHING 소식에만 OPEN ACTIVE 그룹을 하나씩 만든다`() {
        assertEquals(2L, count("SELECT COUNT(*) FROM search_group"), "백필 대상은 SEARCHING 2건뿐이어야 한다")

        listOf(POST_SEARCHING_A, POST_SEARCHING_B).forEach { postId ->
            assertEquals(
                1L,
                count("SELECT COUNT(*) FROM search_group WHERE post_id = '$postId'"),
                "SEARCHING 소식 $postId 에 그룹이 정확히 1개여야 한다",
            )
        }

        assertEquals(
            2L,
            count(
                """
                SELECT COUNT(*) FROM search_group
                 WHERE join_policy = 'OPEN' AND status = 'ACTIVE'
                   AND archived_reason IS NULL AND archived_at IS NULL AND deleted_at IS NULL
                """.trimIndent(),
            ),
            "백필 그룹은 OPEN/ACTIVE 이고 보관 필드가 비어 있어야 한다",
        )

        listOf(POST_SEEN, POST_FOUND, POST_SEARCHING_DELETED).forEach { postId ->
            assertEquals(
                0L,
                count("SELECT COUNT(*) FROM search_group WHERE post_id = '$postId'"),
                "SEEN / FOUND / soft-delete 는 백필 대상이 아니다 ($postId)",
            )
        }

        // 엔티티 매핑도 함께 확인 — 백필 행의 id 는 MySQL UUID() 가 만든 문자열이다.
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(UUID.fromString(POST_SEARCHING_A))
        assertNotNull(group, "백필된 그룹을 엔티티로 읽을 수 있어야 한다")
        assertEquals(JoinPolicy.OPEN, group.joinPolicy)
        assertEquals(SearchGroupStatus.ACTIVE, group.status)
        assertNull(group.archivedReason)
    }

    @Test
    @Order(3)
    fun `uq_tm_single_active_leader 는 같은 팀의 두 번째 활성 팀장을 거부한다`() {
        val team =
            teamRepository.save(
                Team(name = "강남 수색팀", description = "테스트 팀", createdBy = UUID.randomUUID()),
            )

        val leader =
            teamMemberRepository.save(
                TeamMember(
                    teamId = team.id,
                    userId = UUID.randomUUID(),
                    userName = "팀장",
                    role = TeamRole.LEADER,
                    status = TeamMemberStatus.ACTIVE,
                    joinedAt = LocalDateTime.now(),
                ),
            )
        assertNotNull(leader.id)

        // active_leader_key(GENERATED STORED)가 엔티티에 매핑돼 있으면 여기서 1062 가 아니라
        // "value specified for generated column is not allowed"(3105) 로 실패한다.
        val duplicate =
            assertFailsWith<DataIntegrityViolationException> {
                teamMemberRepository.save(
                    TeamMember(
                        teamId = team.id,
                        userId = UUID.randomUUID(),
                        userName = "두번째 팀장",
                        role = TeamRole.LEADER,
                        status = TeamMemberStatus.ACTIVE,
                        joinedAt = LocalDateTime.now(),
                    ),
                )
            }
        assertTrue(
            duplicate.message?.contains("uq_tm_single_active_leader") == true,
            "uq_tm_single_active_leader 위반이어야 한다. 실제: ${duplicate.message}",
        )

        // 활성이 아닌 팀장 이력은 generated key 가 NULL 이라 충돌하지 않는다.
        val formerLeader =
            teamMemberRepository.save(
                TeamMember(
                    teamId = team.id,
                    userId = UUID.randomUUID(),
                    userName = "전 팀장",
                    role = TeamRole.LEADER,
                    status = TeamMemberStatus.LEFT,
                    joinedAt = LocalDateTime.now(),
                ),
            )
        assertNotNull(formerLeader.id)

        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM team_member WHERE team_id = '${team.id}' AND active_leader_key IS NOT NULL"),
            "활성 팀장 generated key 는 팀당 1개여야 한다",
        )
    }

    @Test
    @Order(4)
    fun `post id 와 search_group post_id 의 collation 이 일치한다`() {
        val postIdCollation = collationOf("post", "id")
        val fkCollation = collationOf("search_group", "post_id")

        assertNotNull(postIdCollation, "post.id collation 을 읽지 못했다")
        assertNotNull(fkCollation, "search_group.post_id collation 을 읽지 못했다")
        assertEquals(
            postIdCollation,
            fkCollation,
            "collation 이 다르면 fk_search_group_post 가 마이그레이션 타임에 터진다",
        )

        assertEquals(
            1L,
            count(
                """
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'search_group'
                   AND CONSTRAINT_NAME = 'fk_search_group_post' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """.trimIndent(),
            ),
            "fk_search_group_post 가 생성되지 않았다",
        )
    }

    @Test
    @Order(5)
    fun `멤버십 재가입은 새 행을 만들지 않고 같은 행의 status 만 되돌린다`() {
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(UUID.fromString(POST_SEARCHING_B))!!
        val userId = UUID.randomUUID()

        val saved =
            searchGroupMemberRepository.save(
                SearchGroupMember(
                    groupId = group.id,
                    userId = userId,
                    userName = "참여자",
                    status = SearchGroupMemberStatus.ACTIVE,
                    joinedAt = LocalDateTime.now(),
                ),
            )
        val membershipId = saved.id

        // ACTIVE -> LEFT
        assertEquals(
            1,
            searchGroupMemberRepository.transition(
                membershipId = membershipId,
                groupId = group.id,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.LEFT,
                decidedBy = userId,
                occurredAt = LocalDateTime.now(),
            ),
        )

        // 같은 전이를 한 번 더 = 영향 행 0 (이미 다른 상태). 409 판정의 근거다.
        assertEquals(
            0,
            searchGroupMemberRepository.transition(
                membershipId = membershipId,
                groupId = group.id,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.LEFT,
                decidedBy = userId,
                occurredAt = LocalDateTime.now(),
            ),
        )

        // LEFT -> ACTIVE (재가입). @SQLDelete 가 없으므로 UNIQUE(group_id,user_id)와 충돌하지 않는다.
        assertEquals(
            1,
            searchGroupMemberRepository.activate(
                membershipId = membershipId,
                groupId = group.id,
                expected = SearchGroupMemberStatus.LEFT,
                decidedBy = userId,
                occurredAt = LocalDateTime.now(),
            ),
        )

        val reloaded = searchGroupMemberRepository.findByGroupIdAndUserId(group.id, userId)
        assertNotNull(reloaded)
        assertEquals(membershipId, reloaded.id, "재가입이 새 행을 만들면 안 된다 (F15)")
        assertEquals(SearchGroupMemberStatus.ACTIVE, reloaded.status)
        assertNull(reloaded.deletedAt, "멤버십 테이블은 deleted_at 을 절대 쓰지 않는다")

        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM search_group_member WHERE group_id = '${group.id}' AND user_id = '$userId'"),
            "같은 (group_id,user_id) 행은 언제나 1개여야 한다",
        )

        // 다른 그룹 id 로는 같은 멤버십을 건드릴 수 없어야 한다 (IDOR 방어, 설계 16.1).
        val otherGroup = searchGroupRepository.findByPostIdAndDeletedAtIsNull(UUID.fromString(POST_SEARCHING_A))!!
        assertEquals(
            0,
            searchGroupMemberRepository.transition(
                membershipId = membershipId,
                groupId = otherGroup.id,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.REMOVED,
                decidedBy = UUID.randomUUID(),
                occurredAt = LocalDateTime.now(),
            ),
        )
        assertNull(searchGroupMemberRepository.findByIdAndGroupId(membershipId, otherGroup.id))
    }

    companion object {
        private const val POST_SEARCHING_A = "aaaaaaaa-0000-4000-8000-000000000001"
        private const val POST_SEARCHING_B = "aaaaaaaa-0000-4000-8000-000000000002"
        private const val POST_SEEN = "bbbbbbbb-0000-4000-8000-000000000001"
        private const val POST_FOUND = "cccccccc-0000-4000-8000-000000000001"
        private const val POST_SEARCHING_DELETED = "dddddddd-0000-4000-8000-000000000001"
        private const val OWNER_ID = "eeeeeeee-0000-4000-8000-000000000001"

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            stageUpToV11AndSeedPosts()

            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            // 운영 마이그레이션 체인(V2 빈 파일 = legacy 스키마) 보완: 테스트 전용 V2.1 베이스라인 추가
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }

        /**
         * V1~V11 만 적용하고 백필 대상/비대상 post 를 심는다. V12 는 애플리케이션 Flyway 가 올린다.
         * @DynamicPropertySource 는 컨테이너 기동 후 · DataSource 생성 전에 호출되므로 여기가 유일한 자리다.
         */
        private fun stageUpToV11AndSeedPosts() {
            val dataSource = DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)

            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/testsupport")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion("11"))
                .load()
                .migrate()

            val jdbc = JdbcTemplate(dataSource)
            insertPost(jdbc, POST_SEARCHING_A, "수색중 A", "SEARCHING", softDeleted = false)
            insertPost(jdbc, POST_SEARCHING_B, "수색중 B", "SEARCHING", softDeleted = false)
            insertPost(jdbc, POST_SEEN, "목격됨", "SEEN", softDeleted = false)
            insertPost(jdbc, POST_FOUND, "찾음", "FOUND", softDeleted = false)
            insertPost(jdbc, POST_SEARCHING_DELETED, "삭제된 수색중", "SEARCHING", softDeleted = true)
        }

        private fun insertPost(
            jdbc: JdbcTemplate,
            id: String,
            title: String,
            status: String,
            softDeleted: Boolean,
        ) {
            val deletedAt = if (softDeleted) "NOW(6)" else "NULL"
            jdbc.update(
                """
                INSERT INTO post (id, author_id, author_name, title, phone_num, time, place, gender, gratuity,
                                  description, lat, lng, open_chat_url, missing_animal_status, animal_type,
                                  created_at, updated_at, deleted_at)
                VALUES (?, ?, '보호자', ?, '010-0000-0000', NOW(6), '서울 강남구 역삼동', '남아', 0,
                        '백필 검증용 시드 데이터', 37.5012, 127.0396, NULL, ?, 'DOG', NOW(6), NOW(6), $deletedAt)
                """.trimIndent(),
                id,
                OWNER_ID,
                title,
                status,
            )
        }
    }
}
```

---

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchGroupMigrationIT"
```

Expected: FAIL — 컴파일 단계에서 멈춘다.
```
e: .../SearchGroupMigrationIT.kt:3:32 Unresolved reference: searchgroup
e: .../SearchGroupMigrationIT.kt:10:26 Unresolved reference: team
e: .../SearchGroupMigrationIT.kt:60:5 Unresolved reference: SearchGroupRepository
...
> Task :compileTestKotlin FAILED
```

---

- [ ] **Step 3: 최소 구현**

**3-1. `src/main/resources/db/migration/V12__add_search_group_and_team.sql`** (계약 §6 그대로 — 이 파일은 변경 금지)

```sql
-- 함께 찾기 (수색그룹 · 팀 · 지원 연결) — 설계 6, 17.
-- 신규 테이블 타임스탬프는 DATETIME(6): post(DATETIME(6))와 조인 정합 + TIMESTAMP 의 세션 timezone
-- 변환/초 정밀도 문제 회피. BaseEntity 매핑상 deleted_at 은 모든 테이블에 필수(V10 사고 재발 방지).
-- 멤버십/연결 테이블은 deleted_at 을 사용하지 않는다 — 생명주기는 status 전이로만 표현한다.

CREATE TABLE search_group
(
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    post_id         VARCHAR(36) NOT NULL,
    join_policy     VARCHAR(32) NOT NULL DEFAULT 'OPEN',   -- OPEN / APPROVAL_REQUIRED
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / ARCHIVED
    archived_reason VARCHAR(32)      DEFAULT NULL,         -- FOUND / POST_DELETED
    archived_at     DATETIME(6)      DEFAULT NULL,
    archived_by     VARCHAR(36)      DEFAULT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    deleted_at      DATETIME(6)      DEFAULT NULL,
    UNIQUE KEY uq_search_group_post (post_id),
    KEY idx_search_group_status (status, created_at),
    CONSTRAINT fk_search_group_post FOREIGN KEY (post_id) REFERENCES post (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE search_group_member
(
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    group_id     VARCHAR(36) NOT NULL,
    user_id      VARCHAR(36) NOT NULL,
    user_name    VARCHAR(64)      DEFAULT NULL,
    status       VARCHAR(32) NOT NULL,                  -- PENDING / ACTIVE / REJECTED / LEFT / REMOVED
    joined_at    DATETIME(6)      DEFAULT NULL,
    requested_at DATETIME(6)      DEFAULT NULL,
    decided_at   DATETIME(6)      DEFAULT NULL,
    decided_by   VARCHAR(36)      DEFAULT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    deleted_at   DATETIME(6)      DEFAULT NULL,
    UNIQUE KEY uq_sgm_group_user (group_id, user_id),
    KEY idx_sgm_group_status (group_id, status, created_at),
    KEY idx_sgm_user_status (user_id, status, group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE search_group_user_block
(
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    group_id     VARCHAR(36)  NOT NULL,
    user_id      VARCHAR(36)  NOT NULL,
    blocked_by   VARCHAR(36)  NOT NULL,
    reason       VARCHAR(500)      DEFAULT NULL,        -- 운영 감사용. 참여자에게 노출 금지
    blocked_at   DATETIME(6)  NOT NULL,
    unblocked_at DATETIME(6)       DEFAULT NULL,        -- NULL = 차단 활성
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6)       DEFAULT NULL,
    UNIQUE KEY uq_sgub_group_user (group_id, user_id),
    KEY idx_sgub_group_active (group_id, unblocked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE team
(
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    name        VARCHAR(30)  NOT NULL,
    description VARCHAR(200)      DEFAULT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / ARCHIVED
    created_by  VARCHAR(36)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    deleted_at  DATETIME(6)       DEFAULT NULL,
    KEY idx_team_status_name (status, name),
    KEY idx_team_creator (created_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE team_member
(
    id                VARCHAR(36) NOT NULL PRIMARY KEY,
    team_id           VARCHAR(36) NOT NULL,
    user_id           VARCHAR(36) NOT NULL,
    user_name         VARCHAR(64)      DEFAULT NULL,
    role              VARCHAR(32) NOT NULL,             -- LEADER / MEMBER
    status            VARCHAR(32) NOT NULL,             -- PENDING / ACTIVE / REJECTED / LEFT / REMOVED
    joined_at         DATETIME(6)      DEFAULT NULL,
    requested_at      DATETIME(6)      DEFAULT NULL,
    decided_at        DATETIME(6)      DEFAULT NULL,
    decided_by        VARCHAR(36)      DEFAULT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    deleted_at        DATETIME(6)      DEFAULT NULL,
    -- 팀당 활성 팀장 1명 불변식을 DB 로 강제. NULL 은 UNIQUE 충돌하지 않는다.
    active_leader_key VARCHAR(36) GENERATED ALWAYS AS
        (CASE WHEN role = 'LEADER' AND status = 'ACTIVE' THEN team_id ELSE NULL END) STORED,
    UNIQUE KEY uq_tm_team_user (team_id, user_id),
    UNIQUE KEY uq_tm_single_active_leader (active_leader_key),
    KEY idx_tm_user_status (user_id, status, team_id),
    KEY idx_tm_team_status (team_id, status, role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE search_group_team
(
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    group_id      VARCHAR(36) NOT NULL,
    team_id       VARCHAR(36) NOT NULL,
    status        VARCHAR(32) NOT NULL,
    requested_by  VARCHAR(36) NOT NULL,
    requested_at  DATETIME(6) NOT NULL,
    decided_by    VARCHAR(36)      DEFAULT NULL,
    decided_at    DATETIME(6)      DEFAULT NULL,
    activated_at  DATETIME(6)      DEFAULT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    deleted_at    DATETIME(6)      DEFAULT NULL,
    UNIQUE KEY uq_sgt_group_team (group_id, team_id),
    KEY idx_sgt_team_status (team_id, status, group_id),
    KEY idx_sgt_group_status (group_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE search_group_event
(
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    group_id     VARCHAR(36) NOT NULL,
    type         VARCHAR(64) NOT NULL,
    actor_id     VARCHAR(36)      DEFAULT NULL,
    target_id    VARCHAR(36)      DEFAULT NULL,   -- 대상 사용자/팀 id. 본문·좌표 저장 금지
    detail       VARCHAR(256)     DEFAULT NULL,   -- 상태 전이 요약만. 민감정보 금지
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    deleted_at   DATETIME(6)      DEFAULT NULL,
    KEY idx_sge_group_created (group_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 알림 구조화 컨텍스트 (설계 9). 기존 행은 전부 NULL 로 남아 하위 호환.
-- user_id 컬럼명은 유지한다 (파생 쿼리 4개 + markAllRead JPQL + V7 인덱스가 참조).
ALTER TABLE notification
    ADD COLUMN actor_user_id VARCHAR(36) DEFAULT NULL,
    ADD COLUMN actor_name    VARCHAR(64) DEFAULT NULL,
    ADD COLUMN post_id       VARCHAR(36) DEFAULT NULL,
    ADD COLUMN group_id      VARCHAR(36) DEFAULT NULL,
    ADD COLUMN team_id       VARCHAR(36) DEFAULT NULL;

CREATE INDEX idx_noti_user_group ON notification (user_id, group_id, created_at);

-- 백필: 기능 도입 시점의 삭제되지 않은 SEARCHING 실종 소식마다 OPEN·ACTIVE 그룹 1개 (설계 17 말미).
-- SEEN / FOUND / soft-delete 는 대상 아님. NOT EXISTS 가드로 개별 재실행 가능.
-- MySQL UUID() 는 v1 이라 시간순 정렬이 되지 않는다 → search_group 을 id 로 정렬/페이징하지 말 것.
INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at)
SELECT UUID(), p.id, 'OPEN', 'ACTIVE', NOW(6), NOW(6)
  FROM post p
 WHERE p.missing_animal_status = 'SEARCHING'
   AND p.deleted_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM search_group g WHERE g.post_id = p.id);
```

**3-2. `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupEnums.kt`**

```kotlin
package com.park.animal.searchgroup.entity

/** 수색그룹 참여 정책. 기본은 자유롭게 참여(OPEN). */
enum class JoinPolicy { OPEN, APPROVAL_REQUIRED }

/** 수색그룹 생명주기. 종료된 그룹은 ARCHIVED 이며 재활성화 API 는 없다(설계 7). */
enum class SearchGroupStatus { ACTIVE, ARCHIVED }

/** 보관 사유. SEEN 은 그룹을 보관하지 않으므로 여기에 없다. */
enum class ArchivedReason { FOUND, POST_DELETED }

/**
 * 직접 참여 멤버십 상태. soft-delete 를 쓰지 않고 이 값으로만 생명주기를 표현한다.
 * 재가입은 LEFT/REMOVED/REJECTED 행의 ACTIVE 전이다 — 새 행을 만들지 않는다(F15).
 */
enum class SearchGroupMemberStatus { PENDING, ACTIVE, REJECTED, LEFT, REMOVED }

/** 팀 지원 연결 상태. 어느 쪽이 먼저 요청했는지에 따라 대기 주체가 갈린다. */
enum class SearchGroupTeamStatus {
    PENDING_GROUP_APPROVAL,
    PENDING_TEAM_APPROVAL,
    ACTIVE,
    DECLINED,
    WITHDRAWN,
    REMOVED,
}

/** 감사 이벤트 종류(설계 20). 본문·좌표는 절대 남기지 않고 행위자/대상 id 와 상태만 기록한다. */
enum class SearchGroupEventType {
    GROUP_OPENED,
    JOIN_POLICY_CHANGED,
    MEMBER_JOINED,
    MEMBER_REQUESTED,
    MEMBER_APPROVED,
    MEMBER_REJECTED,
    MEMBER_LEFT,
    MEMBER_REMOVED,
    USER_BLOCKED,
    USER_UNBLOCKED,
    TEAM_SUPPORT_REQUESTED,
    TEAM_SUPPORT_ACCEPTED,
    TEAM_SUPPORT_DECLINED,
    TEAM_SUPPORT_WITHDRAWN,
    TEAM_SUPPORT_REMOVED,
    SEARCH_ENDED,
    GROUP_ARCHIVED_BY_POST_DELETE,
}
```

**3-3. `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroup.kt`**

```kotlin
package com.park.animal.searchgroup.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val SEARCH_GROUP_TABLE_NAME = "search_group"

/**
 * 실종 소식 1건에 붙는 수색그룹. post 와 1:1(UNIQUE post_id).
 *
 * @SQLDelete 를 붙이지 않는다. 그룹의 종료는 삭제가 아니라 status = ARCHIVED 전이이고,
 * 종료된 그룹도 이전 기록은 계속 읽을 수 있어야 한다(설계 14.1).
 */
@Entity
@Table(
    name = SEARCH_GROUP_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_search_group_post", columnNames = ["post_id"])],
)
class SearchGroup(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "post_id", nullable = false)
    val postId: UUID,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "join_policy", nullable = false, length = 32)
    var joinPolicy: JoinPolicy = JoinPolicy.OPEN,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: SearchGroupStatus = SearchGroupStatus.ACTIVE,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "archived_reason", length = 32)
    var archivedReason: ArchivedReason? = null,
    @Column(name = "archived_at")
    var archivedAt: LocalDateTime? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "archived_by")
    var archivedBy: UUID? = null,
) : BaseEntity()
```

**3-4. `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupMember.kt`**

```kotlin
package com.park.animal.searchgroup.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val SEARCH_GROUP_MEMBER_TABLE_NAME = "search_group_member"

/**
 * 수색그룹 직접 참여 멤버십.
 *
 * @SQLDelete 금지: soft-delete + UNIQUE(group_id, user_id) 조합은 재가입을 영구히 막는다.
 * post_bookmark 에서 실제로 터진 버그다(F15). 탈퇴/내보내기는 status 전이이고, 재가입은
 * 같은 행을 ACTIVE 로 되돌리는 것이다. 따라서 deleted_at 은 항상 NULL 로 남는다.
 *
 * userName 은 표시용 비정규화 값이다. 컨트롤러에서 passport.requireUserContext() 를 직접
 * 부르지 않고 runCatching 으로 얻어 이 컬럼에 저장한다.
 */
@Entity
@Table(
    name = SEARCH_GROUP_MEMBER_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_sgm_group_user", columnNames = ["group_id", "user_id"])],
)
class SearchGroupMember(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "user_name", length = 64)
    var userName: String? = null,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: SearchGroupMemberStatus,
    @Column(name = "joined_at")
    var joinedAt: LocalDateTime? = null,
    @Column(name = "requested_at")
    var requestedAt: LocalDateTime? = null,
    @Column(name = "decided_at")
    var decidedAt: LocalDateTime? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decided_by")
    var decidedBy: UUID? = null,
) : BaseEntity()
```

**3-5. `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupUserBlock.kt`**

```kotlin
package com.park.animal.searchgroup.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val SEARCH_GROUP_USER_BLOCK_TABLE_NAME = "search_group_user_block"

/**
 * 보호자의 그룹 단위 사용자 차단. unblockedAt 이 NULL 이면 차단 활성이다.
 *
 * @SQLDelete 금지 — 차단 해제 후 재차단이 UNIQUE(group_id, user_id) 에 막히면 안 된다.
 * reason 은 운영 감사용이며 보호자 전용 GET /blocks 응답 외에는 어디에도 노출하지 않는다.
 * 차단 사실 자체가 응답으로 새면 안 되므로 CTA/상세 DTO 에 isBlocked 필드를 두지 않는다(설계 16.5).
 */
@Entity
@Table(
    name = SEARCH_GROUP_USER_BLOCK_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_sgub_group_user", columnNames = ["group_id", "user_id"])],
)
class SearchGroupUserBlock(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "blocked_by", nullable = false)
    var blockedBy: UUID,
    @Column(name = "reason", length = 500)
    var reason: String? = null,
    @Column(name = "blocked_at", nullable = false)
    var blockedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "unblocked_at")
    var unblockedAt: LocalDateTime? = null,
) : BaseEntity()
```

**3-6. `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupTeam.kt`**

```kotlin
package com.park.animal.searchgroup.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val SEARCH_GROUP_TEAM_TABLE_NAME = "search_group_team"

/**
 * 수색그룹 ↔ 팀 지원 연결. 양방향 요청이 가능해 대기 주체를 status 로 구분한다.
 *
 * @SQLDelete 금지 — 지원 종료 후 재지원이 UNIQUE(group_id, team_id) 에 막히면 안 된다.
 */
@Entity
@Table(
    name = SEARCH_GROUP_TEAM_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_sgt_group_team", columnNames = ["group_id", "team_id"])],
)
class SearchGroupTeam(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "team_id", nullable = false)
    val teamId: UUID,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: SearchGroupTeamStatus,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "requested_by", nullable = false)
    val requestedBy: UUID,
    @Column(name = "requested_at", nullable = false)
    var requestedAt: LocalDateTime = LocalDateTime.now(),
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decided_by")
    var decidedBy: UUID? = null,
    @Column(name = "decided_at")
    var decidedAt: LocalDateTime? = null,
    @Column(name = "activated_at")
    var activatedAt: LocalDateTime? = null,
) : BaseEntity()
```

**3-7. `src/main/kotlin/com/park/animal/searchgroup/entity/SearchGroupEvent.kt`**

```kotlin
package com.park.animal.searchgroup.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

const val SEARCH_GROUP_EVENT_TABLE_NAME = "search_group_event"

/**
 * 그룹 감사 기록(설계 20) + 활동 탭(설계 10)의 원본.
 *
 * detail 에는 상태 전이 요약만 넣는다. 메시지 본문, 상세 좌표, 전화번호, 차단 사유는
 * 절대 저장하지 않는다(설계 16.5). @SQLDelete 금지 — 감사 기록은 지우지 않는다.
 */
@Entity
@Table(name = SEARCH_GROUP_EVENT_TABLE_NAME)
class SearchGroupEvent(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, length = 64)
    val type: SearchGroupEventType,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_id")
    val actorId: UUID? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_id")
    val targetId: UUID? = null,
    @Column(name = "detail", length = 256)
    val detail: String? = null,
) : BaseEntity()
```

**3-8. `src/main/kotlin/com/park/animal/team/entity/TeamEnums.kt`**

```kotlin
package com.park.animal.team.entity

/** 팀 생명주기. */
enum class TeamStatus { ACTIVE, ARCHIVED }

/**
 * 팀 내 역할. 제품 역할에 admin 은 존재하지 않는다(설계 21).
 * 활성 LEADER 유일성은 team_member.uq_tm_single_active_leader 가 DB 에서 강제한다.
 */
enum class TeamRole { LEADER, MEMBER }

/** 팀 멤버십 상태. SearchGroupMemberStatus 와 같은 이유로 soft-delete 를 쓰지 않는다. */
enum class TeamMemberStatus { PENDING, ACTIVE, REJECTED, LEFT, REMOVED }
```

**3-9. `src/main/kotlin/com/park/animal/team/entity/Team.kt`**

```kotlin
package com.park.animal.team.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

const val TEAM_TABLE_NAME = "team"

/**
 * 함께 찾기 팀. 여러 수색그룹을 동시에 지원할 수 있다.
 * @SQLDelete 를 붙이지 않는다 — 팀 해체도 status = ARCHIVED 전이다.
 */
@Entity
@Table(name = TEAM_TABLE_NAME)
class Team(
    @Column(name = "name", nullable = false, length = 30)
    var name: String,
    @Column(name = "description", length = 200)
    var description: String? = null,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: TeamStatus = TeamStatus.ACTIVE,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "created_by", nullable = false)
    val createdBy: UUID,
) : BaseEntity()
```

**3-10. `src/main/kotlin/com/park/animal/team/entity/TeamMember.kt`**

```kotlin
package com.park.animal.team.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val TEAM_MEMBER_TABLE_NAME = "team_member"

/**
 * 팀 멤버십.
 *
 * team_member.active_leader_key 는 매핑하지 않는다 — @Transient 도 아니고 필드 자체를 두지 않는다.
 * MySQL GENERATED ALWAYS ... STORED 컬럼이라 INSERT/UPDATE 문에 값이 포함되면 서버가
 * ERROR 3105 "The value specified for generated column 'active_leader_key' in table 'team_member'
 * is not allowed" 로 거절한다. 필드를 두면 Hibernate 가 기본적으로 INSERT 목록에 넣는다.
 * (@Generated 로 읽기 전용 매핑을 할 수도 있으나, 이 값은 애플리케이션이 읽을 일이 전혀 없고
 *  순수하게 uq_tm_single_active_leader 를 위한 DB 내부 장치이므로 매핑 자체를 생략한다.)
 *
 * @SQLDelete 금지 — 탈퇴/내보내기 후 재가입이 UNIQUE(team_id, user_id) 에 막히면 안 된다(F15).
 * 팀장 이전은 한 트랜잭션 안에서 UPDATE 두 번(강등 → 승격)으로 한다. CASE WHEN 단일 UPDATE 는
 * uq_tm_single_active_leader 가 행 단위로 검사돼 순서에 따라 1062 가 난다.
 */
@Entity
@Table(
    name = TEAM_MEMBER_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_tm_team_user", columnNames = ["team_id", "user_id"])],
)
class TeamMember(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "team_id", nullable = false)
    val teamId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "user_name", length = 64)
    var userName: String? = null,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 32)
    var role: TeamRole,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: TeamMemberStatus,
    @Column(name = "joined_at")
    var joinedAt: LocalDateTime? = null,
    @Column(name = "requested_at")
    var requestedAt: LocalDateTime? = null,
    @Column(name = "decided_at")
    var decidedAt: LocalDateTime? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decided_by")
    var decidedBy: UUID? = null,
) : BaseEntity()
```

**3-11. `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupRepository.kt`**

```kotlin
package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.ArchivedReason
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 수색그룹 리포지토리. 이 시그니처 집합이 Task 3~11 의 최종 계약이다 — 이후 태스크는 이 파일을
 * 다시 쓰지 않고 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 를 절대 쓰지 않는다. 그룹의 종료는 archiveIfStatus() 상태 전이다.
 * 2. 상태 변경은 조건부 UPDATE(`WHERE id = :id AND status = :expected`)로만 노출한다.
 *    JDBC URL 에 useAffectedRows 가 없어 Connector/J 가 CLIENT_FOUND_ROWS 로 동작하므로
 *    반환값은 "매칭된 행 수" 다. 0 = 기대한 상태가 아님 → 409 로 해석한다(F13).
 * 3. 비관적 락을 쓰지 않는다(F23). 동시 종료·동시 정책변경 경합은 위 조건부 UPDATE 의
 *    영향 행 수 0/1 로 판정한다. 재조회 후 목표 상태면 멱등 성공, 아니면 409 다.
 * 4. @Modifying 메서드에 @Transactional 을 명시한다. 명시하지 않으면 SimpleJpaRepository 의
 *    클래스 레벨 @Transactional(readOnly = true) 에 걸려 read-only 커넥션에서 UPDATE 가 터진다.
 */
interface SearchGroupRepository : JpaRepository<SearchGroup, UUID> {
    fun findByIdAndDeletedAtIsNull(id: UUID): SearchGroup?

    fun findByPostIdAndDeletedAtIsNull(postId: UUID): SearchGroup?

    fun existsByPostId(postId: UUID): Boolean

    /**
     * 그룹 보관 조건부 전이. 수색 종료(FOUND)와 실종 소식 삭제(POST_DELETED) 두 경로가 공유한다.
     * archivedBy 는 시스템 전이(글 삭제 배치 등)에서 null 일 수 있다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroup g
           SET g.status = :next,
               g.archivedReason = :reason,
               g.archivedBy = :archivedBy,
               g.archivedAt = :archivedAt,
               g.updatedAt = :archivedAt
         WHERE g.id = :groupId AND g.status = :expected AND g.deletedAt IS NULL
        """,
    )
    fun archiveIfStatus(
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupStatus,
        @Param("next") next: SearchGroupStatus,
        @Param("reason") reason: ArchivedReason,
        @Param("archivedAt") archivedAt: LocalDateTime,
        @Param("archivedBy") archivedBy: UUID?,
    ): Int

    /**
     * 참여 정책 조건부 변경. 현재 정책이 :expected 이고 그룹이 :activeStatus 일 때만 바꾼다.
     * 영향 행 0 = 다른 요청이 먼저 바꿨거나 그룹이 보관됨 → 호출부가 재조회해 멱등/409 를 가른다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroup g
           SET g.joinPolicy = :next, g.updatedAt = :now
         WHERE g.id = :groupId
           AND g.joinPolicy = :expected
           AND g.status = :activeStatus
           AND g.deletedAt IS NULL
        """,
    )
    fun updateJoinPolicyFrom(
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: JoinPolicy,
        @Param("next") next: JoinPolicy,
        @Param("activeStatus") activeStatus: SearchGroupStatus,
        @Param("now") now: LocalDateTime,
    ): Int
}
```

**3-12. `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupMemberRepository.kt`**

```kotlin
package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 직접 참여 멤버십 리포지토리. Task 6(참여 lifecycle)·Task 7(차단)·Task 10(허브)이 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 를 절대 쓰지 않는다. 탈퇴·내보내기·재가입은 전부 status 전이다(F15).
 * 2. child 조회는 반드시 부모 groupId 를 함께 받는다. membershipId 단독 조회 후 그룹 권한을
 *    따로 확인하는 형태는 IDOR 을 부른다(설계 16.1). findById 는 쓰지 않는다.
 * 3. 목록 정렬에는 createdAt 뒤에 id tiebreaker 를 둔다 — 동일 시각 다건에서 순서가 흔들린다(F20).
 *    phase 1 멤버십 목록에는 페이징이 없으므로 Pageable 을 받지 않고 List 를 그대로 돌려준다.
 * 4. 비관적 락을 쓰지 않는다(F23). 동시 승인/탈퇴 경합은 transition()/activate() 의
 *    `AND m.status = :expected` 가 직렬화한다 — 둘 중 하나만 1 을 받는다.
 */
interface SearchGroupMemberRepository : JpaRepository<SearchGroupMember, UUID> {
    /** 자연키 조회. 재가입은 이 행을 찾아 status 를 전이시킨다(새 INSERT 금지). */
    fun findByGroupIdAndUserId(
        groupId: UUID,
        userId: UUID,
    ): SearchGroupMember?

    fun findByIdAndGroupId(
        id: UUID,
        groupId: UUID,
    ): SearchGroupMember?

    fun findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID): List<SearchGroupMember>

    fun findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(
        groupId: UUID,
        status: SearchGroupMemberStatus,
    ): List<SearchGroupMember>

    fun findAllByUserIdAndStatus(
        userId: UUID,
        status: SearchGroupMemberStatus,
    ): List<SearchGroupMember>

    fun countByGroupIdAndStatus(
        groupId: UUID,
        status: SearchGroupMemberStatus,
    ): Long

    /**
     * 조건부 상태 전이(승인 거절·탈퇴·내보내기·요청 접수).
     * joinedAt 은 건드리지 않는다 — ACTIVE 로 올리는 전이는 activate() 를 쓴다.
     * 영향 행 0 = 이미 다른 상태이거나 다른 그룹의 멤버십 id 다 → 호출부가 재조회해 멱등/409 를 가른다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupMember m
           SET m.status = :next,
               m.decidedAt = :occurredAt,
               m.decidedBy = :decidedBy,
               m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.groupId = :groupId AND m.status = :expected
        """,
    )
    fun transition(
        @Param("membershipId") membershipId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupMemberStatus,
        @Param("next") next: SearchGroupMemberStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /** PENDING 승인과 LEFT/REMOVED/REJECTED 재가입에 공통으로 쓴다. 같은 행을 되살린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupMember m
           SET m.status = com.park.animal.searchgroup.entity.SearchGroupMemberStatus.ACTIVE,
               m.joinedAt = :occurredAt,
               m.decidedAt = :occurredAt,
               m.decidedBy = :decidedBy,
               m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.groupId = :groupId AND m.status = :expected
        """,
    )
    fun activate(
        @Param("membershipId") membershipId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupMemberStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int
}
```

**3-13. `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupUserBlockRepository.kt`**

```kotlin
package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.SearchGroupUserBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 보호자 차단 리포지토리. Task 7 이 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 금지. 차단 해제는 deactivate()(unblockedAt 채우기)이고, 재차단은
 *    reactivate()(unblockedAt 을 NULL 로 되돌리기)다 — UNIQUE(group_id,user_id) 때문에 새 행은 불가.
 * 2. reason 은 최초 차단 시 INSERT 로만 기록한다. reactivate 는 reason 을 건드리지 않아
 *    최초 차단 사유가 감사값으로 보존된다. reason 은 보호자 전용 GET /blocks 에서만 노출한다.
 * 3. 조회/전이 모두 부모 groupId 를 동반한다(설계 16.1). 비관적 락은 쓰지 않는다(F23).
 */
interface SearchGroupUserBlockRepository : JpaRepository<SearchGroupUserBlock, UUID> {
    fun findByGroupIdAndUserId(
        groupId: UUID,
        userId: UUID,
    ): SearchGroupUserBlock?

    fun findAllByGroupIdAndUnblockedAtIsNullOrderByBlockedAtDescIdDesc(groupId: UUID): List<SearchGroupUserBlock>

    fun countByGroupIdAndUserIdAndUnblockedAtIsNull(
        groupId: UUID,
        userId: UUID,
    ): Long

    /** 해제 상태(unblockedAt IS NOT NULL)인 기존 행을 다시 차단으로 되돌린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupUserBlock b
           SET b.blockedBy = :blockedBy,
               b.blockedAt = :occurredAt,
               b.unblockedAt = null,
               b.updatedAt = :occurredAt
         WHERE b.id = :blockId AND b.groupId = :groupId AND b.unblockedAt IS NOT NULL
        """,
    )
    fun reactivate(
        @Param("blockId") blockId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("blockedBy") blockedBy: UUID,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /** 차단 해제. 이미 해제된 행이면 영향 행 0 → 멱등 성공으로 해석한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupUserBlock b
           SET b.unblockedAt = :occurredAt, b.updatedAt = :occurredAt
         WHERE b.id = :blockId AND b.groupId = :groupId AND b.unblockedAt IS NULL
        """,
    )
    fun deactivate(
        @Param("blockId") blockId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int
}
```

**3-14. `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupTeamRepository.kt`**

```kotlin
package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 지원 연결 리포지토리. Task 8(팀 보관)·Task 9(지원 lifecycle)·Task 10(허브)이 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 금지 — 지원 종료도 status 전이다.
 * 2. child 조회는 부모 groupId 동반(다른 그룹의 supportId 로 접근하는 IDOR 차단, 설계 16.1).
 *    예외는 findAllByTeamIdAndStatus 하나로, 팀 보관 시 그 팀의 ACTIVE 지원 연결을 일괄
 *    WITHDRAWN 으로 회수하기 위한 팀 스코프 조회다(권한은 팀장 판정으로 이미 걸러진다).
 * 3. 동시 수락 경합은 조건부 UPDATE 의 영향 행 수 0/1 로 판정한다 — 이 레포에는 비관적 락
 *    선례가 없고 도입하지 않는다(F23).
 */
interface SearchGroupTeamRepository : JpaRepository<SearchGroupTeam, UUID> {
    fun findByGroupIdAndTeamId(
        groupId: UUID,
        teamId: UUID,
    ): SearchGroupTeam?

    fun findByIdAndGroupId(
        id: UUID,
        groupId: UUID,
    ): SearchGroupTeam?

    fun findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID): List<SearchGroupTeam>

    fun findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(
        groupId: UUID,
        status: SearchGroupTeamStatus,
    ): List<SearchGroupTeam>

    fun findAllByTeamIdAndStatus(
        teamId: UUID,
        status: SearchGroupTeamStatus,
    ): List<SearchGroupTeam>

    fun countByGroupIdAndStatus(
        groupId: UUID,
        status: SearchGroupTeamStatus,
    ): Long

    /** 거절·철회·해제 전이. activatedAt 은 건드리지 않는다 — 활성화는 activate() 를 쓴다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupTeam t
           SET t.status = :next,
               t.decidedBy = :decidedBy,
               t.decidedAt = :occurredAt,
               t.updatedAt = :occurredAt
         WHERE t.id = :supportId AND t.groupId = :groupId AND t.status = :expected
        """,
    )
    fun transition(
        @Param("supportId") supportId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupTeamStatus,
        @Param("next") next: SearchGroupTeamStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /** 양쪽 대기 상태(PENDING_GROUP_APPROVAL / PENDING_TEAM_APPROVAL)와 재지원이 공유하는 활성화. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupTeam t
           SET t.status = com.park.animal.searchgroup.entity.SearchGroupTeamStatus.ACTIVE,
               t.decidedBy = :decidedBy,
               t.decidedAt = :activatedAt,
               t.activatedAt = :activatedAt,
               t.updatedAt = :activatedAt
         WHERE t.id = :supportId AND t.groupId = :groupId AND t.status = :expected
        """,
    )
    fun activate(
        @Param("supportId") supportId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupTeamStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("activatedAt") activatedAt: LocalDateTime,
    ): Int
}
```

**3-15. `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupEventRepository.kt`**

```kotlin
package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.SearchGroupEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * 그룹 감사 이벤트 리포지토리. Task 4 의 `SearchGroupService.listEvents`(GET /search-groups/{groupId}/events)가
 * 유일한 조회 소비자이고, Task 5~9 의 `SearchGroupEventRecorder` 가 유일한 기록 소비자다.
 *
 * 규칙: 삽입과 조회만 한다. delete / deleteById 금지 — 감사 기록은 지우지 않는다(설계 20).
 * 조회는 언제나 groupId 스코프다. 이벤트 id 단독 조회 API 는 만들지 않는다.
 * 이 목록만 size/offset 페이징을 받으므로 Pageable 을 그대로 노출한다.
 */
interface SearchGroupEventRepository : JpaRepository<SearchGroupEvent, UUID> {
    fun findAllByGroupIdOrderByCreatedAtDescIdDesc(
        groupId: UUID,
        pageable: Pageable,
    ): Page<SearchGroupEvent>
}
```

**3-16. `src/main/kotlin/com/park/animal/team/repository/TeamRepository.kt`**

```kotlin
package com.park.animal.team.repository

import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 리포지토리. Task 8(팀·팀 멤버십·보관)·Task 9(지원)·Task 10(허브)이 그대로 쓴다.
 *
 * 규칙: delete / deleteById 금지 — 팀 해체는 archiveIfStatus() 로 status = ARCHIVED 전이다.
 * 공개 팀 목록은 status 로만 필터하고 개인정보를 노출하지 않는다(팀 이름/설명/인원수만).
 */
interface TeamRepository : JpaRepository<Team, UUID> {
    fun findByIdAndDeletedAtIsNull(id: UUID): Team?

    /**
     * 공개 팀 목록. [q] 는 서비스에서 trim 후 빈 문자열로 정규화해 넘긴다.
     * 빈 문자열이면 `LIKE '%%'` 가 되어 전체 활성 팀을 반환하므로 nullable 파라미터 타입 추론 문제를 피한다.
     */
    @Query(
        """
        SELECT t FROM Team t
        WHERE t.deletedAt IS NULL
          AND t.status = :status
          AND t.name LIKE CONCAT('%', :q, '%')
        ORDER BY t.name ASC
        """,
    )
    fun searchByName(
        @Param("q") q: String,
        @Param("status") status: TeamStatus,
        pageable: Pageable,
    ): Page<Team>

    fun findAllByCreatedByAndStatus(
        createdBy: UUID,
        status: TeamStatus,
    ): List<Team>

    /**
     * 팀 보관 조건부 전이(설계 6.4 — 후임 이전 대신 팀을 보관하는 경로).
     * 영향 행 0 = 이미 보관됐다 → 호출부가 멱등 성공으로 처리한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE Team t
           SET t.status = :next, t.updatedAt = :occurredAt
         WHERE t.id = :teamId AND t.status = :expected AND t.deletedAt IS NULL
        """,
    )
    fun archiveIfStatus(
        @Param("teamId") teamId: UUID,
        @Param("expected") expected: TeamStatus,
        @Param("next") next: TeamStatus,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int
}
```

**3-17. `src/main/kotlin/com/park/animal/team/repository/TeamMemberRepository.kt`**

```kotlin
package com.park.animal.team.repository

import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 멤버십 리포지토리. Task 5(팀원 fan-out)·Task 8(팀 멤버십/팀장 이전/보관)·Task 9(팀장 판정)·
 * Task 10(허브)이 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 금지 — 탈퇴·내보내기·재가입 전부 status 전이다(F15).
 * 2. child 조회는 부모 teamId 동반(설계 16.1). findById 는 쓰지 않는다.
 * 3. 팀장 이전은 changeRole() 을 한 트랜잭션 안에서 두 번 호출한다 —
 *    먼저 현 팀장 LEADER→MEMBER, 그 다음 대상 MEMBER→LEADER. 순서를 바꾸거나 단일
 *    CASE WHEN UPDATE 로 합치면 uq_tm_single_active_leader 가 행 단위로 검사돼 1062 가 난다.
 * 4. 탈퇴/내보내기 시에는 role 을 MEMBER 로 되돌린 뒤 status 를 바꾼다 — LEADER/LEFT 조합은
 *    generated key 가 NULL 이라 제약에는 걸리지 않지만 이력이 헷갈린다.
 * 5. 비관적 락을 쓰지 않는다(F23). 두 팀장 후보가 동시에 승격되는 경합은 조건부 UPDATE 와
 *    uq_tm_single_active_leader 가 함께 막는다.
 * 6. findAllByTeamIdAndStatus 는 Task 5 의 GroupNotificationPublisher.notifyTeamMembers 가
 *    수신자(ACTIVE 팀원)를 계산할 때 쓴다 — 정렬이 필요 없어 별도 orderBy 를 두지 않는다.
 */
interface TeamMemberRepository : JpaRepository<TeamMember, UUID> {
    fun findByTeamIdAndUserId(
        teamId: UUID,
        userId: UUID,
    ): TeamMember?

    fun findByIdAndTeamId(
        id: UUID,
        teamId: UUID,
    ): TeamMember?

    /** 활성 팀장. 유일성은 uq_tm_single_active_leader 가 DB 에서 보장하지만, 전이 도중 조회를 견디도록 First 를 쓴다. */
    fun findFirstByTeamIdAndRoleAndStatus(
        teamId: UUID,
        role: TeamRole,
        status: TeamMemberStatus,
    ): TeamMember?

    fun findAllByTeamIdAndStatus(
        teamId: UUID,
        status: TeamMemberStatus,
    ): List<TeamMember>

    fun findAllByTeamIdOrderByCreatedAtDescIdDesc(teamId: UUID): List<TeamMember>

    fun findAllByUserIdAndStatus(
        userId: UUID,
        status: TeamMemberStatus,
    ): List<TeamMember>

    fun countByTeamIdAndStatus(
        teamId: UUID,
        status: TeamMemberStatus,
    ): Long

    /** 거절·탈퇴·내보내기 전이. joinedAt 은 건드리지 않는다 — 활성화는 activate() 를 쓴다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE TeamMember m
           SET m.status = :next,
               m.decidedAt = :occurredAt,
               m.decidedBy = :decidedBy,
               m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.teamId = :teamId AND m.status = :expected
        """,
    )
    fun transition(
        @Param("membershipId") membershipId: UUID,
        @Param("teamId") teamId: UUID,
        @Param("expected") expected: TeamMemberStatus,
        @Param("next") next: TeamMemberStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /** PENDING 승인과 LEFT/REMOVED/REJECTED 재가입에 공통으로 쓴다. 같은 행을 되살린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE TeamMember m
           SET m.status = com.park.animal.team.entity.TeamMemberStatus.ACTIVE,
               m.joinedAt = :occurredAt,
               m.decidedAt = :occurredAt,
               m.decidedBy = :decidedBy,
               m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.teamId = :teamId AND m.status = :expected
        """,
    )
    fun activate(
        @Param("membershipId") membershipId: UUID,
        @Param("teamId") teamId: UUID,
        @Param("expected") expected: TeamMemberStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /**
     * 팀장 이전용 역할 전이. 활성 멤버에게만 적용된다.
     * 강등(LEADER→MEMBER)을 먼저, 승격(MEMBER→LEADER)을 나중에 호출한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE TeamMember m
           SET m.role = :next, m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.teamId = :teamId AND m.role = :expected
           AND m.status = com.park.animal.team.entity.TeamMemberStatus.ACTIVE
        """,
    )
    fun changeRole(
        @Param("membershipId") membershipId: UUID,
        @Param("teamId") teamId: UUID,
        @Param("expected") expected: TeamRole,
        @Param("next") next: TeamRole,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int
}
```

**3-18. `src/main/kotlin/com/park/animal/post/repository/PostRepository.kt`** — 전체 교체가 아니라 **메서드 하나만 삽입**하는 부분 수정이다.

현재 파일 기준 위치: 28~30행이 `interface PostRepository :` / `    JpaRepository<Post, UUID>,` / `    PostQueryRepository {` 이고 31행이 `    @Modifying` 이다. **30행과 31행 사이**(= `interface PostRepository` 본문 맨 앞)에 아래 블록을 그대로 넣는다.

```kotlin
    /**
     * soft-delete 된 글을 걸러낸 조회.
     *
     * Post 는 @SQLDelete 만 있고 @SQLRestriction 이 없어 findById 가 삭제된 글도 그대로 돌려준다(F3).
     * 함께 찾기 경로(그룹 조회·가입·팀 지원)는 삭제된 실종 소식에 절대 붙으면 안 되므로
     * 서비스 코드에서는 findById 대신 이 메서드만 쓴다.
     */
    fun findByIdAndDeletedAtIsNull(id: UUID): Post?
```

import 추가는 없다 — `com.park.animal.post.entity.Post`(11행)와 `java.util.UUID`(26행)가 이미 있다. 파일의 나머지(`updateAuthorName`, `searchByKeyword`, `searchByKeywordFulltext`, `interface PostQueryRepository`, `class PostQueryRepositoryImpl`)는 한 줄도 건드리지 않는다.

---

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchGroupMigrationIT"
```
Expected: PASS — `BUILD SUCCESSFUL`, 5 tests.

이어서 전체 스위트(기존 IT 포함)가 살아 있는지 확인한다.

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test && head -c 300 build/test-results/test/TEST-com.park.animal.searchgroup.SearchGroupMigrationIT.xml
```
Expected: PASS — `BUILD SUCCESSFUL` + `<testsuite name="com.park.animal.searchgroup.SearchGroupMigrationIT" tests="5" skipped="0" failures="0" errors="0"`.

`@DataJpaTest` 가 앱의 모든 리포지토리를 부팅하므로, 이 테스트가 통과했다는 것은 다음도 함께 확인된 것이다.
- Task 1 에서 바꾼 `NotificationRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc` 파생 쿼리가 실제로 해석된다.
- 새로 추가한 파생 쿼리 28개의 프로퍼티 경로가 전부 유효하다(SearchGroup 3 / SearchGroupMember 6 / SearchGroupUserBlock 3 / SearchGroupTeam 6 / SearchGroupEvent 1 / Team 2 / TeamMember 7).
- `@Query` JPQL 11개(조건부 전이 10개 + `TeamRepository.searchByName`)가 부팅 시점에 파싱된다.
- 새 엔티티 7개의 컬럼 매핑이 V12 스키마와 일치한다(불일치 시 Hibernate 가 SELECT 에서 `Unknown column` 으로 죽는다 — F7).

---

- [ ] **Step 5: 배포 전 운영 DB 사전 확인 — 사용자 확인이 필요한 외부 게이트**

**이 스텝은 에이전트가 실행할 수 없다.** 운영 MySQL 접속 정보가 이 작업 환경에 없고 `mysql` 클라이언트도 없다. 에이전트는 아래 SQL 을 사용자에게 그대로 전달하고, **사용자의 회신을 받은 뒤에만** Step 6(커밋)과 이후 배포로 진행한다. 결과를 추정하거나 자동 검증을 흉내내지 않는다.

배경: V12 는 `fk_search_group_post` 로 collation 불일치를 **쿼리 타임이 아니라 마이그레이션 타임에** 터뜨리도록 설계했다. `FlywayConfig` 가 부팅마다 `repair()` → `migrate()` 를 돌기 때문에 실패한 마이그레이션은 이후 모든 배포를 막는다(F21).

사용자에게 요청할 것 (배포 전, 운영 MySQL 세션):
```sql
SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = 'findmypet' AND TABLE_NAME = 'post' AND COLUMN_NAME = 'id';

SELECT COUNT(*) AS backfill_target
  FROM post
 WHERE missing_animal_status = 'SEARCHING' AND deleted_at IS NULL;
```

Expected: 사용자로부터 두 값을 회신받는다 — (1) `post.id` 의 `CHARACTER_SET_NAME`/`COLLATION_NAME`, (2) `backfill_target` 숫자.

판정 기준.
1. `utf8mb4` / `utf8mb4_0900_ai_ci` 면 V12 를 그대로 배포한다.
2. 다른 값(예: `utf8mb4_general_ci`, `utf8mb3_general_ci`)이면 **배포하지 말고 중단**한다. V12 의 `search_group` 정의를 `) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = <운영값>;` 으로 맞춰 수정하고, `MySQLContainer.withCommand` 의 `--collation-server` 도 같은 값으로 바꾼 뒤 Step 4 부터 반복한다.
3. `backfill_target` 숫자를 기록해 둔다. 배포 후 검증의 기준값이다.

사용자에게 요청할 것 (배포 직후, 운영 MySQL 세션):
```sql
SELECT COUNT(*) AS created_groups FROM search_group;

SELECT COUNT(*) AS mismatched
  FROM post p
  LEFT JOIN search_group g ON g.post_id = p.id
 WHERE p.missing_animal_status = 'SEARCHING' AND p.deleted_at IS NULL AND g.id IS NULL;

SELECT COUNT(*) AS wrongly_created
  FROM search_group g
  JOIN post p ON p.id = g.post_id
 WHERE p.missing_animal_status <> 'SEARCHING' OR p.deleted_at IS NOT NULL;
```

Expected: 사용자 회신이 `created_groups` = 배포 전 `backfill_target`, `mismatched` = 0, `wrongly_created` = 0.

`mismatched` 가 0 이 아니면 백필 `INSERT ... NOT EXISTS` 를 그대로 한 번 더 실행하면 된다(멱등이다). `wrongly_created` 가 0 이 아니면 V12 의 WHERE 절이 변형된 것이므로 롤백하지 말고 원인부터 확인한다 — 그룹 행 삭제는 하지 않고 사용자에게 보고한다.

---

- [ ] **Step 6: 커밋**

```bash
cd /Users/park/Desktop/project/animal && git add src/main/resources/db/migration/V12__add_search_group_and_team.sql src/main/kotlin/com/park/animal/searchgroup/entity src/main/kotlin/com/park/animal/searchgroup/repository src/main/kotlin/com/park/animal/team/entity src/main/kotlin/com/park/animal/team/repository src/main/kotlin/com/park/animal/post/repository/PostRepository.kt src/test/kotlin/com/park/animal/searchgroup/SearchGroupMigrationIT.kt && git commit -m "feat(search-group): V12 스키마 + 수색그룹·팀 엔티티/리포지토리

- V12: search_group / search_group_member / search_group_user_block / team /
  team_member / search_group_team / search_group_event + notification 구조화 컬럼 5종.
  타임스탬프는 DATETIME(6) — post 와 조인 정합 + TIMESTAMP 의 세션 timezone 변환 회피.
  fk_search_group_post 로 collation 불일치를 마이그레이션 타임에 터뜨린다.
  삭제되지 않은 SEARCHING 소식에 OPEN/ACTIVE 그룹을 백필(NOT EXISTS 가드로 멱등).
- 엔티티 7종 전부 @SQLDelete 없음. 멤버십/연결의 생명주기는 status 전이로만 표현한다(F15).
  team_member.active_leader_key 는 GENERATED STORED 라 엔티티에 매핑하지 않는다.
- 리포지토리 7종을 최종 시그니처로 한 번에 확정한다. Task 3~11 은 이 파일들을 재정의하지 않는다.
  child 조회는 전부 부모 id 동반(IDOR 방어), 상태 변경은 조건부 UPDATE 만 노출,
  비관적 락은 도입하지 않는다(F23). delete/deleteById 는 쓰지 않는다.
- PostRepository.findByIdAndDeletedAtIsNull 추가 — findById 는 soft-delete 를 거르지 않는다(F3).
- SearchGroupMigrationIT: V1~V12 체인, 백필 대상/비대상, uq_tm_single_active_leader,
  post.id collation 일치, 멤버십 ACTIVE→LEFT→ACTIVE 동일 행 유지."
```

---

**부록 — `SearchGroupMigrationIT` 의 자주 나오는 실패 3종과 판별법**

이 IT 는 실패 모드가 뚜렷하다. 아래 증상이 보이면 원인이 하나로 특정된다.

| 증상 | 원인 | 조치 |
|---|---|---|
| `ERROR 3105 (HY000): The value specified for generated column 'active_leader_key' in table 'team_member' is not allowed` | `TeamMember` 에 `activeLeaderKey` 필드를 매핑했다 | 필드를 완전히 제거한다. `@Transient` 로도 남기지 않는다 |
| `Referencing column 'post_id' and referenced column 'id' in foreign key constraint 'fk_search_group_post' are incompatible` | `post.id` 와 `search_group.post_id` 의 charset/collation 불일치 | 테스트 컨테이너에서 났다면 V12 에 불필요한 `COLLATE` 를 넣은 것이다. 운영에서 나면 Step 5 의 사전 확인 쿼리 결과로 실제 collation 을 확인해 V12 의 `search_group` 정의에 같은 값을 명시한다 |
| `Unknown column 'sgX_0.deleted_at' in 'field list'` | 신규 테이블에 `deleted_at` 이 빠졌다(F7, V10 과 동일 사고) | V12 의 해당 `CREATE TABLE` 에 `deleted_at DATETIME(6) DEFAULT NULL` 추가 |

`SearchGroupMigrationIT` 가 `Order(1)` 부터 전부 `Failed to load ApplicationContext` 로 죽으면 V12 자체가 실패한 것이다. 컨테이너 로그가 아니라 Flyway 예외 메시지를 먼저 읽는다.

---

### Task 3: 수색그룹 접근 판정 (SearchGroupAccessResolver)

**Files:**
- Create: `src/main/kotlin/com/park/animal/searchgroup/access/GroupAccess.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupAccessQueryRepository.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/access/SearchGroupAccessResolver.kt`
- Test: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupAccessResolverIT.kt`

**Interfaces:**

- Consumes (Task 1 산출물 — `common/http/error/ErrorCode.kt`):
  - `ErrorCode.NOT_FOUND_SEARCH_GROUP` (404), `ErrorCode.SEARCH_GROUP_ACCESS_DENIED` (403), `ErrorCode.SEARCH_ALREADY_ENDED` (410)
- Consumes (Task 2 산출물):
  - `V12__add_search_group_and_team.sql` 의 테이블 `search_group`, `search_group_member`, `search_group_user_block`, `search_group_team`, `team`, `team_member`, `search_group_event` (계약 §6 DDL 그대로)
  - `com.park.animal.searchgroup.entity.JoinPolicy { OPEN, APPROVAL_REQUIRED }`
  - `com.park.animal.searchgroup.entity.SearchGroupStatus { ACTIVE, ARCHIVED }`
  - `com.park.animal.searchgroup.entity.SearchGroupMemberStatus { PENDING, ACTIVE, REJECTED, LEFT, REMOVED }`
  - `com.park.animal.searchgroup.entity.SearchGroupTeamStatus { PENDING_GROUP_APPROVAL, PENDING_TEAM_APPROVAL, ACTIVE, DECLINED, WITHDRAWN, REMOVED }`
  - `com.park.animal.team.entity.TeamRole { LEADER, MEMBER }`, `com.park.animal.team.entity.TeamMemberStatus { PENDING, ACTIVE, REJECTED, LEFT, REMOVED }`
  - `class SearchGroupMember(groupId: UUID, userId: UUID, userName: String?, status: SearchGroupMemberStatus, joinedAt: LocalDateTime?, requestedAt: LocalDateTime?) : BaseEntity()` — `@SQLDelete` 없음(계약 §1-6)
  - `interface SearchGroupMemberRepository : JpaRepository<SearchGroupMember, UUID>`
- Consumes (기존 코드): `com.park.animal.post.entity.MissingAnimalStatus`, `com.park.animal.common.config.JpaConfig`, `com.park.animal.common.http.error.exception.BusinessException`, `io.micrometer.core.instrument.MeterRegistry` (선례: `search/SearchController.kt:34,172` 의 `fmp.search.fulltext.rescue`)
- Produces (Task 4·6·7·9·10 이 의존):
  - `com.park.animal.searchgroup.access.GroupRole { OWNER, PARTICIPANT, NONE }`
  - `com.park.animal.searchgroup.access.AccessSource { OWNER, DIRECT, TEAM }`
  - `com.park.animal.searchgroup.access.GroupAccess` — 계약 §7 필드 14개 + 파생 프로퍼티 6개(`isOwner`, `visible`, `canRead`, `canWrite`, `canManage`, `canJoin`)
  - `com.park.animal.searchgroup.repository.SearchGroupAccessRow` — 매핑 전용 data class
  - `@Repository class SearchGroupAccessQueryRepository(NamedParameterJdbcTemplate)`
    - `fun findAccessRow(groupId: UUID, userId: UUID?): SearchGroupAccessRow?`
    - `fun findAccessRowByPostId(postId: UUID, userId: UUID?): SearchGroupAccessRow?`
    - `fun findAccessibleGroupIds(userId: UUID): List<UUID>`
    - `fun findEffectiveMemberIds(groupId: UUID): List<UUID>`
  - `@Component class SearchGroupAccessResolver(SearchGroupAccessQueryRepository, MeterRegistry)`
    - `fun resolve(groupId: UUID, userId: UUID?): GroupAccess?`
    - `fun resolveByPostId(postId: UUID, userId: UUID?): GroupAccess?`
    - `fun requireVisible(groupId: UUID, userId: UUID): GroupAccess`
    - `fun requireRead(groupId: UUID, userId: UUID): GroupAccess`
    - `fun requireWrite(groupId: UUID, userId: UUID): GroupAccess`
    - `fun requireOwner(groupId: UUID, userId: UUID): GroupAccess`
    - `fun effectiveMemberIds(groupId: UUID): Set<UUID>`
    - `fun accessibleGroupIds(userId: UUID): List<UUID>`
  - 메트릭 `fmp.searchgroup.access.denied` (counter) — tag 는 `reason` 하나뿐이며 값은 `not_found` / `forbidden` / `archived`. **groupId·userId 같은 식별자를 label 에 넣지 않는다**(설계 §20 "메시지 본문과 상세 좌표는 메트릭 label 이나 로그에 넣지 않는다" — 고카디널리티 label 도 같은 이유로 금지).
  - **하위 태스크 주의**: `SearchGroupAccessResolver` 를 `@Import` 하는 모든 `@DataJpaTest` 는 `MeterRegistry` 빈을 함께 공급해야 한다(`@DataJpaTest` 는 actuator auto-configuration 을 포함하지 않는다). Task 4·6·7·9·10·11 의 IT 는 `SimpleMeterRegistry` 를 내는 `@TestConfiguration` 을 import 한다.

> 계약 §7 은 접근 판정 쿼리 3종(`findAccessRow` / `findAccessRowByPostId` / `findAccessibleGroupIds`)을 고정한다. `effectiveMemberIds` 는 알림 fan-out 수신자 계산용이며 같은 native 리포지토리에 `findEffectiveMemberIds` 로 4번째 쿼리를 둔다 — 설계 §9 "한 번만 받는다" 의 중복 제거가 이 한 쿼리에서 끝나야 하기 때문이다.

---

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupAccessResolverIT.kt` 를 새로 만든다.

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.access.AccessSource
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@DataJpaTest` 는 actuator auto-configuration 을 포함하지 않으므로 MeterRegistry 를 직접 공급한다.
 * SimpleMeterRegistry 는 in-memory 라 테스트에서 카운터 값을 그대로 읽을 수 있다.
 */
@TestConfiguration
class AccessMetricsTestConfig {
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * 수색그룹 접근 판정 고정 테스트 — 설계 §6.6 Effective Membership / §7 권한 규칙 / §20 관측.
 *
 * 시드는 전부 JdbcTemplate 로 직접 INSERT 한다. 판정 로직이 native SQL 이므로
 * 엔티티 생성자 형태가 아니라 "테이블에 어떤 행이 있는가" 만이 입력이기 때문이다.
 * 커밋된 행만 native 쿼리에 보이므로 테스트 트랜잭션 래핑을 끈다(NOT_SUPPORTED).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    AccessMetricsTestConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
)
@Testcontainers
class SearchGroupAccessResolverIT {
    @Autowired lateinit var resolver: SearchGroupAccessResolver

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var entityManager: EntityManager

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var meterRegistry: MeterRegistry

    private val owner: UUID = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        listOf(
            "search_group_event",
            "search_group_team",
            "search_group_member",
            "search_group_user_block",
            "team_member",
            "team",
            "search_group",
            "post_image",
            "post",
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    @Test
    fun `보호자는 OWNER 이고 관리 권한을 가진다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)

        val access = resolver.resolve(groupId, owner)!!

        assertEquals(GroupRole.OWNER, access.role)
        assertEquals(setOf(AccessSource.OWNER), access.sources)
        assertEquals(owner, access.ownerUserId)
        assertTrue(access.isOwner)
        assertTrue(access.canRead)
        assertTrue(access.canWrite)
        assertTrue(access.canManage)
        assertFalse(access.canJoin, "이미 보호자이므로 참여 CTA 를 노출하지 않는다")
    }

    @Test
    fun `ACTIVE 직접 멤버는 PARTICIPANT 이고 DIRECT 출처를 가진다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val user = UUID.randomUUID()
        val membershipId = insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)

        val access = resolver.resolve(groupId, user)!!

        assertEquals(GroupRole.PARTICIPANT, access.role)
        assertEquals(setOf(AccessSource.DIRECT), access.sources)
        assertEquals(membershipId, access.directMembershipId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, access.directMembershipStatus)
        assertEquals(0, access.teamCount)
        assertTrue(access.canRead)
        assertTrue(access.canWrite)
        assertFalse(access.canManage)
        assertFalse(access.canJoin)
    }

    @Test
    fun `PENDING 직접 멤버는 아직 참여자가 아니다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId, joinPolicy = JoinPolicy.APPROVAL_REQUIRED)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.PENDING)

        val access = resolver.resolve(groupId, user)!!

        assertEquals(GroupRole.NONE, access.role)
        assertTrue(access.sources.isEmpty())
        assertEquals(SearchGroupMemberStatus.PENDING, access.directMembershipStatus)
        assertFalse(access.canRead)
        assertTrue(access.canJoin, "설계 §8.3 — 대기 사용자는 다시 참여를 누를 수 있어야 한다")
    }

    @Test
    fun `ACTIVE 팀 지원의 ACTIVE 팀원은 TEAM 출처로 참여자가 된다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val teamId = insertTeam(UUID.randomUUID())
        val member = UUID.randomUUID()
        insertTeamMember(teamId, member, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.ACTIVE, owner)

        val access = resolver.resolve(groupId, member)!!

        assertEquals(GroupRole.PARTICIPANT, access.role)
        assertEquals(setOf(AccessSource.TEAM), access.sources)
        assertEquals(1, access.teamCount)
        assertNull(access.directMembershipId)
        assertTrue(access.canWrite)
    }

    @Test
    fun `팀 탈퇴 직후 파생 권한이 사라진다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val teamId = insertTeam(UUID.randomUUID())
        val member = UUID.randomUUID()
        insertTeamMember(teamId, member, TeamRole.MEMBER, TeamMemberStatus.LEFT)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.ACTIVE, owner)

        val access = resolver.resolve(groupId, member)!!

        assertEquals(GroupRole.NONE, access.role)
        assertEquals(0, access.teamCount)
        assertFalse(access.canRead)
    }

    @Test
    fun `팀 지원 종료 직후 파생 권한이 사라진다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val teamId = insertTeam(UUID.randomUUID())
        val member = UUID.randomUUID()
        insertTeamMember(teamId, member, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.WITHDRAWN, owner)

        val access = resolver.resolve(groupId, member)!!

        assertEquals(GroupRole.NONE, access.role)
        assertEquals(0, access.teamCount)
        assertFalse(access.canRead)
    }

    @Test
    fun `차단된 팀원은 차단과 비참여를 구분할 수 없는 403 을 받는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val teamId = insertTeam(UUID.randomUUID())
        val member = UUID.randomUUID()
        insertTeamMember(teamId, member, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.ACTIVE, owner)
        insertBlock(groupId, member, owner)

        val access = resolver.resolve(groupId, member)!!
        assertTrue(access.blocked)
        assertEquals(GroupRole.PARTICIPANT, access.role, "차단은 role 이 아니라 접근 게이트로 표현한다")
        assertFalse(access.canRead)
        assertFalse(access.canJoin)

        val e = assertFailsWith<BusinessException> { resolver.requireRead(groupId, member) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    @Test
    fun `차단 해제된 사용자는 다시 참여자로 복귀한다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)
        insertBlock(groupId, user, owner, unblocked = true)

        val access = resolver.resolve(groupId, user)!!

        assertFalse(access.blocked)
        assertTrue(access.canRead)
    }

    @Test
    fun `soft-delete 된 post 의 그룹은 보이지 않는다 - 404`() {
        val postId = insertPost(owner, deleted = true)
        val groupId = insertGroup(postId)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)

        val access = resolver.resolve(groupId, user)!!
        assertTrue(access.postDeleted)
        assertFalse(access.visible)
        assertFalse(access.canRead)

        val e = assertFailsWith<BusinessException> { resolver.requireVisible(groupId, user) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, e.errorCode)
    }

    @Test
    fun `ARCHIVED 그룹은 읽기만 되고 쓰기는 410`() {
        val postId = insertPost(owner, status = MissingAnimalStatus.FOUND)
        val groupId = insertGroup(postId, status = SearchGroupStatus.ARCHIVED)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)

        val access = resolver.resolve(groupId, user)!!
        assertTrue(access.canRead)
        assertFalse(access.canWrite)
        assertFalse(access.canJoin)

        resolver.requireRead(groupId, user)
        val write = assertFailsWith<BusinessException> { resolver.requireWrite(groupId, user) }
        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, write.errorCode)

        val manage = assertFailsWith<BusinessException> { resolver.requireOwner(groupId, owner) }
        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, manage.errorCode)
    }

    @Test
    fun `보호자가 아닌 사용자의 requireOwner 는 403`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)

        val e = assertFailsWith<BusinessException> { resolver.requireOwner(groupId, user) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    @Test
    fun `비로그인 조회는 역할 없이 그룹 요약만 얻는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.ACTIVE)

        val access = resolver.resolveByPostId(postId, null)!!

        assertEquals(groupId, access.groupId)
        assertEquals(postId, access.postId)
        assertNull(access.viewerId)
        assertEquals(GroupRole.NONE, access.role)
        assertTrue(access.sources.isEmpty())
        assertNull(access.directMembershipId)
        assertEquals(0, access.teamCount)
        assertFalse(access.blocked, "zero-UUID 바인딩이 다른 사용자의 차단 행을 잡아오면 안 된다")
        assertTrue(access.canJoin, "공개 CTA 는 로그인 유도를 위해 참여 가능으로 노출한다")
    }

    @Test
    fun `존재하지 않는 그룹은 null 이고 requireVisible 은 404`() {
        val missing = UUID.randomUUID()

        assertNull(resolver.resolve(missing, owner))
        val e = assertFailsWith<BusinessException> { resolver.requireVisible(missing, owner) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, e.errorCode)
    }

    @Test
    fun `접근 거절은 reason 태그만 가진 카운터로 집계된다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val stranger = UUID.randomUUID()

        val archivedPostId = insertPost(owner, status = MissingAnimalStatus.FOUND)
        val archivedGroupId = insertGroup(archivedPostId, status = SearchGroupStatus.ARCHIVED)

        // 컨텍스트가 클래스 단위로 재사용되고 테스트 순서가 보장되지 않으므로 절대값이 아니라 증분을 본다.
        val beforeNotFound = deniedCount("not_found")
        val beforeForbidden = deniedCount("forbidden")
        val beforeArchived = deniedCount("archived")

        assertFailsWith<BusinessException> { resolver.requireVisible(UUID.randomUUID(), owner) }
        assertFailsWith<BusinessException> { resolver.requireRead(groupId, stranger) }
        assertFailsWith<BusinessException> { resolver.requireOwner(archivedGroupId, owner) }

        assertEquals(1.0, deniedCount("not_found") - beforeNotFound)
        assertEquals(1.0, deniedCount("forbidden") - beforeForbidden)
        assertEquals(1.0, deniedCount("archived") - beforeArchived)

        val tagKeys =
            meterRegistry
                .find("fmp.searchgroup.access.denied")
                .counters()
                .flatMap { counter -> counter.id.tags.map { it.key } }
                .toSet()
        assertEquals(setOf("reason"), tagKeys, "설계 §20 — id 값을 메트릭 label 에 넣지 않는다")
    }

    @Test
    fun `effectiveMemberIds 는 보호자와 직접 팀 경로를 중복 없이 합치고 차단을 뺀다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)

        val direct = UUID.randomUUID()
        val both = UUID.randomUUID() // 직접 참여 + 팀 경유 동시
        val teamOnly = UUID.randomUUID()
        val blocked = UUID.randomUUID()
        val pending = UUID.randomUUID()
        val leftTeamMember = UUID.randomUUID()

        insertMember(groupId, direct, SearchGroupMemberStatus.ACTIVE)
        insertMember(groupId, both, SearchGroupMemberStatus.ACTIVE)
        insertMember(groupId, pending, SearchGroupMemberStatus.PENDING)

        val teamId = insertTeam(UUID.randomUUID())
        insertTeamMember(teamId, both, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamMember(teamId, teamOnly, TeamRole.LEADER, TeamMemberStatus.ACTIVE)
        insertTeamMember(teamId, blocked, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamMember(teamId, leftTeamMember, TeamRole.MEMBER, TeamMemberStatus.LEFT)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.ACTIVE, owner)
        insertBlock(groupId, blocked, owner)

        val ids = resolver.effectiveMemberIds(groupId)

        assertEquals(setOf(owner, direct, both, teamOnly), ids)
    }

    @Test
    fun `accessibleGroupIds 는 볼 수 있는 그룹만 돌려주고 삭제된 글은 제외한다`() {
        val user = UUID.randomUUID()

        val minePostId = insertPost(user)
        val mineGroupId = insertGroup(minePostId)

        val joinedPostId = insertPost(owner)
        val joinedGroupId = insertGroup(joinedPostId)
        insertMember(joinedGroupId, user, SearchGroupMemberStatus.ACTIVE)

        val deletedPostId = insertPost(owner, deleted = true)
        val deletedGroupId = insertGroup(deletedPostId)
        insertMember(deletedGroupId, user, SearchGroupMemberStatus.ACTIVE)

        val blockedPostId = insertPost(owner)
        val blockedGroupId = insertGroup(blockedPostId)
        insertMember(blockedGroupId, user, SearchGroupMemberStatus.ACTIVE)
        insertBlock(blockedGroupId, user, owner)

        val strangerPostId = insertPost(owner)
        insertGroup(strangerPostId)

        val ids = resolver.accessibleGroupIds(user).toSet()

        assertEquals(setOf(mineGroupId, joinedGroupId), ids)
    }

    @Test
    fun `native 접근 판정은 Hibernate auto-flush 를 트리거하지 않는다 - resolve 먼저 mutate 나중`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val joiner = UUID.randomUUID()

        TransactionTemplate(transactionManager).execute {
            searchGroupMemberRepository.save(
                SearchGroupMember(
                    groupId = groupId,
                    userId = joiner,
                    userName = null,
                    status = SearchGroupMemberStatus.ACTIVE,
                    joinedAt = LocalDateTime.now(),
                    requestedAt = null,
                ),
            )

            val beforeFlush = resolver.resolve(groupId, joiner)!!
            assertEquals(
                GroupRole.NONE,
                beforeFlush.role,
                "flush 전 native 쿼리는 새 멤버십을 보지 못한다 — 서비스는 resolve 를 먼저 하고 mutate 를 나중에 해야 한다",
            )

            entityManager.flush()

            val afterFlush = resolver.resolve(groupId, joiner)!!
            assertEquals(GroupRole.PARTICIPANT, afterFlush.role, "flush 후에는 같은 커넥션에서 보인다")
        }
    }

    // --- 메트릭 helper ---

    private fun deniedCount(reason: String): Double =
        meterRegistry
            .find("fmp.searchgroup.access.denied")
            .tag("reason", reason)
            .counter()
            ?.count()
            ?: 0.0

    // --- 시드 helper (전부 커밋된 행) ---

    private fun insertPost(
        authorId: UUID,
        status: MissingAnimalStatus = MissingAnimalStatus.SEARCHING,
        deleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO post (id, author_id, author_name, title, phone_num, time, place, gender, gratuity,
                              description, lat, lng, open_chat_url, missing_animal_status, animal_type, breed_id,
                              created_at, updated_at, deleted_at)
            VALUES (?, ?, '보호자', '테스트 실종 소식', '010-0000-0000', NOW(6), '서울 강남구', '남아', 0,
                    '설명', 37.5, 127.0, NULL, ?, 'DOG', NULL, NOW(6), NOW(6), ?)
            """.trimIndent(),
            id.toString(),
            authorId.toString(),
            status.name,
            if (deleted) Timestamp.valueOf(LocalDateTime.now()) else null,
        )
        return id
    }

    private fun insertGroup(
        postId: UUID,
        joinPolicy: JoinPolicy = JoinPolicy.OPEN,
        status: SearchGroupStatus = SearchGroupStatus.ACTIVE,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            postId.toString(),
            joinPolicy.name,
            status.name,
        )
        return id
    }

    private fun insertMember(
        groupId: UUID,
        userId: UUID,
        status: SearchGroupMemberStatus,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_member (id, group_id, user_id, user_name, status, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            userId.toString(),
            status.name,
        )
        return id
    }

    private fun insertTeam(createdBy: UUID): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO team (id, name, description, status, created_by, created_at, updated_at)
            VALUES (?, '테스트 팀', NULL, 'ACTIVE', ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            createdBy.toString(),
        )
        return id
    }

    private fun insertTeamMember(
        teamId: UUID,
        userId: UUID,
        role: TeamRole,
        status: TeamMemberStatus,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO team_member (id, team_id, user_id, user_name, role, status, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            teamId.toString(),
            userId.toString(),
            role.name,
            status.name,
        )
        return id
    }

    private fun insertTeamSupport(
        groupId: UUID,
        teamId: UUID,
        status: SearchGroupTeamStatus,
        requestedBy: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_team (id, group_id, team_id, status, requested_by, requested_at,
                                           created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            teamId.toString(),
            status.name,
            requestedBy.toString(),
        )
        return id
    }

    private fun insertBlock(
        groupId: UUID,
        userId: UUID,
        blockedBy: UUID,
        unblocked: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_user_block (id, group_id, user_id, blocked_by, reason, blocked_at,
                                                 unblocked_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, NULL, NOW(6), ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            userId.toString(),
            blockedBy.toString(),
            if (unblocked) Timestamp.valueOf(LocalDateTime.now()) else null,
        )
        return id
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            // 운영 체인의 V2 는 빈 파일이라 fresh DB 에서는 테스트 전용 V2.1 베이스라인이 필요하다.
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchGroupAccessResolverIT"
```

Expected: FAIL — Kotlin 컴파일 에러. `e: .../SearchGroupAccessResolverIT.kt: Unresolved reference: access` (`com.park.animal.searchgroup.access` 패키지 없음), `Unresolved reference: SearchGroupAccessQueryRepository`, `Unresolved reference: SearchGroupAccessResolver`, `Unresolved reference: GroupRole`, `Unresolved reference: AccessSource`.

- [ ] **Step 3: 최소 구현**

**3-1. `src/main/kotlin/com/park/animal/searchgroup/access/GroupAccess.kt`** (신규)

```kotlin
package com.park.animal.searchgroup.access

import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import java.util.UUID

/**
 * 수색그룹에서의 역할. 설계 §2 에 따라 제품 역할에 `admin` 을 두지 않는다.
 * 서비스 전체 관리자 권한(`Passport.role`)은 이 판정에 절대 개입하지 않는다.
 */
enum class GroupRole {
    /** 실종 소식 보호자 (`Post.authorId` 에서 파생) */
    OWNER,

    /** 직접 참여 또는 팀 지원으로 권한을 얻은 사용자 */
    PARTICIPANT,

    /** 권한 없음 */
    NONE,
}

/** 권한이 어디서 왔는지. 한 사용자가 여러 출처를 동시에 가질 수 있다(설계 §6.6). */
enum class AccessSource {
    OWNER,
    DIRECT,
    TEAM,
}

/**
 * 한 번의 조회로 확정된 "이 사용자가 이 수색그룹에 대해 무엇을 할 수 있는가".
 *
 * 프런트에 그대로 노출하지 않는다 — [blocked] 는 응답 DTO 에 담지 않으며(계약 §9),
 * 차단 사용자는 아카이브·권한없음과 구분 불가한 응답을 받아야 한다.
 */
data class GroupAccess(
    val groupId: UUID,
    val postId: UUID,
    val ownerUserId: UUID,
    val viewerId: UUID?,
    val groupStatus: SearchGroupStatus,
    val joinPolicy: JoinPolicy,
    val postDeleted: Boolean,
    val postStatus: MissingAnimalStatus,
    val role: GroupRole,
    val sources: Set<AccessSource>,
    val teamCount: Int,
    val directMembershipId: UUID?,
    val directMembershipStatus: SearchGroupMemberStatus?,
    val blocked: Boolean,
) {
    val isOwner: Boolean get() = role == GroupRole.OWNER

    /** soft-delete 된 실종 소식의 그룹은 존재하지 않는 것으로 취급한다(설계 §14.2). */
    val visible: Boolean get() = !postDeleted

    val canRead: Boolean get() = visible && !blocked && role != GroupRole.NONE

    val canWrite: Boolean get() = canRead && groupStatus == SearchGroupStatus.ACTIVE

    val canManage: Boolean get() = isOwner && visible && groupStatus == SearchGroupStatus.ACTIVE

    val canJoin: Boolean
        get() =
            visible && !blocked && groupStatus == SearchGroupStatus.ACTIVE &&
                postStatus == MissingAnimalStatus.SEARCHING && role == GroupRole.NONE
}
```

**3-2. `src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupAccessQueryRepository.kt`** (신규)

```kotlin
package com.park.animal.searchgroup.repository

import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** 접근 판정 한 줄. 도메인 규칙은 담지 않고 DB 사실만 담는다. */
data class SearchGroupAccessRow(
    val groupId: UUID,
    val postId: UUID,
    val ownerUserId: UUID,
    val groupStatus: SearchGroupStatus,
    val joinPolicy: JoinPolicy,
    val postDeleted: Boolean,
    val postStatus: MissingAnimalStatus,
    val directMembershipId: UUID?,
    val directMembershipStatus: SearchGroupMemberStatus?,
    val teamCount: Int,
    val blocked: Boolean,
)

/**
 * 수색그룹 접근 판정 전용 native 쿼리 (설계 §6.6, §12.3).
 *
 * 설계 원칙:
 * 1. 모든 UUID 파라미터는 `.toString()` 으로 바인딩한다. 컬럼이 `VARCHAR(36)` 이라
 *    `java.util.UUID` 를 그대로 넘기면 드라이버가 바이너리로 바인딩해 항상 0건이 된다.
 * 2. 비로그인 조회는 [ANONYMOUS_USER_ID](zero-UUID)를 바인딩한다. 어떤 실제 사용자와도
 *    매칭되지 않으므로 LEFT JOIN 과 상관 서브쿼리가 자연히 비고, SQL 을 한 벌만 유지할 수 있다.
 * 3. `GROUP_CONCAT` 을 쓰지 않는다. `group_concat_max_len` 을 넘기면 경고 없이 잘려서
 *    "권한이 있는데 없다고 판정" 되는 사일런트 버그가 된다. 팀 경로는 `COUNT(*)` 로만 센다.
 *    "어느 팀을 통해 들어왔는가" 가 필요하면 팀 지원 목록 API(계약 §8 #17)에서 행 단위로 조회한다.
 *
 * **중요 — auto-flush 없음**: 이 리포지토리는 JDBC native SQL 이라 Hibernate 의 auto-flush 가
 * 걸리지 않는다. 같은 트랜잭션에서 아직 flush 되지 않은 엔티티 변경은 보이지 않는다.
 * 따라서 서비스 계약은 **"resolve 먼저, mutate 나중"** 이다. 순서를 바꿀 수밖에 없다면
 * 호출 전에 `entityManager.flush()` 로 강제 반영한 뒤 재조회한다.
 */
@Repository
class SearchGroupAccessQueryRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    companion object {
        /** 비로그인 뷰어 자리표시자. 실제 사용자 id 와 절대 충돌하지 않는다. */
        private val ANONYMOUS_USER_ID: UUID = UUID(0L, 0L)

        private const val ACCESS_SELECT = """
            SELECT g.id                    AS group_id,
                   g.post_id               AS post_id,
                   g.join_policy           AS join_policy,
                   g.status                AS group_status,
                   p.author_id             AS owner_user_id,
                   p.missing_animal_status AS post_status,
                   p.deleted_at            AS post_deleted_at,
                   dm.id                   AS direct_membership_id,
                   dm.status               AS direct_membership_status,
                   (SELECT COUNT(*)
                      FROM search_group_team sgt
                      JOIN team_member tm ON tm.team_id = sgt.team_id
                     WHERE sgt.group_id = g.id
                       AND sgt.status = 'ACTIVE'
                       AND tm.user_id = :userId
                       AND tm.status = 'ACTIVE')  AS team_count,
                   (SELECT COUNT(*)
                      FROM search_group_user_block b
                     WHERE b.group_id = g.id
                       AND b.user_id = :userId
                       AND b.unblocked_at IS NULL) AS block_count
              FROM search_group g
              JOIN post p ON p.id = g.post_id
              LEFT JOIN search_group_member dm
                     ON dm.group_id = g.id
                    AND dm.user_id = :userId
        """

        private const val ACCESS_BY_GROUP_SQL = ACCESS_SELECT + " WHERE g.id = :groupId"

        private const val ACCESS_BY_POST_SQL = ACCESS_SELECT + " WHERE g.post_id = :postId"

        /**
         * 유효 참여자(설계 §6.6): 보호자 ∪ ACTIVE 직접멤버 ∪ ACTIVE 팀지원의 ACTIVE 팀원 − 활성차단.
         * `UNION` 이 중복을 제거하므로 여러 경로로 들어온 사용자도 한 번만 나온다(설계 §9 "한 번만").
         * 보호자는 차단 anti-join 의 예외다(계약 §9 — 보호자 자신은 차단 대상이 될 수 없다).
         * 실종 소식의 삭제 여부는 여기서 거르지 않는다 — 멤버십 계산이지 노출 판정이 아니다.
         */
        private const val EFFECTIVE_MEMBER_SQL = """
            SELECT c.user_id AS user_id
              FROM (
                    SELECT p.author_id AS user_id
                      FROM search_group g
                      JOIN post p ON p.id = g.post_id
                     WHERE g.id = :groupId
                    UNION
                    SELECT m.user_id
                      FROM search_group_member m
                     WHERE m.group_id = :groupId
                       AND m.status = 'ACTIVE'
                    UNION
                    SELECT tm.user_id
                      FROM search_group_team sgt
                      JOIN team_member tm ON tm.team_id = sgt.team_id
                     WHERE sgt.group_id = :groupId
                       AND sgt.status = 'ACTIVE'
                       AND tm.status = 'ACTIVE'
                   ) c
              JOIN search_group g2 ON g2.id = :groupId
              JOIN post p2 ON p2.id = g2.post_id
             WHERE (
                    c.user_id = p2.author_id
                 OR NOT EXISTS (SELECT 1
                                  FROM search_group_user_block b
                                 WHERE b.group_id = :groupId
                                   AND b.user_id = c.user_id
                                   AND b.unblocked_at IS NULL)
                   )
        """

        /**
         * 사용자가 볼 수 있는 그룹 id. ACTIVE/ARCHIVED 를 모두 포함한다 —
         * 상태 필터링은 허브·지도 각 호출부의 몫이다.
         * `search_group.id` 는 백필에서 MySQL `UUID()`(v1) 로 생성돼 시간순이 아니므로 절대 id 로 정렬하지 않는다.
         */
        private const val ACCESSIBLE_GROUP_SQL = """
            SELECT g.id AS group_id
              FROM search_group g
              JOIN post p ON p.id = g.post_id
             WHERE p.deleted_at IS NULL
               AND (
                    p.author_id = :userId
                 OR EXISTS (SELECT 1
                              FROM search_group_member m
                             WHERE m.group_id = g.id
                               AND m.user_id = :userId
                               AND m.status = 'ACTIVE')
                 OR EXISTS (SELECT 1
                              FROM search_group_team sgt
                              JOIN team_member tm ON tm.team_id = sgt.team_id
                             WHERE sgt.group_id = g.id
                               AND sgt.status = 'ACTIVE'
                               AND tm.user_id = :userId
                               AND tm.status = 'ACTIVE')
                   )
               AND (
                    p.author_id = :userId
                 OR NOT EXISTS (SELECT 1
                                  FROM search_group_user_block b
                                 WHERE b.group_id = g.id
                                   AND b.user_id = :userId
                                   AND b.unblocked_at IS NULL)
                   )
             ORDER BY g.created_at DESC
        """
    }

    fun findAccessRow(
        groupId: UUID,
        userId: UUID?,
    ): SearchGroupAccessRow? {
        val params =
            MapSqlParameterSource()
                .addValue("groupId", groupId.toString())
                .addValue("userId", viewerParam(userId))
        return jdbcTemplate.query(ACCESS_BY_GROUP_SQL, params) { rs, _ -> mapAccessRow(rs) }.firstOrNull()
    }

    fun findAccessRowByPostId(
        postId: UUID,
        userId: UUID?,
    ): SearchGroupAccessRow? {
        val params =
            MapSqlParameterSource()
                .addValue("postId", postId.toString())
                .addValue("userId", viewerParam(userId))
        return jdbcTemplate.query(ACCESS_BY_POST_SQL, params) { rs, _ -> mapAccessRow(rs) }.firstOrNull()
    }

    fun findEffectiveMemberIds(groupId: UUID): List<UUID> {
        val params = MapSqlParameterSource().addValue("groupId", groupId.toString())
        return jdbcTemplate.query(EFFECTIVE_MEMBER_SQL, params) { rs, _ ->
            UUID.fromString(rs.getString("user_id"))
        }
    }

    fun findAccessibleGroupIds(userId: UUID): List<UUID> {
        val params = MapSqlParameterSource().addValue("userId", userId.toString())
        return jdbcTemplate.query(ACCESSIBLE_GROUP_SQL, params) { rs, _ ->
            UUID.fromString(rs.getString("group_id"))
        }
    }

    private fun viewerParam(userId: UUID?): String = (userId ?: ANONYMOUS_USER_ID).toString()

    private fun mapAccessRow(rs: ResultSet): SearchGroupAccessRow =
        SearchGroupAccessRow(
            groupId = UUID.fromString(rs.getString("group_id")),
            postId = UUID.fromString(rs.getString("post_id")),
            ownerUserId = UUID.fromString(rs.getString("owner_user_id")),
            groupStatus = SearchGroupStatus.valueOf(rs.getString("group_status")),
            joinPolicy = JoinPolicy.valueOf(rs.getString("join_policy")),
            postDeleted = rs.getTimestamp("post_deleted_at") != null,
            postStatus = MissingAnimalStatus.valueOf(rs.getString("post_status")),
            directMembershipId = rs.getString("direct_membership_id")?.let(UUID::fromString),
            directMembershipStatus = rs.getString("direct_membership_status")?.let(SearchGroupMemberStatus::valueOf),
            teamCount = rs.getInt("team_count"),
            blocked = rs.getInt("block_count") > 0,
        )
}
```

**3-3. `src/main/kotlin/com/park/animal/searchgroup/access/SearchGroupAccessResolver.kt`** (신규)

```kotlin
package com.park.animal.searchgroup.access

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupAccessRow
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 수색그룹 접근 판정 단일 진입점 (설계 §7, §16.1, §20).
 *
 * 판정 입력은 `userId: UUID` 뿐이다. `Passport.role` 을 절대 보지 않는다 —
 * 서비스 전체 관리자 권한과 그룹 권한을 섞으면 설계 §2 의 "admin 은 제품 역할이 아니다" 가 깨진다.
 *
 * 예외 순서는 정보 누출을 막기 위해 고정한다.
 * 1. 행 없음 / `postDeleted` → 404 [ErrorCode.NOT_FOUND_SEARCH_GROUP] (`reason=not_found`)
 * 2. 차단됨 또는 역할 없음 → 403 [ErrorCode.SEARCH_GROUP_ACCESS_DENIED] (`reason=forbidden`)
 *    (차단과 비참여가 **같은 코드**여야 한다. 다르면 차단 사실이 응답으로 새어나간다 — 계약 §9)
 * 3. 쓰기 경로에서 그룹이 ARCHIVED → 410 [ErrorCode.SEARCH_ALREADY_ENDED] (`reason=archived`)
 *
 * **관측(설계 §20)**: 거절할 때마다 [ACCESS_DENIED_METRIC] 카운터를 증가시킨다.
 * tag 는 `reason` 하나뿐이다. groupId·userId 를 label 에 넣으면 카디널리티가 사용자 수만큼
 * 폭발하고 설계 §20 의 "식별 정보를 메트릭 label 에 넣지 않는다" 를 위반한다.
 * 어떤 그룹에서 났는지는 `search_group_event` 감사 기록과 애플리케이션 로그로 추적한다.
 * 선례는 `SearchController` 의 `fmp.search.fulltext.rescue` 이다.
 *
 * **호출 순서 계약 — resolve 먼저, mutate 나중.**
 * 하위 리포지토리는 native SQL 이라 Hibernate auto-flush 대상이 아니다. 같은 트랜잭션에서
 * 아직 flush 되지 않은 엔티티 변경(신규 멤버십, 상태 전이 등)은 이 판정에 보이지 않는다.
 * 판정 결과가 필요한 값(예: 알림 수신자 집합)은 **반드시 상태를 바꾸기 전에** 계산한다.
 * 불가피하게 순서가 뒤바뀌면 호출부에서 `entityManager.flush()` 후 재조회한다.
 * 이 규칙은 `SearchGroupAccessResolverIT` 의 "native 접근 판정은 Hibernate auto-flush 를
 * 트리거하지 않는다" 테스트로 고정돼 있다.
 */
@Component
class SearchGroupAccessResolver(
    private val accessQueryRepository: SearchGroupAccessQueryRepository,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        const val ACCESS_DENIED_METRIC = "fmp.searchgroup.access.denied"
        private const val REASON_TAG = "reason"
        private const val REASON_NOT_FOUND = "not_found"
        private const val REASON_FORBIDDEN = "forbidden"
        private const val REASON_ARCHIVED = "archived"
    }

    fun resolve(
        groupId: UUID,
        userId: UUID?,
    ): GroupAccess? = accessQueryRepository.findAccessRow(groupId, userId)?.let { toAccess(it, userId) }

    fun resolveByPostId(
        postId: UUID,
        userId: UUID?,
    ): GroupAccess? = accessQueryRepository.findAccessRowByPostId(postId, userId)?.let { toAccess(it, userId) }

    /** 존재하고 볼 수 있는가. 아니면 404. */
    fun requireVisible(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = resolve(groupId, userId) ?: denyNotFound()
        if (!access.visible) denyNotFound()
        return access
    }

    /** 지도·활동·멤버 조회 권한. 차단·비참여 모두 같은 403. */
    fun requireRead(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = requireVisible(groupId, userId)
        if (!access.canRead) denyForbidden()
        return access
    }

    /** 그룹에 무언가를 남기는 경로. 종료된 수색은 410 으로 읽기 전용임을 알린다. */
    fun requireWrite(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = requireRead(groupId, userId)
        if (access.groupStatus != SearchGroupStatus.ACTIVE) denyArchived()
        return access
    }

    /** 보호자 전용 관리 경로(정책 변경·승인·차단·팀 지원 처리·수색 종료). */
    fun requireOwner(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = requireVisible(groupId, userId)
        if (!access.isOwner) denyForbidden()
        if (access.groupStatus != SearchGroupStatus.ACTIVE) denyArchived()
        return access
    }

    /**
     * 알림 fan-out 수신자 (설계 §6.6, §9).
     * 중복 제거는 여기서 끝난다 — 호출부는 이 집합을 그대로 쓰고 다시 합치지 않는다.
     */
    fun effectiveMemberIds(groupId: UUID): Set<UUID> = accessQueryRepository.findEffectiveMemberIds(groupId).toSet()

    /** 마이페이지 허브·통합 지도의 1단계 좁히기. 상태 필터는 호출부가 적용한다. */
    fun accessibleGroupIds(userId: UUID): List<UUID> = accessQueryRepository.findAccessibleGroupIds(userId)

    private fun denyNotFound(): Nothing {
        countDenied(REASON_NOT_FOUND)
        throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
    }

    private fun denyForbidden(): Nothing {
        countDenied(REASON_FORBIDDEN)
        throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
    }

    private fun denyArchived(): Nothing {
        countDenied(REASON_ARCHIVED)
        throw BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
    }

    private fun countDenied(reason: String) {
        meterRegistry.counter(ACCESS_DENIED_METRIC, REASON_TAG, reason).increment()
    }

    private fun toAccess(
        row: SearchGroupAccessRow,
        viewerId: UUID?,
    ): GroupAccess {
        val isOwner = viewerId != null && viewerId == row.ownerUserId
        val sources = linkedSetOf<AccessSource>()
        if (isOwner) sources += AccessSource.OWNER
        if (row.directMembershipStatus == SearchGroupMemberStatus.ACTIVE) sources += AccessSource.DIRECT
        if (row.teamCount > 0) sources += AccessSource.TEAM

        val role =
            when {
                isOwner -> GroupRole.OWNER
                sources.isNotEmpty() -> GroupRole.PARTICIPANT
                else -> GroupRole.NONE
            }

        return GroupAccess(
            groupId = row.groupId,
            postId = row.postId,
            ownerUserId = row.ownerUserId,
            viewerId = viewerId,
            groupStatus = row.groupStatus,
            joinPolicy = row.joinPolicy,
            postDeleted = row.postDeleted,
            postStatus = row.postStatus,
            role = role,
            sources = sources,
            teamCount = row.teamCount,
            directMembershipId = row.directMembershipId,
            directMembershipStatus = row.directMembershipStatus,
            // 보호자는 차단 대상이 될 수 없다(계약 §9). 과거 데이터로 행이 남아 있어도 무시한다.
            blocked = row.blocked && !isOwner,
        )
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run:

```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchGroupAccessResolverIT"
```

Expected: PASS — 17개 테스트 전부 통과. 그중 `접근 거절은 reason 태그만 가진 카운터로 집계된다` 가 `fmp.searchgroup.access.denied` 의 tag 집합이 정확히 `{reason}` 임을 확인한다.

- [ ] **Step 5: 커밋**

```bash
cd /Users/park/Desktop/project/animal && git add src/main/kotlin/com/park/animal/searchgroup/access/GroupAccess.kt \
        src/main/kotlin/com/park/animal/searchgroup/access/SearchGroupAccessResolver.kt \
        src/main/kotlin/com/park/animal/searchgroup/repository/SearchGroupAccessQueryRepository.kt \
        src/test/kotlin/com/park/animal/searchgroup/SearchGroupAccessResolverIT.kt \
&& git commit -m "feat(search-group): 수색그룹 접근 판정(GroupAccess/SearchGroupAccessResolver) 추가

- 보호자/직접ACTIVE/팀경유 합집합에서 활성 차단을 뺀 유효 참여자 판정 (설계 6.6)
- 차단과 비참여를 같은 403 으로 응답해 차단 사실 누출 차단 (계약 9)
- GROUP_CONCAT 미사용, 비로그인은 zero-UUID 바인딩으로 SQL 한 벌 유지
- 권한 거절을 fmp.searchgroup.access.denied(reason 태그만) 로 집계 (설계 20)
- native SQL 은 auto-flush 대상이 아님을 KDoc + IT 로 고정 (resolve 먼저, mutate 나중)"
```

---

### Task 4: 수색 생명주기 통합 + 그룹 조회/종료 API + PostService 리팩터

설계 §14.1 "상태를 바꿀 수 있는 모든 경로를 하나의 수색 생명주기 서비스로 모은다" 가 이 태스크의 절반이다. `PUT /post` 와 `PATCH /post/renewal-status` 두 경로가 각자 `missingAnimalStatus` 를 직접 바꾸던 것(F5)을 `SearchLifecycleService` 한 곳으로 모은다.

나머지 절반은 그 생명주기에 붙는 **HTTP 진입점**이다. 계약 §8 엔드포인트 #2(공개 CTA)·#3(그룹 상세)·#5(수색 종료)·#6(활동 기록)을 `SearchGroupService` / `SearchGroupController` 로 만든다. 공개 CTA 가 없으면 설계 §11 카카오톡 공유 링크 방문자 흐름에 서버 계약이 없고, `POST /end` 가 없으면 설계 §3.12/§22.7 "보호자의 수색 종료" 가 API 로 성립하지 않는다.

**Files:**
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchLifecycleService.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupEventRecorder.kt`
- Create: `src/main/kotlin/com/park/animal/post/PostWriteService.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupDtos.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupService.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupController.kt`
- Modify: `src/main/kotlin/com/park/animal/post/PostService.kt:1-302` (전체 교체)
- Modify: `src/main/kotlin/com/park/animal/post/PostController.kt:106-161` (`registerPost` 에 `joinPolicy` 파라미터 추가)
- Modify: `src/main/kotlin/com/park/animal/post/dto/RegisterPostCommand.kt:9-27` (`joinPolicy` 필드 추가)
- Test: `src/test/kotlin/com/park/animal/searchgroup/SearchLifecycleIT.kt`
- Test: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupCtaIT.kt`

**리포지토리는 이 태스크에서 만들지 않는다.** `SearchGroupRepository`, `SearchGroupMemberRepository`, `SearchGroupTeamRepository`, `SearchGroupEventRepository`, `PostRepository.findByIdAndDeletedAtIsNull` 은 전부 **Task 2 가 최종 시그니처로 이미 정의**했다. 여기서는 그대로 호출만 한다(재선언하면 중복 선언 컴파일 에러가 난다).

**Interfaces:**

- Consumes (Task 1 산출물):
  - `ErrorCode.NOT_FOUND_SEARCH_GROUP`, `ErrorCode.NOT_FOUND_POST`, `ErrorCode.SEARCH_GROUP_ACCESS_DENIED`, `ErrorCode.SEARCH_ALREADY_ENDED`
  - `NotificationType.SEARCH_ENDED`, `NotificationType.BOOKMARK_STATUS_CHANGED` — 계약 §5 는 phase 1 신규 상수 **전량**을 한 번에 넣도록 요구한다(롤링 배포 중 구버전 replica 가 모르는 문자열을 읽으면 알림 목록 전체가 500). 따라서 최초 태스크인 Task 1 에서 §5 블록 전체가 `NotificationType` 에 들어가 있다.
- Consumes (Task 2 산출물 — 이 시그니처 그대로여야 컴파일된다):
  - `com.park.animal.searchgroup.entity.JoinPolicy`, `SearchGroupStatus`, `ArchivedReason { FOUND, POST_DELETED }`, `SearchGroupEventType`, `SearchGroupMemberStatus`, `SearchGroupTeamStatus`
  - `class SearchGroup(postId: UUID, joinPolicy: JoinPolicy = JoinPolicy.OPEN) : BaseEntity()` — `var status: SearchGroupStatus`, `var archivedReason: ArchivedReason?`, `var archivedAt: LocalDateTime?`, `var archivedBy: UUID?`
  - `class SearchGroupEvent(groupId: UUID, type: SearchGroupEventType, actorId: UUID?, targetId: UUID?, detail: String?) : BaseEntity()`
  - `SearchGroupRepository.findByIdAndDeletedAtIsNull(id: UUID): SearchGroup?`
  - `SearchGroupRepository.findByPostIdAndDeletedAtIsNull(postId: UUID): SearchGroup?`
  - `SearchGroupRepository.archiveIfStatus(groupId: UUID, expected: SearchGroupStatus, next: SearchGroupStatus, reason: ArchivedReason, archivedAt: LocalDateTime, archivedBy: UUID): Int` — 조건부 전이. `@Modifying(flushAutomatically = true, clearAutomatically = true)`
  - `SearchGroupMemberRepository.countByGroupIdAndStatus(groupId: UUID, status: SearchGroupMemberStatus): Long`
  - `SearchGroupTeamRepository.countByGroupIdAndStatus(groupId: UUID, status: SearchGroupTeamStatus): Long`
  - `SearchGroupEventRepository : JpaRepository<SearchGroupEvent, UUID>` + `findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID, pageable: Pageable): Page<SearchGroupEvent>`
  - `PostRepository.findByIdAndDeletedAtIsNull(id: UUID): Post?`
- Consumes (Task 3 산출물):
  - `SearchGroupAccessResolver.resolveByPostId(postId: UUID, userId: UUID?): GroupAccess?`
  - `SearchGroupAccessResolver.requireRead(groupId: UUID, userId: UUID): GroupAccess`
  - `SearchGroupAccessResolver.requireOwner(groupId: UUID, userId: UUID): GroupAccess`
  - `SearchGroupAccessResolver.effectiveMemberIds(groupId: UUID): Set<UUID>`
  - `SearchGroupAccessQueryRepository`
  - `GroupAccess`, `GroupRole`, `AccessSource`
  - **주의**: `SearchGroupAccessResolver` 는 `MeterRegistry` 를 주입받는다. 이 태스크의 두 IT 는 `SimpleMeterRegistry` 를 내는 `@TestConfiguration` 을 함께 import 해야 컨텍스트가 뜬다(`@DataJpaTest` 는 actuator auto-configuration 을 포함하지 않는다).
- Produces (Task 5·6·7·9·10·11 이 의존):
  - `@Service class SearchGroupEventRecorder(SearchGroupEventRepository)`
    - `fun record(groupId: UUID, type: SearchGroupEventType, actorId: UUID?, targetId: UUID?, detail: String?): SearchGroupEvent` — `@Transactional(propagation = MANDATORY)`
  - `@Service class SearchLifecycleService(...)`
    - `fun openGroupForPost(post: Post, joinPolicy: JoinPolicy): SearchGroup?`
    - `fun applyStatusTransition(post: Post, actorUserId: UUID, next: MissingAnimalStatus)`
    - `fun endSearch(groupId: UUID, actorUserId: UUID): SearchGroup`
    - `fun archiveOnPostDeleted(post: Post, actorUserId: UUID)`
  - `@Service class PostWriteService(...)`
    - `@Transactional fun createPostWithSearchGroup(command: RegisterPostCommand, joinPolicy: JoinPolicy): Post` — **non-suspend**
  - `@Service class SearchGroupService(...)`
    - `fun getCta(postId: UUID, viewerId: UUID?): SearchGroupCtaResponse`
    - `fun getDetail(groupId: UUID, viewerId: UUID): SearchGroupDetailResponse`
    - `fun endSearch(groupId: UUID, actorUserId: UUID): SearchGroupDetailResponse`
    - `fun listEvents(groupId: UUID, viewerId: UUID, size: Int, offset: Int): List<SearchGroupEventResponse>`
    - **`updateJoinPolicy` 는 여기서 만들지 않는다 — Task 6 이 이 클래스에 추가한다.**
  - `@RestController class SearchGroupController(private val searchGroupService: SearchGroupService)` — 계약 §8 #2·#3·#5·#6.
    - **생성자 파라미터는 지금 하나뿐이다. Task 6 이 두 번째 파라미터 `SearchGroupMembershipService` 를 추가하고 `PATCH /search-groups/{groupId}/join-policy`(#4) 핸들러를 얹는다.** Task 6 은 이 클래스를 전체 교체하지 않고 생성자 한 줄 + 핸들러 한 개만 덧붙인다.
  - `com.park.animal.searchgroup.dto` 의 `SearchGroupCtaResponse`, `SearchGroupDetailResponse`, `SearchGroupEventResponse`, `SearchGroupViewerAction { JOIN_NOW, REQUEST_JOIN, ALREADY_JOINED, LOGIN_REQUIRED, UNAVAILABLE }`
  - `RegisterPostCommand.joinPolicy: JoinPolicy`
- Produces → **Task 5 가 반드시 교체할 것 (R2)**:
  - 이 태스크의 `SearchLifecycleService.endSearch` 는 아직 레거시 `notificationService.createMany(...)` 로 fan-out 한다. 그러면 V12 가 추가한 `notification.group_id` / `post_id` / `actor_user_id` 가 **전부 NULL** 로 남는다.
  - `PostService.notifyBookmarkers` 의 즐겨찾기 fan-out 도 같은 이유로 `post_id` 가 NULL 이다.
  - **Task 5 는 자기 태스크 말미에** ① `SearchLifecycleService.endSearch` 의 fan-out 을 `groupNotificationPublisher.notifyGroup(groupId, postId, NotificationType.SEARCH_ENDED, excluding = setOf(actorUserId), actorUserId = actorUserId, body = null)` 로, ② 즐겨찾기 fan-out 을 `notificationService.createStructuredMany(..., postId = post.id)` 로 교체한다.
  - 이 태스크가 `endSearch` 안에 하드코딩한 제목 `"수색이 종료됐어요"` 와 본문 문자열은 **Task 5 의 `GroupNotificationTemplates` 문구 표(`titleOf`/`bodyOf`/`linkOf`)로 대체되어 사라진다.** 문구의 단일 출처는 Task 5 의 표 하나뿐이다. Task 4 단계에서는 문구를 여기 두되, Task 5 이후 이 문자열이 코드에 남아 있으면 안 된다.
  - Task 11 의 `SearchGroupConcurrencyIT`(`WHERE group_id = ? AND type='SEARCH_ENDED'`)와 `SearchGroupPrivacyIT` 는 이 교체가 끝난 뒤에야 통과한다.

---

- [ ] **Step 1: 생명주기 실패 테스트 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchLifecycleIT.kt` 를 새로 만든다.

```kotlin
package com.park.animal.searchgroup

import com.park.animal.bookmark.BookmarkService
import com.park.animal.bookmark.repository.PostBookmarkRepository
import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.common.http.error.exception.ImageUploadException
import com.park.animal.multimedia.MultimediaService
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.notification.repository.NotificationRepository
import com.park.animal.post.PostService
import com.park.animal.post.PostWriteService
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.dto.UpdatePostRequest
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.repository.PostNearbyRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.ArchivedReason
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupEventRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 테스트 전용 대체 빈.
 *
 * - `MultimediaService` 는 외부 StorageClient(MinIO) 에 붙으므로 mock 으로 대체한다.
 * - `MeterRegistry` 는 `SearchGroupAccessResolver` 가 주입받는데 `@DataJpaTest` 는
 *   actuator auto-configuration 을 포함하지 않으므로 in-memory 레지스트리를 직접 공급한다.
 *
 * `@DataJpaTest` 는 `@Service` 를 컴포넌트 스캔하지 않으므로 실제 빈과 충돌하지 않는다.
 */
@TestConfiguration
class SearchLifecycleTestBeans {
    @Bean
    fun multimediaService(): MultimediaService = mock()

    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * 수색 생명주기 통합 검증 (설계 §14.1, §14.2).
 *
 * 트랜잭션 경계 자체가 검증 대상이므로 테스트를 트랜잭션으로 감싸지 않는다(NOT_SUPPORTED).
 * 감싸면 "이미지 업로드 실패 시 post 가 롤백된다" 를 절대 재현할 수 없다.
 * 컨트롤러는 서비스로의 얇은 위임이므로 서비스 진입점을 직접 구동한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchLifecycleTestBeans::class,
    PostNearbyRepository::class,
    NotificationService::class,
    BookmarkService::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    SearchLifecycleService::class,
    PostWriteService::class,
    PostService::class,
)
@Testcontainers
class SearchLifecycleIT {
    @Autowired lateinit var postService: PostService

    @Autowired lateinit var lifecycleService: SearchLifecycleService

    @Autowired lateinit var multimediaService: MultimediaService

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupEventRepository: SearchGroupEventRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var postBookmarkRepository: PostBookmarkRepository

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val owner: UUID = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        listOf(
            "search_group_event",
            "search_group_team",
            "search_group_member",
            "search_group_user_block",
            "team_member",
            "team",
            "search_group",
            "notification",
            "post_bookmark",
            "post_image",
            "post",
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
        stubUploadSuccess()
    }

    // (a) 등록 시 그룹 생성 규칙

    @Test
    fun `SEARCHING 실종 소식을 등록하면 수색그룹이 정확히 하나 생긴다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        assertEquals(1L, searchGroupRepository.count())
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ACTIVE, group.status)
        assertEquals(JoinPolicy.OPEN, group.joinPolicy)
        assertNull(group.archivedReason)
        assertEquals(1L, searchGroupEventRepository.count())
        assertEquals(SearchGroupEventType.GROUP_OPENED, searchGroupEventRepository.findAll().first().type)
    }

    @Test
    fun `APPROVAL_REQUIRED 로 등록하면 그 정책 그대로 그룹이 생긴다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.APPROVAL_REQUIRED)

        assertEquals(
            JoinPolicy.APPROVAL_REQUIRED,
            searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.joinPolicy,
        )
    }

    @Test
    fun `SEEN 목격 소식에는 수색그룹을 만들지 않는다`() {
        registerPost(MissingAnimalStatus.SEEN, JoinPolicy.OPEN)

        assertEquals(0L, searchGroupRepository.count())
        assertEquals(0L, searchGroupEventRepository.count())
    }

    @Test
    fun `SEEN 으로 등록한 글이 SEARCHING 이 되면 그때 OPEN 그룹이 생긴다`() {
        val postId = registerPost(MissingAnimalStatus.SEEN, JoinPolicy.OPEN)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.SEARCHING)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(JoinPolicy.OPEN, group.joinPolicy)
        assertEquals(SearchGroupStatus.ACTIVE, group.status)
    }

    // (b) PATCH /post/renewal-status

    @Test
    fun `상태 변경 API 로 FOUND 가 되면 그룹이 ARCHIVED FOUND 로 보관된다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ARCHIVED, group.status)
        assertEquals(ArchivedReason.FOUND, group.archivedReason)
        assertEquals(owner, group.archivedBy)
        assertNotNull(group.archivedAt)
        assertEquals(
            MissingAnimalStatus.FOUND,
            postRepository.findByIdAndDeletedAtIsNull(postId)!!.missingAnimalStatus,
        )
    }

    @Test
    fun `SEARCHING 에서 SEEN 으로 바뀌어도 그룹은 ACTIVE 로 유지된다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.SEEN)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ACTIVE, group.status)
        assertNull(group.archivedReason)
    }

    @Test
    fun `종료된 수색을 다시 SEARCHING 으로 되돌리려 하면 410`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)

        val e =
            assertFailsWith<BusinessException> {
                postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.SEARCHING)
            }

        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, e.errorCode)
        assertEquals(
            SearchGroupStatus.ARCHIVED,
            searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.status,
        )
    }

    // (c) PUT /post 로도 우회 불가

    @Test
    fun `게시글 수정 API 로 FOUND 를 보내도 그룹이 ARCHIVED 된다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        postService.updatePost(command = updateRequest(postId, MissingAnimalStatus.FOUND), userId = owner)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ARCHIVED, group.status)
        assertEquals(ArchivedReason.FOUND, group.archivedReason)
        val post = postRepository.findByIdAndDeletedAtIsNull(postId)!!
        assertEquals(MissingAnimalStatus.FOUND, post.missingAnimalStatus)
        assertEquals("수정된 제목", post.title, "일반 필드 수정도 같은 트랜잭션에서 반영돼야 한다")
    }

    // (d) DELETE /post

    @Test
    fun `실종 소식을 삭제하면 그룹이 POST_DELETED 로 보관된다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        postService.deletePost(postId = postId, userId = owner)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ARCHIVED, group.status)
        assertEquals(ArchivedReason.POST_DELETED, group.archivedReason)
        assertNull(postRepository.findByIdAndDeletedAtIsNull(postId), "post 는 soft-delete 된다")
        assertTrue(
            searchGroupEventRepository.findAll().any { it.type == SearchGroupEventType.GROUP_ARCHIVED_BY_POST_DELETE },
        )
    }

    @Test
    fun `soft-delete 된 실종 소식은 더 이상 상태를 바꿀 수 없다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        postService.deletePost(postId = postId, userId = owner)

        val e =
            assertFailsWith<BusinessException> {
                postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)
            }

        assertEquals(ErrorCode.NOT_FOUND_POST, e.errorCode)
    }

    // (e) 멱등성

    @Test
    fun `수색 종료를 두 번 호출해도 감사와 알림은 각각 한 건이다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        val groupId = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.id
        val participant = UUID.randomUUID()
        joinAsActiveMember(groupId, participant)

        lifecycleService.endSearch(groupId, owner)
        val group = lifecycleService.endSearch(groupId, owner)

        assertEquals(SearchGroupStatus.ARCHIVED, group.status, "두 번째 호출도 예외 없이 현재 상태를 돌려준다")
        assertEquals(1, searchGroupEventRepository.findAll().count { it.type == SearchGroupEventType.SEARCH_ENDED })
        assertEquals(1, notificationRepository.findAll().count { it.userId == participant })
    }

    // (f) 트랜잭션 경계 회귀 방지

    @Test
    fun `이미지 업로드가 실패하면 post 도 수색그룹도 남지 않는다`() {
        stubUploadFailure()

        assertFailsWith<ImageUploadException> {
            runBlocking {
                postService.registerPost(
                    registerCommand(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN, withImage = true),
                )
            }
        }

        assertEquals(0L, postRepository.count(), "suspend 함수에 @Transactional 을 붙이면 여기서 post 가 남는다(F2)")
        assertEquals(0L, searchGroupRepository.count())
        assertEquals(0L, searchGroupEventRepository.count())
    }

    // (g) 알림 중복 제거

    @Test
    fun `즐겨찾기와 참여를 겸한 사용자는 수색 종료 알림을 정확히 한 건만 받는다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        val groupId = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.id
        val both = UUID.randomUUID()
        val bookmarkerOnly = UUID.randomUUID()
        joinAsActiveMember(groupId, both)
        bookmark(both, postId)
        bookmark(bookmarkerOnly, postId)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)

        val forBoth = notificationRepository.findAll().filter { it.userId == both }
        assertEquals(1, forBoth.size, "설계 §9 — 여러 경로로 권한을 가진 사용자는 한 번만 받는다")
        assertEquals(NotificationType.SEARCH_ENDED, forBoth.first().type)

        val forBookmarker = notificationRepository.findAll().filter { it.userId == bookmarkerOnly }
        assertEquals(1, forBookmarker.size)
        assertEquals(
            NotificationType.BOOKMARK_STATUS_CHANGED,
            forBookmarker.first().type,
            "참여자가 아닌 즐겨찾기 사용자는 기존 알림을 그대로 받는다",
        )

        assertEquals(0, notificationRepository.findAll().count { it.userId == owner }, "행위자 본인은 제외")
    }

    // --- helper ---

    private fun stubUploadSuccess() {
        runBlocking {
            whenever(multimediaService.uploadMultipartFiles(any(), any(), any()))
                .thenReturn(listOf("test-bucket/post/${UUID.randomUUID()}"))
        }
    }

    private fun stubUploadFailure() {
        runBlocking {
            whenever(multimediaService.uploadMultipartFiles(any(), any(), any()))
                .thenThrow(ImageUploadException())
        }
    }

    private fun registerCommand(
        status: MissingAnimalStatus,
        joinPolicy: JoinPolicy,
        withImage: Boolean = false,
    ): RegisterPostCommand =
        RegisterPostCommand(
            userId = owner,
            userName = "보호자",
            images =
                if (withImage) {
                    listOf(MockMultipartFile("image", "a.png", "image/png", byteArrayOf(1, 2, 3)))
                } else {
                    emptyList()
                },
            title = "말티즈를 찾습니다",
            phoneNum = "010-0000-0000",
            time = LocalDateTime.now(),
            place = "서울 강남구",
            gender = "남아",
            gratuity = 0,
            description = "겁이 많아요",
            lat = 37.5,
            lng = 127.0,
            openChatUrl = null,
            missingAnimalStatus = status,
            animalType = AnimalType.DOG,
            breedId = null,
            applicationId = "test-app",
            joinPolicy = joinPolicy,
        )

    private fun registerPost(
        status: MissingAnimalStatus,
        joinPolicy: JoinPolicy,
    ): UUID {
        runBlocking { postService.registerPost(registerCommand(status, joinPolicy)) }
        return postRepository.findAll().first { it.deletedAt == null }.id
    }

    private fun updateRequest(
        postId: UUID,
        status: MissingAnimalStatus,
    ): UpdatePostRequest =
        UpdatePostRequest(
            postId = postId,
            title = "수정된 제목",
            phoneNum = "010-0000-0000",
            time = LocalDateTime.now(),
            place = "서울 강남구",
            gender = "남아",
            gratuity = 0,
            description = "겁이 많아요",
            lat = 37.5,
            lng = 127.0,
            openChatUrl = null,
            missingAnimalStatus = status,
            animalType = AnimalType.DOG,
            breedId = null,
        )

    private fun joinAsActiveMember(
        groupId: UUID,
        userId: UUID,
    ) {
        searchGroupMemberRepository.save(
            SearchGroupMember(
                groupId = groupId,
                userId = userId,
                userName = null,
                status = SearchGroupMemberStatus.ACTIVE,
                joinedAt = LocalDateTime.now(),
                requestedAt = null,
            ),
        )
    }

    private fun bookmark(
        userId: UUID,
        postId: UUID,
    ) {
        postBookmarkRepository.save(com.park.animal.bookmark.entity.PostBookmark(userId = userId, postId = postId))
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchLifecycleIT"
```

Expected: FAIL — Kotlin 컴파일 에러. `e: .../SearchLifecycleIT.kt: Unresolved reference: SearchLifecycleService`, `Unresolved reference: SearchGroupEventRecorder`, `Unresolved reference: PostWriteService`, `e: ... No value passed for parameter 'joinPolicy'` (RegisterPostCommand).

- [ ] **Step 3: 생명주기 최소 구현**

**3-1. `src/main/kotlin/com/park/animal/searchgroup/SearchGroupEventRecorder.kt`** (신규)

```kotlin
package com.park.animal.searchgroup

import com.park.animal.searchgroup.entity.SearchGroupEvent
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.repository.SearchGroupEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 수색그룹 감사 기록 (설계 §20).
 *
 * [detail] 에는 상태 전이 요약만 넣는다. 채팅 본문, 상세 좌표, 전화번호, 차단 사유는 절대 넣지 않는다
 * (설계 §16.5). 컬럼이 `VARCHAR(256)` 이므로 저장 전에 잘라 SQL 예외로 상위 트랜잭션을 깨지 않는다.
 *
 * [Propagation.MANDATORY] 로 선언해 감사 기록이 본 작업과 **같은 트랜잭션**에 묶이도록 강제한다.
 * 감사만 남고 상태 전이가 롤백되는(또는 그 반대) 상황을 컴파일이 아니라 런타임에서 즉시 드러낸다.
 */
@Service
class SearchGroupEventRecorder(
    private val searchGroupEventRepository: SearchGroupEventRepository,
) {
    companion object {
        private const val DETAIL_MAX_LENGTH = 256
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        groupId: UUID,
        type: SearchGroupEventType,
        actorId: UUID?,
        targetId: UUID?,
        detail: String?,
    ): SearchGroupEvent =
        searchGroupEventRepository.save(
            SearchGroupEvent(
                groupId = groupId,
                type = type,
                actorId = actorId,
                targetId = targetId,
                detail = detail?.take(DETAIL_MAX_LENGTH),
            ),
        )
}
```

**3-2. `src/main/kotlin/com/park/animal/searchgroup/SearchLifecycleService.kt`** (신규)

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.ArchivedReason
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 수색 생명주기 단일 진입점 (설계 §14.1).
 *
 * 실종 소식의 상태를 바꿀 수 있는 모든 경로 — `POST /post`, `PUT /post`,
 * `PATCH /post/renewal-status`, `DELETE /post/{id}`, `POST /search-groups/{id}/end` — 는
 * 반드시 이 서비스를 통해야 한다. 어떤 API 를 써도 `FOUND` 전환이 그룹 보관을 우회할 수 없다.
 *
 * 전이 규칙(계약 §9):
 * | 대상 상태 | 그룹 없음 | ACTIVE 그룹 | ARCHIVED 그룹 |
 * |---|---|---|---|
 * | SEARCHING | OPEN 그룹 생성(legacy 글 구제) | 유지 | 410 SEARCH_ALREADY_ENDED |
 * | SEEN | 생성하지 않음 | 유지 (archivedReason 에 SEEN 이 없다) | (도달 불가) |
 * | FOUND | post 만 전이 | endSearch | endSearch → 멱등 no-op |
 *
 * **호출 순서 계약**: [SearchGroupAccessResolver] 는 native SQL 이라 auto-flush 되지 않는다.
 * 알림 수신자는 반드시 상태를 바꾸기 **전에** 계산한다.
 *
 * **detach 주의**: [SearchGroupRepository.archiveIfStatus] 는 `clearAutomatically = true` 다.
 * 이 서비스 호출 이후 호출부가 이전에 들고 있던 `Post` 엔티티는 detached 이므로
 * 추가 변경을 하려면 다시 읽어야 한다. 이미 로드된 스칼라 필드 읽기는 안전하다.
 *
 * **TODO(Task 5)**: [endSearch] 의 fan-out 은 아직 레거시 [NotificationService.createMany] 라
 * `notification.group_id` / `post_id` / `actor_user_id` 가 NULL 로 남는다. Task 5 가 이를
 * `GroupNotificationPublisher.notifyGroup(...)` 으로 교체하고, 아래 하드코딩된 제목·본문을
 * `GroupNotificationTemplates` 문구 표로 옮긴다. 그 교체 이후 이 파일에 알림 문구 문자열이 남아 있으면 안 된다.
 */
@Service
class SearchLifecycleService(
    private val searchGroupRepository: SearchGroupRepository,
    private val postRepository: PostRepository,
    private val searchGroupEventRecorder: SearchGroupEventRecorder,
    private val accessResolver: SearchGroupAccessResolver,
    private val notificationService: NotificationService,
) {
    /**
     * 실종 소식에 수색그룹을 연다. `SEARCHING` 이 아니면 그룹을 만들지 않고 null 을 돌려준다(설계 §6.1).
     *
     * 자연키(`post_id` UNIQUE) 선조회 + 없을 때만 삽입으로 멱등성을 얻는다.
     * duplicate-key 를 catch 해서 재조회하면 트랜잭션이 rollback-only 로 오염돼 커밋 시 500 이 된다(F14).
     */
    @Transactional
    fun openGroupForPost(
        post: Post,
        joinPolicy: JoinPolicy,
    ): SearchGroup? {
        if (post.missingAnimalStatus != MissingAnimalStatus.SEARCHING) return null
        searchGroupRepository.findByPostIdAndDeletedAtIsNull(post.id)?.let { return it }

        val saved = searchGroupRepository.save(SearchGroup(postId = post.id, joinPolicy = joinPolicy))
        searchGroupEventRecorder.record(
            groupId = saved.id,
            type = SearchGroupEventType.GROUP_OPENED,
            actorId = post.authorId,
            targetId = null,
            detail = "joinPolicy=${joinPolicy.name}",
        )
        return saved
    }

    /**
     * 실종 소식 상태 전이. 상태 필드를 직접 만지는 유일한 지점이다.
     * 호출부는 `post.missingAnimalStatus` 를 스스로 대입하지 않는다.
     */
    @Transactional
    fun applyStatusTransition(
        post: Post,
        actorUserId: UUID,
        next: MissingAnimalStatus,
    ) {
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(post.id)
        when (next) {
            MissingAnimalStatus.FOUND -> {
                if (group == null) {
                    // 기능 도입 전에 만들어진 글이라 그룹이 없다. 상태만 전이한다.
                    post.updateStatus(MissingAnimalStatus.FOUND)
                    return
                }
                endSearch(group.id, actorUserId)
            }

            MissingAnimalStatus.SEARCHING -> {
                if (group == null) {
                    post.updateStatus(MissingAnimalStatus.SEARCHING)
                    openGroupForPost(post, JoinPolicy.OPEN)
                    return
                }
                if (group.status == SearchGroupStatus.ARCHIVED) {
                    // 설계 §7 — 종료된 수색그룹의 재활성화 API 는 없다. 새 실종 소식을 등록해야 한다.
                    throw BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
                }
                post.updateStatus(MissingAnimalStatus.SEARCHING)
            }

            MissingAnimalStatus.SEEN -> post.updateStatus(MissingAnimalStatus.SEEN)
        }
    }

    /**
     * 보호자의 수색 종료 (설계 §14.1). 한 트랜잭션에서 post→FOUND, group→ARCHIVED(FOUND),
     * 감사 기록, 유효 참여자 알림 fan-out 을 처리한다.
     *
     * 권한 검사는 하지 않는다 — HTTP 진입점인 `SearchGroupService.endSearch` 가
     * `requireOwner` 로 이미 판정했고, `PostService` 경로는 작성자 검사를 먼저 한다.
     *
     * 재시도 안전(설계 §15): 조건부 UPDATE 가 0행이면 이미 종료된 것이므로 410 이 아니라
     * 현재 상태를 그대로 돌려준다. 감사·알림이 두 번 생기지 않는다.
     */
    @Transactional
    fun endSearch(
        groupId: UUID,
        actorUserId: UUID,
    ): SearchGroup {
        val group =
            searchGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        val postId = group.postId

        // 상태를 바꾸기 전에 계산한다 — 접근 판정은 native SQL 이라 auto-flush 되지 않는다.
        val recipients = accessResolver.effectiveMemberIds(groupId)

        val archivedAt = LocalDateTime.now()
        val affected =
            searchGroupRepository.archiveIfStatus(
                groupId = groupId,
                expected = SearchGroupStatus.ACTIVE,
                next = SearchGroupStatus.ARCHIVED,
                reason = ArchivedReason.FOUND,
                archivedAt = archivedAt,
                archivedBy = actorUserId,
            )
        if (affected == 0) {
            return searchGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        }

        // archiveIfStatus 가 1차 캐시를 비웠으므로 다시 읽어 관리 상태로 만든다.
        val post =
            postRepository.findByIdAndDeletedAtIsNull(postId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        post.updateStatus(MissingAnimalStatus.FOUND)

        searchGroupEventRecorder.record(
            groupId = groupId,
            type = SearchGroupEventType.SEARCH_ENDED,
            actorId = actorUserId,
            targetId = null,
            detail = "status=ARCHIVED,reason=FOUND",
        )
        // TODO(Task 5): GroupNotificationPublisher.notifyGroup 으로 교체 — group_id/post_id 를 채운다.
        notificationService.createMany(
            userIds = recipients,
            excludeUserId = actorUserId,
            type = NotificationType.SEARCH_ENDED,
            title = "수색이 종료됐어요",
            body = "'${post.title}' 수색이 종료됐어요. 이전 기록만 확인할 수 있어요.",
            link = "/lost/$postId",
        )

        return searchGroupRepository.findByIdAndDeletedAtIsNull(groupId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
    }

    /**
     * 실종 소식 삭제에 따른 그룹 보관 (설계 §14.2).
     *
     * **반드시 `postRepository.delete(post)` 보다 먼저 호출한다.** `Post` 의 `@SQLDelete` 는
     * 커스텀 UPDATE 라 in-memory `post.deletedAt` 을 채우지 않고(F4), `deletedAt IS NULL` 조회에도
     * 즉시 반영되지 않는다. 순서를 뒤집으면 삭제된 글의 그룹이 ACTIVE 로 남는다.
     *
     * 알림은 보내지 않는다 — 설계 §9 수신자 표에 실종 소식 삭제 항목이 없다.
     */
    @Transactional
    fun archiveOnPostDeleted(
        post: Post,
        actorUserId: UUID,
    ) {
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(post.id) ?: return
        val affected =
            searchGroupRepository.archiveIfStatus(
                groupId = group.id,
                expected = SearchGroupStatus.ACTIVE,
                next = SearchGroupStatus.ARCHIVED,
                reason = ArchivedReason.POST_DELETED,
                archivedAt = LocalDateTime.now(),
                archivedBy = actorUserId,
            )
        if (affected == 0) return

        searchGroupEventRecorder.record(
            groupId = group.id,
            type = SearchGroupEventType.GROUP_ARCHIVED_BY_POST_DELETE,
            actorId = actorUserId,
            targetId = null,
            detail = "status=ARCHIVED,reason=POST_DELETED",
        )
    }
}
```

**3-3. `src/main/kotlin/com/park/animal/post/PostWriteService.kt`** (신규)

```kotlin
package com.park.animal.post

import com.park.animal.multimedia.MultimediaService
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.SearchLifecycleService
import com.park.animal.searchgroup.entity.JoinPolicy
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 실종 소식 등록의 트랜잭션 경계.
 *
 * 이 클래스가 존재하는 이유는 두 가지다.
 *
 * 1. **`suspend fun` 에는 `@Transactional` 이 걸리지 않는다(F2).** 기존
 *    `PostService.registerPost` 는 `@Transactional suspend fun` 이었고 내부에서
 *    `withContext(Dispatchers.IO)` 로 스레드를 갈아탔다. 트랜잭션은 코루틴이 시작되기 전에
 *    끝나 버리므로 실제로는 아무것도 원자적이지 않았다. DB 작업 단위는 non-suspend 빈에 둔다.
 * 2. **이미지 업로드 실패 시 post 가 남던 결함**을 이 구조가 바로잡는다. 업로드가 같은
 *    트랜잭션 안에서 일어나므로 [MultimediaService] 가 던지면 post·post_image·search_group 이
 *    전부 롤백된다. 이 동작은 `SearchLifecycleIT."이미지 업로드가 실패하면 post 도 수색그룹도 남지 않는다"`
 *    로 고정돼 있다.
 */
@Service
class PostWriteService(
    private val postRepository: PostRepository,
    private val postImageRepository: PostImageRepository,
    private val multimediaService: MultimediaService,
    private val searchLifecycleService: SearchLifecycleService,
) {
    /**
     * 실종 소식과 수색그룹을 한 트랜잭션에서 만든다.
     * `SEARCHING` 이 아니면 그룹은 만들지 않는다(설계 §6.1).
     */
    @Transactional
    fun createPostWithSearchGroup(
        command: RegisterPostCommand,
        joinPolicy: JoinPolicy,
    ): Post {
        // search_group 의 fk_search_group_post 가 검증되려면 부모 행이 먼저 DB 에 있어야 한다.
        // id 는 반드시 save 반환값에서 읽는다(F16).
        val post = postRepository.saveAndFlush(Post.createPostFromCommand(command))

        if (command.images.isNotEmpty()) {
            val imageUrls =
                runBlocking {
                    multimediaService.uploadMultipartFiles(
                        command.images,
                        command.userId.toString(),
                        command.applicationId,
                    )
                }
            imageUrls.forEach { url ->
                postImageRepository.save(PostImage(post = post, imageUrl = url))
            }
        }

        searchLifecycleService.openGroupForPost(post, joinPolicy)
        return post
    }
}
```

**3-4. `src/main/kotlin/com/park/animal/post/dto/RegisterPostCommand.kt`** (필드 추가)

```kotlin
package com.park.animal.post.dto

import com.park.animal.breed.entity.AnimalType
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.entity.JoinPolicy
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import java.util.UUID

data class RegisterPostCommand(
    val userId: UUID,
    val userName: String,
    val images: List<MultipartFile>,
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
    val lat: Double,
    val lng: Double,
    val openChatUrl: String?,
    val missingAnimalStatus: MissingAnimalStatus,
    val animalType: AnimalType,
    val breedId: UUID?,
    val applicationId: String,
    /** 직접 참여 정책. 기본값은 설계 §3-6 의 `자유롭게 참여`. */
    val joinPolicy: JoinPolicy = JoinPolicy.OPEN,
)
```

**3-5. `src/main/kotlin/com/park/animal/post/PostService.kt`** (전체 교체)

```kotlin
package com.park.animal.post

import com.park.animal.bookmark.BookmarkService
import com.park.animal.breed.entity.AnimalType
import com.park.animal.breed.repository.BreedRepository
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.common.http.error.exception.ImageUploadException
import com.park.animal.multimedia.MultimediaService
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.post.dto.PostDetailResponse
import com.park.animal.post.dto.PostNearbyResponse
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.dto.SummarizedPostsByPageDto
import com.park.animal.post.dto.SummarizedPostsByPageQuery
import com.park.animal.post.dto.UpdatePostRequest
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostNearbyRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.SearchLifecycleService
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

@Service
class PostService(
    private val postRepository: PostRepository,
    private val multimediaService: MultimediaService,
    private val postImageRepository: PostImageRepository,
    private val breedRepository: BreedRepository,
    private val postNearbyRepository: PostNearbyRepository,
    private val notificationService: NotificationService,
    private val bookmarkService: BookmarkService,
    private val postWriteService: PostWriteService,
    private val searchLifecycleService: SearchLifecycleService,
    private val accessResolver: SearchGroupAccessResolver,
) {
    companion object {
        const val CANCEL_USER_NAME = "탈퇴한 사용자"
    }

    /**
     * 실종 소식 등록. `@Transactional` 은 여기 붙이지 않는다 — `suspend fun` 에는 트랜잭션이
     * 걸리지 않기 때문이다(F2). DB 단위작업은 [PostWriteService] 가 담당한다.
     */
    suspend fun registerPost(command: RegisterPostCommand) {
        validateBreed(command.animalType, command.breedId)
        withContext(Dispatchers.IO) {
            postWriteService.createPostWithSearchGroup(command, command.joinPolicy)
        }
    }

    private fun validateBreed(
        animalType: AnimalType,
        breedId: UUID?,
    ) {
        if (breedId == null) return
        val breed =
            breedRepository
                .findById(breedId)
                .orElseThrow { BusinessException(ErrorCode.NOT_FOUND_BREED) }
        if (breed.animalType != animalType) {
            throw BusinessException(ErrorCode.MISMATCHED_BREED)
        }
    }

    private suspend fun uploadImages(
        images: List<MultipartFile>,
        userId: UUID,
        applicationId: String,
    ): List<String> =
        multimediaService.uploadMultipartFiles(images, userId.toString(), applicationId)
            ?: throw ImageUploadException()

    private suspend fun savePostImages(
        post: Post,
        imageUrls: List<String>,
    ) {
        imageUrls.forEach { url ->
            val postImage =
                PostImage(
                    post = post,
                    imageUrl = url,
                )
            postImageRepository.save(postImage)
        }
    }

    suspend fun findDetailPost(
        id: UUID,
        userId: UUID?,
    ): PostDetailResponse {
        val detail =
            withContext(Dispatchers.IO) {
                postRepository.findPostDetailWithImages(id, userId)
            } ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        val resolved = multimediaService.resolvePresignedUrls(detail.imageUrls.map { it.image })
        detail.imageUrls =
            detail.imageUrls.mapIndexed { idx, item ->
                item.copy(image = resolved[idx])
            }
        return detail
    }

    suspend fun findPostList(query: SummarizedPostsByPageQuery): SummarizedPostsByPageDto {
        val page =
            withContext(Dispatchers.IO) {
                postRepository.findSummarizedPostsByPage(
                    size = query.size,
                    orderBy = query.orderBy,
                    page = query.offset,
                )
            }
        val resolved = multimediaService.resolvePresignedUrls(page.result.map { it.thumbnail ?: "" })
        val newContents =
            page.result.mapIndexed { idx, item ->
                if (item.thumbnail.isNullOrBlank()) item else item.copy(thumbnail = resolved[idx])
            }
        return page.copy(result = newContents)
    }

    suspend fun findNearbyPosts(
        lat: Double,
        lng: Double,
        radiusKm: Double,
        size: Long,
        offset: Long,
    ): NearbyPostsPage {
        val contents =
            withContext(Dispatchers.IO) {
                postNearbyRepository.findNearby(lat, lng, radiusKm, size, offset)
            }
        val totalCount =
            withContext(Dispatchers.IO) {
                postNearbyRepository.countNearby(lat, lng, radiusKm)
            }
        val resolved = multimediaService.resolvePresignedUrls(contents.map { it.thumbnail ?: "" })
        val newContents =
            contents.mapIndexed { idx, item ->
                if (item.thumbnail.isNullOrBlank()) item else item.copy(thumbnail = resolved[idx])
            }
        val hasNextPage = totalCount > (offset + contents.size)
        return NearbyPostsPage(contents = newContents, hasNextPage = hasNextPage, totalCount = totalCount)
    }

    data class NearbyPostsPage(
        val contents: List<PostNearbyResponse>,
        val hasNextPage: Boolean,
        val totalCount: Long,
    )

    /**
     * 실종 소식 삭제. 그룹 보관을 **먼저** 수행한다 — `@SQLDelete` 는 in-memory `deletedAt` 을
     * 채우지 않아서(F4) 순서를 뒤집으면 삭제된 글의 그룹이 ACTIVE 로 남는다(설계 §14.2).
     */
    @Transactional
    fun deletePost(
        postId: UUID,
        userId: UUID,
    ) {
        val post = getPostEntity(postId)

        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        searchLifecycleService.archiveOnPostDeleted(post, userId)
        // 조건부 벌크 UPDATE 가 1차 캐시를 비웠을 수 있으므로 관리 상태의 인스턴스를 다시 얻는다.
        val target = postRepository.findByIdAndDeletedAtIsNull(postId) ?: return
        postRepository.delete(target)
    }

    /**
     * 실종 소식 수정. `missingAnimalStatus` 는 여기서 직접 대입하지 않고
     * [SearchLifecycleService.applyStatusTransition] 에 위임한다(설계 §14.1) —
     * 이 API 로 `FOUND` 를 보내도 그룹 보관을 우회할 수 없어야 한다.
     *
     * 즐겨찾기 알림은 기존 동작을 그대로 보존한다: 이 경로는 원래 알림을 보내지 않았고,
     * 상태 변경 알림은 `PATCH /post/renewal-status` 의 책임이다.
     */
    @Transactional
    fun updatePost(
        command: UpdatePostRequest,
        userId: UUID,
    ) {
        val post = getPostEntity(command.postId)
        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        validateBreed(command.animalType, command.breedId)
        val previous = post.missingAnimalStatus
        post.update(
            title = command.title,
            description = command.description,
            place = command.place,
            phoneNum = command.phoneNum,
            time = command.time,
            gender = command.gender,
            gratuity = command.gratuity,
            lat = command.lat,
            lng = command.lng,
            openChatUrl = command.openChatUrl,
            // 상태는 생명주기 서비스만 바꾼다. 여기서는 기존 값을 그대로 되돌려 놓는다.
            missingAnimalStatus = previous,
            animalType = command.animalType,
            breedId = command.breedId,
        )
        searchLifecycleService.applyStatusTransition(post, userId, command.missingAnimalStatus)
    }

    @Transactional
    fun addPostImage(
        images: List<MultipartFile>,
        postId: UUID,
        userId: UUID,
        applicationId: String,
    ) {
        val postEntity = getPostEntity(postId)
        if (postEntity.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        runBlocking {
            val uploadImages = uploadImages(images, userId, applicationId)
            savePostImages(postEntity, uploadImages)
        }
    }

    @Transactional
    fun deletePostImage(
        userId: UUID,
        postImageId: UUID,
        postId: UUID,
    ) {
        val post = getPostEntity(postId)
        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        val postImageEntity = getPostImageEntity(postImageId)
        postImageRepository.delete(postImageEntity)
    }

    /** soft-delete 된 글은 더 이상 변경 대상이 아니다(F3). */
    private fun getPostEntity(id: UUID) =
        postRepository.findByIdAndDeletedAtIsNull(id) ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)

    private fun getPostImageEntity(id: UUID) =
        postImageRepository.findById(id).getOrElse {
            throw BusinessException(ErrorCode.NOT_FOUND_POST_IMAGE)
        }

    suspend fun myPage(userId: UUID): List<PostSummaryResponse> {
        val rows =
            withContext(Dispatchers.IO) {
                postRepository.findSummarizedPostsByUserId(userId)
            }
        val resolved = multimediaService.resolvePresignedUrls(rows.map { it.thumbnail ?: "" })
        return rows.mapIndexed { idx, item ->
            if (item.thumbnail.isNullOrBlank()) item else item.copy(thumbnail = resolved[idx])
        }
    }

    fun updateAuthor(
        userId: UUID,
        name: String,
    ) {
        postRepository.updateAuthorName(userId, name)
    }

    fun deleteAuthor(userId: UUID) {
        postRepository.updateAuthorName(userId, CANCEL_USER_NAME)
    }

    /**
     * 상태 전용 변경 API. 상태 전이는 [SearchLifecycleService] 에 위임하고
     * 기존 즐겨찾기 알림만 여기서 유지한다.
     */
    @Transactional
    fun updateStatus(
        postId: UUID,
        userId: UUID,
        status: MissingAnimalStatus,
    ) {
        val post = getPostEntity(postId)
        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        val previous = post.missingAnimalStatus

        // 접근 판정은 native SQL 이라 auto-flush 되지 않는다 — 상태를 바꾸기 전에 계산한다.
        val alreadyNotified = effectiveMembersForEndSearch(post, status, userId)

        searchLifecycleService.applyStatusTransition(post, userId, status)

        if (previous != status) {
            notifyBookmarkers(post, userId, status, alreadyNotified)
        }
    }

    /**
     * 수색 종료(`FOUND`)로 `SEARCH_ENDED` 를 이미 받게 될 유효 참여자 집합.
     * 다른 상태 전이에는 그룹 알림이 없으므로 빈 집합이다.
     */
    private fun effectiveMembersForEndSearch(
        post: Post,
        next: MissingAnimalStatus,
        actorUserId: UUID,
    ): Set<UUID> {
        if (next != MissingAnimalStatus.FOUND) return emptySet()
        val access = accessResolver.resolveByPostId(post.id, actorUserId) ?: return emptySet()
        return accessResolver.effectiveMemberIds(access.groupId)
    }

    /**
     * 즐겨찾기한 사용자에게 상태 변경 알림 (작성자 본인 제외).
     * 수색그룹 유효 참여자는 이미 `SEARCH_ENDED` 를 받았으므로 제외한다 —
     * 설계 §9 "여러 경로로 같은 그룹 권한을 가진 사용자는 알림을 한 번만 받는다".
     *
     * TODO(Task 5): `createStructuredMany(..., postId = post.id)` 로 교체해 `notification.post_id` 를 채운다.
     */
    private fun notifyBookmarkers(
        post: Post,
        actorUserId: UUID,
        status: MissingAnimalStatus,
        alreadyNotified: Set<UUID>,
    ) {
        val bookmarkers = bookmarkService.findBookmarkUserIdsByPost(post.id)
        if (bookmarkers.isEmpty()) return
        val targets = bookmarkers.toSet() - alreadyNotified
        if (targets.isEmpty()) return

        notificationService.createMany(
            userIds = targets,
            excludeUserId = actorUserId,
            type = NotificationType.BOOKMARK_STATUS_CHANGED,
            title = "즐겨찾기 게시글 상태가 변경됐어요",
            body = "${post.title} → ${labelOf(status)}",
            link = "/lost/${post.id}",
        )
    }

    private fun labelOf(status: MissingAnimalStatus): String =
        when (status) {
            MissingAnimalStatus.SEARCHING -> "찾는 중"
            MissingAnimalStatus.FOUND -> "찾음"
            MissingAnimalStatus.SEEN -> "목격됨"
        }
}
```

**3-6. `src/main/kotlin/com/park/animal/post/PostController.kt`** (`registerPost` 수정)

파일 상단 import 에 다음 한 줄을 추가한다 (`com.park.animal.post.entity.MissingAnimalStatus` 바로 아래).

```kotlin
import com.park.animal.searchgroup.entity.JoinPolicy
```

`registerPost` 를 아래로 교체한다 (기존 106-161행).

```kotlin
    @PostMapping(path = ["/post"], consumes = ["multipart/form-data", "application/json"])
    @Operation(
        summary = "게시글 등록 API",
        description =
            "실종 소식 등록. missingAnimalStatus 가 SEARCHING 이면 수색그룹이 함께 하나 생성된다.\n" +
                "joinPolicy 는 직접 참여 정책이며 기본값은 OPEN(자유롭게 참여), 다른 값은 " +
                "APPROVAL_REQUIRED(승인 후 참여) 뿐이다.\n" +
                "주의: enum 파라미터 변환은 대소문자를 구분한다(대문자만 허용). 'open' 같은 오타는 " +
                "MethodArgumentTypeMismatchException → 400 MISSING_PARAMETER 로 응답한다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    suspend fun registerPost(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @RequestParam title: String,
        @RequestParam phoneNum: String,
        @RequestParam
        @Parameter(example = "2000-10-31T01:30:00")
        time: LocalDateTime,
        @RequestParam place: String,
        @RequestParam gender: String,
        @RequestParam(required = false, defaultValue = "0") gratuity: Int,
        @RequestParam description: String,
        @RequestParam(required = false) image: List<MultipartFile> = emptyList(),
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam openChatUrl: String?,
        @RequestParam(required = false) customNickname: String?,
        @RequestParam missingAnimalStatus: MissingAnimalStatus,
        @RequestParam(required = false, defaultValue = "DOG") animalType: AnimalType,
        @RequestParam(required = false) breedId: UUID?,
        @RequestParam(required = false, defaultValue = "OPEN") joinPolicy: JoinPolicy,
    ): SucceededApiResponseBody<Void> {
        val resolvedUserName =
            if (customNickname != null && passport.role == Role.ROLE_ADMIN) {
                customNickname
            } else {
                passport.requireUserContext().userName.toString()
            }
        val command =
            RegisterPostCommand(
                userId = passport.userId,
                userName = resolvedUserName,
                images = image,
                title = title,
                phoneNum = phoneNum,
                time = time,
                place = place,
                gender = gender,
                gratuity = gratuity,
                description = description,
                lat = lat,
                lng = lng,
                openChatUrl = openChatUrl,
                missingAnimalStatus = missingAnimalStatus,
                animalType = animalType,
                breedId = breedId,
                applicationId = passport.signInApplicationId,
                joinPolicy = joinPolicy,
            )
        postService.registerPost(command)
        return SucceededApiResponseBody(data = null)
    }
```

- [ ] **Step 4: 생명주기 테스트 통과 확인**

Run:

```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchLifecycleIT"
```

Expected: PASS — 13개 테스트 전부 통과.

- [ ] **Step 5: 그룹 조회/종료 API 실패 테스트 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupCtaIT.kt` 를 새로 만든다. 여기서 계약 §8 #2·#3·#5·#6 의 동작을 고정한다. 컨트롤러는 서비스로의 얇은 위임이므로 서비스 진입점을 직접 구동한다.

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.notification.repository.NotificationRepository
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupCtaResponse
import com.park.animal.searchgroup.dto.SearchGroupViewerAction
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestConfiguration
class SearchGroupCtaTestBeans {
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * 공개 CTA · 그룹 상세 · 수색 종료 · 활동 기록 (계약 §8 #2·#3·#5·#6, 설계 §11/§14.1/§20).
 *
 * 시드는 JdbcTemplate 로 직접 INSERT 한다 — 접근 판정이 native SQL 이라 커밋된 행만 보이고,
 * 여기서 검증하려는 것은 "테이블 상태가 이럴 때 어떤 viewerAction 이 나오는가" 이기 때문이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupCtaTestBeans::class,
    NotificationService::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    SearchLifecycleService::class,
    SearchGroupService::class,
)
@Testcontainers
class SearchGroupCtaIT {
    @Autowired lateinit var searchGroupService: SearchGroupService

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val owner: UUID = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        listOf(
            "search_group_event",
            "search_group_team",
            "search_group_member",
            "search_group_user_block",
            "team_member",
            "team",
            "search_group",
            "notification",
            "post_image",
            "post",
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    // (a) 공개 CTA — 설계 §11 카카오톡 공유 링크 방문자 흐름

    @Test
    fun `비로그인 방문자는 LOGIN_REQUIRED 를 받는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.ACTIVE)

        val cta = searchGroupService.getCta(postId, null)

        assertEquals(SearchGroupViewerAction.LOGIN_REQUIRED, cta.viewerAction)
        assertEquals(groupId, cta.groupId)
        assertEquals(1L, cta.memberCount)
        assertEquals(0L, cta.teamCount)
    }

    @Test
    fun `자유 참여 그룹의 비참여 로그인 사용자는 JOIN_NOW 를 받는다`() {
        val postId = insertPost(owner)
        insertGroup(postId, joinPolicy = JoinPolicy.OPEN)

        val cta = searchGroupService.getCta(postId, UUID.randomUUID())

        assertEquals(SearchGroupViewerAction.JOIN_NOW, cta.viewerAction)
    }

    @Test
    fun `승인 후 참여 그룹의 비참여 로그인 사용자는 REQUEST_JOIN 을 받는다`() {
        val postId = insertPost(owner)
        insertGroup(postId, joinPolicy = JoinPolicy.APPROVAL_REQUIRED)

        val cta = searchGroupService.getCta(postId, UUID.randomUUID())

        assertEquals(SearchGroupViewerAction.REQUEST_JOIN, cta.viewerAction)
    }

    @Test
    fun `보호자와 참여자는 ALREADY_JOINED 를 받는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)

        assertEquals(SearchGroupViewerAction.ALREADY_JOINED, searchGroupService.getCta(postId, owner).viewerAction)
        assertEquals(
            SearchGroupViewerAction.ALREADY_JOINED,
            searchGroupService.getCta(postId, participant).viewerAction,
        )
    }

    @Test
    fun `차단된 사용자는 UNAVAILABLE 을 받고 응답에 차단 사실이 드러나지 않는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val blocked = UUID.randomUUID()
        insertMember(groupId, blocked, SearchGroupMemberStatus.ACTIVE)
        insertBlock(groupId, blocked, owner)

        val cta = searchGroupService.getCta(postId, blocked)

        assertEquals(SearchGroupViewerAction.UNAVAILABLE, cta.viewerAction)
        assertTrue(
            SearchGroupCtaResponse::class.memberProperties.none { it.name.contains("block", ignoreCase = true) },
            "설계 §6.3 / 계약 §9 — CTA 응답에 차단 여부 필드를 두지 않는다",
        )
    }

    @Test
    fun `ARCHIVED 그룹의 CTA 는 누구에게나 UNAVAILABLE 이다`() {
        val postId = insertPost(owner, status = MissingAnimalStatus.FOUND)
        val groupId = insertGroup(postId, status = SearchGroupStatus.ARCHIVED)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)

        assertEquals(SearchGroupViewerAction.UNAVAILABLE, searchGroupService.getCta(postId, null).viewerAction)
        assertEquals(SearchGroupViewerAction.UNAVAILABLE, searchGroupService.getCta(postId, owner).viewerAction)
        assertEquals(SearchGroupViewerAction.UNAVAILABLE, searchGroupService.getCta(postId, participant).viewerAction)
    }

    @Test
    fun `그룹이 없는 실종 소식과 삭제된 실종 소식의 CTA 는 404`() {
        val noGroupPostId = insertPost(owner, status = MissingAnimalStatus.SEEN)
        val deletedPostId = insertPost(owner, deleted = true)
        insertGroup(deletedPostId)

        val noGroup = assertFailsWith<BusinessException> { searchGroupService.getCta(noGroupPostId, owner) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, noGroup.errorCode)

        val deleted = assertFailsWith<BusinessException> { searchGroupService.getCta(deletedPostId, owner) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, deleted.errorCode)
    }

    // (b) 그룹 상세

    @Test
    fun `보호자 상세에는 관리 권한과 대기 건수가 담긴다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.ACTIVE)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.PENDING)

        val detail = searchGroupService.getDetail(groupId, owner)

        assertEquals(GroupRole.OWNER, detail.role)
        assertEquals(SearchGroupStatus.ACTIVE, detail.status)
        assertEquals(1L, detail.memberCount)
        assertEquals(1L, detail.pendingMemberCount)
        assertTrue(detail.canManage)
        assertTrue(detail.canWrite)
        assertEquals("테스트 실종 소식", detail.postTitle)
    }

    @Test
    fun `참여자 상세에는 대기 건수를 노출하지 않는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        val membershipId = insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.PENDING)

        val detail = searchGroupService.getDetail(groupId, participant)

        assertEquals(GroupRole.PARTICIPANT, detail.role)
        assertEquals(membershipId, detail.myMembershipId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, detail.myMembershipStatus)
        assertNull(detail.pendingMemberCount, "확인할 요청은 보호자 전용이다")
        assertNull(detail.pendingTeamCount)
        assertEquals(false, detail.canManage)
    }

    @Test
    fun `비참여자의 상세 조회는 403`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)

        val e = assertFailsWith<BusinessException> { searchGroupService.getDetail(groupId, UUID.randomUUID()) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    // (c) 수색 종료

    @Test
    fun `수색 종료는 보호자만 할 수 있다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)

        val e = assertFailsWith<BusinessException> { searchGroupService.endSearch(groupId, participant) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
        assertEquals(0, notificationRepository.findAll().size)
    }

    @Test
    fun `수색 종료를 두 번 호출해도 알림은 한 건이고 두 번째는 410`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)

        val detail = searchGroupService.endSearch(groupId, owner)

        assertEquals(SearchGroupStatus.ARCHIVED, detail.status)
        assertEquals(MissingAnimalStatus.FOUND, detail.postStatus)
        assertEquals(false, detail.canWrite)

        val second = assertFailsWith<BusinessException> { searchGroupService.endSearch(groupId, owner) }
        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, second.errorCode)

        val forParticipant = notificationRepository.findAll().filter { it.userId == participant }
        assertEquals(1, forParticipant.size)
        assertEquals(NotificationType.SEARCH_ENDED, forParticipant.first().type)
        assertEquals(0, notificationRepository.findAll().count { it.userId == owner }, "행위자 본인은 제외")
    }

    // (d) 활동 기록

    @Test
    fun `활동 기록은 유효 참여자만 최신순으로 조회한다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)
        insertEvent(groupId, SearchGroupEventType.GROUP_OPENED, LocalDateTime.now().minusMinutes(10))
        insertEvent(groupId, SearchGroupEventType.MEMBER_JOINED, LocalDateTime.now().minusMinutes(1))

        val events = searchGroupService.listEvents(groupId, participant, size = 20, offset = 0)

        assertEquals(2, events.size)
        assertEquals(SearchGroupEventType.MEMBER_JOINED, events[0].type, "createdAt DESC, id DESC")
        assertEquals(SearchGroupEventType.GROUP_OPENED, events[1].type)

        val e =
            assertFailsWith<BusinessException> {
                searchGroupService.listEvents(groupId, UUID.randomUUID(), size = 20, offset = 0)
            }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    // --- 시드 helper (전부 커밋된 행) ---

    private fun insertPost(
        authorId: UUID,
        status: MissingAnimalStatus = MissingAnimalStatus.SEARCHING,
        deleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO post (id, author_id, author_name, title, phone_num, time, place, gender, gratuity,
                              description, lat, lng, open_chat_url, missing_animal_status, animal_type, breed_id,
                              created_at, updated_at, deleted_at)
            VALUES (?, ?, '보호자', '테스트 실종 소식', '010-0000-0000', NOW(6), '서울 강남구', '남아', 0,
                    '설명', 37.5, 127.0, NULL, ?, 'DOG', NULL, NOW(6), NOW(6), ?)
            """.trimIndent(),
            id.toString(),
            authorId.toString(),
            status.name,
            if (deleted) Timestamp.valueOf(LocalDateTime.now()) else null,
        )
        return id
    }

    private fun insertGroup(
        postId: UUID,
        joinPolicy: JoinPolicy = JoinPolicy.OPEN,
        status: SearchGroupStatus = SearchGroupStatus.ACTIVE,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            postId.toString(),
            joinPolicy.name,
            status.name,
        )
        return id
    }

    private fun insertMember(
        groupId: UUID,
        userId: UUID,
        status: SearchGroupMemberStatus,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_member (id, group_id, user_id, user_name, status, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            userId.toString(),
            status.name,
        )
        return id
    }

    private fun insertBlock(
        groupId: UUID,
        userId: UUID,
        blockedBy: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_user_block (id, group_id, user_id, blocked_by, reason, blocked_at,
                                                 unblocked_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, NULL, NOW(6), NULL, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            userId.toString(),
            blockedBy.toString(),
        )
        return id
    }

    private fun insertEvent(
        groupId: UUID,
        type: SearchGroupEventType,
        createdAt: LocalDateTime,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_event (id, group_id, type, actor_id, target_id, detail, created_at, updated_at)
            VALUES (?, ?, ?, NULL, NULL, NULL, ?, ?)
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            type.name,
            Timestamp.valueOf(createdAt),
            Timestamp.valueOf(createdAt),
        )
        return id
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 6: CTA 테스트가 실패하는지 확인**

Run:

```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchGroupCtaIT"
```

Expected: FAIL — Kotlin 컴파일 에러. `e: .../SearchGroupCtaIT.kt: Unresolved reference: SearchGroupService`, `Unresolved reference: SearchGroupCtaResponse`, `Unresolved reference: SearchGroupViewerAction`.

- [ ] **Step 7: 그룹 조회/종료 API 구현**

**7-1. `src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupDtos.kt`** (신규)

```kotlin
package com.park.animal.searchgroup.dto

import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.access.AccessSource
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.entity.ArchivedReason
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupEvent
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공개 CTA 버튼이 취해야 할 다음 동작.
 *
 * 이 백엔드는 미인증 요청에 401 을 내지 않는다(F19 — 401 은 게이트웨이 소관).
 * 프런트가 상태코드로 인증 여부를 추론하지 않도록 [LOGIN_REQUIRED] 를 본문에 담는다(계약 §9).
 */
enum class SearchGroupViewerAction {
    /** 자유롭게 참여 — 누르면 바로 활성 참여자가 된다. */
    JOIN_NOW,

    /** 승인 후 참여 — 누르면 PENDING 요청이 생기고 보호자 승인을 기다린다. */
    REQUEST_JOIN,

    /** 보호자이거나 이미 참여 중. */
    ALREADY_JOINED,

    /** 비로그인. 로그인 후 다시 CTA 를 조회한다. */
    LOGIN_REQUIRED,

    /**
     * 참여할 수 없음. **종료된 수색 · 목격 소식 · 차단된 사용자가 모두 이 값 하나를 받는다.**
     * 셋을 구분할 수 있으면 차단 사실이 응답으로 새어나간다(설계 §6.3, 계약 §9).
     */
    UNAVAILABLE,
}

/**
 * 공개 실종 소식의 `함께 찾기` 카드 (계약 §8 #2, 설계 §11).
 *
 * 비로그인 방문자도 받는 응답이므로 **좌표·전화번호·설명 본문을 담지 않는다**(설계 §16.5).
 * 그런 값이 필요하면 공개 `GET /post/{id}` 를 쓴다.
 * **`isBlocked` 같은 필드를 절대 추가하지 않는다** — 차단 여부는 [SearchGroupViewerAction.UNAVAILABLE]
 * 안에 숨어야 하며 비차단 비참여자와 구분 불가해야 한다.
 */
data class SearchGroupCtaResponse(
    val postId: UUID,
    val groupId: UUID,
    val joinPolicy: JoinPolicy,
    val status: SearchGroupStatus,
    val postStatus: MissingAnimalStatus,
    val memberCount: Long,
    val teamCount: Long,
    val viewerAction: SearchGroupViewerAction,
)

/**
 * 수색그룹 상세 (계약 §8 #3·#4·#5 공통 응답).
 *
 * 유효 참여자만 받는 응답이지만 여기에도 좌표·연락처를 담지 않는다 — 지도는 phase 2 의
 * 별도 엔드포인트가 담당하고, 연락처는 공개 게시글 상세의 책임이다.
 *
 * [pendingMemberCount] / [pendingTeamCount] 는 보호자에게만 채워지고 그 외에는 null 이다.
 * `확인할 요청` 은 보호자 전용 화면이다(설계 §7, §10).
 */
data class SearchGroupDetailResponse(
    val groupId: UUID,
    val postId: UUID,
    val postTitle: String,
    val joinPolicy: JoinPolicy,
    val status: SearchGroupStatus,
    val postStatus: MissingAnimalStatus,
    val archivedReason: ArchivedReason?,
    val archivedAt: LocalDateTime?,
    val role: GroupRole,
    val sources: Set<AccessSource>,
    val memberCount: Long,
    val teamCount: Long,
    val pendingMemberCount: Long?,
    val pendingTeamCount: Long?,
    val myMembershipId: UUID?,
    val myMembershipStatus: SearchGroupMemberStatus?,
    val canManage: Boolean,
    val canWrite: Boolean,
)

/**
 * 그룹 활동 기록 한 줄 (계약 §8 #6, 설계 §10 활동 탭 · §20 감사).
 *
 * `detail` 은 상태 전이 요약만 담는다. 좌표·본문·차단 사유는 애초에 저장되지 않는다.
 */
data class SearchGroupEventResponse(
    val id: UUID,
    val type: SearchGroupEventType,
    val actorId: UUID?,
    val targetId: UUID?,
    val detail: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(event: SearchGroupEvent): SearchGroupEventResponse =
            SearchGroupEventResponse(
                id = event.id,
                type = event.type,
                actorId = event.actorId,
                targetId = event.targetId,
                detail = event.detail,
                createdAt = event.createdAt,
            )
    }
}
```

**7-2. `src/main/kotlin/com/park/animal/searchgroup/SearchGroupService.kt`** (신규)

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.GroupAccess
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupCtaResponse
import com.park.animal.searchgroup.dto.SearchGroupDetailResponse
import com.park.animal.searchgroup.dto.SearchGroupEventResponse
import com.park.animal.searchgroup.dto.SearchGroupViewerAction
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupEventRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 수색그룹 조회·종료 진입점 (계약 §8 #2·#3·#5·#6).
 *
 * 권한 판정은 전부 [SearchGroupAccessResolver] 에 위임한다. 이 클래스는 판정 결과를 DTO 로
 * 옮기는 일만 한다 — 여기서 `role` 이나 `blocked` 를 다시 해석하면 판정이 두 곳으로 갈라진다.
 *
 * 참여 정책 변경(`PATCH /search-groups/{groupId}/join-policy`, 계약 §8 #4)은 **Task 6 이
 * 이 클래스에 `updateJoinPolicy` 로 추가**한다. 여기서는 만들지 않는다.
 */
@Service
class SearchGroupService(
    private val accessResolver: SearchGroupAccessResolver,
    private val searchGroupRepository: SearchGroupRepository,
    private val searchGroupMemberRepository: SearchGroupMemberRepository,
    private val searchGroupTeamRepository: SearchGroupTeamRepository,
    private val searchGroupEventRepository: SearchGroupEventRepository,
    private val postRepository: PostRepository,
    private val searchLifecycleService: SearchLifecycleService,
) {
    companion object {
        /** 활동 기록 한 페이지 상한. 설계 §16.7 "서버가 결과 수를 제한한다". */
        const val MAX_EVENT_PAGE_SIZE = 50
    }

    /**
     * 공개 CTA (계약 §8 #2, 설계 §11). 비로그인 [viewerId] = null 을 허용한다.
     *
     * 그룹이 없는 글(`SEEN` 으로 등록돼 백필 대상이 아니었던 글)과 soft-delete 된 글은
     * 똑같이 404 다 — 어느 쪽인지 구분할 수 있으면 삭제 사실이 새어나간다.
     *
     * [SearchGroupViewerAction] 결정 순서가 곧 개인정보 계약이다. 차단 판정을 가장 먼저 두고,
     * 종료·목격 상태를 그다음에 둔다. 그 결과 차단자는 종료된 수색·목격 소식과 완전히 같은
     * `UNAVAILABLE` 을 받는다(설계 §6.3, 계약 §9).
     */
    @Transactional(readOnly = true)
    fun getCta(
        postId: UUID,
        viewerId: UUID?,
    ): SearchGroupCtaResponse {
        val access =
            accessResolver.resolveByPostId(postId, viewerId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        if (!access.visible) throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)

        val action =
            when {
                access.blocked -> SearchGroupViewerAction.UNAVAILABLE
                access.groupStatus != SearchGroupStatus.ACTIVE -> SearchGroupViewerAction.UNAVAILABLE
                access.postStatus != MissingAnimalStatus.SEARCHING -> SearchGroupViewerAction.UNAVAILABLE
                access.role != GroupRole.NONE -> SearchGroupViewerAction.ALREADY_JOINED
                viewerId == null -> SearchGroupViewerAction.LOGIN_REQUIRED
                access.joinPolicy == JoinPolicy.OPEN -> SearchGroupViewerAction.JOIN_NOW
                else -> SearchGroupViewerAction.REQUEST_JOIN
            }

        return SearchGroupCtaResponse(
            postId = access.postId,
            groupId = access.groupId,
            joinPolicy = access.joinPolicy,
            status = access.groupStatus,
            postStatus = access.postStatus,
            memberCount = activeMemberCount(access.groupId),
            teamCount = activeTeamCount(access.groupId),
            viewerAction = action,
        )
    }

    /** 그룹 상세 (계약 §8 #3). 유효 참여자만 볼 수 있다 — 차단·비참여는 같은 403. */
    @Transactional(readOnly = true)
    fun getDetail(
        groupId: UUID,
        viewerId: UUID,
    ): SearchGroupDetailResponse {
        val access = accessResolver.requireRead(groupId, viewerId)
        val group =
            searchGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        val post =
            postRepository.findByIdAndDeletedAtIsNull(group.postId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        return toDetail(access, group, post)
    }

    /**
     * 보호자의 수색 종료 (계약 §8 #5, 설계 §3.12/§14.1/§22.7).
     *
     * [SearchGroupAccessResolver.requireOwner] 가 (a) 보호자가 아니면 403,
     * (b) 이미 ARCHIVED 면 410 을 내므로 두 번째 호출은 여기서 걸린다 —
     * 감사 기록과 알림이 두 번 생기지 않는다. 경합으로 두 요청이 동시에 통과해도
     * `archiveIfStatus` 의 조건부 UPDATE 가 한쪽만 1행을 바꾸므로 fan-out 은 한 번뿐이다.
     *
     * 응답은 판정 시점의 [GroupAccess] 와 **전이 후** 엔티티를 섞어 만든다.
     * 접근 판정은 native SQL 이라 방금 flush 되지 않은 변경을 보지 못하므로 재판정하지 않는다.
     */
    @Transactional
    fun endSearch(
        groupId: UUID,
        actorUserId: UUID,
    ): SearchGroupDetailResponse {
        val access = accessResolver.requireOwner(groupId, actorUserId)
        val group = searchLifecycleService.endSearch(access.groupId, actorUserId)
        val post =
            postRepository.findByIdAndDeletedAtIsNull(group.postId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        return toDetail(access, group, post)
    }

    /**
     * 그룹 활동
기록 (계약 §8 #6, 설계 §10 활동 탭). 유효 참여자만 조회한다.
     *
     * 정렬은 `createdAt DESC, id DESC` 다 — `DATETIME(6)` 이라도 같은 마이크로초 다건이
     * 페이지 경계에 걸리면 offset 페이지네이션에서 중복/누락이 나므로 tiebreaker 를 둔다(F20).
     */
    @Transactional(readOnly = true)
    fun listEvents(
        groupId: UUID,
        viewerId: UUID,
        size: Int,
        offset: Int,
    ): List<SearchGroupEventResponse> {
        accessResolver.requireRead(groupId, viewerId)
        val pageSize = size.coerceIn(1, MAX_EVENT_PAGE_SIZE)
        val pageNumber = (offset.coerceAtLeast(0)) / pageSize
        return searchGroupEventRepository
            .findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId, PageRequest.of(pageNumber, pageSize))
            .content
            .map(SearchGroupEventResponse::from)
    }

    private fun toDetail(
        access: GroupAccess,
        group: SearchGroup,
        post: Post,
    ): SearchGroupDetailResponse {
        val isOwner = access.isOwner
        val active = group.status == SearchGroupStatus.ACTIVE
        return SearchGroupDetailResponse(
            groupId = group.id,
            postId = group.postId,
            postTitle = post.title,
            joinPolicy = group.joinPolicy,
            status = group.status,
            postStatus = post.missingAnimalStatus,
            archivedReason = group.archivedReason,
            archivedAt = group.archivedAt,
            role = access.role,
            sources = access.sources,
            memberCount = activeMemberCount(group.id),
            teamCount = activeTeamCount(group.id),
            pendingMemberCount =
                if (isOwner) {
                    searchGroupMemberRepository.countByGroupIdAndStatus(group.id, SearchGroupMemberStatus.PENDING)
                } else {
                    null
                },
            pendingTeamCount =
                if (isOwner) {
                    searchGroupTeamRepository.countByGroupIdAndStatus(
                        group.id,
                        SearchGroupTeamStatus.PENDING_GROUP_APPROVAL,
                    )
                } else {
                    null
                },
            myMembershipId = access.directMembershipId,
            myMembershipStatus = access.directMembershipStatus,
            canManage = isOwner && active,
            canWrite = access.role != GroupRole.NONE && active,
        )
    }

    private fun activeMemberCount(groupId: UUID): Long =
        searchGroupMemberRepository.countByGroupIdAndStatus(groupId, SearchGroupMemberStatus.ACTIVE)

    private fun activeTeamCount(groupId: UUID): Long =
        searchGroupTeamRepository.countByGroupIdAndStatus(groupId, SearchGroupTeamStatus.ACTIVE)
}
```

**7-3. `src/main/kotlin/com/park/animal/searchgroup/SearchGroupController.kt`** (신규)

```kotlin
package com.park.animal.searchgroup

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchgroup.dto.SearchGroupCtaResponse
import com.park.animal.searchgroup.dto.SearchGroupDetailResponse
import com.park.animal.searchgroup.dto.SearchGroupEventResponse
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

/**
 * 수색그룹 공개 CTA · 상세 · 수색 종료 · 활동 기록 (계약 §8 #2·#3·#5·#6).
 *
 * 계약 §1-16 에 따라 모든 핸들러에 `@PublicEndPoint` 또는 `@AuthenticationUser` 중 하나가 반드시 있다.
 * 없으면 `PassportInterceptor` 가 403 을 낸다.
 *
 * `passport.requireUserContext()` 를 직접 호출하지 않는다(계약 §1-11) — 이 컨트롤러의 모든
 * 응답은 표시 이름을 필요로 하지 않는다.
 *
 * **Task 6 이 이 클래스에 덧붙인다**: 생성자에 `SearchGroupMembershipService` 파라미터 한 줄,
 * 그리고 `PATCH /search-groups/{groupId}/join-policy`(계약 §8 #4) 핸들러 하나.
 * 전체 교체가 아니라 추가다.
 */
@RestController
@RequestMapping("/api/v1")
class SearchGroupController(
    private val searchGroupService: SearchGroupService,
) {
    @PublicEndPoint
    @GetMapping("/posts/{postId}/search-group")
    @Operation(
        summary = "공개 실종 소식의 함께 찾기 카드",
        description =
            "비로그인 접근 가능. 카카오톡 공유 링크 방문자가 보는 CTA 다.\n" +
                "viewerAction: JOIN_NOW(즉시 참여) / REQUEST_JOIN(승인 후 참여) / ALREADY_JOINED / " +
                "LOGIN_REQUIRED(비로그인) / UNAVAILABLE(종료·목격 소식·권한 없음·차단).\n" +
                "이 백엔드는 401 을 내지 않으므로 비로그인은 상태코드가 아니라 LOGIN_REQUIRED 로 판별한다.\n" +
                "그룹이 없는 글과 삭제된 글은 모두 404 NOT_FOUND_SEARCH_GROUP.",
    )
    fun getCta(
        @PathVariable("postId") postId: UUID,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        passport: Passport?,
    ): SucceededApiResponseBody<SearchGroupCtaResponse> =
        SucceededApiResponseBody(data = searchGroupService.getCta(postId, passport?.userId))

    @GetMapping("/search-groups/{groupId}")
    @Operation(
        summary = "수색그룹 상세",
        description =
            "유효 참여자(보호자·직접 ACTIVE 참여자·ACTIVE 팀 지원의 ACTIVE 팀원)만 조회 가능.\n" +
                "비참여자·차단자는 동일하게 403 SEARCH_GROUP_ACCESS_DENIED, 삭제된 실종 소식은 404.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun getDetail(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<SearchGroupDetailResponse> =
        SucceededApiResponseBody(data = searchGroupService.getDetail(groupId, passport.userId))

    @PostMapping("/search-groups/{groupId}/end")
    @Operation(
        summary = "수색 종료 (보호자 전용)",
        description =
            "실종 소식을 FOUND 로, 수색그룹을 ARCHIVED(FOUND) 로 전환하고 유효 참여자에게 " +
                "SEARCH_ENDED 알림을 1인당 1건 보낸다.\n" +
                "재활성화 API 는 없다. 이미 종료됐으면 410 SEARCH_ALREADY_ENDED, 보호자가 아니면 403.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun endSearch(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<SearchGroupDetailResponse> =
        SucceededApiResponseBody(data = searchGroupService.endSearch(groupId, passport.userId))

    @GetMapping("/search-groups/{groupId}/events")
    @Operation(
        summary = "수색그룹 활동 기록",
        description =
            "createdAt DESC, id DESC 정렬. 유효 참여자만 조회 가능.\n" +
                "pageSize 는 서버가 최대 50 으로 제한한다(설계 §16.7).",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun listEvents(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") size: Int,
        @RequestParam(name = "pageOffset", required = false, defaultValue = "0") offset: Int,
    ): SucceededApiResponseBody<List<SearchGroupEventResponse>> =
        SucceededApiResponseBody(
            data = searchGroupService.listEvents(groupId, passport.userId, size, offset),
        )
}
```

- [ ] **Step 8: 전체 테스트 통과 확인**

Run:

```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchGroupCtaIT"
```

Expected: PASS — 13개 테스트 전부 통과.

Run (회귀 확인 — Task 3 판정과 기존 통합검색이 깨지지 않았는지):

```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test
```

Expected: PASS — `SearchGroupMigrationIT`, `SearchGroupAccessResolverIT`, `SearchLifecycleIT`, `SearchGroupCtaIT`, `SearchFulltextIT`, `SearchHybridFallbackTest`, `GlobalExceptionMappingTest` 전부 통과.

- [ ] **Step 9: 커밋**

```bash
cd /Users/park/Desktop/project/animal && git add src/main/kotlin/com/park/animal/searchgroup/SearchLifecycleService.kt \
        src/main/kotlin/com/park/animal/searchgroup/SearchGroupEventRecorder.kt \
        src/main/kotlin/com/park/animal/searchgroup/SearchGroupService.kt \
        src/main/kotlin/com/park/animal/searchgroup/SearchGroupController.kt \
        src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupDtos.kt \
        src/main/kotlin/com/park/animal/post/PostWriteService.kt \
        src/main/kotlin/com/park/animal/post/PostService.kt \
        src/main/kotlin/com/park/animal/post/PostController.kt \
        src/main/kotlin/com/park/animal/post/dto/RegisterPostCommand.kt \
        src/test/kotlin/com/park/animal/searchgroup/SearchLifecycleIT.kt \
        src/test/kotlin/com/park/animal/searchgroup/SearchGroupCtaIT.kt \
&& git commit -m "feat(search-group): 수색 생명주기 통합 + 그룹 조회/종료 API + PostService 트랜잭션 경계 정정

- 상태를 바꾸는 모든 경로(POST/PUT/PATCH/DELETE)를 SearchLifecycleService 로 단일화 (설계 14.1)
- FOUND 전환은 post/group/감사/알림을 한 트랜잭션에서 처리, 재호출은 조건부 UPDATE 로 멱등
- 실종 소식 삭제 시 POST_DELETED 로 그룹 보관, delete() 보다 먼저 호출 (F4)
- PostWriteService 분리: suspend fun 의 @Transactional 무효 문제(F2)와 이미지 업로드 실패 시
  post 가 남던 결함을 함께 해소
- SearchGroupService/Controller 추가: 공개 CTA·그룹 상세·수색 종료·활동 기록 (계약 8 #2/#3/#5/#6)
- CTA 는 차단자를 종료/목격 소식과 같은 UNAVAILABLE 로 응답, isBlocked 필드 없음 (설계 6.3)
- POST /post 에 joinPolicy 추가(기본 OPEN), getPostEntity 를 findByIdAndDeletedAtIsNull 로 교체 (F3)
- 수색 종료 알림은 유효 참여자에게 1건만 — 즐겨찾기 대상에서 참여자를 제외 (설계 9)"
```

> **Task 5 인수인계 (R2)**: 이 시점의 `SearchLifecycleService.endSearch` 와 `PostService.notifyBookmarkers` 는 레거시 `NotificationService.createMany` 를 쓰므로 `notification.group_id` / `post_id` / `actor_user_id` 가 NULL 이다. Task 5 는 자기 태스크 말미에 전자를 `GroupNotificationPublisher.notifyGroup(groupId, postId, NotificationType.SEARCH_ENDED, excluding = setOf(actorUserId), actorUserId = actorUserId, body = null)` 으로, 후자를 `createStructuredMany(..., postId = post.id)` 로 교체하고, 위 두 곳의 하드코딩 제목·본문 문자열을 삭제한 뒤 `GroupNotificationTemplates` 문구 표 하나만 남긴다. Task 11 의 `SearchGroupConcurrencyIT` / `SearchGroupPrivacyIT` 는 그 교체 이후에 통과한다.

---

### Task 5: 알림 구조화 확장 + GroupNotificationPublisher

**Files:**
- Modify: `src/main/kotlin/com/park/animal/notification/entity/Notification.kt:19-39`
- Modify: `src/main/kotlin/com/park/animal/notification/entity/NotificationType.kt:3-12`
- Modify: `src/main/kotlin/com/park/animal/notification/dto/NotificationDtos.kt:8-29`
- Modify: `src/main/kotlin/com/park/animal/notification/NotificationService.kt:19-54`
- Modify: `src/main/resources/application.yml:28-36`
- Modify: `src/main/kotlin/com/park/animal/searchgroup/SearchLifecycleService.kt` (Task 4 산출물 — `endSearch` 알림 fan-out 을 `GroupNotificationPublisher` 로 교체)
- Modify: `src/main/kotlin/com/park/animal/post/PostService.kt` (Task 4 산출물 — 즐겨찾기 fan-out 을 `createStructuredMany` 로 교체)
- Modify: `src/test/kotlin/com/park/animal/searchgroup/SearchLifecycleIT.kt` (Task 4 산출물 — `@Import` 보강)
- Create: `src/main/kotlin/com/park/animal/searchgroup/GroupNotificationPublisher.kt`
- Test: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupTestMetricsConfig.kt`
- Test: `src/test/kotlin/com/park/animal/notification/NotificationTypeContractTest.kt`
- Test: `src/test/kotlin/com/park/animal/searchgroup/GroupNotificationTemplateTest.kt`
- Test: `src/test/kotlin/com/park/animal/searchgroup/GroupNotificationFanoutIT.kt`

**Interfaces:**

- Consumes (Task 1 산출물):
  - `NotificationRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId: UUID, pageable: Pageable): Page<Notification>` — Task 1 이 `createdAt` 단독 정렬에 id tiebreaker 를 붙인 뒤의 이름이다(F20).
- Consumes (Task 2 산출물 — 이 시그니처 그대로여야 컴파일된다):
  - `com.park.animal.team.entity.TeamMemberStatus` (계약 §3)
  - `com.park.animal.team.repository.TeamMemberRepository : JpaRepository<TeamMember, UUID>` 에 `fun findAllByTeamIdAndStatus(teamId: UUID, status: TeamMemberStatus): List<TeamMember>`
  - `com.park.animal.team.entity.TeamMember` 의 프로퍼티 `val userId: UUID`
  - V12 의 `ALTER TABLE notification ADD COLUMN actor_user_id, actor_name, post_id, group_id, team_id` (계약 §6)
- Consumes (Task 3 산출물):
  - `com.park.animal.searchgroup.access.GroupAccess` (계약 §7 data class 전체)
  - `com.park.animal.searchgroup.access.SearchGroupAccessResolver.effectiveMemberIds(groupId: UUID): Set<UUID>`
  - `SearchGroupAccessResolver` 는 R12 로 `MeterRegistry` 를 주입받는다 → `@DataJpaTest` 슬라이스에는 `MeterRegistry` 빈이 없으므로 이 태스크가 만드는 `SearchGroupTestMetricsConfig` 를 함께 `@Import` 한다.
- Consumes (Task 4 산출물 — Step 5 가 이 코드를 고친다):
  - `@Service class SearchLifecycleService(searchGroupRepository, postRepository, searchGroupEventRecorder, accessResolver, notificationService)` 와 그 안의 `@Transactional fun endSearch(groupId: UUID, actorUserId: UUID): SearchGroup`
  - `PostService.notifyBookmarkers(post, actorUserId, status, alreadyNotified)` 안의 `notificationService.createMany(...)` 호출
  - `src/test/kotlin/com/park/animal/searchgroup/SearchLifecycleIT.kt` 의 `@Import(...)` 목록
- Produces (Task 6·7·8·9·10 이 의존):
  - `NotificationService.createStructured(userId: UUID, type: NotificationType, title: String, body: String? = null, link: String? = null, actorUserId: UUID? = null, actorName: String? = null, postId: UUID? = null, groupId: UUID? = null, teamId: UUID? = null, skipSelf: Boolean = true): Notification?`
  - `NotificationService.createStructuredMany(userIds: Collection<UUID>, type: NotificationType, title: String, body: String? = null, link: String? = null, actorUserId: UUID? = null, actorName: String? = null, postId: UUID? = null, groupId: UUID? = null, teamId: UUID? = null, excludeUserId: UUID? = null)`
  - `GroupNotificationPublisher.notifyOwner(access: GroupAccess, type: NotificationType, actorUserId: UUID, actorName: String?, body: String?)`
  - `GroupNotificationPublisher.notifyUser(userId: UUID, type: NotificationType, actorUserId: UUID?, postId: UUID?, groupId: UUID?, teamId: UUID?, body: String?)`
  - `GroupNotificationPublisher.notifyGroup(groupId: UUID, postId: UUID, type: NotificationType, excluding: Set<UUID>, actorUserId: UUID?, body: String?)`
  - `GroupNotificationPublisher.notifyTeamMembers(teamId: UUID, groupId: UUID?, postId: UUID?, type: NotificationType, excluding: Set<UUID>, actorUserId: UUID?, body: String?)`
  - `GroupNotificationTemplates.PHASE1_TYPES: Set<NotificationType>`(17종), `titleOf(type, actorName)`, `bodyOf(type)`, `linkOf(type, postId, groupId, teamId)`
  - `GroupNotificationPublisher.MAX_FANOUT_PER_EVENT = 500`
  - `NotificationResponse` 의 추가 필드 `actorName`, `postId`, `groupId`, `teamId`
  - `SearchGroupTestMetricsConfig` (테스트 전용 `SimpleMeterRegistry` 빈) — Task 6·7·9·10 IT 가 그대로 `@Import` 한다.
  - **Step 5 이후 `SEARCH_ENDED` 알림 행은 `group_id`/`post_id`/`actor_user_id` 가 채워진다.** Task 11 의 `SearchGroupConcurrencyIT`(`WHERE group_id = ? AND type = 'SEARCH_ENDED'`)와 `SearchGroupPrivacyIT` 가 이 사실에 의존한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupTestMetricsConfig.kt`

```kotlin
package com.park.animal.searchgroup

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * `@DataJpaTest` 슬라이스는 `MetricsAutoConfiguration` 을 올리지 않는다.
 * 설계 §20 관측 카운터를 쓰는 빈(`SearchGroupAccessResolver`, `SearchGroupMembershipService`,
 * `SearchGroupTeamSupportService`)을 `@Import` 하면 `MeterRegistry` 가 없어 컨텍스트 로딩 자체가 실패한다.
 * 계측 값은 검증 대상이 아니므로 in-memory 레지스트리로 충분하다.
 *
 * 이 파일은 함께 찾기 IT 전체가 공유한다 — 같은 패키지에 같은 이름의 `@TestConfiguration` 을
 * 파일마다 따로 두면 빈 이름이 충돌한다.
 */
@TestConfiguration
class SearchGroupTestMetricsConfig {
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}
```

`src/test/kotlin/com/park/animal/notification/NotificationTypeContractTest.kt`

```kotlin
package com.park.animal.notification

import com.park.animal.notification.entity.NotificationType
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * NotificationType 은 `@Enumerated(STRING)` 으로 저장된다. 운영 notification 행이 들고 있는
 * 문자열을 코드가 모르면 목록 조회 전체가 500 이 된다(구버전 replica 도 동일).
 * 그래서 (a) legacy 3종은 절대 삭제·개명하지 않고, (b) phase 1 상수는 한 번에 전부 배포한다.
 *
 * `JOIN_POLICY_CHANGED` 는 phase 1 에서 **발행하지 않지만**(설계 §8.3 은 활동 기록만 요구)
 * 상수 자체는 여기에 남겨 둔다 — 롤링 배포 안전성은 발행 여부가 아니라 선언 여부에 달려 있다.
 */
class NotificationTypeContractTest {
    private val names = NotificationType.values().map { it.name }.toSet()

    @Test
    fun `legacy 3종 상수는 이름 그대로 남아 있어야 한다`() {
        listOf(
            "SIGHTING_REGISTERED",
            "BOOKMARK_STATUS_CHANGED",
            "ABANDONED_NEW_IN_REGION",
        ).forEach { legacy ->
            assertTrue(legacy in names, "운영 행이 들고 있는 legacy 문자열 $legacy 이 사라지면 알림 목록 전체가 500 이 된다")
        }
    }

    @Test
    fun `함께 찾기 phase 1 상수는 한 번에 전부 선언돼 있어야 한다`() {
        listOf(
            "GROUP_MEMBER_JOINED", "GROUP_JOIN_REQUESTED", "GROUP_JOIN_APPROVED", "GROUP_JOIN_REJECTED",
            "GROUP_MEMBER_REMOVED", "GROUP_MEMBER_BLOCKED", "JOIN_POLICY_CHANGED",
            "TEAM_SUPPORT_REQUESTED", "TEAM_SUPPORT_ACCEPTED", "TEAM_SUPPORT_DECLINED", "TEAM_SUPPORT_ENDED",
            "TEAM_MEMBER_REQUESTED", "TEAM_MEMBER_APPROVED", "TEAM_MEMBER_REJECTED", "TEAM_MEMBER_REMOVED",
            "TEAM_LEADERSHIP_TRANSFERRED",
            "SEARCH_ENDED",
            "SIGHTING_CREATED", "CHAT_MENTIONED", "GROUP_SYSTEM_EVENT",
        ).forEach { added ->
            assertTrue(added in names, "롤링 배포 중 구버전 replica 가 모르는 문자열을 만나지 않도록 $added 도 phase 1 에 함께 선언한다")
        }
    }
}
```

`src/test/kotlin/com/park/animal/searchgroup/GroupNotificationTemplateTest.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.notification.entity.NotificationType
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 문구 테이블 고정 (설계 §2 어휘 · §9 민감정보 금지 · §6.3 행위자 비공개).
 */
class GroupNotificationTemplateTest {
    private val postId = UUID.randomUUID()
    private val groupId = UUID.randomUUID()
    private val teamId = UUID.randomUUID()

    @Test
    fun `phase 1 모든 타입에 제목과 본문이 있다`() {
        GroupNotificationTemplates.PHASE1_TYPES.forEach { type ->
            val title = GroupNotificationTemplates.titleOf(type, "김보호")
            val body = GroupNotificationTemplates.bodyOf(type)
            assertTrue(title.isNotBlank(), "$type 제목 누락")
            assertTrue(body != null && body.isNotBlank(), "$type 본문 누락")
        }
    }

    @Test
    fun `phase 1 타입 집합은 16종이다`() {
        assertEquals(17, GroupNotificationTemplates.PHASE1_TYPES.size)
        assertTrue(NotificationType.SIGHTING_CREATED !in GroupNotificationTemplates.PHASE1_TYPES)
        assertTrue(NotificationType.CHAT_MENTIONED !in GroupNotificationTemplates.PHASE1_TYPES)
        assertTrue(NotificationType.GROUP_SYSTEM_EVENT !in GroupNotificationTemplates.PHASE1_TYPES)
    }

    @Test
    fun `참여 정책 변경은 알림을 발행하지 않는다`() {
        // 설계 §8.3 은 "정책 변경은 그룹 활동 기록에 남긴다" 만 요구한다. 알림 수신자 표에 이 항목이 없다.
        // 상수는 롤링 배포 안전을 위해 NotificationType 에 남아 있지만 발행 경로는 0개다.
        assertTrue(NotificationType.JOIN_POLICY_CHANGED !in GroupNotificationTemplates.PHASE1_TYPES)
        assertNull(GroupNotificationTemplates.bodyOf(NotificationType.JOIN_POLICY_CHANGED))
        assertNull(GroupNotificationTemplates.linkOf(NotificationType.JOIN_POLICY_CHANGED, postId, groupId, null))
    }

    @Test
    fun `내보내기와 차단 문구는 행위자 이름에 영향받지 않는다`() {
        listOf(NotificationType.GROUP_MEMBER_REMOVED, NotificationType.GROUP_MEMBER_BLOCKED).forEach { type ->
            val withActor = GroupNotificationTemplates.titleOf(type, "김보호")
            val withOther = GroupNotificationTemplates.titleOf(type, "이참여")
            val withNull = GroupNotificationTemplates.titleOf(type, null)
            assertEquals(withActor, withOther, "$type 은 행위자를 노출하면 안 된다")
            assertEquals(withActor, withNull, "$type 은 행위자를 노출하면 안 된다")
            assertTrue(!withActor.contains("김보호"))
        }
    }

    @Test
    fun `참여 관련 문구는 행위자 이름을 포함한다`() {
        assertTrue(GroupNotificationTemplates.titleOf(NotificationType.GROUP_MEMBER_JOINED, "김보호").contains("김보호"))
        assertTrue(GroupNotificationTemplates.titleOf(NotificationType.GROUP_JOIN_REQUESTED, "김보호").contains("김보호"))
    }

    @Test
    fun `문구에 금지어와 숫자가 없다`() {
        val forbidden = listOf("admin", "Admin", "ADMIN", "관리자", "좌표", "위도", "경도", "전화", "휴대폰", "사유", "차단")
        val digits = Regex("\\d")
        GroupNotificationTemplates.PHASE1_TYPES.forEach { type ->
            val text = GroupNotificationTemplates.titleOf(type, "김보호") + " " + GroupNotificationTemplates.bodyOf(type)
            forbidden.forEach { word ->
                assertTrue(!text.contains(word), "$type 문구에 금지어 '$word' 가 있다: $text")
            }
            assertTrue(!digits.containsMatchIn(text), "$type 문구에 숫자가 있다(전화번호·좌표 유출 위험): $text")
        }
    }

    @Test
    fun `링크는 그룹 팀 승인대기 규칙을 따른다`() {
        assertEquals(
            "/lost/$postId/group",
            GroupNotificationTemplates.linkOf(NotificationType.GROUP_MEMBER_JOINED, postId, groupId, null),
        )
        assertEquals(
            "/lost/$postId",
            GroupNotificationTemplates.linkOf(NotificationType.GROUP_MEMBER_BLOCKED, postId, groupId, null),
        )
        assertEquals(
            "/profile",
            GroupNotificationTemplates.linkOf(NotificationType.GROUP_JOIN_REQUESTED, postId, groupId, null),
        )
        assertEquals(
            "/teams/$teamId",
            GroupNotificationTemplates.linkOf(NotificationType.TEAM_MEMBER_APPROVED, null, null, teamId),
        )
        assertNull(GroupNotificationTemplates.linkOf(NotificationType.SIGHTING_REGISTERED, postId, groupId, teamId))
    }
}
```

`src/test/kotlin/com/park/animal/searchgroup/GroupNotificationFanoutIT.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.notification.repository.NotificationRepository
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 설계 §9 "여러 경로로 같은 그룹 권한을 가진 사용자는 알림을 한 번만 받는다" 를 고정한다.
 * 직접 ACTIVE 멤버이면서 동시에 지원 팀의 ACTIVE 팀원인 사용자가 대상이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupTestMetricsConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
)
@Testcontainers
class GroupNotificationFanoutIT {
    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var publisher: GroupNotificationPublisher

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var postId: UUID
    private lateinit var groupId: UUID
    private lateinit var ownerId: UUID
    private lateinit var dualUserId: UUID
    private lateinit var teamOnlyUserId: UUID

    @BeforeEach
    fun seed() {
        listOf(
            "notification", "search_group_event", "search_group_user_block", "search_group_member",
            "search_group_team", "team_member", "team", "search_group", "sighting", "post_bookmark", "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }

        ownerId = UUID.randomUUID()
        dualUserId = UUID.randomUUID()
        teamOnlyUserId = UUID.randomUUID()

        val post =
            postRepository.save(
                Post(
                    authorId = ownerId,
                    authorName = "보호자",
                    title = "말티즈를 찾습니다",
                    phoneNum = "010-0000-0000",
                    time = LocalDateTime.now(),
                    place = "서울 강남구 역삼동",
                    gender = "남아",
                    gratuity = 0,
                    description = "겁이 많아요.",
                    lat = 37.5012,
                    lng = 127.0396,
                    openChatUrl = null,
                    missingAnimalStatus = MissingAnimalStatus.SEARCHING,
                    animalType = AnimalType.DOG,
                ),
            )
        postId = post.id
        groupId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            groupId.toString(), postId.toString(), "OPEN", "ACTIVE",
        )
        jdbcTemplate.update(
            "INSERT INTO search_group_member (id, group_id, user_id, user_name, status, joined_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,NOW(6),NOW(6),NOW(6))",
            UUID.randomUUID().toString(), groupId.toString(), dualUserId.toString(), "이중경로", "ACTIVE",
        )

        val teamId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO team (id, name, status, created_by, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            teamId.toString(), "강남수색팀", "ACTIVE", teamOnlyUserId.toString(),
        )
        listOf(teamOnlyUserId to "LEADER", dualUserId to "MEMBER").forEach { (uid, role) ->
            jdbcTemplate.update(
                "INSERT INTO team_member (id, team_id, user_id, user_name, role, status, joined_at, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,NOW(6),NOW(6),NOW(6))",
                UUID.randomUUID().toString(), teamId.toString(), uid.toString(), "팀원", role, "ACTIVE",
            )
        }
        jdbcTemplate.update(
            "INSERT INTO search_group_team (id, group_id, team_id, status, requested_by, requested_at, activated_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,NOW(6),NOW(6),NOW(6),NOW(6))",
            UUID.randomUUID().toString(), groupId.toString(), teamId.toString(), "ACTIVE", ownerId.toString(),
        )
    }

    private fun countFor(userId: UUID): Long =
        notificationRepository
            .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 50))
            .totalElements

    @Test
    fun `직접과 팀 두 경로를 모두 가진 사용자도 알림을 한 번만 받는다`() {
        publisher.notifyGroup(
            groupId = groupId,
            postId = postId,
            type = NotificationType.SEARCH_ENDED,
            excluding = setOf(ownerId),
            actorUserId = ownerId,
            body = null,
        )

        assertEquals(1L, countFor(dualUserId), "직접 ACTIVE + 팀 ACTIVE 인 사용자는 1건만 받아야 한다")
        assertEquals(1L, countFor(teamOnlyUserId))
        assertEquals(0L, countFor(ownerId), "excluding 에 담긴 보호자는 받지 않는다")
    }

    @Test
    fun `구조화 컨텍스트가 알림 행에 저장된다`() {
        publisher.notifyGroup(
            groupId = groupId,
            postId = postId,
            type = NotificationType.SEARCH_ENDED,
            excluding = setOf(ownerId),
            actorUserId = ownerId,
            body = null,
        )

        val row =
            notificationRepository
                .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(dualUserId, PageRequest.of(0, 1))
                .content
                .first()
        assertEquals(groupId, row.groupId)
        assertEquals(postId, row.postId)
        assertEquals(ownerId, row.actorUserId)
        assertEquals("/lost/$postId/group", row.link)
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.notification.NotificationTypeContractTest" \
  --tests "com.park.animal.searchgroup.GroupNotificationTemplateTest" \
  --tests "com.park.animal.searchgroup.GroupNotificationFanoutIT"
```

Expected: FAIL — 컴파일 에러. `Unresolved reference: GROUP_MEMBER_JOINED` (NotificationType 미확장), `Unresolved reference: GroupNotificationTemplates`, `Unresolved reference: GroupNotificationPublisher`, `Unresolved reference: groupId` (Notification 엔티티 미확장).

- [ ] **Step 3: 최소 구현**

`src/main/kotlin/com/park/animal/notification/entity/NotificationType.kt` (전체 교체)

```kotlin
package com.park.animal.notification.entity

/**
 * 알림 종류. `@Enumerated(STRING)` 으로 저장되므로 **상수 이름이 곧 DB 값**이다.
 *
 * 두 가지 불변식을 지킨다.
 * 1. 기존 상수는 삭제·개명하지 않는다. 운영 `notification` 행이 그 문자열을 들고 있고,
 *    코드가 모르는 문자열을 만나면 `list()` 매핑 시점에 목록 **전체**가 500 이 된다.
 * 2. phase 1 상수는 발행 시점이 아니라 **한 번에 전부** 선언한다. 롤링 배포 중 신버전이 만든
 *    문자열을 구버전 replica 가 읽어도 같은 이유로 전체가 500 이 되기 때문이다.
 *    그래서 `JOIN_POLICY_CHANGED` 처럼 phase 1 에서 발행하지 않는 상수도 여기 남는다.
 */
enum class NotificationType {
    /** 내 게시글에 목격 제보가 등록됨 */
    SIGHTING_REGISTERED,

    /** 내가 즐겨찾기한 게시글의 상태(SEARCHING/FOUND/SEEN)가 변경됨 */
    BOOKMARK_STATUS_CHANGED,

    /** 내가 구독한 지역에 신규 유기동물 등록됨 */
    ABANDONED_NEW_IN_REGION,

    // --- 함께 찾기 phase 1 (수색그룹) ---
    GROUP_MEMBER_JOINED,
    GROUP_JOIN_REQUESTED,
    GROUP_JOIN_APPROVED,
    GROUP_JOIN_REJECTED,
    GROUP_MEMBER_REMOVED,
    GROUP_MEMBER_BLOCKED,

    /** 선언만 한다. 설계 §8.3 은 정책 변경을 활동 기록으로만 남기라고 요구하므로 발행처가 없다. */
    JOIN_POLICY_CHANGED,

    // --- 함께 찾기 phase 1 (팀) ---
    TEAM_SUPPORT_REQUESTED,
    TEAM_SUPPORT_ACCEPTED,
    TEAM_SUPPORT_DECLINED,
    TEAM_SUPPORT_ENDED,
    TEAM_MEMBER_REQUESTED,
    TEAM_MEMBER_APPROVED,
    TEAM_MEMBER_REJECTED,
    TEAM_MEMBER_REMOVED,
    TEAM_LEADERSHIP_TRANSFERRED,

    /** 팀장이 팀을 보관 처리 — 그 팀의 모든 지원 연결이 함께 종료된다 (설계 §6.4, Task 8). */
    TEAM_ARCHIVED,

    // --- 함께 찾기 phase 1 (생명주기) ---
    SEARCH_ENDED,

    // --- 선언만, phase 2/3 에서 발행 ---
    SIGHTING_CREATED,
    CHAT_MENTIONED,
    GROUP_SYSTEM_EVENT,
}
```

`src/main/kotlin/com/park/animal/notification/entity/Notification.kt` (전체 교체)

```kotlin
package com.park.animal.notification.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLDelete
import org.hibernate.type.SqlTypes
import java.util.UUID

const val NOTIFICATION_TABLE_NAME = "notification"

@Entity
@Table(name = NOTIFICATION_TABLE_NAME)
@SQLDelete(sql = "UPDATE $NOTIFICATION_TABLE_NAME SET deleted_at = NOW() WHERE id = ?")
class Notification(
    // 컬럼명 user_id 는 개명하지 않는다: 파생 쿼리 4개 + markAllRead JPQL + V7 인덱스 2개가 참조한다.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false)
    val type: NotificationType,
    @Column(name = "title", nullable = false)
    val title: String,
    @Column(name = "body")
    val body: String? = null,
    @Column(name = "link")
    val link: String? = null,
    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,
    // --- 구조화 컨텍스트 (V12 ALTER, 설계 §9). 기존 행은 전부 NULL 이라 하위 호환. ---
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_user_id")
    val actorUserId: UUID? = null,
    @Column(name = "actor_name")
    val actorName: String? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "post_id")
    val postId: UUID? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id")
    val groupId: UUID? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "team_id")
    val teamId: UUID? = null,
) : BaseEntity() {
    fun markRead() {
        this.isRead = true
    }
}
```

`src/main/kotlin/com/park/animal/notification/dto/NotificationDtos.kt` (전체 교체)

```kotlin
package com.park.animal.notification.dto

import com.park.animal.notification.entity.Notification
import com.park.animal.notification.entity.NotificationType
import java.time.LocalDateTime
import java.util.UUID

/**
 * 필드 추가는 프론트에 하위호환이다(기존 필드는 이름·타입 그대로).
 * 프론트는 link 대신 postId/groupId/teamId 로 목적 화면을 조립할 수 있다.
 */
data class NotificationResponse(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String?,
    val link: String?,
    val isRead: Boolean,
    val createdAt: LocalDateTime,
    val actorName: String?,
    val postId: UUID?,
    val groupId: UUID?,
    val teamId: UUID?,
) {
    companion object {
        fun from(n: Notification): NotificationResponse =
            NotificationResponse(
                id = n.id,
                type = n.type,
                title = n.title,
                body = n.body,
                link = n.link,
                isRead = n.isRead,
                createdAt = n.createdAt,
                actorName = n.actorName,
                postId = n.postId,
                groupId = n.groupId,
                teamId = n.teamId,
            )
    }
}

data class UnreadCountResponse(val unread: Long)
```

`src/main/kotlin/com/park/animal/notification/NotificationService.kt:19-54` — 기존 `create`/`createMany` 본문을 위임으로 바꾸고 두 메서드를 추가한다(시그니처는 그대로라 PostService·SightingService·AbandonedAnimalSyncService 3곳 무변경).

```kotlin
    /**
     * 알림 1건 생성. 본인이 본인에게 보내는 알림은 [skipSelf] 가 true 면 무시 (같은 [userId]/[actorId]).
     *
     * 기존 호출부 계약 유지용 얇은 래퍼다. 신규 코드는 [createStructured] 를 쓴다.
     */
    @Transactional
    fun create(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        actorId: UUID? = null,
        skipSelf: Boolean = true,
    ): Notification? =
        createStructured(
            userId = userId,
            type = type,
            title = title,
            body = body,
            link = link,
            actorUserId = actorId,
            skipSelf = skipSelf,
        )

    /** 다건(즐겨찾기 fanout 등) 생성. 신규 코드는 [createStructuredMany] 를 쓴다. */
    @Transactional
    fun createMany(
        userIds: Collection<UUID>,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        excludeUserId: UUID? = null,
    ) = createStructuredMany(
        userIds = userIds,
        type = type,
        title = title,
        body = body,
        link = link,
        excludeUserId = excludeUserId,
    )

    /** 구조화 컨텍스트를 포함한 알림 1건 생성 (설계 §9). */
    @Transactional
    fun createStructured(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        actorUserId: UUID? = null,
        actorName: String? = null,
        postId: UUID? = null,
        groupId: UUID? = null,
        teamId: UUID? = null,
        skipSelf: Boolean = true,
    ): Notification? {
        if (skipSelf && actorUserId != null && actorUserId == userId) return null
        return notificationRepository.save(
            Notification(
                userId = userId,
                type = type,
                title = title,
                body = body,
                link = link,
                actorUserId = actorUserId,
                actorName = actorName,
                postId = postId,
                groupId = groupId,
                teamId = teamId,
            ),
        )
    }

    /**
     * 구조화 컨텍스트를 포함한 다건 생성. 수신자 중복 제거는 호출부(GroupNotificationPublisher)가
     * 이미 마쳤지만, 방어적으로 여기서도 Set 으로 좁힌다.
     */
    @Transactional
    fun createStructuredMany(
        userIds: Collection<UUID>,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        actorUserId: UUID? = null,
        actorName: String? = null,
        postId: UUID? = null,
        groupId: UUID? = null,
        teamId: UUID? = null,
        excludeUserId: UUID? = null,
    ) {
        val targets = if (excludeUserId == null) userIds.toSet() else userIds.toSet() - excludeUserId
        if (targets.isEmpty()) return
        notificationRepository.saveAll(
            targets.map { uid ->
                Notification(
                    userId = uid,
                    type = type,
                    title = title,
                    body = body,
                    link = link,
                    actorUserId = actorUserId,
                    actorName = actorName,
                    postId = postId,
                    groupId = groupId,
                    teamId = teamId,
                )
            },
        )
    }
```

`src/main/kotlin/com/park/animal/searchgroup/GroupNotificationPublisher.kt` (신규)

```kotlin
package com.park.animal.searchgroup

import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.access.GroupAccess
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.repository.TeamMemberRepository
import org.springframework.stereotype.Service
import org.woo.apm.log.log
import java.util.UUID

/**
 * 수색그룹 알림의 단일 발행 지점 (설계 §9).
 *
 * 책임 세 가지.
 * 1. 수신자 계산: [SearchGroupAccessResolver.effectiveMemberIds] 결과에서 `excluding` 을 뺀다.
 *    보호자 ∪ 직접 ACTIVE ∪ 팀 ACTIVE − 활성 차단이 이미 Set 이므로, 여러 경로로 권한을 가진
 *    사용자가 **한 번만** 받는 지점이 바로 여기다. 호출부는 수신자를 직접 모으지 않는다.
 * 2. 문구: [GroupNotificationTemplates] 표만 사용한다. 호출부가 임의 제목을 만들지 않는다.
 * 3. fan-out 상한: 한 이벤트당 [MAX_FANOUT_PER_EVENT] 건을 넘지 않는다.
 */
@Service
class GroupNotificationPublisher(
    private val notificationService: NotificationService,
    private val accessResolver: SearchGroupAccessResolver,
    private val teamMemberRepository: TeamMemberRepository,
) {
    /** 보호자 1인에게. 행위자가 곧 보호자면 [NotificationService.createStructured] 의 skipSelf 가 걸러낸다. */
    fun notifyOwner(
        access: GroupAccess,
        type: NotificationType,
        actorUserId: UUID,
        actorName: String?,
        body: String?,
    ) {
        notificationService.createStructured(
            userId = access.ownerUserId,
            type = type,
            title = GroupNotificationTemplates.titleOf(type, actorName),
            body = body ?: GroupNotificationTemplates.bodyOf(type),
            link = GroupNotificationTemplates.linkOf(type, access.postId, access.groupId, null),
            actorUserId = actorUserId,
            actorName = actorName,
            postId = access.postId,
            groupId = access.groupId,
            skipSelf = true,
        )
    }

    /**
     * 지정 사용자 1인에게. 내보내기·차단처럼 행위자를 숨겨야 하는 이벤트는 `actorUserId = null` 로
     * 호출한다 — 행 자체에 행위자를 남기지 않는다(설계 §6.3).
     */
    fun notifyUser(
        userId: UUID,
        type: NotificationType,
        actorUserId: UUID?,
        postId: UUID?,
        groupId: UUID?,
        teamId: UUID?,
        body: String?,
    ) {
        notificationService.createStructured(
            userId = userId,
            type = type,
            title = GroupNotificationTemplates.titleOf(type, null),
            body = body ?: GroupNotificationTemplates.bodyOf(type),
            link = GroupNotificationTemplates.linkOf(type, postId, groupId, teamId),
            actorUserId = actorUserId,
            actorName = null,
            postId = postId,
            groupId = groupId,
            teamId = teamId,
            skipSelf = true,
        )
    }

    /** 그룹의 유효 참여자 전체에게. 중복 제거는 effectiveMemberIds 가 Set 이라는 사실로 보장된다. */
    fun notifyGroup(
        groupId: UUID,
        postId: UUID,
        type: NotificationType,
        excluding: Set<UUID>,
        actorUserId: UUID?,
        body: String?,
    ) {
        val recipients = accessResolver.effectiveMemberIds(groupId) - excluding
        fanOut(
            recipients = recipients,
            type = type,
            actorUserId = actorUserId,
            actorName = null,
            postId = postId,
            groupId = groupId,
            teamId = null,
            body = body,
        )
    }

    /** 특정 팀의 ACTIVE 팀원 전체에게 (팀 지원 수락·종료). */
    fun notifyTeamMembers(
        teamId: UUID,
        groupId: UUID?,
        postId: UUID?,
        type: NotificationType,
        excluding: Set<UUID>,
        actorUserId: UUID?,
        body: String?,
    ) {
        val recipients =
            teamMemberRepository
                .findAllByTeamIdAndStatus(teamId, TeamMemberStatus.ACTIVE)
                .map { it.userId }
                .toSet() - excluding
        fanOut(
            recipients = recipients,
            type = type,
            actorUserId = actorUserId,
            actorName = null,
            postId = postId,
            groupId = groupId,
            teamId = teamId,
            body = body,
        )
    }

    private fun fanOut(
        recipients: Set<UUID>,
        type: NotificationType,
        actorUserId: UUID?,
        actorName: String?,
        postId: UUID?,
        groupId: UUID?,
        teamId: UUID?,
        body: String?,
    ) {
        if (recipients.isEmpty()) return
        val ordered = recipients.sortedBy { it.toString() }
        val targets =
            if (ordered.size > MAX_FANOUT_PER_EVENT) {
                log().warn(
                    "group notification fan-out capped: type=$type groupId=$groupId teamId=$teamId " +
                        "recipients=${ordered.size} cap=$MAX_FANOUT_PER_EVENT",
                )
                ordered.take(MAX_FANOUT_PER_EVENT)
            } else {
                ordered
            }
        notificationService.createStructuredMany(
            userIds = targets,
            type = type,
            title = GroupNotificationTemplates.titleOf(type, actorName),
            body = body ?: GroupNotificationTemplates.bodyOf(type),
            link = GroupNotificationTemplates.linkOf(type, postId, groupId, teamId),
            actorUserId = actorUserId,
            actorName = actorName,
            postId = postId,
            groupId = groupId,
            teamId = teamId,
        )
    }

    companion object {
        /** 한 이벤트가 만들 수 있는 알림 행 수 상한. 초과분은 버리고 WARN 로그를 남긴다. */
        const val MAX_FANOUT_PER_EVENT = 500
    }
}

/**
 * phase 1 알림 문구 표.
 *
 * 어휘는 설계 §2 만 쓴다: 수색그룹 / 보호자 / 팀장 / 팀원 / 함께 찾기 / 확인할 요청 / 수색 종료 /
 * 우리 팀의 지원 종료. `admin` 은 쓰지 않는다.
 * 좌표·전화번호·차단 사유는 제목과 본문에 넣지 않는다(설계 §9, §16.5).
 *
 * `GROUP_MEMBER_REMOVED` / `GROUP_MEMBER_BLOCKED` 는 actorName 을 **사용하지 않는다**.
 * 누가 왜 그랬는지는 대상자에게 공개하지 않는 것이 설계 §6.3 이다.
 *
 * `JOIN_POLICY_CHANGED` 는 **여기에 없다**. 설계 §8.3 이 요구하는 것은 그룹 활동 기록
 * (`SearchGroupEventType.JOIN_POLICY_CHANGED`)뿐이고 알림 수신자 표에는 그 항목이 없다.
 * NotificationType 상수는 롤링 배포 안전 때문에 남아 있지만 문구도 발행처도 없다.
 */
object GroupNotificationTemplates {
    val PHASE1_TYPES: Set<NotificationType> =
        setOf(
            NotificationType.GROUP_MEMBER_JOINED,
            NotificationType.GROUP_JOIN_REQUESTED,
            NotificationType.GROUP_JOIN_APPROVED,
            NotificationType.GROUP_JOIN_REJECTED,
            NotificationType.GROUP_MEMBER_REMOVED,
            NotificationType.GROUP_MEMBER_BLOCKED,
            NotificationType.TEAM_SUPPORT_REQUESTED,
            NotificationType.TEAM_SUPPORT_ACCEPTED,
            NotificationType.TEAM_SUPPORT_DECLINED,
            NotificationType.TEAM_SUPPORT_ENDED,
            NotificationType.TEAM_MEMBER_REQUESTED,
            NotificationType.TEAM_MEMBER_APPROVED,
            NotificationType.TEAM_MEMBER_REJECTED,
            NotificationType.TEAM_MEMBER_REMOVED,
            NotificationType.TEAM_LEADERSHIP_TRANSFERRED,
            NotificationType.TEAM_ARCHIVED,
            NotificationType.SEARCH_ENDED,
        )

    fun titleOf(
        type: NotificationType,
        actorName: String?,
    ): String =
        when (type) {
            NotificationType.GROUP_MEMBER_JOINED -> "${who(actorName)}님이 함께 찾기에 참여했어요"
            NotificationType.GROUP_JOIN_REQUESTED -> "${who(actorName)}님이 참여를 신청했어요"
            NotificationType.GROUP_JOIN_APPROVED -> "함께 찾기 참여가 승인됐어요"
            NotificationType.GROUP_JOIN_REJECTED -> "함께 찾기 참여 신청이 받아들여지지 않았어요"
            NotificationType.GROUP_MEMBER_REMOVED -> "수색그룹 참여가 종료됐어요"
            NotificationType.GROUP_MEMBER_BLOCKED -> "수색그룹을 더 이상 이용할 수 없어요"
            NotificationType.TEAM_SUPPORT_REQUESTED -> "${who(actorName)}님이 팀 지원을 요청했어요"
            NotificationType.TEAM_SUPPORT_ACCEPTED -> "팀 지원이 시작됐어요"
            NotificationType.TEAM_SUPPORT_DECLINED -> "팀 지원 요청이 받아들여지지 않았어요"
            NotificationType.TEAM_SUPPORT_ENDED -> "우리 팀의 지원이 종료됐어요"
            NotificationType.TEAM_MEMBER_REQUESTED -> "${who(actorName)}님이 팀 참여를 신청했어요"
            NotificationType.TEAM_MEMBER_APPROVED -> "팀 참여가 승인됐어요"
            NotificationType.TEAM_MEMBER_REJECTED -> "팀 참여 신청이 받아들여지지 않았어요"
            NotificationType.TEAM_MEMBER_REMOVED -> "팀 참여가 종료됐어요"
            NotificationType.TEAM_LEADERSHIP_TRANSFERRED -> "팀장이 변경됐어요"
            NotificationType.TEAM_ARCHIVED -> "팀이 보관되었어요"
            NotificationType.SEARCH_ENDED -> "수색 종료 안내"
            else -> "새로운 알림이 있어요"
        }

    fun bodyOf(type: NotificationType): String? =
        when (type) {
            NotificationType.GROUP_MEMBER_JOINED -> "수색그룹에서 함께 찾는 이웃을 확인해 보세요."
            NotificationType.GROUP_JOIN_REQUESTED -> "확인할 요청에서 승인하거나 거절할 수 있어요."
            NotificationType.GROUP_JOIN_APPROVED -> "이제 수색그룹에서 함께 찾을 수 있어요."
            NotificationType.GROUP_JOIN_REJECTED -> "다른 실종 소식에서도 함께 찾을 수 있어요."
            NotificationType.GROUP_MEMBER_REMOVED -> "이 수색그룹의 참여가 종료되었어요."
            NotificationType.GROUP_MEMBER_BLOCKED -> "이 수색그룹에서는 함께 찾기를 이용할 수 없어요."
            NotificationType.TEAM_SUPPORT_REQUESTED -> "확인할 요청에서 수락하거나 거절할 수 있어요."
            NotificationType.TEAM_SUPPORT_ACCEPTED -> "이제 팀원들이 이 수색그룹에서 함께 찾을 수 있어요."
            NotificationType.TEAM_SUPPORT_DECLINED -> "다른 실종 소식에도 팀 지원을 요청할 수 있어요."
            NotificationType.TEAM_SUPPORT_ENDED -> "이 수색그룹에 대한 우리 팀의 지원이 종료되었어요."
            NotificationType.TEAM_MEMBER_REQUESTED -> "확인할 요청에서 승인하거나 거절할 수 있어요."
            NotificationType.TEAM_MEMBER_APPROVED -> "이제 팀원으로 함께 찾을 수 있어요."
            NotificationType.TEAM_MEMBER_REJECTED -> "다른 팀에도 참여를 신청할 수 있어요."
            NotificationType.TEAM_MEMBER_REMOVED -> "이 팀의 참여가 종료되었어요."
            NotificationType.TEAM_LEADERSHIP_TRANSFERRED -> "팀 화면에서 새로운 팀장을 확인해 보세요."
            NotificationType.TEAM_ARCHIVED -> "이 팀의 활동이 종료되어 함께 찾기 지원도 모두 종료되었어요."
            NotificationType.SEARCH_ENDED -> "보호자가 수색 종료를 선택했어요. 이전 기록만 확인할 수 있어요."
            else -> null
        }

    /**
     * 이동 대상.
     * - 승인 대기(요청) 3종 → `/profile` (확인할 요청 허브)
     * - 그룹 접근 권한이 남아 있는 이벤트 → `/lost/{postId}/group`
     * - 권한이 사라진 이벤트(거절·내보내기·차단) → 공개 상세 `/lost/{postId}`
     * - 팀 이벤트 → `/teams/{teamId}`
     */
    fun linkOf(
        type: NotificationType,
        postId: UUID?,
        groupId: UUID?,
        teamId: UUID?,
    ): String? =
        when (type) {
            NotificationType.GROUP_JOIN_REQUESTED,
            NotificationType.TEAM_MEMBER_REQUESTED,
            NotificationType.TEAM_SUPPORT_REQUESTED,
            -> "/profile"

            NotificationType.GROUP_MEMBER_JOINED,
            NotificationType.GROUP_JOIN_APPROVED,
            NotificationType.TEAM_SUPPORT_ACCEPTED,
            NotificationType.SEARCH_ENDED,
            -> postId?.let { "/lost/$it/group" }

            NotificationType.GROUP_JOIN_REJECTED,
            NotificationType.GROUP_MEMBER_REMOVED,
            NotificationType.GROUP_MEMBER_BLOCKED,
            -> postId?.let { "/lost/$it" }

            NotificationType.TEAM_SUPPORT_DECLINED,
            NotificationType.TEAM_SUPPORT_ENDED,
            -> teamId?.let { "/teams/$it" } ?: postId?.let { "/lost/$it/group" }

            NotificationType.TEAM_MEMBER_APPROVED,
            NotificationType.TEAM_MEMBER_REJECTED,
            NotificationType.TEAM_MEMBER_REMOVED,
            NotificationType.TEAM_LEADERSHIP_TRANSFERRED,
            NotificationType.TEAM_ARCHIVED,
            -> teamId?.let { "/teams/$it" }

            else -> null
        }

    private fun who(actorName: String?): String = actorName?.takeIf { it.isNotBlank() } ?: "이웃"
}
```

`src/main/resources/application.yml:28-36` — `spring.jpa.properties.hibernate` 블록을 다음으로 교체한다. 알림 fan-out 이 최대 500행을 `saveAll` 하는데, id 가 애플리케이션 생성 UUID(BaseEntity)라 Hibernate 가 INSERT 전에 id 를 알고 있어 JDBC 배치가 실제로 걸린다.

```yaml
    properties:
      hibernate:
        default_batch_fetch_size: 1000
        dialect: org.hibernate.dialect.MySQLDialect
        # 알림 fan-out(최대 500행 saveAll) 배치 인서트. id 가 애플리케이션 생성 UUID 라
        # IDENTITY 생성기와 달리 배치가 실제로 적용된다.
        order_inserts: true
        jdbc:
          batch_size: 50
        # Hibernate 6 은 MySQL 에서 기본적으로 enum → native ENUM 컬럼을 기대한다.
        # 우리 마이그레이션은 VARCHAR 를 사용하므로 호환 위해 VARCHAR 로 고정.
        type:
          preferred_enum_jdbc_type: VARCHAR
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.notification.NotificationTypeContractTest" \
  --tests "com.park.animal.searchgroup.GroupNotificationTemplateTest" \
  --tests "com.park.animal.searchgroup.GroupNotificationFanoutIT"
```

Expected: PASS — 9개 테스트 전부 통과. 특히 `직접과 팀 두 경로를 모두 가진 사용자도 알림을 한 번만 받는다` 가 1L 을, `참여 정책 변경은 알림을 발행하지 않는다` 가 PHASE1_TYPES 17종을 확인.

- [ ] **Step 5: `endSearch` 와 즐겨찾기 fan-out 을 구조화 알림으로 이관**

Task 4 의 `SearchLifecycleService.endSearch` 는 레거시 `notificationService.createMany(...)` 를 쓰기 때문에 `group_id`/`post_id`/`actor_user_id` 가 전부 NULL 로 저장된다. Task 11 의 `SearchGroupConcurrencyIT`(`WHERE group_id = ? AND type = 'SEARCH_ENDED'`)와 `SearchGroupPrivacyIT` 가 항상 0건을 받아 실패한다. 이 스텝이 그 경로를 [GroupNotificationPublisher] 로 옮겨 문구 표를 단일화한다.

**5-1. `src/main/kotlin/com/park/animal/searchgroup/SearchLifecycleService.kt` — import 정리**

지운다(이 클래스에서 더 이상 쓰지 않는다):

```kotlin
import com.park.animal.notification.NotificationService
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
```

`GroupNotificationPublisher` 는 같은 패키지(`com.park.animal.searchgroup`)라 import 가 필요 없다. `NotificationType` import 는 그대로 둔다.

**5-2. 생성자 교체** — 아래 두 줄을 지우고 한 줄을 넣는다.

```kotlin
// 삭제
    private val accessResolver: SearchGroupAccessResolver,
    private val notificationService: NotificationService,

// 추가 (searchGroupEventRecorder 다음 줄)
    private val notificationPublisher: GroupNotificationPublisher,
```

**5-3. `endSearch` 안의 수신자 선계산 삭제** — 아래 두 줄을 지운다.

```kotlin
        // 상태를 바꾸기 전에 계산한다 — 접근 판정은 native SQL 이라 auto-flush 되지 않는다.
        val recipients = accessResolver.effectiveMemberIds(groupId)
```

근거: Task 3 의 `EFFECTIVE_MEMBER_SQL` 은 `search_group.status` 도 `post.missing_animal_status` 도 보지 않는다(보호자 ∪ 직접 ACTIVE ∪ 팀 ACTIVE − 활성 차단). 보관 전이 뒤에 계산해도 같은 집합이며, 수신자 계산 책임이 publisher 로 옮겨간다.

**5-4. fan-out 교체** — `endSearch` 말미의 `notificationService.createMany(...)` 블록 전체를 아래로 바꾼다.

```kotlin
        // 제목·본문·링크는 GroupNotificationTemplates 표 하나만 쓴다(하드코딩 문구 금지).
        // 이 호출이 group_id / post_id / actor_user_id 를 채운다 — Task 11 의 알림 검증이 여기에 의존한다.
        notificationPublisher.notifyGroup(
            groupId = groupId,
            postId = postId,
            type = NotificationType.SEARCH_ENDED,
            excluding = setOf(actorUserId),
            actorUserId = actorUserId,
            body = null,
        )
```

`post` 지역 변수는 바로 위의 `post.updateStatus(MissingAnimalStatus.FOUND)` 에서 계속 쓰이므로 남겨 둔다.

**5-5. `src/main/kotlin/com/park/animal/post/PostService.kt` — 즐겨찾기 fan-out 교체**

`notifyBookmarkers` 안의 `notificationService.createMany(...)` 를 아래로 바꾼다. 알림 행에 `post_id` 가 남아야 프론트가 link 문자열 파싱 없이 목적 화면을 조립할 수 있다(설계 §9).

```kotlin
        notificationService.createStructuredMany(
            userIds = targets,
            type = NotificationType.BOOKMARK_STATUS_CHANGED,
            title = "즐겨찾기 게시글 상태가 변경됐어요",
            body = "${post.title} → ${labelOf(status)}",
            link = "/lost/${post.id}",
            actorUserId = actorUserId,
            postId = post.id,
            excludeUserId = actorUserId,
        )
```

**5-6. `src/test/kotlin/com/park/animal/searchgroup/SearchLifecycleIT.kt` — `@Import` 보강**

`SearchLifecycleService` 가 이제 `GroupNotificationPublisher` 를 요구하고, `SearchGroupAccessResolver` 는 R12 로 `MeterRegistry` 를 요구한다. 두 빈이 없으면 컨텍스트 로딩 단계에서 실패한다. `@Import` 목록에 두 줄을 추가한다(같은 패키지라 import 문은 필요 없다).

```kotlin
@Import(
    JpaConfig::class,
    StubMultimediaConfig::class,
    SearchGroupTestMetricsConfig::class,
    PostNearbyRepository::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
    BookmarkService::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    SearchLifecycleService::class,
    PostWriteService::class,
    PostService::class,
)
```

- [ ] **Step 6: 이관 후 회귀 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.searchgroup.GroupNotificationFanoutIT" \
  --tests "com.park.animal.searchgroup.SearchLifecycleIT" \
  --tests "com.park.animal.searchgroup.GroupNotificationTemplateTest"
```

Expected: PASS — `SearchLifecycleIT` 의 `수색 종료를 두 번 호출해도 알림은 한 번만 간다` 와 `즐겨찾기와 참여를 겸한 사용자는 수색 종료 알림을 정확히 한 건만 받는다` 가 그대로 통과하고, 알림 제목이 `수색 종료 안내`(템플릿 표 값)로 바뀐다. 이 시점부터 `SEARCH_ENDED` 알림 행의 `group_id`/`post_id`/`actor_user_id` 가 채워진다.

- [ ] **Step 7: 커밋**

```bash
cd /Users/park/Desktop/project/animal && git add \
  src/main/kotlin/com/park/animal/notification \
  src/main/kotlin/com/park/animal/searchgroup/GroupNotificationPublisher.kt \
  src/main/kotlin/com/park/animal/searchgroup/SearchLifecycleService.kt \
  src/main/kotlin/com/park/animal/post/PostService.kt \
  src/main/resources/application.yml \
  src/test/kotlin/com/park/animal/searchgroup/SearchGroupTestMetricsConfig.kt \
  src/test/kotlin/com/park/animal/searchgroup/SearchLifecycleIT.kt \
  src/test/kotlin/com/park/animal/notification/NotificationTypeContractTest.kt \
  src/test/kotlin/com/park/animal/searchgroup/GroupNotificationTemplateTest.kt \
  src/test/kotlin/com/park/animal/searchgroup/GroupNotificationFanoutIT.kt && \
git commit -m "feat(search-group): 구조화 알림 컨텍스트와 GroupNotificationPublisher 추가"
```

---

### Task 6: 직접 참여 lifecycle (가입·승인·거절·탈퇴·내보내기·정책 변경)

**Files:**
- Create: `src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupMembershipDtos.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupMembershipService.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupMembershipController.kt`
- Modify: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupService.kt` (Task 4 산출물 — 생성자에 `SearchGroupMembershipService` 추가 + `updateJoinPolicy` 메서드 추가)
- Modify: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupController.kt` (Task 4 산출물 — `PATCH /search-groups/{groupId}/join-policy` 핸들러 추가)
- Test: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupMembershipIT.kt`

**리포지토리는 만들지도 고치지도 않는다.** Task 2 가 최종 시그니처로 이미 정의했고, 여기서 "전체 교체" 를 하면 `SearchGroupMigrationIT` 가 부르는 메서드가 사라져 테스트 컴파일 전체가 깨진다.

**Interfaces:**

- Consumes:
  - Task 1: `ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP`(404), `ErrorCode.SEARCH_GROUP_ACCESS_DENIED`(403), `ErrorCode.SEARCH_GROUP_STATE_CONFLICT`(409), `ErrorCode.SEARCH_ALREADY_ENDED`(410), `ErrorCode.MISSING_PARAMETER`(400)
  - Task 2 엔티티 (이 생성자 시그니처 전제):
    ```kotlin
    class SearchGroupMember(
        val groupId: UUID, val userId: UUID, val userName: String? = null,
        var status: SearchGroupMemberStatus,
        var joinedAt: LocalDateTime? = null, var requestedAt: LocalDateTime? = null,
        var decidedAt: LocalDateTime? = null, var decidedBy: UUID? = null,
    ) : BaseEntity()
    ```
  - Task 2 리포지토리 (**이 시그니처를 그대로 호출한다. 재정의하지 않는다**):
    - `SearchGroupMemberRepository.findByGroupIdAndUserId(groupId: UUID, userId: UUID): SearchGroupMember?`
    - `SearchGroupMemberRepository.findByIdAndGroupId(id: UUID, groupId: UUID): SearchGroupMember?`
    - `SearchGroupMemberRepository.findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID): List<SearchGroupMember>`
    - `SearchGroupMemberRepository.findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(groupId: UUID, status: SearchGroupMemberStatus): List<SearchGroupMember>`
    - `SearchGroupMemberRepository.transition(membershipId: UUID, groupId: UUID, expected: SearchGroupMemberStatus, next: SearchGroupMemberStatus, decidedBy: UUID?, occurredAt: LocalDateTime): Int` — `WHERE m.id = :membershipId AND m.groupId = :groupId AND m.status = :expected`, `status`/`decidedAt`/`decidedBy`/`updatedAt` 만 갱신하고 `joinedAt`·`requestedAt` 은 건드리지 않는다.
    - `SearchGroupMemberRepository.activate(membershipId: UUID, groupId: UUID, expected: SearchGroupMemberStatus, decidedBy: UUID?, occurredAt: LocalDateTime): Int` — 같은 WHERE 에 `status = ACTIVE`, `joinedAt = :occurredAt` 까지 세팅한다.
    - `SearchGroupRepository.updateJoinPolicyFrom(groupId: UUID, expected: JoinPolicy, next: JoinPolicy, activeStatus: SearchGroupStatus, now: LocalDateTime): Int`
  - Task 3: `SearchGroupAccessResolver.requireVisible/requireRead/requireOwner`, `GroupAccess`(계약 §7 전체 필드)
  - Task 4: `SearchGroupEventRecorder.record(groupId: UUID, type: SearchGroupEventType, actorId: UUID?, targetId: UUID?, detail: String?)`, `@Service class SearchGroupService(private val ...)` 와 `fun getDetail(groupId: UUID, viewerId: UUID): SearchGroupDetailResponse`, `@RestController class SearchGroupController(private val searchGroupService: SearchGroupService)`
  - Task 5: `GroupNotificationPublisher.notifyOwner / notifyUser`, 테스트 전용 `SearchGroupTestMetricsConfig`
- Produces (Task 7·10·11 이 의존):
  - `SearchGroupMembershipService.join(groupId: UUID, userId: UUID, userName: String?): JoinSearchGroupResponse`
  - `SearchGroupMembershipService.approve(groupId: UUID, membershipId: UUID, ownerUserId: UUID): SearchGroupMembershipResponse`
  - `SearchGroupMembershipService.reject(groupId: UUID, membershipId: UUID, ownerUserId: UUID): SearchGroupMembershipResponse`
  - `SearchGroupMembershipService.remove(groupId: UUID, membershipId: UUID, ownerUserId: UUID): SearchGroupMembershipResponse`
  - `SearchGroupMembershipService.leaveMe(groupId: UUID, userId: UUID): SearchGroupMembershipResponse`
  - `SearchGroupMembershipService.list(groupId: UUID, userId: UUID, status: SearchGroupMemberStatus? = null): List<SearchGroupMembershipResponse>`
  - `SearchGroupMembershipService.updateJoinPolicy(groupId: UUID, ownerUserId: UUID, joinPolicy: JoinPolicy)`
  - `SearchGroupService.updateJoinPolicy(groupId: UUID, ownerUserId: UUID, joinPolicy: JoinPolicy): SearchGroupDetailResponse`
  - DTO `SearchGroupMembershipResponse`, `JoinSearchGroupResponse`, `UpdateJoinPolicyRequest`
  - 메트릭 `fmp.searchgroup.join.requested` (태그 없음, 설계 §20)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupMembershipIT.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.notification.repository.NotificationRepository
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 직접 참여 lifecycle (설계 §8, §15, §16.1).
 * 커밋된 상태를 확인해야 하므로 테스트 트랜잭션 래핑을 끈다(NOT_SUPPORTED).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupTestMetricsConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
    SearchGroupMembershipService::class,
)
@Testcontainers
class SearchGroupMembershipIT {
    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var membershipService: SearchGroupMembershipService

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var ownerId: UUID
    private lateinit var joinerId: UUID
    private lateinit var postId: UUID
    private lateinit var groupId: UUID

    @BeforeEach
    fun seed() {
        listOf(
            "notification", "search_group_event", "search_group_user_block", "search_group_member",
            "search_group_team", "team_member", "team", "search_group", "sighting", "post_bookmark", "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }

        ownerId = UUID.randomUUID()
        joinerId = UUID.randomUUID()
        postId = newPost(ownerId)
        groupId = newGroup(postId, JoinPolicy.OPEN)
    }

    private fun newPost(authorId: UUID): UUID =
        postRepository
            .save(
                Post(
                    authorId = authorId,
                    authorName = "보호자",
                    title = "말티즈를 찾습니다",
                    phoneNum = "010-0000-0000",
                    time = LocalDateTime.now(),
                    place = "서울 강남구 역삼동",
                    gender = "남아",
                    gratuity = 0,
                    description = "겁이 많아요.",
                    lat = 37.5012,
                    lng = 127.0396,
                    openChatUrl = null,
                    missingAnimalStatus = MissingAnimalStatus.SEARCHING,
                    animalType = AnimalType.DOG,
                ),
            ).id

    private fun newGroup(
        forPostId: UUID,
        policy: JoinPolicy,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            id.toString(), forPostId.toString(), policy.name, "ACTIVE",
        )
        return id
    }

    private fun blockUser(
        forGroupId: UUID,
        userId: UUID,
    ) {
        jdbcTemplate.update(
            "INSERT INTO search_group_user_block (id, group_id, user_id, blocked_by, blocked_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,NOW(6),NOW(6),NOW(6))",
            UUID.randomUUID().toString(), forGroupId.toString(), userId.toString(), ownerId.toString(),
        )
    }

    private fun memberRowCount(userId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM search_group_member WHERE group_id = ? AND user_id = ?",
            Long::class.java,
            groupId.toString(),
            userId.toString(),
        )!!

    private fun notificationCount(userId: UUID): Long =
        notificationRepository
            .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 50))
            .totalElements

    @Test
    fun `OPEN 정책은 즉시 참여시키고 보호자에게 알림 1건을 남긴다`() {
        val res = membershipService.join(groupId, joinerId, "이참여")

        assertEquals(SearchGroupMemberStatus.ACTIVE, res.membership.status)
        assertTrue(!res.approvalPending)
        assertEquals(1L, notificationCount(ownerId))
        assertEquals(0L, notificationCount(joinerId))
    }

    @Test
    fun `APPROVAL_REQUIRED 는 신청 승인 거절 양측 알림을 남긴다`() {
        val approvalGroupId = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)

        val requested = membershipService.join(approvalGroupId, joinerId, "이참여")
        assertEquals(SearchGroupMemberStatus.PENDING, requested.membership.status)
        assertTrue(requested.approvalPending)
        assertEquals(1L, notificationCount(ownerId), "보호자에게 참여 신청 알림")

        val approved = membershipService.approve(approvalGroupId, requested.membership.membershipId, ownerId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, approved.status)
        assertEquals(1L, notificationCount(joinerId), "신청자에게 승인 알림")

        val rejecterId = UUID.randomUUID()
        val pending = membershipService.join(approvalGroupId, rejecterId, "거절대상")
        val rejected = membershipService.reject(approvalGroupId, pending.membership.membershipId, ownerId)
        assertEquals(SearchGroupMemberStatus.REJECTED, rejected.status)
        assertEquals(1L, notificationCount(rejecterId), "신청자에게 거절 알림")
    }

    @Test
    fun `중복 클릭 3회에도 ACTIVE 행 1개 알림 1건만 남는다`() {
        val first = membershipService.join(groupId, joinerId, "이참여")
        val second = membershipService.join(groupId, joinerId, "이참여")
        val third = membershipService.join(groupId, joinerId, "이참여")

        assertEquals(first.membership.membershipId, second.membership.membershipId)
        assertEquals(first.membership.membershipId, third.membership.membershipId)
        assertEquals(1L, memberRowCount(joinerId))
        assertEquals(1L, notificationCount(ownerId))
    }

    @Test
    fun `LEFT 후 재가입은 새 행이 아니라 같은 행의 상태 전이다`() {
        val first = membershipService.join(groupId, joinerId, "이참여")
        membershipService.leaveMe(groupId, joinerId)
        val again = membershipService.join(groupId, joinerId, "이참여")

        assertEquals(first.membership.membershipId, again.membership.membershipId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, again.membership.status)
        assertEquals(1L, memberRowCount(joinerId))
    }

    @Test
    fun `APPROVAL 에서 OPEN 으로 바뀐 뒤 PENDING 사용자가 다시 누르면 ACTIVE 가 된다`() {
        val gid = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)
        val pending = membershipService.join(gid, joinerId, "이참여")
        assertEquals(SearchGroupMemberStatus.PENDING, pending.membership.status)

        membershipService.updateJoinPolicy(gid, ownerId, JoinPolicy.OPEN)
        val activated = membershipService.join(gid, joinerId, "이참여")

        assertEquals(SearchGroupMemberStatus.ACTIVE, activated.membership.status)
        assertEquals(pending.membership.membershipId, activated.membership.membershipId)
    }

    @Test
    fun `정책 변경은 기존 ACTIVE 를 유지하고 PENDING 을 자동 승인하지 않는다`() {
        val gid = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)
        val pendingUser = UUID.randomUUID()
        val pending = membershipService.join(gid, pendingUser, "대기자")

        membershipService.updateJoinPolicy(gid, ownerId, JoinPolicy.OPEN)
        val activeUser = UUID.randomUUID()
        membershipService.join(gid, activeUser, "즉시참여")
        membershipService.updateJoinPolicy(gid, ownerId, JoinPolicy.APPROVAL_REQUIRED)

        val all = membershipService.list(gid, ownerId, null)
        assertEquals(
            SearchGroupMemberStatus.PENDING,
            all.first { it.membershipId == pending.membership.membershipId }.status,
        )
        assertEquals(
            SearchGroupMemberStatus.ACTIVE,
            all.first { it.userId == activeUser }.status,
        )
    }

    @Test
    fun `다른 그룹의 membershipId 로 승인하면 404 다 (IDOR)`() {
        val otherOwnerId = UUID.randomUUID()
        val otherGroupId = newGroup(newPost(otherOwnerId), JoinPolicy.APPROVAL_REQUIRED)
        val victim = membershipService.join(otherGroupId, joinerId, "이참여")

        val e =
            assertFailsWith<BusinessException> {
                membershipService.approve(groupId, victim.membership.membershipId, ownerId)
            }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP, e.errorCode)
    }

    @Test
    fun `차단된 사용자는 가입할 수 없다`() {
        blockUser(groupId, joinerId)

        val e = assertFailsWith<BusinessException> { membershipService.join(groupId, joinerId, "이참여") }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
        assertEquals(0L, memberRowCount(joinerId))
    }

    @Test
    fun `차단된 사용자는 개인 참여 종료도 403 이다`() {
        // 설계 §6.3 — 차단이 활성인 동안에는 모든 접근을 거부한다. 탈퇴도 예외가 아니다.
        membershipService.join(groupId, joinerId, "이참여")
        blockUser(groupId, joinerId)

        val e = assertFailsWith<BusinessException> { membershipService.leaveMe(groupId, joinerId) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    @Test
    fun `팀 경유 사용자는 개인 참여 종료로 나갈 수 없다`() {
        val e = assertFailsWith<BusinessException> { membershipService.leaveMe(groupId, UUID.randomUUID()) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP, e.errorCode)
    }

    @Test
    fun `이미 종료한 참여를 다시 종료하면 409 다`() {
        membershipService.join(groupId, joinerId, "이참여")
        membershipService.leaveMe(groupId, joinerId)

        val e = assertFailsWith<BusinessException> { membershipService.leaveMe(groupId, joinerId) }
        assertEquals(ErrorCode.SEARCH_GROUP_STATE_CONFLICT, e.errorCode)
    }

    @Test
    fun `보호자가 내보내면 REMOVED 가 되고 대상자에게 알림이 간다`() {
        val joined = membershipService.join(groupId, joinerId, "이참여")

        val removed = membershipService.remove(groupId, joined.membership.membershipId, ownerId)

        assertEquals(SearchGroupMemberStatus.REMOVED, removed.status)
        assertEquals(1L, notificationCount(joinerId))
    }

    @Test
    fun `참여자 목록에서 참여자는 ACTIVE 만 보고 보호자는 PENDING 까지 본다`() {
        val gid = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)
        val activeUser = UUID.randomUUID()
        val pendingUser = UUID.randomUUID()
        val active = membershipService.join(gid, activeUser, "참여자")
        membershipService.approve(gid, active.membership.membershipId, ownerId)
        membershipService.join(gid, pendingUser, "대기자")

        assertEquals(2, membershipService.list(gid, ownerId, null).size)
        assertEquals(1, membershipService.list(gid, activeUser, null).size)
        assertEquals(
            SearchGroupMemberStatus.ACTIVE,
            membershipService.list(gid, activeUser, SearchGroupMemberStatus.PENDING).single().status,
            "참여자가 status 파라미터로 PENDING 을 요청해도 ACTIVE 목록만 받는다",
        )
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.searchgroup.SearchGroupMembershipIT"
```

Expected: FAIL — 컴파일 에러 `Unresolved reference: SearchGroupMembershipService`, `Unresolved reference: JoinSearchGroupResponse`.

- [ ] **Step 3: 최소 구현**

`src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupMembershipDtos.kt` (신규)

```kotlin
package com.park.animal.searchgroup.dto

import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import java.time.LocalDateTime
import java.util.UUID

/**
 * 직접 참여 멤버십 1건. 차단 여부는 담지 않는다 — 차단 사실은 응답으로 알 수 없어야 한다(설계 §16.5).
 */
data class SearchGroupMembershipResponse(
    val membershipId: UUID,
    val groupId: UUID,
    val userId: UUID,
    val userName: String?,
    val status: SearchGroupMemberStatus,
    val requestedAt: LocalDateTime?,
    val joinedAt: LocalDateTime?,
    val decidedAt: LocalDateTime?,
) {
    companion object {
        fun from(m: SearchGroupMember): SearchGroupMembershipResponse =
            SearchGroupMembershipResponse(
                membershipId = m.id,
                groupId = m.groupId,
                userId = m.userId,
                userName = m.userName,
                status = m.status,
                requestedAt = m.requestedAt,
                joinedAt = m.joinedAt,
                decidedAt = m.decidedAt,
            )
    }
}

/**
 * `함께 찾기` 버튼의 응답. [approvalPending] 이 true 면 프론트는 "확인할 요청 대기" 문구를 띄운다.
 */
data class JoinSearchGroupResponse(
    val membership: SearchGroupMembershipResponse,
    val joinPolicy: JoinPolicy,
    val approvalPending: Boolean,
) {
    companion object {
        fun of(
            member: SearchGroupMember,
            joinPolicy: JoinPolicy,
        ): JoinSearchGroupResponse =
            JoinSearchGroupResponse(
                membership = SearchGroupMembershipResponse.from(member),
                joinPolicy = joinPolicy,
                approvalPending = member.status == SearchGroupMemberStatus.PENDING,
            )
    }
}

/**
 * 참여 정책 변경 요청.
 *
 * 필드 누락이나 알 수 없는 enum 문자열은 Jackson 이 역직렬화 단계에서 거절하므로
 * `HttpMessageNotReadableException` 이 되고, Task 1 의 핸들러가 이를 **400 MISSING_PARAMETER** 로
 * 매핑한다(그 전에는 500 이었다, F9). `INVALID_COLLABORATION_INPUT` 은 서비스 계층의 명시적
 * 값 검증 실패(팀 이름 길이 등)에만 쓰는 코드이므로 여기서는 쓰지 않는다.
 */
data class UpdateJoinPolicyRequest(
    val joinPolicy: JoinPolicy,
)
```

`src/main/kotlin/com/park/animal/searchgroup/SearchGroupMembershipService.kt` (신규)

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.access.GroupAccess
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.JoinSearchGroupResponse
import com.park.animal.searchgroup.dto.SearchGroupMembershipResponse
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 개인의 직접 참여 lifecycle (설계 §8, §14.3).
 *
 * 네 가지 규칙이 이 클래스 전체를 지배한다.
 * 1. 재가입은 **새 INSERT 가 아니라 기존 행의 status 전이**다. `(group_id, user_id)` UNIQUE 와
 *    soft-delete 조합이 재가입을 영구히 막는 사고(post_bookmark)를 여기서는 반복하지 않는다.
 * 2. 모든 전이는 `WHERE id = :membershipId AND group_id = :groupId AND status = :expected` 조건부
 *    UPDATE 다. 영향 행 0 은 "이미 다른 상태" 로만 해석하고, 목표 상태면 멱등 성공으로 처리한다.
 *    비관적 락은 쓰지 않는다 — 이 레포에 선례가 없고(F23) 조건부 전이가 경합을 이미 판정한다.
 * 3. duplicate-key 를 catch 해서 재조회하지 않는다. `@Transactional` 안에서 duplicate 가 나면
 *    트랜잭션이 rollback-only 로 오염돼 커밋 시 500 이 되기 때문이다(F14). 대신 자연키를
 *    **먼저 조회**해 duplicate 상황 자체를 만들지 않는다.
 * 4. ACTIVE 로 올리는 전이는 `activate()`(joinedAt 갱신), 그 밖의 전이는 `transition()` 을 쓴다.
 */
@Service
class SearchGroupMembershipService(
    private val memberRepository: SearchGroupMemberRepository,
    private val searchGroupRepository: SearchGroupRepository,
    private val accessResolver: SearchGroupAccessResolver,
    private val eventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
    private val meterRegistry: MeterRegistry,
) {
    /**
     * `함께 찾기` / `참여 신청`.
     *
     * - OPEN → ACTIVE, APPROVAL_REQUIRED → PENDING
     * - 이미 ACTIVE 면 같은 결과를 그대로 반환한다(멱등). 409 가 아니다.
     * - APPROVAL_REQUIRED 에서 PENDING 상태로 다시 누르면 PENDING 을 유지한다(멱등).
     * - APPROVAL_REQUIRED → OPEN 으로 정책이 바뀐 뒤 PENDING 사용자가 다시 누르면 ACTIVE 로 전이한다(설계 §8.3).
     */
    @Transactional
    fun join(
        groupId: UUID,
        userId: UUID,
        userName: String?,
    ): JoinSearchGroupResponse {
        val access = accessResolver.requireVisible(groupId, userId)
        if (access.isOwner) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val existing = memberRepository.findByGroupIdAndUserId(groupId, userId)
        if (existing != null && existing.status == SearchGroupMemberStatus.ACTIVE) {
            return JoinSearchGroupResponse.of(existing, access.joinPolicy)
        }
        if (existing != null &&
            existing.status == SearchGroupMemberStatus.PENDING &&
            access.joinPolicy == JoinPolicy.APPROVAL_REQUIRED
        ) {
            return JoinSearchGroupResponse.of(existing, access.joinPolicy)
        }
        if (!access.canJoin) throw joinRejection(access)

        val now = LocalDateTime.now()
        val target =
            if (access.joinPolicy == JoinPolicy.OPEN) {
                SearchGroupMemberStatus.ACTIVE
            } else {
                SearchGroupMemberStatus.PENDING
            }

        val membershipId: UUID =
            if (existing == null) {
                memberRepository
                    .save(
                        SearchGroupMember(
                            groupId = groupId,
                            userId = userId,
                            userName = userName,
                            status = target,
                            requestedAt = now,
                            joinedAt = if (target == SearchGroupMemberStatus.ACTIVE) now else null,
                        ),
                    ).id
            } else {
                val affected =
                    if (target == SearchGroupMemberStatus.ACTIVE) {
                        memberRepository.activate(
                            membershipId = existing.id,
                            groupId = groupId,
                            expected = existing.status,
                            decidedBy = userId,
                            occurredAt = now,
                        )
                    } else {
                        // 재신청은 "결정" 이 아니므로 decidedBy 를 null 로 넘겨 이전 결정자를 지운다.
                        memberRepository.transition(
                            membershipId = existing.id,
                            groupId = groupId,
                            expected = existing.status,
                            next = SearchGroupMemberStatus.PENDING,
                            decidedBy = null,
                            occurredAt = now,
                        )
                    }
                if (affected == 0) {
                    val current =
                        memberRepository.findByIdAndGroupId(existing.id, groupId)
                            ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
                    if (current.status != target) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
                }
                existing.id
            }

        val saved =
            memberRepository.findByIdAndGroupId(membershipId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)

        if (target == SearchGroupMemberStatus.ACTIVE) {
            eventRecorder.record(groupId, SearchGroupEventType.MEMBER_JOINED, userId, userId, null)
            notificationPublisher.notifyOwner(access, NotificationType.GROUP_MEMBER_JOINED, userId, userName, null)
        } else {
            eventRecorder.record(groupId, SearchGroupEventType.MEMBER_REQUESTED, userId, userId, null)
            notificationPublisher.notifyOwner(access, NotificationType.GROUP_JOIN_REQUESTED, userId, userName, null)
            // 설계 §20 관측: 승인 대기 생성 수. id·이름은 label 에 넣지 않는다.
            meterRegistry.counter(JOIN_REQUESTED_METRIC).increment()
        }
        return JoinSearchGroupResponse.of(saved, access.joinPolicy)
    }

    /** 보호자의 참여 요청 승인. PENDING 이 아니면 409, 이미 ACTIVE 면 멱등 성공. */
    @Transactional
    fun approve(
        groupId: UUID,
        membershipId: UUID,
        ownerUserId: UUID,
    ): SearchGroupMembershipResponse =
        decide(
            groupId = groupId,
            membershipId = membershipId,
            ownerUserId = ownerUserId,
            target = SearchGroupMemberStatus.ACTIVE,
            eventType = SearchGroupEventType.MEMBER_APPROVED,
            notificationType = NotificationType.GROUP_JOIN_APPROVED,
        )

    /** 보호자의 참여 요청 거절. */
    @Transactional
    fun reject(
        groupId: UUID,
        membershipId: UUID,
        ownerUserId: UUID,
    ): SearchGroupMembershipResponse =
        decide(
            groupId = groupId,
            membershipId = membershipId,
            ownerUserId = ownerUserId,
            target = SearchGroupMemberStatus.REJECTED,
            eventType = SearchGroupEventType.MEMBER_REJECTED,
            notificationType = NotificationType.GROUP_JOIN_REJECTED,
        )

    /** 보호자의 참여자 내보내기. ACTIVE 만 REMOVED 로 보낸다. */
    @Transactional
    fun remove(
        groupId: UUID,
        membershipId: UUID,
        ownerUserId: UUID,
    ): SearchGroupMembershipResponse {
        val access = accessResolver.requireOwner(groupId, ownerUserId)
        val member =
            memberRepository.findByIdAndGroupId(membershipId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        if (member.status == SearchGroupMemberStatus.REMOVED) return SearchGroupMembershipResponse.from(member)
        if (member.status != SearchGroupMemberStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val now = LocalDateTime.now()
        val affected =
            memberRepository.transition(
                membershipId = member.id,
                groupId = groupId,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.REMOVED,
                decidedBy = ownerUserId,
                occurredAt = now,
            )
        val current = reloadOrConflict(groupId, member.id, SearchGroupMemberStatus.REMOVED, affected)

        eventRecorder.record(groupId, SearchGroupEventType.MEMBER_REMOVED, ownerUserId, member.userId, null)
        // 행위자·사유를 대상자에게 알리지 않는다 (설계 §6.3).
        notificationPublisher.notifyUser(
            userId = member.userId,
            type = NotificationType.GROUP_MEMBER_REMOVED,
            actorUserId = null,
            postId = access.postId,
            groupId = groupId,
            teamId = null,
            body = null,
        )
        return SearchGroupMembershipResponse.from(current)
    }

    /**
     * 개인 참여 종료.
     *
     * 판정 순서가 곧 응답 코드다.
     * 1. 그룹이 보이지 않으면 404.
     * 2. **차단이 활성이면 403** — 설계 §6.3 "차단이 활성인 동안 모든 접근 거부" 이므로 탈퇴도 막힌다.
     *    `requireRead` 를 통째로 쓰지 않는 이유는 그것이 이미 떠난 사용자(role = NONE)까지 403 으로
     *    만들어 "이미 종료한 참여의 재종료 = 409" 규칙과 어긋나기 때문이다. 차단 판정만 동일하게 적용한다.
     * 3. 직접 멤버십 행이 없으면 404 — 보호자와 팀 경유 사용자가 여기에 해당한다.
     *    팀을 통해 권한을 얻은 사용자는 이 경로로 나갈 수 없다(설계 §6.6, 팀 지원 종료로만 사라진다).
     * 4. ACTIVE/PENDING 이 아니면 409.
     */
    @Transactional
    fun leaveMe(
        groupId: UUID,
        userId: UUID,
    ): SearchGroupMembershipResponse {
        val access = accessResolver.requireVisible(groupId, userId)
        if (access.blocked) throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)

        val member =
            memberRepository.findByGroupIdAndUserId(groupId, userId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        if (member.status != SearchGroupMemberStatus.ACTIVE && member.status != SearchGroupMemberStatus.PENDING) {
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        val now = LocalDateTime.now()
        val affected =
            memberRepository.transition(
                membershipId = member.id,
                groupId = groupId,
                expected = member.status,
                next = SearchGroupMemberStatus.LEFT,
                decidedBy = userId,
                occurredAt = now,
            )
        val current = reloadOrConflict(groupId, member.id, SearchGroupMemberStatus.LEFT, affected)
        eventRecorder.record(groupId, SearchGroupEventType.MEMBER_LEFT, userId, userId, null)
        return SearchGroupMembershipResponse.from(current)
    }

    /**
     * 참여자 목록.
     * - 보호자: PENDING 포함 전체(또는 요청한 status)
     * - 참여자: ACTIVE 목록만. status 파라미터를 보내도 무시한다.
     */
    @Transactional(readOnly = true)
    fun list(
        groupId: UUID,
        userId: UUID,
        status: SearchGroupMemberStatus? = null,
    ): List<SearchGroupMembershipResponse> {
        val access = accessResolver.requireRead(groupId, userId)
        val rows =
            if (access.isOwner) {
                if (status == null) {
                    memberRepository.findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId)
                } else {
                    memberRepository.findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(groupId, status)
                }
            } else {
                memberRepository.findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(
                    groupId,
                    SearchGroupMemberStatus.ACTIVE,
                )
            }
        return rows.map(SearchGroupMembershipResponse::from)
    }

    /**
     * 참여 정책 변경 (보호자 전용).
     *
     * 기존 ACTIVE 참여자는 그대로 유지하고, PENDING 을 자동 승인하지 않는다(설계 §8.3).
     * 변경 사실은 활동 기록에만 남기고 **알림은 보내지 않는다** — 설계 §8.3 이 요구하는 것은
     * 그룹 활동 기록뿐이고 §9 수신자 표에 이 항목이 없다.
     */
    @Transactional
    fun updateJoinPolicy(
        groupId: UUID,
        ownerUserId: UUID,
        joinPolicy: JoinPolicy,
    ) {
        val access = accessResolver.requireOwner(groupId, ownerUserId)
        if (access.joinPolicy == joinPolicy) return

        val affected =
            searchGroupRepository.updateJoinPolicyFrom(
                groupId = groupId,
                expected = access.joinPolicy,
                next = joinPolicy,
                activeStatus = SearchGroupStatus.ACTIVE,
                now = LocalDateTime.now(),
            )
        if (affected == 0) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        eventRecorder.record(
            groupId,
            SearchGroupEventType.JOIN_POLICY_CHANGED,
            ownerUserId,
            null,
            "${access.joinPolicy.name} -> ${joinPolicy.name}",
        )
    }

    private fun decide(
        groupId: UUID,
        membershipId: UUID,
        ownerUserId: UUID,
        target: SearchGroupMemberStatus,
        eventType: SearchGroupEventType,
        notificationType: NotificationType,
    ): SearchGroupMembershipResponse {
        val access = accessResolver.requireOwner(groupId, ownerUserId)
        // tuple 조회. 다른 그룹의 membershipId 는 여기서 404 로 떨어진다(설계 §16.1).
        val member =
            memberRepository.findByIdAndGroupId(membershipId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        if (member.status == target) return SearchGroupMembershipResponse.from(member)
        if (member.status != SearchGroupMemberStatus.PENDING) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val now = LocalDateTime.now()
        val affected =
            if (target == SearchGroupMemberStatus.ACTIVE) {
                memberRepository.activate(
                    membershipId = member.id,
                    groupId = groupId,
                    expected = SearchGroupMemberStatus.PENDING,
                    decidedBy = ownerUserId,
                    occurredAt = now,
                )
            } else {
                memberRepository.transition(
                    membershipId = member.id,
                    groupId = groupId,
                    expected = SearchGroupMemberStatus.PENDING,
                    next = target,
                    decidedBy = ownerUserId,
                    occurredAt = now,
                )
            }
        val current = reloadOrConflict(groupId, member.id, target, affected)

        eventRecorder.record(groupId, eventType, ownerUserId, member.userId, null)
        notificationPublisher.notifyUser(
            userId = member.userId,
            type = notificationType,
            actorUserId = ownerUserId,
            postId = access.postId,
            groupId = groupId,
            teamId = null,
            body = null,
        )
        return SearchGroupMembershipResponse.from(current)
    }

    /** 영향 행 0 이면 현재 상태를 재조회해 목표 상태면 멱등 성공, 아니면 409. */
    private fun reloadOrConflict(
        groupId: UUID,
        membershipId: UUID,
        target: SearchGroupMemberStatus,
        affected: Int,
    ): SearchGroupMember {
        val current =
            memberRepository.findByIdAndGroupId(membershipId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        if (affected == 0 && current.status != target) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        return current
    }

    private fun joinRejection(access: GroupAccess): BusinessException =
        when {
            // 차단 사실을 응답으로 구분할 수 없게 한다. 권한 없음과 같은 403 이다.
            access.blocked -> BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
            access.groupStatus != SearchGroupStatus.ACTIVE -> BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
            access.postStatus != MissingAnimalStatus.SEARCHING -> BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
            else -> BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

    companion object {
        /** 설계 §20 관측 메트릭. 태그를 붙이지 않는다 — id·본문은 label 금지. */
        const val JOIN_REQUESTED_METRIC = "fmp.searchgroup.join.requested"
    }
}
```

`src/main/kotlin/com/park/animal/searchgroup/SearchGroupMembershipController.kt` (신규)

```kotlin
package com.park.animal.searchgroup

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchgroup.dto.JoinSearchGroupResponse
import com.park.animal.searchgroup.dto.SearchGroupMembershipResponse
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SearchGroupMembershipController(
    private val membershipService: SearchGroupMembershipService,
) {
    @PostMapping("/search-groups/{groupId}/memberships")
    @Operation(
        summary = "함께 찾기 참여 또는 참여 신청",
        description = "참여 정책이 자유롭게 참여면 즉시 참여, 승인 후 참여면 신청 상태가 된다. 중복 호출은 멱등하다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun join(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<JoinSearchGroupResponse> {
        // requireUserContext 를 직접 호출하면 컨텍스트가 없을 때 예외가 난다. 표시 이름은 없어도 진행한다.
        val userName = runCatching { passport.requireUserContext().userName.toString() }.getOrNull()
        return SucceededApiResponseBody(data = membershipService.join(groupId, passport.userId, userName))
    }

    @GetMapping("/search-groups/{groupId}/memberships")
    @Operation(
        summary = "수색그룹 참여자 목록",
        description = "보호자는 확인할 요청(대기)까지 보고, 참여자는 참여 중인 목록만 본다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestParam(name = "status", required = false) status: SearchGroupMemberStatus?,
    ): SucceededApiResponseBody<List<SearchGroupMembershipResponse>> =
        SucceededApiResponseBody(data = membershipService.list(groupId, passport.userId, status))

    @PostMapping("/search-groups/{groupId}/memberships/{membershipId}/approve")
    @Operation(
        summary = "참여 요청 승인 (보호자)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun approve(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<SearchGroupMembershipResponse> =
        SucceededApiResponseBody(data = membershipService.approve(groupId, membershipId, passport.userId))

    @PostMapping("/search-groups/{groupId}/memberships/{membershipId}/reject")
    @Operation(
        summary = "참여 요청 거절 (보호자)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun reject(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<SearchGroupMembershipResponse> =
        SucceededApiResponseBody(data = membershipService.reject(groupId, membershipId, passport.userId))

    @PostMapping("/search-groups/{groupId}/memberships/{membershipId}/remove")
    @Operation(
        summary = "참여자 내보내기 (보호자)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun remove(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<SearchGroupMembershipResponse> =
        SucceededApiResponseBody(data = membershipService.remove(groupId, membershipId, passport.userId))

    @DeleteMapping("/search-groups/{groupId}/memberships/me")
    @Operation(
        summary = "개인 참여 종료",
        description = "직접 참여한 사용자만 사용할 수 있다. 팀을 통해 권한을 얻은 사용자는 팀 지원 종료로만 권한이 사라진다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun leaveMe(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<SearchGroupMembershipResponse> =
        SucceededApiResponseBody(data = membershipService.leaveMe(groupId, passport.userId))
}
```

`src/main/kotlin/com/park/animal/searchgroup/SearchGroupService.kt` — Task 4 가 만든 클래스의 생성자 마지막에 한 줄을 추가하고, 클래스 본문 끝에 메서드를 추가한다. 추가 import: `com.park.animal.searchgroup.entity.JoinPolicy`.

정책 변경과 상세 조회를 **한 트랜잭션**에 묶기 위해 의존성을 컨트롤러가 아니라 이 서비스에 넣는다. 컨트롤러가 두 서비스를 순차 호출하면 두 트랜잭션이 되어, 동시 변경 시 방금 쓴 값이 아닌 상태를 돌려줄 수 있다.

```kotlin
// 생성자 마지막에 추가
    private val membershipService: SearchGroupMembershipService,

// 클래스 본문 끝에 추가
    /**
     * 참여 정책 변경 (엔드포인트 #4, 보호자 전용).
     *
     * 실제 전이와 활동 기록은 [SearchGroupMembershipService.updateJoinPolicy] 가 담당하고,
     * 여기서는 같은 트랜잭션 안에서 변경 후 상세를 다시 만들어 돌려준다.
     * 알림은 발행하지 않는다 — 설계 §8.3 은 활동 기록만 요구한다.
     */
    @Transactional
    fun updateJoinPolicy(
        groupId: UUID,
        ownerUserId: UUID,
        joinPolicy: JoinPolicy,
    ): SearchGroupDetailResponse {
        membershipService.updateJoinPolicy(groupId, ownerUserId, joinPolicy)
        return getDetail(groupId, ownerUserId)
    }
```

`src/main/kotlin/com/park/animal/searchgroup/SearchGroupController.kt` — 클래스 본문 끝에 핸들러를 추가한다. 생성자는 그대로 `SearchGroupService` 하나만 받는다. 추가 import: `com.park.animal.searchgroup.dto.UpdateJoinPolicyRequest`, `org.springframework.web.bind.annotation.PatchMapping`, `org.springframework.web.bind.annotation.RequestBody`.

```kotlin
    @PatchMapping("/search-groups/{groupId}/join-policy")
    @Operation(
        summary = "참여 정책 변경 (보호자)",
        description = "기존 참여자는 그대로 유지되고, 대기 중인 요청도 자동 승인되지 않는다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun updateJoinPolicy(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestBody request: UpdateJoinPolicyRequest,
    ): SucceededApiResponseBody<SearchGroupDetailResponse> =
        SucceededApiResponseBody(
            data = searchGroupService.updateJoinPolicy(groupId, passport.userId, request.joinPolicy),
        )
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.searchgroup.SearchGroupMembershipIT" \
  --tests "com.park.animal.searchgroup.SearchGroupMigrationIT"
```

Expected: PASS — 멤버십 IT 13개 + 마이그레이션 IT 5개 통과. 특히 `중복 클릭 3회에도 ACTIVE 행 1개 알림 1건만 남는다`, `다른 그룹의 membershipId 로 승인하면 404 다 (IDOR)`, `차단된 사용자는 개인 참여 종료도 403 이다`. 마이그레이션 IT 를 함께 도는 이유는 이 태스크가 리포지토리를 건드리지 않았음을 확인하기 위해서다 — 리포지토리를 재정의하면 여기서 컴파일이 깨진다.

- [ ] **Step 5: 커밋**

```bash
cd /Users/park/Desktop/project/animal && git add \
  src/main/kotlin/com/park/animal/searchgroup \
  src/test/kotlin/com/park/animal/searchgroup/SearchGroupMembershipIT.kt && \
git commit -m "feat(search-group): 직접 참여 lifecycle 과 참여 정책 변경 추가"
```

---

### Task 7: 보호자 차단

**Files:**
- Create: `src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupBlockDtos.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupBlockService.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupBlockController.kt`
- Test: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupBlockIT.kt`

**리포지토리는 만들지도 고치지도 않는다.** Task 2 의 `SearchGroupUserBlockRepository` / `SearchGroupMemberRepository` 를 그대로 호출한다.

**Interfaces:**

- Consumes:
  - Task 2 엔티티 (이 생성자 시그니처 전제):
    ```kotlin
    class SearchGroupUserBlock(
        val groupId: UUID, val userId: UUID, var blockedBy: UUID,
        var reason: String? = null,
        var blockedAt: LocalDateTime = LocalDateTime.now(), var unblockedAt: LocalDateTime? = null,
    ) : BaseEntity()
    ```
  - Task 2 리포지토리 (**이 시그니처를 그대로 호출한다. 재정의하지 않는다**):
    - `SearchGroupUserBlockRepository.findByGroupIdAndUserId(groupId: UUID, userId: UUID): SearchGroupUserBlock?`
    - `SearchGroupUserBlockRepository.findAllByGroupIdAndUnblockedAtIsNullOrderByBlockedAtDescIdDesc(groupId: UUID): List<SearchGroupUserBlock>`
    - `SearchGroupUserBlockRepository.reactivate(blockId: UUID, groupId: UUID, blockedBy: UUID, occurredAt: LocalDateTime): Int` — `WHERE b.id = :blockId AND b.groupId = :groupId AND b.unblockedAt IS NOT NULL`, `unblockedAt` 을 NULL 로 되돌린다. **`reason` 은 이 JPQL 이 다루지 않는다** → 서비스가 엔티티에 직접 반영한다.
    - `SearchGroupUserBlockRepository.deactivate(blockId: UUID, groupId: UUID, occurredAt: LocalDateTime): Int` — `WHERE b.id = :blockId AND b.groupId = :groupId AND b.unblockedAt IS NULL`
    - `SearchGroupMemberRepository.findByGroupIdAndUserId(...)`, `findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID): List<SearchGroupMember>`, `transition(membershipId, groupId, expected, next, decidedBy, occurredAt): Int`
  - Task 3: `SearchGroupAccessResolver.resolve/requireVisible/requireOwner/effectiveMemberIds`, `GroupAccess`
  - Task 4: `SearchGroupEventRecorder.record(...)`
  - Task 5: `GroupNotificationPublisher.notifyUser(...)`, 테스트 전용 `SearchGroupTestMetricsConfig`
  - Task 6: `SearchGroupMembershipService.join(...)` (IT 시나리오용)
- Produces (Task 10·11 이 의존):
  - `SearchGroupBlockService.block(groupId: UUID, targetUserId: UUID, ownerUserId: UUID, reason: String?): SearchGroupBlockResponse`
  - `SearchGroupBlockService.unblock(groupId: UUID, targetUserId: UUID, ownerUserId: UUID)`
  - `SearchGroupBlockService.listBlocks(groupId: UUID, ownerUserId: UUID): List<SearchGroupBlockResponse>`
  - DTO `BlockSearchGroupUserRequest(targetUserId, reason)`, `SearchGroupBlockResponse(blockId, groupId, userId, userName, reason, blockedAt, unblockedAt)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupBlockIT.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 보호자 차단 (설계 §6.3, §14.3, §16.5).
 * 차단은 허용 권한보다 우선하며, 팀을 통해 얻은 파생 권한에도 그대로 적용된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupTestMetricsConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
    SearchGroupMembershipService::class,
    SearchGroupBlockService::class,
)
@Testcontainers
class SearchGroupBlockIT {
    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var blockService: SearchGroupBlockService

    @Autowired lateinit var membershipService: SearchGroupMembershipService

    @Autowired lateinit var accessResolver: SearchGroupAccessResolver

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var ownerId: UUID
    private lateinit var postId: UUID
    private lateinit var groupId: UUID

    @BeforeEach
    fun seed() {
        listOf(
            "notification", "search_group_event", "search_group_user_block", "search_group_member",
            "search_group_team", "team_member", "team", "search_group", "sighting", "post_bookmark", "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }

        ownerId = UUID.randomUUID()
        postId =
            postRepository
                .save(
                    Post(
                        authorId = ownerId,
                        authorName = "보호자",
                        title = "말티즈를 찾습니다",
                        phoneNum = "010-0000-0000",
                        time = LocalDateTime.now(),
                        place = "서울 강남구 역삼동",
                        gender = "남아",
                        gratuity = 0,
                        description = "겁이 많아요.",
                        lat = 37.5012,
                        lng = 127.0396,
                        openChatUrl = null,
                        missingAnimalStatus = MissingAnimalStatus.SEARCHING,
                        animalType = AnimalType.DOG,
                    ),
                ).id
        groupId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            groupId.toString(), postId.toString(), JoinPolicy.OPEN.name, "ACTIVE",
        )
    }

    private fun seedSupportingTeam(memberIds: List<UUID>): UUID {
        val teamId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO team (id, name, status, created_by, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            teamId.toString(), "강남수색팀", "ACTIVE", memberIds.first().toString(),
        )
        memberIds.forEachIndexed { idx, uid ->
            jdbcTemplate.update(
                "INSERT INTO team_member (id, team_id, user_id, user_name, role, status, joined_at, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,NOW(6),NOW(6),NOW(6))",
                UUID.randomUUID().toString(), teamId.toString(), uid.toString(), "팀원",
                if (idx == 0) "LEADER" else "MEMBER", "ACTIVE",
            )
        }
        jdbcTemplate.update(
            "INSERT INTO search_group_team (id, group_id, team_id, status, requested_by, requested_at, activated_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,NOW(6),NOW(6),NOW(6),NOW(6))",
            UUID.randomUUID().toString(), groupId.toString(), teamId.toString(), "ACTIVE", ownerId.toString(),
        )
        return teamId
    }

    @Test
    fun `팀 경유 팀원 한 명을 차단해도 나머지 팀 지원은 유지된다`() {
        val leaderId = UUID.randomUUID()
        val blockedMemberId = UUID.randomUUID()
        val teamId = seedSupportingTeam(listOf(leaderId, blockedMemberId))

        blockService.block(groupId, blockedMemberId, ownerId, "반복 신고")

        val effective = accessResolver.effectiveMemberIds(groupId)
        assertTrue(leaderId in effective, "차단되지 않은 팀원의 파생 권한은 유지된다")
        assertFalse(blockedMemberId in effective, "차단은 팀 파생 권한보다 우선한다")
        assertFalse(accessResolver.resolve(groupId, blockedMemberId)!!.canRead)

        val supportStatus =
            jdbcTemplate.queryForObject(
                "SELECT status FROM search_group_team WHERE group_id = ? AND team_id = ?",
                String::class.java,
                groupId.toString(),
                teamId.toString(),
            )
        assertEquals("ACTIVE", supportStatus, "팀 지원 연결 자체는 그대로다")
    }

    @Test
    fun `차단된 사용자의 접근 정보는 비차단 비참여자와 blocked 외에는 동일하다`() {
        val blockedId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        blockService.block(groupId, blockedId, ownerId, "반복 신고")

        val blocked = accessResolver.resolve(groupId, blockedId)!!
        val stranger = accessResolver.resolve(groupId, strangerId)!!

        assertEquals(
            stranger.copy(viewerId = null),
            blocked.copy(viewerId = null, blocked = false),
            "blocked 플래그를 제외한 모든 필드가 같아야 DTO 로 차단 사실이 새지 않는다",
        )
        assertFalse(blocked.canJoin)
        val e = assertFailsWith<BusinessException> { membershipService.join(groupId, blockedId, "차단대상") }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode, "권한 없음과 같은 코드라 차단 여부를 구분할 수 없다")
    }

    @Test
    fun `보호자 자신은 차단 대상이 될 수 없다`() {
        val e = assertFailsWith<BusinessException> { blockService.block(groupId, ownerId, ownerId, null) }
        assertEquals(ErrorCode.SEARCH_GROUP_STATE_CONFLICT, e.errorCode)
    }

    @Test
    fun `차단하면 대상의 직접 참여도 함께 종료되고 목록에 표시 이름이 남는다`() {
        val memberId = UUID.randomUUID()
        val joined = membershipService.join(groupId, memberId, "참여자")

        blockService.block(groupId, memberId, ownerId, "반복 신고")

        val status =
            jdbcTemplate.queryForObject(
                "SELECT status FROM search_group_member WHERE id = ?",
                String::class.java,
                joined.membership.membershipId.toString(),
            )
        assertEquals(SearchGroupMemberStatus.REMOVED.name, status)
        assertEquals(
            "참여자",
            blockService.listBlocks(groupId, ownerId).single().userName,
            "보호자 화면이 사용자 id 만 보여주지 않도록 멤버십 행의 표시 이름을 함께 싣는다",
        )
    }

    @Test
    fun `차단 후 해제하면 다시 참여할 수 있고 같은 멤버십 행을 쓴다`() {
        val memberId = UUID.randomUUID()
        val first = membershipService.join(groupId, memberId, "참여자")
        blockService.block(groupId, memberId, ownerId, "반복 신고")

        blockService.unblock(groupId, memberId, ownerId)
        val again = membershipService.join(groupId, memberId, "참여자")

        assertEquals(first.membership.membershipId, again.membership.membershipId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, again.membership.status)
        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_group_user_block WHERE group_id = ? AND user_id = ?",
                Long::class.java,
                groupId.toString(),
                memberId.toString(),
            ),
            "차단 행은 재사용된다 (UNIQUE 충돌을 만드는 새 INSERT 금지)",
        )
    }

    @Test
    fun `차단 재적용은 새 행을 만들지 않고 같은 행을 되살린다`() {
        val memberId = UUID.randomUUID()
        blockService.block(groupId, memberId, ownerId, "1차")
        blockService.unblock(groupId, memberId, ownerId)
        val reblocked = blockService.block(groupId, memberId, ownerId, "2차")

        assertEquals(1, blockService.listBlocks(groupId, ownerId).size)
        assertEquals("2차", reblocked.reason)
    }

    @Test
    fun `차단 목록은 보호자만 볼 수 있고 사유는 여기서만 노출된다`() {
        val memberId = UUID.randomUUID()
        val outsiderId = UUID.randomUUID()
        blockService.block(groupId, memberId, ownerId, "반복 신고")

        val blocks = blockService.listBlocks(groupId, ownerId)
        assertEquals(1, blocks.size)
        assertEquals("반복 신고", blocks.single().reason)

        val e = assertFailsWith<BusinessException> { blockService.listBlocks(groupId, outsiderId) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    @Test
    fun `해제는 멱등하다`() {
        val memberId = UUID.randomUUID()
        blockService.block(groupId, memberId, ownerId, "반복 신고")

        blockService.unblock(groupId, memberId, ownerId)
        blockService.unblock(groupId, memberId, ownerId)
        blockService.unblock(groupId, UUID.randomUUID(), ownerId)

        assertEquals(0, blockService.listBlocks(groupId, ownerId).size)
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.searchgroup.SearchGroupBlockIT"
```

Expected: FAIL — 컴파일 에러 `Unresolved reference: SearchGroupBlockService`.

- [ ] **Step 3: 최소 구현**

`src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupBlockDtos.kt` (신규)

```kotlin
package com.park.animal.searchgroup.dto

import com.park.animal.searchgroup.entity.SearchGroupUserBlock
import java.time.LocalDateTime
import java.util.UUID

/**
 * 차단 요청. [reason] 은 운영 감사용이며 보호자 전용 목록 조회에서만 되돌아온다.
 * 알림·활동 기록·다른 응답에는 절대 싣지 않는다(설계 §6.3, §16.5).
 */
data class BlockSearchGroupUserRequest(
    val targetUserId: UUID,
    val reason: String? = null,
)

/**
 * 보호자 전용 차단 항목. 이 DTO 는 `GET /search-groups/{groupId}/blocks` 응답에만 쓴다.
 *
 * [userName] 은 멤버십 행(`search_group_member.user_name`)에서 가져온 표시 이름이다.
 * 차단 대상이 직접 참여한 적이 없으면 null 이며, 그 경우 프론트는 id 대신 "알 수 없음" 을 보여준다.
 */
data class SearchGroupBlockResponse(
    val blockId: UUID,
    val groupId: UUID,
    val userId: UUID,
    val userName: String?,
    val reason: String?,
    val blockedAt: LocalDateTime,
    val unblockedAt: LocalDateTime?,
) {
    companion object {
        fun of(
            b: SearchGroupUserBlock,
            userName: String?,
        ): SearchGroupBlockResponse =
            SearchGroupBlockResponse(
                blockId = b.id,
                groupId = b.groupId,
                userId = b.userId,
                userName = userName,
                reason = b.reason,
                blockedAt = b.blockedAt,
                unblockedAt = b.unblockedAt,
            )
    }
}
```

`src/main/kotlin/com/park/animal/searchgroup/SearchGroupBlockService.kt` (신규)

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupBlockResponse
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupUserBlock
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 보호자의 개별 사용자 차단 (설계 §6.3, §14.3).
 *
 * 차단은 허용 권한보다 우선한다. 직접 참여자뿐 아니라 팀을 통해 권한을 얻은 팀원에게도 적용되며,
 * 그 판정은 [SearchGroupAccessResolver] 의 anti-join 이 담당한다 — 이 서비스는 차단 행만 관리한다.
 *
 * `(group_id, user_id)` 가 UNIQUE 이므로 재차단은 **기존 행의 unblocked_at 을 NULL 로 되돌리는 전이**다.
 * 새 INSERT 를 시도하면 1062 가 나고, `@Transactional` 안에서 그것을 catch 하면 트랜잭션이
 * rollback-only 로 오염돼 커밋 시 500 이 된다(F14).
 */
@Service
class SearchGroupBlockService(
    private val blockRepository: SearchGroupUserBlockRepository,
    private val memberRepository: SearchGroupMemberRepository,
    private val accessResolver: SearchGroupAccessResolver,
    private val eventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
) {
    @Transactional
    fun block(
        groupId: UUID,
        targetUserId: UUID,
        ownerUserId: UUID,
        reason: String?,
    ): SearchGroupBlockResponse {
        val access = accessResolver.requireOwner(groupId, ownerUserId)
        // 보호자 자신은 차단 대상이 될 수 없다. 허용하면 자기 그룹에서 스스로를 지우게 된다.
        if (targetUserId == access.ownerUserId) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val now = LocalDateTime.now()
        val reasonText = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(REASON_MAX_LENGTH)
        val existing = blockRepository.findByGroupIdAndUserId(groupId, targetUserId)
        if (existing == null) {
            blockRepository.save(
                SearchGroupUserBlock(
                    groupId = groupId,
                    userId = targetUserId,
                    blockedBy = ownerUserId,
                    reason = reasonText,
                    blockedAt = now,
                    unblockedAt = null,
                ),
            )
        } else {
            // reason 은 조건부 전이 JPQL(reactivate)이 다루지 않는 필드다. 관리 엔티티에 직접 반영하고
            // 즉시 flush 해 두면 뒤따르는 벌크 UPDATE(clearAutomatically)에 값이 씻겨나가지 않는다.
            existing.reason = reasonText
            blockRepository.saveAndFlush(existing)
            if (existing.unblockedAt != null) {
                blockRepository.reactivate(
                    blockId = existing.id,
                    groupId = groupId,
                    blockedBy = ownerUserId,
                    occurredAt = now,
                )
            }
        }

        // 대상의 직접 멤버십이 살아 있으면 함께 종료한다. 파생(팀) 권한은 access 쿼리가 즉시 차단한다.
        val membership = memberRepository.findByGroupIdAndUserId(groupId, targetUserId)
        if (membership != null && membership.status == SearchGroupMemberStatus.ACTIVE) {
            memberRepository.transition(
                membershipId = membership.id,
                groupId = groupId,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.REMOVED,
                decidedBy = ownerUserId,
                occurredAt = now,
            )
        }

        // 감사 기록에 사유를 남기지 않는다. detail 은 상태 전이 요약만 담는다.
        eventRecorder.record(groupId, SearchGroupEventType.USER_BLOCKED, ownerUserId, targetUserId, null)
        // 행위자와 사유를 대상자에게 노출하지 않는다 (설계 §6.3).
        notificationPublisher.notifyUser(
            userId = targetUserId,
            type = NotificationType.GROUP_MEMBER_BLOCKED,
            actorUserId = null,
            postId = access.postId,
            groupId = groupId,
            teamId = null,
            body = null,
        )

        val saved =
            blockRepository.findByGroupIdAndUserId(groupId, targetUserId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        return SearchGroupBlockResponse.of(saved, displayNameOf(groupId, targetUserId))
    }

    /** 차단 해제. 차단 행이 없거나 이미 해제돼 있으면 아무것도 하지 않는다(멱등). */
    @Transactional
    fun unblock(
        groupId: UUID,
        targetUserId: UUID,
        ownerUserId: UUID,
    ) {
        accessResolver.requireOwner(groupId, ownerUserId)
        val existing = blockRepository.findByGroupIdAndUserId(groupId, targetUserId) ?: return
        if (existing.unblockedAt != null) return

        val affected =
            blockRepository.deactivate(
                blockId = existing.id,
                groupId = groupId,
                occurredAt = LocalDateTime.now(),
            )
        if (affected == 0) return

        eventRecorder.record(groupId, SearchGroupEventType.USER_UNBLOCKED, ownerUserId, targetUserId, null)
    }

    /**
     * 활성 차단 목록. **보호자 전용**이며 `reason` 이 노출되는 유일한 지점이다.
     *
     * 보관된 그룹에서도 보호자는 기록을 볼 수 있어야 하므로 requireOwner(410 을 던진다) 대신
     * requireVisible + 소유자 확인을 쓴다.
     *
     * 표시 이름은 멤버십 행을 한 번만 읽어 맵으로 만들어 붙인다(차단 건수만큼 조회하지 않는다).
     */
    @Transactional(readOnly = true)
    fun listBlocks(
        groupId: UUID,
        ownerUserId: UUID,
    ): List<SearchGroupBlockResponse> {
        val access = accessResolver.requireVisible(groupId, ownerUserId)
        if (!access.isOwner) throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)

        val nameByUserId =
            memberRepository
                .findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId)
                .associate { it.userId to it.userName }
        return blockRepository
            .findAllByGroupIdAndUnblockedAtIsNullOrderByBlockedAtDescIdDesc(groupId)
            .map { SearchGroupBlockResponse.of(it, nameByUserId[it.userId]) }
    }

    private fun displayNameOf(
        groupId: UUID,
        userId: UUID,
    ): String? = memberRepository.findByGroupIdAndUserId(groupId, userId)?.userName

    companion object {
        /** V12 의 `reason VARCHAR(500)` 과 맞춘다. 초과 입력은 잘라 저장한다. */
        private const val REASON_MAX_LENGTH = 500
    }
}
```

`src/main/kotlin/com/park/animal/searchgroup/SearchGroupBlockController.kt` (신규)

```kotlin
package com.park.animal.searchgroup

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchgroup.dto.BlockSearchGroupUserRequest
import com.park.animal.searchgroup.dto.SearchGroupBlockResponse
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SearchGroupBlockController(
    private val blockService: SearchGroupBlockService,
) {
    @GetMapping("/search-groups/{groupId}/blocks")
    @Operation(
        summary = "차단 목록 (보호자 전용)",
        description = "보호자만 조회할 수 있다. 참여자에게는 어떤 경로로도 노출하지 않는다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<List<SearchGroupBlockResponse>> =
        SucceededApiResponseBody(data = blockService.listBlocks(groupId, passport.userId))

    @PostMapping("/search-groups/{groupId}/blocks")
    @Operation(
        summary = "사용자 차단 (보호자)",
        description = "직접 참여 중이면 참여도 함께 종료되고, 팀을 통해 얻은 권한도 즉시 사라진다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun block(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestBody request: BlockSearchGroupUserRequest,
    ): SucceededApiResponseBody<SearchGroupBlockResponse> =
        SucceededApiResponseBody(
           data = blockService.block(groupId, request.targetUserId, passport.userId, request.reason),
        )

    @DeleteMapping("/search-groups/{groupId}/blocks/{userId}")
    @Operation(
        summary = "차단 해제 (보호자)",
        description = "해제하면 현재 참여 정책에 따라 다시 참여하거나 신청할 수 있다. 이미 해제된 상태로 다시 호출해도 성공이다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun unblock(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("userId") userId: UUID,
    ): SucceededApiResponseBody<Unit> {
        blockService.unblock(groupId, userId, passport.userId)
        return SucceededApiResponseBody.succeed()
    }
}
```

본문 없는 성공 응답은 `SucceededApiResponseBody<Unit>` + `succeed()` 로 쓴다. `SucceededApiResponseBody(data = null)` 은 `data` 가 non-null 제네릭이라 컴파일되지 않는다(`SightingController.kt:71-73`, `ReviewController.kt:42-50` 선례).

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.searchgroup.SearchGroupBlockIT" \
  --tests "com.park.animal.searchgroup.SearchGroupMembershipIT" \
  --tests "com.park.animal.searchgroup.SearchGroupMigrationIT"
```

Expected: PASS — 차단 IT 8개 + 멤버십 IT 13개 + 마이그레이션 IT 5개 통과. 특히 `차단된 사용자의 접근 정보는 비차단 비참여자와 blocked 외에는 동일하다`, `팀 경유 팀원 한 명을 차단해도 나머지 팀 지원은 유지된다`, `차단하면 대상의 직접 참여도 함께 종료되고 목록에 표시 이름이 남는다`. 마이그레이션 IT 를 함께 도는 이유는 이 태스크가 리포지토리를 재정의하지 않았음을 확인하기 위해서다.

- [ ] **Step 5: 커밋**

```bash
cd /Users/park/Desktop/project/animal && git add \
  src/main/kotlin/com/park/animal/searchgroup \
  src/test/kotlin/com/park/animal/searchgroup/SearchGroupBlockIT.kt && \
git commit -m "feat(search-group): 보호자 차단과 차단 해제 추가"
```

---

### Task 8: 팀 + 팀 멤버십 + 팀장 이전 + 팀 보관

**Files:**
- Create: `src/main/kotlin/com/park/animal/team/dto/TeamRequests.kt`
- Create: `src/main/kotlin/com/park/animal/team/dto/TeamResponses.kt`
- Create: `src/main/kotlin/com/park/animal/team/TeamService.kt`
- Create: `src/main/kotlin/com/park/animal/team/TeamMembershipService.kt`
- Create: `src/main/kotlin/com/park/animal/team/TeamController.kt`
- Create: `src/main/kotlin/com/park/animal/team/TeamMembershipController.kt`
- Test: `src/test/kotlin/com/park/animal/team/TeamMembershipIT.kt`

> **리포지토리를 새로 정의하거나 전체 교체하지 않는다.** `TeamRepository` / `TeamMemberRepository` / `SearchGroupTeamRepository` 는 Task 2 가 최종 시그니처로 이미 만들어 두었고, 이 태스크는 **그대로 호출만** 한다. 조건부 전이 메서드(`transition` / `activate` / `changeRole` / `archiveIfStatus`)는 **위치 인자로 호출**한다 — 파라미터 이름은 Task 2 의 소유이므로 named argument 로 결합하지 않는다.

> **계약 확장**: `POST /teams/{teamId}/archive` 를 계약 §8 엔드포인트 표에 **#33** 으로 추가한다 — **설계 §6.4 근거, §18 표에는 없던 추가**. 설계 §6.4 는 팀장 이탈 조건으로 "후임에게 이전 **또는 팀을 보관 처리**" 를 허용하는데 보관 경로가 없으면 마지막 팀장이 팀을 영원히 떠날 수 없다.

**Interfaces:**
- Consumes (Task 2): 엔티티 `com.park.animal.team.entity.Team(name: String, description: String?, status: TeamStatus, createdBy: UUID) : BaseEntity()`, `com.park.animal.team.entity.TeamMember(teamId: UUID, userId: UUID, userName: String?, role: TeamRole, status: TeamMemberStatus, joinedAt: LocalDateTime?, requestedAt: LocalDateTime?, decidedAt: LocalDateTime?, decidedBy: UUID?) : BaseEntity()`, `com.park.animal.searchgroup.entity.SearchGroup(postId: UUID, joinPolicy: JoinPolicy = JoinPolicy.OPEN, status: SearchGroupStatus = SearchGroupStatus.ACTIVE, ...) : BaseEntity()`, `com.park.animal.searchgroup.entity.SearchGroupTeam(groupId, teamId, status, requestedBy, requestedAt, decidedBy, decidedAt, activatedAt) : BaseEntity()`
- Consumes (Task 2, 리포지토리 — **재정의 금지, 호출만**):
  - `TeamRepository.findByIdAndDeletedAtIsNull(id): Team?`
  - `TeamRepository.searchByName(q: String, status: TeamStatus, pageable: Pageable): Page<Team>`
  - `TeamRepository.archiveIfStatus(teamId, expected: TeamStatus, next: TeamStatus, occurredAt: LocalDateTime): Int`
  - `TeamMemberRepository.findByTeamIdAndUserId(teamId, userId): TeamMember?`
  - `TeamMemberRepository.findByIdAndTeamId(id, teamId): TeamMember?`
  - `TeamMemberRepository.findFirstByTeamIdAndRoleAndStatus(teamId, role, status): TeamMember?`
  - `TeamMemberRepository.findAllByTeamIdAndStatus(teamId, status): List<TeamMember>`
  - `TeamMemberRepository.findAllByTeamIdOrderByCreatedAtDescIdDesc(teamId, pageable): Page<TeamMember>`
  - `TeamMemberRepository.findAllByUserIdAndStatus(userId, status): List<TeamMember>`
  - `TeamMemberRepository.countByTeamIdAndStatus(teamId, status): Long`
  - `TeamMemberRepository.transition(membershipId, teamId, expected, next, decidedBy: UUID?, occurredAt): Int`
  - `TeamMemberRepository.activate(membershipId, teamId, expected, decidedBy: UUID?, occurredAt): Int`
  - `TeamMemberRepository.changeRole(membershipId, teamId, expected: TeamRole, next: TeamRole, occurredAt): Int`
  - `SearchGroupTeamRepository.findAllByTeamIdAndStatus(teamId, status): List<SearchGroupTeam>`
  - `SearchGroupTeamRepository.findByGroupIdAndTeamId(groupId, teamId): SearchGroupTeam?`
  - `SearchGroupTeamRepository.transition(supportId, groupId, expected, next, decidedBy: UUID?, occurredAt): Int`
- Consumes (Task 1): `ErrorCode.NOT_FOUND_TEAM`, `NOT_FOUND_TEAM_MEMBERSHIP`, `TEAM_LEADER_REQUIRED`, `TEAM_LEADER_CANNOT_LEAVE`, `INVALID_COLLABORATION_INPUT`, `SEARCH_GROUP_STATE_CONFLICT`
- Consumes (Task 3): `SearchGroupAccessResolver.resolve(groupId: UUID, userId: UUID?): GroupAccess?`, `GroupAccess.ownerUserId`, `GroupAccess.postId`
- Consumes (Task 4): `SearchGroupEventRecorder.record(groupId: UUID, type: SearchGroupEventType, actorId: UUID?, targetId: UUID?, detail: String?)` (`Propagation.MANDATORY` — 반드시 `@Transactional` 메서드 안에서 호출)
- Consumes (Task 5): `GroupNotificationPublisher.notifyUser(userId, type, actorUserId, postId, groupId, teamId, body)`, `GroupNotificationPublisher.notifyTeamMembers(teamId, groupId, postId, type, excluding, actorUserId, body)`, `NotificationType.TEAM_MEMBER_REQUESTED / TEAM_MEMBER_APPROVED / TEAM_MEMBER_REJECTED / TEAM_MEMBER_REMOVED / TEAM_LEADERSHIP_TRANSFERRED / TEAM_SUPPORT_ENDED`
  - **Task 5 가 추가해야 하는 것**: `NotificationType` 에 `TEAM_ARCHIVED` 상수, `GroupNotificationTemplates.PHASE1_TYPES` 에 `NotificationType.TEAM_ARCHIVED`, `titleOf` 에 `NotificationType.TEAM_ARCHIVED -> "팀이 보관되었어요"`, `bodyOf` 에 `NotificationType.TEAM_ARCHIVED -> "이 팀의 활동이 종료되어 함께 찾기 지원도 모두 종료되었어요."`, `linkOf` 의 팀 이벤트 분기(`teamId?.let { "/teams/$it" }`)에 `TEAM_ARCHIVED` 추가. `GROUP_SYSTEM_EVENT` 로 대체하지 않는다(phase 2/3 예약 상수).
- Produces (Task 9·10·11 이 의존):
  - `TeamService.create(userId: UUID, userName: String?, name: String, description: String?): TeamResponse`
  - `TeamService.list(q: String?, size: Long, offset: Long, viewerId: UUID?): TeamService.TeamPageResult`
  - `TeamService.detail(teamId: UUID, viewerId: UUID?): TeamResponse` — ARCHIVED 팀은 404 `NOT_FOUND_TEAM`
  - `TeamService.update(teamId: UUID, leaderUserId: UUID, name: String, description: String?): TeamResponse`
  - `TeamService.transferLeadership(teamId: UUID, currentLeaderUserId: UUID, targetMembershipId: UUID): TeamResponse`
  - `TeamService.archive(teamId: UUID, leaderUserId: UUID): TeamResponse`
  - `TeamMembershipService.request(teamId: UUID, userId: UUID, userName: String?): TeamMembershipResponse`
  - `TeamMembershipService.approve(teamId: UUID, membershipId: UUID, leaderUserId: UUID): TeamMembershipResponse`
  - `TeamMembershipService.reject(teamId: UUID, membershipId: UUID, leaderUserId: UUID): TeamMembershipResponse`
  - `TeamMembershipService.remove(teamId: UUID, membershipId: UUID, leaderUserId: UUID): TeamMembershipResponse`
  - `TeamMembershipService.leaveMe(teamId: UUID, userId: UUID): TeamMembershipResponse`
  - `TeamMembershipService.list(teamId: UUID, viewerId: UUID): List<TeamMembershipResponse>`
  - DTO `CreateTeamRequest`, `UpdateTeamRequest`, `TransferTeamLeadershipRequest`, `TeamResponse`, `TeamSummaryResponse`, `TeamMembershipResponse`

---

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/team/TeamMembershipIT.kt`

```kotlin
package com.park.animal.team

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.GroupNotificationPublisher
import com.park.animal.searchgroup.SearchGroupEventRecorder
import com.park.animal.searchgroup.access.AccessSource
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 팀 · 팀 멤버십 · 팀장 이전 · 팀 보관 통합 테스트 (설계 §6.4, §22.2).
 *
 * - `uq_tm_single_active_leader` 가 "팀당 활성 팀장 1명" 불변식을 DB 로 강제하는지 확인한다.
 * - 팀원 변경이 수색그룹 권한에 즉시 반영되는지(파생 권한, 복사 없음) 고정한다.
 * - 팀 보관이 파생 권한을 회수하고, 그 뒤 마지막 팀장이 팀을 나갈 수 있는지 고정한다(설계 §6.4).
 *
 * InnoDB 제약 위반과 조건부 UPDATE 의 실제 영향 행 수를 관찰해야 하므로 테스트 트랜잭션을 끈다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    NotificationService::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    GroupNotificationPublisher::class,
    TeamService::class,
    TeamMembershipService::class,
)
@Testcontainers
class TeamMembershipIT {
    @Autowired lateinit var teamService: TeamService

    @Autowired lateinit var teamMembershipService: TeamMembershipService

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var accessResolver: SearchGroupAccessResolver

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clean() {
        listOf(
            "search_group_team",
            "search_group_member",
            "search_group_user_block",
            "search_group_event",
            "search_group",
            "team_member",
            "team",
            "notification",
            "post_bookmark",
            "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }
    }

    private fun savePost(ownerId: UUID): Post =
        postRepository.save(
            Post(
                authorId = ownerId,
                authorName = "보호자",
                title = "말티즈를 찾습니다",
                phoneNum = "010-0000-0000",
                time = LocalDateTime.now(),
                place = "서울 강남구 역삼동",
                gender = "남아",
                gratuity = 0,
                description = "역삼동에서 실종된 하얀 말티즈입니다.",
                lat = 37.5012,
                lng = 127.0396,
                openChatUrl = null,
                missingAnimalStatus = MissingAnimalStatus.SEARCHING,
                animalType = AnimalType.DOG,
            ),
        )

    /** 특정 팀이 ACTIVE 로 지원 중인 수색그룹 하나를 만든다. */
    private fun openGroupSupportedBy(
        ownerId: UUID,
        teamId: UUID,
        decidedBy: UUID,
    ): SearchGroup {
        val post = savePost(ownerId)
        val group = searchGroupRepository.save(SearchGroup(postId = post.id))
        val now = LocalDateTime.now()
        searchGroupTeamRepository.save(
            SearchGroupTeam(
                groupId = group.id,
                teamId = teamId,
                status = SearchGroupTeamStatus.ACTIVE,
                requestedBy = ownerId,
                requestedAt = now,
                decidedBy = decidedBy,
                decidedAt = now,
                activatedAt = now,
            ),
        )
        return group
    }

    @Test
    fun `팀을 만들면 생성자가 유일한 활성 팀장이 된다`() {
        val leaderId = UUID.randomUUID()

        val team = teamService.create(leaderId, "팀장", "한강 수색대", "야간 위주로 움직여요")

        assertEquals("한강 수색대", team.name)
        assertEquals(TeamRole.LEADER, team.viewerRole)
        assertEquals(TeamMemberStatus.ACTIVE, team.viewerStatus)
        assertEquals(1L, team.activeMemberCount)

        val leaders =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND role = 'LEADER' AND status = 'ACTIVE'",
                Int::class.java,
                team.id.toString(),
            )
        assertEquals(1, leaders)
    }

    @Test
    fun `두 번째 활성 팀장은 DB 제약이 막는다`() {
        val leaderId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "야간 수색대", null)

        assertFailsWith<DataIntegrityViolationException> {
            teamMemberRepository.save(
                TeamMember(
                    teamId = team.id,
                    userId = UUID.randomUUID(),
                    userName = "가짜 팀장",
                    role = TeamRole.LEADER,
                    status = TeamMemberStatus.ACTIVE,
                    joinedAt = LocalDateTime.now(),
                    requestedAt = LocalDateTime.now(),
                    decidedAt = LocalDateTime.now(),
                    decidedBy = leaderId,
                ),
            )
        }
    }

    @Test
    fun `팀장 이전 후에도 활성 팀장은 정확히 한 명이다`() {
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "주말 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "새 팀원")
        teamMembershipService.approve(team.id, requested.id, leaderId)

        teamService.transferLeadership(team.id, leaderId, requested.id)

        val leaders =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND role = 'LEADER' AND status = 'ACTIVE'",
                Int::class.java,
                team.id.toString(),
            )
        assertEquals(1, leaders)

        val newLeader = teamMemberRepository.findByTeamIdAndUserId(team.id, memberId)
        assertNotNull(newLeader)
        assertEquals(TeamRole.LEADER, newLeader.role)

        val oldLeader = teamMemberRepository.findByTeamIdAndUserId(team.id, leaderId)
        assertNotNull(oldLeader)
        assertEquals(TeamRole.MEMBER, oldLeader.role)
        assertEquals(TeamMemberStatus.ACTIVE, oldLeader.status)
    }

    @Test
    fun `활성 팀의 팀장은 팀을 나갈 수 없다`() {
        val leaderId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "새벽 수색대", null)

        val e = assertFailsWith<BusinessException> { teamMembershipService.leaveMe(team.id, leaderId) }
        assertEquals(ErrorCode.TEAM_LEADER_CANNOT_LEAVE, e.errorCode)
    }

    @Test
    fun `팀원이 팀을 나가면 그 팀이 지원하던 수색그룹 접근이 즉시 사라진다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()

        val team = teamService.create(leaderId, "팀장", "한강 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, requested.id, leaderId)
        val group = openGroupSupportedBy(ownerId, team.id, leaderId)

        val before = accessResolver.resolve(group.id, memberId)
        assertNotNull(before)
        assertEquals(GroupRole.PARTICIPANT, before.role)
        assertTrue(AccessSource.TEAM in before.sources)

        teamMembershipService.leaveMe(team.id, memberId)

        val after = accessResolver.resolve(group.id, memberId)
        assertNotNull(after)
        assertEquals(GroupRole.NONE, after.role, "팀 탈퇴 즉시 파생 권한이 사라져야 한다")
    }

    @Test
    fun `다른 팀의 membershipId 로 승인하면 404`() {
        val leaderA = UUID.randomUUID()
        val leaderB = UUID.randomUUID()
        val applicant = UUID.randomUUID()
        val teamA = teamService.create(leaderA, "팀장A", "A 수색대", null)
        val teamB = teamService.create(leaderB, "팀장B", "B 수색대", null)
        val requestedInA = teamMembershipService.request(teamA.id, applicant, "신청자")

        val e =
            assertFailsWith<BusinessException> {
                teamMembershipService.approve(teamB.id, requestedInA.id, leaderB)
            }
        assertEquals(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP, e.errorCode)
    }

    @Test
    fun `팀을 보관하면 파생 권한이 회수되고 그 뒤 팀장도 팀을 나갈 수 있다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()

        val team = teamService.create(leaderId, "팀장", "해체 예정 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, requested.id, leaderId)
        val group = openGroupSupportedBy(ownerId, team.id, leaderId)

        val before = accessResolver.resolve(group.id, memberId)
        assertNotNull(before)
        assertEquals(GroupRole.PARTICIPANT, before.role)

        val archived = teamService.archive(team.id, leaderId)
        assertEquals(TeamStatus.ARCHIVED, archived.status)

        val support = searchGroupTeamRepository.findByGroupIdAndTeamId(group.id, team.id)
        assertNotNull(support)
        assertEquals(SearchGroupTeamStatus.WITHDRAWN, support.status, "보관은 활성 지원 연결을 회수한다")

        val after = accessResolver.resolve(group.id, memberId)
        assertNotNull(after)
        assertEquals(GroupRole.NONE, after.role, "팀 보관 즉시 파생 권한이 사라져야 한다")

        val left = teamMembershipService.leaveMe(team.id, leaderId)
        assertEquals(TeamMemberStatus.LEFT, left.status)
        assertEquals(TeamRole.MEMBER, left.role, "이탈 행에 LEADER 가 남지 않는다")
    }

    @Test
    fun `팀원은 팀을 보관할 수 없다`() {
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "보관 시도 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, requested.id, leaderId)

        val e = assertFailsWith<BusinessException> { teamService.archive(team.id, memberId) }
        assertEquals(ErrorCode.TEAM_LEADER_REQUIRED, e.errorCode)
    }

    @Test
    fun `보관은 멱등하고 보관된 팀 상세 조회는 404`() {
        val leaderId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "두 번 보관 수색대", null)

        assertEquals(TeamStatus.ARCHIVED, teamService.archive(team.id, leaderId).status)
        assertEquals(TeamStatus.ARCHIVED, teamService.archive(team.id, leaderId).status)

        val e = assertFailsWith<BusinessException> { teamService.detail(team.id, leaderId) }
        assertEquals(ErrorCode.NOT_FOUND_TEAM, e.errorCode)
    }

    @Test
    fun `팀 목록은 비로그인이면 viewer 필드가 null 이고 보관된 팀은 빠진다`() {
        val leaderId = UUID.randomUUID()
        val live = teamService.create(leaderId, "팀장", "살아있는 수색대", null)
        val gone = teamService.create(UUID.randomUUID(), "다른 팀장", "사라질 수색대", null)
        teamService.archive(gone.id, gone.viewerRole.let { leaderOf(gone.id) })

        val anonymous = teamService.list(null, 20L, 0L, null)
        assertEquals(listOf(live.id), anonymous.contents.map { it.id })
        assertNull(anonymous.contents.first().viewerRole)
        assertNull(anonymous.contents.first().viewerStatus)

        val asLeader = teamService.list(null, 20L, 0L, leaderId)
        assertEquals(TeamRole.LEADER, asLeader.contents.first().viewerRole)
        assertEquals(TeamMemberStatus.ACTIVE, asLeader.contents.first().viewerStatus)
    }

    /** 팀 생성자(=활성 팀장)의 userId 를 되찾는다. */
    private fun leaderOf(teamId: UUID): UUID =
        teamMemberRepository
            .findFirstByTeamIdAndRoleAndStatus(teamId, TeamRole.LEADER, TeamMemberStatus.ACTIVE)!!
            .userId

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && \
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.team.TeamMembershipIT"
```

Expected: FAIL — 컴파일 단계에서 중단된다.
```
e: .../src/test/kotlin/com/park/animal/team/TeamMembershipIT.kt:? Unresolved reference: TeamService
e: .../src/test/kotlin/com/park/animal/team/TeamMembershipIT.kt:? Unresolved reference: TeamMembershipService
```

- [ ] **Step 3-a: 요청 · 응답 DTO**

`src/main/kotlin/com/park/animal/team/dto/TeamRequests.kt`

```kotlin
package com.park.animal.team.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "팀 생성 요청")
data class CreateTeamRequest(
    @Schema(description = "팀 이름 (2~30자)", example = "한강 수색대")
    val name: String,
    @Schema(description = "팀 소개 (200자 이하)", example = "야간 위주로 움직여요")
    val description: String? = null,
)

@Schema(description = "팀 수정 요청 (팀장 전용)")
data class UpdateTeamRequest(
    @Schema(description = "팀 이름 (2~30자)", example = "한강 야간 수색대")
    val name: String,
    @Schema(description = "팀 소개 (200자 이하)")
    val description: String? = null,
)

@Schema(description = "팀장 이전 요청")
data class TransferTeamLeadershipRequest(
    @Schema(description = "팀장이 될 팀원의 멤버십 id")
    val targetMembershipId: UUID,
)
```

`src/main/kotlin/com/park/animal/team/dto/TeamResponses.kt`

```kotlin
package com.park.animal.team.dto

import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "팀 목록 항목")
data class TeamSummaryResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val status: TeamStatus,
    @Schema(description = "활성 팀원 수 (팀장 포함)")
    val activeMemberCount: Long,
    @Schema(description = "조회자의 역할. 비로그인이거나 활성/대기 멤버십이 없으면 null")
    val viewerRole: TeamRole?,
    @Schema(description = "조회자의 멤버십 상태. ACTIVE / PENDING 만 값이 내려가고 그 외에는 null")
    val viewerStatus: TeamMemberStatus?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun of(
            team: Team,
            activeMemberCount: Long,
            viewer: TeamMember?,
        ): TeamSummaryResponse =
            TeamSummaryResponse(
                id = team.id,
                name = team.name,
                description = team.description,
                status = team.status,
                activeMemberCount = activeMemberCount,
                viewerRole = viewer?.role,
                viewerStatus = viewer?.status,
                createdAt = team.createdAt,
            )
    }
}

@Schema(description = "팀 상세 + 조회자의 역할")
data class TeamResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val status: TeamStatus,
    val activeMemberCount: Long,
    @Schema(description = "승인 대기 인원. 팀장에게만 실제 값이 내려간다")
    val pendingMemberCount: Long,
    @Schema(description = "현재 팀장 표시 이름")
    val leaderName: String?,
    @Schema(description = "조회자의 역할. 비로그인/비소속이면 null")
    val viewerRole: TeamRole?,
    @Schema(description = "조회자의 멤버십 상태. 비로그인/비소속이면 null")
    val viewerStatus: TeamMemberStatus?,
    val createdAt: LocalDateTime,
)

@Schema(description = "팀 멤버십")
data class TeamMembershipResponse(
    val id: UUID,
    val teamId: UUID,
    val userId: UUID,
    val userName: String?,
    val role: TeamRole,
    val status: TeamMemberStatus,
    val joinedAt: LocalDateTime?,
    val requestedAt: LocalDateTime?,
) {
    companion object {
        fun from(member: TeamMember): TeamMembershipResponse =
            TeamMembershipResponse(
                id = member.id,
                teamId = member.teamId,
                userId = member.userId,
                userName = member.userName,
                role = member.role,
                status = member.status,
                joinedAt = member.joinedAt,
                requestedAt = member.requestedAt,
            )
    }
}
```

- [ ] **Step 3-b: `TeamService`**

`src/main/kotlin/com/park/animal/team/TeamService.kt`

```kotlin
package com.park.animal.team

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.GroupNotificationPublisher
import com.park.animal.searchgroup.SearchGroupEventRecorder
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.team.dto.TeamResponse
import com.park.animal.team.dto.TeamSummaryResponse
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 생성 · 탐색 · 수정 · 팀장 이전 · 보관.
 *
 * 입력 검증은 서비스에서 명시 코드로 한다 — 이 레포에는 `spring-boot-starter-validation` 이 없어
 * `@Valid` / `@field:NotBlank` 가 무동작이다.
 *
 * 팀원 구성은 수색그룹 권한의 **파생 원본**이다. 팀원을 각 그룹 멤버 테이블로 복사하지 않으므로
 * 이 서비스의 어떤 변경도 수색그룹 쪽에 별도 동기화 코드를 필요로 하지 않는다(설계 §6.6, §22.2).
 * 예외가 딱 하나 있는데 **팀 보관**이다 — 팀이 사라지면 `search_group_team` 의 ACTIVE 연결도
 * 함께 회수해야 파생 권한이 남지 않는다([archive]).
 *
 * **동시성**: 상태를 바꾸는 메서드는 비관적 락을 쓰지 않는다(계약 F23). 안전성은
 * `UPDATE ... WHERE id = :id AND team_id = :teamId AND <expected>` 조건부 전이가 보장하고,
 * 영향 행 0 이면 현재 상태를 재조회해 이미 목표 상태면 멱등 성공, 아니면 409 로 끝낸다.
 * 재조회가 경합 상대의 커밋을 보려면 스냅샷이 최신이어야 하므로 이 트랜잭션들만
 * `Isolation.READ_COMMITTED` 로 낮춘다 — MySQL 기본 REPEATABLE READ 에서는 진 쪽이
 * 트랜잭션 첫 읽기 시점의 스냅샷을 계속 보게 되어 멱등 판정이 409 로 오판된다.
 */
@Service
class TeamService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val searchGroupTeamRepository: SearchGroupTeamRepository,
    private val accessResolver: SearchGroupAccessResolver,
    private val eventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
) {
    companion object {
        const val NAME_MIN_LENGTH = 2
        const val NAME_MAX_LENGTH = 30
        const val DESCRIPTION_MAX_LENGTH = 200
        const val MAX_PAGE_SIZE = 50L
    }

    data class TeamPageResult(
        val contents: List<TeamSummaryResponse>,
        val hasNextPage: Boolean,
        val totalCount: Long,
    )

    @Transactional
    fun create(
        userId: UUID,
        userName: String?,
        name: String,
        description: String?,
    ): TeamResponse {
        val team =
            teamRepository.save(
                Team(
                    name = normalizeName(name),
                    description = normalizeDescription(description),
                    status = TeamStatus.ACTIVE,
                    createdBy = userId,
                ),
            )
        val now = LocalDateTime.now()
        teamMemberRepository.save(
            TeamMember(
                teamId = team.id,
                userId = userId,
                userName = userName,
                role = TeamRole.LEADER,
                status = TeamMemberStatus.ACTIVE,
                joinedAt = now,
                requestedAt = now,
                decidedAt = now,
                decidedBy = userId,
            ),
        )
        return detailOf(team, viewerId = userId)
    }

    /**
     * 공개 팀 목록. 활성 팀만 이름순으로 내려간다.
     *
     * 조회자의 멤버십은 ACTIVE / PENDING 두 번의 사용자 스코프 조회로 한 번에 모은다.
     * 활성 팀원 수는 페이지당 최대 [MAX_PAGE_SIZE] 건이므로 팀별 count 로 충분하다.
     */
    @Transactional(readOnly = true)
    fun list(
        q: String?,
        size: Long,
        offset: Long,
        viewerId: UUID?,
    ): TeamPageResult {
        val pageSize = size.coerceIn(1L, MAX_PAGE_SIZE).toInt()
        val pageIndex = (offset.coerceAtLeast(0L) / pageSize).toInt()
        val page =
            teamRepository.searchByName(
                q?.trim().orEmpty(),
                TeamStatus.ACTIVE,
                PageRequest.of(pageIndex, pageSize),
            )
        val viewerMemberships = viewerId?.let { loadViewerMemberships(it) } ?: emptyMap()
        return TeamPageResult(
            contents =
                page.content.map { team ->
                    TeamSummaryResponse.of(
                        team = team,
                        activeMemberCount = teamMemberRepository.countByTeamIdAndStatus(team.id, TeamMemberStatus.ACTIVE),
                        viewer = viewerMemberships[team.id],
                    )
                },
            hasNextPage = page.hasNext(),
            totalCount = page.totalElements,
        )
    }

    /** 보관된 팀은 공개 조회에서 존재하지 않는 것으로 취급한다(404). */
    @Transactional(readOnly = true)
    fun detail(
        teamId: UUID,
        viewerId: UUID?,
    ): TeamResponse {
        val team = requireTeam(teamId)
        if (team.status != TeamStatus.ACTIVE) throw BusinessException(ErrorCode.NOT_FOUND_TEAM)
        return detailOf(team, viewerId)
    }

    @Transactional
    fun update(
        teamId: UUID,
        leaderUserId: UUID,
        name: String,
        description: String?,
    ): TeamResponse {
        val team = requireActiveTeam(teamId)
        requireActiveLeader(teamId, leaderUserId)
        team.name = normalizeName(name)
        team.description = normalizeDescription(description)
        return detailOf(team, viewerId = leaderUserId)
    }

    /**
     * 팀장 이전. 한 트랜잭션 안에서 **강등 UPDATE → 승격 UPDATE** 순서로 두 번 실행한다.
     *
     * `CASE WHEN` 단일 UPDATE 로 합치면 `uq_tm_single_active_leader` (STORED generated column) 가
     * 행 단위로 검사되어 스캔 순서에 따라 1062 duplicate key 가 난다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun transferLeadership(
        teamId: UUID,
        currentLeaderUserId: UUID,
        targetMembershipId: UUID,
    ): TeamResponse {
        val team = requireActiveTeam(teamId)
        val leader = requireActiveLeader(teamId, currentLeaderUserId)
        val target =
            teamMemberRepository.findByIdAndTeamId(targetMembershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        if (target.id == leader.id) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        if (target.status != TeamMemberStatus.ACTIVE || target.role != TeamRole.MEMBER) {
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        val now = LocalDateTime.now()
        val leaderMembershipId = leader.id
        val targetMembership = target.id
        val targetUserId = target.userId
        val teamName = team.name

        // 인자 순서: (membershipId, teamId, expected, next, occurredAt)
        val demoted =
            teamMemberRepository.changeRole(leaderMembershipId, teamId, TeamRole.LEADER, TeamRole.MEMBER, now)
        if (demoted == 0) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val promoted =
            teamMemberRepository.changeRole(targetMembership, teamId, TeamRole.MEMBER, TeamRole.LEADER, now)
        if (promoted == 0) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        notificationPublisher.notifyUser(
            userId = targetUserId,
            type = NotificationType.TEAM_LEADERSHIP_TRANSFERRED,
            actorUserId = currentLeaderUserId,
            postId = null,
            groupId = null,
            teamId = teamId,
            body = "'$teamName' 팀의 팀장이 되었어요.",
        )
        // 전임 팀장에게는 결과 확인 알림이 필요하다. actorUserId 를 실어 보내면 publisher 의
        // skipSelf 규칙에 걸려 사라지므로 행위자 없이 발행한다(설계 §9).
        notificationPublisher.notifyUser(
            userId = currentLeaderUserId,
            type = NotificationType.TEAM_LEADERSHIP_TRANSFERRED,
            actorUserId = null,
            postId = null,
            groupId = null,
            teamId = teamId,
            body = "'$teamName' 팀의 팀장 권한을 넘겼어요.",
        )

        return detailOf(requireTeam(teamId), viewerId = currentLeaderUserId)
    }

    /**
     * 팀 보관 (설계 §6.4 "후임에게 이전 **또는 팀을 보관 처리**"). 팀장 전용.
     *
     * 순서가 곧 안전성이다.
     * 1. `team.status` ACTIVE → ARCHIVED 조건부 전이. 이미 ARCHIVED 면 멱등 성공.
     * 2. 그 팀의 ACTIVE `search_group_team` 을 전부 WITHDRAWN 으로 전이해 **파생 권한을 회수**한다.
     *    이걸 빼먹으면 보관된 팀의 팀원이 계속 수색그룹을 읽는다.
     * 3. 활성 팀원 전원에게 `TEAM_ARCHIVED` 알림.
     *
     * 보관 후에는 [TeamMembershipService.leaveMe] 가 팀장 이탈을 허용한다 — 마지막 팀장이
     * 팀에 영원히 묶이는 막다른 길을 없애는 것이 이 API 의 존재 이유다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun archive(
        teamId: UUID,
        leaderUserId: UUID,
    ): TeamResponse {
        requireTeam(teamId)
        requireActiveLeader(teamId, leaderUserId)

        val now = LocalDateTime.now()
        // 인자 순서: (teamId, expected, next, occurredAt)
        val moved = teamRepository.archiveIfStatus(teamId, TeamStatus.ACTIVE, TeamStatus.ARCHIVED, now)
        if (moved == 0) {
            val reread = requireTeam(teamId)
            if (reread.status == TeamStatus.ARCHIVED) return detailOf(reread, viewerId = leaderUserId)
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        withdrawActiveSupports(teamId, leaderUserId, now)
        notificationPublisher.notifyTeamMembers(
            teamId = teamId,
            groupId = null,
            postId = null,
            type = NotificationType.TEAM_ARCHIVED,
            excluding = setOf(leaderUserId),
            actorUserId = leaderUserId,
            body = null,
        )
        return detailOf(requireTeam(teamId), viewerId = leaderUserId)
    }

    /**
     * 보관된 팀이 지원 중이던 모든 연결을 회수한다.
     *
     * 조건부 전이가 0행을 돌려주면(다른 경로가 먼저 종료한 경우) 조용히 건너뛴다 — 목표 상태가
     * 이미 달성된 것이므로 실패가 아니다.
     */
    private fun withdrawActiveSupports(
        teamId: UUID,
        actorUserId: UUID,
        now: LocalDateTime,
    ) {
        val supports = searchGroupTeamRepository.findAllByTeamIdAndStatus(teamId, SearchGroupTeamStatus.ACTIVE)
        supports.forEach { support ->
            val groupId = support.groupId
            // 인자 순서: (supportId, groupId, expected, next, decidedBy, occurredAt)
            val moved =
                searchGroupTeamRepository.transition(
                    support.id,
                    groupId,
                    SearchGroupTeamStatus.ACTIVE,
                    SearchGroupTeamStatus.WITHDRAWN,
                    actorUserId,
                    now,
                )
            if (moved == 0) return@forEach

            eventRecorder.record(
                groupId = groupId,
                type = SearchGroupEventType.TEAM_SUPPORT_WITHDRAWN,
                actorId = actorUserId,
                targetId = teamId,
                detail = "ACTIVE -> WITHDRAWN (team archived)",
            )
            val access = accessResolver.resolve(groupId, actorUserId) ?: return@forEach
            if (access.ownerUserId != actorUserId) {
                notificationPublisher.notifyUser(
                    userId = access.ownerUserId,
                    type = NotificationType.TEAM_SUPPORT_ENDED,
                    actorUserId = actorUserId,
                    postId = access.postId,
                    groupId = groupId,
                    teamId = teamId,
                    body = null,
                )
            }
        }
    }

    private fun loadViewerMemberships(viewerId: UUID): Map<UUID, TeamMember> =
        (
            teamMemberRepository.findAllByUserIdAndStatus(viewerId, TeamMemberStatus.ACTIVE) +
                teamMemberRepository.findAllByUserIdAndStatus(viewerId, TeamMemberStatus.PENDING)
        ).associateBy { it.teamId }

    private fun detailOf(
        team: Team,
        viewerId: UUID?,
    ): TeamResponse {
        val viewer = viewerId?.let { teamMemberRepository.findByTeamIdAndUserId(team.id, it) }
        val viewerIsLeader = viewer != null && viewer.status == TeamMemberStatus.ACTIVE && viewer.role == TeamRole.LEADER
        return TeamResponse(
            id = team.id,
            name = team.name,
            description = team.description,
            status = team.status,
            activeMemberCount = teamMemberRepository.countByTeamIdAndStatus(team.id, TeamMemberStatus.ACTIVE),
            pendingMemberCount =
                if (viewerIsLeader) {
                    teamMemberRepository.countByTeamIdAndStatus(team.id, TeamMemberStatus.PENDING)
                } else {
                    0L
                },
            leaderName =
                teamMemberRepository
                    .findFirstByTeamIdAndRoleAndStatus(team.id, TeamRole.LEADER, TeamMemberStatus.ACTIVE)
                    ?.userName,
            viewerRole = viewer?.role,
            viewerStatus = viewer?.status,
            createdAt = team.createdAt,
        )
    }

    private fun requireTeam(teamId: UUID): Team =
        teamRepository.findByIdAndDeletedAtIsNull(teamId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM)

    private fun requireActiveTeam(teamId: UUID): Team {
        val team = requireTeam(teamId)
        if (team.status != TeamStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        return team
    }

    private fun requireActiveLeader(
        teamId: UUID,
        userId: UUID,
    ): TeamMember {
        val me = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
        if (me == null || me.status != TeamMemberStatus.ACTIVE || me.role != TeamRole.LEADER) {
            throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
        }
        return me
    }

    private fun normalizeName(raw: String): String {
        val name = raw.trim()
        if (name.length !in NAME_MIN_LENGTH..NAME_MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_COLLABORATION_INPUT)
        }
        return name
    }

    private fun normalizeDescription(raw: String?): String? {
        val description = raw?.trim()
        if (description.isNullOrEmpty()) return null
        if (description.length > DESCRIPTION_MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_COLLABORATION_INPUT)
        }
        return description
    }
}
```

- [ ] **Step 3-c: `TeamMembershipService`**

`src/main/kotlin/com/park/animal/team/TeamMembershipService.kt`

```kotlin
package com.park.animal.team

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.GroupNotificationPublisher
import com.park.animal.team.dto.TeamMembershipResponse
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 참여 요청 · 승인 · 거절 · 내보내기 · 탈퇴.
 *
 * 규칙:
 * - 재가입은 새 INSERT 가 아니라 기존 행의 status 전이다(`uq_tm_team_user`).
 * - 모든 전이는 `WHERE id = :id AND team_id = :teamId AND status = :expected` 조건부 UPDATE 다.
 *   영향 행 0 = 이미 다른 상태 → 재조회 후 목표 상태면 멱등 반환, 아니면 409.
 *   비관적 락은 쓰지 않는다(계약 F23). 대신 이 트랜잭션들을 [Isolation.READ_COMMITTED] 로 낮춰
 *   재조회가 경합 상대의 커밋을 보게 한다.
 * - LEFT / REMOVED 로 나가는 행은 role 을 MEMBER 로 되돌린다.
 * - 팀원 변경은 그대로 수색그룹 권한이다. 팀원 목록을 그룹 멤버 테이블로 복사하지 않으므로
 *   탈퇴·내보내기 직후 해당 팀이 지원 중인 모든 그룹의 파생 권한이 즉시 사라진다(설계 §6.6, §22.2).
 *   이 서비스에는 그룹 쪽으로 나가는 동기화 호출이 존재하지 않는 것이 정상이다.
 * - **보관된 팀**에서는 참여/승인/거절/내보내기가 막히지만 [leaveMe] 만은 허용된다.
 *   설계 §6.4 의 "보관 처리 후 팀장 이탈" 경로가 여기서 성립한다.
 */
@Service
class TeamMembershipService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val notificationPublisher: GroupNotificationPublisher,
) {
    companion object {
        /** 팀원 목록 1회 조회 상한. phase 1 목록은 페이징 파라미터를 노출하지 않는다. */
        const val MAX_LIST_SIZE = 200
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun request(
        teamId: UUID,
        userId: UUID,
        userName: String?,
    ): TeamMembershipResponse {
        val team = requireActiveTeam(teamId)
        val now = LocalDateTime.now()
        val existing = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)

        if (existing == null) {
            val saved =
                teamMemberRepository.save(
                    TeamMember(
                        teamId = teamId,
                        userId = userId,
                        userName = userName,
                        role = TeamRole.MEMBER,
                        status = TeamMemberStatus.PENDING,
                        joinedAt = null,
                        requestedAt = now,
                        decidedAt = null,
                        decidedBy = null,
                    ),
                )
            notifyLeader(team, actorUserId = userId)
            return TeamMembershipResponse.from(saved)
        }

        return when (existing.status) {
            // 이미 팀원이거나 이미 대기 중 — 재클릭/재시도는 같은 행 하나만 남긴다.
            TeamMemberStatus.ACTIVE, TeamMemberStatus.PENDING -> TeamMembershipResponse.from(existing)
            TeamMemberStatus.REJECTED, TeamMemberStatus.LEFT, TeamMemberStatus.REMOVED -> {
                // 인자 순서: (membershipId, teamId, expected, next, decidedBy, occurredAt)
                val moved =
                    teamMemberRepository.transition(
                        existing.id,
                        teamId,
                        existing.status,
                        TeamMemberStatus.PENDING,
                        null,
                        now,
                    )
                val reread =
                    teamMemberRepository.findByIdAndTeamId(existing.id, teamId)
                        ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
                if (moved == 0) {
                    if (reread.status == TeamMemberStatus.ACTIVE || reread.status == TeamMemberStatus.PENDING) {
                        return TeamMembershipResponse.from(reread)
                    }
                    throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
                }
                // 재신청은 같은 행의 전이다. 대기 행에 남아 있던 이전 결정 흔적을 지우고
                // 신청 시각·표시 이름만 새로 채운다(관리 대상 엔티티라 dirty checking 으로 반영된다).
                reread.role = TeamRole.MEMBER
                reread.requestedAt = now
                reread.decidedAt = null
                reread.decidedBy = null
                if (userName != null) reread.userName = userName
                notifyLeader(team, actorUserId = userId)
                TeamMembershipResponse.from(reread)
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun approve(
        teamId: UUID,
        membershipId: UUID,
        leaderUserId: UUID,
    ): TeamMembershipResponse {
        val team = requireActiveTeam(teamId)
        requireActiveLeader(teamId, leaderUserId)
        // 설계 §16.1 — 다른 팀의 membershipId 는 tuple 조회에서 걸러져 404 가 된다.
        val target =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)

        val now = LocalDateTime.now()
        // 인자 순서: (membershipId, teamId, expected, decidedBy, occurredAt)
        val moved = teamMemberRepository.activate(target.id, teamId, TeamMemberStatus.PENDING, leaderUserId, now)
        val reread =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        if (moved == 0) {
            if (reread.status == TeamMemberStatus.ACTIVE) return TeamMembershipResponse.from(reread)
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        notificationPublisher.notifyUser(
            userId = reread.userId,
            type = NotificationType.TEAM_MEMBER_APPROVED,
            actorUserId = leaderUserId,
            postId = null,
            groupId = null,
            teamId = teamId,
            body = "'${team.name}' 팀의 팀원이 되었어요.",
        )
        return TeamMembershipResponse.from(reread)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun reject(
        teamId: UUID,
        membershipId: UUID,
        leaderUserId: UUID,
    ): TeamMembershipResponse {
        val team = requireActiveTeam(teamId)
        requireActiveLeader(teamId, leaderUserId)
        val target =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)

        val now = LocalDateTime.now()
        val moved =
            teamMemberRepository.transition(
                target.id,
                teamId,
                TeamMemberStatus.PENDING,
                TeamMemberStatus.REJECTED,
                leaderUserId,
                now,
            )
        val reread =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        if (moved == 0) {
            if (reread.status == TeamMemberStatus.REJECTED) return TeamMembershipResponse.from(reread)
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        notificationPublisher.notifyUser(
            userId = reread.userId,
            type = NotificationType.TEAM_MEMBER_REJECTED,
            actorUserId = leaderUserId,
            postId = null,
            groupId = null,
            teamId = teamId,
            body = "'${team.name}' 팀의 참여 요청이 받아들여지지 않았어요.",
        )
        return TeamMembershipResponse.from(reread)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun remove(
        teamId: UUID,
        membershipId: UUID,
        leaderUserId: UUID,
    ): TeamMembershipResponse {
        val team = requireActiveTeam(teamId)
        val leader = requireActiveLeader(teamId, leaderUserId)
        val target =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        // 팀장이 자기 자신을 내보내는 것은 팀장 이탈과 같다.
        if (target.id == leader.id) throw BusinessException(ErrorCode.TEAM_LEADER_CANNOT_LEAVE)

        val now = LocalDateTime.now()
        val moved =
            teamMemberRepository.transition(
                target.id,
                teamId,
                TeamMemberStatus.ACTIVE,
                TeamMemberStatus.REMOVED,
                leaderUserId,
                now,
            )
        val reread =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        if (moved == 0) {
            if (reread.status == TeamMemberStatus.REMOVED) return TeamMembershipResponse.from(reread)
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        notificationPublisher.notifyUser(
            userId = reread.userId,
            type = NotificationType.TEAM_MEMBER_REMOVED,
            actorUserId = leaderUserId,
            postId = null,
            groupId = null,
            teamId = teamId,
            body = "'${team.name}' 팀에서 나오게 되었어요.",
        )
        return TeamMembershipResponse.from(reread)
    }

    /**
     * 팀 나가기.
     *
     * 활성 팀의 팀장은 나갈 수 없다(409 `TEAM_LEADER_CANNOT_LEAVE`) — 후임에게 넘기거나
     * 팀을 보관해야 한다(설계 §6.4). 보관된 팀에서는 팀장도 나갈 수 있고, 이때 이력 정합을 위해
     * role 을 MEMBER 로 먼저 되돌린 뒤 status 를 전이한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun leaveMe(
        teamId: UUID,
        userId: UUID,
    ): TeamMembershipResponse {
        val team = requireTeam(teamId)
        val me =
            teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        val isActiveLeader = me.status == TeamMemberStatus.ACTIVE && me.role == TeamRole.LEADER
        if (isActiveLeader && team.status == TeamStatus.ACTIVE) {
            throw BusinessException(ErrorCode.TEAM_LEADER_CANNOT_LEAVE)
        }
        if (me.status == TeamMemberStatus.LEFT) return TeamMembershipResponse.from(me)
        if (me.status != TeamMemberStatus.ACTIVE && me.status != TeamMemberStatus.PENDING) {
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        val now = LocalDateTime.now()
        val membershipId = me.id
        val expected = me.status
        if (isActiveLeader) {
            // 보관된 팀의 마지막 팀장. LEFT 행에 LEADER 가 남지 않도록 먼저 강등한다.
            teamMemberRepository.changeRole(membershipId, teamId, TeamRole.LEADER, TeamRole.MEMBER, now)
        }

        val moved =
            teamMemberRepository.transition(membershipId, teamId, expected, TeamMemberStatus.LEFT, userId, now)
        val reread =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        if (moved == 0) {
            if (reread.status == TeamMemberStatus.LEFT) return TeamMembershipResponse.from(reread)
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }
        return TeamMembershipResponse.from(reread)
    }

    /** 팀장은 승인 대기까지, 그 외 로그인 사용자는 활성 팀원만 본다. */
    @Transactional(readOnly = true)
    fun list(
        teamId: UUID,
        viewerId: UUID,
    ): List<TeamMembershipResponse> {
        val team = requireTeam(teamId)
        val viewer = teamMemberRepository.findByTeamIdAndUserId(team.id, viewerId)
        val viewerIsLeader = viewer != null && viewer.status == TeamMemberStatus.ACTIVE && viewer.role == TeamRole.LEADER
        if (!viewerIsLeader) {
            return teamMemberRepository
                .findAllByTeamIdAndStatus(team.id, TeamMemberStatus.ACTIVE)
                .map(TeamMembershipResponse::from)
        }
        return teamMemberRepository
            .findAllByTeamIdOrderByCreatedAtDescIdDesc(team.id, PageRequest.of(0, MAX_LIST_SIZE))
            .content
            .filter { it.status == TeamMemberStatus.ACTIVE || it.status == TeamMemberStatus.PENDING }
            .map(TeamMembershipResponse::from)
    }

    private fun notifyLeader(
        team: Team,
        actorUserId: UUID,
    ) {
        val leader =
            teamMemberRepository.findFirstByTeamIdAndRoleAndStatus(
                team.id,
                TeamRole.LEADER,
                TeamMemberStatus.ACTIVE,
            ) ?: return
        notificationPublisher.notifyUser(
            userId = leader.userId,
            type = NotificationType.TEAM_MEMBER_REQUESTED,
            actorUserId = actorUserId,
            postId = null,
            groupId = null,
            teamId = team.id,
            body = "'${team.name}' 팀에 새 참여 요청이 도착했어요.",
        )
    }

    private fun requireTeam(teamId: UUID): Team =
        teamRepository.findByIdAndDeletedAtIsNull(teamId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM)

    private fun requireActiveTeam(teamId: UUID): Team {
        val team = requireTeam(teamId)
        if (team.status != TeamStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        return team
    }

    private fun requireActiveLeader(
        teamId: UUID,
        userId: UUID,
    ): TeamMember {
        val me = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
        if (me == null || me.status != TeamMemberStatus.ACTIVE || me.role != TeamRole.LEADER) {
            throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
        }
        return me
    }
}
```

- [ ] **Step 3-d: 컨트롤러**

`src/main/kotlin/com/park/animal/team/TeamController.kt`

```kotlin
package com.park.animal.team

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.team.dto.CreateTeamRequest
import com.park.animal.team.dto.TeamResponse
import com.park.animal.team.dto.TeamSummaryResponse
import com.park.animal.team.dto.TransferTeamLeadershipRequest
import com.park.animal.team.dto.UpdateTeamRequest
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.PaginatedApiResponseBody
import org.woo.http.PaginatedApiResponseDto
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class TeamController(
    private val teamService: TeamService,
) {
    @PostMapping("/teams")
    @Operation(
        summary = "팀 생성",
        description = "생성자가 팀장이 된다. 이름 2~30자, 소개 200자 이하.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun create(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @RequestBody request: CreateTeamRequest,
    ): SucceededApiResponseBody<TeamResponse> {
        val userName = runCatching { passport.requireUserContext().userName.toString() }.getOrNull()
        val created = teamService.create(passport.userId, userName, request.name, request.description)
        return SucceededApiResponseBody(data = created)
    }

    @PublicEndPoint
    @GetMapping("/teams")
    @Operation(
        summary = "팀 목록 (공개)",
        description =
            "활성 팀만 이름순으로 조회한다. q 는 팀 이름 부분 일치. " +
                "로그인 상태면 각 항목의 viewerRole / viewerStatus 가 채워지고 비로그인이면 null 이다.",
    )
    fun list(
        @RequestParam(name = "q", required = false) q: String?,
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") size: Long,
        @RequestParam(name = "pageOffset", required = false, defaultValue = "0") offset: Long,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        passport: Passport?,
    ): PaginatedApiResponseBody<TeamSummaryResponse> {
        val page = teamService.list(q, size, offset, passport?.userId)
        return PaginatedApiResponseBody(
            data =
                PaginatedApiResponseDto(
                    contents = page.contents,
                    hasNextPage = page.hasNextPage,
                    totalCount = page.totalCount,
                ),
        )
    }

    @PublicEndPoint
    @GetMapping("/teams/{teamId}")
    @Operation(
        summary = "팀 상세 (공개)",
        description = "로그인 사용자면 viewerRole/viewerStatus 로 본인의 역할을 함께 내려준다. 보관된 팀은 404.",
    )
    fun detail(
        @PathVariable("teamId") teamId: UUID,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        passport: Passport?,
    ): SucceededApiResponseBody<TeamResponse> = SucceededApiResponseBody(data = teamService.detail(teamId, passport?.userId))

    @PatchMapping("/teams/{teamId}")
    @Operation(
        summary = "팀 정보 수정 (팀장)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun update(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @RequestBody request: UpdateTeamRequest,
    ): SucceededApiResponseBody<TeamResponse> =
        SucceededApiResponseBody(
            data = teamService.update(teamId, passport.userId, request.name, request.description),
        )

    @PostMapping("/teams/{teamId}/transfer-leadership")
    @Operation(
        summary = "팀장 권한 이전 (팀장)",
        description = "대상은 활성 팀원이어야 한다. 이전 후 활성 팀장은 계속 한 명이다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun transferLeadership(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @RequestBody request: TransferTeamLeadershipRequest,
    ): SucceededApiResponseBody<TeamResponse> =
        SucceededApiResponseBody(
            data = teamService.transferLeadership(teamId, passport.userId, request.targetMembershipId),
        )

    /** 계약 §8 #33 — 설계 §6.4 근거, §18 표에는 없던 추가. */
    @PostMapping("/teams/{teamId}/archive")
    @Operation(
        summary = "팀 보관 (팀장)",
        description =
            "팀 활동을 종료한다. 지원 중이던 모든 수색그룹 연결이 함께 종료되어 팀원의 파생 권한이 사라지고, " +
                "보관 후에는 팀장도 팀을 나갈 수 있다. 이미 보관된 팀에 다시 호출해도 같은 결과를 돌려준다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun archive(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
    ): SucceededApiResponseBody<TeamResponse> = SucceededApiResponseBody(data = teamService.archive(teamId, passport.userId))
}
```

`src/main/kotlin/com/park/animal/team/TeamMembershipController.kt`

```kotlin
package com.park.animal.team

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.team.dto.TeamMembershipResponse
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class TeamMembershipController(
    private val teamMembershipService: TeamMembershipService,
) {
    @PostMapping("/teams/{teamId}/memberships")
    @Operation(
        summary = "팀 참여 요청",
        description = "팀장 승인 후 팀원이 된다. 이미 팀원이거나 대기 중이면 같은 멤버십을 그대로 돌려준다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun request(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> {
        val userName = runCatching { passport.requireUserContext().userName.toString() }.getOrNull()
        return SucceededApiResponseBody(data = teamMembershipService.request(teamId, passport.userId, userName))
    }

    @GetMapping("/teams/{teamId}/memberships")
    @Operation(
        summary = "팀원 목록",
        description = "팀장은 승인 대기까지, 그 외에는 활성 팀원만 조회한다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
    ): SucceededApiResponseBody<List<TeamMembershipResponse>> =
        SucceededApiResponseBody(data = teamMembershipService.list(teamId, passport.userId))

    @PostMapping("/teams/{teamId}/memberships/{membershipId}/approve")
    @Operation(
        summary = "팀원 승인 (팀장)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun approve(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> =
        SucceededApiResponseBody(data = teamMembershipService.approve(teamId, membershipId, passport.userId))

    @PostMapping("/teams/{teamId}/memberships/{membershipId}/reject")
    @Operation(
        summary = "팀원 거절 (팀장)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun reject(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> =
        SucceededApiResponseBody(data = teamMembershipService.reject(teamId, membershipId, passport.userId))

    @PostMapping("/teams/{teamId}/memberships/{membershipId}/remove")
    @Operation(
        summary = "팀원 내보내기 (팀장)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun remove(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> =
        SucceededApiResponseBody(data = teamMembershipService.remove(teamId, membershipId, passport.userId))

    @DeleteMapping("/teams/{teamId}/memberships/me")
    @Operation(
        summary = "팀 나가기",
        description = "활성 팀의 팀장은 팀장 권한을 넘기거나 팀을 보관한 뒤에 나갈 수 있다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun leaveMe(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> =
        SucceededApiResponseBody(data = teamMembershipService.leaveMe(teamId, passport.userId))
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && \
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.team.TeamMembershipIT"
```

Expected: PASS — `BUILD SUCCESSFUL`, 10 tests. 특히 `팀을 보관하면 파생 권한이 회수되고 그 뒤 팀장도 팀을 나갈 수 있다` 가 보관 → `search_group_team` WITHDRAWN → `GroupRole.NONE` → 팀장 `LEFT` 순서를 모두 확인한다.

Run:
```bash
cd /Users/park/Desktop/project/animal && \
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.searchgroup.*"
```

Expected: PASS — Task 3~7 회귀 없음.

- [ ] **Step 5: 커밋**

```bash
cd /Users/park/Desktop/project/animal && \
git add src/main/kotlin/com/park/animal/team src/test/kotlin/com/park/animal/team/TeamMembershipIT.kt && \
git commit -m "feat(search-group): 팀·팀 멤버십·팀장 이전·팀 보관 추가

- 팀 생성 시 생성자를 활성 팀장으로 함께 저장 (같은 트랜잭션)
- 팀장 이전은 강등 UPDATE 후 승격 UPDATE 2회 — uq_tm_single_active_leader 1062 회피
- 참여 요청/승인/거절/내보내기/탈퇴를 Task 2 리포지토리의 조건부 status 전이로 처리 (재가입은 기존 행 전이)
- 비관적 락 없이 조건부 UPDATE + READ_COMMITTED 재조회로 멱등 판정
- 팀 보관(POST /teams/{teamId}/archive, 계약 §8 #33): 지원 연결 전량 WITHDRAWN 회수 + 팀원 알림,
  보관 후 마지막 팀장 탈퇴 허용 (설계 §6.4)
- GET /teams 공개화 + viewerRole/viewerStatus, GET /teams/{teamId} 는 보관 팀에 404
- 팀원 변경은 수색그룹 파생 권한에 즉시 반영 (복사 없음) — IT 로 고정"
```

---

### Task 9: 팀 지원 연결 (양방향)

**Files:**
- Create: `src/main/kotlin/com/park/animal/searchgroup/dto/CreateTeamSupportRequest.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupTeamSupportResponse.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportService.kt`
- Create: `src/main/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportController.kt`
- Test: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportIT.kt`

> **리포지토리를 새로 정의하거나 전체 교체하지 않는다.** `SearchGroupTeamRepository` 는 Task 2 가 최종 시그니처로 이미 만들어 두었고, 이 태스크는 **그대로 호출만** 한다. 조건부 전이(`transition` / `activate`)는 **위치 인자로 호출**한다.

**Interfaces:**
- Consumes (Task 2): `SearchGroupTeam(groupId, teamId, status, requestedBy, requestedAt, decidedBy, decidedAt, activatedAt)`, `SearchGroupTeamStatus`, 그리고 리포지토리
  - `SearchGroupTeamRepository.findByGroupIdAndTeamId(groupId, teamId): SearchGroupTeam?`
  - `SearchGroupTeamRepository.findByIdAndGroupId(id, groupId): SearchGroupTeam?`
  - `SearchGroupTeamRepository.findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId, pageable): Page<SearchGroupTeam>`
  - `SearchGroupTeamRepository.transition(supportId, groupId, expected, next, decidedBy: UUID?, occurredAt): Int`
  - `SearchGroupTeamRepository.activate(supportId, groupId, expected, decidedBy: UUID?, activatedAt): Int`
- Consumes (Task 3): `SearchGroupAccessResolver.requireVisible(groupId: UUID, userId: UUID): GroupAccess`, `GroupAccess.postId`, `GroupAccess.groupStatus`, `GroupAccess.isOwner`, `GroupAccess.ownerUserId`
- Consumes (Task 4): `SearchGroupEventRecorder.record(groupId: UUID, type: SearchGroupEventType, actorId: UUID?, targetId: UUID?, detail: String?)` (`Propagation.MANDATORY`)
- Consumes (Task 5): `GroupNotificationPublisher.notifyOwner(access, type, actorUserId, actorName, body)`, `.notifyUser(userId, type, actorUserId, postId, groupId, teamId, body)`, `.notifyTeamMembers(teamId, groupId, postId, type, excluding, actorUserId, body)`; `NotificationType.TEAM_SUPPORT_REQUESTED / TEAM_SUPPORT_ACCEPTED / TEAM_SUPPORT_DECLINED / TEAM_SUPPORT_ENDED`
- Consumes (Task 8): `TeamRepository.findByIdAndDeletedAtIsNull`, `TeamMemberRepository.findByTeamIdAndUserId`, `TeamMemberRepository.findFirstByTeamIdAndRoleAndStatus`, `TeamMemberRepository.findAllByUserIdAndStatus`, `TeamService.create`, `TeamMembershipService.request/approve` (IT 용)
- Produces (Task 10·11 이 의존):
  - `SearchGroupTeamSupportService.request(groupId: UUID, teamId: UUID, actorUserId: UUID, message: String? = null): SearchGroupTeamSupportResponse` — **기본값 필수** (Task 11 이 3인자로 호출한다)
  - `SearchGroupTeamSupportService.accept(groupId: UUID, supportId: UUID, actorUserId: UUID): SearchGroupTeamSupportResponse`
  - `SearchGroupTeamSupportService.decline(groupId: UUID, supportId: UUID, actorUserId: UUID): SearchGroupTeamSupportResponse`
  - `SearchGroupTeamSupportService.end(groupId: UUID, supportId: UUID, actorUserId: UUID): SearchGroupTeamSupportResponse`
  - `SearchGroupTeamSupportService.list(groupId: UUID, userId: UUID): List<SearchGroupTeamSupportResponse>`
  - DTO `CreateTeamSupportRequest(teamId: UUID, message: String?)`, `SearchGroupTeamSupportResponse`
- Produces (관측, 설계 §20): 카운터 `fmp.searchgroup.team_support.requested` (tag `direction` = `team_to_group` / `owner_to_team`). id·메시지 본문은 label 에 넣지 않는다.

---

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportIT.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.team.TeamMembershipService
import com.park.animal.team.TeamService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 팀 지원 연결(양방향) 통합 테스트 — 설계 §6.5, §8.4, §14.3.
 *
 * 초기 status 는 클라이언트 입력이 아니라 서버가 tuple(group, post.authorId, team_member)로 계산한다.
 * 동시 수락 경합은 두 스레드가 서비스를 직접 호출해 재현한다 — 서비스 자신의 트랜잭션 경계와
 * 격리 수준(READ_COMMITTED)이 그대로 적용돼야 멱등 판정이 실제 운영과 같아진다.
 * 테스트 트랜잭션 없이 raw JDBC COUNT 로 최종 상태를 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    NotificationService::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    GroupNotificationPublisher::class,
    TeamService::class,
    TeamMembershipService::class,
    SearchGroupTeamSupportService::class,
    SearchGroupTeamSupportIT.MetricsTestConfig::class,
)
@Testcontainers
class SearchGroupTeamSupportIT {
    /** @DataJpaTest 슬라이스에는 MeterRegistry 가 없다. 관측 카운터 검증을 위해 in-memory 레지스트리를 준다. */
    @TestConfiguration
    class MetricsTestConfig {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Autowired lateinit var supportService: SearchGroupTeamSupportService

    @Autowired lateinit var teamService: TeamService

    @Autowired lateinit var teamMembershipService: TeamMembershipService

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var accessResolver: SearchGroupAccessResolver

    @Autowired lateinit var meterRegistry: MeterRegistry

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clean() {
        listOf(
            "search_group_team",
            "search_group_member",
            "search_group_user_block",
            "search_group_event",
            "search_group",
            "team_member",
            "team",
            "notification",
            "post_bookmark",
            "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }
    }

    private fun openGroup(ownerId: UUID): SearchGroup {
        val post =
            postRepository.save(
                Post(
                    authorId = ownerId,
                    authorName = "보호자",
                    title = "말티즈를 찾습니다",
                    phoneNum = "010-0000-0000",
                    time = LocalDateTime.now(),
                    place = "서울 강남구 역삼동",
                    gender = "남아",
                    gratuity = 0,
                    description = "역삼동에서 실종된 하얀 말티즈입니다.",
                    lat = 37.5012,
                    lng = 127.0396,
                    openChatUrl = null,
                    missingAnimalStatus = MissingAnimalStatus.SEARCHING,
                    animalType = AnimalType.DOG,
                ),
            )
        return searchGroupRepository.save(SearchGroup(postId = post.id))
    }

    private fun requestedCounterTotal(): Double =
        meterRegistry
            .find("fmp.searchgroup.team_support.requested")
            .counters()
            .sumOf { it.count() }

    @Test
    fun `팀장이 제안하면 보호자 승인 대기, 보호자가 요청하면 팀장 승인 대기`() {
        val ownerA = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val groupA = openGroup(ownerA)
        val team = teamService.create(leaderId, "팀장", "한강 수색대", null)

        val byLeader = supportService.request(groupA.id, team.id, leaderId, "저희가 돕고 싶어요")
        assertEquals(SearchGroupTeamStatus.PENDING_GROUP_APPROVAL, byLeader.status)

        val ownerB = UUID.randomUUID()
        val groupB = openGroup(ownerB)
        // message 를 생략해 기본값(null)을 함께 고정한다.
        val byOwner = supportService.request(groupB.id, team.id, ownerB)
        assertEquals(SearchGroupTeamStatus.PENDING_TEAM_APPROVAL, byOwner.status)

        // 보호자이면서 그 팀의 팀장이면 승인 왕복 없이 바로 활성화된다.
        val selfId = UUID.randomUUID()
        val groupC = openGroup(selfId)
        val selfTeam = teamService.create(selfId, "보호자겸팀장", "자체 수색대", null)
        val bySelf = supportService.request(groupC.id, selfTeam.id, selfId)
        assertEquals(SearchGroupTeamStatus.ACTIVE, bySelf.status)
        assertNotNull(bySelf.activatedAt)
    }

    @Test
    fun `팀원은 팀 지원을 요청할 수 없다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "권한 확인 수색대", null)
        val membership = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, membership.id, leaderId)

        val e =
            assertFailsWith<BusinessException> {
                supportService.request(group.id, team.id, memberId)
            }
        assertEquals(ErrorCode.TEAM_LEADER_REQUIRED, e.errorCode, "팀과 무관한 사용자가 아니라 비팀장 팀원이다")

        val outsider = UUID.randomUUID()
        val e2 =
            assertFailsWith<BusinessException> {
                supportService.request(group.id, team.id, outsider)
            }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e2.errorCode)
    }

    @Test
    fun `요청 생성은 승인 대기 카운터를 올린다`() {
        val before = requestedCounterTotal()

        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "관측 수색대", null)
        supportService.request(group.id, team.id, leaderId)

        assertEquals(before + 1.0, requestedCounterTotal(), 0.0001)
    }

    @Test
    fun `동시 수락 경합 - ACTIVE 는 한 행, 수락 알림도 한 번만`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "야간 수색대", null)
        val support = supportService.request(group.id, team.id, leaderId)
        assertEquals(SearchGroupTeamStatus.PENDING_GROUP_APPROVAL, support.status)

        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val outcomes = ConcurrentHashMap<Int, String>()
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) { idx ->
            pool.submit {
                start.await()
                val outcome =
                    runCatching {
                        supportService.accept(group.id, support.id, ownerId).status.name
                    }.getOrElse { t -> "EX:" + ((t as? BusinessException)?.errorCode?.name ?: t.javaClass.simpleName) }
                outcomes[idx] = outcome
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "두 스레드가 모두 끝나야 한다")
        pool.shutdown()

        assertEquals(setOf("ACTIVE"), outcomes.values.toSet(), "경합에서 진 쪽도 멱등하게 ACTIVE 를 받아야 한다: $outcomes")

        val activeRows =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_group_team WHERE group_id = ? AND status = 'ACTIVE'",
                Int::class.java,
                group.id.toString(),
            )
        assertEquals(1, activeRows)

        val missingActivatedAt =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_group_team WHERE group_id = ? AND status = 'ACTIVE' AND activated_at IS NULL",
                Int::class.java,
                group.id.toString(),
            )
        assertEquals(0, missingActivatedAt, "ACTIVE 전이는 activated_at 을 반드시 채운다")

        val acceptedNoti =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE type = 'TEAM_SUPPORT_ACCEPTED'",
                Int::class.java,
            )
        assertEquals(1, acceptedNoti, "수락 알림은 팀장에게 한 번만 발행돼야 한다")
    }

    @Test
    fun `지원을 종료하면 팀원의 그룹 접근이 즉시 사라진다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "주말 수색대", null)
        val membership = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, membership.id, leaderId)

        val support = supportService.request(group.id, team.id, ownerId)
        assertEquals(SearchGroupTeamStatus.PENDING_TEAM_APPROVAL, support.status)
        val accepted = supportService.accept(group.id, support.id, leaderId)
        assertEquals(SearchGroupTeamStatus.ACTIVE, accepted.status)

        val before = accessResolver.resolve(group.id, memberId)
        assertNotNull(before)
        assertEquals(GroupRole.PARTICIPANT, before.role)

        val ended = supportService.end(group.id, support.id, leaderId)
        assertEquals(SearchGroupTeamStatus.WITHDRAWN, ended.status)

        val after = accessResolver.resolve(group.id, memberId)
        assertNotNull(after)
        assertEquals(GroupRole.NONE, after.role, "지원 종료 즉시 파생 권한이 사라져야 한다")

        // 보호자와 다른 참여 경로에는 영향이 없다.
        val ownerAccess = accessResolver.resolve(group.id, ownerId)
        assertNotNull(ownerAccess)
        assertEquals(GroupRole.OWNER, ownerAccess.role)
    }

    @Test
    fun `다른 그룹의 supportId 로 수락하면 404 이고 원본 상태는 그대로다`() {
        val ownerA = UUID.randomUUID()
        val ownerB = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val groupA = openGroup(ownerA)
        val groupB = openGroup(ownerB)
        val team = teamService.create(leaderId, "팀장", "새벽 수색대", null)
        val supportInA = supportService.request(groupA.id, team.id, leaderId)

        val e =
            assertFailsWith<BusinessException> {
                supportService.accept(groupB.id, supportInA.id, ownerB)
            }
        assertEquals(ErrorCode.NOT_FOUND_TEAM_SUPPORT, e.errorCode)

        assertEquals(
            SearchGroupTeamStatus.PENDING_GROUP_APPROVAL,
            searchGroupTeamRepository.findByIdAndGroupId(supportInA.id, groupA.id)!!.status,
        )
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && \
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.searchgroup.SearchGroupTeamSupportIT"
```

Expected: FAIL — 컴파일 단계에서 중단된다.
```
e: .../src/test/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportIT.kt:? Unresolved reference: SearchGroupTeamSupportService
```

- [ ] **Step 3-a: DTO**

`src/main/kotlin/com/park/animal/searchgroup/dto/CreateTeamSupportRequest.kt`

```kotlin
package com.park.animal.searchgroup.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * 팀 지원 연결 요청.
 *
 * 초기 status 와 방향(direction)은 **요청 본문에 두지 않는다**. 서버가
 * tuple(group, post.authorId, team_member(teamId, actorUserId, LEADER, ACTIVE))로 계산한다 —
 * 클라이언트가 보낸 값을 신뢰하면 보호자/팀장 승인 단계를 건너뛰는 IDOR 가 된다(설계 §16.1).
 */
@Schema(description = "팀 지원 연결 요청")
data class CreateTeamSupportRequest(
    @Schema(description = "지원할 팀 id")
    val teamId: UUID,
    @Schema(description = "상대에게 전달할 짧은 메시지 (200자 이하). 저장하지 않고 알림 본문에만 쓴다")
    val message: String? = null,
)
```

`src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupTeamSupportResponse.kt`

```kotlin
package com.park.animal.searchgroup.dto

import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "수색그룹 - 팀 지원 연결")
data class SearchGroupTeamSupportResponse(
    val id: UUID,
    val groupId: UUID,
    val teamId: UUID,
    val teamName: String?,
    val status: SearchGroupTeamStatus,
    @Schema(
        description =
            "이 연결을 최초로 만든 사용자. 종료 후 재요청으로 되살아난 행은 최초 요청자를 유지한다 " +
                "— 상태 전이는 status/decidedBy 만 바꾸기 때문이다",
    )
    val requestedBy: UUID,
    val requestedAt: LocalDateTime,
    val decidedAt: LocalDateTime?,
    @Schema(description = "지원이 활성화된 시각. 팀원의 기록 열람 시작점")
    val activatedAt: LocalDateTime?,
) {
    companion object {
        fun of(
            support: SearchGroupTeam,
            teamName: String?,
        ): SearchGroupTeamSupportResponse =
            SearchGroupTeamSupportResponse(
                id = support.id,
                groupId = support.groupId,
                teamId = support.teamId,
                teamName = teamName,
                status = support.status,
                requestedBy = support.requestedBy,
                requestedAt = support.requestedAt,
                decidedAt = support.decidedAt,
                activatedAt = support.activatedAt,
            )
    }
}
```

- [ ] **Step 3-b: 서비스**

`src/main/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportService.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.access.GroupAccess
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupTeamSupportResponse
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 지원 연결(양방향) — 설계 §6.5, §8.4, §14.3.
 *
 * 방향 판정 규칙(서버 계산, 클라이언트 입력 금지):
 * - 액터가 보호자면 팀 승인 대기 `PENDING_TEAM_APPROVAL`
 * - 액터가 그 팀의 활성 팀장이면 보호자 승인 대기 `PENDING_GROUP_APPROVAL`
 * - 둘 다면 즉시 `ACTIVE` — 같은 사람이 자기 자신의 승인을 기다리는 막다른 길을 만들지 않는다.
 *   자동 활성화 사실은 감사 기록에 남긴다.
 * - 그 팀의 활성 팀원이지만 팀장이 아니면 403 `TEAM_LEADER_REQUIRED`
 *   (설계 §7 "우리 팀의 지원 종료 — 팀장만"). 팀과 아무 관계도 없으면 403 `SEARCH_GROUP_ACCESS_DENIED`.
 *
 * `(group_id, team_id)` 가 UNIQUE 이므로 재요청은 새 INSERT 가 아니라 기존 행의 조건부 status 전이다.
 *
 * **동시성**: 비관적 락을 쓰지 않는다(계약 F23). 안전성은
 * `UPDATE ... WHERE id = :id AND group_id = :groupId AND status = :expected` 조건부 전이가 보장하고,
 * 영향 행 0 이면 현재 상태를 재조회해 이미 목표 상태면 멱등 성공, 아니면 409 로 끝낸다.
 * 재조회가 경합 상대의 커밋을 보려면 스냅샷이 최신이어야 하므로 상태를 바꾸는 트랜잭션만
 * [Isolation.READ_COMMITTED] 로 낮춘다 — MySQL 기본 REPEATABLE READ 에서는 진 쪽이 트랜잭션
 * 첫 읽기 시점의 스냅샷을 계속 보게 되어 멱등 판정이 409 로 오판된다.
 *
 * **관측(설계 §20)**: 승인 대기 생성 시 [REQUESTED_COUNTER] 를 올린다. label 에는 방향만 넣고
 * id·메시지 본문·좌표는 넣지 않는다.
 */
@Service
class SearchGroupTeamSupportService(
    private val searchGroupTeamRepository: SearchGroupTeamRepository,
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val accessResolver: SearchGroupAccessResolver,
    private val eventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        const val MESSAGE_MAX_LENGTH = 200

        /** 목록 1회 조회 상한. phase 1 목록은 페이징 파라미터를 노출하지 않는다. */
        const val MAX_LIST_SIZE = 100

        const val REQUESTED_COUNTER = "fmp.searchgroup.team_support.requested"
    }

    /** 액터가 특정 팀에 대해 갖는 지위. 권한 오류를 403 두 종류로 정확히 가르기 위한 값이다. */
    private enum class TeamStanding { LEADER, MEMBER, OUTSIDER }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun request(
        groupId: UUID,
        teamId: UUID,
        actorUserId: UUID,
        message: String? = null,
    ): SearchGroupTeamSupportResponse {
        val access = requireWritableGroup(groupId, actorUserId)
        val team = requireActiveTeam(teamId)
        val note = normalizeMessage(message)

        val isOwner = access.isOwner
        val standing = standingIn(teamId, actorUserId)
        val initial =
            when {
                isOwner && standing == TeamStanding.LEADER -> SearchGroupTeamStatus.ACTIVE
                isOwner -> SearchGroupTeamStatus.PENDING_TEAM_APPROVAL
                standing == TeamStanding.LEADER -> SearchGroupTeamStatus.PENDING_GROUP_APPROVAL
                standing == TeamStanding.MEMBER -> throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
                else -> throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
            }

        val now = LocalDateTime.now()
        val existing = searchGroupTeamRepository.findByGroupIdAndTeamId(groupId, teamId)

        if (existing == null) {
            val saved =
                searchGroupTeamRepository.save(
                    SearchGroupTeam(
                        groupId = groupId,
                        teamId = teamId,
                        status = initial,
                        requestedBy = actorUserId,
                        requestedAt = now,
                        decidedBy = if (initial == SearchGroupTeamStatus.ACTIVE) actorUserId else null,
                        decidedAt = if (initial == SearchGroupTeamStatus.ACTIVE) now else null,
                        activatedAt = if (initial == SearchGroupTeamStatus.ACTIVE) now else null,
                    ),
                )
            afterRequested(access, team, saved, actorUserId, note)
            return SearchGroupTeamSupportResponse.of(saved, team.name)
        }

        return when (existing.status) {
            // 이미 활성이거나 이미 대기 중 — 재전송은 같은 행 하나만 유지한다.
            SearchGroupTeamStatus.ACTIVE,
            SearchGroupTeamStatus.PENDING_GROUP_APPROVAL,
            SearchGroupTeamStatus.PENDING_TEAM_APPROVAL,
            -> SearchGroupTeamSupportResponse.of(existing, team.name)

            SearchGroupTeamStatus.DECLINED,
            SearchGroupTeamStatus.WITHDRAWN,
            SearchGroupTeamStatus.REMOVED,
            -> {
                val moved =
                    if (initial == SearchGroupTeamStatus.ACTIVE) {
                        // 인자 순서: (supportId, groupId, expected, decidedBy, activatedAt)
                        searchGroupTeamRepository.activate(
                            existing.id,
                            groupId,
                            existing.status,
                            actorUserId,
                            now,
                        )
                    } else {
                        // 인자 순서: (supportId, groupId, expected, next, decidedBy, occurredAt)
                        // 대기 상태로 되살아나는 행이므로 결정자는 비운다.
                        searchGroupTeamRepository.transition(
                            existing.id,
                            groupId,
                            existing.status,
                            initial,
                            null,
                            now,
                        )
                    }
                val reread =
                    searchGroupTeamRepository.findByIdAndGroupId(existing.id, groupId)
                        ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
                if (moved == 0) {
                    if (reread.status == SearchGroupTeamStatus.ACTIVE ||
                        reread.status == SearchGroupTeamStatus.PENDING_GROUP_APPROVAL ||
                        reread.status == SearchGroupTeamStatus.PENDING_TEAM_APPROVAL
                    ) {
                        return SearchGroupTeamSupportResponse.of(reread, team.name)
                    }
                    throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
                }
                // 되살아난 연결의 요청 시각은 이번 요청 시각이다(관리 대상 엔티티라 dirty checking 으로 반영).
                reread.requestedAt = now
                if (initial != SearchGroupTeamStatus.ACTIVE) {
                    reread.decidedAt = null
                    reread.activatedAt = null
                }
                afterRequested(access, team, reread, actorUserId, note)
                SearchGroupTeamSupportResponse.of(reread, team.name)
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun accept(
        groupId: UUID,
        supportId: UUID,
        actorUserId: UUID,
    ): SearchGroupTeamSupportResponse {
        val access = requireWritableGroup(groupId, actorUserId)
        val support =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        val teamId = support.teamId
        val team = requireActiveTeam(teamId)
        // 보호자는 팀이 제안한 건만, 팀장은 보호자가 요청한 건만 수락할 수 있다.
        val expected = expectedPendingFor(access, support, teamId, actorUserId)

        val now = LocalDateTime.now()
        val moved = searchGroupTeamRepository.activate(supportId, groupId, expected, actorUserId, now)
        val reread =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        if (moved == 0) {
            // 반대편이 먼저 수락한 경합: 이미 ACTIVE 면 멱등 성공, 그 외에는 상태 충돌.
            if (reread.status == SearchGroupTeamStatus.ACTIVE) {
                return SearchGroupTeamSupportResponse.of(reread, team.name)
            }
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        eventRecorder.record(
            groupId = groupId,
            type = SearchGroupEventType.TEAM_SUPPORT_ACCEPTED,
            actorId = actorUserId,
            targetId = teamId,
            detail = "${expected.name} -> ACTIVE",
        )
        notificationPublisher.notifyTeamMembers(
            teamId = teamId,
            groupId = groupId,
            postId = access.postId,
            type = NotificationType.TEAM_SUPPORT_ACCEPTED,
            excluding = setOf(actorUserId),
            actorUserId = actorUserId,
            body = "'${team.name}' 팀이 함께 찾기에 참여하게 됐어요.",
        )
        // 보호자가 행위자면 publisher 의 skipSelf 가 중복을 지운다 — 조건 분기를 두지 않는다.
        notificationPublisher.notifyOwner(
            access = access,
            type = NotificationType.TEAM_SUPPORT_ACCEPTED,
            actorUserId = actorUserId,
            actorName = team.name,
            body = "'${team.name}' 팀의 지원이 시작됐어요.",
        )
        return SearchGroupTeamSupportResponse.of(reread, team.name)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun decline(
        groupId: UUID,
        supportId: UUID,
        actorUserId: UUID,
    ): SearchGroupTeamSupportResponse {
        val access = requireWritableGroup(groupId, actorUserId)
        val support =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        val teamId = support.teamId
        val team = requireActiveTeam(teamId)
        val expected = expectedPendingFor(access, support, teamId, actorUserId)

        val now = LocalDateTime.now()
        val moved =
            searchGroupTeamRepository.transition(
                supportId,
                groupId,
                expected,
                SearchGroupTeamStatus.DECLINED,
                actorUserId,
                now,
            )
        val reread =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        if (moved == 0) {
            if (reread.status == SearchGroupTeamStatus.DECLINED) {
                return SearchGroupTeamSupportResponse.of(reread, team.name)
            }
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        eventRecorder.record(
            groupId = groupId,
            type = SearchGroupEventType.TEAM_SUPPORT_DECLINED,
            actorId = actorUserId,
            targetId = teamId,
            detail = "${expected.name} -> DECLINED",
        )
        // 통보 대상은 반대편이다. 대기 상태가 곧 방향이므로 requestedBy 에 의존하지 않는다.
        if (expected == SearchGroupTeamStatus.PENDING_GROUP_APPROVAL) {
            val leader =
                teamMemberRepository.findFirstByTeamIdAndRoleAndStatus(teamId, TeamRole.LEADER, TeamMemberStatus.ACTIVE)
            if (leader != null) {
                notificationPublisher.notifyUser(
                    userId = leader.userId,
                    type = NotificationType.TEAM_SUPPORT_DECLINED,
                    actorUserId = actorUserId,
                    postId = access.postId,
                    groupId = groupId,
                    teamId = teamId,
                    body = "'${team.name}' 팀의 지원 제안이 받아들여지지 않았어요.",
                )
            }
        } else {
            notificationPublisher.notifyOwner(
                access = access,
                type = NotificationType.TEAM_SUPPORT_DECLINED,
                actorUserId = actorUserId,
                actorName = team.name,
                body = "'${team.name}' 팀에 보낸 지원 요청이 받아들여지지 않았어요.",
            )
        }
        return SearchGroupTeamSupportResponse.of(reread, team.name)
    }

    /**
     * 활성 지원 종료. 보호자면 `REMOVED`, 팀장이면 `WITHDRAWN`.
     *
     * 해당 연결 한 행만 전이하므로 같은 그룹의 다른 팀·직접 참여자에게는 영향이 없다(설계 §14.3).
     * 대기 중인 요청은 이 API 로 취소하지 않는다 — 상대의 거절(decline)로 정리한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun end(
        groupId: UUID,
        supportId: UUID,
        actorUserId: UUID,
    ): SearchGroupTeamSupportResponse {
        val access = requireWritableGroup(groupId, actorUserId)
        val support =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        val teamId = support.teamId
        val team = requireActiveTeam(teamId)

        val standing = standingIn(teamId, actorUserId)
        val next =
            when {
                access.isOwner -> SearchGroupTeamStatus.REMOVED
                standing == TeamStanding.LEADER -> SearchGroupTeamStatus.WITHDRAWN
                standing == TeamStanding.MEMBER -> throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
                else -> throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
            }

        val now = LocalDateTime.now()
        val moved =
            searchGroupTeamRepository.transition(
                supportId,
                groupId,
                SearchGroupTeamStatus.ACTIVE,
                next,
                actorUserId,
                now,
            )
        val reread =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        if (moved == 0) {
            if (reread.status == SearchGroupTeamStatus.REMOVED || reread.status == SearchGroupTeamStatus.WITHDRAWN) {
                return SearchGroupTeamSupportResponse.of(reread, team.name)
            }
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        eventRecorder.record(
            groupId = groupId,
            type =
                if (next == SearchGroupTeamStatus.REMOVED) {
                    SearchGroupEventType.TEAM_SUPPORT_REMOVED
                } else {
                    SearchGroupEventType.TEAM_SUPPORT_WITHDRAWN
                },
            actorId = actorUserId,
            targetId = teamId,
            detail = "ACTIVE -> ${next.name}",
        )
        notificationPublisher.notifyTeamMembers(
            teamId = teamId,
            groupId = groupId,
            postId = access.postId,
            type = NotificationType.TEAM_SUPPORT_ENDED,
            excluding = setOf(actorUserId),
            actorUserId = actorUserId,
            body = "'${team.name}' 팀의 지원이 종료됐어요.",
        )
        notificationPublisher.notifyOwner(
            access = access,
            type = NotificationType.TEAM_SUPPORT_ENDED,
            actorUserId = actorUserId,
            actorName = team.name,
            body = "'${team.name}' 팀의 지원이 종료됐어요.",
        )
        return SearchGroupTeamSupportResponse.of(reread, team.name)
    }

    /** 보호자는 전체 연결을, 팀장은 자기 팀의 연결만 본다. */
    @Transactional(readOnly = true)
    fun list(
        groupId: UUID,
        userId: UUID,
    ): List<SearchGroupTeamSupportResponse> {
        val access = accessResolver.requireVisible(groupId, userId)
        val rows =
            searchGroupTeamRepository
                .findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId, PageRequest.of(0, MAX_LIST_SIZE))
                .content
        val visible =
            if (access.isOwner) {
                rows
            } else {
                val myTeamIds =
                    teamMemberRepository
                        .findAllByUserIdAndStatus(userId, TeamMemberStatus.ACTIVE)
                        .filter { it.role == TeamRole.LEADER }
                        .map { it.teamId }
                        .toSet()
                if (myTeamIds.isEmpty()) throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
                rows.filter { it.teamId in myTeamIds }
            }
        if (visible.isEmpty()) return emptyList()
        val names =
            teamRepository
                .findAllById(visible.map { it.teamId }.distinct())
                .associate { it.id to it.name }
        return visible.map { SearchGroupTeamSupportResponse.of(it, names[it.teamId]) }
    }

    /**
     * 수락·거절이 소비할 대기 상태를 방향으로 결정한다.
     *
     * 액터가 보호자이면서 동시에 그 팀의 팀장이면 지금 걸려 있는 대기 상태를 그대로 소비한다 —
     * 양쪽 권한을 다 가진 사람이 자기 요청을 스스로 처리하는 정상 경로다.
     */
    private fun expectedPendingFor(
        access: GroupAccess,
        support: SearchGroupTeam,
        teamId: UUID,
        actorUserId: UUID,
    ): SearchGroupTeamStatus {
        val standing = standingIn(teamId, actorUserId)
        return when {
            access.isOwner && standing == TeamStanding.LEADER ->
                if (support.status == SearchGroupTeamStatus.PENDING_TEAM_APPROVAL) {
                    SearchGroupTeamStatus.PENDING_TEAM_APPROVAL
                } else {
                    SearchGroupTeamStatus.PENDING_GROUP_APPROVAL
                }
            access.isOwner -> SearchGroupTeamStatus.PENDING_GROUP_APPROVAL
            standing == TeamStanding.LEADER -> SearchGroupTeamStatus.PENDING_TEAM_APPROVAL
            standing == TeamStanding.MEMBER -> throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
            else -> throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
        }
    }

    private fun afterRequested(
        access: GroupAccess,
        team: Team,
        support: SearchGroupTeam,
        actorUserId: UUID,
        note: String?,
    ) {
        when (support.status) {
            SearchGroupTeamStatus.ACTIVE -> {
                // 보호자 = 팀장. 승인 왕복 없이 활성화했다는 사실을 감사 기록에 남긴다.
                eventRecorder.record(
                    groupId = support.groupId,
                    type = SearchGroupEventType.TEAM_SUPPORT_REQUESTED,
                    actorId = actorUserId,
                    targetId = team.id,
                    detail = "auto-active: requester is both owner and team leader",
                )
                notificationPublisher.notifyTeamMembers(
                    teamId = team.id,
                    groupId = support.groupId,
                    postId = access.postId,
                    type = NotificationType.TEAM_SUPPORT_ACCEPTED,
                    excluding = setOf(actorUserId),
                    actorUserId = actorUserId,
                    body = "'${team.name}' 팀이 함께 찾기에 참여하게 됐어요.",
                )
            }

            SearchGroupTeamStatus.PENDING_GROUP_APPROVAL -> {
                eventRecorder.record(
                    groupId = support.groupId,
                    type = SearchGroupEventType.TEAM_SUPPORT_REQUESTED,
                    actorId = actorUserId,
                    targetId = team.id,
                    detail = "-> PENDING_GROUP_APPROVAL",
                )
                meterRegistry.counter(REQUESTED_COUNTER, "direction", "team_to_group").increment()
                notificationPublisher.notifyOwner(
                    access = access,
                    type = NotificationType.TEAM_SUPPORT_REQUESTED,
                    actorUserId = actorUserId,
                    actorName = team.name,
                    body = note ?: "'${team.name}' 팀이 함께 찾기를 돕겠다고 제안했어요.",
                )
            }

            SearchGroupTeamStatus.PENDING_TEAM_APPROVAL -> {
                eventRecorder.record(
                    groupId = support.groupId,
                    type = SearchGroupEventType.TEAM_SUPPORT_REQUESTED,
                    actorId = actorUserId,
                    targetId = team.id,
                    detail = "-> PENDING_TEAM_APPROVAL",
                )
                meterRegistry.counter(REQUESTED_COUNTER, "direction", "owner_to_team").increment()
                val leader =
                    teamMemberRepository.findFirstByTeamIdAndRoleAndStatus(
                        team.id,
                        TeamRole.LEADER,
                        TeamMemberStatus.ACTIVE,
                    )
                if (leader != null) {
                    notificationPublisher.notifyUser(
                        userId = leader.userId,
                        type = NotificationType.TEAM_SUPPORT_REQUESTED,
                        actorUserId = actorUserId,
                        postId = access.postId,
                        groupId = support.groupId,
                        teamId = team.id,
                        body = note ?: "보호자가 '${team.name}' 팀에 함께 찾기를 요청했어요.",
                    )
                }
            }

            SearchGroupTeamStatus.DECLINED,
            SearchGroupTeamStatus.WITHDRAWN,
            SearchGroupTeamStatus.REMOVED,
            -> Unit
        }
    }

    private fun requireWritableGroup(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = accessResolver.requireVisible(groupId, userId)
        if (access.groupStatus != SearchGroupStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
        return access
    }

    private fun requireActiveTeam(teamId: UUID): Team {
        val team =
            teamRepository.findByIdAndDeletedAtIsNull(teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM)
        if (team.status != TeamStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        return team
    }

    private fun standingIn(
        teamId: UUID,
        userId: UUID,
    ): TeamStanding {
        val membership = teamMemberRepository.findByTeamIdAndUserId(teamId, userId) ?: return TeamStanding.OUTSIDER
        if (membership.status != TeamMemberStatus.ACTIVE) return TeamStanding.OUTSIDER
        return if (membership.role == TeamRole.LEADER) TeamStanding.LEADER else TeamStanding.MEMBER
    }

    private fun normalizeMessage(raw: String?): String? {
        val message = raw?.trim()
        if (message.isNullOrEmpty()) return null
        if (message.length > MESSAGE_MAX_LENGTH) throw BusinessException(ErrorCode.INVALID_COLLABORATION_INPUT)
        return message
    }
}
```

- [ ] **Step 3-c: 컨트롤러**

`src/main/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportController.kt`

```kotlin
package com.park.animal.searchgroup

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchgroup.dto.CreateTeamSupportRequest
import com.park.animal.searchgroup.dto.SearchGroupTeamSupportResponse
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SearchGroupTeamSupportController(
    private val searchGroupTeamSupportService: SearchGroupTeamSupportService,
) {
    @PostMapping("/search-groups/{groupId}/team-supports")
    @Operation(
        summary = "팀 지원 연결 요청",
        description =
            "보호자가 요청하면 팀장 승인 대기, 팀장이 제안하면 보호자 승인 대기 상태가 된다. " +
                "요청자가 보호자이면서 그 팀의 팀장이면 바로 활성화된다. 초기 상태는 서버가 계산한다. " +
                "팀원(비팀장)이 호출하면 403 TEAM_LEADER_REQUIRED.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun request(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestBody request: CreateTeamSupportRequest,
    ): SucceededApiResponseBody<SearchGroupTeamSupportResponse> =
        SucceededApiResponseBody(
            data =
                searchGroupTeamSupportService.request(
                    groupId = groupId,
                    teamId = request.teamId,
                    actorUserId = passport.userId,
                    message = request.message,
                ),
        )

    @GetMapping("/search-groups/{groupId}/team-supports")
    @Operation(
        summary = "팀 지원 연결 목록",
        description = "보호자는 전체, 팀장은 자기 팀의 연결만 조회한다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<List<SearchGroupTeamSupportResponse>> =
        SucceededApiResponseBody(data = searchGroupTeamSupportService.list(groupId, passport.userId))

    @PostMapping("/search-groups/{groupId}/team-supports/{supportId}/accept")
    @Operation(
        summary = "팀 지원 수락",
        description = "상대편만 수락할 수 있다. 이미 활성이면 같은 결과를 그대로 돌려준다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun accept(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("supportId") supportId: UUID,
    ): SucceededApiResponseBody<SearchGroupTeamSupportResponse> =
        SucceededApiResponseBody(data = searchGroupTeamSupportService.accept(groupId, supportId, passport.userId))

    @PostMapping("/search-groups/{groupId}/team-supports/{supportId}/decline")
    @Operation(
        summary = "팀 지원 거절",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun decline(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("supportId") supportId: UUID,
    ): SucceededApiResponseBody<SearchGroupTeamSupportResponse> =
        SucceededApiResponseBody(data = searchGroupTeamSupportService.decline(groupId, supportId, passport.userId))

    @DeleteMapping("/search-groups/{groupId}/team-supports/{supportId}")
    @Operation(
        summary = "팀 지원 종료",
        description = "보호자가 실행하면 팀 제거, 팀장이 실행하면 우리 팀의 지원 종료다. 다른 팀에는 영향이 없다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun end(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("supportId") supportId: UUID,
    ): SucceededApiResponseBody<SearchGroupTeamSupportResponse> =
        SucceededApiResponseBody(data = searchGroupTeamSupportService.end(groupId, supportId, passport.userId))
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/park/Desktop/project/animal && \
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.searchgroup.SearchGroupTeamSupportIT"
```

Expected: PASS — `BUILD SUCCESSFUL`, 6 tests. 특히 `동시 수락 경합` 은 두 스레드 모두 `ACTIVE` 를 받고, `search_group_team` 의 ACTIVE 행 1개 / `activated_at IS NULL` 0개 / `TEAM_SUPPORT_ACCEPTED` 알림 1건이어야 한다.

Run:
```bash
cd /Users/park/Desktop/project/animal && \
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test \
  --tests "com.park.animal.team.TeamMembershipIT" --tests "com.park.animal.searchgroup.*"
```

Expected: PASS — Task 8 회귀 없음.

- [ ] **Step 5: 커밋**

```bash
cd /Users/park/Desktop/project/animal && \
git add src/main/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportService.kt \
        src/main/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportController.kt \
        src/main/kotlin/com/park/animal/searchgroup/dto/CreateTeamSupportRequest.kt \
        src/main/kotlin/com/park/animal/searchgroup/dto/SearchGroupTeamSupportResponse.kt \
        src/test/kotlin/com/park/animal/searchgroup/SearchGroupTeamSupportIT.kt && \
git commit -m "feat(search-group): 팀 지원 연결(양방향 요청·수락·거절·종료) 추가

- 초기 status 는 서버가 tuple(group, post.authorId, team_member)로 계산 — 요청 본문에 status/direction 없음 (IDOR 차단)
- 보호자=팀장이면 승인 왕복 없이 즉시 ACTIVE, 사실을 감사 기록에 남김
- 팀원(비팀장)은 403 TEAM_LEADER_REQUIRED, 팀과 무관하면 403 SEARCH_GROUP_ACCESS_DENIED
- 수락은 Task 2 리포지토리의 조건부 activate + activated_at 필수 기록, 경합 패자는 재조회 후 멱등 200
  (비관적 락 없이 READ_COMMITTED 로 최신 스냅샷 확보)
- 종료는 보호자 REMOVED / 팀장 WITHDRAWN, 해당 연결 한 행만 전이
- 알림은 GroupNotificationPublisher 경유 + 대기 방향으로 상대편만 통보
- 승인 대기 생성 시 fmp.searchgroup.team_support.requested 카운터 (설계 §20)
- IT: 양방향 초기 status, 비팀장 거부, 관측 카운터, 동시 수락 경합(raw JDBC 검증), 종료 즉시 권한 회수, 타 그룹 supportId 404"
```

---

### Task 10: 마이페이지 통합 허브 (`GET /api/v1/me/search-hub`)

**Files:**
- Create: `src/main/kotlin/com/park/animal/searchhub/dto/SearchHubDtos.kt`
- Create: `src/main/kotlin/com/park/animal/searchhub/repository/SearchHubQueryRepository.kt`
- Create: `src/main/kotlin/com/park/animal/searchhub/SearchHubService.kt`
- Create: `src/main/kotlin/com/park/animal/searchhub/SearchHubController.kt`
- Test: `src/test/kotlin/com/park/animal/searchhub/SearchHubIT.kt`

> 계약 §2 의 `searchhub` 패키지 목록에는 `repository/` 가 없다. 허브 집계 쿼리를 `SearchHubService` 안에 두면 (a) `suspend` 서비스 안에 JPA 호출이 섞이고 (b) Hibernate Statistics 로 쿼리 수를 고정하는 IT 를 쓸 수 없다. 그래서 `searchgroup/repository/` 관례를 그대로 따라 `searchhub/repository/SearchHubQueryRepository.kt` **한 개 파일만** 추가한다. 계약의 다른 이름은 하나도 바꾸지 않는다.

**Interfaces:**
- Consumes (Task 2):
  - 엔티티 필드 — `SearchGroup(postId, joinPolicy, status, archivedReason, archivedAt, archivedBy)`, `SearchGroupMember(groupId, userId, userName, status, joinedAt, requestedAt, decidedAt, decidedBy)`, `SearchGroupTeam(groupId, teamId, status, requestedBy, requestedAt, decidedBy, decidedAt, activatedAt)`, `SearchGroupUserBlock(groupId, userId, blockedBy, reason, blockedAt, unblockedAt)`, `Team(name, description, status, createdBy)`, `TeamMember(teamId, userId, userName, role, status, joinedAt, requestedAt, decidedAt, decidedBy)` — 전부 `BaseEntity()` 상속, 연관관계 매핑 없이 스칼라 `UUID`.
  - kapt Q-타입 — `QSearchGroup.searchGroup`, `QSearchGroupMember.searchGroupMember`, `QSearchGroupTeam.searchGroupTeam`, `QSearchGroupUserBlock`, `QTeam.team`, `QTeamMember.teamMember`.
  - 리포지토리 — `SearchGroupRepository`, `SearchGroupMemberRepository`, `SearchGroupTeamRepository`, `SearchGroupUserBlockRepository`, `TeamRepository`, `TeamMemberRepository` (전부 `JpaRepository<T, UUID>`). Task 10 은 리포지토리를 **재정의하지 않고 그대로 사용**한다.
- Consumes (Task 3): `SearchGroupAccessResolver.accessibleGroupIds(userId: UUID): List<UUID>` — `NamedParameterJdbcTemplate` 기반 **단일 네이티브 쿼리**. 보호자 ∪ 직접 ACTIVE ∪ 팀 ACTIVE − 활성 차단(단 `is_owner` 예외), 중복 제거된 distinct 목록.
- Consumes (Task 5): `Notification` 엔티티에 V12 구조화 컬럼이 매핑되어 있어야 한다 — `Notification(userId, type, title, body, link, isRead, actorUserId, actorName, postId, groupId, teamId)`. 허브의 미읽음 집계는 `notification.group_id` 와 V12 의 `idx_noti_user_group (user_id, group_id, created_at)` 인덱스에 의존한다. 이 컬럼이 없으면 kapt 가 `QNotification.groupId` 를 만들지 않아 컴파일이 깨진다.
- Consumes (기존): `MultimediaService.resolvePresignedUrls(stored: List<String>): List<String>` (suspend), `com.park.animal.post.entity.QPost.post`, `QPostImage`, `NotificationRepository`.
- Produces:
  - `SearchHubService.getHub(userId: UUID): SearchHubResponse` (suspend)
  - `SearchHubQueryRepository.loadSnapshot(userId: UUID, accessibleGroupIds: List<UUID>, sectionLimit: Int, pendingScanLimit: Int): SearchHubSnapshot` (non-suspend, `@Transactional(readOnly = true)`)
  - DTO — `SearchHubResponse`, `SearchHubSummary`, `SearchHubAnimalCard`, `SearchHubTeamCard`, `SearchHubPendingAction`, `SearchHubPendingKind`

---

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/park/animal/searchhub/SearchHubIT.kt`

```kotlin
package com.park.animal.searchhub

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.multimedia.MultimediaService
import com.park.animal.notification.entity.Notification
import com.park.animal.notification.entity.NotificationType
import com.park.animal.notification.repository.NotificationRepository
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.entity.SearchGroupUserBlock
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import com.park.animal.searchhub.dto.SearchHubPendingKind
import com.park.animal.searchhub.repository.SearchHubQueryRepository
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.runBlocking
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.woo.storagesdk.usecase.StorageClient
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 마이페이지 통합 허브(`GET /api/v1/me/search-hub`) 통합 테스트 — 설계 §10, §12.3, §16.5.
 *
 * SearchFulltextIT 패턴: mysql:8.4 Testcontainer + 운영 Flyway 체인 + 테스트 baseline,
 * 테스트 트랜잭션 래핑 끔(NOT_SUPPORTED) — 서비스가 스스로 트랜잭션을 열고 커밋하는 경로를 그대로 본다.
 *
 * 쿼리 수 검증 주의: `SearchGroupAccessResolver.accessibleGroupIds` 는 NamedParameterJdbcTemplate 를
 * 쓰므로 Hibernate Statistics 에 잡히지 않는다. 아래 상한은 `SearchHubQueryRepository` 의 JPA 쿼리만 센다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchHubQueryRepository::class,
    SearchHubService::class,
    SearchHubIT.StorageStubConfig::class,
)
@Testcontainers
class SearchHubIT {
    @TestConfiguration
    class StorageStubConfig {
        // 썸네일은 전부 https:// legacy URL 로 시드하므로 resolvePresignedUrl 이 조기 반환한다 → stub 은 호출되지 않는다.
        @Bean
        fun stubStorageClient(): StorageClient = mock()

        @Bean
        fun multimediaService(stubStorageClient: StorageClient): MultimediaService = MultimediaService(stubStorageClient)

        // Task 3 의 SearchGroupAccessResolver 는 접근 거절 카운터(fmp.searchgroup.access.denied)를 올리려고
        // MeterRegistry 를 주입받는다. @DataJpaTest 슬라이스에는 Micrometer 자동설정이 없으므로 직접 넣는다.
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Autowired lateinit var searchHubService: SearchHubService

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var postImageRepository: PostImageRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var searchGroupUserBlockRepository: SearchGroupUserBlockRepository

    @Autowired lateinit var teamRepository: TeamRepository

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private fun statistics(): Statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private fun savePost(
        ownerId: UUID,
        title: String,
        status: MissingAnimalStatus = MissingAnimalStatus.SEARCHING,
    ): Post =
        postRepository.save(
            Post(
                authorId = ownerId,
                authorName = "보호자",
                title = title,
                phoneNum = "010-9999-8888",
                time = LocalDateTime.of(2026, 7, 20, 9, 0),
                place = "서울 강남구 역삼동",
                gender = "남아",
                gratuity = 0,
                description = "비밀설명토큰-노출금지",
                lat = 37.123456,
                lng = 127.654321,
                openChatUrl = null,
                missingAnimalStatus = status,
                animalType = AnimalType.DOG,
            ),
        )

    private fun saveGroup(
        post: Post,
        status: SearchGroupStatus = SearchGroupStatus.ACTIVE,
    ): SearchGroup =
        searchGroupRepository.save(
            SearchGroup(postId = post.id, joinPolicy = JoinPolicy.OPEN, status = status),
        )

    private fun saveDirectMember(
        groupId: UUID,
        userId: UUID,
        status: SearchGroupMemberStatus = SearchGroupMemberStatus.ACTIVE,
        userName: String? = "참여자",
    ): SearchGroupMember =
        searchGroupMemberRepository.save(
            SearchGroupMember(
                groupId = groupId,
                userId = userId,
                userName = userName,
                status = status,
                joinedAt = if (status == SearchGroupMemberStatus.ACTIVE) LocalDateTime.now() else null,
                requestedAt = LocalDateTime.now(),
            ),
        )

    private fun saveTeamWithLeader(
        leaderId: UUID,
        name: String,
    ): Team {
        val team = teamRepository.save(Team(name = name, description = null, status = TeamStatus.ACTIVE, createdBy = leaderId))
        teamMemberRepository.save(
            TeamMember(
                teamId = team.id,
                userId = leaderId,
                userName = "팀장",
                role = TeamRole.LEADER,
                status = TeamMemberStatus.ACTIVE,
                joinedAt = LocalDateTime.now(),
                requestedAt = LocalDateTime.now(),
            ),
        )
        return team
    }

    private fun saveNotification(
        userId: UUID,
        groupId: UUID?,
        isRead: Boolean,
    ): Notification =
        notificationRepository.save(
            Notification(
                userId = userId,
                type = NotificationType.GROUP_MEMBER_JOINED,
                title = "함께 찾기 알림",
                body = null,
                link = null,
                isRead = isRead,
                groupId = groupId,
            ),
        )

    @Test
    fun `직접 참여와 팀 경유 그룹이 모두 카드로 나오고 중복이 없다`() {
        val me = UUID.randomUUID()
        val owner = UUID.randomUUID()

        val directGroup = saveGroup(savePost(owner, "직접참여-말티즈"))
        saveDirectMember(directGroup.id, me)

        val team = saveTeamWithLeader(UUID.randomUUID(), "강남수색대-${UUID.randomUUID()}")
        teamMemberRepository.save(
            TeamMember(
                teamId = team.id,
                userId = me,
                userName = "팀원",
                role = TeamRole.MEMBER,
                status = TeamMemberStatus.ACTIVE,
                joinedAt = LocalDateTime.now(),
                requestedAt = LocalDateTime.now(),
            ),
        )
        val teamGroup = saveGroup(savePost(owner, "팀경유-포메"))
        searchGroupTeamRepository.save(
            SearchGroupTeam(
                groupId = teamGroup.id,
                teamId = team.id,
                status = SearchGroupTeamStatus.ACTIVE,
                requestedBy = owner,
                activatedAt = LocalDateTime.now(),
            ),
        )

        // 직접 + 팀 두 경로를 동시에 가진 그룹 — 카드는 정확히 1장이어야 한다.
        val bothGroup = saveGroup(savePost(owner, "양쪽경로-비숑"))
        saveDirectMember(bothGroup.id, me)
        searchGroupTeamRepository.save(
            SearchGroupTeam(
                groupId = bothGroup.id,
                teamId = team.id,
                status = SearchGroupTeamStatus.ACTIVE,
                requestedBy = owner,
                activatedAt = LocalDateTime.now(),
            ),
        )

        val hub = runBlocking { searchHubService.getHub(me) }
        val groupIds = hub.animals.map { it.groupId }

        assertEquals(3, groupIds.size, "직접·팀·양쪽 경로가 각각 한 장씩 나와야 한다")
        assertEquals(groupIds.toSet().size, groupIds.size, "같은 그룹이 두 장으로 중복되면 안 된다")
        assertEquals(3L, hub.summary.activeSearchCount)
    }

    @Test
    fun `종료된 수색과 삭제된 실종 소식은 카드에서 빠진다`() {
        val me = UUID.randomUUID()
        val owner = UUID.randomUUID()

        val active = saveGroup(savePost(owner, "진행중-시바"))
        saveDirectMember(active.id, me)

        val archived = saveGroup(savePost(owner, "종료됨-닥스"), status = SearchGroupStatus.ARCHIVED)
        saveDirectMember(archived.id, me)

        val deletedPost = savePost(owner, "삭제글-치와와")
        val deletedGroup = saveGroup(deletedPost)
        saveDirectMember(deletedGroup.id, me)
        postRepository.delete(deletedPost)

        val hub = runBlocking { searchHubService.getHub(me) }

        assertEquals(listOf(active.id), hub.animals.map { it.groupId })
        assertEquals(1L, hub.summary.activeSearchCount)
    }

    @Test
    fun `차단된 그룹은 허브에 보이지 않는다`() {
        val me = UUID.randomUUID()
        val owner = UUID.randomUUID()

        val visible = saveGroup(savePost(owner, "정상-진돗개"))
        saveDirectMember(visible.id, me)

        val blocked = saveGroup(savePost(owner, "차단됨-스피츠"))
        saveDirectMember(blocked.id, me)
        searchGroupUserBlockRepository.save(
            SearchGroupUserBlock(
                groupId = blocked.id,
                userId = me,
                blockedBy = owner,
                reason = "운영 사유",
                blockedAt = LocalDateTime.now(),
            ),
        )

        val hub = runBlocking { searchHubService.getHub(me) }

        assertEquals(listOf(visible.id), hub.animals.map { it.groupId })
    }

    @Test
    fun `보호자는 차단 행이 있어도 자기 사건을 항상 본다`() {
        val me = UUID.randomUUID()
        val myPost = savePost(me, "내사건-리트리버")
        val myGroup = saveGroup(myPost)
        // 데이터 사고로 보호자 자신에게 차단 행이 생겨도 자기 사건은 사라지면 안 된다(계약 §9 is_owner 예외).
        searchGroupUserBlockRepository.save(
            SearchGroupUserBlock(
                groupId = myGroup.id,
                userId = me,
                blockedBy = me,
                reason = null,
                blockedAt = LocalDateTime.now(),
            ),
        )

        val hub = runBlocking { searchHubService.getHub(me) }
        val card = hub.animals.single()

        assertEquals(myGroup.id, card.groupId)
        assertTrue(card.isOwner)
        assertEquals("/lost/${myPost.id}/group?tab=members", card.managePath)
    }

    @Test
    fun `확인할 요청은 알림이 아니라 PENDING 행에서 만들어진다`() {
        val me = UUID.randomUUID()

        // (1) 내가 보호자인 그룹의 PENDING 직접 참여 신청
        val myPost = savePost(me, "보호자사건-말라뮤트")
        val myGroup = saveGroup(myPost)
        val pendingJoin = saveDirectMember(myGroup.id, UUID.randomUUID(), SearchGroupMemberStatus.PENDING, "신청자")

        // (2) 내가 보호자인 그룹에 들어온 팀의 지원 제안
        val offerTeam = saveTeamWithLeader(UUID.randomUUID(), "제안팀-${UUID.randomUUID()}")
        val offer =
            searchGroupTeamRepository.save(
                SearchGroupTeam(
                    groupId = myGroup.id,
                    teamId = offerTeam.id,
                    status = SearchGroupTeamStatus.PENDING_GROUP_APPROVAL,
                    requestedBy = offerTeam.createdBy,
                ),
            )

        // (3) 내가 팀장인 팀의 PENDING 합류 신청
        val myTeam = saveTeamWithLeader(me, "내팀-${UUID.randomUUID()}")
        val pendingTeamMember =
            teamMemberRepository.save(
                TeamMember(
                    teamId = myTeam.id,
                    userId = UUID.randomUUID(),
                    userName = "합류신청자",
                    role = TeamRole.MEMBER,
                    status = TeamMemberStatus.PENDING,
                    requestedAt = LocalDateTime.now(),
                ),
            )

        // (4) 보호자가 내 팀에 보낸 지원 요청
        val otherGroup = saveGroup(savePost(UUID.randomUUID(), "타인사건-웰시코기"))
        val invite =
            searchGroupTeamRepository.save(
                SearchGroupTeam(
                    groupId = otherGroup.id,
                    teamId = myTeam.id,
                    status = SearchGroupTeamStatus.PENDING_TEAM_APPROVAL,
                    requestedBy = UUID.randomUUID(),
                ),
            )

        val hub = runBlocking { searchHubService.getHub(me) }
        val byKind = hub.pendingActions.associateBy { it.kind }

        assertEquals(4, hub.pendingActions.size)
        assertEquals(4, hub.summary.pendingActionCount)
        assertEquals(pendingJoin.id, byKind.getValue(SearchHubPendingKind.GROUP_JOIN_REQUEST).referenceId)
        assertEquals(offer.id, byKind.getValue(SearchHubPendingKind.GROUP_TEAM_SUPPORT_OFFER).referenceId)
        assertEquals(pendingTeamMember.id, byKind.getValue(SearchHubPendingKind.TEAM_JOIN_REQUEST).referenceId)
        assertEquals(invite.id, byKind.getValue(SearchHubPendingKind.TEAM_SUPPORT_REQUEST).referenceId)
        assertEquals(
            "/api/v1/teams/${myTeam.id}/memberships/${pendingTeamMember.id}/approve",
            byKind.getValue(SearchHubPendingKind.TEAM_JOIN_REQUEST).approvePath,
        )
        assertEquals(
            "/api/v1/search-groups/${myGroup.id}/memberships/${pendingJoin.id}/reject",
            byKind.getValue(SearchHubPendingKind.GROUP_JOIN_REQUEST).rejectPath,
        )
    }

    @Test
    fun `미읽음 알림 수가 그룹별로 카드에 붙고 요약에 합산된다`() {
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        val owner = UUID.randomUUID()

        val groupA = saveGroup(savePost(owner, "미읽음A-비글"))
        saveDirectMember(groupA.id, me)
        val groupB = saveGroup(savePost(owner, "미읽음B-푸들"))
        saveDirectMember(groupB.id, me)

        saveNotification(me, groupA.id, isRead = false)
        saveNotification(me, groupA.id, isRead = false)
        saveNotification(me, groupA.id, isRead = true) // 읽음 → 세지 않는다
        saveNotification(me, groupB.id, isRead = false)
        saveNotification(other, groupA.id, isRead = false) // 남의 알림 → 세지 않는다
        saveNotification(me, null, isRead = false) // 그룹과 무관한 알림 → 세지 않는다

        val hub = runBlocking { searchHubService.getHub(me) }
        val byGroup = hub.animals.associateBy { it.groupId }

        assertEquals(2L, byGroup.getValue(groupA.id).unreadNotificationCount)
        assertEquals(1L, byGroup.getValue(groupB.id).unreadNotificationCount)
        assertEquals(3L, hub.summary.unreadNotificationCount, "요약은 접근 가능한 그룹의 미읽음 합계다")
    }

    @Test
    fun `허브 응답 JSON 에 좌표 연락처 상세설명이 없다`() {
        val me = UUID.randomUUID()
        val post = savePost(me, "개인정보검사-포인터")
        val group = saveGroup(post)
        postImageRepository.save(PostImage(post = post, imageUrl = "https://cdn.platformholder.site/thumb-a.jpg"))
        saveDirectMember(group.id, UUID.randomUUID(), SearchGroupMemberStatus.PENDING, "신청자")

        val hub = runBlocking { searchHubService.getHub(me) }
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val json = mapper.writeValueAsString(hub)

        assertFalse(json.contains("\"lat\""), "허브 카드에 좌표 필드가 있으면 안 된다: $json")
        assertFalse(json.contains("\"lng\""), "허브 카드에 좌표 필드가 있으면 안 된다: $json")
        assertFalse(json.contains("phoneNum"), "허브 카드에 연락처 필드가 있으면 안 된다: $json")
        assertFalse(json.contains("37.123456"))
        assertFalse(json.contains("127.654321"))
        assertFalse(json.contains("010-9999-8888"))
        assertFalse(json.contains("비밀설명토큰"), "실종 소식 상세 설명은 허브에 실리지 않는다: $json")
        assertTrue(json.contains("https://cdn.platformholder.site/thumb-a.jpg"))
    }

    @Test
    fun `카드 수가 늘어도 허브 쿼리 수가 늘지 않는다`() {
        val me = UUID.randomUUID()
        val owner = UUID.randomUUID()
        repeat(2) { idx ->
            val g = saveGroup(savePost(owner, "쿼리수-$idx"))
            saveDirectMember(g.id, me)
        }

        runBlocking { searchHubService.getHub(me) } // 워밍업 (메타데이터/쿼리플랜 캐시)

        statistics().clear()
        runBlocking { searchHubService.getHub(me) }
        val withTwoCards = statistics().prepareStatementCount

        repeat(3) { idx ->
            val g = saveGroup(savePost(owner, "쿼리수-추가-$idx"))
            saveDirectMember(g.id, me)
        }

        statistics().clear()
        val hub = runBlocking { searchHubService.getHub(me) }
        val withFiveCards = statistics().prepareStatementCount

        assertEquals(5, hub.animals.size)
        assertEquals(withTwoCards, withFiveCards, "카드 수에 비례해 쿼리가 늘면 N+1 이다")
        assertTrue(withFiveCards <= 9, "허브 JPA 쿼리는 9개 이하여야 한다 (실제: $withFiveCards)")
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchhub.SearchHubIT"`

Expected: FAIL — 컴파일 단계에서 `Unresolved reference: searchhub` / `Unresolved reference: SearchHubService` / `Unresolved reference: SearchHubQueryRepository` / `Unresolved reference: SearchHubPendingKind` (`e: file:///.../SearchHubIT.kt:... Unresolved reference`). 아직 `searchhub` 패키지가 존재하지 않는다.

- [ ] **Step 3: DTO 최소 구현**

`src/main/kotlin/com/park/animal/searchhub/dto/SearchHubDtos.kt`

```kotlin
package com.park.animal.searchhub.dto

import com.park.animal.breed.entity.AnimalType
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.team.entity.TeamRole
import java.time.LocalDateTime
import java.util.UUID

/**
 * 마이페이지 통합 허브 응답 — 설계 §10.
 *
 * 섹션 구성: (1) 함께 찾는 반려동물 카드 [animals] (2) 내가 참여한 팀 [teams]
 * (3) 확인할 요청 [pendingActions] (4) 요약 카운트 [summary].
 *
 * 통합 지도는 phase 2 범위다. 이 응답은 **지도 데이터를 담지 않고** 진입 링크 [mapPath] 와
 * 활성화 여부 [mapAvailable] = false 만 내려준다. 좌표는 phase 2 의 멤버 전용 지도 DTO 에서만
 * 다루며(설계 §12.2/§12.3), 허브 응답에는 어떤 경우에도 lat/lng 를 싣지 않는다.
 *
 * 섹션별 상한은 10건이고, 초과분은 [animalsMorePath] / [teamsMorePath] 화면과
 * 각 요청 카드의 `detailPath` 로 이어진다.
 */
data class SearchHubResponse(
    val summary: SearchHubSummary,
    val animals: List<SearchHubAnimalCard>,
    val animalsHasMore: Boolean,
    val animalsMorePath: String,
    val teams: List<SearchHubTeamCard>,
    val teamsHasMore: Boolean,
    val teamsMorePath: String,
    val pendingActions: List<SearchHubPendingAction>,
    val pendingActionsHasMore: Boolean,
    val mapPath: String,
    val mapAvailable: Boolean,
)

/**
 * 첫 화면 요약 — 설계 §10 "현재 수색 중인 동물과 미읽음 수를 첫 화면에 노출".
 *
 * [unreadNotificationCount] 는 조회자가 접근 가능한 수색그룹에 붙은 **알림 미읽음** 합계다
 * (`notification.group_id IS NOT NULL AND is_read = false`). 카드별 값의 합과 항상 일치한다.
 * **채팅 미읽음은 phase 3 범위이며 이 필드에 섞이지 않는다** — 채팅 테이블 자체가 phase 1 에 없다.
 *
 * [pendingActionCount] 는 스캔 상한(도메인당 50건, 최대 200건)까지 정확하며,
 * 상한에 걸리면 [pendingActionCountCapped] 가 true 가 된다.
 */
data class SearchHubSummary(
    val activeSearchCount: Long,
    val unreadNotificationCount: Long,
    val pendingActionCount: Int,
    val pendingActionCountCapped: Boolean,
)

/**
 * 함께 찾는 반려동물 카드.
 *
 * 설계 §12.3/§16.5 에 따라 좌표(lat/lng), 연락처(phoneNum), 실종 소식 상세 설명(description)을
 * **필드로 갖지 않는다**. 프로젝션에서 컬럼 자체를 선택하지 않으므로 직렬화 실수로도 새지 않는다.
 *
 * [unreadNotificationCount] 는 이 그룹(`groupId`)에 붙은 조회자의 미읽음 알림 수다.
 * V12 의 `idx_noti_user_group (user_id, group_id, created_at)` 이 정확히 이 집계를 위한 인덱스다.
 * **채팅 미읽음은 phase 3 이라 여기 포함되지 않는다.**
 *
 * 링크 규칙(설계 §10 "카드에서 목적 화면까지 한 번, 세부 관리까지 최대 두 번"):
 * - [groupPath] : 카드 → 수색그룹 화면 (1번)
 * - [managePath]: 보호자만. 수색그룹 화면의 멤버 탭 (2번)
 * - [postPath]  : 공개 실종 소식
 *
 * [isOwnerInt] 보조 생성자는 QueryDSL `Projections.constructor` 가 `CASE WHEN ... THEN 1 ELSE 0`
 * 정수 표현식을 받기 위한 것이다(`PostDetailResponse.isMineInt` 와 동일 관례). 미읽음 수는 별도
 * 집계 쿼리 결과를 `copy` 로 채우므로 보조 생성자는 0 을 넣는다.
 */
data class SearchHubAnimalCard(
    val groupId: UUID,
    val postId: UUID,
    val title: String,
    val place: String,
    val missingAt: LocalDateTime,
    val animalType: AnimalType,
    val missingAnimalStatus: MissingAnimalStatus,
    val joinPolicy: JoinPolicy,
    val isOwner: Boolean,
    val thumbnail: String?,
    val unreadNotificationCount: Long,
) {
    constructor(
        groupId: UUID,
        postId: UUID,
        title: String,
        place: String,
        missingAt: LocalDateTime,
        animalType: AnimalType,
        missingAnimalStatus: MissingAnimalStatus,
        joinPolicy: JoinPolicy,
        isOwnerInt: Int,
        thumbnail: String?,
    ) : this(
        groupId,
        postId,
        title,
        place,
        missingAt,
        animalType,
        missingAnimalStatus,
        joinPolicy,
        isOwnerInt == 1,
        thumbnail,
        0L,
    )

    val postPath: String get() = "/lost/$postId"

    val groupPath: String get() = "/lost/$postId/group"

    val managePath: String? get() = if (isOwner) "/lost/$postId/group?tab=members" else null
}

/**
 * 내가 참여한 팀 카드. [supportingSearchCount] 는 이 팀이 현재 지원 중인(ACTIVE) 수색 수다.
 * [teamPath] 로 한 번, 팀장이면 [managePath] 로 두 번 만에 팀원 관리까지 도달한다.
 */
data class SearchHubTeamCard(
    val teamId: UUID,
    val name: String,
    val role: TeamRole,
    val supportingSearchCount: Long,
) {
    val teamPath: String get() = "/teams/$teamId"

    val managePath: String? get() = if (role == TeamRole.LEADER) "/teams/$teamId?tab=members" else null
}

/** 확인할 요청의 종류. 알림이 아니라 PENDING 행에서 파생된다(계약 §9). */
enum class SearchHubPendingKind {
    /** 내가 보호자인 수색그룹의 직접 참여 신청 (search_group_member.PENDING) */
    GROUP_JOIN_REQUEST,

    /** 내가 보호자인 수색그룹에 들어온 팀의 지원 제안 (search_group_team.PENDING_GROUP_APPROVAL) */
    GROUP_TEAM_SUPPORT_OFFER,

    /** 내가 팀장인 팀의 합류 신청 (team_member.PENDING) */
    TEAM_JOIN_REQUEST,

    /** 보호자가 내 팀에 보낸 수색 지원 요청 (search_group_team.PENDING_TEAM_APPROVAL) */
    TEAM_SUPPORT_REQUEST,
}

/**
 * 확인할 요청 1건. [title] 은 사용자 노출 문구이므로 좌표·전화번호·차단 사유를 담지 않는다.
 * [approvePath] / [rejectPath] 는 계약 §8 의 실제 엔드포인트 경로다.
 */
data class SearchHubPendingAction(
    val kind: SearchHubPendingKind,
    val referenceId: UUID,
    val groupId: UUID?,
    val postId: UUID?,
    val teamId: UUID?,
    val title: String,
    val requestedAt: LocalDateTime?,
    val approvePath: String,
    val rejectPath: String,
    val detailPath: String,
)
```

- [ ] **Step 4: 쿼리 리포지토리 구현**

`src/main/kotlin/com/park/animal/searchhub/repository/SearchHubQueryRepository.kt`

```kotlin
package com.park.animal.searchhub.repository

import com.park.animal.notification.entity.QNotification.notification
import com.park.animal.post.entity.QPost.post
import com.park.animal.post.entity.QPostImage
import com.park.animal.searchgroup.entity.QSearchGroup.searchGroup
import com.park.animal.searchgroup.entity.QSearchGroupMember.searchGroupMember
import com.park.animal.searchgroup.entity.QSearchGroupTeam.searchGroupTeam
import com.park.animal.searchgroup.entity.QSearchGroupUserBlock
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchhub.dto.SearchHubAnimalCard
import com.park.animal.searchhub.dto.SearchHubPendingKind
import com.park.animal.searchhub.dto.SearchHubTeamCard
import com.park.animal.team.entity.QTeam.team
import com.park.animal.team.entity.QTeamMember
import com.park.animal.team.entity.QTeamMember.teamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.querydsl.core.types.Projections
import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.core.types.dsl.CaseBuilder
import com.querydsl.core.types.dsl.Expressions
import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.JPQLQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/** 확인할 요청 원본 행. 종류 태그는 쿼리별로 리포지토리가 붙인다. */
data class SearchHubPendingRow(
    val referenceId: UUID,
    val groupId: UUID?,
    val postId: UUID?,
    val teamId: UUID?,
    val subjectName: String?,
    val postTitle: String?,
    val teamName: String?,
    val requestedAt: LocalDateTime?,
)

data class SearchHubPendingEntry(
    val kind: SearchHubPendingKind,
    val row: SearchHubPendingRow,
)

/** 허브 한 번 조회에 필요한 모든 원본 데이터. presign 전 상태다. */
data class SearchHubSnapshot(
    val activeSearchCount: Long,
    val unreadNotificationCount: Long,
    val animals: List<SearchHubAnimalCard>,
    val animalsHasMore: Boolean,
    val teams: List<SearchHubTeamCard>,
    val teamsHasMore: Boolean,
    val pending: List<SearchHubPendingEntry>,
    val pendingCapped: Boolean,
)

/**
 * 마이페이지 허브 집계 쿼리 — 설계 §10.
 *
 * 규칙:
 * 1. `select(post)` 를 쓰지 않는다. 필요한 컬럼만 [Projections.constructor] 로 나열한다.
 *    lat / lng / phone_num / description 은 **선택 자체를 하지 않는다**(설계 §12.3, §16.5).
 * 2. 카드 수에 비례하는 쿼리를 만들지 않는다. 썸네일은 상관 서브쿼리 + left join 으로 한 번에 붙이고
 *    (`PostQueryRepositoryImpl.postSummaryWithThumbnail` 과 동일 패턴), 미읽음 수는 그룹별
 *    `GROUP BY group_id` 집계 **한 번**으로 모아 카드에 매핑한다.
 * 3. 차단 anti-join 은 보호자 자신을 지우지 않는다 — `post.author_id = :userId` 예외를 둔다(계약 §9).
 * 4. 총 JPA 쿼리 8개 고정 (카드 / 진행중 수 / 팀 / 확인할 요청 4종 / 미읽음 집계 1).
 */
@Repository
class SearchHubQueryRepository(
    private val jpaQueryFactory: JPAQueryFactory,
) {
    @Transactional(readOnly = true)
    fun loadSnapshot(
        userId: UUID,
        accessibleGroupIds: List<UUID>,
        sectionLimit: Int,
        pendingScanLimit: Int,
    ): SearchHubSnapshot {
        val unreadByGroup = countUnreadByGroup(userId, accessibleGroupIds)
        val animalRows =
            findAnimalCards(userId, accessibleGroupIds, sectionLimit + 1L)
                .map { it.copy(unreadNotificationCount = unreadByGroup[it.groupId] ?: 0L) }
        val teamRows = findTeamCards(userId, sectionLimit + 1L)
        val pendingRows = findPendingEntries(userId, pendingScanLimit.toLong())
        return SearchHubSnapshot(
            activeSearchCount = countActiveSearches(userId, accessibleGroupIds),
            unreadNotificationCount = unreadByGroup.values.sum(),
            animals = animalRows.take(sectionLimit),
            animalsHasMore = animalRows.size > sectionLimit,
            teams = teamRows.take(sectionLimit),
            teamsHasMore = teamRows.size > sectionLimit,
            pending = pendingRows,
            pendingCapped = pendingRows.size >= pendingScanLimit,
        )
    }

    /**
     * 그룹별 미읽음 알림 수 — 설계 §10.
     *
     * 카드마다 COUNT 를 날리면 즉시 N+1 이 된다. `group_id IN (...)` + `GROUP BY group_id` 한 번으로
     * 끝내고 결과 Map 을 카드에 매핑한다. V12 의 `idx_noti_user_group (user_id, group_id, created_at)`
     * 이 이 쿼리를 위한 인덱스다. 채팅 미읽음은 phase 3 이라 여기에 포함되지 않는다.
     */
    private fun countUnreadByGroup(
        userId: UUID,
        groupIds: List<UUID>,
    ): Map<UUID, Long> {
        if (groupIds.isEmpty()) return emptyMap()
        val unreadCount = notification.count()
        return jpaQueryFactory
            .select(notification.groupId, unreadCount)
            .from(notification)
            .where(
                notification.userId.eq(userId),
                notification.groupId.`in`(groupIds),
                notification.isRead.isFalse,
                notification.deletedAt.isNull,
            ).groupBy(notification.groupId)
            .fetch()
            .mapNotNull { tuple ->
                val groupId = tuple.get(notification.groupId) ?: return@mapNotNull null
                groupId to (tuple.get(unreadCount) ?: 0L)
            }.toMap()
    }

    private fun findAnimalCards(
        userId: UUID,
        groupIds: List<UUID>,
        limit: Long,
    ): List<SearchHubAnimalCard> {
        if (groupIds.isEmpty()) return emptyList()
        val thumb = QPostImage("thumb")
        val thumbPick = QPostImage("thumbPick")
        return jpaQueryFactory
            .select(
                Projections.constructor(
                    SearchHubAnimalCard::class.java,
                    searchGroup.id,
                    post.id,
                    post.title,
                    post.place,
                    post.time,
                    post.animalType,
                    post.missingAnimalStatus,
                    searchGroup.joinPolicy,
                    CaseBuilder().`when`(post.authorId.eq(userId)).then(1).otherwise(0),
                    thumb.imageUrl,
                ),
            ).from(searchGroup)
            .join(post)
            .on(post.id.eq(searchGroup.postId))
            .leftJoin(thumb)
            .on(
                thumb.post.id
                    .eq(post.id)
                    .and(thumb.deletedAt.isNull)
                    .and(
                        thumb.createdAt.eq(
                            JPAExpressions
                                .select(thumbPick.createdAt.min())
                                .from(thumbPick)
                                .where(thumbPick.post.id.eq(post.id), thumbPick.deletedAt.isNull),
                        ),
                    ),
            ).where(
                searchGroup.id.`in`(groupIds),
                searchGroup.status.eq(SearchGroupStatus.ACTIVE),
                searchGroup.deletedAt.isNull,
                post.deletedAt.isNull,
                visibleToViewer(userId),
            ).orderBy(post.time.desc(), post.id.asc())
            .limit(limit)
            .fetch()
    }

    private fun countActiveSearches(
        userId: UUID,
        groupIds: List<UUID>,
    ): Long {
        if (groupIds.isEmpty()) return 0L
        return jpaQueryFactory
            .select(searchGroup.count())
            .from(searchGroup)
            .join(post)
            .on(post.id.eq(searchGroup.postId))
            .where(
                searchGroup.id.`in`(groupIds),
                searchGroup.status.eq(SearchGroupStatus.ACTIVE),
                searchGroup.deletedAt.isNull,
                post.deletedAt.isNull,
                visibleToViewer(userId),
            ).fetchOne() ?: 0L
    }

    private fun findTeamCards(
        userId: UUID,
        limit: Long,
    ): List<SearchHubTeamCard> =
        jpaQueryFactory
            .select(
                Projections.constructor(
                    SearchHubTeamCard::class.java,
                    team.id,
                    team.name,
                    teamMember.role,
                    JPAExpressions
                        .select(searchGroupTeam.count())
                        .from(searchGroupTeam)
                        .where(
                            searchGroupTeam.teamId.eq(team.id),
                            searchGroupTeam.status.eq(SearchGroupTeamStatus.ACTIVE),
                        ),
                ),
            ).from(teamMember)
            .join(team)
            .on(team.id.eq(teamMember.teamId))
            .where(
                teamMember.userId.eq(userId),
                teamMember.status.eq(TeamMemberStatus.ACTIVE),
                team.status.eq(TeamStatus.ACTIVE),
                team.deletedAt.isNull,
            ).orderBy(team.name.asc(), team.id.asc())
            .limit(limit)
            .fetch()

    private fun findPendingEntries(
        userId: UUID,
        scanLimit: Long,
    ): List<SearchHubPendingEntry> {
        val entries =
            findOwnerJoinRequests(userId, scanLimit).map { SearchHubPendingEntry(SearchHubPendingKind.GROUP_JOIN_REQUEST, it) } +
                findOwnerTeamOffers(userId, scanLimit).map {
                    SearchHubPendingEntry(SearchHubPendingKind.GROUP_TEAM_SUPPORT_OFFER, it)
                } +
                findLeaderJoinRequests(userId, scanLimit).map { SearchHubPendingEntry(SearchHubPendingKind.TEAM_JOIN_REQUEST, it) } +
                findLeaderSupportRequests(userId, scanLimit).map {
                    SearchHubPendingEntry(SearchHubPendingKind.TEAM_SUPPORT_REQUEST, it)
                }
        return entries.sortedByDescending { it.row.requestedAt ?: LocalDateTime.MIN }
    }

    /** (1) 내가 보호자인 활성 그룹의 직접 참여 신청. */
    private fun findOwnerJoinRequests(
        userId: UUID,
        limit: Long,
    ): List<SearchHubPendingRow> =
        jpaQueryFactory
            .select(
                Projections.constructor(
                    SearchHubPendingRow::class.java,
                    searchGroupMember.id,
                    searchGroup.id,
                    post.id,
                    Expressions.nullExpression(UUID::class.java),
                    searchGroupMember.userName,
                    post.title,
                    Expressions.nullExpression(String::class.java),
                    searchGroupMember.requestedAt,
                ),
            ).from(searchGroupMember)
            .join(searchGroup)
            .on(searchGroup.id.eq(searchGroupMember.groupId))
            .join(post)
            .on(post.id.eq(searchGroup.postId))
            .where(
                post.authorId.eq(userId),
                post.deletedAt.isNull,
                searchGroup.status.eq(SearchGroupStatus.ACTIVE),
                searchGroup.deletedAt.isNull,
                searchGroupMember.status.eq(SearchGroupMemberStatus.PENDING),
            ).orderBy(searchGroupMember.requestedAt.desc(), searchGroupMember.id.asc())
            .limit(limit)
            .fetch()

    /** (2) 내가 보호자인 활성 그룹에 들어온 팀의 지원 제안. */
    private fun findOwnerTeamOffers(
        userId: UUID,
        limit: Long,
    ): List<SearchHubPendingRow> =
        jpaQueryFactory
            .select(
                Projections.constructor(
                    SearchHubPendingRow::class.java,
                    searchGroupTeam.id,
                    searchGroup.id,
                    post.id,
                    team.id,
                    team.name,
                    post.title,
                    team.name,
                    searchGroupTeam.requestedAt,
                ),
            ).from(searchGroupTeam)
            .join(searchGroup)
            .on(searchGroup.id.eq(searchGroupTeam.groupId))
            .join(post)
            .on(post.id.eq(searchGroup.postId))
            .join(team)
            .on(team.id.eq(searchGroupTeam.teamId))
            .where(
                post.authorId.eq(userId),
                post.deletedAt.isNull,
                searchGroup.status.eq(SearchGroupStatus.ACTIVE),
                searchGroup.deletedAt.isNull,
                searchGroupTeam.status.eq(SearchGroupTeamStatus.PENDING_GROUP_APPROVAL),
            ).orderBy(searchGroupTeam.requestedAt.desc(), searchGroupTeam.id.asc())
            .limit(limit)
            .fetch()

    /** (3) 내가 팀장인 팀의 합류 신청. */
    private fun findLeaderJoinRequests(
        userId: UUID,
        limit: Long,
    ): List<SearchHubPendingRow> =
        jpaQueryFactory
            .select(
                Projections.constructor(
                    SearchHubPendingRow::class.java,
                    teamMember.id,
                    Expressions.nullExpression(UUID::class.java),
                    Expressions.nullExpression(UUID::class.java),
                    team.id,
                    teamMember.userName,
                    Expressions.nullExpression(String::class.java),
                    team.name,
                    teamMember.requestedAt,
                ),
            ).from(teamMember)
            .join(team)
            .on(team.id.eq(teamMember.teamId))
            .where(
                teamMember.status.eq(TeamMemberStatus.PENDING),
                team.status.eq(TeamStatus.ACTIVE),
                team.deletedAt.isNull,
                teamMember.teamId.`in`(leaderTeamIds(userId)),
            ).orderBy(teamMember.requestedAt.desc(), teamMember.id.asc())
            .limit(limit)
            .fetch()

    /** (4) 보호자가 내 팀에 보낸 수색 지원 요청. */
    private fun findLeaderSupportRequests(
        userId: UUID,
        limit: Long,
    ): List<SearchHubPendingRow> =
        jpaQueryFactory
            .select(
                Projections.constructor(
                    SearchHubPendingRow::class.java,
                    searchGroupTeam.id,
                    searchGroup.id,
                    post.id,
                    team.id,
                    post.title,
                    post.title,
                    team.name,
                    searchGroupTeam.requestedAt,
                ),
            ).from(searchGroupTeam)
            .join(searchGroup)
            .on(searchGroup.id.eq(searchGroupTeam.groupId))
            .join(post)
            .on(post.id.eq(searchGroup.postId))
            .join(team)
            .on(team.id.eq(searchGroupTeam.teamId))
            .where(
                searchGroupTeam.status.eq(SearchGroupTeamStatus.PENDING_TEAM_APPROVAL),
                searchGroup.status.eq(SearchGroupStatus.ACTIVE),
                searchGroup.deletedAt.isNull,
                post.deletedAt.isNull,
                searchGroupTeam.teamId.`in`(leaderTeamIds(userId)),
            ).orderBy(searchGroupTeam.requestedAt.desc(), searchGroupTeam.id.asc())
            .limit(limit)
            .fetch()

    private fun leaderTeamIds(userId: UUID): JPQLQuery<UUID> {
        val leader = QTeamMember("leader")
        return JPAExpressions
            .select(leader.teamId)
            .from(leader)
            .where(
                leader.userId.eq(userId),
                leader.role.eq(TeamRole.LEADER),
                leader.status.eq(TeamMemberStatus.ACTIVE),
            )
    }

    /**
     * 차단 anti-join. 보호자 자신은 어떤 차단 행이 있어도 자기 사건에서 밀려나지 않는다(계약 §9).
     */
    private fun visibleToViewer(userId: UUID): BooleanExpression {
        val block = QSearchGroupUserBlock.searchGroupUserBlock
        val notBlocked =
            JPAExpressions
                .selectOne()
                .from(block)
                .where(
                    block.groupId.eq(searchGroup.id),
                    block.userId.eq(userId),
                    block.unblockedAt.isNull,
                ).notExists()
        return post.authorId.eq(userId).or(notBlocked)
    }
}
```

- [ ] **Step 5: 서비스 · 컨트롤러 구현**

`src/main/kotlin/com/park/animal/searchhub/SearchHubService.kt`

```kotlin
package com.park.animal.searchhub

import com.park.animal.multimedia.MultimediaService
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchhub.dto.SearchHubPendingAction
import com.park.animal.searchhub.dto.SearchHubPendingKind
import com.park.animal.searchhub.dto.SearchHubResponse
import com.park.animal.searchhub.dto.SearchHubSummary
import com.park.animal.searchhub.repository.SearchHubPendingEntry
import com.park.animal.searchhub.repository.SearchHubQueryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 마이페이지 통합 허브 조립 — 설계 §10.
 *
 * DB 단위작업은 전부 non-suspend 빈([SearchGroupAccessResolver], [SearchHubQueryRepository])에 있고
 * 이 서비스에는 `@Transactional` 을 붙이지 않는다. `suspend` + `@Transactional` 조합은 코루틴 경계에서
 * 트랜잭션이 먼저 커밋돼 버리기 때문이다(계약 F2 / 전역제약 9).
 *
 * 썸네일 presign 은 `PostService.myPage` 와 같은 방식으로 **한 번에 배치** 처리한다.
 * 미읽음 수는 알림(`notification.group_id`) 기준이며, 채팅 미읽음은 phase 3 범위다.
 */
@Service
class SearchHubService(
    private val searchGroupAccessResolver: SearchGroupAccessResolver,
    private val searchHubQueryRepository: SearchHubQueryRepository,
    private val multimediaService: MultimediaService,
) {
    companion object {
        const val SECTION_LIMIT = 10
        const val PENDING_SCAN_LIMIT = 50
        const val MAP_PATH = "/profile/search/map"
        const val ANIMALS_MORE_PATH = "/profile/search"
        const val TEAMS_MORE_PATH = "/teams"
    }

    suspend fun getHub(userId: UUID): SearchHubResponse {
        val snapshot =
            withContext(Dispatchers.IO) {
                val accessibleGroupIds = searchGroupAccessResolver.accessibleGroupIds(userId)
                searchHubQueryRepository.loadSnapshot(userId, accessibleGroupIds, SECTION_LIMIT, PENDING_SCAN_LIMIT)
            }

        val resolved = multimediaService.resolvePresignedUrls(snapshot.animals.map { it.thumbnail ?: "" })
        val animals =
            snapshot.animals.mapIndexed { idx, card ->
                if (card.thumbnail.isNullOrBlank()) card else card.copy(thumbnail = resolved[idx])
            }
        val pendingActions = snapshot.pending.map(::toPendingAction)

        return SearchHubResponse(
            summary =
                SearchHubSummary(
                    activeSearchCount = snapshot.activeSearchCount,
                    unreadNotificationCount = snapshot.unreadNotificationCount,
                    pendingActionCount = pendingActions.size,
                    pendingActionCountCapped = snapshot.pendingCapped,
                ),
            animals = animals,
            animalsHasMore = snapshot.animalsHasMore,
            animalsMorePath = ANIMALS_MORE_PATH,
            teams = snapshot.teams,
            teamsHasMore = snapshot.teamsHasMore,
            teamsMorePath = TEAMS_MORE_PATH,
            pendingActions = pendingActions.take(SECTION_LIMIT),
            pendingActionsHasMore = pendingActions.size > SECTION_LIMIT,
            mapPath = MAP_PATH,
            mapAvailable = false,
        )
    }

    private fun toPendingAction(entry: SearchHubPendingEntry): SearchHubPendingAction {
        val row = entry.row
        val subject = row.subjectName ?: "새 참여자"
        val animal = row.postTitle ?: "함께 찾기"
        val teamName = row.teamName ?: "우리 팀"
        return when (entry.kind) {
            SearchHubPendingKind.GROUP_JOIN_REQUEST ->
                SearchHubPendingAction(
                    kind = entry.kind,
                    referenceId = row.referenceId,
                    groupId = row.groupId,
                    postId = row.postId,
                    teamId = null,
                    title = "${subject}님이 $animal 함께 찾기에 참여를 신청했어요",
                    requestedAt = row.requestedAt,
                    approvePath = "/api/v1/search-groups/${row.groupId}/memberships/${row.referenceId}/approve",
                    rejectPath = "/api/v1/search-groups/${row.groupId}/memberships/${row.referenceId}/reject",
                    detailPath = "/lost/${row.postId}/group?tab=members",
                )

            SearchHubPendingKind.GROUP_TEAM_SUPPORT_OFFER ->
                SearchHubPendingAction(
                    kind = entry.kind,
                    referenceId = row.referenceId,
                    groupId = row.groupId,
                    postId = row.postId,
                    teamId = row.teamId,
                    title = "$teamName 팀이 $animal 수색 지원을 제안했어요",
                    requestedAt = row.requestedAt,
                    approvePath = "/api/v1/search-groups/${row.groupId}/team-supports/${row.referenceId}/accept",
                    rejectPath = "/api/v1/search-groups/${row.groupId}/team-supports/${row.referenceId}/decline",
                    detailPath = "/lost/${row.postId}/group?tab=teams",
                )

            SearchHubPendingKind.TEAM_JOIN_REQUEST ->
                SearchHubPendingAction(
                    kind = entry.kind,
                    referenceId = row.referenceId,
                    groupId = null,
                    postId = null,
                    teamId = row.teamId,
                    title = "${subject}님이 $teamName 팀에 합류를 신청했어요",
                    requestedAt = row.requestedAt,
                    approvePath = "/api/v1/teams/${row.teamId}/memberships/${row.referenceId}/approve",
                    rejectPath = "/api/v1/teams/${row.teamId}/memberships/${row.referenceId}/reject",
                    detailPath = "/teams/${row.teamId}?tab=members",
                )

            SearchHubPendingKind.TEAM_SUPPORT_REQUEST ->
                SearchHubPendingAction(
                    kind = entry.kind,
                    referenceId = row.referenceId,
                    groupId = row.groupId,
                    postId = row.postId,
                    teamId = row.teamId,
                    title = "보호자가 $teamName 팀에 $animal 수색 지원을 요청했어요",
                    requestedAt = row.requestedAt,
                    approvePath = "/api/v1/search-groups/${row.groupId}/team-supports/${row.referenceId}/accept",
                    rejectPath = "/api/v1/search-groups/${row.groupId}/team-supports/${row.referenceId}/decline",
                    detailPath = "/teams/${row.teamId}?tab=animals",
                )
        }
    }
}
```

`src/main/kotlin/com/park/animal/searchhub/SearchHubController.kt`

```kotlin
package com.park.animal.searchhub

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchhub.dto.SearchHubResponse
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody

@RestController
@RequestMapping("/api/v1")
class SearchHubController(
    private val searchHubService: SearchHubService,
) {
    @GetMapping("/me/search-hub")
    @Operation(
        summary = "마이페이지 함께 찾기 허브",
        description =
            "함께 찾는 반려동물 · 내가 참여한 팀 · 확인할 요청 · 요약 카운트(진행 중 수색 수, 미읽음 알림 수)를 " +
                "한 번에 반환한다. 통합 지도는 phase 2 범위라 진입 경로(mapPath)만 내려주고 지도 데이터는 담지 않는다. " +
                "채팅 미읽음은 phase 3 범위다. 카드에는 좌표·연락처·상세 설명을 담지 않는다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    suspend fun getHub(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
    ): SucceededApiResponseBody<SearchHubResponse> = SucceededApiResponseBody(data = searchHubService.getHub(passport.userId))
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchhub.SearchHubIT"`

Expected: PASS — 8개 테스트 전부 통과. 특히 `미읽음 알림 수가 그룹별로 카드에 붙고 요약에 합산된다` 가 카드 2/1 · 요약 3 을 만족하고, `카드 수가 늘어도 허브 쿼리 수가 늘지 않는다` 가 `withTwoCards == withFiveCards` 와 `<= 9` 를 동시에 만족해야 한다.

- [ ] **Step 7: 전체 회귀 확인**

Run: `cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test`

Expected: PASS — `SearchFulltextIT`, `SearchHybridFallbackTest`, `GlobalExceptionMappingTest`, Task 1~9 의 IT 포함 전부 통과.

- [ ] **Step 8: 커밋**

```bash
cd /Users/park/Desktop/project/animal && \
git add src/main/kotlin/com/park/animal/searchhub src/test/kotlin/com/park/animal/searchhub && \
git commit -m "feat(search-group): 마이페이지 함께 찾기 허브 - GET /me/search-hub 단일 집계

- 함께 찾는 반려동물 / 내가 참여한 팀 / 확인할 요청 / 요약 카운트 4개 섹션
- 요약과 카드에 미읽음 알림 수 노출 (설계 10절). 채팅 미읽음은 phase 3
- 미읽음은 group_id GROUP BY 집계 1회로 붙임 - idx_noti_user_group 사용, N+1 없음
- accessibleGroupIds 1회 + 카드 projection 1회 + 썸네일 presign 배치 1회 (JPA 쿼리 8개 고정)
- projection 에서 lat/lng/phoneNum/description 컬럼 자체를 선택하지 않음 (설계 12.3/16.5)
- 확인할 요청은 알림이 아니라 PENDING 행 4종에서 파생
- 차단 anti-join 에 is_owner 예외 - 보호자 자신의 사건은 항상 노출
- 통합 지도는 phase 2 - 진입 링크만, mapAvailable=false"
```

---

### Task 11: 권한 행렬 / IDOR / 동시성 / 개인정보 통합 테스트

**Files:**
- Create: `src/test/kotlin/com/park/animal/support/CollaborationFixtures.kt`
- Create: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupPermissionMatrixIT.kt`
- Create: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupIdorIT.kt`
- Create: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupConcurrencyIT.kt`
- Create: `src/test/kotlin/com/park/animal/searchgroup/SearchGroupPrivacyIT.kt`

**Interfaces:**
- Consumes (Task 2 엔티티/리포지토리): Task 10 의 Consumes 와 동일. 리포지토리는 Task 2 가 정의한 최종 시그니처를 그대로 쓰며 이 태스크는 아무것도 재정의하지 않는다.
- Consumes (Task 3): `SearchGroupAccessResolver.requireVisible/requireRead/requireWrite/requireOwner(groupId: UUID, userId: UUID): GroupAccess`. Task 3 이 R12 로 `MeterRegistry` 를 주입받으므로 IT 컨텍스트에 `SimpleMeterRegistry` 빈이 있어야 한다(`CollaborationTestConfig` 제공).
- Consumes (Task 4 `SearchGroupService`) — 이 매트릭스가 호출하는 **정확한 시그니처**:
  - `fun getCta(postId: UUID, viewerId: UUID?): SearchGroupCtaResponse`
  - `fun getDetail(groupId: UUID, viewerId: UUID): SearchGroupDetailResponse`
  - `fun endSearch(groupId: UUID, actorUserId: UUID): SearchGroupDetailResponse`
  - `fun listEvents(groupId: UUID, viewerId: UUID, size: Int, offset: Int): List<SearchGroupEventResponse>`
- Consumes (Task 5) — **필수 선행 조건**: `SearchLifecycleService.endSearch` 의 fan-out 이 `GroupNotificationPublisher.notifyGroup(groupId, postId, NotificationType.SEARCH_ENDED, excluding, actorUserId, body)` 로 교체되어 `notification.group_id` 가 채워져 있어야 한다. Task 5 이전의 레거시 `notificationService.createMany` 는 `group_id` 를 NULL 로 남기므로 `SearchGroupConcurrencyIT` / `SearchGroupPrivacyIT` 의 `WHERE group_id = ?` 검증이 항상 0건을 받아 실패한다.
- Consumes (Task 6 `SearchGroupMembershipService`):
  - `fun join(groupId: UUID, userId: UUID, userName: String?): JoinSearchGroupResponse`
  - `fun approve(groupId: UUID, membershipId: UUID, ownerUserId: UUID): SearchGroupMembershipResponse`
  - `fun reject(groupId: UUID, membershipId: UUID, ownerUserId: UUID): SearchGroupMembershipResponse`
  - `fun remove(groupId: UUID, membershipId: UUID, ownerUserId: UUID): SearchGroupMembershipResponse`
  - `fun leaveMe(groupId: UUID, userId: UUID): SearchGroupMembershipResponse`
  - `fun list(groupId: UUID, userId: UUID, status: SearchGroupMemberStatus? = null): List<SearchGroupMembershipResponse>`
  - `fun updateJoinPolicy(groupId: UUID, ownerUserId: UUID, joinPolicy: JoinPolicy)` — Task 6 이 `SearchGroupMembershipService` 에 추가하고 `SearchGroupController` 가 두 번째 생성자 파라미터로 이 서비스를 받아 `PATCH /search-groups/{groupId}/join-policy` 에서 호출한다.
- Consumes (Task 7 `SearchGroupBlockService`): `fun block(groupId: UUID, targetUserId: UUID, ownerUserId: UUID, reason: String?): SearchGroupBlockResponse`
- Consumes (Task 9 `SearchGroupTeamSupportService`):
  - `fun request(groupId: UUID, teamId: UUID, actorUserId: UUID, message: String? = null): SearchGroupTeamSupportResponse` — 보호자가 부르면 `PENDING_TEAM_APPROVAL`, 팀장이 부르면 `PENDING_GROUP_APPROVAL`, 둘 다면 즉시 `ACTIVE`
  - `fun accept(groupId: UUID, supportId: UUID, actorUserId: UUID): SearchGroupTeamSupportResponse`
  - `fun decline(groupId: UUID, supportId: UUID, actorUserId: UUID): SearchGroupTeamSupportResponse`
  - `fun end(groupId: UUID, supportId: UUID, actorUserId: UUID): SearchGroupTeamSupportResponse`
- Consumes (Task 8 `TeamMembershipService`): `fun approve(teamId: UUID, membershipId: UUID, actorUserId: UUID): TeamMembershipResponse`
- Consumes (Task 10): `SearchHubService.getHub(userId: UUID): SearchHubResponse` (suspend), `SearchHubQueryRepository`
- Produces: 테스트 하네스 `CollaborationFixtures`, `CollaborationWorld`, `CollaborationTestConfig`, `PrivacyProbe` — 이후 phase 2/3 IT 가 재사용한다.

> 이 태스크는 **프로덕션 코드를 만들지 않는다**. 아래 매트릭스가 Task 6~9 의 권위 있는 기대값이다. 실패하면 이 파일을 고치지 말고 해당 태스크의 서비스 코드를 고친다.

---

- [ ] **Step 1: 공용 테스트 하네스 작성**

`src/test/kotlin/com/park/animal/support/CollaborationFixtures.kt`

```kotlin
package com.park.animal.support

import com.park.animal.breed.entity.AnimalType
import com.park.animal.multimedia.MultimediaService
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.entity.SearchGroupUserBlock
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.kotlin.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.woo.storagesdk.usecase.StorageClient
import java.time.LocalDateTime
import java.util.UUID

/** 함께 찾기 IT 가 공유하는 개인정보 검사용 상수. 응답/알림 어디에도 나오면 안 되는 값들. */
object PrivacyProbe {
    const val PHONE = "010-4444-3333"
    const val DESCRIPTION = "비밀설명토큰-노출금지"
    const val BLOCK_REASON = "운영차단사유토큰-노출금지"
    const val LAT = 37.987654
    const val LNG = 127.123456
}

/**
 * gRPC storage 없이 IT 를 띄우기 위한 스텁 + 접근 판정 메트릭용 레지스트리.
 *
 * 썸네일은 항상 `https://` 로 시드하므로 `MultimediaService.resolvePresignedUrl` 이 조기 반환하고
 * storage 스텁은 실제로 호출되지 않는다.
 *
 * `SearchGroupAccessResolver` 는 거절 카운터(`fmp.searchgroup.access.denied`)를,
 * Task 6/9 는 요청 카운터를 올리려고 `MeterRegistry` 를 주입받는다. `@DataJpaTest` 슬라이스에는
 * Micrometer 자동설정이 없으므로 여기서 직접 제공한다.
 */
@TestConfiguration
class CollaborationTestConfig {
    @Bean
    fun stubStorageClient(): StorageClient = mock()

    @Bean
    fun multimediaService(stubStorageClient: StorageClient): MultimediaService = MultimediaService(stubStorageClient)

    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * 설계 §7 권한 행렬이 요구하는 6개 역할 + 필요한 지원 연결이 전부 들어간 표준 세계.
 *
 * - [ownerId]        보호자 (post.authorId)
 * - [directMemberId] 직접 참여 ACTIVE
 * - [teamLeaderId]   팀장. 네 팀([teamId] · [otherTeamId] · [offerTeamId] · [inviteTeamId]) 전부의 팀장이다
 * - [teamMemberId]   팀원. 위 네 팀 모두에 ACTIVE MEMBER 로 들어가 있다 —
 *                    "해당 팀 소속이지만 팀장이 아님" 을 4개 지원 행위에서 동일하게 검증하기 위함
 * - [leftMemberId]   탈퇴자 (LEFT 행 보존)
 * - [blockedUserId]  차단자 (차단 시 직접 멤버십이 REMOVED 로 정리된 상태 + 활성 차단 행)
 * - [strangerId]     아무 관계 없는 사용자 (차단 대상 / 신규 가입자)
 */
data class CollaborationWorld(
    val ownerId: UUID,
    val directMemberId: UUID,
    val teamLeaderId: UUID,
    val teamMemberId: UUID,
    val leftMemberId: UUID,
    val blockedUserId: UUID,
    val strangerId: UUID,
    val applicantId: UUID,
    val postId: UUID,
    val groupId: UUID,
    val teamId: UUID,
    val otherTeamId: UUID,
    val offerTeamId: UUID,
    val inviteTeamId: UUID,
    val directMembershipId: UUID,
    val leftMembershipId: UUID,
    val pendingMembershipId: UUID,
    val activeSupportId: UUID,
    val groupApprovalSupportId: UUID,
    val teamApprovalSupportId: UUID,
)

class CollaborationFixtures(
    private val postRepository: PostRepository,
    private val postImageRepository: PostImageRepository,
    private val searchGroupRepository: SearchGroupRepository,
    private val searchGroupMemberRepository: SearchGroupMemberRepository,
    private val searchGroupTeamRepository: SearchGroupTeamRepository,
    private val searchGroupUserBlockRepository: SearchGroupUserBlockRepository,
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
) {
    fun post(
        ownerId: UUID,
        title: String = "실종 소식-${UUID.randomUUID()}",
        status: MissingAnimalStatus = MissingAnimalStatus.SEARCHING,
    ): Post =
        postRepository.save(
            Post(
                authorId = ownerId,
                authorName = "보호자",
                title = title,
                phoneNum = PrivacyProbe.PHONE,
                time = LocalDateTime.of(2026, 7, 20, 9, 0),
                place = "서울 강남구 역삼동",
                gender = "남아",
                gratuity = 0,
                description = PrivacyProbe.DESCRIPTION,
                lat = PrivacyProbe.LAT,
                lng = PrivacyProbe.LNG,
                openChatUrl = null,
                missingAnimalStatus = status,
                animalType = AnimalType.DOG,
            ),
        )

    fun thumbnail(
        post: Post,
        url: String = "https://cdn.platformholder.site/thumb-${UUID.randomUUID()}.jpg",
    ): PostImage = postImageRepository.save(PostImage(post = post, imageUrl = url))

    fun group(
        post: Post,
        joinPolicy: JoinPolicy = JoinPolicy.OPEN,
        status: SearchGroupStatus = SearchGroupStatus.ACTIVE,
    ): SearchGroup = searchGroupRepository.save(SearchGroup(postId = post.id, joinPolicy = joinPolicy, status = status))

    fun directMember(
        groupId: UUID,
        userId: UUID,
        status: SearchGroupMemberStatus = SearchGroupMemberStatus.ACTIVE,
        userName: String? = "참여자",
    ): SearchGroupMember =
        searchGroupMemberRepository.save(
            SearchGroupMember(
                groupId = groupId,
                userId = userId,
                userName = userName,
                status = status,
                joinedAt = if (status == SearchGroupMemberStatus.ACTIVE) LocalDateTime.now() else null,
                requestedAt = LocalDateTime.now(),
            ),
        )

    fun block(
        groupId: UUID,
        userId: UUID,
        blockedBy: UUID,
        reason: String? = PrivacyProbe.BLOCK_REASON,
    ): SearchGroupUserBlock =
        searchGroupUserBlockRepository.save(
            SearchGroupUserBlock(
                groupId = groupId,
                userId = userId,
                blockedBy = blockedBy,
                reason = reason,
                blockedAt = LocalDateTime.now(),
            ),
        )

    /** 팀 + ACTIVE LEADER 1명. `uq_tm_single_active_leader` 는 팀 단위라 한 사람이 여러 팀을 이끌 수 있다. */
    fun team(
        leaderId: UUID,
        name: String = "수색대-${UUID.randomUUID()}",
        leaderName: String? = "팀장",
    ): Team {
        val saved = teamRepository.save(Team(name = name, description = null, status = TeamStatus.ACTIVE, createdBy = leaderId))
        teamMember(saved.id, leaderId, TeamRole.LEADER, TeamMemberStatus.ACTIVE, leaderName)
        return saved
    }

    fun teamMember(
        teamId: UUID,
        userId: UUID,
        role: TeamRole = TeamRole.MEMBER,
        status: TeamMemberStatus = TeamMemberStatus.ACTIVE,
        userName: String? = "팀원",
    ): TeamMember =
        teamMemberRepository.save(
            TeamMember(
                teamId = teamId,
                userId = userId,
                userName = userName,
                role = role,
                status = status,
                joinedAt = if (status == TeamMemberStatus.ACTIVE) LocalDateTime.now() else null,
                requestedAt = LocalDateTime.now(),
            ),
        )

    fun teamSupport(
        groupId: UUID,
        teamId: UUID,
        status: SearchGroupTeamStatus,
        requestedBy: UUID,
    ): SearchGroupTeam =
        searchGroupTeamRepository.save(
            SearchGroupTeam(
                groupId = groupId,
                teamId = teamId,
                status = status,
                requestedBy = requestedBy,
                requestedAt = LocalDateTime.now(),
                activatedAt = if (status == SearchGroupTeamStatus.ACTIVE) LocalDateTime.now() else null,
            ),
        )

    /** 매 호출마다 완전히 새로운 세계를 만든다 — 파라미터라이즈드 케이스끼리 서로 오염되지 않는다. */
    fun standardWorld(joinPolicy: JoinPolicy = JoinPolicy.OPEN): CollaborationWorld {
        val ownerId = UUID.randomUUID()
        val directMemberId = UUID.randomUUID()
        val teamLeaderId = UUID.randomUUID()
        val teamMemberId = UUID.randomUUID()
        val leftMemberId = UUID.randomUUID()
        val blockedUserId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        val applicantId = UUID.randomUUID()

        val post = post(ownerId)
        thumbnail(post)
        val group = group(post, joinPolicy)

        val direct = directMember(group.id, directMemberId, SearchGroupMemberStatus.ACTIVE, "직접참여자")
        val left = directMember(group.id, leftMemberId, SearchGroupMemberStatus.LEFT, "탈퇴자")
        val pending = directMember(group.id, applicantId, SearchGroupMemberStatus.PENDING, "신청자")
        // 차단은 직접 멤버십을 REMOVED 로 정리한 뒤 차단 행을 남긴다 —
        // SearchGroupBlockService.block 이 실제로 만드는 상태와 동일하게 시드한다.
        directMember(group.id, blockedUserId, SearchGroupMemberStatus.REMOVED, "차단자")
        block(group.id, blockedUserId, ownerId)

        val supportingTeam = team(teamLeaderId)
        teamMember(supportingTeam.id, teamMemberId, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        val activeSupport = teamSupport(group.id, supportingTeam.id, SearchGroupTeamStatus.ACTIVE, ownerId)

        // 아직 이 그룹과 연결이 없는 팀. REQUEST_TEAM_SUPPORT 케이스가 쓴다.
        val otherTeam = team(teamLeaderId)
        teamMember(otherTeam.id, teamMemberId, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)

        // 팀이 먼저 제안한 연결(보호자 승인 대기).
        val offerTeam = team(teamLeaderId)
        teamMember(offerTeam.id, teamMemberId, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        val groupApprovalSupport =
            teamSupport(group.id, offerTeam.id, SearchGroupTeamStatus.PENDING_GROUP_APPROVAL, teamLeaderId)

        // 보호자가 먼저 요청한 연결(팀장 승인 대기).
        val inviteTeam = team(teamLeaderId)
        teamMember(inviteTeam.id, teamMemberId, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        val teamApprovalSupport =
            teamSupport(group.id, inviteTeam.id, SearchGroupTeamStatus.PENDING_TEAM_APPROVAL, ownerId)

        return CollaborationWorld(
            ownerId = ownerId,
            directMemberId = directMemberId,
            teamLeaderId = teamLeaderId,
            teamMemberId = teamMemberId,
            leftMemberId = leftMemberId,
            blockedUserId = blockedUserId,
            strangerId = strangerId,
            applicantId = applicantId,
            postId = post.id,
            groupId = group.id,
            teamId = supportingTeam.id,
            otherTeamId = otherTeam.id,
            offerTeamId = offerTeam.id,
            inviteTeamId = inviteTeam.id,
            directMembershipId = direct.id,
            leftMembershipId = left.id,
            pendingMembershipId = pending.id,
            activeSupportId = activeSupport.id,
            groupApprovalSupportId = groupApprovalSupport.id,
            teamApprovalSupportId = teamApprovalSupport.id,
        )
    }
}
```

- [ ] **Step 2: 권한 행렬 IT 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupPermissionMatrixIT.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import com.park.animal.support.CollaborationFixtures
import com.park.animal.support.CollaborationTestConfig
import com.park.animal.support.CollaborationWorld
import com.park.animal.team.TeamMembershipService
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 설계 §7 권한 표를 그대로 옮긴 전수 검증 — 6개 역할 × 14개 행위 = 84 케이스.
 *
 * 각 케이스는 자기만의 세계를 새로 만든다(행위가 상태를 바꾸므로 공유 금지).
 * 이 표가 Task 4·6~9 서비스의 권위 있는 기대값이다. 실패하면 이 파일이 아니라 서비스를 고친다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    CollaborationTestConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
    SearchLifecycleService::class,
    SearchGroupService::class,
    SearchGroupMembershipService::class,
    SearchGroupBlockService::class,
    SearchGroupTeamSupportService::class,
    TeamMembershipService::class,
)
@Testcontainers
class SearchGroupPermissionMatrixIT {
    @Autowired lateinit var accessResolver: SearchGroupAccessResolver

    @Autowired lateinit var searchGroupService: SearchGroupService

    @Autowired lateinit var membershipService: SearchGroupMembershipService

    @Autowired lateinit var blockService: SearchGroupBlockService

    @Autowired lateinit var teamSupportService: SearchGroupTeamSupportService

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var postImageRepository: PostImageRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var searchGroupUserBlockRepository: SearchGroupUserBlockRepository

    @Autowired lateinit var teamRepository: TeamRepository

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    private lateinit var fixtures: CollaborationFixtures

    @BeforeEach
    fun setUp() {
        fixtures =
            CollaborationFixtures(
                postRepository,
                postImageRepository,
                searchGroupRepository,
                searchGroupMemberRepository,
                searchGroupTeamRepository,
                searchGroupUserBlockRepository,
                teamRepository,
                teamMemberRepository,
            )
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @MethodSource("matrix")
    fun `설계 7 권한 표 전수 검증`(
        action: Action,
        actor: Actor,
        expected: Outcome,
    ) {
        val world = fixtures.standardWorld()
        val actorId = actorId(actor, world)

        val thrown =
            try {
                execute(action, world, actorId)
                null
            } catch (e: BusinessException) {
                e
            }

        when (expected) {
            is Outcome.Allow ->
                if (thrown != null) {
                    fail("$actor 는 $action 을 할 수 있어야 하는데 ${thrown.errorCode} 로 막혔다")
                }

            is Outcome.Deny -> {
                if (thrown == null) fail("$actor 는 $action 을 할 수 없어야 하는데 성공했다")
                assertEquals(expected.errorCode, thrown.errorCode, "$actor / $action 의 거부 코드가 다르다")
            }
        }
    }

    private fun actorId(
        actor: Actor,
        world: CollaborationWorld,
    ): UUID =
        when (actor) {
            Actor.OWNER -> world.ownerId
            Actor.DIRECT_MEMBER -> world.directMemberId
            Actor.TEAM_LEADER -> world.teamLeaderId
            Actor.TEAM_MEMBER -> world.teamMemberId
            Actor.LEFT_MEMBER -> world.leftMemberId
            Actor.BLOCKED_USER -> world.blockedUserId
        }

    private fun execute(
        action: Action,
        world: CollaborationWorld,
        actorId: UUID,
    ) {
        when (action) {
            // 실종 소식 수정·삭제는 PostService 의 authorId 검증(FR-3)이 담당한다.
            // 여기서는 그룹 관리 주체가 보호자와 동일함을 requireOwner 로 고정한다.
            Action.POST_MANAGE -> accessResolver.requireOwner(world.groupId, actorId)
            Action.CHANGE_JOIN_POLICY -> membershipService.updateJoinPolicy(world.groupId, actorId, JoinPolicy.APPROVAL_REQUIRED)
            Action.APPROVE_JOIN -> membershipService.approve(world.groupId, world.pendingMembershipId, actorId)
            Action.REJECT_JOIN -> membershipService.reject(world.groupId, world.pendingMembershipId, actorId)
            Action.REMOVE_MEMBER -> membershipService.remove(world.groupId, world.directMembershipId, actorId)
            Action.BLOCK_USER -> blockService.block(world.groupId, world.strangerId, actorId, null)
            Action.REQUEST_TEAM_SUPPORT -> teamSupportService.request(world.groupId, world.otherTeamId, actorId)
            Action.ACCEPT_TEAM_OFFER -> teamSupportService.accept(world.groupId, world.groupApprovalSupportId, actorId)
            Action.RESPOND_OWNER_REQUEST -> teamSupportService.accept(world.groupId, world.teamApprovalSupportId, actorId)
            Action.END_TEAM_SUPPORT -> teamSupportService.end(world.groupId, world.activeSupportId, actorId)
            Action.JOIN -> membershipService.join(world.groupId, actorId, "재가입자")
            Action.LEAVE -> membershipService.leaveMe(world.groupId, actorId)
            Action.READ_GROUP -> searchGroupService.getDetail(world.groupId, actorId)
            Action.END_SEARCH -> searchGroupService.endSearch(world.groupId, actorId)
        }
    }

    enum class Actor { OWNER, DIRECT_MEMBER, TEAM_LEADER, TEAM_MEMBER, LEFT_MEMBER, BLOCKED_USER }

    enum class Action {
        POST_MANAGE,
        CHANGE_JOIN_POLICY,
        APPROVE_JOIN,
        REJECT_JOIN,
        REMOVE_MEMBER,
        BLOCK_USER,
        REQUEST_TEAM_SUPPORT,
        ACCEPT_TEAM_OFFER,
        RESPOND_OWNER_REQUEST,
        END_TEAM_SUPPORT,
        JOIN,
        LEAVE,
        READ_GROUP,
        END_SEARCH,
    }

    sealed interface Outcome {
        data object Allow : Outcome

        data class Deny(val errorCode: ErrorCode) : Outcome
    }

    companion object {
        private val ALLOW = Outcome.Allow
        private val DENIED = Outcome.Deny(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
        private val CONFLICT = Outcome.Deny(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        private val NO_MEMBERSHIP = Outcome.Deny(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        private val LEADER_ONLY = Outcome.Deny(ErrorCode.TEAM_LEADER_REQUIRED)

        /**
         * 설계 §7 표 + 계약 §9 결정사항.
         * 행 = 행위, 열 = 역할. 14개 행위 중 12개는 §7 표의 행이고, JOIN 은 §8.1/§8.2 참여 흐름,
         * END_SEARCH 는 §7 마지막 행 "전체 수색 종료" 다.
         */
        private val MATRIX: Map<Action, Map<Actor, Outcome>> =
            mapOf(
                Action.POST_MANAGE to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to DENIED,
                        Actor.TEAM_MEMBER to DENIED,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.CHANGE_JOIN_POLICY to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to DENIED,
                        Actor.TEAM_MEMBER to DENIED,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.APPROVE_JOIN to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to DENIED,
                        Actor.TEAM_MEMBER to DENIED,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.REJECT_JOIN to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to DENIED,
                        Actor.TEAM_MEMBER to DENIED,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.REMOVE_MEMBER to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to DENIED,
                        Actor.TEAM_MEMBER to DENIED,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.BLOCK_USER to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to DENIED,
                        Actor.TEAM_MEMBER to DENIED,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.REQUEST_TEAM_SUPPORT to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        // 대상 팀(otherTeam)과 아무 관계가 없으면 팀 소속 여부를 알려주지 않고 403 DENIED 다.
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to ALLOW,
                        // 대상 팀의 ACTIVE 팀원이지만 팀장이 아니다 → 설계 §7 "팀장만".
                        Actor.TEAM_MEMBER to LEADER_ONLY,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.ACCEPT_TEAM_OFFER to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        // 제안을 낸 쪽(팀장)은 자기 제안을 스스로 수락할 수 없다.
                        Actor.TEAM_LEADER to CONFLICT,
                        Actor.TEAM_MEMBER to LEADER_ONLY,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.RESPOND_OWNER_REQUEST to
                    mapOf(
                        // 요청을 낸 쪽(보호자)은 자기 요청을 스스로 수락할 수 없다.
                        Actor.OWNER to CONFLICT,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to ALLOW,
                        Actor.TEAM_MEMBER to LEADER_ONLY,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.END_TEAM_SUPPORT to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to ALLOW,
                        Actor.TEAM_MEMBER to LEADER_ONLY,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.JOIN to
                    mapOf(
                        // 보호자는 자기 그룹에 "참여" 할 대상이 아니다.
                        Actor.OWNER to CONFLICT,
                        // 설계 §8.1 "중복 클릭과 재시도는 같은 활성 멤버십 하나만" → 이미 ACTIVE 면 멱등 성공.
                        Actor.DIRECT_MEMBER to ALLOW,
                        // 팀 경유로 이미 참여 중이라 직접 참여를 새로 만들 수 없다.
                        Actor.TEAM_LEADER to CONFLICT,
                        Actor.TEAM_MEMBER to CONFLICT,
                        // 탈퇴자는 같은 행의 status 전이로 재가입한다(설계 §6.2).
                        Actor.LEFT_MEMBER to ALLOW,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.LEAVE to
                    mapOf(
                        // 설계 §7 "개인 참여 종료 — 보호자: 해당 없음". 직접 멤버십 행이 없으므로 404.
                        Actor.OWNER to NO_MEMBERSHIP,
                        Actor.DIRECT_MEMBER to ALLOW,
                        Actor.TEAM_LEADER to NO_MEMBERSHIP,
                        Actor.TEAM_MEMBER to NO_MEMBERSHIP,
                        // leaveMe 는 requireRead 를 쓴다 → 유효 멤버가 아닌 탈퇴자는 읽기 단계에서 막힌다.
                        Actor.LEFT_MEMBER to DENIED,
                        // 설계 §6.3 "차단이 활성인 동안 모든 접근 거부" — 멤버십 조회 이전에 403.
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.READ_GROUP to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to ALLOW,
                        Actor.TEAM_LEADER to ALLOW,
                        Actor.TEAM_MEMBER to ALLOW,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
                Action.END_SEARCH to
                    mapOf(
                        Actor.OWNER to ALLOW,
                        Actor.DIRECT_MEMBER to DENIED,
                        Actor.TEAM_LEADER to DENIED,
                        Actor.TEAM_MEMBER to DENIED,
                        Actor.LEFT_MEMBER to DENIED,
                        Actor.BLOCKED_USER to DENIED,
                    ),
            )

        @JvmStatic
        fun matrix(): Stream<Arguments> =
            MATRIX.entries
                .flatMap { (action, byActor) ->
                    Actor.entries.map { actor ->
                        Arguments.of(action, actor, byActor.getValue(actor))
                    }
                }.stream()

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 3: 권한 행렬 실행**

Run: `cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.SearchGroupPermissionMatrixIT"`

Expected: PASS — 84개 케이스(14 행위 × 6 역할) 전부 통과.
실패하면 실패한 `(Action, Actor)` 이름이 테스트 이름에 그대로 찍힌다. **이 파일을 고치지 말고** 해당 Task 로 돌아간다:
`CHANGE_JOIN_POLICY`/`APPROVE_JOIN`/`REJECT_JOIN`/`REMOVE_MEMBER`/`JOIN`/`LEAVE` → Task 6, `BLOCK_USER` → Task 7, `REQUEST_TEAM_SUPPORT`/`ACCEPT_TEAM_OFFER`/`RESPOND_OWNER_REQUEST`/`END_TEAM_SUPPORT` → Task 9, `READ_GROUP`/`END_SEARCH` → Task 4, `POST_MANAGE` → Task 3.

- [ ] **Step 4: IDOR IT 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupIdorIT.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import com.park.animal.support.CollaborationFixtures
import com.park.animal.support.CollaborationTestConfig
import com.park.animal.team.TeamMembershipService
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 설계 §16.1 — child mutation 은 전체 관계 tuple 로 조회한다.
 * 부모(그룹/팀)의 권한만 통과하고 자식 id 를 따로 조회해 처리하면 교차 접근이 뚫린다.
 * 이 IT 는 "부모는 내 것, 자식은 남의 것" 조합이 전부 404 인지 고정한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    CollaborationTestConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
    SearchLifecycleService::class,
    SearchGroupService::class,
    SearchGroupMembershipService::class,
    SearchGroupTeamSupportService::class,
    TeamMembershipService::class,
)
@Testcontainers
class SearchGroupIdorIT {
    @Autowired lateinit var membershipService: SearchGroupMembershipService

    @Autowired lateinit var teamSupportService: SearchGroupTeamSupportService

    @Autowired lateinit var teamMembershipService: TeamMembershipService

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var postImageRepository: PostImageRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var searchGroupUserBlockRepository: SearchGroupUserBlockRepository

    @Autowired lateinit var teamRepository: TeamRepository

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    private lateinit var fixtures: CollaborationFixtures

    @BeforeEach
    fun setUp() {
        fixtures =
            CollaborationFixtures(
                postRepository,
                postImageRepository,
                searchGroupRepository,
                searchGroupMemberRepository,
                searchGroupTeamRepository,
                searchGroupUserBlockRepository,
                teamRepository,
                teamMemberRepository,
            )
    }

    @Test
    fun `다른 그룹의 membershipId 로 승인 거절 내보내기를 할 수 없다`() {
        val mine = fixtures.standardWorld()
        val other = fixtures.standardWorld()

        val approve = assertThrows<BusinessException> { membershipService.approve(mine.groupId, other.pendingMembershipId, mine.ownerId) }
        val reject = assertThrows<BusinessException> { membershipService.reject(mine.groupId, other.pendingMembershipId, mine.ownerId) }
        val remove = assertThrows<BusinessException> { membershipService.remove(mine.groupId, other.directMembershipId, mine.ownerId) }

        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP, approve.errorCode)
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP, reject.errorCode)
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP, remove.errorCode)

        // 남의 그룹 행은 손대지 않았다.
        assertEquals(
            SearchGroupMemberStatus.PENDING,
            searchGroupMemberRepository.findById(other.pendingMembershipId).orElseThrow().status,
        )
    }

    @Test
    fun `다른 그룹의 supportId 로 수락 거절 종료를 할 수 없다`() {
        val mine = fixtures.standardWorld()
        val other = fixtures.standardWorld()

        val accept =
            assertThrows<BusinessException> { teamSupportService.accept(mine.groupId, other.groupApprovalSupportId, mine.ownerId) }
        val decline =
            assertThrows<BusinessException> { teamSupportService.decline(mine.groupId, other.groupApprovalSupportId, mine.ownerId) }
        val end = assertThrows<BusinessException> { teamSupportService.end(mine.groupId, other.activeSupportId, mine.ownerId) }

        assertEquals(ErrorCode.NOT_FOUND_TEAM_SUPPORT, accept.errorCode)
        assertEquals(ErrorCode.NOT_FOUND_TEAM_SUPPORT, decline.errorCode)
        assertEquals(ErrorCode.NOT_FOUND_TEAM_SUPPORT, end.errorCode)
    }

    @Test
    fun `다른 팀의 membershipId 로 팀원 승인을 할 수 없다`() {
        val leaderA = UUID.randomUUID()
        val leaderB = UUID.randomUUID()
        val teamA = fixtures.team(leaderA)
        val teamB = fixtures.team(leaderB)
        val applicantInB =
            fixtures.teamMember(teamB.id, UUID.randomUUID(), TeamRole.MEMBER, TeamMemberStatus.PENDING, "B팀신청자")

        val thrown = assertThrows<BusinessException> { teamMembershipService.approve(teamA.id, applicantInB.id, leaderA) }

        assertEquals(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP, thrown.errorCode)
        assertEquals(TeamMemberStatus.PENDING, teamMemberRepository.findById(applicantInB.id).orElseThrow().status)
    }

    @Test
    fun `존재하지 않는 그룹 id 는 참여도 404 다`() {
        val world = fixtures.standardWorld()
        val ghostGroupId = UUID.randomUUID()

        val thrown = assertThrows<BusinessException> { membershipService.join(ghostGroupId, world.strangerId, "누군가") }

        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, thrown.errorCode)
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 5: 동시성 IT 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupConcurrencyIT.kt`

```kotlin
package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import com.park.animal.support.CollaborationFixtures
import com.park.animal.support.CollaborationTestConfig
import com.park.animal.team.TeamMembershipService
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 설계 §13.1 재시도 안전성 + §19.1 동시 수락 경합.
 *
 * 테스트 트랜잭션 래핑을 끄고(NOT_SUPPORTED) 실제 스레드로 경합시킨다.
 * 검증은 JPA 1차 캐시가 개입하지 않도록 raw JDBC COUNT(*) 로만 한다.
 *
 * `notification.group_id` 로 세는 단언들은 Task 5 가 `SearchLifecycleService.endSearch` 의 fan-out 을
 * `GroupNotificationPublisher.notifyGroup` 으로 교체한 뒤에만 통과한다(레거시 `createMany` 는 group_id 가 NULL).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    CollaborationTestConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
    SearchLifecycleService::class,
    SearchGroupService::class,
    SearchGroupMembershipService::class,
    SearchGroupTeamSupportService::class,
    TeamMembershipService::class,
)
@Testcontainers
class SearchGroupConcurrencyIT {
    @Autowired lateinit var membershipService: SearchGroupMembershipService

    @Autowired lateinit var teamSupportService: SearchGroupTeamSupportService

    @Autowired lateinit var searchGroupService: SearchGroupService

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var postImageRepository: PostImageRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var searchGroupUserBlockRepository: SearchGroupUserBlockRepository

    @Autowired lateinit var teamRepository: TeamRepository

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    private lateinit var fixtures: CollaborationFixtures

    @BeforeEach
    fun setUp() {
        fixtures =
            CollaborationFixtures(
                postRepository,
                postImageRepository,
                searchGroupRepository,
                searchGroupMemberRepository,
                searchGroupTeamRepository,
                searchGroupUserBlockRepository,
                teamRepository,
                teamMemberRepository,
            )
    }

    /** 모든 스레드를 같은 순간에 출발시키고, 각 스레드는 자기 TransactionTemplate 로 실행한다. */
    private fun runConcurrently(
        threads: Int,
        block: (Int) -> Unit,
    ): List<Result<Unit>> {
        val pool = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val results = Collections.synchronizedList(mutableListOf<Result<Unit>>())
        repeat(threads) { idx ->
            pool.submit {
                val template = TransactionTemplate(transactionManager)
                startGate.await()
                results +=
                    runCatching {
                        template.execute { block(idx) }
                        Unit
                    }
                doneGate.countDown()
            }
        }
        startGate.countDown()
        assertTrue(doneGate.await(60, TimeUnit.SECONDS), "동시 실행이 60초 안에 끝나야 한다")
        pool.shutdownNow()
        return results.toList()
    }

    private fun count(
        sql: String,
        vararg args: Any,
    ): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *args) ?: 0L

    private fun businessCauseOf(failure: Throwable): BusinessException =
        generateSequence(failure) { it.cause }.filterIsInstance<BusinessException>().firstOrNull()
            ?: fail("실패한 동시 요청은 BusinessException 으로 정규화돼야 한다 (raw duplicate-key / UnexpectedRollback 금지): $failure")

    @Test
    fun `동시 가입 클릭 10회에도 ACTIVE 멤버십 1행 알림 1건`() {
        val world = fixtures.standardWorld()
        val joiner = UUID.randomUUID()

        val results = runConcurrently(10) { membershipService.join(world.groupId, joiner, "동시가입자") }

        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM search_group_member WHERE group_id = ? AND user_id = ?",
                world.groupId.toString(),
                joiner.toString(),
            ),
            "자연키 UNIQUE 로 행은 정확히 1개여야 한다",
        )
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM search_group_member WHERE group_id = ? AND user_id = ? AND status = 'ACTIVE'",
                world.groupId.toString(),
                joiner.toString(),
            ),
        )
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM notification WHERE group_id = ? AND type = 'GROUP_MEMBER_JOINED'",
                world.groupId.toString(),
            ),
            "보호자에게 가는 참여 알림은 1건이어야 한다",
        )
        results.mapNotNull { it.exceptionOrNull() }.forEach { businessCauseOf(it) }
    }

    @Test
    fun `팀 지원 양방향 요청과 동시 수락에도 ACTIVE 연결 1행 알림 1건`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val post = fixtures.post(ownerId)
        val group = fixtures.group(post)
        val team = fixtures.team(leaderId)

        // (1) 보호자와 팀장이 동시에 지원 연결을 요청 → uq_sgt_group_team 으로 1행만 남는다.
        val requestResults =
            runConcurrently(2) { idx ->
                if (idx == 0) {
                    teamSupportService.request(group.id, team.id, ownerId)
                } else {
                    teamSupportService.request(group.id, team.id, leaderId)
                }
            }
        requestResults.mapNotNull { it.exceptionOrNull() }.forEach { businessCauseOf(it) }
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM search_group_team WHERE group_id = ? AND team_id = ?",
                group.id.toString(),
                team.id.toString(),
            ),
        )

        val supportId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM search_group_team WHERE group_id = ? AND team_id = ?",
                String::class.java,
                group.id.toString(),
                team.id.toString(),
            )!!.let(UUID::fromString)

        // (2) 보호자와 팀장이 동시에 수락 → 승자 1명, ACTIVE 1행, 수락 알림 1건.
        runConcurrently(2) { idx ->
            val actor = if (idx == 0) ownerId else leaderId
            teamSupportService.accept(group.id, supportId, actor)
        }

        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM search_group_team WHERE id = ? AND status = 'ACTIVE'", supportId.toString()),
        )
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM search_group_team WHERE id = ? AND activated_at IS NULL", supportId.toString()),
            "ACTIVE 로 전이했으면 activated_at 이 반드시 채워져 있어야 한다",
        )
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM notification WHERE group_id = ? AND type = 'TEAM_SUPPORT_ACCEPTED'",
                group.id.toString(),
            ),
        )
    }

    @Test
    fun `동시 수색 종료 2회에도 ARCHIVED 1회 SEARCH_ENDED 알림 1건`() {
        val world = fixtures.standardWorld()

        val results = runConcurrently(2) { searchGroupService.endSearch(world.groupId, world.ownerId) }

        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM search_group WHERE id = ? AND status = 'ARCHIVED' AND archived_at IS NOT NULL",
                world.groupId.toString(),
            ),
        )
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM search_group_event WHERE group_id = ? AND type = 'SEARCH_ENDED'", world.groupId.toString()),
            "감사 이벤트도 1건이어야 한다",
        )
        // 종료 알림 수신자 = 직접 ACTIVE 1 + 팀 ACTIVE 2(팀장·팀원). 차단자·탈퇴자·대기자·보호자 본인은 제외.
        assertEquals(
            3L,
            count("SELECT COUNT(*) FROM notification WHERE group_id = ? AND type = 'SEARCH_ENDED'", world.groupId.toString()),
            "수신자당 정확히 1건 — 두 번 종료돼도 중복 발송되면 안 된다",
        )

        // 두 스레드가 모두 ACTIVE 를 읽으면 패자의 조건부 UPDATE 가 0행이 되어 멱등 성공으로 끝난다(설계 §15).
        // 패자가 이미 ARCHIVED 를 읽었으면 requireOwner 단계에서 410 이 난다. 둘 다 정상이므로 실패는 0~1건이다.
        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.size <= 1, "적어도 한 번은 성공해야 한다 (실패 ${failures.size}건)")
        failures.forEach { failure ->
            val businessException = businessCauseOf(failure)
            assertTrue(
                businessException.errorCode in setOf(ErrorCode.SEARCH_ALREADY_ENDED, ErrorCode.SEARCH_GROUP_STATE_CONFLICT),
                "패자는 410 SEARCH_ALREADY_ENDED(이미 ARCHIVED 를 읽은 경우) 또는 " +
                    "409 SEARCH_GROUP_STATE_CONFLICT(조건부 UPDATE 가 0행인 경우)여야 한다: ${businessException.errorCode}",
            )
        }
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 6: 개인정보 IT 작성**

`src/test/kotlin/com/park/animal/searchgroup/SearchGroupPrivacyIT.kt`

```kotlin
package com.park.animal.searchgroup

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.park.animal.common.config.JpaConfig
import com.park.animal.notification.NotificationService
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import com.park.animal.searchhub.SearchHubService
import com.park.animal.searchhub.repository.SearchHubQueryRepository
import com.park.animal.support.CollaborationFixtures
import com.park.animal.support.CollaborationTestConfig
import com.park.animal.support.PrivacyProbe
import com.park.animal.team.TeamMembershipService
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertFalse

/**
 * 설계 §9 · §16.5 — 응답 본문과 알림 문구에 좌표·전화번호·차단 사유가 남지 않는다.
 * 차단 사유는 보호자 전용 `GET /search-groups/{id}/blocks` 에만 존재한다(계약 §9).
 *
 * 알림 검증은 `notification.group_id` 로 행을 찾으므로 Task 5 의 구조화 fan-out 교체가 선행돼야 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    CollaborationTestConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
    SearchLifecycleService::class,
    SearchGroupService::class,
    SearchGroupMembershipService::class,
    SearchGroupBlockService::class,
    SearchGroupTeamSupportService::class,
    TeamMembershipService::class,
    SearchHubQueryRepository::class,
    SearchHubService::class,
)
@Testcontainers
class SearchGroupPrivacyIT {
    @Autowired lateinit var searchGroupService: SearchGroupService

    @Autowired lateinit var membershipService: SearchGroupMembershipService

    @Autowired lateinit var teamSupportService: SearchGroupTeamSupportService

    @Autowired lateinit var searchHubService: SearchHubService

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var postImageRepository: PostImageRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var searchGroupUserBlockRepository: SearchGroupUserBlockRepository

    @Autowired lateinit var teamRepository: TeamRepository

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    private lateinit var fixtures: CollaborationFixtures

    private val mapper =
        jacksonObjectMapper().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeEach
    fun setUp() {
        fixtures =
            CollaborationFixtures(
                postRepository,
                postImageRepository,
                searchGroupRepository,
                searchGroupMemberRepository,
                searchGroupTeamRepository,
                searchGroupUserBlockRepository,
                teamRepository,
                teamMemberRepository,
            )
    }

    private fun assertNoSecrets(
        label: String,
        text: String,
    ) {
        assertFalse(text.contains(PrivacyProbe.PHONE), "$label 에 전화번호가 있다: $text")
        assertFalse(text.contains(PrivacyProbe.LAT.toString()), "$label 에 위도가 있다: $text")
        assertFalse(text.contains(PrivacyProbe.LNG.toString()), "$label 에 경도가 있다: $text")
        assertFalse(text.contains(PrivacyProbe.BLOCK_REASON), "$label 에 차단 사유가 있다: $text")
        assertFalse(text.contains(PrivacyProbe.DESCRIPTION), "$label 에 실종 소식 상세 설명이 있다: $text")
        assertFalse(text.contains("admin", ignoreCase = true), "$label 에 admin 어휘가 있다: $text")
    }

    @Test
    fun `그룹 상세와 멤버 목록 활동 기록 응답에 좌표 연락처 차단사유가 없다`() {
        val world = fixtures.standardWorld()

        assertNoSecrets("그룹 상세", mapper.writeValueAsString(searchGroupService.getDetail(world.groupId, world.ownerId)))
        assertNoSecrets("참여자 시점 상세", mapper.writeValueAsString(searchGroupService.getDetail(world.groupId, world.directMemberId)))
        assertNoSecrets("멤버 목록", mapper.writeValueAsString(membershipService.list(world.groupId, world.ownerId, null)))
        assertNoSecrets("지원 팀 목록", mapper.writeValueAsString(teamSupportService.list(world.groupId, world.ownerId)))
        assertNoSecrets("활동 기록", mapper.writeValueAsString(searchGroupService.listEvents(world.groupId, world.ownerId, 20, 0)))
    }

    @Test
    fun `공개 CTA 응답에 좌표 연락처가 없고 차단 여부도 드러나지 않는다`() {
        val world = fixtures.standardWorld()

        val anonymous = mapper.writeValueAsString(searchGroupService.getCta(world.postId, null))
        val blockedView = mapper.writeValueAsString(searchGroupService.getCta(world.postId, world.blockedUserId))

        assertNoSecrets("CTA(비로그인)", anonymous)
        assertNoSecrets("CTA(차단자)", blockedView)
        assertFalse(blockedView.contains("isBlocked"), "CTA 에 차단 여부 필드가 있으면 안 된다: $blockedView")
        assertFalse(blockedView.contains("BLOCKED"), "차단자는 비참여자와 구분되지 않는 viewerAction 을 받아야 한다: $blockedView")
    }

    @Test
    fun `허브 응답에 좌표 연락처 차단사유가 없다`() {
        val world = fixtures.standardWorld()

        assertNoSecrets("허브(보호자)", mapper.writeValueAsString(runBlocking { searchHubService.getHub(world.ownerId) }))
        assertNoSecrets("허브(참여자)", mapper.writeValueAsString(runBlocking { searchHubService.getHub(world.directMemberId) }))
    }

    @Test
    fun `알림 제목과 본문에 좌표 연락처 차단사유가 없다`() {
        val world = fixtures.standardWorld()
        membershipService.join(world.groupId, world.strangerId, "새참여자")
        searchGroupService.endSearch(world.groupId, world.ownerId)

        val rows =
            jdbcTemplate.queryForList(
                "SELECT type, title, IFNULL(body, '') AS body, IFNULL(link, '') AS link FROM notification WHERE group_id = ?",
                world.groupId.toString(),
            )

        assertFalse(rows.isEmpty(), "검증할 알림이 있어야 한다 (Task 5 의 구조화 fan-out 이 group_id 를 채워야 한다)")
        rows.forEach { row ->
            assertNoSecrets("알림 ${row["type"]}", "${row["title"]} ${row["body"]} ${row["link"]}")
        }
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
```

- [ ] **Step 7: 네 개 IT 전부 실행**

Run: `cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "com.park.animal.searchgroup.*IT"`

Expected: PASS — `SearchGroupPermissionMatrixIT`(84), `SearchGroupIdorIT`(4), `SearchGroupConcurrencyIT`(3), `SearchGroupPrivacyIT`(4) 전부 통과.
동시성 테스트가 `raw duplicate-key / UnexpectedRollback 금지` 메시지로 깨지면 Task 6/9 가 `@Transactional` 안에서 duplicate-key 를 catch 하고 있다는 뜻이다(계약 F14). 자연키 선조회 + 조건부 상태 전이로 고친다.
`검증할 알림이 있어야 한다` 로 깨지면 Task 5 의 `endSearch` fan-out 교체(R2)가 아직 안 된 것이다.

- [ ] **Step 8: 전체 회귀 + 커밋**

Run: `cd /Users/park/Desktop/project/animal && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test`

Expected: PASS — Task 0~10 의 IT 를 포함해 전부 통과.

```bash
cd /Users/park/Desktop/project/animal && \
git add src/test/kotlin/com/park/animal/support src/test/kotlin/com/park/animal/searchgroup && \
git commit -m "test(search-group): 권한 행렬 / IDOR / 동시성 / 개인정보 IT

- SearchGroupPermissionMatrixIT: 설계 7절 표를 6역할 x 14행위 파라미터라이즈드로 전수 검증
  - JOIN 은 이미 참여 중이면 멱등 성공, LEAVE 는 보호자에게 404, 차단자에게 403
  - 팀 지원 4행위는 해당 팀 팀원이면 TEAM_LEADER_REQUIRED, 무관하면 ACCESS_DENIED
- SearchGroupIdorIT: 교차 membershipId/supportId/teamId 접근이 전부 404
- SearchGroupConcurrencyIT: 동시 가입 10회 / 팀 지원 양방향 요청+동시 수락 / 동시 수색 종료
  - 실제 스레드 + CountDownLatch + 스레드별 TransactionTemplate, 검증은 raw JDBC COUNT(*)
- SearchGroupPrivacyIT: 응답/CTA/허브/알림에 좌표·전화번호·차단 사유·admin 어휘 없음
- CollaborationFixtures: post/group/team/membership 공용 픽스처 + storage 스텁 + MeterRegistry"
```

---

### Task 12: PRD · API 스펙 동기화

**Files:**
- Modify: `../prd/find-my-pet/requirements.md` (절대 경로 `/Users/park/Desktop/project/prd/find-my-pet/requirements.md`) — FR-17 절 신설(FR-16 뒤, `## 외부 의존성` 앞) + `## 구현 상태` 표에 행 추가
- Modify: `../prd/find-my-pet/api-spec.md` (절대 경로 `/Users/park/Desktop/project/prd/find-my-pet/api-spec.md`) — Error Codes 표 확장, `## Search Group (함께 찾기)` / `## Team` / `## Search Hub` 섹션 신설(`## Bookmark` 앞), NotificationType 표 확장, `설계 대비 변경` 표, `변경 이력` 행 추가
- Read-only (수정 금지): `docs/superpowers/specs/2026-07-25-fmp-search-collaboration-design.md` — 승인된 설계 메모
- Judgement only: `../../marketing/services/find-my-pet/feature-truth.md`

**Interfaces:**
- Consumes: Task 1~11 의 실제 산출물 — `ErrorCode` 신규 11개, `NotificationType` 신규 20개 상수, 계약 §8 의 엔드포인트 32개 + R9 의 `POST /teams/{teamId}/archive`(#33), `V12__add_search_group_and_team.sql`, 각 도메인 응답 DTO(`SearchGroupCtaResponse`, `SearchGroupDetailResponse`, `SearchGroupEventResponse`, `SearchGroupMembershipResponse`, `JoinSearchGroupResponse`, `SearchGroupBlockResponse`, `SearchGroupTeamSupportResponse`, `TeamResponse`, `TeamSummaryResponse`, `TeamMembershipResponse`, `SearchHubResponse`).
- Produces: 프런트엔드/게이트웨이가 참조하는 제품 계약 문서. 이후 마케팅 `feature-truth.md` 동기화의 입력.

> `/Users/park/Desktop/project/prd` 는 git 저장소가 아니다(`git rev-parse --show-toplevel` 실패, 상위에도 `.git` 없음). 이 태스크의 산출물은 파일 내용 자체이며 커밋 대상이 아니다. `animal` 레포에는 이 태스크에서 코드 변경이 없다.

---

- [ ] **Step 1: 실제 구현과 문서를 대조할 사실 목록 추출**

문서를 손으로 쓰기 전에 코드에서 사실을 뽑는다. 이 출력이 이후 스텝의 유일한 근거다.

Run:
```bash
cd /Users/park/Desktop/project/animal && \
echo "=== ErrorCode (함께 찾기) ===" && \
sed -n '/함께 찾기 (수색그룹\/팀)/,/UNKNOWN_ERROR/p' src/main/kotlin/com/park/animal/common/http/error/ErrorCode.kt && \
echo "=== NotificationType ===" && \
cat src/main/kotlin/com/park/animal/notification/entity/NotificationType.kt && \
echo "=== 엔드포인트 전수 ===" && \
grep -rn "@GetMapping\|@PostMapping\|@PatchMapping\|@DeleteMapping\|@PutMapping" \
  src/main/kotlin/com/park/animal/searchgroup src/main/kotlin/com/park/animal/team src/main/kotlin/com/park/animal/searchhub && \
echo "=== 응답 DTO 필드 ===" && \
cat src/main/kotlin/com/park/animal/searchgroup/dto/*.kt src/main/kotlin/com/park/animal/team/dto/*.kt \
    src/main/kotlin/com/park/animal/searchhub/dto/*.kt && \
echo "=== 요청 DTO 필드 ===" && \
grep -rn "data class .*Request" src/main/kotlin/com/park/animal/searchgroup src/main/kotlin/com/park/animal/team -A 8 && \
echo "=== 컨트롤러 반환 타입 ===" && \
grep -rn "SucceededApiResponseBody<\|PaginatedApiResponseBody<" \
  src/main/kotlin/com/park/animal/searchgroup src/main/kotlin/com/park/animal/team src/main/kotlin/com/park/animal/searchhub
```

Expected:
- 함께 찾기 엔드포인트 32개가 출력된다(계약 §8 의 31개 신규 + R9 의 `POST /teams/{teamId}/archive`). 기존 `POST /post` 는 `post` 패키지라 여기 잡히지 않는다 → 문서 총계는 33개.
- ErrorCode 11개, `NotificationType` 신규 상수 20개(phase 1 발행 16 + 선언만 4)가 모두 출력된다.
- 목록 4종(`memberships`, `blocks`, `team-supports`, `teams/{id}/memberships`)의 반환 타입이 `SucceededApiResponseBody<List<...>>` 이고, `GET /teams` 만 `PaginatedApiResponseBody<TeamSummaryResponse>` 다.

**출력과 아래 Step 2~9 의 문서 내용이 다르면 문서를 코드에 맞춘다** — 문서가 아니라 코드가 사실이다. 특히 DTO 필드명은 Step 1 출력값을 그대로 옮긴다.
단 하나의 예외: `UpdateJoinPolicyRequest` 의 KDoc 이 "필드 누락 시 `INVALID_COLLABORATION_INPUT`" 이라고 적혀 있으면 그것은 Task 6 의 오기다. 실제 매핑은 `GlobalExceptionController` 의 `HttpMessageNotReadableException → MISSING_PARAMETER`(400) 이며 **문서는 코드 동작(`MISSING_PARAMETER`)을 따른다**. 오기 자체는 Task 6 에서 수정한다.

- [ ] **Step 2: `requirements.md` 에 FR-17 절 추가**

`### FR-16 …` 블록 뒤, `---` + `## 외부 의존성` 앞에 삽입한다.

```markdown
### FR-17. 함께 찾기 (수색그룹 · 팀)

실종 소식 한 건마다 **수색그룹**이 하나 열리고, 개인과 팀이 그 그룹에 모여 함께 찾는다.
설계 원본: `animal/docs/superpowers/specs/2026-07-25-fmp-search-collaboration-design.md` (승인된 설계 메모, 수정 금지).
Phase 1 범위는 그룹·팀·승인·마이페이지 허브까지다. 통합 지도(phase 2)와 기록형 그룹 채팅(phase 3)은 이 절에 포함되지 않는다.

#### 제품 언어

사용자에게 보이는 문구는 다음 어휘만 쓴다: **수색그룹 / 보호자 / 팀장 / 팀원 / 함께 찾기 / 확인할 요청 / 수색 종료 / 우리 팀의 지원 종료**.
영문 `admin` 은 제품 역할로 쓰지 않는다. 알림·응답 어디에도 좌표·전화번호·차단 사유를 담지 않는다.

#### 도메인 모델

| 테이블 | 역할 | 핵심 제약 |
|---|---|---|
| `search_group` | 실종 소식 1건당 수색그룹 1개 | `UNIQUE(post_id)`, FK → `post(id)`. `join_policy` = `OPEN`/`APPROVAL_REQUIRED`, `status` = `ACTIVE`/`ARCHIVED`, `archived_reason` = `FOUND`/`POST_DELETED` |
| `search_group_member` | 직접 참여 멤버십 | `UNIQUE(group_id, user_id)`. `status` = `PENDING`/`ACTIVE`/`REJECTED`/`LEFT`/`REMOVED` |
| `search_group_user_block` | 보호자의 사용자 차단 | `UNIQUE(group_id, user_id)`. `unblocked_at IS NULL` = 차단 활성. `reason` 은 보호자 전용 조회에만 노출 |
| `team` | 사용자가 만드는 수색 팀 | `status` = `ACTIVE`/`ARCHIVED`. 이름 2~30자, 소개 200자 이하 |
| `team_member` | 팀 멤버십 | `UNIQUE(team_id, user_id)` + 생성 컬럼 `active_leader_key` 로 **팀당 활성 팀장 1명** 강제. `role` = `LEADER`/`MEMBER` |
| `search_group_team` | 그룹 ↔ 팀 지원 연결 | `UNIQUE(group_id, team_id)`. `status` = `PENDING_GROUP_APPROVAL`/`PENDING_TEAM_APPROVAL`/`ACTIVE`/`DECLINED`/`WITHDRAWN`/`REMOVED` |
| `search_group_event` | 그룹 감사 로그 | 상태 전이 요약만 저장. 메시지 본문·좌표 저장 금지 |

마이그레이션: `V12__add_search_group_and_team.sql` (신규 7개 테이블 + `notification` 구조화 컬럼 5개 + 기존 SEARCHING 실종 소식 백필).
신규 테이블 타임스탬프는 `DATETIME(6)` — `post` 와 조인 정합을 맞추고 `TIMESTAMP` 의 세션 timezone 변환·초 정밀도 문제를 피한다.
멤버십/연결 테이블은 `deleted_at` 을 사용하지 않는다(항상 NULL). 생명주기는 **status 전이로만** 표현하며, 그래서 탈퇴 후 재가입이 가능하다.

#### 유효 멤버십 (Effective Membership)

그룹에 대한 접근 권한 = **보호자 ∪ 직접 `ACTIVE` 멤버 ∪ `ACTIVE` 지원 팀의 `ACTIVE` 팀원 − 활성 차단자**.
팀원 변경·팀 지원 종료·팀 보관은 파생 권한에 즉시 반영된다. 차단 anti-join 은 보호자 자신에게 적용하지 않는다.

#### 권한 (설계 §7)

| 행위 | 보호자 | 개인 참여자 | 팀장 | 팀원 |
|---|:---:|:---:|:---:|:---:|
| 실종 소식 수정·삭제 | O | X | X | X |
| 참여 정책 변경 | O | X | X | X |
| 직접 참여 요청 승인·거절 | O | X | X | X |
| 직접 참여자 내보내기 | O | X | X | X |
| 사용자 차단·차단 해제 | O | X | X | X |
| 팀에 지원 요청 | O | X | X | X |
| 팀의 지원 제안 수락·거절 | O | X | X | X |
| 보호자 요청 수락·거절 | X | X | O | X |
| 우리 팀의 지원 종료 | O | X | O | X |
| 개인 참여 종료 | 해당 없음 | O | 직접 참여 중이면 O | 직접 참여 중이면 O |
| 그룹 조회·알림 수신 | O | 활성일 때 O | 팀 지원 활성일 때 O | 팀 지원 활성일 때 O |
| 전체 수색 종료 | O | X | X | X |

권한 판정은 `SearchGroupAccessResolver` 단일 진입점이 담당하며 `Passport.role` 을 보지 않는다.
탈퇴자·차단자는 **권한 없음과 구분되지 않는 응답**을 받는다 — 차단 여부를 알려주는 필드를 두지 않는다.
팀 지원 4행위(요청·수락·거절·종료)를 해당 팀의 `ACTIVE` 팀원(비팀장)이 시도하면 `403 TEAM_LEADER_REQUIRED`,
그 팀과 아무 관계가 없으면 소속 여부를 알리지 않도록 `403 SEARCH_GROUP_ACCESS_DENIED` 를 준다.

#### 생명주기

- 실종 소식을 `SEARCHING` 으로 등록하면 `OPEN` 정책의 `ACTIVE` 그룹이 정확히 1개 생긴다. `SEEN` 으로 등록하면 그룹을 만들지 않고, 이후 `SEARCHING` 으로 바뀔 때 만든다.
- `SEARCHING → SEEN` 은 그룹을 그대로 둔다. `→ FOUND` 는 그룹을 `ARCHIVED(FOUND)` 로 만들고 `SEARCH_ENDED` 알림을 유효 멤버에게 1회 보낸다.
- 실종 소식 soft-delete 는 그룹을 `ARCHIVED(POST_DELETED)` 로 만든다.
- **재활성화 API 는 없다.** 이미 `ARCHIVED` 인 그룹의 글을 `FOUND → SEARCHING` 으로 되돌리려 하면 `410 SEARCH_ALREADY_ENDED` 로 막고 "새 실종 소식을 등록해 주세요" 를 안내한다. 단 그룹 행이 아예 없는 legacy `FOUND` 글은 `OPEN` 그룹을 새로 만들며 허용한다.
- 팀은 팀장이 `POST /teams/{teamId}/archive` 로 보관할 수 있다. 보관하면 그 팀의 모든 `ACTIVE` 지원 연결이 `WITHDRAWN` 으로 정리되고 파생 권한이 회수되며, 그 뒤 마지막 팀장도 팀을 나갈 수 있다(설계 §6.4).
- 상태 전이는 전부 `UPDATE ... WHERE id = :id AND <부모 id> = :parentId AND status = :expected` 조건부 업데이트다. 영향 행 0 = 이미 다른 상태 = `409 SEARCH_GROUP_STATE_CONFLICT`. 비관적 락은 쓰지 않는다.
- 멱등성은 자연키 `UNIQUE` + 조건부 전이로 얻는다. 별도 `Idempotency-Key` 헤더나 dedup 테이블을 두지 않는다. **같은 요청을 다시 보내면 409 가 아니라 같은 결과를 200 으로 돌려준다**(설계 §15 재시도 안전).

#### 마이페이지 통합 허브

`GET /api/v1/me/search-hub` 하나로 (1) 함께 찾는 반려동물 (2) 내가 참여한 팀 (3) 확인할 요청 (4) 요약 카운트를 반환한다.
- 섹션별 상한 10건 + `hasMore`.
- 요약과 각 동물 카드에 **미읽음 알림 수**를 담는다(설계 §10). 그룹별 집계는 `notification.group_id` 기준이며 `idx_noti_user_group` 을 쓴다. **채팅 미읽음은 phase 3 범위라 포함되지 않는다.**
- "확인할 요청" 은 알림이 아니라 **PENDING 행**(직접 참여 신청 / 팀 지원 제안 / 팀 합류 신청 / 팀 지원 요청)에서 만든다.
- 카드에는 좌표·연락처·상세 설명을 담지 않는다. 카드 → 목적 화면 1회, 세부 관리 2회 이동을 넘지 않도록 링크 필드를 카드마다 둔다.
- 통합 지도는 phase 2 범위라 진입 경로(`mapPath`)와 `mapAvailable=false` 만 내려주고 지도 데이터는 담지 않는다.

#### 보안·개인정보

- 모든 child mutation 은 전체 관계 tuple(그룹+멤버십, 그룹+지원연결, 팀+멤버십)로 조회한다. 부모 권한만 확인하고 자식 id 를 따로 조회해 처리하지 않는다(IDOR 차단).
- 모든 조회·변경에서 `deletedAt`, 그룹 상태, 멤버 상태, 팀 지원 상태를 명시적으로 검사한다.
- 그룹·팀·마이페이지 경로는 sitemap 제외 + `noindex` 처리한다. **이 처리는 `find-my-pet-frontend` 범위이며 백엔드 작업이 아니다.**
- 실시간 팀원 위치·수색 동선·백그라운드 위치는 수집하지 않는다.
```

Run(검증): `grep -n "FR-17" /Users/park/Desktop/project/prd/find-my-pet/requirements.md`
Expected: FR-17 절 제목 1건이 잡힌다(구현 상태 표 행은 Step 3 이후에 추가된다).

- [ ] **Step 3: `requirements.md` 의 `## 구현 상태` 표에 행 추가**

`| 공유하기 (FR-16) | ✅ | ... |` 행 **뒤**에 이어 붙인다. 표 헤더(`| 영역 | 상태 | 비고 |`)는 그대로 둔다.

```markdown
| 함께 찾기 — 수색그룹 (FR-17) | ⚠️ 백엔드만 | Flyway `V12`, `search_group`·`search_group_member`·`search_group_user_block`·`search_group_team`·`search_group_event`. 접근 판정 `SearchGroupAccessResolver`, 생명주기 단일 진입점 `SearchLifecycleService`. **프론트엔드 미구현 → 사용자에게 노출되지 않음** |
| 함께 찾기 — 팀 (FR-17) | ⚠️ 백엔드만 | `team`·`team_member`(활성 팀장 1명 DB 강제), 팀장 이전은 강등→승격 2회 UPDATE, 팀 보관 `POST /teams/{id}/archive`. 프론트엔드 미구현 |
| 함께 찾기 — 마이페이지 허브 (FR-17) | ⚠️ 백엔드만 | `GET /me/search-hub` 단일 집계(JPA 쿼리 8개 고정, N+1 없음). 확인할 요청은 PENDING 행 기반, 미읽음 알림 수 포함. 프론트엔드 미구현 |
| 함께 찾기 — 알림 (FR-17) | ⚠️ 백엔드만 | `NotificationType` 20개 상수 추가(phase 1 발행 16 + 선언만 4) + `notification` 구조화 컬럼(actor_user_id/actor_name/post_id/group_id/team_id) |
| 함께 찾기 — 테스트 | ✅ | 권한 행렬(6역할×14행위)·IDOR·동시성·개인정보 IT. CI 백엔드 테스트 job 신설(Task 0)로 실제 실행됨 |
| 통합 지도 (FR-17 phase 2) | ❌ 미착수 | `/me/search-map`, `/teams/{id}/search-map`, `/search-groups/{id}/map` |
| 기록형 그룹 채팅 (FR-17 phase 3) | ❌ 미착수 | 메시지 저장 + WebSocket 실시간 전송, `realtime-grants`, 채팅 미읽음 수 |
```

Run(검증): `grep -c "FR-17" /Users/park/Desktop/project/prd/find-my-pet/requirements.md`
Expected: `8` 이상 — FR-17 절 제목 1건 + 구현 상태 표 7건.

- [ ] **Step 4: `api-spec.md` Error Codes 표 확장**

`| \`UNKNOWN_ERROR\` | 500 | ERROR | 알 수 없는 에러 | 처리되지 않은 예외 |` 행 **앞**에 12행을 삽입하고, 표 바로 아래 `처리 위치: ...` 문단 앞에 주석 문단을 넣는다.

```markdown
| `NOT_FOUND_SEARCH_GROUP` | 404 | WARN | 요청한 정보를 찾을 수 없어요. | 수색그룹 미존재 / 실종 소식 soft-delete |
| `NOT_FOUND_SEARCH_GROUP_MEMBERSHIP` | 404 | WARN | 요청한 정보를 찾을 수 없어요. | membershipId 미존재 또는 **다른 그룹 소속**(IDOR 차단). 직접 멤버십이 없는 사용자의 `DELETE /memberships/me` 포함 |
| `NOT_FOUND_TEAM` | 404 | WARN | 요청한 정보를 찾을 수 없어요. | teamId 미존재 / soft-delete / `ARCHIVED` |
| `NOT_FOUND_TEAM_MEMBERSHIP` | 404 | WARN | 요청한 정보를 찾을 수 없어요. | membershipId 미존재 또는 **다른 팀 소속** |
| `NOT_FOUND_TEAM_SUPPORT` | 404 | WARN | 요청한 정보를 찾을 수 없어요. | supportId 미존재 또는 **다른 그룹 소속** |
| `SEARCH_GROUP_ACCESS_DENIED` | 403 | WARN | 현재 이 수색그룹을 이용할 권한이 없어요. | 비참여자 · 탈퇴자 · 차단자 · 보호자 전용 작업 시도 · 대상 팀과 무관한 사용자의 지원 연결 조작 |
| `SEARCH_GROUP_STATE_CONFLICT` | 409 | WARN | 처리할 수 없는 상태예요. 최신 상태를 다시 불러와 주세요. | 조건부 UPDATE 영향 행 0(다른 상태로 이미 전이됨) / 보호자의 자기 그룹 참여 시도 / 팀 경유 참여자의 직접 참여 시도 / 보호자 자신 차단 시도 / 요청자 본인의 자기 요청 수락 |
| `SEARCH_ALREADY_ENDED` | 410 | WARN | 종료된 수색이에요. 이전 기록만 확인할 수 있어요. | ARCHIVED 그룹에 쓰기 시도 / FOUND → SEARCHING 되돌리기 시도 |
| `TEAM_LEADER_REQUIRED` | 403 | WARN | 팀장만 할 수 있는 작업이에요. | 해당 팀의 `ACTIVE` 팀원(비팀장)이 팀장 전용 작업 시도 |
| `TEAM_LEADER_CANNOT_LEAVE` | 409 | WARN | 팀장 권한을 다른 팀원에게 넘긴 뒤에 나갈 수 있어요. | 활성 팀장이 `DELETE /teams/{id}/memberships/me` 호출 |
| `INVALID_COLLABORATION_INPUT` | 400 | WARN | 입력값을 다시 확인해 주세요. | **서비스 계층 명시 검증 실패 전용** — 팀 이름 2~30자 위반, 팀 소개 200자 초과, 지원 메시지 200자 초과 |
```

표 아래 주석 문단:

```markdown
> **의도적 상태코드 불일치 (읽고 나서 프론트 분기할 것)**
> 이 문서 위쪽의 legacy `NOT_FOUND_*` 계열(`NOT_FOUND_POST`, `NOT_FOUND_BREED`, `NOT_FOUND_SIGHTING` …)은 전부 **400** 이다.
> 반면 함께 찾기(FR-17) 도메인은 설계 §15 를 지키기 위해 **404 / 409 / 410** 을 쓴다.
> 기존 400 을 소급 변경하지 않는다 — 프론트엔드 계약이 깨진다. 두 규약이 한 서비스에 공존하는 것이 현재 상태다.
>
> **401 은 이 백엔드가 내지 않는다.** 미인증·passport 파싱 실패는 `PassportInterceptor`/`AuthenticationResolver` 가 **403 `FORBIDDEN`** 으로 처리한다. 401 은 게이트웨이 소관이다.
> 그래서 공개 CTA 응답(`GET /posts/{postId}/search-group`)은 상태코드가 아니라 본문 `viewerAction = "LOGIN_REQUIRED"` 로 로그인 필요를 알린다. **프론트는 상태코드로 인증 여부를 추론하지 않는다.**
>
> **입력 검증 두 갈래**
> - 요청 본문이 아예 파싱되지 않는 경우(깨진 JSON, Kotlin non-null 필드 누락, **enum 오타 — 예: `joinPolicy: "open"`**)는 `HttpMessageNotReadableException` 이 나고 `GlobalExceptionController` 가 **400 `MISSING_PARAMETER`** 로 매핑한다.
> - 본문은 파싱됐지만 값이 규칙을 어기는 경우(팀 이름 길이, 소개 길이, 메시지 길이)는 서비스 계층 명시 코드가 **400 `INVALID_COLLABORATION_INPUT`** 을 던진다.
> `spring-boot-starter-validation` 이 classpath 에 없어 `@Valid`/`@field:NotBlank` 는 무동작이다. 이 두 갈래가 함께 찾기 도메인 입력 검증의 전부다.
```

Run(검증): `grep -c "SEARCH_GROUP\|SEARCH_ALREADY_ENDED\|TEAM_LEADER\|INVALID_COLLABORATION_INPUT" /Users/park/Desktop/project/prd/find-my-pet/api-spec.md`
Expected: 12 이상.

- [ ] **Step 5: `api-spec.md` 에 `## Search Group (함께 찾기)` 섹션 신설**

`## Bookmark (즐겨찾기)` 앞에 삽입한다. 엔드포인트 19개(#2~#20)를 다룬다.

````markdown
## Search Group (함께 찾기)

실종 소식 1건 = 수색그룹 1개. 도메인 규칙은 `requirements.md` FR-17 참조.
이 섹션의 모든 경로는 `/api/v1` 프리픽스가 붙는다. 상태코드 규약은 Error Codes 표 아래 주석을 먼저 읽는다.

| Method | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/posts/{postId}/search-group` | 공개 CTA — 참여 정책·참여 수·다음 행동 | 선택 (`isRequired=false`) |
| GET | `/search-groups/{groupId}` | 수색그룹 상세 | USER |
| PATCH | `/search-groups/{groupId}/join-policy` | 참여 정책 변경 (보호자) | USER |
| POST | `/search-groups/{groupId}/end` | 전체 수색 종료 (보호자) | USER |
| GET | `/search-groups/{groupId}/events` | 활동 기록 (감사 로그) | USER |
| POST | `/search-groups/{groupId}/memberships` | 함께 찾기 참여/참여 신청 | USER |
| GET | `/search-groups/{groupId}/memberships` | 참여자 목록 | USER |
| POST | `/search-groups/{groupId}/memberships/{membershipId}/approve` | 참여 신청 승인 (보호자) | USER |
| POST | `/search-groups/{groupId}/memberships/{membershipId}/reject` | 참여 신청 거절 (보호자) | USER |
| POST | `/search-groups/{groupId}/memberships/{membershipId}/remove` | 참여자 내보내기 (보호자) | USER |
| DELETE | `/search-groups/{groupId}/memberships/me` | 개인 참여 종료 | USER |
| GET | `/search-groups/{groupId}/blocks` | 차단 목록 (보호자 전용) | USER |
| POST | `/search-groups/{groupId}/blocks` | 사용자 차단 (보호자) | USER |
| DELETE | `/search-groups/{groupId}/blocks/{userId}` | 차단 해제 (보호자) | USER |
| POST | `/search-groups/{groupId}/team-supports` | 팀 지원 연결 요청/제안 | USER |
| GET | `/search-groups/{groupId}/team-supports` | 지원 팀 목록 | USER |
| POST | `/search-groups/{groupId}/team-supports/{supportId}/accept` | 지원 수락 | USER |
| POST | `/search-groups/{groupId}/team-supports/{supportId}/decline` | 지원 거절 | USER |
| DELETE | `/search-groups/{groupId}/team-supports/{supportId}` | 지원 종료 | USER |

> **멱등성 공통 규약 (설계 §15 재시도 안전).** 이 섹션의 모든 상태 변경은 자연키 `UNIQUE` + 조건부 상태 전이로 구현된다.
> **같은 요청을 여러 번 보내도 409 가 아니라 같은 결과를 200 으로 돌려준다.** 409 는 "이미 목표 상태" 가 아니라
> "목표로 갈 수 없는 다른 상태" 일 때만 난다. 네트워크 재시도·중복 클릭에 프론트가 방어 코드를 넣을 필요가 없다.

> **sitemap / noindex.** 그룹·팀·마이페이지 경로의 sitemap 제외와 `noindex` 처리(설계 §16.6)는 `find-my-pet-frontend` 범위이며 이 계획서와 백엔드 계약에서 다루지 않는다.

### GET `/posts/{postId}/search-group`

공개 실종 소식의 `함께 찾기` 카드용. 비로그인 접근 가능(`@PublicEndPoint` + `@AuthenticationUser(isRequired = false)`).

| 파라미터 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `postId` | path | UUID | ✓ | 실종 소식 id |

응답: `SucceededApiResponseBody<SearchGroupCtaResponse>`

```json
{
  "postId": "uuid",
  "groupId": "uuid",
  "joinPolicy": "OPEN",
  "status": "ACTIVE",
  "memberCount": 12,
  "teamCount": 2,
  "viewerAction": "JOIN_NOW"
}
```

`viewerAction` 값: `JOIN_NOW`(즉시 참여) · `REQUEST_JOIN`(승인 후 참여) · `ALREADY_JOINED` · `LOGIN_REQUIRED`(비로그인) · `UNAVAILABLE`(종료·삭제·권한 없음·차단).
**차단 사실을 구분할 수 있는 값이나 필드를 두지 않는다** — `isBlocked` 필드는 존재하지 않으며, 차단자는 아카이브·권한없음과 동일하게 `UNAVAILABLE` 을 받는다.
좌표·연락처는 담지 않는다. 그룹이 없는 legacy 글(`SEEN`/`FOUND` 로 등록되어 백필 대상이 아니었던 글)과 삭제된 글은 `404 NOT_FOUND_SEARCH_GROUP`.

### GET `/search-groups/{groupId}`

응답: `SucceededApiResponseBody<SearchGroupDetailResponse>`

```json
{
  "groupId": "uuid",
  "postId": "uuid",
  "postTitle": "말티즈를 찾습니다",
  "joinPolicy": "OPEN",
  "status": "ACTIVE",
  "archivedReason": null,
  "archivedAt": null,
  "role": "PARTICIPANT",
  "sources": ["DIRECT", "TEAM"],
  "memberCount": 12,
  "teamCount": 2,
  "myMembershipId": "uuid",
  "myMembershipStatus": "ACTIVE",
  "canManage": false,
  "canWrite": true
}
```

`role`: `OWNER` / `PARTICIPANT` / `NONE`. `sources`: `OWNER` / `DIRECT` / `TEAM` 의 부분집합(두 경로를 동시에 가질 수 있다).
비참여자·탈퇴자·차단자는 `403 SEARCH_GROUP_ACCESS_DENIED`, 실종 소식이 삭제됐으면 `404 NOT_FOUND_SEARCH_GROUP`.
**좌표·연락처·상세 설명은 담지 않는다** — 공개 `GET /post/{id}` 로 조회한다.

### PATCH `/search-groups/{groupId}/join-policy`

Request Body:

```json
{ "joinPolicy": "APPROVAL_REQUIRED" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `joinPolicy` | enum | ✓ | **대문자 `OPEN` 또는 `APPROVAL_REQUIRED` 만 허용**. 소문자·오타·누락은 본문 파싱 단계에서 걸려 `400 MISSING_PARAMETER` |

보호자만 가능(`403 SEARCH_GROUP_ACCESS_DENIED`), 종료된 그룹은 `410 SEARCH_ALREADY_ENDED`.
현재 정책과 같은 값을 보내면 아무것도 바꾸지 않고 200 으로 끝난다(멱등).
**정책을 바꿔도 기존 `ACTIVE` 멤버는 유지된다.** `APPROVAL_REQUIRED` → `OPEN` 전환 시 기존 `PENDING` 신청은 자동 승인되지 않고 그대로 남는다.
변경 사실은 그룹 활동 기록(`SearchGroupEventType.JOIN_POLICY_CHANGED`)에만 남긴다. **정책 변경 알림은 발행하지 않는다** — 설계 §8.3 이 요구하는 것은 활동 기록뿐이다.

응답: `SucceededApiResponseBody<Void>` (본문 `data: null`)

### POST `/search-groups/{groupId}/end`

Request Body 없음. 보호자만 가능.
그룹을 `ARCHIVED(FOUND)` 로 만들고 실종 소식을 `FOUND` 로 전환한 뒤 유효 멤버(보호자 제외)에게 `SEARCH_ENDED` 알림을 **1인당 1건** 보낸다.
이미 종료된 그룹을 다시 종료하려 하면 `410 SEARCH_ALREADY_ENDED`, 동시 호출 경합에서 조건부 UPDATE 가 0행이 된 쪽은 알림·감사를 만들지 않고 현재 상태를 200 으로 돌려준다.
**재활성화 API 는 없다.**

응답: `SucceededApiResponseBody<SearchGroupDetailResponse>`

### GET `/search-groups/{groupId}/events`

| 파라미터 | 위치 | 타입 | 필수 | 기본 | 설명 |
|---|---|---|---|---|---|
| `size` | query | int | - | 20 | 최대 100 |
| `offset` | query | int | - | 0 | |

정렬 `createdAt DESC, id DESC`. 유효 멤버만 조회 가능(`requireRead`).

응답: `SucceededApiResponseBody<List<SearchGroupEventResponse>>`

```json
[
  {
    "id": "uuid",
    "type": "MEMBER_APPROVED",
    "actorId": "uuid",
    "targetId": "uuid",
    "detail": "PENDING -> ACTIVE",
    "createdAt": "2026-07-25T15:00:00"
  }
]
```

`type` 값: `GROUP_OPENED`, `JOIN_POLICY_CHANGED`, `MEMBER_JOINED`, `MEMBER_REQUESTED`, `MEMBER_APPROVED`, `MEMBER_REJECTED`, `MEMBER_LEFT`, `MEMBER_REMOVED`, `USER_BLOCKED`, `USER_UNBLOCKED`, `TEAM_SUPPORT_REQUESTED`, `TEAM_SUPPORT_ACCEPTED`, `TEAM_SUPPORT_DECLINED`, `TEAM_SUPPORT_WITHDRAWN`, `TEAM_SUPPORT_REMOVED`, `SEARCH_ENDED`, `GROUP_ARCHIVED_BY_POST_DELETE`.
`detail` 에는 상태 전이 요약만 담는다 — 메시지 본문·좌표·연락처·차단 사유 저장 금지.

### POST `/search-groups/{groupId}/memberships`

Request Body 없음(표시 이름은 passport 에서 꺼내 행에 비정규화 저장).

- `joinPolicy=OPEN` → 즉시 `ACTIVE`, 보호자에게 `GROUP_MEMBER_JOINED` 알림.
- `joinPolicy=APPROVAL_REQUIRED` → `PENDING`, 보호자에게 `GROUP_JOIN_REQUESTED` 알림.
- 탈퇴(`LEFT`)·거절(`REJECTED`)·내보내기(`REMOVED`) 상태에서 다시 호출하면 **같은 행의 status 전이**로 재가입된다(새 행을 만들지 않는다).
- **이미 `ACTIVE` 인 직접 참여자가 다시 호출하면 멱등 200** 이다. 같은 멤버십을 그대로 돌려주고 알림을 다시 보내지 않는다(설계 §8.1 "중복 클릭과 재시도는 같은 활성 멤버십 하나만").
- `APPROVAL_REQUIRED` 에서 이미 `PENDING` 인 사용자가 다시 호출해도 멱등 200 이다. 다만 그 사이 정책이 `OPEN` 으로 바뀌었다면 `ACTIVE` 로 전이한다(설계 §8.3).
- 보호자 자신, 그리고 팀 경유로만 참여 중인 사용자(직접 멤버십 행이 없음)는 `409 SEARCH_GROUP_STATE_CONFLICT`.
- 차단자·종료된 그룹·삭제된 글은 각각 `403 SEARCH_GROUP_ACCESS_DENIED` / `410 SEARCH_ALREADY_ENDED` / `404 NOT_FOUND_SEARCH_GROUP`.

응답: `SucceededApiResponseBody<JoinSearchGroupResponse>`

```json
{
  "membership": {
    "membershipId": "uuid",
    "groupId": "uuid",
    "userId": "uuid",
    "userName": "홍길동",
    "status": "ACTIVE",
    "requestedAt": "2026-07-25T15:00:00",
    "joinedAt": "2026-07-25T15:00:00",
    "decidedAt": null
  },
  "joinPolicy": "OPEN",
  "approvalPending": false
}
```

`approvalPending` 이 true 면 프론트는 "확인할 요청 대기" 문구를 띄운다.

### GET `/search-groups/{groupId}/memberships`

| 파라미터 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `status` | query | enum? | - | `PENDING`/`ACTIVE`/`REJECTED`/`LEFT`/`REMOVED`. **보호자에게만 적용된다** |

페이징 파라미터가 없다. 보호자는 전체(또는 요청한 `status`) 목록을, 그 외 유효 멤버는 `ACTIVE` 목록만 본다 — 비보호자가 `status` 를 보내도 무시된다.
유효 멤버가 아니면 `403 SEARCH_GROUP_ACCESS_DENIED`.

응답: `SucceededApiResponseBody<List<SearchGroupMembershipResponse>>`

`SearchGroupMembershipResponse` 필드: `membershipId`, `groupId`, `userId`, `userName`, `status`, `requestedAt`, `joinedAt`, `decidedAt`.
차단 여부 필드는 존재하지 않는다(설계 §16.5).

### POST `.../memberships/{membershipId}/approve` · `/reject` · `/remove`

셋 다 Request Body 없음, 보호자 전용, 응답은 `SucceededApiResponseBody<SearchGroupMembershipResponse>`.

| 경로 | 허용 이전 상태 | 이후 상태 | 알림 |
|---|---|---|---|
| `/approve` | `PENDING` | `ACTIVE` | 신청자에게 `GROUP_JOIN_APPROVED` |
| `/reject` | `PENDING` | `REJECTED` | 신청자에게 `GROUP_JOIN_REJECTED` |
| `/remove` | `ACTIVE` | `REMOVED` | 대상자에게 `GROUP_MEMBER_REMOVED` |

- **다른 그룹의 `membershipId` 는 `404 NOT_FOUND_SEARCH_GROUP_MEMBERSHIP`** — `(groupId, membershipId)` 전체 tuple 로 조회한다.
- **이미 목표 상태면 멱등 200** 이다(중복 클릭·재시도 안전). 알림을 다시 보내지 않는다.
- 목표로 갈 수 없는 다른 상태면 `409 SEARCH_GROUP_STATE_CONFLICT`.
- `REMOVED` 사용자는 스스로 재가입할 수 있다. 재가입을 막으려면 차단(`POST /blocks`)을 쓴다.

### DELETE `/search-groups/{groupId}/memberships/me`

본인의 직접 멤버십(`ACTIVE` 또는 `PENDING`)을 `LEFT` 로 만든다. 팀 경유 권한은 그대로 남는다(팀에서 나가거나 팀 지원이 끝나야 사라진다).

- 직접 멤버십 행이 없으면 `404 NOT_FOUND_SEARCH_GROUP_MEMBERSHIP`.
- **보호자가 호출하면 `404 NOT_FOUND_SEARCH_GROUP_MEMBERSHIP`** — 설계 §7 "개인 참여 종료 / 보호자: 해당 없음". 보호자에게는 애초에 멤버십 행이 없다.
- 이미 `LEFT` 면 멱등 200.
- 탈퇴자·차단자는 `403 SEARCH_GROUP_ACCESS_DENIED`(유효 멤버가 아니므로 읽기 단계에서 막힌다).

응답: `SucceededApiResponseBody<SearchGroupMembershipResponse>` (`status = "LEFT"` 인 멤버십)

### GET `/search-groups/{groupId}/blocks`

**보호자 전용.** 다른 역할은 `403 SEARCH_GROUP_ACCESS_DENIED`. 보관된 그룹에서도 보호자는 기록을 볼 수 있다.
`reason` 이 노출되는 유일한 엔드포인트다. 페이징 파라미터가 없다.

응답: `SucceededApiResponseBody<List<SearchGroupBlockResponse>>`

```json
[
  {
    "blockId": "uuid",
    "groupId": "uuid",
    "userId": "uuid",
    "userName": "홍길동",
    "reason": "반복적인 허위 제보",
    "blockedAt": "2026-07-25T15:00:00",
    "unblockedAt": null
  }
]
```

`userName` 은 멤버십 행에 비정규화 저장된 표시 이름이며, 이름이 없던 사용자는 `null` 이다.

### POST `/search-groups/{groupId}/blocks`

Request Body:

```json
{ "targetUserId": "uuid", "reason": "반복적인 허위 제보" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `targetUserId` | UUID | ✓ | 차단 대상. **필드명은 `userId` 가 아니라 `targetUserId` 다** |
| `reason` | string? | - | 최대 500자(초과분은 잘라 저장). **운영 감사용이며 참여자에게 노출되지 않는다** |

차단하면 직접 멤버십은 `REMOVED` 가 되고 팀 경유 파생 권한도 즉시 무효화된다.
대상자에게 `GROUP_MEMBER_BLOCKED` 알림을 보내되 **본문에 차단 사유를 담지 않는다.**
이미 활성 차단이 있으면 멱등 200(같은 차단 행을 돌려준다).
**보호자 자신은 차단 대상이 될 수 없다 → `409 SEARCH_GROUP_STATE_CONFLICT`.**

응답: `SucceededApiResponseBody<SearchGroupBlockResponse>`

### DELETE `/search-groups/{groupId}/blocks/{userId}`

`unblocked_at` 을 채워 차단을 해제한다. 멤버십은 자동 복구되지 않으며 본인이 다시 참여하거나 신청해야 한다.
차단 행이 없거나 이미 해제돼 있으면 아무것도 하지 않고 200 으로 끝난다(멱등).

응답: `SucceededApiResponseBody<Void>` (본문 `data: null`)

### POST `/search-groups/{groupId}/team-supports`

Request Body:

```json
{ "teamId": "uuid", "message": "저희 팀이 야간 수색을 도울 수 있어요" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `teamId` | UUID | ✓ | 지원할 팀 |
| `message` | string? | - | 200자 이하. **저장하지 않고 알림 본문에만 쓴다**. 초과 시 `400 INVALID_COLLABORATION_INPUT` |

**초기 상태와 방향은 요청 본문에 두지 않는다** — 서버가 `(그룹 보호자, 팀 팀장)` tuple 로 계산한다. 클라이언트가 방향을 보내면 승인 단계를 건너뛰는 IDOR 가 된다.

| 호출자 | 생성 상태 | 알림 |
|---|---|---|
| 보호자 | `PENDING_TEAM_APPROVAL` | 해당 팀의 팀장에게 `TEAM_SUPPORT_REQUESTED` |
| 팀장 | `PENDING_GROUP_APPROVAL` | 보호자에게 `TEAM_SUPPORT_REQUESTED` |
| 보호자이면서 그 팀의 팀장 | 즉시 `ACTIVE` | 팀원(본인 제외)에게 `TEAM_SUPPORT_ACCEPTED` |
| 대상 팀의 `ACTIVE` 팀원(비팀장) | - | `403 TEAM_LEADER_REQUIRED` |
| 그 외 | - | `403 SEARCH_GROUP_ACCESS_DENIED` |

`UNIQUE(group_id, team_id)` 때문에 **양쪽이 동시에 눌러도 연결은 1행**이다. 종료된 그룹은 `410 SEARCH_ALREADY_ENDED`, 없거나 보관된 팀은 각각 `404 NOT_FOUND_TEAM` / `409 SEARCH_GROUP_STATE_CONFLICT`.

응답: `SucceededApiResponseBody<SearchGroupTeamSupportResponse>`

```json
{
  "id": "uuid",
  "groupId": "uuid",
  "teamId": "uuid",
  "teamName": "강남 수색대",
  "status": "PENDING_TEAM_APPROVAL",
  "requestedBy": "uuid",
  "requestedAt": "2026-07-25T15:00:00",
  "decidedAt": null,
  "activatedAt": null
}
```

### GET `/search-groups/{groupId}/team-supports`

파라미터 없음. 보호자는 이 그룹의 모든 연결을, 팀장은 **자기 팀의 연결만** 본다. 둘 다 아니면 `403 SEARCH_GROUP_ACCESS_DENIED`.

응답: `SucceededApiResponseBody<List<SearchGroupTeamSupportResponse>>`

### POST `.../team-supports/{supportId}/accept` · `/decline`

Request Body 없음. **수락·거절 권한은 현재 상태가 결정한다** — 요청을 낸 쪽은 자기 요청을 스스로 수락할 수 없다.

| 현재 상태 | 처리 주체 | accept 결과 | decline 결과 |
|---|---|---|---|
| `PENDING_GROUP_APPROVAL` | 보호자 | `ACTIVE` | `DECLINED` |
| `PENDING_TEAM_APPROVAL` | 팀장 | `ACTIVE` | `DECLINED` |

- 잘못된 주체 → `403 SEARCH_GROUP_ACCESS_DENIED`. 단 **해당 팀의 `ACTIVE` 팀원(비팀장)이 시도하면 `403 TEAM_LEADER_REQUIRED`**.
- 요청자 본인이 자기 요청을 수락/거절 → `409 SEARCH_GROUP_STATE_CONFLICT`.
- 다른 그룹의 `supportId` → `404 NOT_FOUND_TEAM_SUPPORT`.
- **이미 목표 상태면 멱등 200** — 동시 수락 경합에서 진 쪽도 `ACTIVE` 를 그대로 받고, 알림은 1건만 남는다.
- `ACTIVE` 전환 시 **팀 전원에게 파생 권한이 즉시 생기고**, 상대편과 팀원에게 `TEAM_SUPPORT_ACCEPTED` 알림이 1인당 1건 간다. `activated_at` 이 채워진다.

응답: `SucceededApiResponseBody<SearchGroupTeamSupportResponse>`

### DELETE `/search-groups/{groupId}/team-supports/{supportId}`

`ACTIVE` 지원을 종료한다. 사용자 문구는 **`우리 팀의 지원 종료`**. 대기 중인 요청은 이 API 로 취소하지 않고 상대의 `decline` 으로 정리한다.

| 호출자 | 이후 상태 | 알림 |
|---|---|---|
| 팀장 | `WITHDRAWN` | 보호자 + 팀원에게 `TEAM_SUPPORT_ENDED` |
| 보호자 | `REMOVED` | 팀원에게 `TEAM_SUPPORT_ENDED` |
| 해당 팀의 `ACTIVE` 팀원(비팀장) | - | `403 TEAM_LEADER_REQUIRED` |
| 그 외 | - | `403 SEARCH_GROUP_ACCESS_DENIED` |

종료 즉시 해당 팀원들의 파생 권한이 사라진다. 이미 종료된 연결은 멱등 200, 그 밖의 상태는 `409 SEARCH_GROUP_STATE_CONFLICT`.

응답: `SucceededApiResponseBody<SearchGroupTeamSupportResponse>`

---
````

Run(검증):
```bash
cd /Users/park/Desktop/project/prd/find-my-pet && \
grep -cE '^\| (GET|POST|PATCH|DELETE) \| `/(search-groups|posts/\{postId\}/search-group)' api-spec.md && \
grep -c "SearchGroupMemberResponse\|SearchGroupTeamResponse" api-spec.md
```
Expected: 첫 숫자 `19`(Search Group 경로 표 19행), 두 번째 숫자 `0`(폐기된 DTO 이름이 남아 있지 않다). `grep -c` 가 0 건일 때 종료코드 1 을 내므로 두 번째 명령은 `|| true` 없이도 `0` 을 출력한 뒤 실패로 보일 수 있다 — **출력 숫자만 본다.**

- [ ] **Step 6: `api-spec.md` 에 `## Team` 과 `## Search Hub` 섹션 추가**

Step 5 블록 바로 뒤(`## Bookmark` 앞)에 이어 붙인다. 엔드포인트 #21~#33 을 다룬다.

````markdown
## Team (수색 팀)

여러 실종 사건을 함께 도는 사용자 모임. 팀은 여러 수색그룹을 동시에 지원할 수 있고, 팀원 변경은 파생 권한에 즉시 반영된다.

| Method | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/teams` | 팀 생성 (생성자가 팀장) | USER |
| GET | `/teams` | 팀 목록·검색 (공개) | 선택 (`isRequired=false`) |
| GET | `/teams/{teamId}` | 팀 상세 (공개) | 선택 (`isRequired=false`) |
| PATCH | `/teams/{teamId}` | 팀 이름·소개 수정 (팀장) | USER |
| POST | `/teams/{teamId}/transfer-leadership` | 팀장 이전 (팀장) | USER |
| POST | `/teams/{teamId}/archive` | 팀 보관 (팀장) | USER |
| POST | `/teams/{teamId}/memberships` | 팀 합류 신청 | USER |
| GET | `/teams/{teamId}/memberships` | 팀원 목록 | USER |
| POST | `/teams/{teamId}/memberships/{membershipId}/approve` | 합류 승인 (팀장) | USER |
| POST | `/teams/{teamId}/memberships/{membershipId}/reject` | 합류 거절 (팀장) | USER |
| POST | `/teams/{teamId}/memberships/{membershipId}/remove` | 팀원 내보내기 (팀장) | USER |
| DELETE | `/teams/{teamId}/memberships/me` | 팀 나가기 | USER |

### POST `/teams`

Request Body:

```json
{ "name": "강남 수색대", "description": "역삼·논현 일대 야간 수색" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | ✓ | trim 후 **2~30자**. 벗어나면 `400 INVALID_COLLABORATION_INPUT` |
| `description` | string? | - | trim 후 200자 이하. 빈 문자열은 `null` 로 저장 |

생성자가 `LEADER` / `ACTIVE` 팀원으로 함께 등록된다. 팀 이름은 중복을 허용한다(식별자는 `id`).

응답: `SucceededApiResponseBody<TeamResponse>`

```json
{
  "id": "uuid",
  "name": "강남 수색대",
  "description": "역삼·논현 일대 야간 수색",
  "status": "ACTIVE",
  "activeMemberCount": 1,
  "pendingMemberCount": 0,
  "leaderName": "홍길동",
  "viewerRole": "LEADER",
  "viewerStatus": "ACTIVE",
  "createdAt": "2026-07-25T15:00:00"
}
```

`pendingMemberCount` 는 **팀장에게만 실제 값**이 내려간다(그 외에는 항상 `0`).
`viewerRole`/`viewerStatus` 는 조회자의 멤버십이고, 비로그인·비소속이면 `null`.

### GET `/teams`

| 파라미터 | 위치 | 타입 | 필수 | 기본 | 설명 |
|---|---|---|---|---|---|
| `q` | query | string? | - | - | 팀 이름 부분 일치 |
| `pageSize` | query | long | - | 20 | 1~50 으로 보정 |
| `pageOffset` | query | long | - | 0 | |

`status = ACTIVE` 이고 soft-delete 되지 않은 팀만 이름순으로 반환한다. 비로그인 조회 가능하며 이 경우 `viewerRole`/`viewerStatus` 는 `null`.
공개 목록이어도 **합류는 팀장 승인이 필요**하므로 노출 자체는 안전하다.

응답: `PaginatedApiResponseBody<TeamSummaryResponse>` — `data.contents` / `data.hasNextPage` / `data.totalCount`

```json
{
  "id": "uuid",
  "name": "강남 수색대",
  "description": "역삼·논현 일대 야간 수색",
  "status": "ACTIVE",
  "activeMemberCount": 8,
  "viewerRole": null,
  "viewerStatus": null,
  "createdAt": "2026-07-25T15:00:00"
}
```

### GET `/teams/{teamId}`

응답: `SucceededApiResponseBody<TeamResponse>`. 로그인 사용자면 `viewerRole`/`viewerStatus` 가 채워진다.
존재하지 않거나 soft-delete 됐거나 **`ARCHIVED` 면 `404 NOT_FOUND_TEAM`** — 보관된 팀은 공개 조회에서 사라진다.

### PATCH `/teams/{teamId}`

Request Body: `{ "name": "...", "description": "..." }` (필드 규칙은 생성과 동일).
팀장만 가능 → 그 외 `403 TEAM_LEADER_REQUIRED`. 보관된 팀은 `409 SEARCH_GROUP_STATE_CONFLICT`.

응답: `SucceededApiResponseBody<TeamResponse>`

### POST `/teams/{teamId}/transfer-leadership`

Request Body:

```json
{ "targetMembershipId": "uuid" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `targetMembershipId` | UUID | ✓ | 팀장이 될 팀원의 **멤버십 id**. `userId` 가 아니다 |

대상은 같은 팀의 `ACTIVE` / `MEMBER` 여야 한다. 다른 팀의 멤버십 id 는 `404 NOT_FOUND_TEAM_MEMBERSHIP`, 자기 자신이거나 상태·역할이 맞지 않으면 `409 SEARCH_GROUP_STATE_CONFLICT`.
한 트랜잭션 안에서 **UPDATE 두 번**(현 팀장 강등 → 대상 승격)으로 처리한다. `uq_tm_single_active_leader` 가 행 단위로 검사되므로 `CASE WHEN` 단일 UPDATE 는 쓰지 않는다.
양쪽에 `TEAM_LEADERSHIP_TRANSFERRED` 알림이 간다.

응답: `SucceededApiResponseBody<TeamResponse>`

### POST `/teams/{teamId}/archive`

Request Body 없음. **팀장 전용** → 그 외 `403 TEAM_LEADER_REQUIRED`.
설계 §6.4 "후임에게 이전 **또는 팀을 보관 처리**" 를 구현한 경로다. 계약 §18 표에는 없던 추가 엔드포인트다.

보관하면 한 트랜잭션 안에서:
1. 팀 `status` 를 `ACTIVE → ARCHIVED` 로 조건부 전이한다.
2. 그 팀의 모든 `ACTIVE` `search_group_team` 을 `WITHDRAWN` 으로 전이한다 — **파생 권한을 회수한다.**
3. 팀원 전원에게 알림을 보낸다.
4. 그 뒤 마지막 팀장도 `DELETE /teams/{teamId}/memberships/me` 로 나갈 수 있다.

이미 `ARCHIVED` 면 멱등 200. 보관된 팀은 `GET /teams` 목록과 `GET /teams/{teamId}` 상세에서 사라지고, 새 합류 신청·지원 연결도 받지 않는다.

응답: `SucceededApiResponseBody<TeamResponse>` (`status = "ARCHIVED"`)

### POST `/teams/{teamId}/memberships`

Request Body 없음. 항상 `PENDING` 으로 생성되고 팀장에게 `TEAM_MEMBER_REQUESTED` 알림이 간다(팀에는 `OPEN` 정책이 없다).
`LEFT`/`REJECTED`/`REMOVED` 상태였으면 같은 행이 `PENDING` 으로 재전이되고 `role` 은 `MEMBER` 로 돌아간다.
**이미 `PENDING` 또는 `ACTIVE` 면 멱등 200** — 같은 멤버십을 돌려주고 알림을 다시 보내지 않는다.
보관된 팀은 `409 SEARCH_GROUP_STATE_CONFLICT`, 없는 팀은 `404 NOT_FOUND_TEAM`.

응답: `SucceededApiResponseBody<TeamMembershipResponse>`

```json
{
  "id": "uuid",
  "teamId": "uuid",
  "userId": "uuid",
  "userName": "홍길동",
  "role": "MEMBER",
  "status": "PENDING",
  "joinedAt": null,
  "requestedAt": "2026-07-25T15:00:00"
}
```

### GET `/teams/{teamId}/memberships`

파라미터 없음. 팀장은 `ACTIVE` + `PENDING` 을, 그 외 로그인 사용자는 `ACTIVE` 팀원만 본다. 없는 팀은 `404 NOT_FOUND_TEAM`.

응답: `SucceededApiResponseBody<List<TeamMembershipResponse>>`

### POST `.../memberships/{membershipId}/approve` · `/reject` · `/remove`

팀장 전용, Request Body 없음, 응답 `SucceededApiResponseBody<TeamMembershipResponse>`.

| 경로 | 허용 이전 상태 | 이후 상태 | 알림 |
|---|---|---|---|
| `/approve` | `PENDING` | `ACTIVE` | 신청자에게 `TEAM_MEMBER_APPROVED` |
| `/reject` | `PENDING` | `REJECTED` | 신청자에게 `TEAM_MEMBER_REJECTED` |
| `/remove` | `ACTIVE` | `REMOVED` (role 은 `MEMBER` 로 되돌림) | 대상자에게 `TEAM_MEMBER_REMOVED` |

- **다른 팀의 `membershipId` 는 `404 NOT_FOUND_TEAM_MEMBERSHIP`** — `(teamId, membershipId)` tuple 로 조회한다.
- **이미 목표 상태면 멱등 200.** 목표로 갈 수 없는 상태면 `409 SEARCH_GROUP_STATE_CONFLICT`.
- 팀장 아닌 사람이 호출하면 `403 TEAM_LEADER_REQUIRED`.
- `ACTIVE` → `REMOVED` 즉시 그 팀이 지원 중인 모든 그룹에 대한 파생 권한이 사라진다(팀원 목록을 그룹 멤버 테이블로 복사하지 않기 때문에 별도 동기화가 없다).

### DELETE `/teams/{teamId}/memberships/me`

본인 팀 멤버십을 `LEFT` 로 만들고 `role` 을 `MEMBER` 로 되돌린다. 이미 `LEFT` 면 멱등 200.
멤버십 행이 없으면 `404 NOT_FOUND_TEAM_MEMBERSHIP`.
**활성 팀장은 그냥 나갈 수 없다 → `409 TEAM_LEADER_CANNOT_LEAVE`.** 먼저 `POST /teams/{teamId}/transfer-leadership` 으로 팀장을 넘기거나, 후임이 없으면 `POST /teams/{teamId}/archive` 로 팀을 보관한다(설계 §6.4).

응답: `SucceededApiResponseBody<TeamMembershipResponse>` (`status = "LEFT"`)

---

## Search Hub (마이페이지 함께 찾기 허브)

| Method | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/me/search-hub` | 함께 찾는 반려동물 · 팀 · 확인할 요청 · 요약을 한 번에 | USER |

### GET `/me/search-hub`

파라미터 없음. 섹션별 상한 10건.

응답: `SucceededApiResponseBody<SearchHubResponse>`

```json
{
  "summary": {
    "activeSearchCount": 3,
    "unreadNotificationCount": 5,
    "pendingActionCount": 2,
    "pendingActionCountCapped": false
  },
  "animals": [
    {
      "groupId": "uuid",
      "postId": "uuid",
      "title": "말티즈를 찾습니다",
      "place": "서울 강남구 역삼동",
      "missingAt": "2026-07-20T09:00:00",
      "animalType": "DOG",
      "missingAnimalStatus": "SEARCHING",
      "joinPolicy": "OPEN",
      "isOwner": true,
      "thumbnail": "https://.../presigned.jpg",
      "unreadNotificationCount": 2,
      "postPath": "/lost/{postId}",
      "groupPath": "/lost/{postId}/group",
      "managePath": "/lost/{postId}/group?tab=members"
    }
  ],
  "animalsHasMore": false,
  "animalsMorePath": "/profile/search",
  "teams": [
    {
      "teamId": "uuid",
      "name": "강남 수색대",
      "role": "LEADER",
      "supportingSearchCount": 2,
      "teamPath": "/teams/{teamId}",
      "managePath": "/teams/{teamId}?tab=members"
    }
  ],
  "teamsHasMore": false,
  "teamsMorePath": "/teams",
  "pendingActions": [
    {
      "kind": "GROUP_JOIN_REQUEST",
      "referenceId": "uuid",
      "groupId": "uuid",
      "postId": "uuid",
      "teamId": null,
      "title": "홍길동님이 말티즈를 찾습니다 함께 찾기에 참여를 신청했어요",
      "requestedAt": "2026-07-25T14:00:00",
      "approvePath": "/api/v1/search-groups/{groupId}/memberships/{referenceId}/approve",
      "rejectPath": "/api/v1/search-groups/{groupId}/memberships/{referenceId}/reject",
      "detailPath": "/lost/{postId}/group?tab=members"
    }
  ],
  "pendingActionsHasMore": false,
  "mapPath": "/profile/search/map",
  "mapAvailable": false
}
```

- `summary.unreadNotificationCount` 는 조회자가 접근 가능한 수색그룹에 붙은 **알림 미읽음 합계**이며, `animals[].unreadNotificationCount` 의 합과 항상 일치한다(설계 §10). **채팅 미읽음은 phase 3 범위라 포함되지 않는다.**
- `pendingActions[].kind`: `GROUP_JOIN_REQUEST`(내 그룹의 참여 신청) · `GROUP_TEAM_SUPPORT_OFFER`(팀의 지원 제안) · `TEAM_JOIN_REQUEST`(내 팀의 합류 신청) · `TEAM_SUPPORT_REQUEST`(보호자가 내 팀에 보낸 지원 요청).
- **출처는 알림이 아니라 PENDING 행이다.** 알림을 읽어도 요청은 사라지지 않고, 승인·거절해야 사라진다.
- `pendingActionCount` 는 도메인당 50건(최대 200) 스캔 범위에서 정확하며, 초과 시 `pendingActionCountCapped=true`.
- `mapAvailable` 은 phase 1 에서 항상 `false` 다. **응답에 좌표가 없다** — 통합 지도는 phase 2 의 별도 엔드포인트에서 제공한다.
- 카드에 `lat`/`lng`/`phoneNum`/`description` 필드는 존재하지 않는다(프로젝션 단계에서 컬럼을 선택하지 않는다).

---
````

Run(검증):
```bash
cd /Users/park/Desktop/project/prd/find-my-pet && \
grep -cE '^\| (GET|POST|PATCH|DELETE) \| `/(teams|me/search-hub)' api-spec.md && \
grep -c "teamId\": \"uuid\",\n" api-spec.md; \
grep -n "unreadNotificationCount\|/teams/{teamId}/archive\|targetMembershipId" api-spec.md | head -10
```
Expected: 첫 숫자 `13`(Team 12행 + Search Hub 1행). `unreadNotificationCount` 2건 이상, `/teams/{teamId}/archive` 2건 이상(표 + 상세), `targetMembershipId` 2건 이상이 잡힌다.

- [ ] **Step 7: `api-spec.md` NotificationType 표 확장**

`### NotificationType` 표의 기존 행 뒤에 이어 붙이고, 표 아래 저장 모델 문단을 갱신한다.

```markdown
| `GROUP_MEMBER_JOINED` | `OPEN` 그룹에 즉시 참여 | 보호자 |
| `GROUP_JOIN_REQUESTED` | `APPROVAL_REQUIRED` 그룹에 참여 신청 | 보호자 |
| `GROUP_JOIN_APPROVED` | 보호자가 신청 승인 | 신청자 |
| `GROUP_JOIN_REJECTED` | 보호자가 신청 거절 | 신청자 |
| `GROUP_MEMBER_REMOVED` | 보호자가 참여자 내보내기 | 대상 참여자 |
| `GROUP_MEMBER_BLOCKED` | 보호자가 사용자 차단 | 대상 사용자 (**차단 사유는 담지 않음**) |
| `TEAM_SUPPORT_REQUESTED` | 지원 연결 요청/제안 생성 | 상대편 (보호자 또는 해당 팀 팀장) |
| `TEAM_SUPPORT_ACCEPTED` | 지원 연결 `ACTIVE` 전환 | 상대편 + 해당 팀 팀원 |
| `TEAM_SUPPORT_DECLINED` | 지원 연결 거절 | 요청한 쪽 |
| `TEAM_SUPPORT_ENDED` | 지원 종료 (`WITHDRAWN`/`REMOVED`/팀 보관) | 보호자 + 해당 팀 팀원 |
| `TEAM_MEMBER_REQUESTED` | 팀 합류 신청 | 팀장 |
| `TEAM_MEMBER_APPROVED` | 팀장이 합류 승인 | 신청자 |
| `TEAM_MEMBER_REJECTED` | 팀장이 합류 거절 | 신청자 |
| `TEAM_MEMBER_REMOVED` | 팀장이 팀원 내보내기 | 대상 팀원 |
| `TEAM_LEADERSHIP_TRANSFERRED` | 팀장 이전 | 이전 팀장 + 새 팀장 |
| `SEARCH_ENDED` | 보호자가 수색 종료 / 실종 소식 삭제 | 유효 멤버 전원 (보호자 제외) |
| `JOIN_POLICY_CHANGED` | (선언만 — phase 1 에서 발행하지 않음) | - |
| `SIGHTING_CREATED` | (선언만 — phase 2 에서 발행) | - |
| `CHAT_MENTIONED` | (선언만 — phase 3 에서 발행) | - |
| `GROUP_SYSTEM_EVENT` | (선언만 — phase 2/3 에서 발행) | - |
```

표 아래 문단을 다음으로 교체:

```markdown
저장 모델: `notification (id, user_id, type, title, body, link, is_read, created_at, updated_at, deleted_at, actor_user_id, actor_name, post_id, group_id, team_id)`.
인덱스: `idx_noti_user_unread (user_id, is_read, created_at)`, `idx_noti_user_created (user_id, created_at)`, `idx_noti_user_group (user_id, group_id, created_at)`.

- 구조화 컬럼 5개(`actor_user_id`, `actor_name`, `post_id`, `group_id`, `team_id`)는 `V12` 에서 추가됐고 기존 행은 전부 `NULL` 이다(하위 호환). 프론트는 `link` 대신 `postId`/`groupId`/`teamId` 로 목적 화면을 조립할 수 있다.
- `NotificationType` 은 phase 2/3 용 값까지 **phase 1 에서 전부 선언**한다(신규 상수 20개). 롤링 배포 중 구버전 replica 가 모르는 문자열을 읽으면 알림 목록 전체가 500 이 되기 때문이다. 그중 **phase 1 에 실제로 발행되는 것은 16종**이고 나머지 4종(`JOIN_POLICY_CHANGED`, `SIGHTING_CREATED`, `CHAT_MENTIONED`, `GROUP_SYSTEM_EVENT`)은 선언만 되어 있다.
- **참여 정책 변경은 알림을 만들지 않는다.** 설계 §8.3 이 요구하는 것은 그룹 활동 기록뿐이라 `SearchGroupEventType.JOIN_POLICY_CHANGED` 감사 이벤트만 남는다. 같은 이름의 `NotificationType` 상수는 롤링 배포 안전을 위해 선언만 유지한다.
- 알림 `title`/`body` 에는 **좌표·전화번호·차단 사유·채팅 본문을 넣지 않는다**(설계 §9, §16.5).
- 목록 정렬은 `createdAt DESC, id DESC` — `created_at` 동일 초 다건에서 중복/누락이 나던 문제를 tiebreaker 로 고정했다.
- 같은 사용자가 여러 경로(직접+팀)로 대상이 되어도 알림은 **1건**으로 중복 제거된다. 행위자 본인에게는 보내지 않는다.
- `GET /me/search-hub` 의 미읽음 수는 `group_id` 기준 집계이며 `idx_noti_user_group` 을 쓴다.
```

Run(검증):
```bash
cd /Users/park/Desktop/project/prd/find-my-pet && \
grep -c "JOIN_POLICY_CHANGED" api-spec.md && \
grep -n "유효 멤버 전원에게 .JOIN_POLICY_CHANGED" api-spec.md | head -3
```
Expected: 첫 숫자 `3` (NotificationType 표의 "선언만" 행 1 + 저장 모델 문단 1 + Search Group `events` 의 `SearchGroupEventType` 나열 1). 두 번째 명령은 **아무것도 출력하지 않는다** — "유효 멤버 전원에게 JOIN_POLICY_CHANGED 알림" 문장이 남아 있으면 안 된다.

- [ ] **Step 8: `api-spec.md` 에 `설계 대비 변경` 표 추가**

`## Search Hub` 섹션 끝 `---` 뒤, `## Bookmark` 앞에 넣는다.

```markdown
## 설계 대비 변경 (FR-17)

설계 메모 `animal/docs/superpowers/specs/2026-07-25-fmp-search-collaboration-design.md` 는 **승인된 원본이라 수정하지 않는다.**
구현하면서 달라진 결정은 여기에 남기고, 실제 계약은 이 api-spec 을 따른다.

| # | 설계 원문 | 구현 결정 | 이유 |
|---|---|---|---|
| 1 | §18 API 목록에 마이페이지 집계 엔드포인트 없음 | `GET /me/search-hub` 신설 | §10 이 요구하는 "카드에서 목적 화면까지 한 번" 을 지키려면 화면당 호출 1회여야 한다. 4개 섹션을 각각 호출하면 첫 화면에서 4~7회 왕복이 생긴다 |
| 2 | §18 에 팀 탐색 API 없음 | `GET /teams` 공개 목록·검색 신설 | 팀 이름을 정확히 알아야만 합류할 수 있으면 팀 기능이 시작되지 않는다. 합류는 여전히 팀장 승인이 필요하므로 공개 노출이 안전하다 |
| 3 | §18 에 목록 조회 API 없음 | `GET .../memberships`, `GET .../blocks`, `GET .../team-supports`, `GET .../events`, `GET /teams/{id}/memberships` 신설 | 승인·거절 화면이 알림에만 의존하면 알림을 읽은 뒤 요청을 다시 찾을 수 없다. §10 "확인할 요청" 의 권위 있는 원본은 PENDING 행이다 |
| 4 | §18 에 팀원 거절 없음 | `POST /teams/{id}/memberships/{id}/reject` 신설 | 승인만 있으면 거절이 "무기한 방치" 로 표현된다. 신청자가 상태를 알 수 없다 |
| 5 | §9 알림 목록에 팀 멤버십 알림 없음 | `TEAM_MEMBER_REQUESTED/APPROVED/REJECTED/REMOVED`, `TEAM_LEADERSHIP_TRANSFERRED` 5종 추가 | §4.1·§10 이 팀 관리 화면을 요구하는데 알림이 없으면 팀장이 신청을 인지할 방법이 없다 |
| 6 | §13.1 "가입·승인·팀 지원·메시지 작성은 client request ID 로 재시도 안전" | **자연키 `UNIQUE` + 조건부 상태 전이**로 대체. `Idempotency-Key` 헤더와 dedup 테이블을 만들지 않음. 재시도는 409 가 아니라 **멱등 200** | phase 1 대상(가입·승인·지원 연결)은 전부 자연키를 갖는다: `(group_id,user_id)`, `(team_id,user_id)`, `(group_id,team_id)`. 클라이언트 생성 ID 가 실제로 필요한 것은 자연키가 없는 메시지 작성(phase 3)뿐이다 |
| 7 | §12.2 "다음 커서와 결과 잘림 여부 반환" | phase 1 목록 중 `GET /teams` 만 offset 페이지네이션(`PaginatedApiResponseBody`), 나머지 목록 4종은 **전체 반환**(`SucceededApiResponseBody<List<...>>`), `GET .../events` 만 `size`/`offset` | 응답 래퍼 `PaginatedApiResponseBody` 가 외부 컴파일 라이브러리(`org.woo:http:0.2.1`)의 `(contents, hasNextPage, totalCount)` 고정 형태라 cursor 필드를 넣을 수 없다. 그룹당 멤버·지원 팀 수는 상한이 작아 전체 반환이 더 단순하다. cursor 는 지도·채팅이 들어오는 phase 2/3 에서 로컬 페이지 타입으로 도입한다 |
| 8 | §15 오류 표 | legacy `NOT_FOUND_*`(400)와 공존하는 404/409/410 을 **의도적 불일치로 문서화** | 기존 400 을 소급 변경하면 프론트엔드 계약이 깨진다. Error Codes 표 아래 주석 참조 |
| 9 | §7 "재활성화 API 없음" | `FOUND → SEARCHING` 되돌리기는 그룹이 `ARCHIVED` 면 `410`, 그룹 행이 아예 없는 legacy 글은 `OPEN` 그룹 신규 생성 허용 | 백필 대상이 아니었던 과거 `FOUND` 글까지 영구히 막으면 정상 사용을 방해한다 |
| 10 | §21 제외 목록에 `admin` 제품 역할 | `Passport.role` 의 `ROLE_ADMIN` 을 권한 판정에 **쓰지 않음**. 접근 판정 컴포넌트는 `userId: UUID` 만 받음 | 제품 역할은 보호자/팀장/팀원 셋뿐이다. 인증 역할이 새면 §2 제품 언어가 무너진다 |
| 11 | §6.4 "후임에게 이전 또는 팀을 보관 처리" 만 서술, §18 표에 API 없음 | `POST /teams/{teamId}/archive`(팀장 전용) 신설. 보관 시 `ACTIVE` 지원 연결을 `WITHDRAWN` 으로 전이하고 팀원에게 알린 뒤 팀장 탈퇴를 허용 | API 가 없으면 후임이 없는 마지막 팀장이 팀을 영원히 떠날 수 없다. `TeamStatus.ARCHIVED` 가 선언만 되고 전이 경로가 0개였다 |
| 12 | §8.3 "정책 변경은 그룹 활동 기록에 남긴다" | `JOIN_POLICY_CHANGED` **알림을 발행하지 않는다**. 감사 이벤트만 남긴다 | 설계가 요구한 것은 활동 기록뿐이다. 정책 변경마다 전원 알림을 쏘면 §9 "한 번만 받는다" 의 신호 대 잡음비가 나빠진다. enum 상수는 롤링 배포 안전을 위해 유지 |
| 13 | §16.6 "그룹·팀·채팅·마이페이지 경로는 sitemap 제외 + noindex" | **`find-my-pet-frontend` 범위**. 이 백엔드 계획서에서 다루지 않는다 | Next.js `robots`/`metadata` 설정이므로 백엔드 엔드포인트와 무관하다 |
| 14 | §3.7·§11 카카오톡 공유 | **백엔드 작업 없음.** 프런트엔드 `ShareButtons` 재사용 | 공유는 공개 `/lost/{postId}` 링크를 그대로 쓴다. 백엔드 의존물은 공개 CTA `GET /posts/{postId}/search-group` 하나뿐이다. 멤버십을 자동 부여하는 비밀 초대 링크는 만들지 않는다 |
| 15 | §23 "비관적 락으로 동시 수락 직렬화" 를 가정할 수 있는 서술 | **비관적 락을 쓰지 않는다.** 모든 전이는 `UPDATE ... WHERE id = :id AND <부모 id> = :parentId AND status = :expected` | 레포에 `@Lock`/`FOR UPDATE` 선례가 0개이고, 조건부 UPDATE 만으로 "영향 행 0 = 이미 다른 상태" 가 명확히 판정된다. 부모 id 를 WHERE 에 함께 넣어 IDOR 도 같은 쿼리에서 막는다 |

---
```

Run(검증): `grep -c "^| 1[0-5] |" /Users/park/Desktop/project/prd/find-my-pet/api-spec.md`
Expected: `6` — 10~15번 행이 모두 들어갔다(1~9번은 한 자리라 이 패턴에 안 잡힌다).

- [ ] **Step 9: `api-spec.md` 변경 이력에 행 추가**

`## 변경 이력` 표 마지막(2026-07-04 행 뒤)에 붙인다.

```markdown
| 2026-07-25 | **함께 찾기(FR-17) phase 1 백엔드** — Flyway `V12`(`search_group`/`search_group_member`/`search_group_user_block`/`team`/`team_member`/`search_group_team`/`search_group_event` + `notification` 구조화 컬럼 5개 + SEARCHING 백필). 신규 엔드포인트 32개(Search Group 19 / Team 12 / Search Hub 1) + 기존 `POST /post` 에 `joinPolicy` 파라미터 추가 = 총 33개. ErrorCode 11개 추가 — legacy `NOT_FOUND_*`(400)와 달리 **404/409/410 을 의도적으로 사용**. `NotificationType` 상수 20개 추가(phase 1 발행 16 + 선언만 4, 롤링 배포 중 미지의 enum 500 방지). 재시도·중복 클릭은 409 가 아니라 **멱등 200**(자연키 UNIQUE + 조건부 전이). `HttpMessageNotReadableException`/`MissingRequestHeaderException` 400 `MISSING_PARAMETER` 매핑 추가(기존 500 오매핑). 알림 정렬 tiebreaker `id DESC`. 권한 행렬(6역할×14행위)·IDOR·동시성·개인정보 IT 신설 + CI 백엔드 테스트 job 신설. **프론트엔드 미구현 — 사용자 노출 없음** |
```

Run(검증): `grep -n "2026-07-25" /Users/park/Desktop/project/prd/find-my-pet/api-spec.md`
Expected: 변경 이력 표에 1건이 잡힌다.

- [ ] **Step 10: 문서 ↔ 코드 대조 검증**

Step 1 의 출력과 Step 4~9 로 완성된 문서를 기계적으로 대조한다.

Run:
```bash
cd /Users/park/Desktop/project && \
echo "=== 코드에만 있고 문서에 없는 ErrorCode ===" && \
for c in $(grep -oE '^\s{4}[A-Z_]+\(' animal/src/main/kotlin/com/park/animal/common/http/error/ErrorCode.kt | tr -d ' ('); do \
  grep -q "\`$c\`" prd/find-my-pet/api-spec.md || echo "MISSING_IN_DOC: $c"; done && \
echo "=== 코드에만 있고 문서에 없는 NotificationType ===" && \
for t in $(grep -oE '^\s{4}[A-Z_]+' animal/src/main/kotlin/com/park/animal/notification/entity/NotificationType.kt | tr -d ' '); do \
  grep -q "\`$t\`" prd/find-my-pet/api-spec.md || echo "MISSING_IN_DOC: $t"; done && \
echo "=== 문서화된 함께 찾기 경로 수 ===" && \
grep -cE '^\| (GET|POST|PATCH|PUT|DELETE) \| `/(search-groups|teams|posts/\{postId\}/search-group|me/search-hub)' prd/find-my-pet/api-spec.md && \
echo "=== 폐기된 DTO 이름 잔존 ===" && \
grep -n "SearchGroupMemberResponse\|SearchGroupTeamResponse\|TeamMemberResponse\|supportingSearchCount\": 0,\|\"myRole\"\|\"myStatus\"\|\"memberCount\": 1" prd/find-my-pet/api-spec.md; \
echo "=== 설계 메모 무변경 확인 ===" && \
cd animal && git status --porcelain docs/superpowers/specs/2026-07-25-fmp-search-collaboration-design.md
```

Expected:
- `MISSING_IN_DOC:` 줄이 **하나도 없다**. 나오면 그 코드/타입을 Step 4 또는 Step 7 표에 추가한다.
- 함께 찾기 경로 카운트 = **32** (계약 §8 의 31개 + R9 의 `POST /teams/{teamId}/archive`. 기존 `POST /post` 는 Post 섹션에 있으므로 이 패턴에 안 잡힌다 → 문서 총계는 33개).
- 폐기된 DTO 이름 grep 이 **아무것도 출력하지 않는다**. `SearchGroupMemberResponse`/`SearchGroupTeamResponse`/`TeamMemberResponse` 와 `myRole`/`myStatus`/`memberCount` 같은 초안 필드가 남아 있으면 Step 5·6 으로 돌아간다.
- `git status --porcelain` 출력이 **비어 있다** — 설계 메모는 수정하지 않았다. 출력이 있으면 `git checkout -- docs/superpowers/specs/2026-07-25-fmp-search-collaboration-design.md` 로 되돌린다.

- [ ] **Step 11: `POST /post` 의 `joinPolicy` 파라미터를 기존 Post 섹션에 반영**

`### POST /post` 의 필드 표에서 `customNickname` 행 **앞**에 삽입한다.

```markdown
| `joinPolicy` | enum | - | `OPEN` | 수색그룹 참여 정책. `OPEN`(자유롭게 참여) / `APPROVAL_REQUIRED`(승인 후 참여). **대문자만 허용**, 다른 값은 본문 파싱 단계에서 걸려 `400 MISSING_PARAMETER`. `missingAnimalStatus=SEARCHING` 일 때만 그룹이 만들어지고 이 값이 적용된다 (`SEEN`/`FOUND` 등록 시 그룹 미생성 → 값 무시). 등록 폼 기본값은 `OPEN` |
```

이어서 `### PATCH /post/renewal-status` 설명과 `### DELETE /post/{id}` 설명에 다음 문단을 각각 덧붙인다.

```markdown
> FR-17 연동: 상태 전환은 `SearchLifecycleService` 를 거친다. `→ FOUND` 는 수색그룹을 `ARCHIVED(FOUND)` 로 만들고 유효 멤버(보호자 제외)에게 `SEARCH_ENDED` 알림을 1인 1건 보낸다. `SEARCHING → SEEN` 은 그룹을 그대로 둔다. `SEEN → SEARCHING` 은 그룹이 없으면 `OPEN` 그룹을 새로 만들고, 이미 `ARCHIVED` 면 `410 SEARCH_ALREADY_ENDED` 로 막는다(재활성화 API 없음 — "새 실종 소식을 등록해 주세요").
```

```markdown
> FR-17 연동: 실종 소식 soft-delete 는 수색그룹을 `ARCHIVED(POST_DELETED)` 로 만든다. 삭제된 글의 그룹은 조회·참여·쓰기가 모두 `404 NOT_FOUND_SEARCH_GROUP` 이다.
```

Run(검증): `grep -n "joinPolicy\|SearchLifecycleService\|POST_DELETED" /Users/park/Desktop/project/prd/find-my-pet/api-spec.md | head -20`
Expected: `POST /post` 표 · `renewal-status` · `DELETE /post/{id}` 세 곳에 각각 반영돼 있고, Search Group 섹션의 `joinPolicy` 언급과 함께 잡힌다.

- [ ] **Step 12: `feature-truth.md` 갱신 여부 판단 — 결론: ✅ Shipped 로 올리지 않는다**

판단 근거(이 스텝에서 ✅ 승격을 하지 않는 이유):

1. **사용자에게 도달하지 않았다.** phase 1 은 백엔드 엔드포인트·스키마·알림뿐이고 `find-my-pet-fe` 에 `함께 찾기` 진입점·수색그룹 화면·팀 화면·허브 화면이 하나도 없다. 실제 사용자는 이 기능을 **쓸 방법이 없다**. `feature-truth.md` 규칙상 ✅ Shipped 는 "현재 출시·홍보 가능"이며, 마케팅 콘텐츠가 "함께 찾기 지원"이라고 주장하면 즉시 검증 불가능한 과장이 된다.
2. **✅ 로 올리면 drift 가 발생한다.** `brand-qc` 게이트가 ✅ 항목만 사실로 허용하는 구조라, 프론트가 없는 상태에서 ✅ 로 올리면 QC 를 통과한 허위 주장이 발행된다.
3. **API 는 계약이지 기능이 아니다.** 기존 ✅ 항목(`bookmark_toggle`, `notification_bell` 등)은 전부 프론트 UI 가 있는 항목이다. 같은 기준을 유지한다.

대신 `## 🚧 In Development` 섹션에 **한 줄만** 추가한다. 이 섹션은 "예고 명시 시에만 등장 허용"이므로 사실 왜곡이 없다.

```markdown
- **함께 찾기 (수색그룹 · 팀 · 마이페이지 허브)** (`search_collaboration`) — 백엔드 phase 1 완료(수색그룹·팀·승인·알림·허브 API), **프론트엔드 미착수라 사용자 노출 없음**. 콘텐츠 등장 시 "준비 중"/"곧 출시" 명시 필수. 통합 지도(phase 2)·그룹 채팅(phase 3)은 언급 금지. 출처: FR-17
```

또한 `### 금기 표현` 목록 끝에 한 줄 추가한다.

```markdown
- "친구·이웃과 함께 찾기", "수색팀 모집", "실시간 수색 지도" — FR-17 은 백엔드만 존재하고 프론트엔드가 없다. 프론트 출시 전까지 현재형으로 쓰지 않는다
```

`prd_sources` 의 `last_synced_at` 은 `2026-07-25` 로 갱신한다(requirements.md · api-spec.md 두 항목 모두).

Run(검증):
```bash
cd /Users/park/Desktop/project/marketing && \
grep -n "search_collaboration\|함께 찾기\|2026-07-25" services/find-my-pet/feature-truth.md && \
grep -c "search_collaboration" services/find-my-pet/feature-truth.md && \
awk '/## ✅ Shipped/,/## 🚧 In Development/' services/find-my-pet/feature-truth.md | grep -c "함께 찾기"
```

Expected: `search_collaboration` 은 1회(In Development 안에서만), **✅ Shipped 구간의 "함께 찾기" 카운트는 `0`**, `last_synced_at: 2026-07-25` 2건.

- [ ] **Step 13: 마케팅 레포 커밋 (prd 는 커밋 대상 아님)**

`prd/` 는 git 저장소가 아니므로 커밋하지 않는다. `marketing/` 만 커밋한다.

```bash
cd /Users/park/Desktop/project/marketing && \
git add services/find-my-pet/feature-truth.md && \
git commit -m "docs(fmp): 함께 찾기 FR-17 을 In Development 로 기록

- 백엔드 phase 1 완료(수색그룹/팀/승인/알림/허브 API) 이나 프론트엔드 미착수
- 사용자 도달 불가 -> Shipped 승격 금지. 현재형 홍보 금기 표현 추가
- prd_sources last_synced_at 2026-07-25"
```

`animal` 레포에는 이 태스크의 변경이 없다. 확인:

Run: `cd /Users/park/Desktop/project/animal && git status --porcelain`
Expected: 출력 없음(Task 11 커밋 이후 working tree clean). 출력이 있으면 Task 0~11 중 커밋되지 않은 변경이 남아 있는 것이므로 해당 태스크의 커밋 스텝으로 돌아간다.