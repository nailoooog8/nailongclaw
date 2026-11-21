// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import android.content.Context
import io.agents.nailongclaw.agent.CloudProvider
import io.agents.nailongclaw.agent.llm.LocalModelManager
import io.agents.nailongclaw.agent.llm.ModelConfigRepository
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.utils.XLog

/**
 * Assembles the model catalog the two chat surfaces share — the parts of the
 * projection that need Android ([LocalModelManager] reads the model directory
 * and the device's RAM, cloud keys live in encrypted prefs).
 *
 * Everything that *decides* stays in [ModelCatalogBridge] (pure, unit-tested);
 * this class only gathers the inputs and performs the write, so there is one
 * assembly, not one per surface. Both doors — the WebView model card
 * (`readModelCatalog` / `selectModel` in [ClaudeUiPreviewActivity]) and the
 * Compose chat screen's dropdown ([io.agents.nailongclaw.ui.chat.ChatTopBar])
 * — eat rows from [options] and write through [activate]; an assembly forked
 * per surface is how one door ends up listing a model the other refuses.
 *
 * Threading: [options] touches disk (file existence checks) and decrypts up to
 * one key per provider, so callers keep it off a hot path — the WebView card
 * calls it on the JavaScript thread when the card opens, the Compose screen on
 * resume and after a switch. The same trade-off [ClaudeUiPreviewActivity]
 * already documents for its per-open reads: small local reads run inline, and
 * the tempting fingerprint shortcut is rejected for the same reason — a row
 * marked usable must be a row the client can actually build.
 */
object ModelCatalogSource {

    private const val TAG = "ModelCatalogSource"

    /**
     * Every model this install can select, as card rows.
     *
     * Local first, then cloud in [CloudProvider] declaration order — the same
     * order the native 模型与任务 page lists, so the two doors cannot disagree
     * about which model is which. The custom endpoint is appended last and only
     * when one is actually configured: [CloudProvider.CUSTOM] declares no
     * models of its own, and without the synthesized row the card would show
     * nothing selected while the agent runs on it.
     */
    fun options(context: Context): List<ModelCatalogBridge.ModelOption> {
        val snapshot = ModelConfigRepository.snapshot()
        val local = LocalModelManager.catalog(context).map { entry ->
            val availability = LocalModelManager.availabilityForModel(context, entry.model, snapshot.local)
            ModelCatalogBridge.ModelOption(
                kind = ModelCatalogBridge.KIND_LOCAL,
                id = entry.model.id,
                label = entry.model.displayName,
                meta = localModelMeta(entry.model),
                usable = availability.isAvailable,
                supported = entry.isSupported,
            )
        }
        val cloud = CloudProvider.entries
            .filter { it != CloudProvider.CUSTOM }
            .flatMap { provider ->
                val hasKey = hasCloudKey(provider.name)
                provider.models.map { model ->
                    ModelCatalogBridge.ModelOption(
                        kind = ModelCatalogBridge.KIND_CLOUD,
                        id = model.id,
                        label = model.displayName,
                        meta = "${model.tier.label} · ${formatContextSize(model.contextSize)}",
                        provider = provider.name,
                        providerLabel = provider.displayName,
                        usable = hasKey,
                    )
                }
            }
        val custom = ModelCatalogBridge.customCloudOption(
            providerName = snapshot.defaultCloud.providerName,
            modelName = snapshot.defaultCloud.modelName,
            hasKey = hasCloudKey(CloudProvider.CUSTOM.name),
        )
        return local + cloud + listOfNotNull(custom)
    }

    /** Which row the agent is actually using right now. */
    fun activeSelection(): ModelCatalogBridge.ActiveSelection {
        val snapshot = ModelConfigRepository.snapshot()
        return if (snapshot.isLocalActive()) {
            ModelCatalogBridge.ActiveSelection(
                kind = ModelCatalogBridge.KIND_LOCAL,
                id = snapshot.local.modelId,
            )
        } else {
            ModelCatalogBridge.ActiveSelection(
                kind = ModelCatalogBridge.KIND_CLOUD,
                id = snapshot.activeCloud.modelName,
                provider = snapshot.activeCloud.providerName,
            )
        }
    }

    /**
     * Write the selection.
     *
     * The local branch resolves the file the same way LlmConfigActivity does —
     * a managed download and a user-linked file live in different places, and
     * asking for the managed path of a linked model would activate a file that
     * is not there.
     */
    fun activate(context: Context, selection: ModelCatalogBridge.ActiveSelection) {
        if (selection.kind == ModelCatalogBridge.KIND_LOCAL) {
            val snapshot = ModelConfigRepository.snapshot()
            val entry = LocalModelManager.catalog(context)
                .firstOrNull { it.model.id == selection.id } ?: run {
                XLog.e(TAG, "activate local '${selection.id}' refused — not in the catalog")
                return
            }
            val availability = LocalModelManager.availabilityForModel(context, entry.model, snapshot.local)
            val path = when (availability.source) {
                LocalModelManager.AvailabilitySource.MANAGED_DOWNLOAD ->
                    LocalModelManager.getModelPath(context, entry.model)
                LocalModelManager.AvailabilitySource.LINKED_FILE -> snapshot.local.modelPath
                LocalModelManager.AvailabilitySource.MISSING -> null
            }
            if (path.isNullOrBlank()) {
                XLog.e(TAG, "activate local '${selection.id}' refused — no file on disk")
                return
            }
            ModelConfigRepository.saveLocalDefault(path, entry.model.id, activateNow = true)
            return
        }
        // No explicit base URL: activateCloudSelection then reuses the stored
        // default for this provider, which is where a custom endpoint's URL
        // lives. Passing the provider default here would wipe it.
        ModelConfigRepository.activateCloudSelection(
            modelId = selection.id,
            explicitProviderName = selection.provider,
        )
    }

    /**
     * Whether a provider has a key, resolved exactly the way the runtime
     * resolves it (per-provider first, the legacy single key as fallback) — a
     * row marked usable here must be a row the client can actually build.
     */
    fun hasCloudKey(provider: String): Boolean =
        KVUtils.getApiKeyForProvider(provider)
            .ifEmpty { KVUtils.getLlmApiKey() }
            .isNotBlank()

    /** Size and RAM requirement, or a plain label for a user-supplied file. */
    private fun localModelMeta(model: LocalModelManager.ModelInfo): String =
        if (model.isCustom) "自定义模型文件"
        else "${model.sizeBytes / 1_000_000} MB · 需 ${model.minRamGb}GB+ 内存"

    private fun formatContextSize(tokens: Int): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        else -> "${tokens / 1_000}K"
    }
}
