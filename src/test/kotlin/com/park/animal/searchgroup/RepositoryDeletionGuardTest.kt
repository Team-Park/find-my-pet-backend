package com.park.animal.searchgroup

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 수색그룹·팀 리포지토리 7종에 delete 계열 호출이 전혀 없음을 정적으로 강제한다.
 *
 * 배경: `SearchGroupRepository`/`SearchGroupMemberRepository`/`SearchGroupUserBlockRepository`/
 * `SearchGroupTeamRepository`/`SearchGroupEventRepository`/`TeamRepository`/`TeamMemberRepository`
 * 는 전부 `JpaRepository` 를 상속하므로 `delete`/`deleteById`/`deleteAll`/`deleteAllById` 가
 * 시그니처상 여전히 호출 가능하다. KDoc 주석("delete / deleteById 를 절대 쓰지 않는다")만으로는
 * Task 3~11 이 실수로 호출하는 것을 막지 못한다. 어떤 엔티티도 `@SQLDelete` 를 갖지 않으므로
 * 그런 호출은 soft-delete 가 아니라 즉시 하드 삭제이고, 특히 `SearchGroupEventRepository` 는
 * 설계 §20 감사 로그라 삭제되면 영구 손실이다.
 *
 * `Repository<T, UUID>` 로 인터페이스를 좁히지 않는 이유: Task 3~11 이 `save`/`findById`/`count`
 * 등을 그대로 쓰는 것이 이 태스크가 확정한 계약이고, 지금 시점에 그 부분집합을 추측해서 좁히는
 * 것은 근거 없는 축소다. 대신 소스를 정적으로 스캔해 delete 계열 호출을 원천 차단한다.
 *
 * ### 리뷰에서 지적된 우회 3종과 대응
 *
 * 최초 버전은 한 줄씩(`readLines()`) `수신자이름.delete(` 형태의 리터럴만 정규식으로 잡았는데,
 * 다음 세 가지로 쉽게 우회됐다:
 * 1. 별칭 변수 — `val repo = searchGroupEventRepository; repo.deleteById(id)` (수신자 이름이 안 보임)
 * 2. 개행으로 쪼갠 호출 — 수신자와 `.deleteById(...)` 가 서로 다른 줄 (한 줄씩 봐서 못 잡음)
 * 3. safe call — `searchGroupEventRepository?.deleteById(id)` (`?.` 는 `\s*\.\s*` 와 불일치)
 *
 * 이를 막기 위해 규칙을 둘로 나눴다.
 *
 * **Rule A(수신자 무관, searchgroup/team 패키지 전용)** — `search_group`/`team` 도메인 파일
 * 안에서는 애초에 delete 계열을 호출할 정당한 이유가 없으므로, 수신자가 무엇이든(별칭이든 뭐든)
 * `.delete(`/`.deleteById(`/`.deleteAll(`/`.deleteAllById(` 형태의 호출 자체를 막는다. 파일
 * 전체 텍스트(줄 단위가 아님)를 정규식으로 훑고, 점(`.`) 앞에 물음표(`?.`)를 허용하며, 점 앞뒤의
 * 공백·개행을 임의 허용한다 — 그래서 위 3종 우회가 전부 이 규칙 하나로 막힌다. 변수명을 아무리
 * 바꿔도(우회 1) 소용없고, 호출을 여러 줄로 쪼개도(우회 2) `\s*` 가 개행을 포함해 잡히고,
 * safe call(우회 3)도 `[?]?` 로 잡힌다.
 *
 * **Rule B(수신자명 리터럴, `src/main/kotlin` 전체)** — 두 패키지 밖(예: 훗날의 `PostService`
 * 수정 등)에서 이 리포지토리 인스턴스를 그대로 가리키는 변수명으로 delete 계열을 호출하는
 * 경우를 잡는 방어망이다. 별칭 우회까지는 못 막지만(그건 도메인 밖이라 Rule A 대상이 아니다),
 * 실수로 리터럴 그대로 쓴 호출은 여기서 잡힌다.
 *
 * 두 규칙 모두 파일 텍스트 전체를 스캔하고, 매칭 위치를 (라인, 컬럼)으로 환산해 리포트한다.
 */
class RepositoryDeletionGuardTest {
    /**
     * Rule A — 수신자와 무관하게 delete 계열 호출 자체를 막는다.
     * `[?]?` 로 safe call(`?.`) 을, `\s*` 로 개행을 포함한 임의 공백(여러 줄로 쪼갠 호출)을 흡수한다.
     */
    private val receiverAgnosticDeleteCallPattern =
        Regex("""[?]?\s*\.\s*(delete|deleteById|deleteAll|deleteAllById)\s*\(""")

    /**
     * Rule B — `searchGroup*Repository`/`team*Repository` 라는 변수명을 리터럴로 호출하는
     * 경우를 잡는다. Rule A 와 마찬가지로 safe call·개행 분할을 허용한다.
     */
    private val namedReceiverDeleteCallPattern =
        Regex("""\b((?:searchGroup|team)\w*Repository)[?]?\s*\.\s*(delete|deleteById|deleteAll|deleteAllById)\s*\(""")

    private fun mainSourceRoot(): File {
        // gradle 은 테스트를 항상 프로젝트 루트에서 실행하므로 상대 경로로 충분하다.
        val root = File("src/main/kotlin")
        require(root.exists() && root.isDirectory) {
            "src/main/kotlin 을 찾지 못했다 (cwd=${File(".").absolutePath}) — 프로젝트 루트에서 실행해야 한다"
        }
        return root
    }

    private fun kotlinFilesUnder(dir: File): List<File> =
        if (!dir.exists()) {
            emptyList()
        } else {
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    /** 문자 오프셋을 1-base (라인, 컬럼) 으로 환산한다 — 실패 리포트가 실제 파일 위치를 가리키게 하기 위해서다. */
    private fun lineAndColumnAt(
        text: String,
        offset: Int,
    ): Pair<Int, Int> {
        var line = 1
        var lastNewlineIndex = -1
        for (i in 0 until offset) {
            if (text[i] == '\n') {
                line++
                lastNewlineIndex = i
            }
        }
        val column = offset - lastNewlineIndex
        return line to column
    }

    private fun scan(
        files: List<File>,
        pattern: Regex,
        ruleLabel: String,
    ): List<String> {
        val offenders = mutableListOf<String>()
        files.forEach { file ->
            val text = file.readText()
            pattern.findAll(text).forEach { match ->
                val (line, column) = lineAndColumnAt(text, match.range.first)
                offenders += "[$ruleLabel] ${file.path}:$line:$column: ${match.value.trim()}"
            }
        }
        return offenders
    }

    @Test
    fun `search_group·team 리포지토리에 delete 계열 호출이 없다 — 수신자 무관 + 리포지토리명 리터럴 이중 검사`() {
        val searchGroupPackage = File("src/main/kotlin/com/park/animal/searchgroup")
        val teamPackage = File("src/main/kotlin/com/park/animal/team")
        val ownedFiles = kotlinFilesUnder(searchGroupPackage) + kotlinFilesUnder(teamPackage)
        require(ownedFiles.isNotEmpty()) { "searchgroup/team 패키지에서 .kt 파일을 찾지 못했다" }

        // Rule A: searchgroup/team 안에서는 수신자가 무엇이든 delete 계열 호출 자체가 위반이다.
        val ruleAOffenders = scan(ownedFiles, receiverAgnosticDeleteCallPattern, "RuleA:수신자무관(searchgroup/team)")

        // Rule B: src/main/kotlin 전체에서 리포지토리 변수명을 literal 로 호출하는 경우를 잡는다.
        val allMainFiles = kotlinFilesUnder(mainSourceRoot())
        val ruleBOffenders = scan(allMainFiles, namedReceiverDeleteCallPattern, "RuleB:리포지토리명리터럴(전체)")

        val offenders = ruleAOffenders + ruleBOffenders
        assertTrue(
            offenders.isEmpty(),
            "delete 계열 호출이 발견됐다 — 상태 전이 조건부 UPDATE 로 대체해야 한다(F15, 설계 §20):\n" +
                offenders.joinToString("\n"),
        )
    }
}
