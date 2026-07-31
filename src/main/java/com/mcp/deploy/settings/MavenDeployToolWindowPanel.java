package com.mcp.deploy.settings;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven Deploy 右侧面板 — 列表视图 + 编辑表单双模式。
 */
public class MavenDeployToolWindowPanel extends JPanel {

    private final Project project;
    private final MavenDeploySettings settings;

    // ===== 卡片布局：列表 / 编辑表单 =====
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private static final String CARD_LIST = "list";
    private static final String CARD_EDIT = "edit";

    // ===== 列表视图组件 =====
    private JBCheckBox globalEnabledCheckBox;
    private JBTable serverTable;
    private ServerTableModel tableModel;
    private JButton newBtn;
    private JButton editBtn;
    private JButton deleteBtn;
    private JButton uploadSelectedBtn;
    private JLabel statusLabel;
    private JProgressBar progressBar;

    // ===== 编辑表单组件 =====
    private JLabel formTitleLabel;
    private JBTextField editNameField;
    private JBTextField editHostField;
    private JBTextField editPortField;
    private JBTextField editUserField;
    private JBPasswordField editPasswordField;
    private JBTextField editPrivateKeyField;
    private JBPasswordField editKeyPassphraseField;
    private JBTextField editRemoteDirField;
    private JBTextArea editRemoteCommandField;
    private JButton editSaveBtn;
    private JButton editCancelBtn;
    private JLabel editStatusLabel;
    private String editingServerId = null; // null=新建, 非null=编辑

    public MavenDeployToolWindowPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.settings = MavenDeploySettings.getInstance(project);

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);

        cardPanel.add(buildListView(), CARD_LIST);
        cardPanel.add(buildEditView(), CARD_EDIT);

        add(cardPanel, BorderLayout.CENTER);

        showList();
    }

    // ==========================================
    //  列表视图
    // ==========================================

    private JPanel buildListView() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(8));

        // --- 顶部：标题 + 按钮 + 开关 ---
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(JBUI.Borders.emptyBottom(6));

        // 标题
        JLabel title = new JLabel("服务器列表");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        header.add(title, BorderLayout.WEST);

        // 中间：新建/编辑/删除按钮
        JPanel centerBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        newBtn = new JButton("新建", AllIcons.General.Add);
        newBtn.addActionListener(e -> openEditForm(null));
        editBtn = new JButton("编辑", AllIcons.Actions.Edit);
        editBtn.addActionListener(e -> editSelected());
        deleteBtn = new JButton("删除", AllIcons.Actions.GC);
        deleteBtn.addActionListener(e -> deleteSelected());
        centerBtns.add(newBtn);
        centerBtns.add(editBtn);
        centerBtns.add(deleteBtn);
        header.add(centerBtns, BorderLayout.CENTER);

        // 右侧：上传选中 + 启用开关
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        globalEnabledCheckBox = new JBCheckBox("启用插件");
        uploadSelectedBtn = new JButton("上传选中", AllIcons.Actions.Upload);
        uploadSelectedBtn.addActionListener(e -> uploadSelected());
        headerRight.add(uploadSelectedBtn);
        headerRight.add(globalEnabledCheckBox);
        header.add(headerRight, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        // --- 底部状态栏：文字 + 进度条 ---
        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.setBorder(JBUI.Borders.empty(4, 0, 0, 0));

        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(JBColor.GRAY);
        bottomBar.add(statusLabel, BorderLayout.CENTER);

        progressBar = new JProgressBar();
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(120, 14));
        bottomBar.add(progressBar, BorderLayout.EAST);

        panel.add(bottomBar, BorderLayout.SOUTH);

        // --- 中间：表格 ---
        tableModel = new ServerTableModel();
        serverTable = new JBTable(tableModel);
        serverTable.setRowHeight(32);
        serverTable.setShowGrid(false);
        serverTable.setIntercellSpacing(new Dimension(0, 0));
        serverTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        serverTable.setRowSelectionAllowed(true);
        serverTable.setColumnSelectionAllowed(false);
        serverTable.getTableHeader().setReorderingAllowed(false);

        // 列宽
        TableColumnModel colModel = serverTable.getColumnModel();
        colModel.getColumn(0).setMaxWidth(40);  // 勾选框
        colModel.getColumn(1).setMaxWidth(50);  // 自动上传
        colModel.getColumn(2).setPreferredWidth(120); // 名称
        colModel.getColumn(3).setPreferredWidth(180); // 主机+目录

        // 渲染：第3列（主机信息）用两行文字
        colModel.getColumn(3).setCellRenderer(new TwoLineRenderer());

        // 双击编辑
        serverTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = serverTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < settings.servers.size()) {
                        openEditForm(settings.servers.get(row).id);
                    }
                }
            }
        });

        JBScrollPane tableScroll = new JBScrollPane(serverTable);
        tableScroll.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
        tableScroll.setPreferredSize(new Dimension(200, 200));
        panel.add(tableScroll, BorderLayout.CENTER);

        return panel;
    }

    // ==========================================
    //  编辑表单视图 — 仿 Kotlin UI DSL 布局
    // ==========================================

    private static final int FORM_H = 14;
    private static final int FORM_V = 12;
    /** 标签统一宽度，右对齐 */
    private static final int LABEL_W = 78;

    private JPanel buildEditView() {
        JPanel outer = new JPanel(new BorderLayout());

        // ---- 标题栏：标题 + 取消/保存按钮 ----
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBorder(JBUI.Borders.compound(
            JBUI.Borders.empty(10, FORM_H, 8, FORM_H),
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        ));
        JLabel formTitle = new JLabel(editingServerId == null ? "新建服务器" : "编辑服务器");
        formTitle.setFont(formTitle.getFont().deriveFont(Font.BOLD, 13f));
        titleBar.add(formTitle, BorderLayout.WEST);
        this.formTitleLabel = formTitle;

        JPanel titleBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        editCancelBtn = new JButton("取消");
        editCancelBtn.addActionListener(e -> showList());
        JButton testConnBtn = new JButton("测试连接");
        testConnBtn.addActionListener(e -> testCurrentConnection());
        editSaveBtn = new JButton("保存配置(S)");
        editSaveBtn.addActionListener(e -> saveEditedServer());
        titleBtns.add(editCancelBtn);
        titleBtns.add(testConnBtn);
        titleBtns.add(editSaveBtn);
        titleBar.add(titleBtns, BorderLayout.EAST);
        outer.add(titleBar, BorderLayout.NORTH);

        // ---- 表单内容（GridBagLayout 紧凑精确布局） ----
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(JBUI.Borders.empty(8, FORM_H, 0, FORM_H));
        int row = 0;

        // 1. 基本信息 Group
        addToForm(content, buildGroupHeader("基本信息"), row++, false);
        editNameField = createTextField(20, "如：生产服务器");
        addToForm(content, buildRow("名称:", editNameField), row++, true);
        editHostField = createTextField(20, "192.168.1.100");
        addToForm(content, buildRow("主机:", editHostField), row++, true);
        editPortField = createTextField(5, "22");
        editUserField = createTextField(12, "root");
        addToForm(content, buildDoubleRow("端口:", editPortField, "用户名:", editUserField), row++, true);
        row++;

        // 2. 认证 CollapsibleGroup
        JPanel authContent = new JPanel(new GridBagLayout());
        int ar = 0;
        editPasswordField = new JBPasswordField();
        editPasswordField.setColumns(20);
        addToForm(authContent, buildRow("密码:", editPasswordField), ar++, true);
        editPrivateKeyField = createTextField(16, "~/.ssh/id_rsa");
        JPanel keyRow = new JPanel(new BorderLayout(4, 0));
        keyRow.add(editPrivateKeyField, BorderLayout.CENTER);
        JButton browseBtn = new JButton("…");
        browseBtn.setMargin(JBUI.insets(0, 6, 0, 6));
        browseBtn.setToolTipText("选择 SSH 私钥文件");
        browseBtn.addActionListener(e -> {
            FileChooserDescriptor desc = FileChooserDescriptorFactory.createSingleFileDescriptor();
            desc.setTitle("选择 SSH 私钥文件");
            com.intellij.openapi.fileChooser.FileChooser.chooseFile(desc, project, null,
                file -> { if (file != null) editPrivateKeyField.setText(file.getPath()); });
        });
        keyRow.add(browseBtn, BorderLayout.EAST);
        addToForm(authContent, buildRow("私钥:", keyRow), ar++, true);
        editKeyPassphraseField = new JBPasswordField();
        editKeyPassphraseField.setColumns(20);
        addToForm(authContent, buildRow("密钥密码:", editKeyPassphraseField), ar++, true);
        addToForm(content, buildCollapsibleGroup("认证", authContent), row++, false);
        row++;

        // 3. 上传配置 CollapsibleGroup
        JPanel uploadContent = new JPanel(new GridBagLayout());
        int ur = 0;
        editRemoteDirField = createTextField(20, "/opt/app/");
        addToForm(uploadContent, buildRow("远程目录:", editRemoteDirField), ur++, true);
        editRemoteCommandField = new JBTextArea();
        editRemoteCommandField.setRows(3);
        editRemoteCommandField.setColumns(20);
        editRemoteCommandField.setLineWrap(true);
        editRemoteCommandField.setWrapStyleWord(true);
        editRemoteCommandField.getEmptyText().setText(" 如: systemctl restart app\n或输入多行 Shell 脚本");
        JBScrollPane cmdScroll = new JBScrollPane(editRemoteCommandField);
        cmdScroll.setPreferredSize(new Dimension(200, 72));
        addToForm(uploadContent, buildRow("上传后执行:", cmdScroll), ur++, true);
        addToForm(content, buildCollapsibleGroup("上传配置", uploadContent), row++, false);

        // 底部填充
        GridBagConstraints padGbc = new GridBagConstraints();
        padGbc.gridy = row;
        padGbc.weighty = 1.0;
        padGbc.fill = GridBagConstraints.VERTICAL;
        content.add(new JPanel(), padGbc);

        JBScrollPane formScroll = new JBScrollPane(content);
        formScroll.setBorder(null);
        formScroll.getVerticalScrollBar().setUnitIncrement(16);
        outer.add(formScroll, BorderLayout.CENTER);

        // ---- 底部状态栏 ----
        editStatusLabel = new JLabel(" ");
        editStatusLabel.setFont(editStatusLabel.getFont().deriveFont(11f));
        editStatusLabel.setBorder(JBUI.Borders.empty(4, FORM_H, 4, FORM_H));
        outer.add(editStatusLabel, BorderLayout.SOUTH);

        return outer;
    }

    /** 向表单添加一行 */
    private void addToForm(JPanel form, JComponent comp, int gridy, boolean withTopGap) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = gridy;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = withTopGap ? JBUI.insets(5, 0, 0, 0) : JBUI.insets(0, 0, 0, 0);
        form.add(comp, gbc);
    }

    // ---- 布局辅助 ----

    /** Group 标题 */
    private JLabel buildGroupHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.putClientProperty("Group.title", true);
        return label;
    }

    /** 可折叠分组 */
    private JPanel buildCollapsibleGroup(String title, JPanel body) {
        JPanel wrapper = new JPanel(new BorderLayout());

        // 标题栏
        JPanel header = new JPanel(new BorderLayout());
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel arrow = new JLabel(AllIcons.General.ArrowDown);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(arrow);
        left.add(titleLabel);
        header.add(left, BorderLayout.WEST);

        // 分隔线
        JSeparator line = new JSeparator(SwingConstants.HORIZONTAL);
        line.setForeground(JBColor.border());
        header.add(line, BorderLayout.SOUTH);

        wrapper.add(header, BorderLayout.NORTH);
        wrapper.add(body, BorderLayout.CENTER);

        final boolean[] expanded = {true};
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                expanded[0] = !expanded[0];
                body.setVisible(expanded[0]);
                arrow.setIcon(expanded[0] ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
                wrapper.revalidate();
                wrapper.repaint();
            }
        });

        return wrapper;
    }

    /** 单行：标签右对齐 + 输入框 */
    private JPanel buildRow(String labelText, JComponent field) {
        JPanel row = new JPanel(new GridBagLayout());

        JLabel label = new JLabel(labelText);
        label.setHorizontalAlignment(SwingConstants.RIGHT);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.EAST;
        gc.insets = JBUI.insets(0, 0, 0, 8);
        row.add(label, gc);

        gc.gridx = 1;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = JBUI.emptyInsets();
        row.add(field, gc);

        return row;
    }

    /** 双字段行：标签1-字段1-标签2-字段2 */
    private JPanel buildDoubleRow(String label1, JComponent field1,
                                   String label2, JComponent field2) {
        JPanel row = new JPanel(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0;
        gc.weightx = 0;
        gc.insets = JBUI.insets(0, 0, 0, 4);
        JLabel lbl1 = new JLabel(label1);
        lbl1.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(lbl1, gc);

        gc.gridx = 1;
        gc.insets = JBUI.insets(0, 0, 0, 16);
        row.add(field1, gc);

        gc.gridx = 2;
        gc.insets = JBUI.insets(0, 0, 0, 4);
        row.add(new JLabel(label2), gc);

        gc.gridx = 3;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = JBUI.emptyInsets();
        row.add(field2, gc);

        return row;
    }

    /** 创建指定列数的单行输入框 */
    private JBTextField createTextField(int columns, String emptyText) {
        JBTextField field = new JBTextField(columns);
        if (emptyText != null && !emptyText.isEmpty()) {
            field.getEmptyText().setText(emptyText);
        }
        return field;
    }

    // ==========================================
    //  视图切换
    // ==========================================

    private void showList() {
        refreshTable();
        globalEnabledCheckBox.setSelected(settings.pluginEnabled);
        globalEnabledCheckBox.addChangeListener(e ->
            settings.pluginEnabled = globalEnabledCheckBox.isSelected());
        cardLayout.show(cardPanel, CARD_LIST);
    }

    private void openEditForm(String serverId) {
        this.editingServerId = serverId;

        // 动态更新标题
        if (formTitleLabel != null) {
            formTitleLabel.setText(serverId == null ? "新建服务器" : "编辑服务器");
        }

        MavenDeploySettings.ServerConfig server;

        if (serverId != null) {
            server = settings.servers.stream()
                .filter(s -> s.id.equals(serverId))
                .findFirst().orElse(new MavenDeploySettings.ServerConfig());
            // 填充已有值
            editNameField.setText(server.name);
            editHostField.setText(server.host);
            editPortField.setText(String.valueOf(server.port));
            editUserField.setText(server.user);
            editPasswordField.setText(server.password);
            editPrivateKeyField.setText(server.privateKeyPath);
            editKeyPassphraseField.setText(server.keyPassphrase);
            editRemoteDirField.setText(server.remoteDir);
            editRemoteCommandField.setText(server.remoteCommand);
        } else {
            // 新建空表单（保留上次的 global 设置）
            editNameField.setText("");
            editHostField.setText("");
            editPortField.setText("22");
            editUserField.setText("root");
            editPasswordField.setText("");
            editPrivateKeyField.setText("");
            editKeyPassphraseField.setText("");
            editRemoteDirField.setText("/opt/app/");
            editRemoteCommandField.setText("");
        }

        editStatusLabel.setText(" ");
        cardLayout.show(cardPanel, CARD_EDIT);
    }

    private void testCurrentConnection() {
        // 从表单构建临时 ServerConfig 用于测试
        MavenDeploySettings.ServerConfig tmp = new MavenDeploySettings.ServerConfig();
        tmp.host = editHostField.getText().trim();
        try { tmp.port = Integer.parseInt(editPortField.getText().trim()); }
        catch (NumberFormatException e) { tmp.port = 22; }
        tmp.user = editUserField.getText().trim();
        tmp.password = String.valueOf(editPasswordField.getPassword());
        tmp.privateKeyPath = editPrivateKeyField.getText().trim();
        tmp.keyPassphrase = String.valueOf(editKeyPassphraseField.getPassword());
        tmp.name = editNameField.getText().trim();

        if (tmp.host.isEmpty()) {
            editStatusLabel.setText("请填写主机地址");
            editStatusLabel.setForeground(JBColor.RED);
            return;
        }

        editStatusLabel.setText("正在测试连接...");
        editStatusLabel.setForeground(JBColor.GRAY);

        new Thread(() -> {
            try {
                String result = com.mcp.deploy.ssh.SshUploader.testConnection(tmp, settings.connectTimeout);
                ApplicationManager.getApplication().invokeLater(() -> {
                    editStatusLabel.setText(result);
                    editStatusLabel.setForeground(JBColor.GREEN);
                });
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    editStatusLabel.setText("连接失败: " + e.getMessage());
                    editStatusLabel.setForeground(JBColor.RED);
                });
            }
        }, "maven-deploy-test-conn").start();
    }

    private void saveEditedServer() {
        MavenDeploySettings.ServerConfig server;
        if (editingServerId != null) {
            server = settings.servers.stream()
                .filter(s -> s.id.equals(editingServerId))
                .findFirst().orElse(null);
            if (server == null) {
                editStatusLabel.setText("服务器配置不存在");
                editStatusLabel.setForeground(JBColor.RED);
                return;
            }
        } else {
            server = new MavenDeploySettings.ServerConfig();
            settings.servers.add(server);
        }

        server.name = editNameField.getText().trim();
        server.host = editHostField.getText().trim();
        try { server.port = Integer.parseInt(editPortField.getText().trim()); }
        catch (NumberFormatException e) { server.port = 22; }
        server.user = editUserField.getText().trim();
        server.password = String.valueOf(editPasswordField.getPassword());
        server.privateKeyPath = editPrivateKeyField.getText().trim();
        server.keyPassphrase = String.valueOf(editKeyPassphraseField.getPassword());
        server.remoteDir = editRemoteDirField.getText().trim();
        server.remoteCommand = editRemoteCommandField.getText().trim();

        editStatusLabel.setText("已保存");
        editStatusLabel.setForeground(JBColor.GREEN);

        // 延迟回到列表
        new Thread(() -> {
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
            ApplicationManager.getApplication().invokeLater(this::showList);
        }).start();
    }

    // ==========================================
    //  列表操作
    // ==========================================

    private void editSelected() {
        int row = serverTable.getSelectedRow();
        if (row < 0 || row >= settings.servers.size()) {
            statusLabel.setText("请先选中一行");
            return;
        }
        openEditForm(settings.servers.get(row).id);
    }

    private void deleteSelected() {
        int[] rows = serverTable.getSelectedRows();
        if (rows.length == 0) {
            statusLabel.setText("请先选中要删除的行");
            return;
        }
        int result = Messages.showYesNoDialog(
            project,
            "确定删除选中的 " + rows.length + " 个服务器配置？",
            "确认删除",
            Messages.getQuestionIcon()
        );
        if (result == Messages.YES) {
            // 从后往前删，避免索引变化
            List<MavenDeploySettings.ServerConfig> toRemove = new ArrayList<>();
            for (int i = rows.length - 1; i >= 0; i--) {
                toRemove.add(settings.servers.get(rows[i]));
            }
            settings.servers.removeAll(toRemove);
            refreshTable();
            statusLabel.setText("已删除 " + rows.length + " 项");
        }
    }

    private void uploadSelected() {
        int[] rows = serverTable.getSelectedRows();
        if (rows.length == 0) {
            statusLabel.setText("请先勾选要上传的服务器");
            return;
        }

        String basePath = project.getBasePath();
        if (basePath == null) {
            statusLabel.setText("无法获取项目路径");
            return;
        }

        Path jarPath = findLocalJar(basePath);
        if (jarPath == null) {
            statusLabel.setText("未找到 jar，请先 Maven 打包");
            return;
        }

        List<MavenDeploySettings.ServerConfig> selected = new ArrayList<>();
        for (int row : rows) {
            selected.add(settings.servers.get(row));
        }

        int total = selected.size();
        String jarName = jarPath.getFileName().toString();
        statusLabel.setText("同时上传 " + jarName + " 到 " + total + " 台服务器...");

        // 显示进度条
        progressBar.setMaximum(total);
        progressBar.setValue(0);
        progressBar.setVisible(true);

        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger fail = new java.util.concurrent.atomic.AtomicInteger(0);

        for (MavenDeploySettings.ServerConfig server : selected) {
            new Thread(() -> {
                try {
                    com.mcp.deploy.ssh.SshUploader.uploadToServer(project, settings, server, jarPath);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    int done = completed.incrementAndGet();
                    ApplicationManager.getApplication().invokeLater(() -> progressBar.setValue(done));
                }
            }, "maven-deploy-upload-" + server.getDisplayLabel()).start();
        }

        // 等待全部完成
        new Thread(() -> {
            while (completed.get() < total) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            int s = success.get(), f = fail.get();
            ApplicationManager.getApplication().invokeLater(() -> {
                progressBar.setVisible(false);
                if (f == 0) {
                    statusLabel.setText("全部上传成功: " + s + " 台");
                    statusLabel.setForeground(JBColor.GREEN);
                } else {
                    statusLabel.setText("⚠ 上传完成: " + s + " 成功, " + f + " 失败");
                    statusLabel.setForeground(JBColor.ORANGE);
                }
            });
        }, "maven-deploy-multi-wait").start();
    }

    // ==========================================
    //  表格模型
    // ==========================================

    private void refreshTable() {
        tableModel.fireTableDataChanged();
        serverTable.clearSelection();
    }

    private class ServerTableModel extends AbstractTableModel {
        private final String[] columns = {"", "自动", "名称", "主机 / 目录"};

        @Override public int getRowCount() { return settings.servers.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            // 第 1 列（自动）是 Boolean，显示勾选框
            // 第 0 列是普通文本，JTable 的多选模式管理行选择
            return col == 1 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 1; // 只有「自动」列可编辑
        }

        @Override
        public Object getValueAt(int row, int col) {
            MavenDeploySettings.ServerConfig s = settings.servers.get(row);
            switch (col) {
                case 0: return ""; // 选择列，空占位
                case 1: return s.autoUpload;
                case 2: return s.getDisplayLabel();
                case 3: return s.user + "@" + s.host + ":" + s.port + " → " + s.remoteDir;
                default: return "";
            }
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col == 1 && value instanceof Boolean) {
                settings.servers.get(row).autoUpload = (Boolean) value;
                fireTableCellUpdated(row, col);
            }
        }
    }

    /** 两行渲染：第一行主机信息，第二行远程目录 */
    private static class TwoLineRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column);
            if (value != null) {
                String text = value.toString();
                // 用 → 分隔两行
                int idx = text.indexOf(" → ");
                if (idx > 0) {
                    label.setText("<html>" + text.substring(0, idx)
                        + "<br><span style='color:#888;font-size:10px'>"
                        + text.substring(idx + 3) + "</span></html>");
                } else {
                    label.setText(text);
                }
            }
            label.setBorder(JBUI.Borders.empty(2, 6));
            return label;
        }
    }

    // ==========================================
    //  查找 jar
    // ==========================================

    private Path findLocalJar(String basePath) {
        Path targetDir = Paths.get(basePath, "target");
        if (!Files.isDirectory(targetDir)) {
            try {
                return Files.walk(Paths.get(basePath), 4)
                    .filter(p -> p.toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-sources")
                        && !p.getFileName().toString().contains("-javadoc")
                        && p.getParent().getFileName().toString().equals("target"))
                    .findFirst().orElse(null);
            } catch (Exception e) { return null; }
        }
        try {
            if ("auto".equals(settings.jarNameMode)) {
                return Files.list(targetDir)
                    .filter(p -> p.toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-sources")
                        && !p.getFileName().toString().contains("-javadoc"))
                    .max((a, b) -> { try { return Long.compare(Files.size(a), Files.size(b)); } catch (Exception e) { return 0; } })
                    .orElse(null);
            } else {
                Path p = targetDir.resolve(settings.jarNameManual);
                if (Files.exists(p)) return p;
                if (!settings.jarNameManual.endsWith(".jar")) {
                    p = targetDir.resolve(settings.jarNameManual + ".jar");
                    if (Files.exists(p)) return p;
                }
                return null;
            }
        } catch (Exception e) { return null; }
    }
}
