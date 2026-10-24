package com.trackspeed.android.ui.screens.help

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateFeedbackDraftStoreTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun failedSubmissionDraftAndIdSurviveReopenWithoutAutoSend() = runBlocking {
        val root = folder.newFolder(); val store = PrivateFeedbackDraftStore(root) { 100 }
        val draft = PrivateFeedbackDraft(kind = PrivateFeedbackKind.BUG, details = "Saved", updatedAtMillis = 100).beginAttempt(emptyMap(), 100)
        store.save(draft)
        assertEquals(draft, PrivateFeedbackDraftStore(root) { 100 }.load(PrivateFeedbackKind.BUG))
        assertEquals(1, root.listFiles()!!.size)
    }
    @Test fun expiryRemovesOnlyOldKindAndItsAttachments() = runBlocking {
        val store = PrivateFeedbackDraftStore(folder.newFolder()) { PrivateFeedbackDraftStore.TTL_MILLIS + 200 }
        store.save(PrivateFeedbackDraft(kind = PrivateFeedbackKind.BUG, details = "Old", updatedAtMillis = 100))
        store.save(PrivateFeedbackDraft(kind = PrivateFeedbackKind.QUESTION, details = "New", updatedAtMillis = 300))
        assertNull(store.load(PrivateFeedbackKind.BUG)); assertNotNull(store.load(PrivateFeedbackKind.QUESTION))
    }
    @Test fun lateReceiptCannotRemoveEditedDraft() = runBlocking {
        val store = PrivateFeedbackDraftStore(folder.newFolder()) { 100 }
        val old = PrivateFeedbackDraft(kind = PrivateFeedbackKind.IMPROVEMENT, details = "First", updatedAtMillis = 100).beginAttempt(emptyMap(), 100)
        val edited = old.edited(old.copy(details = "Second"), 102)
        store.save(edited); store.remove(old.kind, old.id)
        assertEquals(edited, store.load(old.kind))
        store.remove(edited.kind, edited.id); assertNull(store.load(old.kind))
    }
    @Test fun delayedSaveDoesNotOverwriteNewerDraft() = runBlocking {
        val store = PrivateFeedbackDraftStore(folder.newFolder()) { 100 }
        val old = PrivateFeedbackDraft(kind = PrivateFeedbackKind.BUG, details = "Old", updatedAtMillis = 100)
        val newer = old.edited(old.copy(details = "New"), 100)
        store.save(newer); store.save(old); assertEquals(newer, store.load(old.kind))
    }
    @Test fun excessiveAttachmentCountRejectedBeforeWrite() = runBlocking {
        val store = PrivateFeedbackDraftStore(folder.newFolder()) { 100 }
        val draft = PrivateFeedbackDraft(kind = PrivateFeedbackKind.BUG, attachments = List(4) { PrivateFeedbackAttachment(base64 = "AA==") })
        try { store.save(draft); fail("Accepted over limit") } catch (_: IllegalArgumentException) { }
        assertNull(store.load(draft.kind))
    }
}
