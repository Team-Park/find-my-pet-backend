package com.park.animal.category

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.category.dto.CategoryResponse
import com.park.animal.category.dto.RegistrationCategoryReqeust
import com.park.animal.common.config.SwaggerConfig
import dto.UserContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class CategoryController(
    val categoryService: CategoryService,
) {
    @PostMapping("/category")
    @Operation(
        summary = "카테고리 등록",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun registerCategory(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
        @RequestBody
        request: RegistrationCategoryReqeust,
    ): SucceededApiResponseBody<Unit> {
        categoryService.register(name = request.name, parentId = request.parentId)
        return SucceededApiResponseBody.succeed()
    }

    @PublicEndPoint
    @GetMapping("/categories")
    @Operation(
        summary = "카테고리 전체 조회",
    )
    fun getCategories(): SucceededApiResponseBody<List<CategoryResponse>> {
        val response = categoryService.findByAll()
        return SucceededApiResponseBody(data = response)
    }

    @PublicEndPoint
    @GetMapping("/category/child/{parentId}")
    @Operation(
        summary = "sub 카테고리 조회",
        description = "부모 id 로 하위 카테고리를 조회 합니다.",
    )
    fun getChildCategory(
        @PathVariable("parentId")
        parentId: UUID,
    ): SucceededApiResponseBody<List<CategoryResponse>> {
        val response = categoryService.findByParentId(parentId)
        return SucceededApiResponseBody(data = response)
    }
}
