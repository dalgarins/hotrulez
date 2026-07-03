package dev.lezli.hotrulez.structureview

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import dev.lezli.hotrulez.FirebaseRulesIcons
import dev.lezli.hotrulez.psi.FirebaseRulesAllowStatement
import dev.lezli.hotrulez.psi.FirebaseRulesFile
import dev.lezli.hotrulez.psi.FirebaseRulesFunctionDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesMatchDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesServiceDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesTypes

/**
 * One node in the Firebase Rules structure outline, wrapping a single PSI element.
 *
 * Navigation (double-click / caret sync) delegates straight to the wrapped
 * [NavigatablePsiElement]. Presentation and children, however, are computed here
 * with a `when` on the element's PSI type rather than read from the element's own
 * `getPresentation()` — the generated `service`/`match`/`allow` elements have no
 * meaningful presentation of their own, so the outline builds its own labels and
 * platform icons.
 *
 * The tree shape (Decision #7, #8, #11):
 *  - The [FirebaseRulesFile] root exposes the top-level `service`/`match`/`function`
 *    declarations (`rules_version` is not structure; a top-level `allow` is
 *    grammatically impossible).
 *  - A `service` or `match` exposes its block's matches, allows, and functions in
 *    document order.
 *  - `function` and `allow` are leaves; `let`/`return`/path variables are never
 *    surfaced.
 *
 * Every label source is optional: when a name/path/method list is absent (a
 * half-typed declaration), the node falls back to a stable placeholder rather than
 * disappearing, so an in-progress file yields a partial — never throwing — tree.
 */
class FirebaseRulesStructureViewElement(private val element: NavigatablePsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)

    override fun canNavigate(): Boolean = element.canNavigate()

    override fun canNavigateToSource(): Boolean = element.canNavigateToSource()

    override fun getAlphaSortKey(): String = presentation.presentableText ?: ""

    override fun getPresentation(): ItemPresentation = when (val e = element) {
        is FirebaseRulesServiceDeclaration ->
            PresentationData(e.serviceName?.text ?: "service", null, AllIcons.Nodes.Package, null)
        is FirebaseRulesMatchDeclaration ->
            PresentationData(e.matchPath?.text ?: "match", null, AllIcons.Nodes.Folder, null)
        is FirebaseRulesFunctionDeclaration ->
            PresentationData(functionLabel(e), null, AllIcons.Nodes.Method, null)
        is FirebaseRulesAllowStatement ->
            PresentationData(allowLabel(e), null, AllIcons.Nodes.Property, null)
        is FirebaseRulesFile ->
            PresentationData(e.name, null, FirebaseRulesIcons.FILE, null)
        else -> PresentationData()
    }

    override fun getChildren(): Array<TreeElement> = when (val e = element) {
        is FirebaseRulesFile -> childElements(e.children)
        is FirebaseRulesServiceDeclaration -> childElements(e.block?.children)
        is FirebaseRulesMatchDeclaration -> childElements(e.block?.children)
        // function & allow are leaves; anything else is not a container we surface.
        else -> TreeElement.EMPTY_ARRAY
    }

    /**
     * Wraps the surfaced declarations among [children] in document order. Uses the
     * raw PSI child list (which is already source-ordered) and keeps only the four
     * node kinds, so interleaved matches / allows / functions retain their relative
     * position — matching the "source order by default" behavior. A null child list
     * (a missing block on a half-typed container) yields no children.
     */
    private fun childElements(children: Array<PsiElement>?): Array<TreeElement> =
        children.orEmpty()
            .filter {
                it is FirebaseRulesServiceDeclaration ||
                    it is FirebaseRulesMatchDeclaration ||
                    it is FirebaseRulesFunctionDeclaration ||
                    it is FirebaseRulesAllowStatement
            }
            .map { FirebaseRulesStructureViewElement(it as NavigatablePsiElement) }
            .toTypedArray()

    /** `name(param, …)`, falling back to the placeholder `function` when unnamed. */
    private fun functionLabel(function: FirebaseRulesFunctionDeclaration): String {
        val name = function.name ?: return "function"
        val params = function.parameterList?.parameterList
            ?.joinToString(", ") { it.name ?: "" }
            .orEmpty()
        return "$name($params)"
    }

    /**
     * `allow read, write`, rebuilt from the method list's `IDENTIFIER` leaves rather
     * than its raw text (which can carry interleaved comments / whitespace). Falls
     * back to the bare placeholder `allow` when no operations are present yet.
     */
    private fun allowLabel(statement: FirebaseRulesAllowStatement): String {
        val ops = statement.methodList
            ?.node
            ?.getChildren(null)
            ?.filter { it.elementType == FirebaseRulesTypes.IDENTIFIER }
            ?.joinToString(", ") { it.text }
            .orEmpty()
        return if (ops.isEmpty()) "allow" else "allow $ops"
    }
}
