package com.park.animal.team.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "팀 생성 요청")
data class CreateTeamRequest(
    @Schema(description = "팀 이름 (2~30자)", example = "한강 수색대")
    val name: String,
    @Schema(description = "팀 소개 (200자 이하)", example = "야간 위주로 움직여요")
    val description: String? = null,
)

@Schema(description = "팀 수정 요청 (팀장 전용)")
data class UpdateTeamRequest(
    @Schema(description = "팀 이름 (2~30자)", example = "한강 야간 수색대")
    val name: String,
    @Schema(description = "팀 소개 (200자 이하)")
    val description: String? = null,
)

@Schema(description = "팀장 이전 요청")
data class TransferTeamLeadershipRequest(
    @Schema(description = "팀장이 될 팀원의 멤버십 id")
    val targetMembershipId: UUID,
)
