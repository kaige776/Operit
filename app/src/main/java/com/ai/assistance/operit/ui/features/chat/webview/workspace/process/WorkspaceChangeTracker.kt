package com.ai.assistance.operit.ui.features.chat.webview.workspace.process

import android.content.Context
import com.ai.assistance.operit.ui.features.chat.webview.workspace.WatchConfig
import com.ai.assistance.operit.ui.features.chat.webview.workspace.WorkspaceConfigReader
import com.ai.assistance.operit.util.AppLogger
import java.io.File

data class WorkspaceChangeSnapshot(
    val changes: List<WorkspaceFileChange>,
    val omittedCount: Int
)

class WorkspaceChangeTracker private constructor(private val context: Context) {
    companion object {
        private const val TAG = "WorkspaceChangeTracker"

        @Volatile
        private var instance: WorkspaceChangeTracker? = null

        fun getInstance(context: Context): WorkspaceChangeTracker {
            return instance ?: synchronized(this) {
                instance ?: WorkspaceChangeTracker(context.applicationContext).also { instance = it }
            }
        }
    }

    private data class OwnerBinding(
        val chatId: String,
        val workspacePath: String,
        val workspaceEnv: String?
    )

    private data class MonitorKey(
        val workspacePath: String,
        val workspaceEnv: String?
    )

    private data class TrackedChange(
        val relativePath: String,
        val kind: WorkspaceFileChangeKind
    )

    private class MonitorState(
        val observer: DepthLimitedFileObserver,
        val maxChangedFiles: Int
    ) {
        val changes = LinkedHashMap<String, TrackedChange>()
        var omittedCount: Int = 0
    }

    private val ownerBindings = LinkedHashMap<String, OwnerBinding>()
    private val monitors = LinkedHashMap<MonitorKey, MonitorState>()

    @Synchronized
    fun updateOwner(
        ownerId: String,
        chatId: String?,
        workspacePath: String?,
        workspaceEnv: String?
    ) {
        if (chatId.isNullOrBlank() || workspacePath.isNullOrBlank() || !workspaceEnv.isNullOrBlank()) {
            ownerBindings.remove(ownerId)
            refreshMonitors()
            return
        }

        ownerBindings[ownerId] =
            OwnerBinding(
                chatId = chatId,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv
            )
        refreshMonitors()
    }

    @Synchronized
    fun clearOwner(ownerId: String) {
        ownerBindings.remove(ownerId)
        refreshMonitors()
    }

    @Synchronized
    fun consumeChanges(
        chatId: String?,
        workspacePath: String?,
        workspaceEnv: String?
    ): WorkspaceChangeSnapshot {
        if (chatId.isNullOrBlank() || workspacePath.isNullOrBlank() || !workspaceEnv.isNullOrBlank()) {
            return WorkspaceChangeSnapshot(emptyList(), 0)
        }

        val key = MonitorKey(workspacePath, workspaceEnv)
        val state = monitors[key] ?: return WorkspaceChangeSnapshot(emptyList(), 0)
        val result =
            state.changes.values.map { change ->
                WorkspaceFileChange(
                    relativePath = change.relativePath,
                    kind = change.kind
                )
            }
        val omittedCount = state.omittedCount
        state.changes.clear()
        state.omittedCount = 0
        return WorkspaceChangeSnapshot(result, omittedCount)
    }

    @Synchronized
    private fun refreshMonitors() {
        val requiredKeys =
            ownerBindings.values
                .map { MonitorKey(it.workspacePath, it.workspaceEnv) }
                .toSet()

        val obsoleteKeys = monitors.keys.filterNot { it in requiredKeys }
        obsoleteKeys.forEach { key ->
            monitors.remove(key)?.observer?.stop()
        }

        requiredKeys.forEach { key ->
            if (!monitors.containsKey(key)) {
                createMonitor(key)?.let { state ->
                    monitors[key] = state
                    state.observer.start()
                }
            }
        }
    }

    private fun createMonitor(key: MonitorKey): MonitorState? {
        val workspaceDir = File(key.workspacePath)
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) return null

        val config = WorkspaceConfigReader.readConfig(key.workspacePath).watch
        if (!config.enabled) return null

        val rules = buildIgnoreRules(workspaceDir, config)
        lateinit var state: MonitorState
        val observer =
            DepthLimitedFileObserver(
                rootDir = workspaceDir,
                maxDepth = config.maxDepth,
                shouldIgnore = { relativePath, isDirectory ->
                    val rel = relativePath.trimStart('/').replace('\\', '/')
                    if (rel.isBlank()) {
                        false
                    } else {
                        GitIgnoreFilter.shouldIgnore(
                            relativePath = rel,
                            fileName = rel.substringAfterLast('/'),
                            isDirectory = isDirectory,
                            rules = rules
                        )
                    }
                },
                onChange = { change ->
                    synchronized(this@WorkspaceChangeTracker) {
                        recordChange(state, change)
                    }
                }
            )
        state = MonitorState(
            observer = observer,
            maxChangedFiles = config.maxChangedFiles.coerceAtLeast(1)
        )
        return state
    }

    private fun buildIgnoreRules(workspaceDir: File, config: WatchConfig): List<String> {
        val rules = LinkedHashSet<String>()
        rules.addAll(GitIgnoreFilter.loadRules(workspaceDir))
        config.exclude
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { rule -> rules.add(rule) }
        return rules.toList()
    }

    private fun recordChange(state: MonitorState, change: WorkspaceFileChange) {
        val relativePath = change.relativePath.trimStart('/').replace('\\', '/')
        if (relativePath.isBlank()) return

        if (!state.changes.containsKey(relativePath) && state.changes.size >= state.maxChangedFiles) {
            state.omittedCount += 1
            return
        }

        state.changes[relativePath] =
            TrackedChange(
                relativePath = relativePath,
                kind = change.kind
            )
        AppLogger.d(TAG, "Workspace change recorded: ${change.kind} $relativePath")
    }
}
