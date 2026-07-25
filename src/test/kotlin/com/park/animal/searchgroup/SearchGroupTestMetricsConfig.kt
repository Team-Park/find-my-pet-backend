package com.park.animal.searchgroup

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * `@DataJpaTest` 슬라이스는 `MetricsAutoConfiguration` 을 올리지 않는다.
 * 설계 §20 관측 카운터를 쓰는 빈(`SearchGroupAccessResolver`, `SearchGroupMembershipService`,
 * `SearchGroupTeamSupportService`)을 `@Import` 하면 `MeterRegistry` 가 없어 컨텍스트 로딩 자체가 실패한다.
 * 계측 값은 검증 대상이 아니므로 in-memory 레지스트리로 충분하다.
 *
 * 이 파일은 함께 찾기 IT 전체가 공유한다 — 같은 패키지에 같은 이름의 `@TestConfiguration` 을
 * 파일마다 따로 두면 빈 이름이 충돌한다.
 */
@TestConfiguration
class SearchGroupTestMetricsConfig {
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}
