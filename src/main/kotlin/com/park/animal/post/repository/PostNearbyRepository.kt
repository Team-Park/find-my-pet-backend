package com.park.animal.post.repository

import com.park.animal.breed.entity.AnimalType
import com.park.animal.post.dto.PostNearbyResponse
import com.park.animal.post.entity.MissingAnimalStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class PostNearbyRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    companion object {
        private const val NEARBY_SQL = """
            SELECT
              p.id                     AS id,
              p.author_name            AS author,
              p.title                  AS title,
              p.description            AS description,
              p.gratuity               AS gratuity,
              p.place                  AS place,
              p.time                   AS time,
              (SELECT pi.image_url
                 FROM post_image pi
                WHERE pi.post_id = p.id AND pi.deleted_at IS NULL
                ORDER BY pi.created_at
                LIMIT 1)                AS thumbnail,
              p.missing_animal_status  AS missing_animal_status,
              p.animal_type            AS animal_type,
              p.breed_id               AS breed_id,
              p.lat                    AS lat,
              p.lng                    AS lng,
              ST_Distance_Sphere(
                POINT(:lng, :lat), POINT(p.lng, p.lat)
              ) / 1000                 AS distance_km
            FROM post p
            WHERE p.deleted_at IS NULL
              AND ST_Distance_Sphere(POINT(:lng, :lat), POINT(p.lng, p.lat)) <= :radiusM
            ORDER BY distance_km ASC
            LIMIT :size OFFSET :offset
        """

        private const val COUNT_SQL = """
            SELECT COUNT(*)
            FROM post p
            WHERE p.deleted_at IS NULL
              AND ST_Distance_Sphere(POINT(:lng, :lat), POINT(p.lng, p.lat)) <= :radiusM
        """
    }

    fun findNearby(
        lat: Double,
        lng: Double,
        radiusKm: Double,
        size: Long,
        offset: Long,
    ): List<PostNearbyResponse> {
        val params =
            MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radiusM", radiusKm * 1000)
                .addValue("size", size)
                .addValue("offset", offset)
        return jdbcTemplate.query(NEARBY_SQL, params) { rs, _ -> mapRow(rs) }
    }

    fun countNearby(
        lat: Double,
        lng: Double,
        radiusKm: Double,
    ): Long {
        val params =
            MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radiusM", radiusKm * 1000)
        return jdbcTemplate.queryForObject(COUNT_SQL, params, Long::class.java) ?: 0L
    }

    private fun mapRow(rs: ResultSet): PostNearbyResponse =
        PostNearbyResponse(
            id = UUID.fromString(rs.getString("id")),
            author = rs.getString("author"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            gratuity = rs.getInt("gratuity"),
            place = rs.getString("place"),
            time = rs.getTimestamp("time").toLocalDateTime(),
            thumbnail = rs.getString("thumbnail"),
            missingAnimalStatus = MissingAnimalStatus.valueOf(rs.getString("missing_animal_status")),
            animalType = AnimalType.valueOf(rs.getString("animal_type")),
            breedId = rs.getString("breed_id")?.let(UUID::fromString),
            lat = rs.getDouble("lat"),
            lng = rs.getDouble("lng"),
            distanceKm = rs.getDouble("distance_km"),
        )
}
