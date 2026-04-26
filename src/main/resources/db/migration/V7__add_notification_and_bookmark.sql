-- 인앱 알림 + 즐겨찾기 (FR-N1, FR-N2)
-- - notification: 사용자별 알림함 (sighting 등록 / 즐겨찾기 게시글 상태 변경 등)
-- - post_bookmark: 사용자가 관심 게시글 즐겨찾기

CREATE TABLE notification
(
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36)  NOT NULL,
    type        VARCHAR(64)  NOT NULL, -- SIGHTING_REGISTERED / BOOKMARK_STATUS_CHANGED 등
    title       VARCHAR(128) NOT NULL,
    body        VARCHAR(512)      DEFAULT NULL,
    link        VARCHAR(256)      DEFAULT NULL, -- 클릭 시 이동할 상대 경로 (예: /lost/{id})
    is_read     TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP         DEFAULT NULL,
    KEY idx_noti_user_unread (user_id, is_read, created_at),
    KEY idx_noti_user_created (user_id, created_at)
);

CREATE TABLE post_bookmark
(
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL,
    post_id     VARCHAR(36) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP        DEFAULT NULL,
    UNIQUE KEY uq_bookmark_user_post (user_id, post_id),
    KEY idx_bookmark_post (post_id),
    KEY idx_bookmark_user_created (user_id, created_at)
);
