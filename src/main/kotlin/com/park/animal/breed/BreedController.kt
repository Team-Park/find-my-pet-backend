package com.park.animal.breed

import annotation.PublicEndPoint
import com.park.animal.breed.dto.BreedResponse
import com.park.animal.breed.entity.AnimalType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class BreedController(
    val breedService: BreedService,
) {
    @PublicEndPoint
    @GetMapping("/breeds")
    @Operation(
        summary = "품종 목록 조회",
        description = "animalType 필터 (DOG/CAT/OTHER). 생략 시 전체 반환.",
    )
    fun getBreeds(
        @Parameter(
            `in` = ParameterIn.QUERY,
            description = "DOG | CAT | OTHER (생략 가능)",
            required = false,
        )
        @RequestParam(name = "animalType", required = false)
        animalType: AnimalType?,
    ): SucceededApiResponseBody<List<BreedResponse>> {
        val response = breedService.findAll(animalType)
        return SucceededApiResponseBody(data = response)
    }

    @PublicEndPoint
    @GetMapping("/breeds/{id}")
    @Operation(summary = "품종 상세 조회")
    fun getBreed(
        @PathVariable("id") id: UUID,
    ): SucceededApiResponseBody<BreedResponse> {
        val response = breedService.findById(id)
        return SucceededApiResponseBody(data = response)
    }
}
