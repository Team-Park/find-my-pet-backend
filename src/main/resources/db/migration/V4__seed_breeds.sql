-- ========================================================================
-- 품종 시드 데이터 (50종)
-- 출처: ASPCA/Missing Animal Response Network 가이드라인 + 한국 반려동물 구조 사례 기반 추정치.
-- 운영 중 실제 구조 사례 누적으로 점진 교정 필요.
-- ========================================================================

-- ─────────────────────────────────────────────────────────────────────
-- 개 (DOG) 30종 — 크기별로 분류
-- TINY (2-5kg) — HIDE 경향, 숨어있다 나옴
-- ─────────────────────────────────────────────────────────────────────
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'DOG', '말티즈',        'Maltese',           'TINY', 1.0, 'HIDE', 0.3, NOW(), NOW()),
  (UUID(), 'DOG', '포메라니안',    'Pomeranian',        'TINY', 1.0, 'HIDE', 0.3, NOW(), NOW()),
  (UUID(), 'DOG', '요크셔테리어',  'Yorkshire Terrier', 'TINY', 1.0, 'HIDE', 0.3, NOW(), NOW()),
  (UUID(), 'DOG', '치와와',        'Chihuahua',         'TINY', 1.0, 'HIDE', 0.3, NOW(), NOW()),
  (UUID(), 'DOG', '토이푸들',      'Toy Poodle',        'TINY', 1.0, 'HIDE', 0.3, NOW(), NOW());

-- SMALL (5-10kg) — HIDE/ROAM 혼합
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'DOG', '시추',          'Shih Tzu',             'SMALL', 1.8, 'HIDE', 0.5, NOW(), NOW()),
  (UUID(), 'DOG', '닥스훈트',      'Dachshund',            'SMALL', 1.8, 'HIDE', 0.5, NOW(), NOW()),
  (UUID(), 'DOG', '비숑프리제',    'Bichon Frise',         'SMALL', 1.8, 'HIDE', 0.5, NOW(), NOW()),
  (UUID(), 'DOG', '페키니즈',      'Pekingese',            'SMALL', 1.8, 'HIDE', 0.5, NOW(), NOW()),
  (UUID(), 'DOG', '파피용',        'Papillon',             'SMALL', 1.8, 'ROAM', 0.5, NOW(), NOW()),
  (UUID(), 'DOG', '코커스패니얼',  'Cocker Spaniel',       'SMALL', 1.8, 'ROAM', 0.5, NOW(), NOW());

-- MEDIUM (10-25kg) — ROAM 전형
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'DOG', '비글',              'Beagle',             'MEDIUM', 3.0, 'ROAM', 1.0, NOW(), NOW()),
  (UUID(), 'DOG', '웰시코기',          'Welsh Corgi',        'MEDIUM', 3.0, 'ROAM', 1.0, NOW(), NOW()),
  (UUID(), 'DOG', '진돗개',            'Jindo',              'MEDIUM', 3.0, 'ROAM', 1.0, NOW(), NOW()),
  (UUID(), 'DOG', '풍산개',            'Pungsan',            'MEDIUM', 3.0, 'ROAM', 1.0, NOW(), NOW()),
  (UUID(), 'DOG', '슈나우저',          'Schnauzer',          'MEDIUM', 3.0, 'ROAM', 1.0, NOW(), NOW()),
  (UUID(), 'DOG', '잉글리시불도그',    'English Bulldog',    'MEDIUM', 2.5, 'HIDE', 0.8, NOW(), NOW()),
  (UUID(), 'DOG', '아메리칸불리',      'American Bully',     'MEDIUM', 3.0, 'ROAM', 1.0, NOW(), NOW());

-- LARGE (25-40kg) — 장거리 ROAM
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'DOG', '래브라도리트리버', 'Labrador Retriever', 'LARGE', 4.5, 'ROAM',   1.3, NOW(), NOW()),
  (UUID(), 'DOG', '골든리트리버',     'Golden Retriever',   'LARGE', 4.5, 'RETURN', 1.3, NOW(), NOW()),
  (UUID(), 'DOG', '저먼셰퍼드',       'German Shepherd',    'LARGE', 4.5, 'ROAM',   1.3, NOW(), NOW()),
  (UUID(), 'DOG', '시베리안허스키',   'Siberian Husky',     'LARGE', 5.0, 'ROAM',   1.5, NOW(), NOW()),
  (UUID(), 'DOG', '사모예드',         'Samoyed',            'LARGE', 4.5, 'ROAM',   1.3, NOW(), NOW()),
  (UUID(), 'DOG', '보더콜리',         'Border Collie',      'LARGE', 4.5, 'RETURN', 1.3, NOW(), NOW()),
  (UUID(), 'DOG', '달마시안',         'Dalmatian',          'LARGE', 4.5, 'ROAM',   1.3, NOW(), NOW());

-- GIANT (40kg+) — 최장 반경, RETURN 성향
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'DOG', '세인트버나드',     'Saint Bernard',     'GIANT', 4.0, 'RETURN', 1.0, NOW(), NOW()),
  (UUID(), 'DOG', '그레이트데인',     'Great Dane',        'GIANT', 4.0, 'ROAM',   1.0, NOW(), NOW()),
  (UUID(), 'DOG', '마스티프',         'Mastiff',           'GIANT', 3.5, 'RETURN', 0.9, NOW(), NOW()),
  (UUID(), 'DOG', '알래스칸맬러뮤트', 'Alaskan Malamute',  'GIANT', 4.5, 'ROAM',   1.2, NOW(), NOW());

-- 미상 / 기타 견종 (보호자가 품종 모를 때 선택) — animalType만 DOG
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'DOG', '믹스견 (중형 추정)', 'Mixed Breed', 'MEDIUM', 2.5, 'ROAM', 0.8, NOW(), NOW());

-- ─────────────────────────────────────────────────────────────────────
-- 고양이 (CAT) 15종 — 대부분 HIDE, 공식 미사용 (base_speed_kmh = NULL)
-- ─────────────────────────────────────────────────────────────────────
-- 실내묘 잃어버림 (default) — 숨어있을 확률 극대
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'CAT', '한국 코숏',       'Korean Shorthair',    'MEDIUM', NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'CAT', '러시안블루',      'Russian Blue',        'MEDIUM', NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'CAT', '페르시안',        'Persian',             'MEDIUM', NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'CAT', '브리티쉬숏헤어',  'British Shorthair',   'MEDIUM', NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'CAT', '먼치킨',          'Munchkin',            'SMALL',  NULL, 'HIDE', 1.0, NOW(), NOW());

-- 외출 경험 있음 / 활발 — 반경 더 큼
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'CAT', '메인쿤',        'Maine Coon',   'LARGE',  NULL, 'ROAM', 1.5, NOW(), NOW()),
  (UUID(), 'CAT', '벵갈',          'Bengal',       'MEDIUM', NULL, 'ROAM', 1.5, NOW(), NOW()),
  (UUID(), 'CAT', '아비시니안',    'Abyssinian',   'MEDIUM', NULL, 'ROAM', 1.3, NOW(), NOW()),
  (UUID(), 'CAT', '사바나',        'Savannah',     'LARGE',  NULL, 'ROAM', 1.5, NOW(), NOW()),
  (UUID(), 'CAT', '스핑크스',      'Sphynx',       'MEDIUM', NULL, 'HIDE', 1.2, NOW(), NOW());

-- 나머지 (중간 성향)
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'CAT', '랙돌',            'Ragdoll',           'LARGE',  NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'CAT', '노르웨이숲',      'Norwegian Forest',  'LARGE',  NULL, 'ROAM', 1.3, NOW(), NOW()),
  (UUID(), 'CAT', '스코티시폴드',    'Scottish Fold',     'MEDIUM', NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'CAT', '터키쉬앙고라',    'Turkish Angora',    'MEDIUM', NULL, 'HIDE', 1.1, NOW(), NOW()),
  (UUID(), 'CAT', '믹스묘 (품종 미상)', 'Mixed Breed',    'MEDIUM', NULL, 'HIDE', 1.0, NOW(), NOW());

-- ─────────────────────────────────────────────────────────────────────
-- 기타 (OTHER) 5종 — 실내 탈출, 멀리 못 감
-- ─────────────────────────────────────────────────────────────────────
INSERT INTO breed (id, animal_type, name_ko, name_en, size_category, base_speed_kmh, behavior_pattern, explore_factor, created_at, updated_at) VALUES
  (UUID(), 'OTHER', '페럿',     'Ferret',       'TINY',  NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'OTHER', '햄스터',   'Hamster',      'TINY',  NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'OTHER', '토끼',     'Rabbit',       'SMALL', NULL, 'HIDE', 1.0, NOW(), NOW()),
  (UUID(), 'OTHER', '앵무새',   'Parrot',       'TINY',  NULL, 'ROAM', 1.2, NOW(), NOW()),
  (UUID(), 'OTHER', '기타',     'Other',        'SMALL', NULL, 'HIDE', 1.0, NOW(), NOW());
