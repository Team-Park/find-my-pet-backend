-- 알림 구조화 컨텍스트 (설계 9). 기존 행은 전부 NULL 로 남아 하위 호환.
-- user_id 컬럼명은 유지한다 (파생 쿼리 4개 + markAllRead JPQL + V7 인덱스가 참조).
--
-- V12(테이블 생성)와 분리된 별도 마이그레이션이다 — 이 문장이 실패해도 V12 가 만든 7개
-- 테이블을 되돌릴 필요 없이 이 파일만 재시도하면 된다(MySQL DDL 은 트랜잭션이 아니므로
-- V12 의 CREATE TABLE 들은 이미 개별적으로 커밋되어 있다).

ALTER TABLE notification
    ADD COLUMN actor_user_id VARCHAR(36) DEFAULT NULL,
    ADD COLUMN actor_name    VARCHAR(64) DEFAULT NULL,
    ADD COLUMN post_id       VARCHAR(36) DEFAULT NULL,
    ADD COLUMN group_id      VARCHAR(36) DEFAULT NULL,
    ADD COLUMN team_id       VARCHAR(36) DEFAULT NULL;

CREATE INDEX idx_noti_user_group ON notification (user_id, group_id, created_at);
