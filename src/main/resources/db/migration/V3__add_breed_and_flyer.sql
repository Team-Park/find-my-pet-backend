-- 품종 마스터 (견종 + 묘종 + 기타)
CREATE TABLE breed
(
    id                VARCHAR(36) PRIMARY KEY,
    animal_type       VARCHAR(16) NOT NULL,                                          -- DOG / CAT / OTHER
    name_ko           VARCHAR(64) NOT NULL,
    name_en           VARCHAR(64)      DEFAULT NULL,
    size_category     VARCHAR(16) NOT NULL,                                          -- TINY/SMALL/MEDIUM/LARGE/GIANT
    base_speed_kmh    DOUBLE           DEFAULT NULL,                                 -- 고양이·기타는 NULL (공식 미사용)
    behavior_pattern  VARCHAR(16) NOT NULL,                                          -- HIDE/ROAM/RETURN
    explore_factor    DOUBLE      NOT NULL DEFAULT 1.0,
    created_at        TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_breed_name (animal_type, name_ko)
);

-- 전단지 위치 (개인 회수 관리용, private)
CREATE TABLE flyer_location
(
    id            VARCHAR(36) PRIMARY KEY,
    post_id       VARCHAR(36)  NOT NULL,
    posted_by     VARCHAR(36)  NOT NULL,                                             -- 작성자 userId (= post.author_id)
    lat           DOUBLE       NOT NULL,
    lng           DOUBLE       NOT NULL,
    note          VARCHAR(255)      DEFAULT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'POSTED',                            -- POSTED / COLLECTED
    posted_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    collected_at  TIMESTAMP         DEFAULT NULL,
    created_at    TIMESTAMP         DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at    TIMESTAMP         DEFAULT NULL,
    KEY idx_flyer_post (post_id),
    KEY idx_flyer_user (posted_by)
);

-- post 테이블 확장 (기존 데이터 보존)
ALTER TABLE post
    ADD COLUMN animal_type VARCHAR(16) DEFAULT NULL,
    ADD COLUMN breed_id    VARCHAR(36) DEFAULT NULL;

-- 기존 레코드 전부 DOG 로 backfill (실종 데이터가 현재 모두 개임)
UPDATE post SET animal_type = 'DOG' WHERE animal_type IS NULL;

-- NOT NULL 확정 + 위치 인덱스
ALTER TABLE post
    MODIFY COLUMN animal_type VARCHAR(16) NOT NULL,
    ADD CONSTRAINT fk_post_breed FOREIGN KEY (breed_id) REFERENCES breed (id) ON DELETE SET NULL,
    ADD KEY idx_post_location (lat, lng);
