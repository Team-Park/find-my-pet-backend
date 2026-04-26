package com.park.animal.notification.entity

enum class NotificationType {
    /** 내 게시글에 목격 제보가 등록됨 */
    SIGHTING_REGISTERED,

    /** 내가 즐겨찾기한 게시글의 상태(SEARCHING/FOUND/SEEN)가 변경됨 */
    BOOKMARK_STATUS_CHANGED,
}
