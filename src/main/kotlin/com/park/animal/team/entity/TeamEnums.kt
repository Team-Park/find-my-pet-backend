package com.park.animal.team.entity

/** 팀 생명주기. */
enum class TeamStatus { ACTIVE, ARCHIVED }

/**
 * 팀 내 역할. 제품 역할에 admin 은 존재하지 않는다(설계 21).
 * 활성 LEADER 유일성은 team_member.uq_tm_single_active_leader 가 DB 에서 강제한다.
 */
enum class TeamRole { LEADER, MEMBER }

/** 팀 멤버십 상태. SearchGroupMemberStatus 와 같은 이유로 soft-delete 를 쓰지 않는다. */
enum class TeamMemberStatus { PENDING, ACTIVE, REJECTED, LEFT, REMOVED }
