package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.SearchGroupEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * 그룹 감사 이벤트 리포지토리. Task 4 의 `SearchGroupService.listEvents`(GET /search-groups/{groupId}/events)가
 * 유일한 조회 소비자이고, Task 5~9 의 `SearchGroupEventRecorder` 가 유일한 기록 소비자다.
 *
 * 규칙: 삽입과 조회만 한다. delete / deleteById 금지 — 감사 기록은 지우지 않는다(설계 20).
 * 조회는 언제나 groupId 스코프다. 이벤트 id 단독 조회 API 는 만들지 않는다.
 * 이 목록만 size/offset 페이징을 받으므로 Pageable 을 그대로 노출한다.
 */
interface SearchGroupEventRepository : JpaRepository<SearchGroupEvent, UUID> {
    fun findAllByGroupIdOrderByCreatedAtDescIdDesc(
        groupId: UUID,
        pageable: Pageable,
    ): Page<SearchGroupEvent>
}
