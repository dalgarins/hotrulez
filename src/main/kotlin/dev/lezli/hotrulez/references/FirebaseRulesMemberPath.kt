package dev.lezli.hotrulez.references

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import dev.lezli.hotrulez.psi.FirebaseRulesMemberExpression
import dev.lezli.hotrulez.psi.FirebaseRulesTypes as T

/**
 * The shared member-path key logic for Firebase Rules member expressions.
 *
 * A member path is the fully-qualified receiver chain of a member access —
 * `request.auth`, `resource.size`, `request.auth.token` — assembled from the
 * `IDENTIFIER` and `.` leaves only. Keys built this way are identical to the
 * member paths in [RulesService]'s member tables, so both completion (member
 * suggestions) and documentation (quick-docs lookup) resolve against the same
 * string (Decision #24).
 *
 * The key is derived from tokens rather than the receiver's raw text, so
 * interleaved whitespace or comments — e.g. `request /* x */ .auth` — still
 * collapse to `request.auth`. All walking is null-safe: a partially malformed
 * receiver simply contributes whatever identifier/dot leaves it does have.
 */
object FirebaseRulesMemberPath {
    /**
     * The lookup key for a member receiver, assembled from its identifier and `.` leaves only.
     * Built from tokens (not raw text) so interleaved whitespace or comments — e.g.
     * `request /* x */ .auth` — still map to `request.auth`.
     */
    fun receiverKey(receiver: PsiElement): String {
        val builder = StringBuilder()
        fun collect(node: ASTNode) {
            val children = node.getChildren(null)
            if (children.isEmpty()) {
                if (node.elementType == T.IDENTIFIER || node.elementType == T.DOT) builder.append(node.text)
            } else {
                children.forEach(::collect)
            }
        }
        collect(receiver.node)
        return builder.toString()
    }

    /**
     * The full member-path key for a member expression: its receiver key joined to the
     * accessed member name — e.g. the expression `request.auth.uid` keys to `request.auth.uid`.
     */
    fun memberPath(member: FirebaseRulesMemberExpression): String =
        receiverKey(member.expression) + "." + member.identifier.text
}
