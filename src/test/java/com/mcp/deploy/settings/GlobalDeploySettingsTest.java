package com.mcp.deploy.settings;

import com.intellij.mock.MockApplication;
import com.intellij.mock.MockProject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.xmlb.XmlSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GlobalDeploySettingsTest {
    private final Disposable lifetime = Disposer.newDisposable();
    private MockApplication application;

    @BeforeEach
    void startApplication() {
        application = MockApplication.setUp(lifetime);
    }

    @AfterEach
    void stopApplication() {
        Disposer.dispose(lifetime);
    }

    @Test
    void twoProjectsShareTheSameServiceAndReceiveChanges() throws Exception {
        GlobalDeploySettings shared = new GlobalDeploySettings();
        application.registerService(GlobalDeploySettings.class, shared);
        MockProject emptyProject = new MockProject(null, lifetime);
        emptyProject.registerService(MavenDeploySettings.class, new MavenDeploySettings());
        MockProject configuredProject = new MockProject(null, lifetime);
        MavenDeploySettings legacy = new MavenDeploySettings();
        legacy.servers.add(new MavenDeploySettings.ServerConfig());
        configuredProject.registerService(MavenDeploySettings.class, legacy);

        AtomicInteger changes = new AtomicInteger();
        application.getMessageBus().connect(lifetime).subscribe(GlobalDeploySettings.CHANGED, changes::incrementAndGet);
        SwingUtilities.invokeAndWait(() -> {
            assertSame(shared, GlobalDeploySettings.getInstance(emptyProject));
            assertTrue(shared.snapshot().servers.isEmpty());
            GlobalDeploySettings fromOldProject = GlobalDeploySettings.getInstance(configuredProject);
            fromOldProject.setPluginEnabled(false);
            fromOldProject.setAutoUpload(legacy.servers.get(0).id, false);
        });
        MavenDeploySettings fromEmptyProject = GlobalDeploySettings.getInstance(emptyProject).snapshot();
        assertFalse(fromEmptyProject.pluginEnabled);
        assertFalse(fromEmptyProject.servers.get(0).autoUpload);
        assertEquals(3, changes.get());
    }

    @Test
    void firstConfiguredProjectSuppliesTheSharedSettings() {
        GlobalDeploySettings shared = new GlobalDeploySettings();
        assertFalse(shared.importLegacyIfNeeded(new MavenDeploySettings()));

        MavenDeploySettings legacy = new MavenDeploySettings();
        legacy.sshHost = "example.test";
        legacy.sshPort = 2222;
        legacy.sshPassword = "test-password";
        legacy.remoteDir = "/srv/demo";
        legacy.enabled = false;
        legacy.pluginEnabled = false;
        legacy.connectTimeout = 25;
        MavenDeploySettings oldProject = new MavenDeploySettings();
        oldProject.loadState(legacy);
        assertTrue(shared.importLegacyIfNeeded(oldProject));

        MavenDeploySettings view = shared.snapshot();
        assertFalse(view.pluginEnabled);
        assertEquals(25, view.connectTimeout);
        MavenDeploySettings.ServerConfig server = view.servers.get(0);
        assertEquals("example.test", server.host);
        assertEquals(2222, server.port);
        assertEquals("test-password", server.password);
        assertEquals("/srv/demo", server.remoteDir);
        assertFalse(server.autoUpload);

        server.host = "another.example.test";
        assertEquals("example.test", shared.snapshot().servers.get(0).host);
        assertFalse(shared.importLegacyIfNeeded(oldProject));
    }

    @Test
    void savedGlobalConfigurationSurvivesRestartAndWinsOverOtherProjects() {
        MavenDeploySettings oldProject = new MavenDeploySettings();
        MavenDeploySettings.ServerConfig server = new MavenDeploySettings.ServerConfig();
        server.remoteFileName = "app.jar";
        server.keyPassphrase = "test-passphrase";
        server.remoteCommand = "echo done";
        oldProject.servers.add(server);

        GlobalDeploySettings initial = new GlobalDeploySettings();
        assertTrue(initial.importLegacyIfNeeded(oldProject));
        GlobalDeploySettings restored = new GlobalDeploySettings();
        var state = initial.getState();
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), state.getClass()));

        assertFalse(restored.importLegacyIfNeeded(oldProject));
        MavenDeploySettings.ServerConfig saved = restored.snapshot().servers.get(0);
        assertEquals(server.id, saved.id);
        assertEquals("app.jar", saved.remoteFileName);
        assertEquals("test-passphrase", saved.keyPassphrase);
        assertEquals("echo done", saved.remoteCommand);
        assertTrue(saved.autoUpload);
    }

    @Test
    void simultaneousEditsPreserveSwitchesAndDeletionSurvivesRestart() {
        GlobalDeploySettings shared = new GlobalDeploySettings();
        MavenDeploySettings.ServerConfig original = new MavenDeploySettings.ServerConfig();
        assertTrue(shared.saveServer(original, null));
        MavenDeploySettings.ServerConfig edit = original.copy();
        edit.host = "server.example.test";
        shared.setAutoUpload(edit.id, false);
        assertTrue(shared.saveServer(edit, original));
        assertFalse(shared.snapshot().servers.get(0).autoUpload);
        assertFalse(shared.saveServer(original, original));
        shared.removeServers(List.of(edit.id));
        assertFalse(shared.saveServer(edit, edit));

        GlobalDeploySettings restored = new GlobalDeploySettings();
        var state = shared.getState();
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), state.getClass()));
        MavenDeploySettings legacy = new MavenDeploySettings();
        legacy.servers.add(original);
        assertFalse(restored.importLegacyIfNeeded(legacy));
        assertTrue(restored.snapshot().servers.isEmpty());
    }
}
