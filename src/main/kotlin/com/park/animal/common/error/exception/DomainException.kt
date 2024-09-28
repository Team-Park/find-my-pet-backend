package com.park.animal.common.error.exception

import com.park.animal.common.error.ErrorCode

open class DomainException(val errorCode: ErrorCode) : RuntimeException()
