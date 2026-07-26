package com.park.animal.searchgroup.dto

import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "수색그룹 - 팀 지원 연결")
data class SearchGroupTeamSupportResponse(
    val id: UUID,
    val groupId: UUID,
    val teamId: UUID,
    val teamName: String?,
    val status: SearchGroupTeamStatus,
    @Schema(
        description =
            "이 연결을 최초로 만든 사용자. 종료 후 재요청으로 되살아난 행은 최초 요청자를 유지한다 " +
                "— 상태 전이는 status/decidedBy 만 바꾸기 때문이다",
    )
    val requestedBy: UUID,
    val requestedAt: LocalDateTime,
    val decidedAt: LocalDateTime?,
    @Schema(description = "지원이 활성화된 시각. 팀원의 기록 열람 시작점")
    val activatedAt: LocalDateTime?,
) {
    companion object {
        fun of(
            support: SearchGroupTeam,
            teamName: String?,
        ): SearchGroupTeamSupportResponse =
            SearchGroupTeamSupportResponse(
                id = support.id,
                groupId = support.groupId,
                teamId = support.teamId,
                teamName = teamName,
                status = support.status,
                requestedBy = support.requestedBy,
                requestedAt = support.requestedAt,
                decidedAt = support.decidedAt,
                activatedAt = support.activatedAt,
            )
    }
}
