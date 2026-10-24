package com.trackspeed.android.cloud.ideas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class IdeasBoardModelsTest {
    @Test fun publicSubmissionTrimsCapsUnicodeAndNeverAddsAccountFields() {
        val result = IdeaDraft("  ${"🏃".repeat(110)}  ", " ${"x".repeat(610)} ", IdeaCategory.IMPROVEMENT, "  ${"a".repeat(65)}  ").submission()!!
        assertEquals(100, result.title.codePointLength())
        assertEquals(600, result.description.length)
        assertEquals(60, result.publicName!!.length)
        assertFalse(Character.isHighSurrogate(result.title.last()))
        val payload = Json.parseToJsonElement(Json.encodeToString(IdeaSubmission.serializer(), result)).jsonObject
        assertEquals(setOf("title", "description", "category", "author_name"), payload.keys)
        assertEquals("improvement", result.category)
    }

    @Test fun onlyFeatureAndImprovementCanBeSubmittedAndNameDefaultsBlank() {
        assertNull(IdeaDraft(" ", "details").submission())
        assertNull(IdeaDraft("title", "\n").submission())
        assertNull(IdeaDraft("title", "details", IdeaCategory.BUG).submission())
        assertNull(IdeaDraft("title", "details", IdeaCategory.ALL).submission())
        assertNull(IdeaDraft("title", "details").submission()!!.publicName)
        assertEquals(listOf(IdeaCategory.FEATURE, IdeaCategory.IMPROVEMENT), IdeaCategory.submissionCategories)
    }

    @Test fun mappingUsesLatestMeaningfulAdminReplyAndDoesNotInventDates() {
        val posts = mapIdeaPosts(listOf(
            IdeaPostRow("a", "First", category = "feature", status = "unexpected", votes = -10, publicName = " ", createdAt = "bad-date"),
            IdeaPostRow("b", "Second", category = "bug", createdAt = "2026-10-24T12:00:00.123456Z"),
            IdeaPostRow("hidden", "Unknown", category = "invalid", createdAt = "bad-date")
        ), listOf(IdeaAdminRow("a", " Earlier ", "2026-01-01T00:00:00Z"),
            IdeaAdminRow("a", "Latest", "2026-02-01T00:00:00Z"), IdeaAdminRow("a", " ", "2026-03-01T00:00:00Z")))
        assertEquals(2, posts.size)
        assertEquals("Latest", posts[0].adminResponse)
        assertNull(posts[0].createdAt)
        assertNull(posts[0].publicName)
        assertEquals(0, posts[0].votes)
        assertEquals(IdeaStatus.OPEN, posts[0].status)
        assertEquals(Instant.parse("2026-10-24T12:00:00.123456Z"), posts[1].createdAt)
    }

    @Test fun votesSortBreaksTiesByDateAndCategoryFilteringDoesNotMutateSource() {
        val a = IdeaPost("a", "A", "Details", IdeaCategory.FEATURE, votes = 2, createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        val b = a.copy(id = "b", category = IdeaCategory.BUG, createdAt = Instant.parse("2026-02-01T00:00:00Z"))
        val c = a.copy(id = "c", votes = 4)
        val source = listOf(a, b, c)
        assertEquals(listOf("c", "b", "a"), visibleIdeas(source, IdeaCategory.ALL, IdeaSort.MOST_VOTED).map { it.id })
        assertEquals(listOf("b", "a", "c"), visibleIdeas(source, IdeaCategory.ALL, IdeaSort.NEWEST).map { it.id })
        assertEquals(listOf("b"), visibleIdeas(source, IdeaCategory.BUG, IdeaSort.NEWEST).map { it.id })
        assertEquals(listOf(a, b, c), source)
    }
}
