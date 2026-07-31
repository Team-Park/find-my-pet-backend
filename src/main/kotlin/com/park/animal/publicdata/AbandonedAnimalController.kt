package com.park.animal.publicdata

import annotation.PublicEndPoint
import com.park.animal.abandoned.NoticeStatus
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
                "HTTPS 래핑으로 제공. 서비스 키는 서버 내부에만 존재. " +
                "기본은 서버가 OPEN으로 판정한 공고만 반환하며, CLOSED·ALL 필터도 지원한다.",
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
        @Parameter(description = "시도 코드 (예: 6110000 서울특별시)")
        @RequestParam("uprCd", required = false) uprCd: String?,
        @Parameter(description = "시군구 코드")
        @RequestParam("orgCd", required = false) orgCd: String?,
        @Parameter(
            description =
                "공고 상태 OPEN | CLOSED | ALL. 기본 OPEN(진행중). " +
                    "CLOSED는 명시 종료·실효 기간 만료·수용된 상류 제거를 포함하며 상세 조회는 계속 200. " +
                    "인식 불가 값은 OPEN 으로 처리.",
        )
        @RequestParam("noticeStatus", required = false, defaultValue = "OPEN") noticeStatus: String,
    ): PaginatedApiResponseBody<AbandonedAnimalResponse> {
        val page =
            abandonedAnimalService.findAbandonedAnimals(
                animalType = animalType,
                pageNo = pageNo,
                numOfRows = numOfRows,
                bgnde = bgnde,
                endde = endde,
                uprCd = uprCd,
                orgCd = orgCd,
                noticeStatus = NoticeStatus.from(noticeStatus),
            )
        return PaginatedApiResponseBody(
            data =
                PaginatedApiResponseDto(
                    contents = page.contents,
                    hasNextPage = page.hasNextPage,
                    totalCount = page.totalCount,
                ),
        )
    }

    @PublicEndPoint
    @GetMapping("/abandoned-animals/{desertionNo}")
    @Operation(
        summary = "유기동물 단건 조회 (로컬 mirror)",
        description =
            "SEO/SSR 용. desertionNo path param 으로 단건 조회. mirror 에 없는 항목만 404 이고, " +
                "공고가 종료된 항목도 200 으로 반환한다(이미 색인된 URL 을 죽이지 않는다). " +
                "종료 여부는 응답의 noticeClosed / noticeClosedAt 으로 판단한다.",
    )
    suspend fun getOne(
        @org.springframework.web.bind.annotation.PathVariable desertionNo: String,
    ): org.woo.http.SucceededApiResponseBody<AbandonedAnimalResponse> {
        val item = abandonedAnimalService.findByDesertionNo(desertionNo)
            ?: throw com.park.animal.common.http.error.exception.BusinessException(
                com.park.animal.common.http.error.ErrorCode.NOT_FOUND_REQUEST,
            )
        return org.woo.http.SucceededApiResponseBody(data = item)
    }

    @PublicEndPoint
    @GetMapping("/abandoned-animals/sido")
    @Operation(summary = "시도 코드 목록")
    suspend fun getSido(): org.woo.http.SucceededApiResponseBody<List<PublicDataClient.RegionItem>> =
        org.woo.http.SucceededApiResponseBody(data = abandonedAnimalService.findSidoList())

    @PublicEndPoint
    @GetMapping("/abandoned-animals/sigungu")
    @Operation(summary = "시군구 코드 목록 — 상위 시도 코드 필요")
    suspend fun getSigungu(
        @Parameter(description = "시도 코드 (예: 6110000)") @RequestParam("uprCd") uprCd: String,
    ): org.woo.http.SucceededApiResponseBody<List<PublicDataClient.RegionItem>> =
        org.woo.http.SucceededApiResponseBody(data = abandonedAnimalService.findSigunguList(uprCd))
}
