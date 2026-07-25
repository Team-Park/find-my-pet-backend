-- 함께 찾기 (수색그룹 · 팀 · 지원 연결) — 설계 6, 17.
-- 신규 테이블 타임스탬프는 DATETIME(6): post(DATETIME(6))와 조인 정합 + TIMESTAMP 의 세션 timezone
-- 변환/초 정밀도 문제 회피. BaseEntity 매핑상 deleted_at 은 모든 테이블에 필수(V10 사고 재발 방지).
-- 멤버십/연결 테이블은 deleted_at 을 사용하지 않는다 — 생명주기는 status 전이로만 표현한다.
--
-- 이 파일은 CREATE TABLE 7개만 담는다. notification 컬럼 확장은 V13, 백필은 V14 로 분리했다:
-- MySQL 은 DDL 이 트랜잭션이 아니고 각 문장이 즉시 커밋되며, FlywayConfig 는 부팅마다
-- repair() 후 migrate() 를 돈다. 대용량 post 를 풀스캔하는 백필이 이 파일 안에 있었다면
-- 타임아웃 시 repair() 가 파일 전체를 재실행하다 이미 존재하는 CREATE TABLE search_group 에서
-- 죽고, 그 뒤로는 어떤 배포도 성공하지 못한다. fk_search_group_post 는 의도적으로 이 파일의
-- 첫 CREATE TABLE 안에 둔다 — post.id 와 collation 이 다르면 가능한 가장 이른 시점(첫 테이블
-- 생성)에 실패를 터뜨리기 위해서다.

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
