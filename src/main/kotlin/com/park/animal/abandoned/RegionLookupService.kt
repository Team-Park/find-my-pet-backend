package com.park.animal.abandoned

import com.park.animal.publicdata.PublicDataClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * v2 응답의 `orgNm` ("경상남도 거창군") 텍스트를 (uprCd, orgCd) 쌍으로 매핑.
 *
 * 매핑 데이터는 sido_v2 + 각 시도별 sigungu_v2 응답으로 만들어지며 24h 캐시.
 */
@Service
class RegionLookupService(
    private val publicDataClient: PublicDataClient,
) {
    companion object {
        private const val TTL_MS = 24L * 60 * 60 * 1000
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = AtomicReference<CacheEntry?>(null)

    data class RegionCode(val uprCd: String, val orgCd: String?)

    private data class CacheEntry(
        val builtAt: Long,
        val byOrgNm: Map<String, RegionCode>,
        val bySidoOnly: Map<String, RegionCode>,
    )

    /** orgNm("경상남도 거창군") → (uprCd, orgCd). 시군구 매칭 실패 시 시도만 매칭. 둘 다 실패 시 null. */
    fun lookup(orgNm: String?): RegionCode? {
        if (orgNm.isNullOrBlank()) return null
        val map = ensureCache()
        map.byOrgNm[orgNm]?.let { return it }
        // fallback: orgNm 의 첫 토큰(시도명) 만 매칭
        val sido = orgNm.substringBefore(' ').trim()
        return map.bySidoOnly[sido]
    }

    private fun ensureCache(): CacheEntry {
        val current = cache.get()
        if (current != null && System.currentTimeMillis() - current.builtAt < TTL_MS) {
            return current
        }
        return rebuildCache()
    }

    @Synchronized
    private fun rebuildCache(): CacheEntry {
        val current = cache.get()
        if (current != null && System.currentTimeMillis() - current.builtAt < TTL_MS) {
            return current
        }
        return runBlocking {
            try {
                val sidoList = publicDataClient.fetchSidoList()
                val byOrgNm = mutableMapOf<String, RegionCode>()
                val bySidoOnly = mutableMapOf<String, RegionCode>()

                for (sido in sidoList) {
                    val sidoOrgCd = sido.orgCd ?: continue
                    val sidoName = sido.orgdownNm ?: continue
                    bySidoOnly[sidoName] = RegionCode(uprCd = sidoOrgCd, orgCd = null)
                    byOrgNm[sidoName] = RegionCode(uprCd = sidoOrgCd, orgCd = null)

                    val sigunguList = publicDataClient.fetchSigunguList(sidoOrgCd)
                    for (sigungu in sigunguList) {
                        val sigunguName = sigungu.orgdownNm ?: continue
                        val sigunguOrgCd = sigungu.orgCd ?: continue
                        byOrgNm["$sidoName $sigunguName"] =
                            RegionCode(uprCd = sidoOrgCd, orgCd = sigunguOrgCd)
                    }
                }

                val entry = CacheEntry(System.currentTimeMillis(), byOrgNm, bySidoOnly)
                cache.set(entry)
                log.info("region lookup cache built: {} sido + sigungu mappings", byOrgNm.size)
                entry
            } catch (e: Exception) {
                log.error("region cache rebuild failed", e)
                // 빈 캐시지만 짧은 TTL 로 재시도 가능하게.
                CacheEntry(System.currentTimeMillis() - (TTL_MS - 60_000), emptyMap(), emptyMap())
            }
        }
    }
}
