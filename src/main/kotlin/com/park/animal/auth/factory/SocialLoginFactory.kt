package com.park.animal.auth.factory

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.handler.AbstractSocialLoginHandler
import org.springframework.stereotype.Component

@Component
class SocialLoginFactory(
    private val handlers: List<AbstractSocialLoginHandler>,
) {
    fun getProviderService(provider: SocialLoginProvider): AbstractSocialLoginHandler {
        return handlers.stream()
            .filter { i -> i.isApplicable(provider) }
            .findFirst()
            .orElseThrow { throw RuntimeException() }
    }
}
