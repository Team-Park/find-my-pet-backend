package com.park.animal.auth.service

import com.park.animal.auth.dto.JwtResponseDto
import com.park.animal.auth.dto.SignInWithSocialCommand
import com.park.animal.auth.factory.SocialLoginFactory
import org.springframework.stereotype.Service

@Service
class SignInService(
    private val socialLoginFactory: SocialLoginFactory,
) {
    fun signInWithSocial(command: SignInWithSocialCommand): JwtResponseDto {
        val providerService = socialLoginFactory.getProviderService(command.provider)
        return providerService.handleSocialLogin(
            code = command.code,
            redirectUri = command.redirectUri,
            provider = command.provider,
        )
    }
}
