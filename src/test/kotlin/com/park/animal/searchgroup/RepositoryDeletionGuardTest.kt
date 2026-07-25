package com.park.animal.searchgroup

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 수색그룹·팀 리포지토리 7종에 delete 계열 호출이 전혀 없음을 정적으로 강제한다.
 *
 * 배경: `SearchGroupRepository`/`SearchGroupMemberRepository`/`SearchGroupUserBlockRepository`/
 * `SearchGroupTeamRepository`/`SearchGroupEventRepository`/`TeamRepository`/`TeamMemberRepository`
 * 는 전부 `JpaRepository` 를 상속하므로 `delete`/`deleteById`/`deleteAll` 이 시그니처상 여전히
 * 호출 가능하다. KDoc 주석("delete / deleteById 를 절대 쓰지 않는다")만으로는 Task 3~11 이
 * 실수로 호출하는 것을 막지 못한다. 어떤 엔티티도 `@SQLDelete` 를 갖지 않으므로 그런 호출은
 * soft-delete 가 아니라 즉시 하드 삭제이고, 특히 `SearchGroupEventRepository` 는 설계 §20 감사
 * 로그라 삭제되면 영구 손실이다.
 *
 * `Repository<T, UUID>` 로 인터페이스를 좁히지 않는 이유: Task 3~11 이 `save`/`findById`/`count`
 * 등을 그대로 쓰는 것이 이 태스크가 확정한 계약이고, 지금 시점에 그 부분집합을 추측해서 좁히는
 * 것은 근거 없는 축소다. 대신 소스를 정적으로 스캔해 delete 계열 호출을 원천 차단한다.
 *
 * 이 테스트는 `src/main/kotlin` 아래 모든 `.kt` 파일을 훑어 `<searchGroup*Repository|team*Repository>
 * .delete(` / `.deleteById(` / `.deleteAll(` 패턴을 찾는다. 리포지토리 인터페이스 파일 자체에는
 * (메서드 선언일 뿐 호출이 아니므로) 매칭되지 않는다 — 매칭되려면 실제 수신자.메서드( 형태의
 * 호출 구문이어야 한다.
 */
class RepositoryDeletionGuardTest {
    private val forbiddenCallPattern =
        Regex("""\b((?:searchGroup|team)\w*Repository)\s*\.\s*(delete|deleteById|deleteAll)\s*\(""")

    private fun mainSourceRoot(): File {
        // gradle 은 테스트를 항상 프로젝트 루트에서 실행하므로 상대 경로로 충분하다.
        val root = File("src/main/kotlin")
        require(root.exists() && root.isDirectory) {
            "src/main/kotlin 을 찾지 못했다 (cwd=${File(".").absolutePath}) — 프로젝트 루트에서 실행해야 한다"
        }
        return root
    }

    @Test
    fun `search_group·team 리포지토리 delete 계열 호출이 소스 어디에도 없다`() {
        val offenders = mutableListOf<String>()

        mainSourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    forbiddenCallPattern.findAll(line).forEach { match ->
                        offenders += "${file.path}:${index + 1}: ${match.value}"
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "delete 계열 호출이 발견됐다 — 상태 전이 조건부 UPDATE 로 대체해야 한다(F15, 설계 §20):\n" +
                offenders.joinToString("\n"),
        )
    }
}
