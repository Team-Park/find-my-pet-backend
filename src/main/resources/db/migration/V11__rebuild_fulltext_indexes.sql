-- FULLTEXT ngram 인덱스 재빌드.
-- 2026-05-02 운영에서 MATCH AGAINST 가 한국어 검색어에 0건을 반환했다(커밋 1d4ad98 로 LIKE 우회).
-- 동일 구성(mysql 8.4, 기본 ngram_token_size=2)의 로컬 재현 실험에서는 V9 와 같은 DDL 로
-- 정상 매칭됨을 확인 — 당시 인덱스 빌드 상태가 원인 후보로 남아 동일 정의로 재빌드한다.
-- 애플리케이션은 FULLTEXT → LIKE 하이브리드로 재활성화되어 재빌드가 무효하더라도 검색은 보전된다.
ALTER TABLE post DROP INDEX ftx_post_search;
ALTER TABLE post ADD FULLTEXT INDEX ftx_post_search (title, description, place) WITH PARSER ngram;

ALTER TABLE abandoned_animal DROP INDEX ftx_aa_search;
ALTER TABLE abandoned_animal ADD FULLTEXT INDEX ftx_aa_search (kind_full_nm, happen_place, care_addr, care_nm, special_mark) WITH PARSER ngram;
