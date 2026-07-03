package dev.lezli.hotrulez

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import dev.lezli.hotrulez.parameterinfo.FirebaseRulesParameterInfoHandler
import dev.lezli.hotrulez.parameterinfo.FirebaseRulesParameterInfoHandler.SignaturePresentation

/**
 * Parameter info (the `fn(|)` hint popup) for Firebase Rules calls. The handler is
 * driven with the platform mock contexts
 * ([MockCreateParameterInfoContext] / [MockUpdateParameterInfoContext]); both read
 * their offset from the fixture editor's caret, so `<caret>` placement inside the
 * argument list is what selects the call and the current parameter.
 *
 * Coverage: user-function signatures (params, no note), the current-parameter index
 * advancing across a top-level comma, bare `get(`/`exists(` and cross-service
 * `firestore.get(` path helpers (with their doc note), dialect awareness (a Storage
 * file), no signature for a deferred global-namespace call (`math.abs`), and graceful
 * behaviour on a malformed argument list.
 */
class FirebaseRulesParameterInfoTest : BasePlatformTestCase() {

    private val handler = FirebaseRulesParameterInfoHandler()

    // --- User functions --------------------------------------------------

    fun testUserFunctionSignatureHasParamsAndNoNote() {
        val items = itemsAt(
            inDocuments(
                """
                function isOwner(uid) { return request.auth.uid == uid; }
                match /cities/{city} {
                  allow read: if isOwner(<caret>city);
                }
                """,
            ),
        )
        assertNotNull("expected a signature for the user function call", items)
        assertEquals("exactly one signature for a single declaration", 1, items!!.size)
        assertEquals(listOf("uid"), items[0].params)
        assertNull("user-function signatures carry no note", items[0].note)
    }

    fun testCurrentParameterAdvancesAcrossTopLevelComma() {
        // Caret sits after the first top-level comma → current parameter index 1.
        myFixture.configureByText(
            FirebaseRulesFileType,
            inDocuments(
                """
                function grant(a, b) { return a == b; }
                match /cities/{city} {
                  allow read: if grant(city, <caret>city);
                }
                """,
            ),
        )
        val ctx = MockUpdateParameterInfoContext(myFixture.editor, myFixture.file)
        val argumentList = handler.findElementForUpdatingParameterInfo(ctx)
        assertNotNull("expected the enclosing argument list", argumentList)
        handler.updateParameterInfo(argumentList!!, ctx)
        assertEquals("caret past the first comma selects the second parameter", 1, ctx.currentParameter)
    }

    // --- Bare path helpers -----------------------------------------------

    fun testBareGetHelperSignature() {
        val items = itemsAt(inDocuments("match /c/{id} { allow read: if get(<caret>) != null; }"))
        assertNotNull(items)
        assertEquals(1, items!!.size)
        assertEquals(listOf("path"), items[0].params)
        assertNotNull("the bare helper carries a doc note", items[0].note)
        assertTrue(
            "note should carry the get(path) one-liner, got ${items[0].note}",
            items[0].note!!.contains("get(path)"),
        )
    }

    fun testBareExistsHelperSignature() {
        val items = itemsAt(inDocuments("match /c/{id} { allow read: if exists(<caret>); }"))
        assertNotNull(items)
        assertEquals(1, items!!.size)
        assertEquals(listOf("path"), items[0].params)
        assertTrue(
            "note should carry the exists(path) one-liner, got ${items[0].note}",
            items[0].note!!.contains("exists(path)"),
        )
    }

    // --- Dialect awareness: cross-service helper in a Storage file --------

    fun testFirestoreGetInStorageFileSignature() {
        val items = itemsAt(
            inStorage(
                """
                match /files/{fileId} {
                  allow read: if firestore.get(<caret>) != null;
                }
                """,
            ),
        )
        assertNotNull("firestore.get is a cross-service helper in Storage rules", items)
        assertEquals(1, items!!.size)
        assertEquals(listOf("path"), items[0].params)
    }

    fun testCrossServiceFirestoreGetOffersNoSignatureInFirestoreFile() {
        // firestore.get is a Cloud Storage cross-service helper; in a Cloud Firestore file
        // `firestore` is not a namespace, so no signature must be offered.
        val items = itemsAt(inDocuments("match /c/{id} { allow read: if firestore.get(<caret>) != null; }"))
        assertNull("firestore.get offers no parameter info inside a Cloud Firestore file", items)
    }

    // --- Deferred global-namespace call ----------------------------------

    fun testGlobalNamespaceCallOffersNoSignature() {
        myFixture.configureByText(
            FirebaseRulesFileType,
            inDocuments("match /c/{id} { allow read: if math.abs(<caret>-3) > 1; }"),
        )
        val ctx = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        assertNull(
            "namespace-function signatures (math.abs) are deferred — no items",
            handler.findElementForParameterInfo(ctx),
        )
    }

    // --- Recovery --------------------------------------------------------

    fun testMalformedArgumentListDoesNotThrow() {
        // An unclosed call inside an unterminated condition must never throw when the
        // handler probes it; whatever it returns (a partial argument list or nothing)
        // is acceptable so long as no exception escapes.
        myFixture.configureByText(
            FirebaseRulesFileType,
            inDocuments(
                """
                function grant(a, b) { return a == b; }
                match /cities/{city} {
                  allow read: if grant(city, ,<caret>
                }
                """,
            ),
        )
        val createCtx = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val argumentList = handler.findElementForParameterInfo(createCtx)
        if (argumentList != null) {
            handler.showParameterInfo(argumentList, createCtx)
        }
        val updateCtx = MockUpdateParameterInfoContext(myFixture.editor, myFixture.file)
        handler.findElementForUpdatingParameterInfo(updateCtx)?.let {
            handler.updateParameterInfo(it, updateCtx)
        }
        // Reaching here without an exception is the assertion.
    }

    // --- Helpers ---------------------------------------------------------

    /**
     * Configures [text] and returns the signatures the handler would show for the call
     * at the caret, or `null` when the handler offers none (its
     * `findElementForParameterInfo` returns null for an empty item set).
     */
    private fun itemsAt(text: String): List<SignaturePresentation>? {
        myFixture.configureByText(FirebaseRulesFileType, text)
        val ctx = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        handler.findElementForParameterInfo(ctx) ?: return null
        return ctx.itemsToShow!!.map { it as SignaturePresentation }
    }

    private fun inDocuments(body: String): String =
        "rules_version = '2';\n" +
            "service cloud.firestore {\n" +
            "  match /databases/{database}/documents {\n" +
            body.trimIndent() + "\n" +
            "  }\n" +
            "}\n"

    private fun inStorage(body: String): String =
        "rules_version = '2';\n" +
            "service firebase.storage {\n" +
            "  match /b/{bucket}/o {\n" +
            body.trimIndent() + "\n" +
            "  }\n" +
            "}\n"
}
