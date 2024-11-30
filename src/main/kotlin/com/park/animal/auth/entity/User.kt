package com.park.animal.auth.entity

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.common.constants.AuthConstants
import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.AttributeOverride
import jakarta.persistence.AttributeOverrides
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import model.Role
import org.hibernate.annotations.DynamicUpdate

@Entity
@DynamicUpdate
class User(
    @Column(name = "email", nullable = true)
    var email: String?,
    @Column(name = "password", nullable = true)
    var password: String?,
    @AttributeOverrides(
        AttributeOverride(name = "social_id", column = Column(name = "social_id")),
        AttributeOverride(name = "provider", column = Column(name = "provider")),
    )
    var socialProvider: SocialProvider?,
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    var role: Role,
    @Column(name = "profile", nullable = true)
    var profile: String?,
) : BaseEntity() {
    fun getClaims(): Map<String, Any> {
        val claims = mutableMapOf<String, Any>()
        claims[AuthConstants.USER_ID] = id
        claims[AuthConstants.USER_ROLE] = role
        return claims
    }
}

@Embeddable
data class SocialProvider(
    @Column(name = "social_id")
    val socialId: String,
    @Enumerated(EnumType.STRING)
    val provider: SocialLoginProvider,
)
