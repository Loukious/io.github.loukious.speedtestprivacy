package io.github.loukious.speedtestprivacy.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class DiscoveryCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val loader = javaClass.classLoader!!
    private val identity = HostIdentity("fixture", 1, "a".repeat(64), false, emptyList())
    private val first = CacheFixture::class.java.getDeclaredMethod("first")
    private val second = CacheFixture::class.java.getDeclaredMethod("second")

    private fun target(key: String, validator: (Method) -> Boolean = { it.returnType == java.lang.Boolean.TYPE }) =
        SemanticTarget(key, key, query = { emptyList() }, validate = validator)

    @Test fun restoresOnlyCompleteValidatedSnapshot() {
        val cache = DiscoveryCache(temporary.newFile(), "module-one")
        val targets = listOf(target("first"), target("second"))
        val methods = mapOf("first" to listOf(first), "second" to listOf(second))
        assertTrue(cache.store(identity, targets, methods))
        assertEquals(methods, cache.load(identity, loader, targets))
        val requiredDependencyMissing = listOf(target("first"), target("second") { false })
        assertNull(cache.load(identity, loader, requiredDependencyMissing))
    }

    @Test fun staleReturnTypeOrStaticityInvalidatesWholeSnapshot() {
        for ((field, value) in listOf(
            "result" to "int",
            "modifiers" to (first.modifiers xor Modifier.STATIC).toString()
        )) {
            val file = temporary.newFile()
            val cache = DiscoveryCache(file, "module-one")
            val targets = listOf(target("first"), target("second"))
            assertTrue(cache.store(identity, targets, mapOf("first" to listOf(first), "second" to listOf(second))))
            val properties = DiscoveryStorage.read(file)!!
            properties.setProperty("target.0.method.$field", value)
            DiscoveryStorage.write(file, properties)
            assertNull(field, cache.load(identity, loader, targets))
        }
    }

    @Test fun fingerprintModuleAndSchemaChangesForceRediscovery() {
        val file = temporary.newFile()
        val targets = listOf(target("first"))
        val cache = DiscoveryCache(file, "module-one", schema = 1)
        assertTrue(cache.store(identity, targets, mapOf("first" to listOf(first))))
        assertNull(cache.load(identity.copy(fingerprint = "b".repeat(64)), loader, targets))
        assertNull(DiscoveryCache(file, "module-two", schema = 1).load(identity, loader, targets))
        assertNull(DiscoveryCache(file, "module-one", schema = 2).load(identity, loader, targets))
        assertNull(cache.load(identity, loader, targets + target("new-role")))
    }

    @Test fun failuresAndAmbiguityAreNotCached() {
        val file = temporary.newFile()
        val cache = DiscoveryCache(file, "module-one")
        val targets = listOf(target("first"), target("second"))
        assertFalse(cache.store(identity, targets, mapOf("first" to listOf(first))))
        assertFalse(cache.store(identity, targets,
            mapOf("first" to listOf(first, second), "second" to listOf(second))))
        assertNull(cache.load(identity, loader, targets))
    }

    @Test fun malformedMemberDoesNotRestoreAnUnrelatedSubset() {
        val file = temporary.newFile()
        val cache = DiscoveryCache(file, "module-one")
        val targets = listOf(target("first"), target("second"))
        assertTrue(cache.store(identity, targets, mapOf("first" to listOf(first), "second" to listOf(second))))
        val properties = DiscoveryStorage.read(file)!!
        properties.setProperty("target.1.method.name", "noSuchMethod")
        DiscoveryStorage.write(file, properties)
        assertNull(cache.load(identity, loader, targets))
    }

    @Test fun completeDescriptorRestoresPrimitiveAndArrayParameters() {
        val method = CacheFixture::class.java.getDeclaredMethod("arrays", Array<String>::class.java, IntArray::class.java)
        assertEquals(method, CachedMethodDescriptor.from(method).resolve(loader))
        val static = CacheFixture::class.java.getDeclaredMethod("staticFlag")
        assertTrue(Modifier.isStatic(static.modifiers))
        assertEquals(static, CachedMethodDescriptor.from(static).resolve(loader))
    }

    @Test fun covariantBridgeRestoresItsOwnReturnAndModifierContract() {
        val methods = CovariantFixture::class.java.declaredMethods.filter { it.name == "value" }
        assertEquals(2, methods.size)
        assertEquals(setOf(String::class.java, Any::class.java), methods.map { it.returnType }.toSet())
        methods.forEach { method -> assertEquals(method, CachedMethodDescriptor.from(method).resolve(loader)) }
    }

    @Test fun ambiguityIsRejectedAfterSemanticValidationAndDeduplication() {
        val broad = target("boolean")
        val ambiguous = DiscoveryEngine.resolveCandidateMethods(broad, listOf(first, second))
        assertEquals(DiscoveryStatus.NOT_UNIQUE, ambiguous.status)
        assertTrue(ambiguous.methods.isEmpty())
        val duplicate = DiscoveryEngine.resolveCandidateMethods(broad, listOf(first, first))
        assertEquals(listOf(first), duplicate.methods)
        val narrow = target("first") { it.name == "first" && it.returnType == java.lang.Boolean.TYPE }
        assertEquals(listOf(first), DiscoveryEngine.resolveCandidateMethods(narrow, listOf(first, second)).methods)
    }

    @Test fun missingOrThrowingValidatorCannotYieldHookMethods() {
        assertEquals(DiscoveryStatus.NOT_FOUND,
            DiscoveryEngine.resolveCandidateMethods(target("empty"), emptyList()).status)
        val invalid = DiscoveryEngine.resolveCandidateMethods(target("invalid") { false }, listOf(first))
        assertEquals(DiscoveryStatus.VALIDATION_FAILED, invalid.status)
        assertTrue(invalid.methods.isEmpty())
        val throwing = DiscoveryEngine.resolveCandidateMethods(target("throwing") { error("missing callback") }, listOf(first))
        assertEquals(DiscoveryStatus.VALIDATION_FAILED, throwing.status)
        assertTrue(throwing.methods.isEmpty())
    }

    class CacheFixture {
        fun first(): Boolean = true
        fun second(): Boolean = false
        fun arrays(strings: Array<String>, numbers: IntArray): Long = strings.size.toLong() + numbers.size
        companion object { @JvmStatic fun staticFlag(): Boolean = true }
    }

    interface GenericValue<T> { fun value(): T }
    class CovariantFixture : GenericValue<String> { override fun value(): String = "fixture" }
}
