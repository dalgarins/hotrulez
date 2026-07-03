package dev.lezli.hotrulez.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import dev.lezli.hotrulez.psi.FirebaseRulesFunctionDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesLetStatement
import dev.lezli.hotrulez.psi.FirebaseRulesMatchDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesMemberExpression
import dev.lezli.hotrulez.psi.FirebaseRulesMethodList
import dev.lezli.hotrulez.psi.FirebaseRulesParameter
import dev.lezli.hotrulez.psi.FirebaseRulesPathWildcard
import dev.lezli.hotrulez.psi.FirebaseRulesRecursiveWildcard
import dev.lezli.hotrulez.psi.FirebaseRulesReferenceExpression
import dev.lezli.hotrulez.psi.FirebaseRulesTypes
import dev.lezli.hotrulez.references.FirebaseRulesBuiltins
import dev.lezli.hotrulez.references.FirebaseRulesMemberPath
import dev.lezli.hotrulez.references.FirebaseRulesNamedElement
import dev.lezli.hotrulez.references.FirebaseRulesScopes
import dev.lezli.hotrulez.references.RulesService

/**
 * Quick documentation (hover / Ctrl-Q / lookup-item preview) for Firebase Rules.
 *
 * Two kinds of target are documented, along the same structural boundary the
 * resolver and completion already draw:
 *
 *  - **User symbols** — a function, parameter, `let` binding, or path / recursive
 *    wildcard variable — are documented from the code itself: a function shows its
 *    reconstructed signature plus its own immediately-preceding comment (the
 *    user's words, doc-grounded by definition); the others show a one-line
 *    description of what they bind. These arrive here already resolved by the
 *    platform's default reference resolution (through [FirebaseRulesScopes] /
 *    `FirebaseRulesReference`), so [getCustomDocumentationElement] deliberately
 *    returns `null` for a use of a user symbol and lets that default path find the
 *    declaration.
 *  - **Built-in vocabulary** — `allow` operations, built-in globals, `request` /
 *    `resource` members, path / cross-service helpers, and type/global
 *    namespaces — are documented from [FirebaseRulesDocs], the shared doc-prose
 *    table. Dialect is resolved through [RulesService.forElement], and a member is
 *    validated against [RulesService.membersFor] *before* any prose is fetched, so
 *    a Storage-only member never shows inside a Firestore file and an unknown
 *    member (`request.foo`) shows nothing at all.
 *
 * There is no type inference and no fabrication: an unknown member, an unresolved
 * name, or a name with no table entry yields `null`, and the platform shows
 * nothing. Every lookup is null-safe, so a partially-malformed file degrades
 * gracefully rather than throwing.
 */
class FirebaseRulesDocumentationProvider : AbstractDocumentationProvider() {

    /**
     * Map the caret's context leaf to a documentable **built-in** element (or a
     * declaration, for Ctrl-Q on the declaration itself). Uses of user symbols
     * return `null` so the platform's default reference resolution takes over and
     * [generateDoc] runs on the resolved declaration.
     */
    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        val leaf = contextElement ?: return super.getCustomDocumentationElement(editor, file, contextElement, targetOffset)
        return documentableTarget(leaf)
            ?: super.getCustomDocumentationElement(editor, file, contextElement, targetOffset)
    }

    /**
     * Build the doc popup for [element], branching on its PSI type and (for
     * built-ins) the file's dialect. Returns `null` — never fabricated prose — for
     * anything not recognised as a user symbol or a tabled built-in.
     */
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? =
        when (element) {
            is FirebaseRulesFunctionDeclaration -> functionDoc(element)
            is FirebaseRulesParameter -> parameterDoc(element)
            is FirebaseRulesLetStatement -> letDoc(element)
            is FirebaseRulesPathWildcard -> pathVariableDoc(element, recursive = false)
            is FirebaseRulesRecursiveWildcard -> pathVariableDoc(element, recursive = true)
            else -> builtinEntryFor(element)?.let(::renderEntry)
        }

    /**
     * The external Firebase docs URL for a tabled built-in (enables "open in
     * browser" from the doc popup), or `null` for a user symbol / untabled name —
     * the same entry-resolution branching [generateDoc] uses.
     */
    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): List<String>? =
        builtinEntryFor(element)?.docUrl?.let(::listOf)

    /**
     * Let hover work on a completion lookup item: map the element under the caret
     * to its documentable target when it is a recognised built-in, otherwise fall
     * back to the element itself (from which [generateDoc] simply yields nothing).
     */
    override fun getDocumentationElementForLookupItem(
        psiManager: PsiManager,
        obj: Any?,
        element: PsiElement?,
    ): PsiElement? {
        if (element == null) return null
        return documentableTarget(element) ?: element
    }

    // --- Target selection -------------------------------------------------------

    /**
     * The documentable element denoted by [leaf], or `null` when the leaf denotes
     * a user-symbol *use* (left to default resolution) or nothing documentable:
     *
     *  - a member-access member name → the enclosing `member_expression`;
     *  - a `reference_expression` that is a recognised built-in (and does not
     *    resolve to a user symbol) → that `reference_expression`;
     *  - an `allow` operation name → the leaf itself;
     *  - a declaration's own name identifier → the declaration (Ctrl-Q on a decl).
     */
    private fun documentableTarget(leaf: PsiElement): PsiElement? {
        val parent = leaf.parent
        return when {
            parent is FirebaseRulesMemberExpression && leaf === parent.identifier -> parent
            parent is FirebaseRulesReferenceExpression ->
                if (referenceIsBuiltin(parent)) parent else null
            parent is FirebaseRulesMethodList -> leaf
            parent is FirebaseRulesNamedElement -> parent
            else -> null
        }
    }

    /**
     * True when [reference] is a built-in global / helper / namespace and does not
     * resolve to a user symbol. Resolution is scope-based (forward references
     * included) so a use of a same-named user function is treated as a user
     * symbol, not a built-in.
     */
    private fun referenceIsBuiltin(reference: FirebaseRulesReferenceExpression): Boolean {
        if (FirebaseRulesScopes.resolve(reference).isNotEmpty()) return false
        val name = reference.identifier.text
        val service = RulesService.forElement(reference)
        return name in RulesService.globalsFor(service) ||
            name in RulesService.bareHelpersFor(service) ||
            name in FirebaseRulesBuiltins.GLOBALS
    }

    // --- Built-in entry resolution ---------------------------------------------

    /** The [FirebaseRulesDocs.Entry] for a built-in [element], or `null` for user symbols / untabled names. */
    private fun builtinEntryFor(element: PsiElement?): FirebaseRulesDocs.Entry? =
        when (element) {
            is FirebaseRulesMemberExpression -> memberEntry(element)
            is FirebaseRulesReferenceExpression -> referenceEntry(element)
            else ->
                if (element != null && element.parent is FirebaseRulesMethodList) {
                    FirebaseRulesDocs.forOperation(element.text)
                } else {
                    null
                }
        }

    /**
     * Prose for a `member_expression`. The cross-service `firestore.get` /
     * `firestore.exists` calls are recognised service-agnostically; every other
     * member is validated against the detected dialect's member table before any
     * prose is fetched, so no dialect leak and no invented member.
     */
    private fun memberEntry(member: FirebaseRulesMemberExpression): FirebaseRulesDocs.Entry? {
        val memberName = member.identifier.text
        val receiverKey = FirebaseRulesMemberPath.receiverKey(member.expression)
        if (receiverKey == "firestore" && memberName in RulesService.CROSS_SERVICE_HELPERS) {
            return FirebaseRulesDocs.forHelper(memberName)
        }
        val members = RulesService.membersFor(RulesService.forElement(member))
        if (memberName in members[receiverKey].orEmpty()) {
            return FirebaseRulesDocs.forMember(FirebaseRulesMemberPath.memberPath(member))
        }
        return null
    }

    /**
     * Prose for a `reference_expression`: a built-in global, a bare path helper
     * (only in call-callee position), or a type/global namespace. Uses of user
     * symbols resolve to a declaration and are documented there instead, so this
     * returns `null` for them.
     */
    private fun referenceEntry(reference: FirebaseRulesReferenceExpression): FirebaseRulesDocs.Entry? {
        if (FirebaseRulesScopes.resolve(reference).isNotEmpty()) return null
        val name = reference.identifier.text
        val service = RulesService.forElement(reference)
        return when {
            name in RulesService.globalsFor(service) -> FirebaseRulesDocs.forGlobal(name)
            FirebaseRulesScopes.isFunctionCall(reference) && name in RulesService.bareHelpersFor(service) ->
                FirebaseRulesDocs.forHelper(name)
            name in FirebaseRulesBuiltins.GLOBALS -> FirebaseRulesDocs.forNamespace(name)
            else -> null
        }
    }

    // --- Rendering --------------------------------------------------------------

    /** Wrap a table entry in `DocumentationMarkup` DEFINITION (title) + CONTENT (already-HTML summary) sections. */
    private fun renderEntry(entry: FirebaseRulesDocs.Entry): String =
        DocumentationMarkup.DEFINITION_START + escape(entry.title) + DocumentationMarkup.DEFINITION_END +
            DocumentationMarkup.CONTENT_START + entry.summaryHtml + DocumentationMarkup.CONTENT_END

    /** `function name(params)` plus the declaration's own preceding comment, if any. */
    private fun functionDoc(function: FirebaseRulesFunctionDeclaration): String {
        val name = function.name ?: "function"
        val params = function.parameterList?.parameterList
            ?.mapNotNull { it.name }
            ?.joinToString(", ")
            .orEmpty()
        val signature = "function " + escape(name) + "(" + escape(params) + ")"
        val builder = StringBuilder()
        builder.append(DocumentationMarkup.DEFINITION_START).append(signature).append(DocumentationMarkup.DEFINITION_END)
        precedingCommentHtml(function)?.let {
            builder.append(DocumentationMarkup.CONTENT_START).append(it).append(DocumentationMarkup.CONTENT_END)
        }
        return builder.toString()
    }

    /** `parameter 'x' of function f`. */
    private fun parameterDoc(parameter: FirebaseRulesParameter): String {
        val name = parameter.name ?: "parameter"
        val function = PsiTreeUtil.getParentOfType(parameter, FirebaseRulesFunctionDeclaration::class.java)
        val functionName = function?.name ?: "?"
        return DocumentationMarkup.DEFINITION_START +
            "parameter '" + escape(name) + "' of function " + escape(functionName) +
            DocumentationMarkup.DEFINITION_END
    }

    /** `let x = <expr>`, the expression text collapsed and truncated for display. */
    private fun letDoc(binding: FirebaseRulesLetStatement): String {
        val name = binding.name ?: "let"
        val expression = binding.expression?.text?.let(::collapseWhitespace).orEmpty()
        return DocumentationMarkup.DEFINITION_START +
            "let " + escape(name) + " = " + escape(truncate(expression)) +
            DocumentationMarkup.DEFINITION_END
    }

    /** `path variable 'x' captured by match /…` (or `recursive path variable` for `{x=**}`). */
    private fun pathVariableDoc(wildcard: PsiElement, recursive: Boolean): String {
        val name = (wildcard as? FirebaseRulesNamedElement)?.name ?: "path variable"
        val match = PsiTreeUtil.getParentOfType(wildcard, FirebaseRulesMatchDeclaration::class.java)
        val path = match?.matchPath?.text?.let(::collapseWhitespace).orEmpty()
        val label = if (recursive) "recursive path variable" else "path variable"
        return DocumentationMarkup.DEFINITION_START +
            label + " '" + escape(name) + "' captured by match " + escape(path) +
            DocumentationMarkup.DEFINITION_END
    }

    // --- Comment extraction -----------------------------------------------------

    /**
     * The HTML doc body drawn from [declaration]'s immediately-preceding comment:
     * the contiguous run of `LINE_COMMENT`s directly above it, or a single
     * preceding `BLOCK_COMMENT`. Only whitespace may sit between the comment and
     * the declaration; a blank line breaks a line-comment run. Markers are
     * stripped and the text HTML-escaped. `null` when there is no such comment.
     */
    private fun precedingCommentHtml(declaration: PsiElement): String? {
        var sibling = declaration.prevSibling
        while (sibling is PsiWhiteSpace) sibling = sibling.prevSibling
        val comment = sibling ?: return null
        return when (comment.node.elementType) {
            FirebaseRulesTypes.BLOCK_COMMENT -> renderBlockComment(comment.text)
            FirebaseRulesTypes.LINE_COMMENT -> renderLineComments(comment)
            else -> null
        }
    }

    /** Collect the contiguous run of line comments ending at [last] (upward), stripped and joined. */
    private fun renderLineComments(last: PsiElement): String {
        val lines = ArrayDeque<String>()
        var current: PsiElement? = last
        while (current != null && current.node.elementType == FirebaseRulesTypes.LINE_COMMENT) {
            lines.addFirst(current.text.removePrefix("//").trim())
            var previous = current.prevSibling
            if (previous is PsiWhiteSpace) {
                // A blank line (more than one newline) ends the doc run.
                if (previous.text.count { it == '\n' } > 1) break
                previous = previous.prevSibling
            }
            current = previous
        }
        return lines.joinToString(LINE_BREAK) { escape(it) }
    }

    /** Strip the block-comment delimiters (and any per-line leading star) from [text], escape, and join its lines. */
    private fun renderBlockComment(text: String): String {
        val body = text.removePrefix("/*").removeSuffix("*/")
        return body.trim()
            .lines()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(LINE_BREAK) { escape(it) }
    }

    // --- Text helpers -----------------------------------------------------------

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    private fun collapseWhitespace(text: String): String = text.replace(WHITESPACE_RUN, " ").trim()

    private fun truncate(text: String): String =
        if (text.length > MAX_EXPRESSION_CHARS) text.take(MAX_EXPRESSION_CHARS) + "…" else text

    private companion object {
        /** Upper bound on a `let` expression rendered in its doc definition line. */
        const val MAX_EXPRESSION_CHARS = 100

        /** Separator between rendered comment lines in the HTML doc body. */
        const val LINE_BREAK = "<br/>"

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
