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
