package io.github.loukious.speedtestprivacy.core

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Method

data class SemanticTarget(
    val key: String,
    val description: String,
    val critical: Boolean = true,
    val query: (DexKitBridge) -> List<MethodData>,
    val validate: (Method) -> Boolean
)

enum class DiscoveryStatus { FOUND, NOT_FOUND, NOT_UNIQUE, QUERY_FAILED, RESOLVE_FAILED, VALIDATION_FAILED }

data class DiscoveryReport(
    val key: String,
    val description: String,
    val critical: Boolean,
    val status: DiscoveryStatus,
    val detail: String,
    val methods: List<Method> = emptyList(),
    val candidateCount: Int = methods.size
)

data class DiscoverySnapshot(
    val identity: HostIdentity,
    val reports: List<DiscoveryReport>,
    val methods: Map<String, List<Method>>,
    val cacheHit: Boolean,
    val elapsedMs: Long
)

internal data class DiscoveryCandidateOrigin(val source: String, val descriptor: String) {
    val ownerDescriptor: String get() = descriptor.substringBefore("->")
}

/** Equal descriptors in different DEX sources need not describe the class the loader will select. */
internal fun conflictingDexSources(candidates: List<DiscoveryCandidateOrigin>): Map<String, Set<String>> =
    candidates.groupBy { it.descriptor }.mapValues { (_, origins) -> origins.map { it.source }.toSet() }
        .filterValues { it.size > 1 }

internal data class DiscoveryOwnerProvenance(
    val conflicts: Map<String, List<String>>,
    val unmatchedOwners: Set<String>
)

/** Semantic matches cannot prove that an unmarked class in another source will not shadow them. */
internal fun checkDexOwnerProvenance(
    candidates: List<DiscoveryCandidateOrigin>, definitions: Map<String, List<String>>
): DiscoveryOwnerProvenance {
    val owners = candidates.map { it.ownerDescriptor }.toSet()
    val conflicts = definitions.filterKeys { it in owners }.filterValues { it.size > 1 }
    val unmatched = candidates.filter { definitions[it.ownerDescriptor]?.singleOrNull() != it.source }
        .map { it.ownerDescriptor }.toSet() - conflicts.keys
    return DiscoveryOwnerProvenance(conflicts, unmatched)
}

/** Queries current installed DEX synchronously; never installs or guesses a hook itself. */
class DiscoveryEngine(
    private val loader: ClassLoader,
    private val targets: List<SemanticTarget>,
    private val log: (String) -> Unit
) {
    init {
        require(targets.all { it.key.isNotBlank() })
        require(targets.map { it.key }.distinct().size == targets.size) { "Duplicate semantic target keys" }
    }

    @Synchronized
    fun prepare(context: Context): DiscoverySnapshot {
        val started = System.nanoTime()
        val identity = try {
            HostIdentity.compute(context, ::logSafely)
        } catch (error: Throwable) {
            val detail = "APK identity failed: ${error.javaClass.simpleName}"
            val unknown = HostIdentity("", -1, "", false, emptyList())
            return snapshot(unknown, targets.map { report(it, DiscoveryStatus.QUERY_FAILED, detail) }, false, started)
        }
        val cache = DiscoveryCache(File(context.cacheDir, "speedtest-privacy-discovery/methods.properties"))
        cache.load(identity, loader, targets)?.let { methods ->
            val reports = targets.map { target ->
                report(target, DiscoveryStatus.FOUND, "validated cached descriptor", methods.getValue(target.key), 1)
            }
            return snapshot(identity, reports, true, started)
        }

        val reports = scan(identity)
        val methods = reports.filter { it.status == DiscoveryStatus.FOUND }.associate { it.key to it.methods }
        if (reports.isNotEmpty() && reports.all { it.status == DiscoveryStatus.FOUND }) {
            if (!cache.store(identity, targets, methods)) logSafely("Discovery cache write/validation failed")
        }
        return snapshot(identity, reports, false, started)
    }

    private fun scan(identity: HostIdentity): List<DiscoveryReport> {
        val bridges = mutableListOf<Pair<String, DexKitBridge>>()
        val sourceErrors = mutableListOf<String>()
        val ownerDefinitions = mutableMapOf<String, Result<List<String>>>()
        try {
            for (path in identity.apkPaths) {
                try {
                    if (HostIdentity.hasExecutableDex(File(path))) bridges += path to DexKitBridge.create(path)
                } catch (error: Throwable) {
                    sourceErrors += "${File(path).name}: ${error.javaClass.simpleName}"
                }
            }
            if (bridges.isEmpty() || sourceErrors.isNotEmpty()) {
                val detail = if (sourceErrors.isEmpty()) "No executable installed DEX source"
                    else "Incomplete DEX sources: ${sourceErrors.joinToString()}"
                return targets.map { report(it, DiscoveryStatus.QUERY_FAILED, detail) }
            }
            return targets.map { target ->
                val candidates = linkedMapOf<String, MethodData>()
                val origins = mutableListOf<DiscoveryCandidateOrigin>()
                var queryError: Throwable? = null
                for ((source, bridge) in bridges) {
                    try {
                        for (candidate in target.query(bridge)) {
                            origins += DiscoveryCandidateOrigin(source, candidate.descriptor)
                            candidates.putIfAbsent(candidate.descriptor, candidate)
                        }
                    } catch (error: Throwable) {
                        queryError = error
                    }
                }
                if (queryError != null) {
                    return@map report(target, DiscoveryStatus.QUERY_FAILED, queryError.javaClass.simpleName,
                        candidateCount = candidates.size)
                }
                val conflicts = conflictingDexSources(origins)
                if (conflicts.isNotEmpty()) {
                    return@map report(target, DiscoveryStatus.NOT_UNIQUE,
                        "Descriptor present in multiple DEX sources: " + conflicts.entries.take(5).joinToString {
                            (descriptor, sources) -> "$descriptor in ${sources.joinToString { File(it).name }}"
                        }, candidateCount = origins.distinct().size)
                }
                val provenance = try {
                    val definitions = origins.map { it.ownerDescriptor }.distinct().associateWith { owner ->
                        ownerDefinitions.getOrPut(owner) {
                            runCatching {
                                bridges.flatMap { (source, bridge) ->
                                    // findClass searches definitions, unlike a type-reference lookup. A composite
                                    // exact matcher also avoids DexKit's single-owner fast path across APK DEX files.
                                    bridge.findClass {
                                        matcher { allOf(ClassMatcher().descriptor(owner)) }
                                    }.map { definition ->
                                        check(definition.descriptor == owner) { "Unexpected owner definition" }
                                        source
                                    }
                                }
                            }
                        }.getOrThrow()
                    }
                    checkDexOwnerProvenance(origins, definitions)
                } catch (error: Throwable) {
                    return@map report(target, DiscoveryStatus.QUERY_FAILED,
                        "Owner definition lookup failed: ${error.javaClass.simpleName}",
                        candidateCount = candidates.size)
                }
                if (provenance.conflicts.isNotEmpty()) {
                    return@map report(target, DiscoveryStatus.NOT_UNIQUE,
                        "Declaring owner has multiple DEX definitions: " +
                            provenance.conflicts.entries.take(5).joinToString { (owner, sources) ->
                                "$owner in ${sources.joinToString { File(it).name }}"
                            }, candidateCount = candidates.size)
                }
                if (provenance.unmatchedOwners.isNotEmpty()) {
                    return@map report(target, DiscoveryStatus.QUERY_FAILED,
                        "Declaring owner not defined in semantic source: " +
                            provenance.unmatchedOwners.take(5).joinToString(),
                        candidateCount = candidates.size)
                }
                try {
                    val resolved = candidates.values.map { it.getMethodInstance(loader) }
                    resolveCandidateMethods(target, resolved)
                } catch (error: Throwable) {
                    report(target, DiscoveryStatus.RESOLVE_FAILED, error.javaClass.simpleName,
                        candidateCount = candidates.size)
                }
            }
        } finally {
            bridges.forEach { (_, bridge) -> runCatching { bridge.close() } }
        }
    }

    private fun snapshot(
        identity: HostIdentity, reports: List<DiscoveryReport>, cacheHit: Boolean, started: Long
    ): DiscoverySnapshot {
        reports.forEach { logSafely("discovery ${it.status} key=${it.key} candidates=${it.candidateCount} ${it.detail}") }
        return DiscoverySnapshot(
            identity, reports, reports.filter { it.status == DiscoveryStatus.FOUND }.associate { it.key to it.methods },
            cacheHit, (System.nanoTime() - started) / 1_000_000
        )
    }

    private fun logSafely(message: String) { runCatching { log(message) } }

    companion object {
        /** Shared pure reflection policy for discovery and ambiguity/contract tests. */
        internal fun resolveCandidateMethods(target: SemanticTarget, candidates: List<Method>): DiscoveryReport {
            val unique = candidates.distinct()
            if (unique.isEmpty()) return report(target, DiscoveryStatus.NOT_FOUND, "No matching DEX methods")
            val valid = try {
                unique.filter(target.validate)
            } catch (error: Throwable) {
                return report(target, DiscoveryStatus.VALIDATION_FAILED, error.javaClass.simpleName,
                    candidateCount = unique.size)
            }
            return when (valid.size) {
                0 -> report(target, DiscoveryStatus.VALIDATION_FAILED, "No candidate satisfies the member contract",
                    candidateCount = unique.size)
                1 -> report(target, DiscoveryStatus.FOUND, valid.single().toGenericString(), valid, unique.size)
                else -> report(target, DiscoveryStatus.NOT_UNIQUE,
                    "${valid.size} valid candidates: ${valid.take(5).joinToString { it.toGenericString() }}",
                    candidateCount = unique.size)
            }
        }

        private fun report(
            target: SemanticTarget, status: DiscoveryStatus, detail: String,
            methods: List<Method> = emptyList(), candidateCount: Int = methods.size
        ) = DiscoveryReport(target.key, target.description, target.critical, status, detail, methods, candidateCount)
    }
}
