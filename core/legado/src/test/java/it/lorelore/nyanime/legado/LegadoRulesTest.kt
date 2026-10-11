package it.lorelore.nyanime.legado

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class LegadoRulesTest {
    @Test
    @Timeout(5)
    fun fanoutIsRejectedDuringSplittingBeforeAnUnboundedTreeIsAllocated() {
        val raw = List(10000) { "tag.a@text" }.joinToString("||")
        assertEquals(
            "rule_fanout",
            assertThrows(LegadoRuleException::class.java) {
                LegadoRules.parse(raw)
            }
                .capability,
        )
    }

    @Test
    fun explicitModesApplyToEveryCombinedBranch() {
        val parsed = LegadoRules.parse("@css:a@text||tag.foo@text") as LegadoRule.Combine
        assertEquals(
            "tag.foo",
            (parsed.rules[1] as LegadoRule.Select).steps.single().selector,
        )
        assertThrows(LegadoRuleException::class.java) {
            LegadoRules.parse("tag.a@text&&tag.b@text||tag.c@text")
        }
    }

    @Test
    fun defaultSelectorsPreserveChainedSelectionAndIndexes() {
        val parsed = LegadoRules.parse("class.card.0@tag.a.-1@href") as LegadoRule.Select
        assertEquals(listOf(".card", "a"), parsed.steps.map { it.selector })
        assertEquals(listOf(0), parsed.steps[0].indexes)
        assertEquals(listOf(-1), parsed.steps[1].indexes)
        assertEquals("href", parsed.attribute)
    }

    @Test
    fun listReversalExclusionsAndSlicesStayExplicit() {
        val reversed = LegadoRules.parse("-tag.li!0:2", list = true) as LegadoRule.Select
        assertTrue(reversed.reverse)
        assertEquals(setOf(0, 2), reversed.steps.single().exclusions)
        val sliced = LegadoRules.parse("@css:.row[0:10:2]", list = true) as LegadoRule.Select
        assertEquals(listOf(0, 10, 2), sliced.steps.single().slice)
        assertThrows(LegadoRuleException::class.java) { LegadoRules.parse("tag.li[0:10:0]") }
    }

    @Test
    fun quotedOperatorsAndAttributeSeparatorsAreData() {
        val rule = LegadoRules.parse("@css:a[data-x='x||y@z']@href") as LegadoRule.Select
        assertEquals("a[data-x='x||y@z']", rule.steps.single().selector)
        assertEquals("href", rule.attribute)
        val combined = LegadoRules.parse("tag.a@text||tag.span@text") as LegadoRule.Combine
        assertEquals(LegadoCombination.FIRST_AVAILABLE, combined.kind)
        assertEquals(2, combined.rules.size)
    }

    @Test
    fun jsonAndXpathStayDistinctFromHtmlAndUnsupportedPathsAreRejected() {
        val json = LegadoRules.parse("$.books[*].title") as LegadoRule.Select
        assertEquals(LegadoSelectionMode.JSON, json.mode)
        val xpath = LegadoRules.parse("//a/@href") as LegadoRule.Select
        assertEquals(LegadoSelectionMode.XPATH, xpath.mode)
        assertEquals(
            "json_path",
            assertThrows(LegadoRuleException::class.java) {
                LegadoRules.parse("$..books")
            }
                .capability,
        )
    }

    @Test
    fun regexReplacementAndSingleMatchStayDifferent() {
        val all = LegadoRules.parse("tag.p@text##old##new") as LegadoRule.Replace
        assertFalse(all.firstOnly)
        assertEquals("old", all.pattern)
        assertEquals("new", all.replacement)
        val first = LegadoRules.parse("##a(.)##$1###") as LegadoRule.Replace
        assertTrue(first.firstOnly)
        assertEquals(null, first.input)
        assertThrows(LegadoRuleException::class.java) { LegadoRules.parse("text##(?<=bad)x##") }
    }

    @Test
    fun javascriptIsUnevaluatedAndHasAnExplicitInput() {
        val result = LegadoRules.parse("tag.p@text@js:result.toUpperCase()") as LegadoRule.Script
        assertTrue(result.input is LegadoRule.Select)
        assertEquals("result.toUpperCase()", result.code)
        assertThrows(LegadoRuleException::class.java) { LegadoRules.parse("@js:Packages.android") }
        assertThrows(LegadoRuleException::class.java) { LegadoRules.parse("@get:token") }
    }

    @Test
    fun invalidAndUnsupportedRulesNeverBecomeNoOps() {
        for (
        raw in
        listOf(
            "@css:[bad",
            ":all-in-one",
            "text##a##b##c##d",
            "<js>x</js>more",
            "@css:p:matches((a+)+)",
        )
        ) {
            assertThrows(LegadoRuleException::class.java) { LegadoRules.parse(raw) }
        }
        assertThrows(LegadoRuleException::class.java) {
            LegadoRules.parse("tag.a.99999999999999999999")
        }
    }
}
