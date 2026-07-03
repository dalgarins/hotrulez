package dev.lezli.hotrulez.structureview

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.lezli.hotrulez.psi.FirebaseRulesFile

/**
 * Wires the Structure tool window (and the navigation bar / "Select in Structure
 * View") to a Firebase Rules outline.
 *
 * Registered under `lang.psiStructureViewFactory`; the platform calls it for every
 * opened file, so the guard restricts the outline to genuine [FirebaseRulesFile]s
 * and returns `null` for anything else. The returned [TreeBasedStructureViewBuilder]
 * defers model construction to the moment the tool window is opened, handing the
 * live editor to [FirebaseRulesStructureViewModel] so caret tracking works.
 */
class FirebaseRulesStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is FirebaseRulesFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                FirebaseRulesStructureViewModel(psiFile, editor)
        }
    }
}
