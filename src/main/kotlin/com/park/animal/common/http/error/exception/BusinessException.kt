package com.park.animal.common.http.error.exception

import com.park.animal.common.http.error.ErrorCode

open class BusinessException(open val errorCode: ErrorCode) : RuntimeException(errorCode.message)
