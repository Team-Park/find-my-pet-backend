-- 목격 제보 (FR-F10) — 로그인 사용자 누구나 "여기서 봤어요" 제보 가능.
-- 지도에 핀 누적 → 이동 궤적 추정 + 보호자 알림.
CREATE TABLE sighting
(
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    post_id      VARCHAR(36)  NOT NULL,
    reporter_id  VARCHAR(36)  NOT NULL,
    reporter_name VARCHAR(64)      DEFAULT NULL,
    lat          DOUBLE       NOT NULL,
    lng          DOUBLE       NOT NULL,
    sighted_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note         VARCHAR(512)      DEFAULT NULL,
    photo_url    VARCHAR(512)      DEFAULT NULL,
    created_at   TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   TIMESTAMP        DEFAULT NULL,
    KEY idx_sighting_post (post_id, sighted_at),
    KEY idx_sighting_reporter (reporter_id)
);
