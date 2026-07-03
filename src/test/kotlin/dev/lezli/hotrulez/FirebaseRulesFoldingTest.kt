package dev.lezli.hotrulez

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lezli.hotrulez.folding.FirebaseRulesFoldingBuilder

/**
 * Code folding: the builder emits a `{…}` fold over each fully-braced, non-empty
 * `service`/`match` block and `function` body, and a `/*…*/` fold over each
 * multi-line block comment. Heads stay visible (the fold is brace-inclusive), and
 * half-typed/empty/single-line input yields no bogus fold and never throws.
 *
 * The placeholders use the ellipsis character U+2026, not three ASCII dots.
 */
class FirebaseRulesFoldingTest : BasePlatformTestCase() {

    // --- Positive: braced bodies fold over their {…} ---------------------

    fun testServiceBlockFolds() {
        val (doc, descriptors) = foldRegions(
            """
            rules_version = '2';
            service cloud.firestore {
              match /b/{x} { allow read: if true; }
            }
            """.trimIndent(),
        )
        // The service block's own text spans the whole `{ … }` and holds the match.
        val service = single(descriptors) { doc.getText(it.range).contains("match /b/{x}") }
        assertBraceFold(doc, service)
    }

    fun testMatchBlockFolds() {
        val (doc, descriptors) = foldRegions(
            """
            rules_version = '2';
            service cloud.firestore {
              match /b/{x} { allow read: if true; }
            }
            """.trimIndent(),
        )
        // The inner match block's body carries the allow statement but not the `match`
        // head — the enclosing service block's text contains both, so exclude it.
        val match = single(descriptors) {
            val t = doc.getText(it.range)
            t.contains("allow read") && !t.contains("match")
        }
        assertBraceFold(doc, match)
    }

    fun testFunctionBodyFolds() {
        val (doc, descriptors) = foldRegions(
            inDocuments(
                """
                function isSignedIn() {
                  return request.auth != null;
                }
                """,
            ),
        )
        // The function body is the braced range that holds `return` but is not a
        // service/match block (it contains neither the `function` head nor `match`).
        val body = single(descriptors) {
            val t = doc.getText(it.range)
            t.contains("return") && !t.contains("function") && !t.contains("match")
        }
        assertBraceFold(doc, body)
    }

    fun testMultiLineBlockCommentFolds() {
        val (doc, descriptors) = foldRegions(
            inDocuments(
                """
                /* line one
                   line two */
                match /cities/{city} { allow read: if true; }
                """,
            ),
        )
        val comment = single(descriptors) { it.placeholderText == COMMENT_PLACEHOLDER }
        val text = doc.getText(comment.range)
        assertTrue("comment fold text should start with /*, was <$text>", text.startsWith("/*"))
        assertTrue("comment fold text should end with */, was <$text>", text.endsWith("*/"))
    }

    // --- Dialect awareness: Storage blocks fold the same way -------------

    fun testStorageBlocksFold() {
        val (doc, descriptors) = foldRegions(
            """
            rules_version = '2';
            service firebase.storage {
              match /b/{bucket}/o {
                match /images/{img} { allow read: if true; }
              }
            }
            """.trimIndent(),
        )
        // service block, `/b/{bucket}/o` match, and `/images/{img}` match all fold.
        assertTrue("expected at least three block folds, got ${descriptors.size}", descriptors.size >= 3)
        for (d in descriptors) {
            assertEquals("storage blocks use the block placeholder", BLOCK_PLACEHOLDER, d.placeholderText)
            assertBraceFold(doc, d)
        }
    }

    // --- Negatives: no bogus folds --------------------------------------

    fun testEmptyBlockDoesNotFold() {
        val (doc, descriptors) = foldRegions(inDocuments("match /x/{y} {}"))
        // The empty `{}` gains nothing from folding; no descriptor may cover it.
        assertTrue(
            "an empty {} block must not produce a fold",
            descriptors.none { doc.getText(it.range) == "{}" },
        )
    }

    fun testUnclosedBlockAtEofDoesNotFoldAndDoesNotThrow() {
        // No closing brace: the block is not fully braced, so it must not fold —
        // and folding must never swallow the whole file or throw.
        val (_, descriptors) = foldRegions("rules_version = '2';\nservice cloud.firestore {")
        assertEquals("an unclosed block at EOF must yield no fold", 0, descriptors.size)
    }

    fun testSingleLineBlockCommentDoesNotFold() {
        val (_, descriptors) = foldRegions(
            inDocuments("/* one line */ match /cities/{city} { allow read: if true; }"),
        )
        assertTrue(
            "a single-line /* … */ comment must not fold",
            descriptors.none { it.placeholderText == COMMENT_PLACEHOLDER },
        )
    }

    // --- Behavioural contract -------------------------------------------

    fun testNothingCollapsedByDefault() {
        val builder = FirebaseRulesFoldingBuilder()
        val (_, descriptors) = foldRegions(
            """
            rules_version = '2';
            service cloud.firestore {
              match /b/{x} { allow read: if true; }
            }
            """.trimIndent(),
        )
        assertTrue("expected at least one fold to test against", descriptors.isNotEmpty())
        for (d in descriptors) {
            assertFalse(
                "folds must not be collapsed on open",
                builder.isCollapsedByDefault(d.element),
            )
        }
    }

    // --- Helpers ---------------------------------------------------------

    private fun foldRegions(text: String): Pair<Document, Array<FoldingDescriptor>> {
        val file = myFixture.configureByText(FirebaseRulesFileType, text)
        val document = myFixture.getDocument(file)
        val descriptors = FirebaseRulesFoldingBuilder().buildFoldRegions(file, document, false)
        return document to descriptors
    }

    /** Asserts a `{…}` block fold whose range is brace-inclusive. */
    private fun assertBraceFold(doc: Document, descriptor: FoldingDescriptor) {
        assertEquals("block folds use the {…} placeholder", BLOCK_PLACEHOLDER, descriptor.placeholderText)
        val text = doc.getText(descriptor.range)
        assertTrue("fold text should start with {, was <$text>", text.startsWith("{"))
        assertTrue("fold text should end with }, was <$text>", text.endsWith("}"))
    }

    private fun single(
        descriptors: Array<FoldingDescriptor>,
        predicate: (FoldingDescriptor) -> Boolean,
    ): FoldingDescriptor {
        val matches = descriptors.filter(predicate)
        assertEquals("expected exactly one matching fold descriptor", 1, matches.size)
        return matches.single()
    }

    /** Wraps body inside the conventional v2 Firestore service + root documents match. */
    private fun inDocuments(body: String): String =
        "rules_version = '2';\n" +
            "service cloud.firestore {\n" +
            "  match /databases/{database}/documents {\n" +
            body.trimIndent() + "\n" +
            "  }\n" +
            "}\n"

    private companion object {
        /** Ellipsis character (U+2026), matching the builder's placeholders. */
        const val BLOCK_PLACEHOLDER = "{…}"
        const val COMMENT_PLACEHOLDER = "/*…*/"
    }
}
