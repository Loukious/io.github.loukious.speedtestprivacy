package io.github.loukious.speedtestprivacy.core

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Roles identified from DEX semantics; obfuscated names are discovery results only. */
object TelemetryDiscovery {
    const val FIXED_DENIAL = "permissions.fixedDenial"
    const val LEGACY_UPLOAD = "results.legacyUpload"
    const val BATCH_UPLOAD = "results.batchUpload"
    const val BATCH_AUTOMATIC = "results.batchAutomatic"

    private const val LOCAL_SAVE = "insertTestResultInDB: Download, upload and latency values do not exist."
    private const val EXTENDED_ERROR = "Error submitting extended report"
    private const val QUEUE_LOG = "Reporting Queue processor: %s"

    val targets = listOf(
        SemanticTarget(FIXED_DENIAL, "Permission result Back classification gate", query = { bridge ->
            bridge.findClass {
                matcher {
                    addUsingString("permissionsAccounting", StringMatchType.Equals)
                    addUsingString("permissionsManager", StringMatchType.Equals)
                }
            }.flatMap { owner ->
                owner.methods.filter { method ->
                    method.isMethod && Modifier.isPrivate(method.modifiers) &&
                        !Modifier.isStatic(method.modifiers) && method.paramCount == 0 &&
                        method.returnTypeName == "boolean"
                }
            }
        }, validate = ::fixedDenialShape),
        SemanticTarget(LEGACY_UPLOAD, "Legacy upload after the local result save", query = { bridge ->
            bridge.findClass {
                matcher {
                    addUsingString(LOCAL_SAVE, StringMatchType.Equals)
                    addUsingString(EXTENDED_ERROR, StringMatchType.Equals)
                }
            }.flatMap { owner ->
                owner.methods.filter { method ->
                    method.isMethod && !Modifier.isStatic(method.modifiers) &&
                        method.returnTypeName == "void" && method.paramCount == 2 &&
                        method.paramTypeNames[1] == "java.lang.String" &&
                        "POST" in method.usingStrings && legacyDexClosure(bridge, method)
                }
            }
        }, validate = { method -> runCatching { LegacySubmissionMembers.resolve(method) }.isSuccess }),
        SemanticTarget(BATCH_UPLOAD, "Explicit reporting queue processing with a completed Single", critical = false, query = ::batchUploads,
            validate = { method ->
                concreteInstance(method) && method.parameterCount == 1 &&
                    !method.parameterTypes[0].isPrimitive && !method.returnType.isPrimitive &&
                    runCatching { SingleResultFactory.resolve(method.returnType) }.isSuccess
            }),
        SemanticTarget(BATCH_AUTOMATIC, "Automatic reporting queue processing", critical = false, query = { bridge ->
            batchUploads(bridge).flatMap { upload ->
                upload.declaredClass?.methods.orEmpty().filter { method ->
                    method.isMethod && !Modifier.isStatic(method.modifiers) &&
                        method.returnTypeName == "void" && method.paramTypeNames == upload.paramTypeNames
                }
            }.distinctBy { it.descriptor }
        }, validate = { method -> concreteInstance(method) && method.returnType == Void.TYPE &&
            method.parameterCount == 1 && !method.parameterTypes[0].isPrimitive })
    )

    private fun fixedDenialShape(method: Method): Boolean = concreteInstance(method) &&
        Modifier.isPrivate(method.modifiers) && method.returnType == java.lang.Boolean.TYPE &&
        method.parameterCount == 0 && method.declaringClass.declaredMethods.count {
            concreteInstance(it) && Modifier.isPrivate(it.modifiers) &&
                it.returnType == java.lang.Boolean.TYPE && it.parameterCount == 0
        } == 1

    private fun batchUploads(bridge: DexKitBridge): List<MethodData> = bridge.findClass {
        matcher { addUsingString(QUEUE_LOG, StringMatchType.Equals) }
    }.flatMap { owner ->
        owner.methods.filter { method ->
            method.isMethod && Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                method.paramCount == 1 && method.returnTypeName !in setOf("void", "boolean", "int", "long") &&
                method.invokes.any { factory ->
                    Modifier.isStatic(factory.modifiers) && factory.paramTypeNames == listOf("java.lang.Object") &&
                        factory.returnTypeName == method.returnTypeName && "item is null" in factory.usingStrings
                }
        }
    }

    /** Validate the ID mutation, local-save sentinel, and completion guard before replacing any uploader. */
    private fun legacyDexClosure(bridge: DexKitBridge, upload: MethodData): Boolean {
        val owner = upload.declaredClass ?: return false
        val methods = owner.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) }
        val idSetter = methods.filter { it.returnTypeName == "void" && it.paramTypeNames == listOf("long") }
            .singleOrNull() ?: return false
        val modelSetters = idSetter.invokes.filter { !Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramTypeNames == listOf("long") }.distinctBy { it.descriptor }
        val modelSetter = modelSetters.singleOrNull() ?: return false
        val modelFields = idSetter.usingFields.filter { it.usingType == FieldUsingType.Read &&
            it.field.declaredClassName == owner.name && it.field.typeName == modelSetter.declaredClassName }
            .map { it.field }.distinctBy { it.descriptor }
        if (modelFields.size != 2) return false
        val writes = modelSetter.usingFields.filter { it.usingType == FieldUsingType.Write }
            .map { it.field }.distinctBy { it.descriptor }
        if (writes.size != 1 || writes[0].declaredClassName != modelSetter.declaredClassName || writes[0].typeName != "long")
            return false
        val saves = bridge.findMethod {
            matcher {
                declaredClass(owner.name)
                paramCount(0)
                addUsingString(LOCAL_SAVE, StringMatchType.Equals)
                addUsingNumber(-1L)
            }
        }.filter { save -> !Modifier.isStatic(save.modifiers) && save.returnTypeName != "void" &&
            save.invokes.any { it.descriptor == modelSetter.descriptor } }
        if (saves.size != 1) return false
        val callbackFields = owner.fields.filter { field ->
            !Modifier.isStatic(field.modifiers) && field.type?.let { type ->
                Modifier.isInterface(type.modifiers) && type.methods.filter { Modifier.isAbstract(it.modifiers) }
                    .map { it.returnTypeName to it.paramTypeNames }.toSet() == setOf(
                        "void" to listOf("long"), "void" to listOf("java.lang.Exception"),
                        "void" to listOf("long", "java.lang.Exception")
                    )
            } == true
        }
        val callback = callbackFields.singleOrNull() ?: return false
        val finished = owner.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == "boolean" }
            .singleOrNull() ?: return false
        val guards = methods.filter { method -> method.paramCount == 0 && method.returnTypeName == "boolean" &&
            method.usingFields.filter { it.usingType == FieldUsingType.Read }.map { it.field.descriptor }.toSet() ==
                setOf(callback.descriptor, finished.descriptor) }
        return guards.size == 1
    }

    internal fun concreteInstance(method: Method): Boolean = !Modifier.isStatic(method.modifiers) &&
        !Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers) &&
        !method.isBridge && !method.isSynthetic
}

/** One complete replacement contract, resolved without class, field, or callback method names. */
data class LegacySubmissionMembers(
    val callback: Field,
    val finished: Field,
    val setRemoteId: Method,
    val complete: Method,
    val fail: Method
) {
    companion object {
        fun resolve(upload: Method): LegacySubmissionMembers {
            val owner = upload.declaringClass
            require(TelemetryDiscovery.concreteInstance(upload) && upload.returnType == Void.TYPE &&
                upload.parameterCount == 2 && !upload.parameterTypes[0].isPrimitive &&
                upload.parameterTypes[1] == String::class.java) { "Uploader signature changed" }
            val fields = owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            val finished = fields.filter { it.type == java.lang.Boolean.TYPE && !Modifier.isFinal(it.modifiers) }
                .singleOrNull() ?: error("Finished flag is missing or ambiguous")
            val callbacks = fields.filter { field ->
                field.type.isInterface && callbackMethods(field.type) != null
            }
            val callback = callbacks.singleOrNull() ?: error("Completion callback field is missing or ambiguous")
            val contract = callbackMethods(callback.type)!!
            val setter = owner.declaredMethods.filter { method -> TelemetryDiscovery.concreteInstance(method) &&
                method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE)) }
                .singleOrNull() ?: error("Remote ID setter is missing or ambiguous")
            val guards = owner.declaredMethods.filter { method -> TelemetryDiscovery.concreteInstance(method) &&
                method.returnType == java.lang.Boolean.TYPE && method.parameterCount == 0 }
            require(guards.size == 1) { "Completion guard is missing or ambiguous" }
            return LegacySubmissionMembers(callback.apply { isAccessible = true }, finished.apply { isAccessible = true },
                setter.apply { isAccessible = true }, contract.first.apply { isAccessible = true },
                contract.second.apply { isAccessible = true })
        }

        private fun callbackMethods(type: Class<*>): Pair<Method, Method>? {
            val methods = type.methods.filter { Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            if (methods.size != 3 || methods.any { it.returnType != Void.TYPE }) return null
            val complete = methods.singleOrNull { it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE)) } ?: return null
            val fail = methods.singleOrNull { it.parameterTypes.contentEquals(arrayOf(Exception::class.java)) } ?: return null
            if (methods.count { it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE, Exception::class.java)) } != 1) return null
            return complete to fail
        }
    }
}

/** Derive Single.just from the resolved method's return type, including renamed RxJava builds. */
object SingleResultFactory {
    fun resolve(type: Class<*>): Method = type.declaredMethods.filter { method ->
        Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            !Modifier.isNative(method.modifiers) && method.returnType == type &&
            method.parameterTypes.contentEquals(arrayOf(Any::class.java))
    }.singleOrNull()?.apply { isAccessible = true } ?: error("Completed Single factory is missing or ambiguous")
}
