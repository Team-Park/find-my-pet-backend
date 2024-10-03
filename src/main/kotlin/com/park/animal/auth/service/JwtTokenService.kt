package com.park.animal.auth.service

import com.park.animal.auth.dto.JwtResponseDto
import com.park.animal.common.constants.AuthConstants
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.ParseJwtFailedException
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Header
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*
import com.park.animal.common.http.error.exception.ExpiredJwtException as CustomExpiredJwtException

@Service
class JwtTokenService(
    @Value("\${jwt.access-token.secret-key}")
    private val accessTokenSecretKeyString: String,
    @Value("\${jwt.refresh-token.secret-key}")
    private val refreshTokenSecretKeyString: String,
    @Value("\${jwt.access-token.expire-millis}")
    private val accessTokenExpireTime: Long,
    @Value("\${jwt.refresh-token.expire-millis}")
    private val refreshTokenExpireTime: Long,
) {
    private val accessTokenSecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(accessTokenSecretKeyString))
    private val refreshTokenSecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(refreshTokenSecretKeyString))

    fun build(claims: Map<String, Any>): JwtResponseDto {
        val accessToken = buildAccessToken(claims)
        val refreshToken = buildRefreshToken(claims)
        return JwtResponseDto(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresIn = accessTokenExpireTime,
            refreshTokenExpiresIn = refreshTokenExpireTime,
        )
    }

    fun buildAccessToken(claims: Map<String, Any>): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .setHeader(Jwts.header().setType(Header.JWT_TYPE))
            .setClaims(claims)
            .setExpiration(Date((now + accessTokenExpireTime)))
            .setIssuedAt(Date(now))
            .signWith(
                accessTokenSecretKey,
//                SignatureAlgorithm.ES512,
                SignatureAlgorithm.HS512,
            )
            .compact()
    }

    fun buildRefreshToken(claims: Map<String, Any>): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .setHeader(Jwts.header().setType(Header.JWT_TYPE))
            .setClaims(claims)
            .setExpiration(Date((now + refreshTokenExpireTime)))
            .setIssuedAt(Date(now))
            .signWith(
                refreshTokenSecretKey,
                SignatureAlgorithm.HS512,
            )
            .compact()
    }

    fun parseAccessToken(token: String): Map<String, Any> {
        return try {
            Jwts.parserBuilder()
                .setSigningKey(accessTokenSecretKey)
                .build()
                .parseClaimsJws(token)
                .body
        } catch (e: ExpiredJwtException) {
            throw CustomExpiredJwtException(ErrorCode.EXPIRED_JWT)
        } catch (e: JwtException) {
            throw ParseJwtFailedException(ErrorCode.PARSE_JWT_FAILED)
        }
    }

    fun parseRefreshToken(refreshToken: String): Map<String, Any> {
        return try {
            Jwts.parserBuilder()
                .setSigningKey(refreshTokenSecretKey)
                .build()
                .parseClaimsJws(refreshToken)
                .body
        } catch (e: ExpiredJwtException) {
            throw CustomExpiredJwtException(ErrorCode.EXPIRED_JWT)
        } catch (e: JwtException) {
            throw ParseJwtFailedException(ErrorCode.PARSE_JWT_FAILED)
        }
    }

    fun getUserIdByRefreshToken(refreshToken: String): UUID =
        UUID.fromString(parseRefreshToken(refreshTokenSecretKeyString)[AuthConstants.USER_ID].toString())

    companion object {
        fun minKeyStringLength(algorithm: SignatureAlgorithm) = algorithm.minKeyLength.let { (it + 5) / 6 }
    }
}
