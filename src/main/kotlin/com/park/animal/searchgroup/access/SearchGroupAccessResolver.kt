package com.park.animal.searchgroup.access

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupAccessRow
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 수색그룹 접근 판정 단일 진입점 (설계 §7, §16.1, §20).
 *
 * 판정 입력은 `userId: UUID` 뿐이다. `Passport.role` 을 절대 보지 않는다 —
 * 서비스 전체 관리자 권한과 그룹 권한을 섞으면 설계 §2 의 "admin 은 제품 역할이 아니다" 가 깨진다.
 *
 * 예외 순서는 정보 누출을 막기 위해 고정한다.
 * 1. 행 없음 / `postDeleted` → 404 [ErrorCode.NOT_FOUND_SEARCH_GROUP] (`reason=not_found`)
 * 2. 차단됨 또는 역할 없음 → 403 [ErrorCode.SEARCH_GROUP_ACCESS_DENIED] (`reason=forbidden`)
 *    (차단과 비참여가 **같은 코드**여야 한다. 다르면 차단 사실이 응답으로 새어나간다 — 계약 §9)
 * 3. 쓰기 경로에서 그룹이 ARCHIVED → 410 [ErrorCode.SEARCH_ALREADY_ENDED] (`reason=archived`)
 *
 * **관측(설계 §20)**: 거절할 때마다 [ACCESS_DENIED_METRIC] 카운터를 증가시킨다.
 * tag 는 `reason` 하나뿐이다. groupId·userId 를 label 에 넣으면 카디널리티가 사용자 수만큼
 * 폭발하고 설계 §20 의 "식별 정보를 메트릭 label 에 넣지 않는다" 를 위반한다.
 * 어떤 그룹에서 났는지는 `search_group_event` 감사 기록과 애플리케이션 로그로 추적한다.
 * 선례는 `SearchController` 의 `fmp.search.fulltext.rescue` 이다.
 *
 * **호출 순서 계약 — resolve 먼저, mutate 나중.**
 * 하위 리포지토리는 native SQL 이라 Hibernate auto-flush 대상이 아니다. 같은 트랜잭션에서
 * 아직 flush 되지 않은 엔티티 변경(신규 멤버십, 상태 전이 등)은 이 판정에 보이지 않는다.
 * 판정 결과가 필요한 값(예: 알림 수신자 집합)은 **반드시 상태를 바꾸기 전에** 계산한다.
 * 불가피하게 순서가 뒤바뀌면 호출부에서 `entityManager.flush()` 후 재조회한다.
 * 이 규칙은 `SearchGroupAccessResolverIT` 의 "native 접근 판정은 Hibernate auto-flush 를
 * 트리거하지 않는다" 테스트로 고정돼 있다.
 */
@Component
class SearchGroupAccessResolver(
    private val accessQueryRepository: SearchGroupAccessQueryRepository,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        const val ACCESS_DENIED_METRIC = "fmp.searchgroup.access.denied"
        private const val REASON_TAG = "reason"
        private const val REASON_NOT_FOUND = "not_found"
        private const val REASON_FORBIDDEN = "forbidden"
        private const val REASON_ARCHIVED = "archived"
    }

    fun resolve(
        groupId: UUID,
        userId: UUID?,
    ): GroupAccess? = accessQueryRepository.findAccessRow(groupId, userId)?.let { toAccess(it, userId) }

    fun resolveByPostId(
        postId: UUID,
        userId: UUID?,
    ): GroupAccess? = accessQueryRepository.findAccessRowByPostId(postId, userId)?.let { toAccess(it, userId) }

    /** 존재하고 볼 수 있는가. 아니면 404. */
    fun requireVisible(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = resolve(groupId, userId) ?: denyNotFound()
        if (!access.visible) denyNotFound()
        return access
    }

    /** 지도·활동·멤버 조회 권한. 차단·비참여 모두 같은 403. */
    fun requireRead(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = requireVisible(groupId, userId)
        if (!access.canRead) denyForbidden()
        return access
    }

    /** 그룹에 무언가를 남기는 경로. 종료된 수색은 410 으로 읽기 전용임을 알린다. */
    fun requireWrite(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = requireRead(groupId, userId)
        if (access.groupStatus != SearchGroupStatus.ACTIVE) denyArchived()
        return access
    }

    /** 보호자 전용 관리 경로(정책 변경·승인·차단·팀 지원 처리·수색 종료). */
    fun requireOwner(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = requireVisible(groupId, userId)
        if (!access.isOwner) denyForbidden()
        if (access.groupStatus != SearchGroupStatus.ACTIVE) denyArchived()
        return access
    }

    /**
     * 알림 fan-out 수신자 (설계 §6.6, §9).
     * 중복 제거는 여기서 끝난다 — 호출부는 이 집합을 그대로 쓰고 다시 합치지 않는다.
     */
    fun effectiveMemberIds(groupId: UUID): Set<UUID> = accessQueryRepository.findEffectiveMemberIds(groupId).toSet()

    /** 마이페이지 허브·통합 지도의 1단계 좁히기. 상태 필터는 호출부가 적용한다. */
    fun accessibleGroupIds(userId: UUID): List<UUID> = accessQueryRepository.findAccessibleGroupIds(userId)

    private fun denyNotFound(): Nothing {
        countDenied(REASON_NOT_FOUND)
        throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
    }

    private fun denyForbidden(): Nothing {
        countDenied(REASON_FORBIDDEN)
        throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
    }

    private fun denyArchived(): Nothing {
        countDenied(REASON_ARCHIVED)
        throw BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
    }

    private fun countDenied(reason: String) {
        meterRegistry.counter(ACCESS_DENIED_METRIC, REASON_TAG, reason).increment()
    }

    private fun toAccess(
        row: SearchGroupAccessRow,
        viewerId: UUID?,
    ): GroupAccess {
        val isOwner = viewerId != null && viewerId == row.ownerUserId
        val sources = linkedSetOf<AccessSource>()
        if (isOwner) sources += AccessSource.OWNER
        if (row.directMembershipStatus == SearchGroupMemberStatus.ACTIVE) sources += AccessSource.DIRECT
        if (row.teamCount > 0) sources += AccessSource.TEAM

        val role =
            when {
                isOwner -> GroupRole.OWNER
                sources.isNotEmpty() -> GroupRole.PARTICIPANT
                else -> GroupRole.NONE
            }

        return GroupAccess(
            groupId = row.groupId,
            postId = row.postId,
            ownerUserId = row.ownerUserId,
            viewerId = viewerId,
            groupStatus = row.groupStatus,
            joinPolicy = row.joinPolicy,
            postDeleted = row.postDeleted,
            postStatus = row.postStatus,
            role = role,
            sources = sources,
            teamCount = row.teamCount,
            directMembershipId = row.directMembershipId,
            directMembershipStatus = row.directMembershipStatus,
            // 보호자는 차단 대상이 될 수 없다(계약 §9). 과거 데이터로 행이 남아 있어도 무시한다.
            blocked = row.blocked && !isOwner,
        )
    }
}
