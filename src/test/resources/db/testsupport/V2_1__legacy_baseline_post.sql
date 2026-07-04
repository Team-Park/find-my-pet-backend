-- 테스트 전용 레거시 베이스라인.
-- 운영 DB 의 post/post_image 테이블은 Flyway 도입 이전(legacy)에 만들어져 V2 가 빈 파일이다.
-- fresh DB(테스트 컨테이너)에서는 V3(ALTER TABLE post ...)가 실행되기 전에 테이블이 있어야 하므로
-- V2 와 V3 사이(V2.1)에 운영 스키마와 동일한 형태로 생성한다. 운영 마이그레이션 체인에는 포함되지 않는다.
CREATE TABLE post (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    author_id             VARCHAR(36)  NOT NULL,
    author_name           VARCHAR(255) NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    phone_num             VARCHAR(64)  NOT NULL,
    time                  DATETIME(6)  NOT NULL,
    place                 VARCHAR(255) NOT NULL,
    gender                VARCHAR(32)  NOT NULL,
    gratuity              INT          NOT NULL,
    description           TEXT         NOT NULL,
    lat                   DOUBLE       NOT NULL,
    lng                   DOUBLE       NOT NULL,
    open_chat_url         VARCHAR(512) NULL,
    missing_animal_status VARCHAR(32)  NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    deleted_at            DATETIME(6)  NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE post_image (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    post_id    VARCHAR(36)  NOT NULL,
    image_url  VARCHAR(512) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    deleted_at DATETIME(6)  NULL,
    KEY idx_post_image_post (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
