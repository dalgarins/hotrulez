package dev.lezli.hotrulez

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lezli.hotrulez.documentation.FirebaseRulesDocs
import dev.lezli.hotrulez.references.FirebaseRulesBuiltins
import dev.lezli.hotrulez.references.RulesService

/**
 * Doc-table integrity: guarantees that every name the vocabulary tables
 * ([RulesService.members] / `.globals` / `.bareHelpers`, [FirebaseRulesBuiltins.OPERATIONS]
 * / `.TYPE_NAMES`) can surface in the editor also has doc prose in [FirebaseRulesDocs].
 * A name present in a vocabulary table but missing prose would show a blank quick-doc
 * rather than crash — these tests turn that silent gap into a red build so vocabulary and
 * prose cannot drift apart.
 *
 * Pure-data assertions; no editor is required, but the suite rides on
 * [BasePlatformTestCase] to match this module's test conventions.
 *
 * ### Integrity subtlety
 * `RulesService.STORAGE.members` carries `firestore -> [get, exists]`; those member paths
 * (`firestore.get`/`firestore.exists`) are documented via [FirebaseRulesDocs.forHelper]
 * (the cross-service helpers), **not** via `forMember`. The member sweep therefore treats
 * a `firestore` receiver leaf as helper-backed.
 */
class FirebaseRulesDocsTableTest : BasePlatformTestCase() {

    /** Every composed `receiver.leaf` member path (both dialects) has doc prose. */
    fun testEveryMemberPathHasProse() {
        val offenders = mutableListOf<String>()
        for (service in RulesService.entries) {
            for ((receiver, leaves) in service.members) {
                for (leaf in leaves) {
                    val path = "$receiver.$leaf"
                    val byMember = FirebaseRulesDocs.forMember(path) != null
                    val byHelper = receiver == "firestore" &&
                        leaf in RulesService.CROSS_SERVICE_HELPERS &&
                        FirebaseRulesDocs.forHelper(leaf) != null
                    if (!byMember && !byHelper) {
                        offenders += "${service.name}: $path"
                    }
                }
            }
        }
        assertTrue("member paths missing FirebaseRulesDocs prose: $offenders", offenders.isEmpty())
    }

    /** Every member-table receiver that is itself a global name has global-level prose. */
    fun testEveryGlobalReceiverHasProse() {
        val globalNames = RulesService.entries.flatMap { it.globals }.toSet()
        val offenders = mutableListOf<String>()
        for (service in RulesService.entries) {
            for (receiver in service.members.keys) {
                if (receiver in globalNames && FirebaseRulesDocs.forGlobal(receiver) == null) {
                    offenders += "${service.name}: $receiver"
                }
            }
        }
        assertTrue("global receivers missing FirebaseRulesDocs.forGlobal prose: $offenders", offenders.isEmpty())
    }

    /** The `request`/`resource`/`firestore` globals are each documented. */
    fun testDeclaredGlobalsHaveProse() {
        val offenders = RulesService.entries
            .flatMap { it.globals }
            .toSet()
            .filter { FirebaseRulesDocs.forGlobal(it) == null }
        assertTrue("globals missing FirebaseRulesDocs.forGlobal prose: $offenders", offenders.isEmpty())
    }

    /** Every `allow` operation has doc prose. */
    fun testEveryOperationHasProse() {
        val offenders = FirebaseRulesBuiltins.OPERATIONS.filter { FirebaseRulesDocs.forOperation(it) == null }
        assertTrue("operations missing FirebaseRulesDocs.forOperation prose: $offenders", offenders.isEmpty())
    }

    /** Every type name plus the `debug` global has namespace-level prose. */
    fun testEveryTypeNamePlusDebugHasProse() {
        val names = FirebaseRulesBuiltins.TYPE_NAMES + "debug"
        val offenders = names.filter { FirebaseRulesDocs.forNamespace(it) == null }
        assertTrue("namespaces missing FirebaseRulesDocs.forNamespace prose: $offenders", offenders.isEmpty())
    }

    /** Every bare Firestore path helper is documented and its title carries the `(path)` signature. */
    fun testEveryBareHelperHasSignedProse() {
        val missing = mutableListOf<String>()
        val unsigned = mutableListOf<String>()
        for (name in RulesService.FIRESTORE.bareHelpers) {
            val entry = FirebaseRulesDocs.forHelper(name)
            if (entry == null) {
                missing += name
            } else if (!entry.title.contains("(path)")) {
                unsigned += "$name -> '${entry.title}'"
            }
        }
        assertTrue("bare helpers missing FirebaseRulesDocs.forHelper prose: $missing", missing.isEmpty())
        assertTrue("bare helper titles missing '(path)' signature: $unsigned", unsigned.isEmpty())
    }

    /** The cross-service helpers reachable as `firestore.get`/`firestore.exists` are documented. */
    fun testCrossServiceHelpersHaveProse() {
        val offenders = RulesService.CROSS_SERVICE_HELPERS.filter { FirebaseRulesDocs.forHelper(it) == null }
        assertTrue("cross-service helpers missing FirebaseRulesDocs.forHelper prose: $offenders", offenders.isEmpty())
    }
}
