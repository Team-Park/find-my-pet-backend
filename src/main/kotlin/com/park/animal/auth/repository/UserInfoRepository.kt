package com.park.animal.auth.repository

import com.park.animal.auth.entity.UserInfo
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserInfoRepository : JpaRepository<UserInfo, UUID>
