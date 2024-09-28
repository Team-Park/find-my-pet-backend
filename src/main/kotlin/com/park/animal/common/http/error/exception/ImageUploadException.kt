package com.park.animal.common.http.error.exception

import com.park.animal.common.http.error.ErrorCode

data class ImageUploadException(
    override val errorCode: ErrorCode = ErrorCode.FAILURE_UPLOAD_IMAGE
): BusinessException(errorCode = errorCode)
