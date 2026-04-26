-- 공공데이터 유기동물을 로컬에 mirror + 사용자 지역 구독.
-- 1시간 주기 sync 잡 (AbandonedAnimalSyncService) 가 data.go.kr v2 를 페이지 순회해
-- 신규/제외 desertion_no 를 diff. 신규는 INSERT + 매칭 구독자에게 알림 fanout.
-- 더 이상 응답에 없거나 process_state 가 종료(...)인 항목은 closed_at 마킹 (soft close).

CREATE TABLE abandoned_animal
(
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    desertion_no  VARCHAR(64)  NOT NULL UNIQUE,
    animal_type   VARCHAR(16)  NOT NULL,                 -- DOG / CAT / OTHER
    upr_cd        VARCHAR(16)       DEFAULT NULL,        -- 시도 코드
    org_cd        VARCHAR(16)       DEFAULT NULL,        -- 시군구 코드
    kind_full_nm  VARCHAR(128)      DEFAULT NULL,        -- "[개] 말티즈"
    popfile       VARCHAR(512)      DEFAULT NULL,        -- 대표 사진 (HTTPS)
    sex_cd        VARCHAR(8)        DEFAULT NULL,
    age           VARCHAR(64)       DEFAULT NULL,
    weight        VARCHAR(64)       DEFAULT NULL,
    special_mark  VARCHAR(512)      DEFAULT NULL,
    happen_place  VARCHAR(256)      DEFAULT NULL,
    happen_dt     VARCHAR(16)       DEFAULT NULL,        -- YYYYMMDD
    care_nm       VARCHAR(128)      DEFAULT NULL,
    care_tel      VARCHAR(64)       DEFAULT NULL,
    care_addr     VARCHAR(256)      DEFAULT NULL,
    process_state VARCHAR(32)       DEFAULT NULL,        -- 보호중 / 종료(반환) / 종료(입양) ...
    notice_no     VARCHAR(64)       DEFAULT NULL,
    notice_sdt    VARCHAR(16)       DEFAULT NULL,
    notice_edt    VARCHAR(16)       DEFAULT NULL,
    closed_at     TIMESTAMP         DEFAULT NULL,        -- 응답 미존재 or process_state 종료 시 마킹
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_aa_region_active (animal_type, upr_cd, org_cd, closed_at),
    KEY idx_aa_open (closed_at, happen_dt),               -- 진행중 항목 시간순 정렬
    KEY idx_aa_created (created_at)
);

CREATE TABLE abandoned_subscription
(
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id      VARCHAR(36)  NOT NULL,
    upr_cd       VARCHAR(16)  NOT NULL,                  -- 시도 (필수)
    org_cd       VARCHAR(16)       DEFAULT NULL,         -- 시군구 (NULL = 시도 전체)
    animal_type  VARCHAR(16)       DEFAULT NULL,         -- NULL = 전체 종
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   TIMESTAMP         DEFAULT NULL,
    UNIQUE KEY uq_subs_user_region (user_id, upr_cd, org_cd, animal_type),
    KEY idx_subs_region (upr_cd, org_cd, animal_type)    -- 신규 매칭 시 fanout 용
);
