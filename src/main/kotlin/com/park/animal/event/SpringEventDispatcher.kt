package com.park.animal.event

import com.park.animal.common.constants.RecordOperation.DELETE
import com.park.animal.common.constants.RecordOperation.INSERT
import com.park.animal.common.constants.RecordOperation.UPDATE
import com.park.animal.event.dto.UpdateNameEvent
import com.park.animal.post.PostService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.Executors

@Component
class SpringEventDispatcher(
    val postService: PostService,
) {
    private val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(executor)

    @EventListener
    fun handle(event: DomainEvent) {
        scope.launch {
            when (event) {
                is UpdateNameEvent -> handleUpdateName(event)
            }
        }
    }

    suspend fun handleUpdateName(event: UpdateNameEvent) {
        val userId = UUID.fromString(event.userId)
        when (event.recordOperation) {
            UPDATE -> {
                postService.updateAuthor(userId, event.name)
            }

            DELETE -> {
                postService.deleteAuthor(userId)
            }

            INSERT -> {}
        }
    }
}
