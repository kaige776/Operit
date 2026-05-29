package com.ai.assistance.operit.ui.features.chat.webview.workspace.process

object WorkspaceAttachmentProcessor {
    fun generateWorkspaceAttachment(
        workspaceEnv: String?,
        changes: WorkspaceChangeSnapshot = WorkspaceChangeSnapshot(emptyList(), 0)
    ): String {
        val workspaceTag = workspaceEnv?.trim().orEmpty()
        return buildString {
            appendLine("当前对话已附着工作区。")
            if (workspaceTag.isNotEmpty()) {
                appendLine("工作区环境：${escapeText(workspaceTag)}")
            }
            if (changes.changes.isNotEmpty() || changes.omittedCount > 0) {
                appendLine("工作区文件变化：")
                changes.changes.forEach { change ->
                    appendLine("- ${change.kind.displayName()} ${escapeText(change.relativePath)}")
                }
                if (changes.omittedCount > 0) {
                    appendLine("- 还有 ${changes.omittedCount} 个变化未列出")
                }
            }
        }.trim()
    }

    private fun WorkspaceFileChangeKind.displayName(): String {
        return when (this) {
            WorkspaceFileChangeKind.CREATED -> "新增"
            WorkspaceFileChangeKind.MODIFIED -> "修改"
            WorkspaceFileChangeKind.DELETED -> "删除"
            WorkspaceFileChangeKind.MOVED -> "移动"
        }
    }

    private fun escapeText(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
