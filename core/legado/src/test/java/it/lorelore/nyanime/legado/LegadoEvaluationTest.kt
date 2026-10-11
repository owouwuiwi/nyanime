package it.lorelore.nyanime.legado

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegadoEvaluationTest {
    @Test
    fun failuresFromALibraryOrPortDoNotExposeTheirInputOrCause() = runTest {
        val scripted =
            LegadoEvaluation(
                LegadoScript { _, _, _ ->
                    throw IllegalArgumentException("private-fixture-credential")
                },
            )
        val failure =
            try {
                scripted.values(LegadoRule.Script(null, "sample"), LegadoInput.text(""))
                null
            } catch (failure: LegadoRuleException) {
                failure
            }
        assertEquals("rule_execution", failure?.capability)
        assertEquals(null, failure?.cause)
        assertFalse(failure.toString().contains("private-fixture-credential"))
    }

    private val evaluator = LegadoEvaluation()
    private val empty = LegadoInput.text("")

    @Test
    fun indexedStepsApplyInsideEveryParentAndFieldsRetainTheirRow() = runTest {
        val input =
            LegadoInput.html(
                "<div class='book'><a href='/one'>One</a><a>Extra</a></div>" +
                    "<div class='book'><a href='/two'>Two</a></div>",
            )
        val rows = evaluator.values(LegadoRules.parse("class.book", list = true), input)
        assertEquals(2, rows.size)
        assertEquals(
            listOf("One", "Two"),
            rows.map { evaluator.strings(LegadoRules.parse("tag.a.0@text"), it).single() },
        )
        assertEquals(
            listOf("/one", "/two"),
            evaluator.strings(LegadoRules.parse("class.book@tag.a.0@href"), input),
        )
    }

    @Test
    fun htmlRemovesActiveElementsWithoutMutatingTheContext() = runTest {
        val input =
            LegadoInput.html("<div id='body'>Text<script>sample()</script><style>p{}</style></div>")
        val html = evaluator.strings(LegadoRules.parse("@css:#body@html"), input).single()
        assertTrue(html.startsWith("<div"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<style"))
        assertEquals(1, evaluator.values(LegadoRules.parse("@css:script", list = true), input).size)
    }

    @Test
    fun ownTextAndTextNodesRemainDifferentFromDescendants() = runTest {
        val input = LegadoInput.html("<p>First <b>bold</b> last</p>")
        assertEquals(
            listOf("First bold last"),
            evaluator.strings(LegadoRules.parse("tag.p@text"), input),
        )
        assertEquals(
            listOf("First last"),
            evaluator.strings(LegadoRules.parse("tag.p@ownText"), input),
        )
        assertEquals(
            listOf("First\nlast"),
            evaluator.strings(LegadoRules.parse("tag.p@textNodes"), input),
        )
    }

    @Test
    fun firstAvailableIsLazyAndDoesNotCallUnauthorizedScriptFallback() = runTest {
        val rule =
            LegadoRule.Combine(
                LegadoCombination.FIRST_AVAILABLE,
                listOf(LegadoRules.parse("tag.p@text"), LegadoRule.Script(null, "never")),
            )
        assertEquals(listOf("Found"), evaluator.strings(rule, LegadoInput.html("<p>Found</p>")))
    }

    @Test
    fun listsCombineInDeclaredOrderAndInterleaveEndsAtTheFirstBranch() = runTest {
        val html = LegadoInput.html("<a>A1</a><a>A2</a><b>B1</b><b>B2</b><b>B3</b>")
        assertEquals(
            listOf("A1", "A2", "B1", "B2", "B3"),
            evaluator.strings(LegadoRules.parse("tag.a@text&&tag.b@text"), html),
        )
        assertEquals(
            listOf("A1", "B1", "A2", "B2"),
            evaluator.strings(LegadoRules.parse("tag.a@text%%tag.b@text"), html),
        )
    }

    @Test
    fun slicingUsesInclusiveLegacyBoundsAndCanReverseWithoutChangingRows() = runTest {
        val html = LegadoInput.html("<i>0</i><i>1</i><i>2</i><i>3</i><i>4</i>")
        assertEquals(
            listOf("0", "2", "4"),
            evaluator.strings(LegadoRules.parse("tag.i[0:4:2]@text"), html),
        )
        assertEquals(
            listOf("4", "3", "2", "1", "0"),
            evaluator.strings(LegadoRules.parse("tag.i[-1:0]@text"), html),
        )
        assertEquals(
            listOf("0", "2", "3"),
            evaluator.strings(LegadoRules.parse("tag.i!1:-1@text"), html),
        )
    }

    @Test
    fun overflowingIndexesCannotWrapAndHugeStepsDoNotLoop() = runTest {
        val html = LegadoInput.html("<i>0</i><i>1</i>")
        assertTrue(evaluator.strings(LegadoRules.parse("tag.i.-2147483648@text"), html).isEmpty())
        assertEquals(
            listOf("0"),
            evaluator.strings(LegadoRules.parse("tag.i[0:1:2147483647]@text"), html),
        )
    }

    @Test
    fun jsonRowsStayStructuredAndNullsNeverBecomeLiteralNullText() = runTest {
        val input =
            LegadoInput.json("""{"books":[{"name":"One"},{"name":null},{"name":"Three"}]}""")
        val rows = evaluator.values(LegadoRules.parse("$.books[*]", list = true), input)
        assertEquals(3, rows.size)
        assertEquals(listOf("One"), evaluator.strings(LegadoRules.parse("$.name"), rows[0]))
        assertTrue(evaluator.strings(LegadoRules.parse("$.name"), rows[1]).isEmpty())
    }

    @Test
    fun xpathSupportsAttributesAndPreservesElementContext() = runTest {
        val input =
            LegadoInput.html(
                "<div class='book'><a href='/one'>One</a></div><div class='book'><a href='/two'>Two</a></div>",
            )
        assertEquals(
            listOf("/one", "/two"),
            evaluator.strings(LegadoRules.parse("//a/@href"), input),
        )
        val rows = evaluator.values(LegadoRules.parse("//div[@class='book']", list = true), input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Two"), evaluator.strings(LegadoRules.parse("tag.a@text"), rows.last()))
    }

    @Test
    fun singleMatchExtractsOnlyTheReplacementWhileNormalReplacementKeepsUnmatchedText() = runTest {
        val input = LegadoInput.text("prefix AB12 suffix AB34")
        assertEquals(
            listOf("12"),
            evaluator.strings(LegadoRules.parse("##AB([0-9]+)##$1###"), input),
        )
        assertEquals(
            listOf("prefix N12 suffix N34"),
            evaluator.strings(LegadoRules.parse("##AB([0-9]+)##N$1"), input),
        )
        assertTrue(evaluator.strings(LegadoRules.parse("##missing##found###"), input).isEmpty())
    }

    @Test
    fun replacementExpansionHasABoundedOutput() = runTest {
        val input = LegadoInput.text("a".repeat(5000))
        val rule = LegadoRule.Replace(null, "a", "x".repeat(1000))
        val failure =
            try {
                evaluator.strings(rule, input)
                null
            } catch (failure: LegadoRuleException) {
                failure
            }
        assertEquals("result_size", failure?.capability)
    }

    @Test
    fun scriptPortReceivesOnlyDeclaredJsonAndIsNeverInvokedWithoutAPort() = runTest {
        val rule = LegadoRules.parse("@css:p@text@js:result.toUpperCase()")
        var calls = 0
        val scripted =
            LegadoEvaluation(
                LegadoScript { code, result, variables ->
                    calls++
                    assertEquals("result.toUpperCase()", code)
                    assertEquals(JsonPrimitive("hello"), result)
                    assertEquals(setOf("page"), variables.keys)
                    JsonArray(listOf(JsonPrimitive("HELLO")))
                },
            )
        val input = LegadoInput.html("<p>hello</p>")
        assertEquals(
            listOf("HELLO"),
            scripted.strings(rule, input, JsonObject(mapOf("page" to JsonPrimitive(2)))),
        )
        assertEquals(1, calls)
        val failure =
            try {
                evaluator.strings(rule, input)
                null
            } catch (failure: LegadoRuleException) {
                failure
            }
        assertEquals("script_not_authorized", failure?.capability)
    }

    @Test
    fun cancellationPropagatesThroughTheSuspendedPortWithoutLateResults() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val scripted =
            LegadoEvaluation(
                LegadoScript { _, _, _ ->
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                },
            )
        val job = async { scripted.values(LegadoRule.Script(null, "wait"), empty) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertTrue(job.isCancelled)
    }

    @Test
    fun oversizedAndDeepInputsAreRejectedWithoutQuotingContent() {
        assertThrows(LegadoRuleException::class.java) {
            LegadoInput.html(" ".repeat(LegadoInput.MAX_CHARACTERS + 1))
        }
        assertThrows(LegadoRuleException::class.java) {
            LegadoInput.json("[".repeat(65) + "0" + "]".repeat(65))
        }
        val secret = "private-test-value"
        val failure = assertThrows(LegadoRuleException::class.java) { LegadoInput.json(secret) }
        assertFalse(failure.toString().contains(secret))
    }

    @Test
    fun excessiveSelectionsFailWithoutReturningATruncatedSuccess() = runTest {
        val html = LegadoInput.html("<p>text</p>".repeat(LegadoEvaluation.MAX_VALUES + 1))
        val failure =
            try {
                evaluator.values(LegadoRules.parse("tag.p", list = true), html)
                null
            } catch (failure: LegadoRuleException) {
                failure
            }
        assertEquals("result_count", failure?.capability)
    }
}
