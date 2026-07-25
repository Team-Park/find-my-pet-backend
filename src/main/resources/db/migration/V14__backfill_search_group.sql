-- 백필: 기능 도입 시점의 삭제되지 않은 SEARCHING 실종 소식마다 OPEN·ACTIVE 그룹 1개 (설계 17 말미).
-- SEEN / FOUND / soft-delete 는 대상 아님. NOT EXISTS 가드로 개별 재실행 가능.
-- MySQL UUID() 는 v1 이라 시간순 정렬이 되지 않는다 → search_group 을 id 로 정렬/페이징하지 말 것.
--
-- V12/V13 과 분리된 별도 마이그레이션이다 — post 전체를 스캔하는 단일 문장이라 대용량
-- 테이블에서는 시간이 오래 걸릴 수 있고, 실패/타임아웃 시 FlywayConfig 의 repair()+migrate() 가
-- 이 파일 하나만 재시도하면 된다. NOT EXISTS 가드가 이미 멱등이므로 재시도는 안전하다 — 같은
-- 문장이 V12 안에 있었다면 repair() 가 파일 전체를 재실행하다 이미 존재하는 CREATE TABLE 에서
-- 죽어 이후 모든 배포가 막혔을 것이다.

INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at)
SELECT UUID(), p.id, 'OPEN', 'ACTIVE', NOW(6), NOW(6)
  FROM post p
 WHERE p.missing_animal_status = 'SEARCHING'
   AND p.deleted_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM search_group g WHERE g.post_id = p.id);
