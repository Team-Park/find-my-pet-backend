package com.park.animal.team.dto

import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "팀 목록 항목")
data class TeamSummaryResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val status: TeamStatus,
    @Schema(description = "활성 팀원 수 (팀장 포함)")
    val activeMemberCount: Long,
    @Schema(description = "조회자의 역할. 비로그인이거나 활성/대기 멤버십이 없으면 null")
    val viewerRole: TeamRole?,
    @Schema(description = "조회자의 멤버십 상태. ACTIVE / PENDING 만 값이 내려가고 그 외에는 null")
    val viewerStatus: TeamMemberStatus?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun of(
            team: Team,
            activeMemberCount: Long,
            viewer: TeamMember?,
        ): TeamSummaryResponse =
            TeamSummaryResponse(
                id = team.id,
                name = team.name,
                description = team.description,
                status = team.status,
                activeMemberCount = activeMemberCount,
                viewerRole = viewer?.role,
                viewerStatus = viewer?.status,
                createdAt = team.createdAt,
            )
    }
}

@Schema(description = "팀 상세 + 조회자의 역할")
data class TeamResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val status: TeamStatus,
    val activeMemberCount: Long,
    @Schema(description = "승인 대기 인원. 팀장에게만 실제 값이 내려간다")
    val pendingMemberCount: Long,
    @Schema(description = "현재 팀장 표시 이름")
    val leaderName: String?,
    @Schema(description = "조회자의 역할. 비로그인/비소속이면 null")
    val viewerRole: TeamRole?,
    @Schema(description = "조회자의 멤버십 상태. 비로그인/비소속이면 null")
    val viewerStatus: TeamMemberStatus?,
    val createdAt: LocalDateTime,
)

@Schema(description = "팀 멤버십")
data class TeamMembershipResponse(
    val id: UUID,
    val teamId: UUID,
    val userId: UUID,
    val userName: String?,
    val role: TeamRole,
    val status: TeamMemberStatus,
    val joinedAt: LocalDateTime?,
    val requestedAt: LocalDateTime?,
) {
    companion object {
        fun from(member: TeamMember): TeamMembershipResponse =
            TeamMembershipResponse(
                id = member.id,
                teamId = member.teamId,
                userId = member.userId,
                userName = member.userName,
                role = member.role,
                status = member.status,
                joinedAt = member.joinedAt,
                requestedAt = member.requestedAt,
            )
    }
}
