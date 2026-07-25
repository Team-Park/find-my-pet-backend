package com.park.animal.searchgroup.repository

import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** 접근 판정 한 줄. 도메인 규칙은 담지 않고 DB 사실만 담는다. */
data class SearchGroupAccessRow(
    val groupId: UUID,
    val postId: UUID,
    val ownerUserId: UUID,
    val groupStatus: SearchGroupStatus,
    val joinPolicy: JoinPolicy,
    val postDeleted: Boolean,
    val postStatus: MissingAnimalStatus,
    val directMembershipId: UUID?,
    val directMembershipStatus: SearchGroupMemberStatus?,
    val teamCount: Int,
    val blocked: Boolean,
)

/**
 * 수색그룹 접근 판정 전용 native 쿼리 (설계 §6.6, §12.3).
 *
 * 설계 원칙:
 * 1. 모든 UUID 파라미터는 `.toString()` 으로 바인딩한다. 컬럼이 `VARCHAR(36)` 이라
 *    `java.util.UUID` 를 그대로 넘기면 드라이버가 바이너리로 바인딩해 항상 0건이 된다.
 * 2. 비로그인 조회는 [ANONYMOUS_USER_ID](zero-UUID)를 바인딩한다. 어떤 실제 사용자와도
 *    매칭되지 않으므로 LEFT JOIN 과 상관 서브쿼리가 자연히 비고, SQL 을 한 벌만 유지할 수 있다.
 * 3. `GROUP_CONCAT` 을 쓰지 않는다. `group_concat_max_len` 을 넘기면 경고 없이 잘려서
 *    "권한이 있는데 없다고 판정" 되는 사일런트 버그가 된다. 팀 경로는 `COUNT(*)` 로만 센다.
 *    "어느 팀을 통해 들어왔는가" 가 필요하면 팀 지원 목록 API(계약 §8 #17)에서 행 단위로 조회한다.
 *
 * **중요 — auto-flush 없음**: 이 리포지토리는 JDBC native SQL 이라 Hibernate 의 auto-flush 가
 * 걸리지 않는다. 같은 트랜잭션에서 아직 flush 되지 않은 엔티티 변경은 보이지 않는다.
 * 따라서 서비스 계약은 **"resolve 먼저, mutate 나중"** 이다. 순서를 바꿀 수밖에 없다면
 * 호출 전에 `entityManager.flush()` 로 강제 반영한 뒤 재조회한다.
 */
@Repository
class SearchGroupAccessQueryRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    companion object {
        /** 비로그인 뷰어 자리표시자. 실제 사용자 id 와 절대 충돌하지 않는다. */
        private val ANONYMOUS_USER_ID: UUID = UUID(0L, 0L)

        private const val ACCESS_SELECT = """
            SELECT g.id                    AS group_id,
                   g.post_id               AS post_id,
                   g.join_policy           AS join_policy,
                   g.status                AS group_status,
                   p.author_id             AS owner_user_id,
                   p.missing_animal_status AS post_status,
                   p.deleted_at            AS post_deleted_at,
                   dm.id                   AS direct_membership_id,
                   dm.status               AS direct_membership_status,
                   (SELECT COUNT(*)
                      FROM search_group_team sgt
                      JOIN team_member tm ON tm.team_id = sgt.team_id
                     WHERE sgt.group_id = g.id
                       AND sgt.status = 'ACTIVE'
                       AND tm.user_id = :userId
                       AND tm.status = 'ACTIVE')  AS team_count,
                   (SELECT COUNT(*)
                      FROM search_group_user_block b
                     WHERE b.group_id = g.id
                       AND b.user_id = :userId
                       AND b.unblocked_at IS NULL) AS block_count
              FROM search_group g
              JOIN post p ON p.id = g.post_id
              LEFT JOIN search_group_member dm
                     ON dm.group_id = g.id
                    AND dm.user_id = :userId
        """

        private const val ACCESS_BY_GROUP_SQL = ACCESS_SELECT + " WHERE g.id = :groupId"

        private const val ACCESS_BY_POST_SQL = ACCESS_SELECT + " WHERE g.post_id = :postId"

        /**
         * 유효 참여자(설계 §6.6): 보호자 ∪ ACTIVE 직접멤버 ∪ ACTIVE 팀지원의 ACTIVE 팀원 − 활성차단.
         * `UNION` 이 중복을 제거하므로 여러 경로로 들어온 사용자도 한 번만 나온다(설계 §9 "한 번만").
         * 보호자는 차단 anti-join 의 예외다(계약 §9 — 보호자 자신은 차단 대상이 될 수 없다).
         * 실종 소식의 삭제 여부는 여기서 거르지 않는다 — 멤버십 계산이지 노출 판정이 아니다.
         */
        private const val EFFECTIVE_MEMBER_SQL = """
            SELECT c.user_id AS user_id
              FROM (
                    SELECT p.author_id AS user_id
                      FROM search_group g
                      JOIN post p ON p.id = g.post_id
                     WHERE g.id = :groupId
                    UNION
                    SELECT m.user_id
                      FROM search_group_member m
                     WHERE m.group_id = :groupId
                       AND m.status = 'ACTIVE'
                    UNION
                    SELECT tm.user_id
                      FROM search_group_team sgt
                      JOIN team_member tm ON tm.team_id = sgt.team_id
                     WHERE sgt.group_id = :groupId
                       AND sgt.status = 'ACTIVE'
                       AND tm.status = 'ACTIVE'
                   ) c
              JOIN search_group g2 ON g2.id = :groupId
              JOIN post p2 ON p2.id = g2.post_id
             WHERE (
                    c.user_id = p2.author_id
                 OR NOT EXISTS (SELECT 1
                                  FROM search_group_user_block b
                                 WHERE b.group_id = :groupId
                                   AND b.user_id = c.user_id
                                   AND b.unblocked_at IS NULL)
                   )
        """

        /**
         * 사용자가 볼 수 있는 그룹 id. ACTIVE/ARCHIVED 를 모두 포함한다 —
         * 상태 필터링은 허브·지도 각 호출부의 몫이다.
         * `search_group.id` 는 백필에서 MySQL `UUID()`(v1) 로 생성돼 시간순이 아니므로 절대 id 로 정렬하지 않는다.
         */
        private const val ACCESSIBLE_GROUP_SQL = """
            SELECT g.id AS group_id
              FROM search_group g
              JOIN post p ON p.id = g.post_id
             WHERE p.deleted_at IS NULL
               AND (
                    p.author_id = :userId
                 OR EXISTS (SELECT 1
                              FROM search_group_member m
                             WHERE m.group_id = g.id
                               AND m.user_id = :userId
                               AND m.status = 'ACTIVE')
                 OR EXISTS (SELECT 1
                              FROM search_group_team sgt
                              JOIN team_member tm ON tm.team_id = sgt.team_id
                             WHERE sgt.group_id = g.id
                               AND sgt.status = 'ACTIVE'
                               AND tm.user_id = :userId
                               AND tm.status = 'ACTIVE')
                   )
               AND (
                    p.author_id = :userId
                 OR NOT EXISTS (SELECT 1
                                  FROM search_group_user_block b
                                 WHERE b.group_id = g.id
                                   AND b.user_id = :userId
                                   AND b.unblocked_at IS NULL)
                   )
             ORDER BY g.created_at DESC
        """
    }

    fun findAccessRow(
        groupId: UUID,
        userId: UUID?,
    ): SearchGroupAccessRow? {
        val params =
            MapSqlParameterSource()
                .addValue("groupId", groupId.toString())
                .addValue("userId", viewerParam(userId))
        return jdbcTemplate.query(ACCESS_BY_GROUP_SQL, params) { rs, _ -> mapAccessRow(rs) }.firstOrNull()
    }

    fun findAccessRowByPostId(
        postId: UUID,
        userId: UUID?,
    ): SearchGroupAccessRow? {
        val params =
            MapSqlParameterSource()
                .addValue("postId", postId.toString())
                .addValue("userId", viewerParam(userId))
        return jdbcTemplate.query(ACCESS_BY_POST_SQL, params) { rs, _ -> mapAccessRow(rs) }.firstOrNull()
    }

    fun findEffectiveMemberIds(groupId: UUID): List<UUID> {
        val params = MapSqlParameterSource().addValue("groupId", groupId.toString())
        return jdbcTemplate.query(EFFECTIVE_MEMBER_SQL, params) { rs, _ ->
            UUID.fromString(rs.getString("user_id"))
        }
    }

    fun findAccessibleGroupIds(userId: UUID): List<UUID> {
        val params = MapSqlParameterSource().addValue("userId", userId.toString())
        return jdbcTemplate.query(ACCESSIBLE_GROUP_SQL, params) { rs, _ ->
            UUID.fromString(rs.getString("group_id"))
        }
    }

    private fun viewerParam(userId: UUID?): String = (userId ?: ANONYMOUS_USER_ID).toString()

    private fun mapAccessRow(rs: ResultSet): SearchGroupAccessRow =
        SearchGroupAccessRow(
            groupId = UUID.fromString(rs.getString("group_id")),
            postId = UUID.fromString(rs.getString("post_id")),
            ownerUserId = UUID.fromString(rs.getString("owner_user_id")),
            groupStatus = SearchGroupStatus.valueOf(rs.getString("group_status")),
            joinPolicy = JoinPolicy.valueOf(rs.getString("join_policy")),
            postDeleted = rs.getTimestamp("post_deleted_at") != null,
            postStatus = MissingAnimalStatus.valueOf(rs.getString("post_status")),
            directMembershipId = rs.getString("direct_membership_id")?.let(UUID::fromString),
            directMembershipStatus = rs.getString("direct_membership_status")?.let(SearchGroupMemberStatus::valueOf),
            teamCount = rs.getInt("team_count"),
            blocked = rs.getInt("block_count") > 0,
        )
}
