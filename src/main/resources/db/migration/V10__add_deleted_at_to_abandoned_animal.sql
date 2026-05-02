-- AbandonedAnimal 엔티티가 BaseEntity 를 상속해 deleted_at 필드를 가지지만
-- V7 마이그레이션 작성 시 closed_at 만 추가하고 deleted_at 누락. Hibernate SELECT 시
-- 'Unknown column deleted_at' SQLGrammarException 발생.
-- 실제 soft delete 는 사용 안 하지만 (closed_at 으로 처리) 컬럼은 추가해야 매핑 일치.

ALTER TABLE abandoned_animal
    ADD COLUMN deleted_at TIMESTAMP NULL DEFAULT NULL AFTER updated_at;
