package io.github.loukious.speedtestprivacy.core

import org.junit.Assert.*
import org.junit.Test

class TelemetryDiscoveryTest {
    interface Continuation { fun resume(owner: Any, id: Any, guid: Any): Any? }
    interface CallbackA {
        fun finish(value: Long)
        fun abort(error: Exception)
        fun partial(value: Long, error: Exception)
    }
    interface CallbackB {
        fun x(value: Long)
        fun y(error: Exception)
        fun z(value: Long, error: Exception)
    }
    private class ObserverA : CallbackA {
        var received: Long? = null
        override fun finish(value: Long) { received = value }
        override fun abort(error: Exception) = Unit
        override fun partial(value: Long, error: Exception) = Unit
    }
    private class ObserverB : CallbackB {
        var received: Long? = null
        override fun x(value: Long) { received = value }
        override fun y(error: Exception) = Unit
        override fun z(value: Long, error: Exception) = Unit
    }
    private class LayoutA(private val observer: CallbackA) {
        private var closed = false
        @JvmField var primaryId = 12L
        @JvmField var copiedId = 12L
        fun transmit(continuation: Continuation, guid: String) = Unit
        fun replaceId(value: Long) { primaryId = value; copiedId = value }
        private fun eligible(): Boolean = !closed
    }
    private class LayoutB(private val z: CallbackB) {
        private var a = false
        @JvmField var b = 9L
        @JvmField var c = 9L
        fun q(x: Continuation, y: String) = Unit
        fun r(x: Long) { b = x; c = x }
        private fun s(): Boolean = !a
    }
    private class TwoCallbacks(private val left: CallbackA, private val right: CallbackB) {
        private var closed = false
        fun upload(c: Continuation, guid: String) = Unit
        fun id(value: Long) = Unit
        private fun eligible(): Boolean = !closed
    }
    private class TwoFlags(private val callback: CallbackA) {
        private var one = false
        private var two = false
        fun upload(c: Continuation, guid: String) = Unit
        fun id(value: Long) = Unit
        private fun eligible(): Boolean = !one && !two
    }
    private class TwoSetters(private val callback: CallbackA) {
        private var closed = false
        fun upload(c: Continuation, guid: String) = Unit
        fun one(value: Long) = Unit
        fun two(value: Long) = Unit
        private fun eligible(): Boolean = !closed
    }
    private class TwoGuards(private val callback: CallbackA) {
        private var closed = false
        fun upload(c: Continuation, guid: String) = Unit
        fun id(value: Long) = Unit
        private fun one(): Boolean = !closed
        private fun two(): Boolean = closed
    }
    interface IncompatibleCallback {
        fun finish(value: Long)
        fun abort(error: Throwable)
        fun partial(value: Long, error: Exception)
    }
    private class BadCallback(private val callback: IncompatibleCallback) {
        private var closed = false
        fun upload(c: Continuation, guid: String) = Unit
        fun id(value: Long) = Unit
        private fun eligible(): Boolean = !closed
    }
    private class RenamedSingle(val value: Any) {
        companion object {
            @JvmStatic fun emit(value: Any): RenamedSingle = RenamedSingle(value)
        }
    }
    private class AmbiguousSingle(val value: Any) {
        companion object {
            @JvmStatic fun emit(value: Any): AmbiguousSingle = AmbiguousSingle(value)
            @JvmStatic fun alternative(value: Any): AmbiguousSingle = AmbiguousSingle(value)
        }
    }

    @Test fun resolvesRenamedCompletionContractAndPreservesBothResultCopies() {
        val observerA = ObserverA()
        val a = LayoutA(observerA)
        val ma = LegacySubmissionMembers.resolve(LayoutA::class.java.getDeclaredMethod("transmit", Continuation::class.java, String::class.java))
        ma.setRemoteId.invoke(a, -1L)
        ma.finished.setBoolean(a, true)
        ma.complete.invoke(ma.callback.get(a), -1L)
        assertEquals(-1L, a.primaryId)
        assertEquals(-1L, a.copiedId)
        assertEquals(-1L, observerA.received)
        assertTrue(ma.finished.getBoolean(a))

        val observerB = ObserverB()
        val b = LayoutB(observerB)
        val mb = LegacySubmissionMembers.resolve(LayoutB::class.java.getDeclaredMethod("q", Continuation::class.java, String::class.java))
        mb.setRemoteId.invoke(b, -1L)
        mb.finished.setBoolean(b, true)
        mb.complete.invoke(mb.callback.get(b), -1L)
        assertEquals(-1L, b.b)
        assertEquals(-1L, b.c)
        assertEquals(-1L, observerB.received)
        assertTrue(mb.finished.getBoolean(b))
        assertNotEquals(ma.callback.name, mb.callback.name)
        assertNotEquals(ma.complete.name, mb.complete.name)
        assertNotEquals(ma.setRemoteId.name, mb.setRemoteId.name)
    }

    @Test fun rejectsAmbiguousMembersInsteadOfSelectingFirstMatch() {
        for (type in listOf(TwoCallbacks::class.java, TwoFlags::class.java, TwoSetters::class.java, TwoGuards::class.java)) {
            val upload = type.getDeclaredMethod("upload", Continuation::class.java, String::class.java)
            assertTrue(type.name, runCatching { LegacySubmissionMembers.resolve(upload) }.isFailure)
        }
    }

    @Test fun rejectsChangedFailureCallbackContract() {
        val upload = BadCallback::class.java.getDeclaredMethod("upload", Continuation::class.java, String::class.java)
        assertTrue(runCatching { LegacySubmissionMembers.resolve(upload) }.isFailure)
    }

    @Test fun derivesCompletedSingleFromRenamedReturnType() {
        val factory = SingleResultFactory.resolve(RenamedSingle::class.java)
        val result = factory.invoke(null, 0) as RenamedSingle
        assertEquals(0, result.value)
        assertTrue(runCatching { SingleResultFactory.resolve(AmbiguousSingle::class.java) }.isFailure)
    }
}
