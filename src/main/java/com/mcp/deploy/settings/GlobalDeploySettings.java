package com.mcp.deploy.settings;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import com.mcp.deploy.settings.MavenDeploySettings.ServerConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 当前 IDE 实例中所有项目共用的部署配置。 */
@State(
    name = "GlobalMavenDeploySettings",
    storages = @Storage(value = "maven-deploy-plugin.xml", roamingType = RoamingType.DISABLED)
)
public final class GlobalDeploySettings implements PersistentStateComponent<MavenDeploySettings> {

    public static final Topic<Runnable> CHANGED = Topic.create("Maven Deploy settings changed", Runnable.class);

    private MavenDeploySettings state = new MavenDeploySettings();

    public static GlobalDeploySettings getInstance(@NotNull Project project) {
        GlobalDeploySettings global = ApplicationManager.getApplication().getService(GlobalDeploySettings.class);
        if (!global.isInitialized()
            && global.importLegacyIfNeeded(project.getService(MavenDeploySettings.class))) {
            publishChange();
        }
        return global;
    }

    private synchronized boolean isInitialized() {
        return state.initialized;
    }

    synchronized boolean importLegacyIfNeeded(MavenDeploySettings legacy) {
        if (state.initialized || legacy.servers.isEmpty()) return false;
        state = legacy.copy();
        state.initialized = true;
        return true;
    }

    public synchronized MavenDeploySettings snapshot() {
        return state.copy();
    }

    @Override
    public MavenDeploySettings getState() {
        return snapshot();
    }

    @Override
    public void loadState(@NotNull MavenDeploySettings saved) {
        synchronized (this) {
            state = saved.copy();
        }
        publishChange();
    }

    public void setPluginEnabled(boolean enabled) {
        synchronized (this) {
            state.pluginEnabled = enabled;
            state.initialized = true;
        }
        publishChange();
    }

    /** original 为打开表单时的快照。记录已删除或在其他窗口编辑过时返回 false。 */
    public boolean saveServer(ServerConfig server, @Nullable ServerConfig original) {
        synchronized (this) {
            int index = state.servers.indexOf(server);
            ServerConfig updated = server.copy();
            if (original == null) {
                if (index >= 0) return false;
                state.servers.add(updated);
            } else {
                if (index < 0 || !server.equals(original)) return false;
                ServerConfig current = state.servers.get(index);
                if (!current.hasSameFormValues(original)) return false;
                updated.autoUpload = current.autoUpload;
                state.servers.set(index, updated);
            }
            state.initialized = true;
        }
        publishChange();
        return true;
    }

    public void removeServers(List<String> ids) {
        synchronized (this) {
            if (!state.servers.removeIf(server -> ids.contains(server.id))) return;
            state.initialized = true;
        }
        publishChange();
    }

    public void setAutoUpload(String id, boolean enabled) {
        synchronized (this) {
            int index = state.servers.indexOf(new ServerConfig(id));
            if (index < 0) return;
            state.servers.get(index).autoUpload = enabled;
            state.initialized = true;
        }
        publishChange();
    }

    /** 避免在持有状态锁时调用其他项目的 Swing 监听器。 */
    private static void publishChange() {
        Application app = ApplicationManager.getApplication();
        Runnable notify = () -> app.getMessageBus().syncPublisher(CHANGED).run();
        if (app.isDispatchThread()) {
            notify.run();
        } else {
            app.invokeLater(notify);
        }
    }
}
