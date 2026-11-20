package com.aharou.feature.plugin.runtime

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.util.UUID
import kotlinx.serialization.json.*

/** Trusted same-UID bridge, not a sandbox or a DEX method-body rewriter. */
class HostPluginReflection(private val applicationContext: () -> Any) {
    private val prefix = UUID.randomUUID().toString()
    private val objects = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private var serial = 0L
    @Volatile private var closed = false

    @Synchronized
    fun call(operation: String, args: JsonObject): String {
        check(!closed) { "Plugin host was released" }
        val result: Any? = when (operation) {
            "reflect.context" -> applicationContext()
            "reflect.class" -> classFor(args.getValue("name").jsonPrimitive.content)
            "reflect.new" -> {
                val type = resolve(args.getValue("target")) as Class<*>
                val constructor = type.getDeclaredConstructor(*types(args))
                accessible(constructor)
                constructor.newInstance(*arguments(args))
            }
            "reflect.invoke" -> {
                val target = resolve(args.getValue("target"))
                val type = if (target is Class<*>) target else requireNotNull(target).javaClass
                val method = hierarchy(type).mapNotNull { owner ->
                    try { owner.getDeclaredMethod(args.getValue("name").jsonPrimitive.content, *types(args)) }
                    catch (_: NoSuchMethodException) { null }
                }.firstOrNull() ?: error("Method not found")
                accessible(method)
                method.invoke(if (target is Class<*>) null else target, *arguments(args))
            }
            "reflect.get", "reflect.set" -> {
                val target = resolve(args.getValue("target"))
                val type = if (target is Class<*>) target else requireNotNull(target).javaClass
                val field = field(type, args.getValue("name").jsonPrimitive.content)
                accessible(field)
                val receiver = if (target is Class<*>) null else target
                if (operation == "reflect.set") {
                    field.set(receiver, convert(args.getValue("value"), field.type))
                    null
                } else field.get(receiver)
            }
            "reflect.release" -> {
                val id = args.getValue("target").jsonObject.getValue("handle").jsonPrimitive.content
                require(objects.remove(id) != null) { "Unknown plugin handle" }
                null
            }
            else -> error("Unknown reflection operation")
        }
        check(!closed) { "Plugin host was released" }
        return encode(result).toString()
    }

    fun close() { closed = true; objects.clear() }

    private fun accessible(member: AccessibleObject) { member.isAccessible = true }
    private fun hierarchy(type: Class<*>): Sequence<Class<*>> = generateSequence(type) { it.superclass }
    private fun field(type: Class<*>, name: String): Field = hierarchy(type).mapNotNull {
        try { it.getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
    }.firstOrNull() ?: error("Field not found")

    private fun classFor(name: String): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        else -> Class.forName(name, true, javaClass.classLoader)
    }

    private fun types(args: JsonObject): Array<Class<*>> = (args["parameterTypes"] as? JsonArray).orEmpty()
        .map { classFor(it.jsonPrimitive.content) }.toTypedArray()

    private fun arguments(args: JsonObject): Array<Any?> {
        val values = (args["arguments"] as? JsonArray).orEmpty()
        val types = types(args)
        require(values.size == types.size) { "Arguments and parameterTypes must match" }
        return values.mapIndexed { index, value -> convert(value, types[index]) }.toTypedArray()
    }

    private fun convert(value: JsonElement, type: Class<*>): Any? {
        if (value is JsonObject || value == JsonNull) return resolve(value)
        val text = value.jsonPrimitive.content
        return when (type) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> text.toBooleanStrict()
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> text.toByte()
            java.lang.Short.TYPE, java.lang.Short::class.java -> text.toShort()
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> text.toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> text.toLong()
            java.lang.Float.TYPE, java.lang.Float::class.java -> text.toFloat()
            java.lang.Double.TYPE, java.lang.Double::class.java -> text.toDouble()
            java.lang.Character.TYPE, java.lang.Character::class.java -> text.single()
            else -> resolve(value)
        }
    }

    private fun resolve(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> objects[value.getValue("handle").jsonPrimitive.content] ?: error("Unknown plugin handle")
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull
        else -> error("Pass object references as handles")
    }

    private fun encode(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value.toString())
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> {
            val id = "$prefix:${++serial}"
            check(!closed) { "Plugin host was released" }
            objects[id] = value
            if (closed) { objects.remove(id); error("Plugin host was released") }
            buildJsonObject { put("handle", id) }
        }
    }
}
