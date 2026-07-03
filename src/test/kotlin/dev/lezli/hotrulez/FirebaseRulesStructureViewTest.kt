package dev.lezli.hotrulez

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lezli.hotrulez.psi.FirebaseRulesAllowStatement
import dev.lezli.hotrulez.psi.FirebaseRulesFunctionDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesMatchDeclaration
import dev.lezli.hotrulez.psi.FirebaseRulesServiceDeclaration
import dev.lezli.hotrulez.structureview.FirebaseRulesStructureViewFactory
import dev.lezli.hotrulez.structureview.FirebaseRulesStructureViewModel

/**
 * The Structure tool window outline: the tree shape and presentation labels the
 * model derives from typed PSI, its sorter/suitable-class policy, and its
 * null-safe degradation on a partially-parsed file.
 *
 * Every assertion is driven straight off [FirebaseRulesStructureViewModel] by
 * walking `.root` and recursing `.children`, snapshotting each node's
 * `presentation.presentableText` — the same data the platform renders.
 */
class FirebaseRulesStructureViewTest : BasePlatformTestCase() {

    // --- Firestore shape + labels ----------------------------------------

    fun testFirestoreTreeShapeAndLabels() {
        val root = snapshot(
            """
            rules_version = '2';
            service cloud.firestore {
              match /databases/{database}/documents {
                match /cities/{city} {
                  function isSignedIn() { return request.auth != null; }
                  allow read, write: if isSignedIn();
                }
              }
            }
            """.trimIndent(),
        )

        // service → root documents match → nested match, verifying each label along the way.
        val service = root.children.single { it.text == "cloud.firestore" }
        val documents = service.children.single { it.text == "/databases/{database}/documents" }
        val cities = documents.children.single { it.text == "/cities/{city}" }

        // The nested match surfaces exactly a function leaf and an allow leaf.
        val labels = cities.children.map { it.text }
        assertTrue("expected function leaf 'isSignedIn()' in $labels", "isSignedIn()" in labels)
        assertTrue("expected allow leaf 'allow read, write' in $labels", "allow read, write" in labels)

        // function and allow are leaves — no further children.
        val function = cities.children.single { it.text == "isSignedIn()" }
        val allow = cities.children.single { it.text == "allow read, write" }
        assertTrue("a function node must be a leaf", function.children.isEmpty())
        assertTrue("an allow node must be a leaf", allow.children.isEmpty())
    }

    fun testFunctionLabelIncludesParameters() {
        val root = snapshot(
            inDocuments("function isOwner(uid) { return resource.data.owner == uid; }"),
        )
        val labels = allLabels(root)
        assertTrue("function label must carry its parameter, got $labels", "isOwner(uid)" in labels)
    }

    fun testFunctionLabelWithMultipleParameters() {
        val root = snapshot(
            inDocuments("function eq(a, b) { return a == b; }"),
        )
        val labels = allLabels(root)
        assertTrue("multi-param label must join with ', ', got $labels", "eq(a, b)" in labels)
    }

    fun testTopLevelDeclarationsAreSurfaced() {
        // A function declared at file top level (sibling of `service`) is a root child.
        val root = snapshot(
            """
            rules_version = '2';
            function shared() { return true; }
            service cloud.firestore {
              match /databases/{database}/documents { allow read: if shared(); }
            }
            """.trimIndent(),
        )
        val topLabels = root.children.map { it.text }
        assertTrue("top-level function must be a root child, got $topLabels", "shared()" in topLabels)
        assertTrue("top-level service must be a root child, got $topLabels", "cloud.firestore" in topLabels)
        // rules_version is not structure.
        assertFalse("rules_version must not be surfaced, got $topLabels", topLabels.any { it.contains("rules_version") })
    }

    // --- Storage shape + labels ------------------------------------------

    fun testStorageTreeShapeAndLabels() {
        val root = snapshot(
            """
            rules_version = '2';
            service firebase.storage {
              match /b/{bucket}/o {
                match /images/{imageId} {
                  function isImageOwner(uid) { return request.auth.uid == uid; }
                  allow read, write: if isImageOwner(request.auth.uid);
                }
              }
            }
            """.trimIndent(),
        )

        val service = root.children.single { it.text == "firebase.storage" }
        val bucket = service.children.single { it.text == "/b/{bucket}/o" }
        val images = bucket.children.single { it.text == "/images/{imageId}" }

        val labels = images.children.map { it.text }
        assertTrue("expected Storage function leaf, got $labels", "isImageOwner(uid)" in labels)
        assertTrue("expected Storage allow leaf, got $labels", "allow read, write" in labels)
    }

    // --- Non-surfaced elements -------------------------------------------

    fun testLetReturnAndPathVariablesAreNotSurfaced() {
        val root = snapshot(
            """
            rules_version = '2';
            service cloud.firestore {
              match /databases/{database}/documents {
                match /cities/{city} {
                  function gate() {
                    let known = request.auth != null;
                    return known;
                  }
                  allow read: if gate();
                }
              }
            }
            """.trimIndent(),
        )
        val labels = allLabels(root)

        // No node carries a let's name, a `return`, or a bare path-variable name.
        assertFalse("a let binding must never become a node, got $labels", "known" in labels)
        assertFalse("a return must never become a node, got $labels", labels.any { it == "return" || it.contains("return ") })
        assertFalse("a bare path variable must never become a node, got $labels", labels.any { it == "city" || it == "{city}" })

        // The function stays a leaf: its let/return bodies contribute no children.
        val function = allNodes(root).single { it.text == "gate()" }
        assertTrue("a function must expose no children, got ${function.children.map { it.text }}", function.children.isEmpty())
    }

    // --- Model policy -----------------------------------------------------

    fun testSuitableClassesAreTheFourDeclarationKinds() {
        myFixture.configureByText(FirebaseRulesFileType, inDocuments("allow read: if true;"))
        val model = FirebaseRulesStructureViewModel(myFixture.file, null)
        val classes = suitableClassesOf(model)
        assertEquals("exactly four surfaced PSI kinds", 4, classes.size)
        assertTrue(FirebaseRulesServiceDeclaration::class.java in classes)
        assertTrue(FirebaseRulesMatchDeclaration::class.java in classes)
        assertTrue(FirebaseRulesFunctionDeclaration::class.java in classes)
        assertTrue(FirebaseRulesAllowStatement::class.java in classes)
    }

    fun testSortersOfferAlphaSorter() {
        myFixture.configureByText(FirebaseRulesFileType, inDocuments("allow read: if true;"))
        val model = FirebaseRulesStructureViewModel(myFixture.file, null)
        assertTrue("the alphabetical sorter must be offered", Sorter.ALPHA_SORTER in model.sorters.toList())
    }

    fun testFactoryBuildsBuilderForRulesFile() {
        myFixture.configureByText(FirebaseRulesFileType, inDocuments("allow read: if true;"))
        val builder = FirebaseRulesStructureViewFactory().getStructureViewBuilder(myFixture.file)
        assertNotNull("the factory must build an outline for a Firebase Rules file", builder)
    }

    // --- Recovery ---------------------------------------------------------

    fun testMalformedFileYieldsPartialTreeWithoutThrowing() {
        // An unclosed match and stray tokens must not stop the model from building,
        // and a well-formed sibling must still appear somewhere in the outline.
        val root = snapshot(
            """
            rules_version = '2';
            service cloud.firestore {
              match /databases/{database}/documents {
                match /broken/ {
                  allow read: if @@@ ;
                match /cities/{city} {
                  allow read, write: if true;
                }
              }
            }
            """.trimIndent(),
        )
        // Walking every node above already exercised presentation + children without throwing.
        val labels = allLabels(root)
        assertTrue("the well-formed nested match must survive recovery, got $labels", "/cities/{city}" in labels)
        assertTrue("the well-formed allow must survive recovery, got $labels", "allow read, write" in labels)
    }

    fun testEmptyFileDoesNotThrow() {
        val root = snapshot("")
        assertTrue("an empty file has no surfaced declarations", root.children.isEmpty())
    }

    fun testHalfTypedContainersDoNotThrow() {
        // A service with no block and a match with no path/block: labels fall back, no crash.
        val root = snapshot("rules_version = '2';\nservice cloud.firestore {\n  match\n")
        // The walk itself must complete; nothing more is asserted about the partial shape.
        assertNotNull(root)
    }

    // --- Helpers ---------------------------------------------------------

    /**
     * Reads the model's `getSuitableClasses()` policy. The platform declares it `protected`
     * on `TextEditorBasedStructureViewModel` and the override inherits that visibility, so a
     * cross-package test reaches it reflectively rather than widening the main API.
     */
    private fun suitableClassesOf(model: FirebaseRulesStructureViewModel): List<Class<*>> {
        val method = model.javaClass.getDeclaredMethod("getSuitableClasses")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (method.invoke(model) as Array<Class<*>>).toList()
    }

    /** An immutable snapshot of a structure node: its label and its recursively snapshotted children. */
    private data class Node(val text: String, val children: List<Node>)

    /** Configures the file and snapshots the whole model tree from its root. */
    private fun snapshot(text: String): Node {
        myFixture.configureByText(FirebaseRulesFileType, text)
        val model = FirebaseRulesStructureViewModel(myFixture.file, null)
        return snapshot(model.root)
    }

    /** Recursively reads presentableText + children of every node — the full walk. */
    private fun snapshot(element: StructureViewTreeElement): Node {
        val text = element.presentation.presentableText ?: ""
        val children = element.children.map { snapshot(it as StructureViewTreeElement) }
        return Node(text, children)
    }

    /** All nodes in the subtree (excluding the file root itself), depth-first. */
    private fun allNodes(root: Node): List<Node> {
        val out = mutableListOf<Node>()
        fun visit(node: Node) {
            for (child in node.children) {
                out += child
                visit(child)
            }
        }
        visit(root)
        return out
    }

    /** All node labels in the subtree (excluding the file root's own label). */
    private fun allLabels(root: Node): List<String> = allNodes(root).map { it.text }

    /** Wraps body inside the conventional v2 Firestore service + root documents match. */
    private fun inDocuments(body: String): String =
        "rules_version = '2';\n" +
            "service cloud.firestore {\n" +
            "  match /databases/{database}/documents {\n" +
            body.trimIndent() + "\n" +
            "  }\n" +
            "}\n"
}
