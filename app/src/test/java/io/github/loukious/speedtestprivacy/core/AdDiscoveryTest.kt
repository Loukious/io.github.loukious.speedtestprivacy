package io.github.loukious.speedtestprivacy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdDiscoveryTest {
    enum class Result { NO_BIDS, FAILED }
    interface Completion { fun renamed(result: Result) }
    interface InheritedCompletion : Completion
    interface AmbiguousCompletion { fun first(result: Result); fun second(result: Result) }
    interface WrongParameter { fun complete(result: String) }
    interface WrongReturn { fun complete(result: Result): Boolean }
    class Listener : Completion { override fun renamed(result: Result) = Unit }

    @Test fun renamedCallbackResolvesByEnumContract() {
        val callback = AdDiscovery.enumCallback(Completion::class.java)!!
        assertEquals("renamed", callback.name)
        assertEquals(Result::class.java, callback.parameterTypes.single())
    }

    @Test fun inheritedSingleCallbackRemainsValid() {
        assertEquals("renamed", AdDiscovery.enumCallback(InheritedCompletion::class.java)?.name)
    }

    @Test fun multipleCallbackMethodsAreRejected() {
        assertNull(AdDiscovery.enumCallback(AmbiguousCompletion::class.java))
    }

    @Test fun changedParameterOrReturnContractIsRejected() {
        assertNull(AdDiscovery.enumCallback(WrongParameter::class.java))
        assertNull(AdDiscovery.enumCallback(WrongReturn::class.java))
    }

    @Test fun implementationClassCannotReplaceTheCallbackInterfaceContract() {
        assertNull(AdDiscovery.enumCallback(Listener::class.java))
    }
}
