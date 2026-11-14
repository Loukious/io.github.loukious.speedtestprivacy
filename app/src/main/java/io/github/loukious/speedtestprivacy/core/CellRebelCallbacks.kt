package io.github.loukious.speedtestprivacy.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Failure protocols verified in CRMeasurementSDK and CellRebelSDK's shipped DEX. */
object CellRebelCallbacks {
    data class Cancellation(val method: Method, val error: Any)

    fun completion(type: Class<*>): Method? {
        if (!type.isInterface) return null
        val method = type.methods.filter { Modifier.isAbstract(it.modifiers) }.singleOrNull() ?: return null
        return method.takeIf { voidMethod(it, java.lang.Boolean.TYPE) }
    }

    fun cancellation(type: Class<*>): Cancellation? {
        if (!type.isInterface) return null
        val methods = type.methods.filter { Modifier.isAbstract(it.modifiers) }
        if (methods.size != 3 || methods.count { voidMethod(it, String::class.java) } != 1 ||
            methods.count { voidMethod(it) } != 1) return null
        val errorMethod = methods.singleOrNull {
            it.returnType == Void.TYPE && it.parameterCount == 1 && it.parameterTypes[0].isEnum
        } ?: return null
        val error = errorMethod.parameterTypes[0].enumConstants.singleOrNull {
            (it as? Enum<*>)?.name == "CANCELLED"
        } ?: return null
        return Cancellation(errorMethod, error)
    }

    private fun voidMethod(method: Method, vararg parameters: Class<*>): Boolean =
        method.returnType == Void.TYPE && method.parameterTypes.contentEquals(parameters)
}
