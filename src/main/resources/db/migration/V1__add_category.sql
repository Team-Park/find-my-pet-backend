CREATE TABLE category
(
    id         VARCHAR(36) PRIMARY KEY,                                  -- 카테고리 고유 ID
    name       VARCHAR(255) NOT NULL,                                           -- 카테고리 이름
    parent_id  VARCHAR(36)       DEFAULT NULL,                                          -- 상위 카테고리 ID (계층 구조)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,                             -- 생성 시간
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, -- 수정 시간
    FOREIGN KEY (parent_id) REFERENCES category (id) ON DELETE SET NULL         -- 상위 카테고리 참조
);