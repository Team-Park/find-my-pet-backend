package com.park.animal.searchgroup

import com.park.animal.searchgroup.entity.SearchGroupEvent
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.repository.SearchGroupEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 수색그룹 감사 기록 (설계 §20).
 *
 * [detail] 에는 상태 전이 요약만 넣는다. 채팅 본문, 상세 좌표, 전화번호, 차단 사유는 절대 넣지 않는다
 * (설계 §16.5). 컬럼이 `VARCHAR(256)` 이므로 저장 전에 잘라 SQL 예외로 상위 트랜잭션을 깨지 않는다.
 *
 * [Propagation.MANDATORY] 로 선언해 감사 기록이 본 작업과 **같은 트랜잭션**에 묶이도록 강제한다.
 * 감사만 남고 상태 전이가 롤백되는(또는 그 반대) 상황을 컴파일이 아니라 런타임에서 즉시 드러낸다.
 */
@Service
class SearchGroupEventRecorder(
    private val searchGroupEventRepository: SearchGroupEventRepository,
) {
    companion object {
        private const val DETAIL_MAX_LENGTH = 256
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        groupId: UUID,
        type: SearchGroupEventType,
        actorId: UUID?,
        targetId: UUID?,
        detail: String?,
    ): SearchGroupEvent =
        searchGroupEventRepository.save(
            SearchGroupEvent(
                groupId = groupId,
                type = type,
                actorId = actorId,
                targetId = targetId,
                detail = detail?.take(DETAIL_MAX_LENGTH),
            ),
        )
}
