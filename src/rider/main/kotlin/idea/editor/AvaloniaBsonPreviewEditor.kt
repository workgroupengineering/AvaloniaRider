package me.fornever.avaloniarider.idea.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AvaloniaBsonPreviewEditor(
    project: Project,
    currentFile: VirtualFile
) : AvaloniaPreviewEditorBase(project, currentFile) {

    private val panel = lazy {
        sessionController.start(currentFile)
        BitmapPreviewEditorComponent(lifetime, sessionController)
    }

    override fun getComponent() = panel.value
}
