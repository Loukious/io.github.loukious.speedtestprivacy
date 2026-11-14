package io.github.loukious.speedtestprivacy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellRebelCallbacksTest {
    enum class Error { CANCELLED, UNKNOWN }
    enum class OtherError { UNKNOWN }
    interface Complete { fun renamed(success: Boolean) }
    interface WrongCompletion { fun renamed(success: String) }
    interface MultipleCompletion { fun first(success: Boolean); fun second(success: Boolean) }
    interface Events { fun error(error: Error); fun start(id: String); fun success() }
    interface WrongEvents { fun error(error: Error); fun start(id: Int); fun success() }
    interface NoCancellation { fun error(error: OtherError); fun start(id: String); fun success() }

    @Test fun completionResolvesRenamedPrimitiveBooleanCallback() {
        assertEquals("renamed", CellRebelCallbacks.completion(Complete::class.java)?.name)
    }

    @Test fun changedOrAmbiguousCompletionIsRejected() {
        assertNull(CellRebelCallbacks.completion(WrongCompletion::class.java))
        assertNull(CellRebelCallbacks.completion(MultipleCompletion::class.java))
    }

    @Test fun cancellationResolvesTheFullEventContractAndEnumName() {
        val cancellation = CellRebelCallbacks.cancellation(Events::class.java)!!
        assertEquals("error", cancellation.method.name)
        assertEquals(Error.CANCELLED, cancellation.error)
    }

    @Test fun changedEventContractOrMissingCancelledValueIsRejected() {
        assertNull(CellRebelCallbacks.cancellation(WrongEvents::class.java))
        assertNull(CellRebelCallbacks.cancellation(NoCancellation::class.java))
    }
}
