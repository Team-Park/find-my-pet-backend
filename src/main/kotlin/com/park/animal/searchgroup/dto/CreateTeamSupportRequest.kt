package com.park.animal.searchgroup.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * 팀 지원 연결 요청.
 *
 * 초기 status 와 방향(direction)은 **요청 본문에 두지 않는다**. 서버가
 * tuple(group, post.authorId, team_member(teamId, actorUserId, LEADER, ACTIVE))로 계산한다 —
 * 클라이언트가 보낸 값을 신뢰하면 보호자/팀장 승인 단계를 건너뛰는 IDOR 가 된다(설계 §16.1).
 */
@Schema(description = "팀 지원 연결 요청")
data class CreateTeamSupportRequest(
    @Schema(description = "지원할 팀 id")
    val teamId: UUID,
    @Schema(
        description =
            "상대에게 전달할 짧은 메시지 (200자 이하). 길이만 검증하고 알림 본문에는 넣지 않는다 — " +
                "다른 사용자가 자유 텍스트로 지은 값(팀 이름·게시글 제목과 같은 종류)을 다른 사용자의 알림에 " +
                "그대로 보간하면 알림 문구 표(Task 5)가 갖는 금칙어·숫자 검사를 우회하게 된다.",
    )
    val message: String? = null,
)
