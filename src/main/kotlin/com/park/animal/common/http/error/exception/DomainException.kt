package com.park.animal.common.http.error.exception

import com.park.animal.common.http.error.ErrorCode

open class DomainException(val errorCode: ErrorCode) : RuntimeException()
