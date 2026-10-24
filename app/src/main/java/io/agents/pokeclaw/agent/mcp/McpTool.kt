// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import com.google.gson.JsonObject
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * A remote MCP tool wrapped as a PokeClaw BaseTool so it participates in
 * ToolRegistry scheduling exactly like built-in tools.
 */
class McpTool(
    private val server: McpServerConfig,
    private val remote: McpStreamableHttpClient.McpRemoteTool,
    private val client: McpStreamableHttpClient
) : BaseTool() {

    companion object {
        /** JSON Schema type -> PokeClaw ToolParameter type */
        private fun mapType(schemaType: String?): String = when (schemaType) {
            "integer" -> "integer"
            "number" -> "number"
            "boolean" -> "boolean"
            else -> "string" // string, arrays and objects are passed as serialized strings
        }

        /** Params shown on the confirmation card before the rest are elided. */
        private const val MAX_PREVIEW_PARAMS = 4

        /** Characters of one param value before it is cut with an ellipsis. */
        private const val MAX_PREVIEW_VALUE_CHARS = 120

        private fun schemaToParameters(schema: JsonObject?): List<ToolParameter> {
            if (schema == null) return emptyList()
            val properties = schema.getAsJsonObject("properties") ?: return emptyList()
            val required: Set<String> = schema.getAsJsonArray("required")
                ?.mapNotNull { (it as? com.google.gson.JsonPrimitive)?.takeIf { p -> p.isString }?.asString }
                ?.toSet() ?: emptySet()
            val out = ArrayList<ToolParameter>(properties.size())
            for (entry in properties.entrySet()) {
                val paramName = entry.key
                val def = entry.value.asJsonObject
                out.add(
                    ToolParameter(
                        name = paramName,
                        type = mapType(def.get("type")?.takeIf { !it.isJsonNull }?.asString),
                        description = def.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        isRequired = paramName in required
                    )
                )
            }
            return out
        }
    }

    private val registeredName: String = "mcp_${server.safeName()}_${remote.name.replace(Regex("[^a-zA-Z0-9_]"), "_")}"
    private val mappedParams: List<ToolParameter> = schemaToParameters(remote.inputSchema)

    // The description comes from a third-party server and is rendered into the
    // system prompt. Sanitize once at construction rather than on every call,
    // and log when the text looked like an injection attempt.
    private val sanitizedDescription: McpDescriptionSanitizer.Sanitized =
        McpDescriptionSanitizer.sanitize(remote.description)

    init {
        if (sanitizedDescription.suspicious) {
            XLog.w(
                "McpTool",
                "Server '${server.name}' tool '${remote.name}' carries instruction-like text; " +
                    "description was neutralized before reaching the prompt"
            )
        }
    }

    override fun getName(): String = registeredName

    override fun getParameters(): List<ToolParameter> = mappedParams

    override fun getDescriptionEN(): String =
        "[MCP:${server.name}] ${sanitizedDescription.text.ifEmpty { "Remote tool ${remote.name}" }}"

    override fun getDescriptionCN(): String =
        "［MCP:${server.name}］${sanitizedDescription.text.ifEmpty { "远程工具 ${remote.name}" }}（来自第三方 MCP 服务器 ${server.name}）"

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            ToolResult.success(client.callTool(remote.name, params))
        } catch (e: Exception) {
            ToolResult.error("MCP tool '${remote.name}' failed: ${e.message}")
        }
    }

    /**
     * What the confirmation card shows for a call with [params].
     *
     * Built here rather than at the call site because this is the only place
     * that knows which server the tool came from and what its sanitized
     * description says. The description has already been through
     * [McpDescriptionSanitizer], so a server cannot smuggle instructions into
     * the card the user is asked to trust.
     *
     * The params preview is deliberately short: the card exists to answer "do
     * I let this run?", not to be a debugger. Long values are cut, never
     * dropped silently — the cut is visible as an ellipsis.
     */
    fun describeForConfirmation(params: Map<String, Any>): McpConfirmationRequest {
        val preview = params.entries
            .sortedBy { it.key }
            .take(MAX_PREVIEW_PARAMS)
            .joinToString("\n") { (key, value) -> "$key = ${previewValue(value)}" }
        val omitted = params.size - minOf(params.size, MAX_PREVIEW_PARAMS)
        return McpConfirmationRequest(
            toolName = registeredName,
            displayName = remote.name,
            serverName = server.name,
            description = sanitizedDescription.text.ifBlank {
                "远程工具 ${remote.name}（来自 MCP 服务器 ${server.name}）"
            },
            paramsPreview = if (omitted > 0) "$preview\n…另有 $omitted 个参数" else preview,
        )
    }

    private fun previewValue(value: Any?): String {
        val text = value?.toString().orEmpty().replace('\n', ' ').replace('\r', ' ')
        return if (text.length <= MAX_PREVIEW_VALUE_CHARS) text else text.take(MAX_PREVIEW_VALUE_CHARS) + "…"
    }
}
