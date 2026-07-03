package dev.lezli.hotrulez.structureview

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.lezli.hotrulez.psi.FirebaseRulesAllowStatement
import dev.lezli.hotrulez.psi.FirebaseRulesFunctionDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesMatchDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesServiceDeclaration

/**
 * The Structure tool window model for a Firebase Rules file.
 *
 * The tree is rooted at the file and built lazily by [FirebaseRulesStructureViewElement];
 * this class only supplies the platform's cross-cutting policy:
 *  - [getSorters] offers the standard alphabetical sorter while leaving source
 *    order as the default (an empty sorter list would forbid sorting entirely).
 *  - [getSuitableClasses] names the four surfaced PSI kinds so the navigation bar
 *    and "Select in Structure View" can map the caret onto a tree node.
 *  - [isAlwaysShowsPlus] / [isAlwaysLeaf] mark `service`/`match` as expandable
 *    containers and `function`/`allow` as leaves, so the tree renders the correct
 *    expand handles without first computing children.
 *
 * All node content is derived from typed PSI and is fully null-safe, so a
 * partially-parsed file yields a partial outline rather than an exception.
 */
class FirebaseRulesStructureViewModel(psiFile: PsiFile, editor: Editor?) :
    StructureViewModelBase(psiFile, editor, FirebaseRulesStructureViewElement(psiFile)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun getSuitableClasses(): Array<Class<*>> = arrayOf(
        FirebaseRulesServiceDeclaration::class.java,
        FirebaseRulesMatchDeclaration::class.java,
        FirebaseRulesFunctionDeclaration::class.java,
        FirebaseRulesAllowStatement::class.java,
    )

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean {
        val value = element.value
        return value is FirebaseRulesServiceDeclaration || value is FirebaseRulesMatchDeclaration
    }

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean {
        val value = element.value
        return value is FirebaseRulesFunctionDeclaration || value is FirebaseRulesAllowStatement
    }
}
