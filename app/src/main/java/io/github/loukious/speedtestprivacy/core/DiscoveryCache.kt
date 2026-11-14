package io.github.loukious.speedtestprivacy.core

import io.github.loukious.speedtestprivacy.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** A complete positive snapshot; failures are rediscovered instead of being negative-cached. */
class DiscoveryCache(
    private val file: File,
    private val moduleVersion: String = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}",
    private val schema: Int = SCHEMA
) {
    fun load(identity: HostIdentity, loader: ClassLoader, targets: List<SemanticTarget>): Map<String, List<Method>>? =
        runCatching {
            val properties = DiscoveryStorage.read(file) ?: return@runCatching null
            if (properties.getProperty("fingerprint") != identity.fingerprint ||
                properties.getProperty("moduleVersion") != moduleVersion ||
                properties.getProperty("schema") != schema.toString()) return@runCatching null
            val count = properties.getProperty("targetCount").toInt()
            if (count != targets.size || count !in 1..MAX_TARGETS) return@runCatching null
            val definitions = targets.associateBy { it.key }
            if (definitions.size != targets.size) return@runCatching null
            val result = linkedMapOf<String, List<Method>>()
            for (index in 0 until count) {
                val prefix = "target.$index."
                val key = checkNotNull(properties.getProperty(prefix + "key"))
                val target = definitions[key] ?: return@runCatching null
                if (key in result) return@runCatching null
                val method = CachedMethodDescriptor.read(properties, prefix + "method.").resolve(loader)
                    ?: return@runCatching null
                if (!target.validate(method)) return@runCatching null
                result[key] = listOf(method)
            }
            if (result.keys != definitions.keys) null else result
        }.getOrNull()

    fun store(identity: HostIdentity, targets: List<SemanticTarget>, methods: Map<String, List<Method>>): Boolean =
        runCatching {
            require(identity.fingerprint.isNotBlank())
            require(targets.isNotEmpty() && targets.size <= MAX_TARGETS)
            require(targets.map { it.key }.toSet() == methods.keys)
            require(targets.map { it.key }.distinct().size == targets.size)
            val properties = Properties().apply {
                setProperty("fingerprint", identity.fingerprint)
                setProperty("moduleVersion", moduleVersion)
                setProperty("schema", schema.toString())
                setProperty("targetCount", targets.size.toString())
            }
            targets.forEachIndexed { index, target ->
                val family = methods.getValue(target.key)
                require(family.size == 1 && target.validate(family.single()))
                val prefix = "target.$index."
                properties.setProperty(prefix + "key", target.key)
                CachedMethodDescriptor.from(family.single()).write(properties, prefix + "method.")
            }
            DiscoveryStorage.write(file, properties)
            true
        }.getOrDefault(false)

    companion object {
        // Change independently of the public module version whenever resolver/cache contracts change.
        const val SCHEMA = 2
        private const val MAX_TARGETS = 512
    }
}

/** Return type and all modifiers are part of the persisted contract, not just name/arity. */
internal data class CachedMethodDescriptor(
    val owner: String,
    val name: String,
    val parameters: List<String>,
    val result: String,
    val modifiers: Int
) {
    fun resolve(loader: ClassLoader): Method? = runCatching {
        val ownerClass = Class.forName(owner, false, loader)
        val types = parameters.map { resolveType(it, loader) }.toTypedArray()
        // Covariant-return bridges can share a Java name/parameter signature.
        // Match the complete persisted descriptor rather than whichever reflection returns first.
        ownerClass.declaredMethods.singleOrNull { method ->
            method.name == name && method.parameterTypes.contentEquals(types) &&
                method.returnType.name == result && method.modifiers == modifiers
        }
    }.getOrNull()

    fun write(properties: Properties, prefix: String) {
        properties.setProperty(prefix + "owner", owner)
        properties.setProperty(prefix + "name", name)
        properties.setProperty(prefix + "result", result)
        properties.setProperty(prefix + "modifiers", modifiers.toString())
        properties.setProperty(prefix + "parameterCount", parameters.size.toString())
        parameters.forEachIndexed { index, type -> properties.setProperty(prefix + "parameter.$index", type) }
    }

    companion object {
        fun from(method: Method) = CachedMethodDescriptor(
            method.declaringClass.name, method.name, method.parameterTypes.map { it.name },
            method.returnType.name, method.modifiers
        )

        fun read(properties: Properties, prefix: String): CachedMethodDescriptor {
            val count = properties.getProperty(prefix + "parameterCount").toInt()
            require(count in 0..255)
            return CachedMethodDescriptor(
                checkNotNull(properties.getProperty(prefix + "owner")),
                checkNotNull(properties.getProperty(prefix + "name")),
                (0 until count).map { checkNotNull(properties.getProperty(prefix + "parameter.$it")) },
                checkNotNull(properties.getProperty(prefix + "result")),
                properties.getProperty(prefix + "modifiers").toInt()
            )
        }

        private fun resolveType(name: String, loader: ClassLoader): Class<*> = when (name) {
            "void" -> java.lang.Void.TYPE
            "boolean" -> java.lang.Boolean.TYPE
            "byte" -> java.lang.Byte.TYPE
            "short" -> java.lang.Short.TYPE
            "int" -> java.lang.Integer.TYPE
            "long" -> java.lang.Long.TYPE
            "float" -> java.lang.Float.TYPE
            "double" -> java.lang.Double.TYPE
            "char" -> java.lang.Character.TYPE
            else -> Class.forName(name, false, loader)
        }
    }
}

/** Same-directory atomic replacement prevents readers from observing half a cache snapshot. */
internal object DiscoveryStorage {
    fun read(file: File): Properties? = runCatching {
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return@runCatching null
        Properties().apply { file.inputStream().buffered().use(::load) }
    }.getOrNull()

    fun write(file: File, properties: Properties) {
        val directory = checkNotNull(file.absoluteFile.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) error("Cannot create discovery cache directory")
        val temporary = Files.createTempFile(directory.toPath(), ".speedtest-discovery-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                properties.store(stream, "Speedtest Privacy discovery")
                stream.fd.sync()
            }
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
