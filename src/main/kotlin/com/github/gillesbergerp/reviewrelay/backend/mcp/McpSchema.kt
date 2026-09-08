package com.github.gillesbergerp.reviewrelay.backend.mcp

/**
 * The JSON Schema an MCP tool advertises, read off the class its arguments are parsed into.
 *
 * Written by hand the schema and the parsing drift the moment one is edited and the other is not,
 * and an agent is told about a field that is ignored - or not told about one that is read.
 */
internal object McpSchema {

    /** A field the tool takes. Everything without one is not part of the tool's surface. */
    @Target(AnnotationTarget.FIELD)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Argument(val description: String, val required: Boolean = false)

    data class Schema(
        val type: String = "object",
        val properties: Map<String, Property>,
        val required: List<String>? = null,
    )

    data class Property(val type: String, val description: String)

    fun of(arguments: Class<*>): Schema {
        val properties = LinkedHashMap<String, Property>()
        val required = mutableListOf<String>()
        for (field in arguments.declaredFields) {
            val argument = field.getAnnotation(Argument::class.java) ?: continue
            properties[field.name] = Property(typeOf(field.type), argument.description)
            if (argument.required) required += field.name
        }
        return Schema(properties = properties, required = required.takeIf { it.isNotEmpty() })
    }

    private fun typeOf(type: Class<*>): String = when (type) {
        Boolean::class.java, Boolean::class.javaObjectType -> "boolean"
        Int::class.java, Int::class.javaObjectType, Long::class.java, Long::class.javaObjectType -> "integer"
        else -> "string"
    }
}
