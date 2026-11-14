package io.github.loukious.speedtestprivacy.core

import org.junit.Assert.*
import org.junit.Test

class DiscoverySourceTest {
    private val descriptor = "Lfixture/PermissionPolicy;->a()Z"
    private val owner = "Lfixture/PermissionPolicy;"

    @Test fun duplicateDescriptorAcrossBaseAndFeatureRemainsAmbiguous() {
        val sources = listOf(
            DiscoveryCandidateOrigin("/installed/base.apk", descriptor),
            DiscoveryCandidateOrigin("/installed/feature.apk", descriptor)
        )
        assertEquals(mapOf(descriptor to setOf("/installed/base.apk", "/installed/feature.apk")),
            conflictingDexSources(sources))
    }

    @Test fun repeatedQueryHitsWithinOneSourceDoNotManufactureAmbiguity() {
        val source = DiscoveryCandidateOrigin("/installed/base.apk", descriptor)
        assertTrue(conflictingDexSources(listOf(source, source)).isEmpty())
    }

    @Test fun unrelatedDescriptorInAnotherSourceDoesNotConflict() {
        val sources = listOf(
            DiscoveryCandidateOrigin("/installed/base.apk", descriptor),
            DiscoveryCandidateOrigin("/installed/feature.apk", "Lfixture/OtherPolicy;->a()Z")
        )
        assertTrue(conflictingDexSources(sources).isEmpty())
    }

    @Test fun sameJavaNameAndParametersWithDifferentReturnRemainSeparateDescriptors() {
        val sources = listOf(
            DiscoveryCandidateOrigin("/installed/base.apk", "Lfixture/Bridge;->value()Ljava/lang/String;"),
            DiscoveryCandidateOrigin("/installed/feature.apk", "Lfixture/Bridge;->value()Ljava/lang/Object;")
        )
        assertTrue(conflictingDexSources(sources).isEmpty())
    }

    @Test fun unmarkedBaseOwnerCannotShadowMarkedSplitCandidate() {
        val candidates = listOf(DiscoveryCandidateOrigin("/installed/feature.apk", descriptor))
        val definitions = mapOf(owner to listOf("/installed/base.apk", "/installed/feature.apk"))

        assertTrue(conflictingDexSources(candidates).isEmpty())
        val provenance = checkDexOwnerProvenance(candidates, definitions)
        assertEquals(definitions, provenance.conflicts)
        assertTrue(provenance.unmatchedOwners.isEmpty())
    }

    @Test fun differentMethodsInSameShadowedOwnerRemainAmbiguous() {
        val candidates = listOf(
            DiscoveryCandidateOrigin("/installed/base.apk", descriptor),
            DiscoveryCandidateOrigin("/installed/feature.apk", "$owner->b()V")
        )
        val definitions = mapOf(owner to listOf("/installed/base.apk", "/installed/feature.apk"))

        assertTrue(conflictingDexSources(candidates).isEmpty())
        assertEquals(definitions, checkDexOwnerProvenance(candidates, definitions).conflicts)
    }

    @Test fun duplicateOwnerDefinitionsWithinOneApkCannotBeCollapsed() {
        val candidates = listOf(DiscoveryCandidateOrigin("/installed/base.apk", descriptor))
        val definitions = mapOf(owner to listOf("/installed/base.apk", "/installed/base.apk"))

        assertEquals(definitions, checkDexOwnerProvenance(candidates, definitions).conflicts)
    }

    @Test fun uniqueOwnerInSemanticSourceIsAcceptedDespiteRepeatedHits() {
        val candidate = DiscoveryCandidateOrigin("/installed/base.apk", descriptor)
        val definitions = mapOf(owner to listOf("/installed/base.apk"))

        val provenance = checkDexOwnerProvenance(listOf(candidate, candidate), definitions)
        assertTrue(provenance.conflicts.isEmpty())
        assertTrue(provenance.unmatchedOwners.isEmpty())
    }

    @Test fun unrelatedDuplicateOwnerDoesNotAffectCandidate() {
        val candidates = listOf(DiscoveryCandidateOrigin("/installed/base.apk", descriptor))
        val definitions = mapOf(
            owner to listOf("/installed/base.apk"),
            "Lfixture/Other;" to listOf("/installed/base.apk", "/installed/feature.apk")
        )

        val provenance = checkDexOwnerProvenance(candidates, definitions)
        assertTrue(provenance.conflicts.isEmpty())
        assertTrue(provenance.unmatchedOwners.isEmpty())
    }

    @Test fun missingOwnerDefinitionFailsProvenance() {
        val candidates = listOf(DiscoveryCandidateOrigin("/installed/feature.apk", descriptor))

        assertEquals(setOf(owner), checkDexOwnerProvenance(candidates, emptyMap()).unmatchedOwners)
    }

    @Test fun uniqueOwnerInDifferentSourceFailsProvenance() {
        val candidates = listOf(DiscoveryCandidateOrigin("/installed/feature.apk", descriptor))
        val definitions = mapOf(owner to listOf("/installed/base.apk"))

        val provenance = checkDexOwnerProvenance(candidates, definitions)
        assertTrue(provenance.conflicts.isEmpty())
        assertEquals(setOf(owner), provenance.unmatchedOwners)
    }
}
