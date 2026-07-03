package dev.lezli.hotrulez.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.lezli.hotrulez.psi.FirebaseRulesBlock
import dev.lezli.hotrulez.psi.FirebaseRulesFunctionBody
import dev.lezli.hotrulez.psi.FirebaseRulesTypes

/**
 * Code folding for Firebase Security Rules.
 *
 * Folds the brace-delimited bodies that carry the bulk of a `.rules` file so the
 * reader can collapse detail while the head stays on screen:
 *
 *  - a `service` block and a `match` block ([FirebaseRulesBlock]),
 *  - a `function` body ([FirebaseRulesFunctionBody]),
 *  - and each multi-line `/* … */` block comment.
 *
 * Only the braces fold: the grammar makes both [FirebaseRulesBlock] and
 * [FirebaseRulesFunctionBody] span their `{ … }` inclusive, and the head (service
 * name, match path, or function signature) is a *preceding* sibling that lies
 * outside the folded range — so it remains visible when the body is collapsed.
 *
 * The builder is [DumbAware]: it reads only the PSI tree structure and never
 * touches indices, so folding works while the IDE is indexing.
 *
 * Robustness: a `.rules` file is edited a keystroke at a time, so this builder
 * must tolerate half-typed, unbalanced input without throwing or producing bogus
 * folds. Guards (see [shouldFold]) suppress descriptors for unclosed or empty
 * bodies and for single-line block comments, which would fold to nothing useful.
 */
class FirebaseRulesFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()

        PsiTreeUtil.processElements(root) { element ->
            when {
                element is FirebaseRulesBlock && isFoldableBlock(element) ->
                    descriptors += descriptor(element, BLOCK_PLACEHOLDER)

                element is FirebaseRulesFunctionBody && isFoldableFunctionBody(element) ->
                    descriptors += descriptor(element, BLOCK_PLACEHOLDER)

                isFoldableBlockComment(element) ->
                    descriptors += descriptor(element, COMMENT_PLACEHOLDER)
            }
            true
        }

        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? =
        when (node.elementType) {
            FirebaseRulesTypes.BLOCK, FirebaseRulesTypes.FUNCTION_BODY -> BLOCK_PLACEHOLDER
            FirebaseRulesTypes.BLOCK_COMMENT -> COMMENT_PLACEHOLDER
            else -> null
        }

    /** Nothing is collapsed on open — the reader chooses what to fold (per spec). */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    /**
     * A `service`/`match` block folds only once it is fully braced and holds at
     * least one member. An in-progress block whose `}` has not been typed yet has
     * no [FirebaseRulesTypes.RBRACE] child (the grammar pins on `{`), so folding
     * it would swallow the rest of the file; an empty `{}` gains nothing.
     */
    private fun isFoldableBlock(block: FirebaseRulesBlock): Boolean {
        if (!hasBothBraces(block)) return false
        return block.matchDeclarationList.isNotEmpty() ||
            block.allowStatementList.isNotEmpty() ||
            block.functionDeclarationList.isNotEmpty()
    }

    /**
     * A `function` body folds only when fully braced and non-empty (at least one
     * `let` or a `return`). Same unclosed-brace guard as [isFoldableBlock].
     */
    private fun isFoldableFunctionBody(body: FirebaseRulesFunctionBody): Boolean {
        if (!hasBothBraces(body)) return false
        return body.letStatementList.isNotEmpty() || body.returnStatementList.isNotEmpty()
    }

    /** True only for a `/* … */` block comment that actually spans more than one line. */
    private fun isFoldableBlockComment(element: PsiElement): Boolean =
        element.node.elementType == FirebaseRulesTypes.BLOCK_COMMENT &&
            element.textContains('\n')

    /** Both `{` and `}` leaves present — i.e. the body is closed, not still being typed. */
    private fun hasBothBraces(element: PsiElement): Boolean {
        val node = element.node
        return node.findChildByType(FirebaseRulesTypes.LBRACE) != null &&
            node.findChildByType(FirebaseRulesTypes.RBRACE) != null
    }

    /**
     * A descriptor over [element]'s own text range (which, for blocks and function
     * bodies, already spans the braces inclusive), carrying its placeholder so the
     * platform need not call [getPlaceholderText] to render the collapsed region.
     */
    private fun descriptor(element: PsiElement, placeholder: String): FoldingDescriptor =
        FoldingDescriptor(element.node, element.textRange, null, placeholder)

    private companion object {
        /** Ellipsis character (U+2026), not three dots. */
        const val BLOCK_PLACEHOLDER = "{…}"
        const val COMMENT_PLACEHOLDER = "/*…*/"
    }
}
