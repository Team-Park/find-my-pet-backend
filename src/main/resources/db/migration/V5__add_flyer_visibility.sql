-- 전단지 공개 공유 (FR-F13) 지원을 위한 visibility 컬럼.
-- 기본값 PRIVATE — 기존 레코드와 앞으로 등록되는 전단지 모두 기본은 본인만 조회.
ALTER TABLE flyer_location
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';

-- 공개로 전환된 전단지를 게시글 단위로 빨리 긁어오기 위한 인덱스
CREATE INDEX idx_flyer_public ON flyer_location (post_id, visibility, deleted_at);
