package com.park.animal.publicdata

import annotation.PublicEndPoint
import com.park.animal.publicdata.dto.AbandonedAnimalResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.PaginatedApiResponseBody
import org.woo.http.PaginatedApiResponseDto

@RestController
@RequestMapping("/api/v1")
class AbandonedAnimalController(
    private val abandonedAnimalService: AbandonedAnimalService,
) {
    @PublicEndPoint
    @GetMapping("/abandoned-animals")
    @Operation(
        summary = "구조(유기)동물 목록 — 공공데이터 proxy",
        description =
            "국가동물보호정보시스템(data.go.kr) 구조동물 조회 서비스를 백엔드 캐싱(Redis 5분) + " +
                "HTTPS 래핑으로 제공. 서비스 키는 서버 내부에만 존재.",
    )
    suspend fun getAbandonedAnimals(
        @Parameter(description = "DOG | CAT | OTHER — 생략 시 전체")
        @RequestParam("animalType", required = false) animalType: String?,
        @RequestParam("pageNo", required = false, defaultValue = "1") pageNo: Int,
        @RequestParam("numOfRows", required = false, defaultValue = "20") numOfRows: Int,
        @Parameter(description = "발견 시작일 YYYYMMDD", example = "20260401")
        @RequestParam("bgnde", required = false) bgnde: String?,
        @Parameter(description = "발견 종료일 YYYYMMDD", example = "20260421")
        @RequestParam("endde", required = false) endde: String?,
    ): PaginatedApiResponseBody<AbandonedAnimalResponse> {
        val page = abandonedAnimalService.findAbandonedAnimals(animalType, pageNo, numOfRows, bgnde, endde)
        return PaginatedApiResponseBody(
            data =
                PaginatedApiResponseDto(
                    contents = page.contents,
                    hasNextPage = page.hasNextPage,
                    totalCount = page.totalCount,
                ),
        )
    }
}
