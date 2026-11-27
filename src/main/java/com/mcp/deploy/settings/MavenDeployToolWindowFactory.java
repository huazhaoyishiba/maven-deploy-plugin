package com.mcp.deploy.settings;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 注册右侧边栏 Tool Window 面板。
 */
public class MavenDeployToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        Disposable lifetime = Disposer.newDisposable();
        MavenDeployToolWindowPanel panel = new MavenDeployToolWindowPanel(project, lifetime);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(lifetime);
        toolWindow.getContentManager().addContent(content);
    }
}
