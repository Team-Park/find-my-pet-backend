package com.park.animal.common.constants

import org.springframework.data.domain.Sort

enum class OrderBy {
    CREATED_AT_DESC,
    UPDATED_AT_DESC,
    CREATED_AT_ASC,
//    NAME_ASC,
//    POPULAR_ASC,
//    ID_ASC,
    ;

    fun toSort(): Sort =
        when (this) {
            CREATED_AT_ASC -> Sort.by(Sort.Direction.ASC, "created_at")
            UPDATED_AT_DESC -> Sort.by(Sort.Direction.DESC, "updated_at")
            CREATED_AT_DESC -> Sort.by(Sort.Direction.DESC, "created_at")
        }
}
