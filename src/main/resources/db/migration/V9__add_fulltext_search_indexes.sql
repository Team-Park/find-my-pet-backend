-- 한국어 검색 성능 강화 — InnoDB FULLTEXT + ngram parser.
-- ngram_token_size 는 MySQL 서버 변수(기본 2). 2글자 미만 쿼리는 매칭 안 되므로
-- 서비스 레이어에서 q.length < 2 일 때 LIKE fallback 으로 처리.

-- 실종 게시글 검색: title + description + place
ALTER TABLE post
    ADD FULLTEXT INDEX ftx_post_search (title, description, place) WITH PARSER ngram;

-- 유기동물 검색: 품종 + 발견장소 + 보호소주소 + 보호소명 + 특이사항
ALTER TABLE abandoned_animal
    ADD FULLTEXT INDEX ftx_aa_search (kind_full_nm, happen_place, care_addr, care_nm, special_mark) WITH PARSER ngram;
