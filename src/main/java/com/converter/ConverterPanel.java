/*
 * Copyright (c) 2026 Nomikosi Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.converter;

import com.converter.converter.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.converter.ConverterTheme.*;

public class ConverterPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ConverterPanel.class);

    /** Client-property key under which the panel registers itself on its root component. */
    public static final String PANEL_CLIENT_PROPERTY = "beWater.converterPanel";

    private static final String NOTIFICATION_GROUP = "Be Water Converter";

    // PropertiesComponent keys for options persisted across IDE restarts.
    private static final String PROP_CSV_MODE       = "beWater.csvMode";
    private static final String PROP_ROW_THRESHOLD  = "beWater.rowThreshold";
    private static final String PROP_LOMBOK         = "beWater.lombok";
    private static final String PROP_INFER_TYPES    = "beWater.csvInferTypes";
    private static final String PROP_DETECT_DATES   = "beWater.detectDates";
    private static final String PROP_SPLIT_VERTICAL = "beWater.splitVertical";
    private static final String PROP_WRAP_LINES     = "beWater.wrapLines";

    /** Above this output size, syntax highlighting is disabled to keep the EDT responsive. */
    private static final int HIGHLIGHT_LIMIT_CHARS = 2_000_000;

    static final String FMT_JSON  = ConversionPipeline.FMT_JSON;
    static final String FMT_XML   = ConversionPipeline.FMT_XML;
    static final String FMT_YAML  = ConversionPipeline.FMT_YAML;
    static final String FMT_CSV   = ConversionPipeline.FMT_CSV;
    static final String FMT_TOML  = ConversionPipeline.FMT_TOML;
    static final String FMT_PROTO = ConversionPipeline.FMT_PROTO;
    static final String FMT_JAVA  = ConversionPipeline.FMT_JAVA;

    private static final Map<String, Color> FORMAT_COLORS = new LinkedHashMap<>();
    static {
        FORMAT_COLORS.put(FMT_JSON,  new JBColor(new Color(41, 128, 185), new Color(52, 152, 219)));
        FORMAT_COLORS.put(FMT_XML,   new JBColor(new Color(211, 84,   0), new Color(230, 126, 34)));
        FORMAT_COLORS.put(FMT_YAML,  new JBColor(new Color(142, 68, 173), new Color(155, 89, 182)));
        FORMAT_COLORS.put(FMT_CSV,   new JBColor(new Color(39, 174,  96), new Color(46, 204, 113)));
        FORMAT_COLORS.put(FMT_TOML,  new JBColor(new Color(44,  62,  80), new Color(149, 165, 166)));
        FORMAT_COLORS.put(FMT_PROTO, new JBColor(new Color(192, 57,  43), new Color(231, 76,  60)));
        FORMAT_COLORS.put(FMT_JAVA,  new JBColor(new Color(142, 110, 45), new Color(243, 196, 66)));
    }

    private static final Map<String, String> FORMAT_EXTENSIONS = new LinkedHashMap<>();
    static {
        FORMAT_EXTENSIONS.put(FMT_JSON,  "json");
        FORMAT_EXTENSIONS.put(FMT_XML,   "xml");
        FORMAT_EXTENSIONS.put(FMT_YAML,  "yaml");
        FORMAT_EXTENSIONS.put(FMT_CSV,   "csv");
        FORMAT_EXTENSIONS.put(FMT_TOML,  "toml");
        FORMAT_EXTENSIONS.put(FMT_PROTO, "proto");
        FORMAT_EXTENSIONS.put(FMT_JAVA,  "java");
    }

    private static final Map<String, String[]> VALID_OUTPUTS = new LinkedHashMap<>();
    static {
        VALID_OUTPUTS.put(FMT_JSON,  new String[]{FMT_XML,  FMT_YAML, FMT_CSV, FMT_TOML, FMT_PROTO, FMT_JAVA});
        VALID_OUTPUTS.put(FMT_XML,   new String[]{FMT_JSON, FMT_YAML, FMT_CSV, FMT_TOML, FMT_PROTO, FMT_JAVA});
        VALID_OUTPUTS.put(FMT_YAML,  new String[]{FMT_JSON, FMT_XML,  FMT_CSV, FMT_TOML, FMT_PROTO, FMT_JAVA});
        VALID_OUTPUTS.put(FMT_CSV,   new String[]{FMT_JSON, FMT_XML,  FMT_YAML,FMT_TOML, FMT_PROTO, FMT_JAVA});
        VALID_OUTPUTS.put(FMT_TOML,  new String[]{FMT_JSON, FMT_XML,  FMT_YAML,FMT_CSV,  FMT_PROTO, FMT_JAVA});
        VALID_OUTPUTS.put(FMT_PROTO, new String[]{FMT_JSON, FMT_XML,  FMT_YAML,FMT_CSV,  FMT_TOML,  FMT_JAVA});
    }

    private static final String[] ALL_INPUTS =
          {FMT_JSON, FMT_XML, FMT_YAML, FMT_CSV, FMT_TOML, FMT_PROTO};

    private static final long DEFAULT_ROW_WARNING_THRESHOLD = 1_000L;

    /** Files larger than this trigger a confirmation before loading (whole pipeline is in-memory). */
    private static final long LARGE_FILE_WARNING_BYTES = 10L * 1024 * 1024;


    private static final int BUTTON_ARC = 8;
    private static final int STATUS_MAX_LEN = 120;
    private static final String ACTION_CONVERT = "convert";
    private static final String ACTION_FORMAT = "format";
    private static final String ACTION_COPY_OUTPUT = "copyOutput";
    private static final String ACTION_OPEN_FILE = "openFile";
    private static final String ACTION_SAVE_FILE = "saveFile";
    private static final String ACTION_FIND      = "find";

    private final JPanel            mainPanel;
    private final RSyntaxTextArea   inputArea;
    private final RSyntaxTextArea   outputArea;
    private final JLabel            statusLabel;
    private final JLabel            charCountLabel;
    private final JLabel            inputFormatLabel;
    private final JLabel            outputFormatLabel;
    private final JComboBox<String> inputCombo;
    private final JComboBox<String> outputCombo;
    private final JComboBox<CsvConverter.CsvMode> csvModeCombo;
    private final JLabel    csvModeHint;
    private final JSpinner  rowThresholdSpinner;
    private final JLabel    rowThresholdLabel;
    private final JCheckBox lombokCheck;
    private final JCheckBox detectDatesCheck;
    private final JCheckBox inferTypesCheck;
    private final ConversionHistory history = new ConversionHistory();
    private final JPanel    csvOptions;
    private final JPanel    csvInputOptions;
    private final JPanel    javaOptions;
    private final JPanel    optionsBar;
    private final JSplitPane splitPane;
    private JButton convertBtn;
    private JButton splitToggleBtn;
    private JPanel     findBar;
    private JTextField findField;
    private RSyntaxTextArea findTarget;

    private final com.intellij.openapi.project.Project project;
    private final AtomicBoolean converting = new AtomicBoolean(false);
    private final PropertyChangeListener lafListener;
    private volatile boolean disposed;
    private volatile Thread convertWorker;

    private final ConversionPipeline pipeline = new ConversionPipeline();

    public ConverterPanel() {
        this(null);
    }

    public ConverterPanel(com.intellij.openapi.project.Project project) {
        this.project = project;
        mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(BG_DARK);
        mainPanel.putClientProperty(PANEL_CLIENT_PROPERTY, this);

        inputArea  = buildEditor();
        outputArea = buildEditor();
        outputArea.setEditable(false);
        applyEditorTheme(inputArea);
        applyEditorTheme(outputArea);
        installFileDrop(inputArea);

        inputFormatLabel  = buildFormatBadge(FMT_JSON);
        outputFormatLabel = buildFormatBadge(FMT_XML);

        inputCombo  = buildCombo(ALL_INPUTS);
        outputCombo = buildCombo(VALID_OUTPUTS.get(FMT_JSON));
        outputCombo.setSelectedItem(FMT_XML);

        // ── conversion-specific option controls ──────────────────────────
        csvModeCombo = new JComboBox<>(CsvConverter.CsvMode.values());
        csvModeCombo.setBackground(DROPDOWN_BG);
        csvModeCombo.setForeground(TEXT_BRIGHT);
        csvModeCombo.setFont(new Font("SansSerif", Font.PLAIN, 13));
        csvModeCombo.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        csvModeCombo.setToolTipText("How arrays of objects are expanded into CSV rows");
        csvModeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                  int index, boolean isSelected, boolean hasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
                setBackground(isSelected ? ACCENT : DROPDOWN_BG);
                setForeground(TEXT_BRIGHT);
                setBorder(new EmptyBorder(4, 10, 4, 10));
                return this;
            }
        });

        csvModeHint = new JLabel(csvModeHintFor(CsvConverter.CsvMode.FLAT_FIRST));
        csvModeHint.setForeground(TEXT_DIM);
        csvModeHint.setFont(new Font("SansSerif", Font.ITALIC, 12));

        rowThresholdLabel = toolbarLabel("Row warning:");
        rowThresholdSpinner = new JSpinner(
              new SpinnerNumberModel(
                    (Number) DEFAULT_ROW_WARNING_THRESHOLD, 10L, 10_000_000L, 100L));
        rowThresholdSpinner.setToolTipText(
              "CROSS_JOIN conversions estimated to exceed this row count trigger a confirmation");
        rowThresholdSpinner.setFont(new Font("SansSerif", Font.PLAIN, 13));
        rowThresholdSpinner.setPreferredSize(new Dimension(90, 26));

        csvModeCombo.addActionListener(e -> {
            CsvConverter.CsvMode m = (CsvConverter.CsvMode) csvModeCombo.getSelectedItem();
            if (m != null) {
                csvModeHint.setText(csvModeHintFor(m));
                boolean isCross = m == CsvConverter.CsvMode.CROSS_JOIN;
                rowThresholdLabel.setVisible(isCross);
                rowThresholdSpinner.setVisible(isCross);
            }
        });

        lombokCheck = new JCheckBox("Lombok annotations");
        lombokCheck.setToolTipText(
              "Annotate generated classes with @Data, @NoArgsConstructor and @AllArgsConstructor");
        lombokCheck.setOpaque(false);
        lombokCheck.setForeground(TEXT_BRIGHT);
        lombokCheck.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lombokCheck.setFocusPainted(false);

        detectDatesCheck = new JCheckBox("Detect dates", true);
        detectDatesCheck.setToolTipText(
              "Type ISO-8601 values as LocalDate / LocalDateTime / OffsetDateTime instead of String");
        detectDatesCheck.setOpaque(false);
        detectDatesCheck.setForeground(TEXT_BRIGHT);
        detectDatesCheck.setFont(new Font("SansSerif", Font.PLAIN, 13));
        detectDatesCheck.setFocusPainted(false);

        inferTypesCheck = new JCheckBox("Infer types", true);
        inferTypesCheck.setToolTipText(
              "Convert CSV/XML values that look like numbers, booleans or null into typed JSON values");
        inferTypesCheck.setOpaque(false);
        inferTypesCheck.setForeground(TEXT_BRIGHT);
        inferTypesCheck.setFont(new Font("SansSerif", Font.PLAIN, 13));
        inferTypesCheck.setFocusPainted(false);

        csvOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        csvOptions.setOpaque(false);
        csvOptions.add(toolbarLabel("CSV mode:"));
        csvOptions.add(csvModeCombo);
        csvOptions.add(csvModeHint);
        csvOptions.add(rowThresholdLabel);
        csvOptions.add(rowThresholdSpinner);
        rowThresholdLabel.setVisible(false);
        rowThresholdSpinner.setVisible(false);

        csvInputOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        csvInputOptions.setOpaque(false);
        csvInputOptions.add(toolbarLabel("Input:"));
        csvInputOptions.add(inferTypesCheck);

        javaOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        javaOptions.setOpaque(false);
        javaOptions.add(toolbarLabel("Java POJO:"));
        javaOptions.add(lombokCheck);
        javaOptions.add(detectDatesCheck);

        optionsBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
        optionsBar.setBackground(BG_LABEL_BAR);
        optionsBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        optionsBar.add(toolbarLabel("Options:"));
        optionsBar.add(csvInputOptions);
        optionsBar.add(csvOptions);
        optionsBar.add(javaOptions);

        inputCombo.addActionListener(e -> {
            String fmt = (String) inputCombo.getSelectedItem();
            if (fmt == null) return;
            inputArea.setSyntaxEditingStyle(syntaxFor(fmt));
            inputFormatLabel.setText(fmt);
            inputFormatLabel.repaint();
            rebuildOutputCombo(fmt);
        });

        JPanel toolbar    = buildToolbar();
        updateConversionOptions();
        JPanel inputWrap  = wrapEditor(inputArea,  inputFormatLabel,  "Input");
        JPanel outputWrap = wrapEditor(outputArea, outputFormatLabel, "Output");

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputWrap, outputWrap);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerSize(JBUI.scale(6));
        splitPane.setBorder(null);
        splitPane.setBackground(BG_DARK);
        installDividerUI();
        applyPersistedOptions();

        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(TEXT_DIM);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusLabel.setBorder(JBUI.Borders.empty(4, 10));

        charCountLabel = new JLabel("");
        charCountLabel.setForeground(TEXT_DIM);
        charCountLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        charCountLabel.setBorder(JBUI.Borders.empty(4, 10));
        updateCharCount();

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(BG_STATUS);
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
        statusBar.add(statusLabel,    BorderLayout.WEST);
        statusBar.add(charCountLabel, BorderLayout.EAST);

        buildFindBar();
        findTarget = inputArea;
        FocusAdapter targetTracker = new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                findTarget = (RSyntaxTextArea) e.getComponent();
            }
        };
        inputArea.addFocusListener(targetTracker);
        outputArea.addFocusListener(targetTracker);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(findBar,   BorderLayout.NORTH);
        south.add(statusBar, BorderLayout.SOUTH);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(toolbar,    BorderLayout.NORTH);
        north.add(optionsBar, BorderLayout.SOUTH);

        mainPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                north.revalidate();
            }
        });

        mainPanel.add(north,     BorderLayout.NORTH);
        mainPanel.add(splitPane, BorderLayout.CENTER);
        mainPanel.add(south,     BorderLayout.SOUTH);

        // ── keyboard shortcuts ───────────────────────────────────────────
        installKeyboardShortcuts();

        // ── live char/line count ─────────────────────────────────────────
        DocumentListener countUpdater = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { updateCharCount(); }
            @Override public void removeUpdate(DocumentEvent e)  { updateCharCount(); }
            @Override public void changedUpdate(DocumentEvent e) { updateCharCount(); }
        };
        inputArea.getDocument().addDocumentListener(countUpdater);
        outputArea.getDocument().addDocumentListener(countUpdater);

        // ── re-apply editor theme when IDE L&F changes ───────────────────
        // UIManager is static: the listener must be removed in dispose() or
        // every panel instance leaks for the lifetime of the IDE.
        lafListener = evt -> {
            if ("lookAndFeel".equals(evt.getPropertyName())) {
                applyEditorTheme(inputArea);
                applyEditorTheme(outputArea);
                // Custom-painted components use JBColor (which resolves per
                // theme at paint time) — a repaint refreshes them all.
                mainPanel.repaint();
            }
        };
        UIManager.addPropertyChangeListener(lafListener);
    }

    @Override
    public void dispose() {
        disposed = true;
        UIManager.removePropertyChangeListener(lafListener);
    }

    // ── Public entry points for registered IDE actions ───────────────────
    public void convert()     { doConvert(); }
    public void formatInput() { doFormat(); }
    public void copyOutput()  { doCopy(); }
    public void openFile()    { doOpenFile(); }
    public void saveOutput()  { doSaveFile(); }

    // ── Option persistence (application-level, survives IDE restarts) ────
    private void applyPersistedOptions() {
        String mode = loadProp(PROP_CSV_MODE);
        if (mode != null) {
            try {
                csvModeCombo.setSelectedItem(CsvConverter.CsvMode.valueOf(mode));
            } catch (IllegalArgumentException ignored) {}
        }
        String threshold = loadProp(PROP_ROW_THRESHOLD);
        if (threshold != null) {
            try {
                long v = Long.parseLong(threshold);
                if (v >= 10L && v <= 10_000_000L) rowThresholdSpinner.setValue(v);
            } catch (NumberFormatException ignored) {}
        }
        lombokCheck.setSelected("true".equals(loadProp(PROP_LOMBOK)));
        if (loadProp(PROP_INFER_TYPES) != null) {
            inferTypesCheck.setSelected("true".equals(loadProp(PROP_INFER_TYPES)));
        }
        if (loadProp(PROP_DETECT_DATES) != null) {
            detectDatesCheck.setSelected("true".equals(loadProp(PROP_DETECT_DATES)));
        }
        if ("true".equals(loadProp(PROP_WRAP_LINES))) {
            setLineWrap(true);
        }
        if ("true".equals(loadProp(PROP_SPLIT_VERTICAL))) {
            splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
            if (splitToggleBtn != null) {
                splitToggleBtn.setIcon(com.intellij.icons.AllIcons.Actions.SplitHorizontally);
            }
            installDividerUI();
        }

        csvModeCombo.addActionListener(e -> {
            Object m = csvModeCombo.getSelectedItem();
            if (m != null) saveProp(PROP_CSV_MODE, m.toString());
        });
        rowThresholdSpinner.addChangeListener(e ->
              saveProp(PROP_ROW_THRESHOLD, rowThresholdSpinner.getValue().toString()));
        lombokCheck.addActionListener(e ->
              saveProp(PROP_LOMBOK, String.valueOf(lombokCheck.isSelected())));
        inferTypesCheck.addActionListener(e ->
              saveProp(PROP_INFER_TYPES, String.valueOf(inferTypesCheck.isSelected())));
        detectDatesCheck.addActionListener(e ->
              saveProp(PROP_DETECT_DATES, String.valueOf(detectDatesCheck.isSelected())));
    }

    private static String loadProp(String key) {
        try {
            return com.intellij.ide.util.PropertiesComponent.getInstance().getValue(key);
        } catch (Throwable outsideIde) {
            return null;
        }
    }

    private static void saveProp(String key, String value) {
        try {
            com.intellij.ide.util.PropertiesComponent.getInstance().setValue(key, value);
        } catch (Throwable outsideIde) {
            // Outside a full IDE (tests, standalone) options simply aren't persisted.
        }
    }

    private void installKeyboardShortcuts() {
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
              ACTION_CONVERT, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { doConvert(); }
              });

        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_L,
              InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
              ACTION_FORMAT, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { doFormat(); }
              });

        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_C,
              InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
              ACTION_COPY_OUTPUT, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { doCopy(); }
              });

        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_O,
              InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
              ACTION_OPEN_FILE, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { doOpenFile(); }
              });

        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_S,
              InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
              ACTION_SAVE_FILE, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { doSaveFile(); }
              });

        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
              ACTION_FIND, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { showFindBar(); }
              });
    }

    private void bindShortcut(KeyStroke keyStroke, String actionKey, Action action) {
        JComponent[] targets = {
              mainPanel,
              inputArea,
              outputArea,
              inputCombo,
              outputCombo,
              csvModeCombo,
              rowThresholdSpinner,
              lombokCheck
        };
        for (JComponent target : targets) {
            target.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                  .put(keyStroke, actionKey);
            target.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionKey);
            target.getActionMap().put(actionKey, action);
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────
    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 7));
        bar.setBackground(BG_TOOLBAR);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));

        bar.add(toolbarLabel("From:"));
        bar.add(inputCombo);

        bar.add(buildSwapButton());

        bar.add(toolbarLabel("To:"));
        bar.add(outputCombo);

        outputCombo.addActionListener(e -> updateConversionOptions());

        convertBtn = buildButton("Convert", ACCENT, ACCENT_HOVER, false);
        convertBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        convertBtn.setToolTipText("Convert (Ctrl+Enter)");
        convertBtn.addActionListener(e -> {
            if (converting.get()) cancelConvert(); else doConvert();
        });
        bar.add(convertBtn);

        bar.add(makeSep());

        JButton formatBtn = buildButton("Format", FORMAT_BG, FORMAT_HOVER, false);
        formatBtn.setToolTipText("Format input");
        formatBtn.addActionListener(e -> doFormat());
        bar.add(formatBtn);

        bar.add(makeSep());

        JButton copyBtn  = buildButton("Copy",  UTIL_BG, UTIL_HOVER, true);
        JButton clearBtn = buildButton("Clear", UTIL_BG, UTIL_HOVER, true);
        copyBtn.setToolTipText("Copy output");
        clearBtn.setToolTipText("Clear all");
        copyBtn.addActionListener(e  -> doCopy());
        clearBtn.addActionListener(e -> doClear());
        bar.add(copyBtn);
        bar.add(clearBtn);

        bar.add(makeSep());

        JButton openBtn = buildIconButton(com.intellij.icons.AllIcons.Actions.MenuOpen,
              "Open file");
        openBtn.addActionListener(e -> doOpenFile());
        bar.add(openBtn);

        JButton saveBtn = buildIconButton(com.intellij.icons.AllIcons.Actions.MenuSaveall,
              "Save output to file");
        saveBtn.addActionListener(e -> doSaveFile());
        bar.add(saveBtn);

        bar.add(makeSep());
        bar.add(buildSplitToggleButton());

        JButton wrapBtn = buildIconButton(com.intellij.icons.AllIcons.Actions.ToggleSoftWrap,
              "Toggle soft-wrap in both editors");
        wrapBtn.addActionListener(e -> setLineWrap(!inputArea.getLineWrap()));
        bar.add(wrapBtn);

        JButton historyBtn = buildIconButton(com.intellij.icons.AllIcons.Vcs.History,
              "Conversion history — restore a previous conversion");
        historyBtn.addActionListener(e -> showHistoryPopup(historyBtn));
        bar.add(historyBtn);

        return bar;
    }

    // ── Find bar ──────────────────────────────────────────────────────────
    private void buildFindBar() {
        findField = new JTextField(24);
        findField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        findField.setBackground(DROPDOWN_BG);
        findField.setForeground(TEXT_BRIGHT);
        findField.setCaretColor(TEXT_BRIGHT);
        findField.setBorder(BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(BORDER, 1), new EmptyBorder(3, 6, 3, 6)));

        findField.addActionListener(e -> findNext(true));
        findField.getInputMap().put(
              KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "findPrev");
        findField.getActionMap().put("findPrev", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { findNext(false); }
        });
        findField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        findField.getActionMap().put("closeFind", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { hideFindBar(); }
        });

        JButton prevBtn = buildIconButton(com.intellij.icons.AllIcons.Actions.PreviousOccurence,
              "Previous match (Shift+Enter)");
        prevBtn.addActionListener(e -> findNext(false));
        JButton nextBtn = buildIconButton(com.intellij.icons.AllIcons.Actions.NextOccurence,
              "Next match (Enter)");
        nextBtn.addActionListener(e -> findNext(true));
        JButton closeBtn = buildIconButton(com.intellij.icons.AllIcons.Actions.Close,
              "Close find bar (Esc)");
        closeBtn.addActionListener(e -> hideFindBar());

        findBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        findBar.setBackground(BG_TOOLBAR);
        findBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
        findBar.add(toolbarLabel("Find:"));
        findBar.add(findField);
        findBar.add(prevBtn);
        findBar.add(nextBtn);
        findBar.add(closeBtn);
        findBar.setVisible(false);
    }

    private void showFindBar() {
        findBar.setVisible(true);
        findBar.revalidate();
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    private void hideFindBar() {
        findBar.setVisible(false);
        findBar.revalidate();
        if (findTarget != null) findTarget.requestFocusInWindow();
    }

    /** Searches the last-focused editor, wrapping around at the ends. */
    private void findNext(boolean forward) {
        String query = findField.getText();
        if (query.isEmpty() || findTarget == null) return;
        org.fife.ui.rtextarea.SearchContext ctx = new org.fife.ui.rtextarea.SearchContext(query);
        ctx.setSearchForward(forward);
        ctx.setMatchCase(false);
        ctx.setSearchWrap(true);
        boolean found = org.fife.ui.rtextarea.SearchEngine.find(findTarget, ctx).wasFound();
        if (found) {
            setStatus("Found \"" + query + "\"", true);
        } else {
            setStatusWarn("No matches for \"" + query + "\"");
        }
    }

    // ── Soft-wrap ─────────────────────────────────────────────────────────
    private void setLineWrap(boolean wrap) {
        for (RSyntaxTextArea area : new RSyntaxTextArea[]{inputArea, outputArea}) {
            area.setLineWrap(wrap);
            area.setWrapStyleWord(wrap);
            // Code folding and soft-wrap don't combine well in RSyntaxTextArea.
            area.setCodeFoldingEnabled(!wrap);
        }
        saveProp(PROP_WRAP_LINES, String.valueOf(wrap));
    }

    /** Popup listing recent conversions; selecting one restores both editors. */
    private void showHistoryPopup(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        List<ConversionHistory.Entry> entries = history.entries();
        if (entries.isEmpty()) {
            JMenuItem empty = new JMenuItem("No conversions yet");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            java.time.format.DateTimeFormatter fmt =
                  java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
            for (ConversionHistory.Entry entry : entries) {
                JMenuItem item = new JMenuItem(String.format("%s   %s → %s   (%,d chars)",
                      entry.time().format(fmt), entry.inputFormat(), entry.outputFormat(),
                      entry.output().length()));
                item.addActionListener(ev -> restoreFromHistory(entry));
                menu.add(item);
            }
            menu.addSeparator();
            JMenuItem clear = new JMenuItem("Clear history");
            clear.addActionListener(ev -> {
                history.clear();
                setStatus("History cleared", true);
            });
            menu.add(clear);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void restoreFromHistory(ConversionHistory.Entry entry) {
        // Restoring overwrites both editors — save the current state first so
        // a restore can itself be undone from the history menu.
        String curIn  = inputArea.getText();
        String curOut = outputArea.getText();
        if (!curIn.isEmpty() || !curOut.isEmpty()) {
            history.push(new ConversionHistory.Entry(
                  inputFormatLabel.getText(), outputFormatLabel.getText(),
                  curIn, curOut, java.time.LocalTime.now()));
        }

        inputCombo.setSelectedItem(entry.inputFormat());   // updates syntax, badge, output combo
        inputArea.setText(entry.input());
        inputArea.setCaretPosition(0);

        outputCombo.setSelectedItem(entry.outputFormat());
        outputArea.setSyntaxEditingStyle(syntaxFor(entry.outputFormat()));
        outputArea.setText(entry.output());
        outputArea.setCaretPosition(0);
        outputFormatLabel.setText(entry.outputFormat());
        outputFormatLabel.repaint();

        setStatus("Restored " + entry.inputFormat() + " → " + entry.outputFormat()
              + " from history", true);
    }

    /** Compact icon-only button between the From/To combos that swaps the two sides. */
    private JButton buildSwapButton() {
        JButton btn = buildIconButton(com.intellij.icons.AllIcons.Actions.SwapPanels,
              "Swap input and output");
        btn.addActionListener(e -> doSwap());
        return btn;
    }

    /** Toggle button that switches the split pane between horizontal and vertical. */
    private JButton buildSplitToggleButton() {
        JButton btn = buildIconButton(com.intellij.icons.AllIcons.Actions.SplitVertically,
              "Toggle vertical / horizontal split");
        btn.addActionListener(e -> {
            boolean wasHorizontal = splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
            splitPane.setOrientation(wasHorizontal
                  ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT);
            btn.setIcon(wasHorizontal
                  ? com.intellij.icons.AllIcons.Actions.SplitHorizontally
                  : com.intellij.icons.AllIcons.Actions.SplitVertically);
            installDividerUI();
            splitPane.setDividerLocation(0.5);
            saveProp(PROP_SPLIT_VERTICAL, String.valueOf(wasHorizontal));
        });
        splitToggleBtn = btn;
        return btn;
    }

    private JButton buildIconButton(Icon icon, String tooltip) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setBorder(JBUI.Borders.empty(4, 6));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.getAccessibleContext().setAccessibleName(tooltip);
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                btn.setContentAreaFilled(true);
                btn.setBackground(UTIL_HOVER);
                btn.setOpaque(true);
            }
            public void mouseExited(MouseEvent e) {
                btn.setContentAreaFilled(false);
                btn.setOpaque(false);
            }
        });
        return btn;
    }

    // ── Custom split-pane divider with grip dots ─────────────────────────
    private void installDividerUI() {
        splitPane.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
            @Override
            public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                              RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(DIVIDER_BG);
                        g2.fillRect(0, 0, getWidth(), getHeight());

                        g2.setColor(DIVIDER_GRIP);
                        int cx = getWidth()  / 2;
                        int cy = getHeight() / 2;
                        int dot = JBUI.scale(3);
                        boolean horiz =
                              splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
                        for (int i = -2; i <= 2; i++) {
                            int x = horiz ? cx - dot / 2 : cx + i * JBUI.scale(5) - dot / 2;
                            int y = horiz ? cy + i * JBUI.scale(5) - dot / 2 : cy - dot / 2;
                            g2.fillRoundRect(x, y, dot, dot, dot, dot);
                        }
                        g2.dispose();
                    }
                };
            }
        });
    }

    // ── Output combo rebuild ──────────────────────────────────────────────
    private void rebuildOutputCombo(String inputFmt) {
        String[] options = VALID_OUTPUTS.getOrDefault(inputFmt, new String[]{});
        String current   = (String) outputCombo.getSelectedItem();
        outputCombo.removeAllItems();
        for (String o : options) outputCombo.addItem(o);
        boolean found = false;
        for (String o : options) {
            if (o.equals(current)) { outputCombo.setSelectedItem(o); found = true; break; }
        }
        if (!found && options.length > 0) outputCombo.setSelectedIndex(0);

        updateConversionOptions();
    }

    // ── Conversion-specific options visibility ────────────────────────────
    private void updateConversionOptions() {
        String outFmt = (String) outputCombo.getSelectedItem();
        String inFmt  = (String) inputCombo.getSelectedItem();
        boolean isCsvOut  = FMT_CSV.equals(outFmt);
        boolean isJava    = FMT_JAVA.equals(outFmt);
        boolean untypedIn = FMT_CSV.equals(inFmt) || FMT_XML.equals(inFmt);
        csvOptions.setVisible(isCsvOut);
        csvInputOptions.setVisible(untypedIn);
        javaOptions.setVisible(isJava);
        optionsBar.setVisible(isCsvOut || isJava || untypedIn);
        optionsBar.revalidate();
        optionsBar.repaint();
    }

    private static String csvModeHintFor(CsvConverter.CsvMode mode) {
        return switch (mode) {
            case FLAT_FIRST -> "expands only the first object-array into rows (safe default)";
            case CROSS_JOIN -> "Cartesian product of all object-arrays \u2014 rows can explode";
        };
    }

    // ── Convert ───────────────────────────────────────────────────────────
    private void doConvert() {
        if (!converting.compareAndSet(false, true)) return;

        final String rawInput  = inputArea.getText();
        if (rawInput.isBlank()) {
            converting.set(false);
            setStatus("Input is empty", false);
            return;
        }
        final String inFmt     = (String) inputCombo.getSelectedItem();
        final String outFmt    = (String) outputCombo.getSelectedItem();
        final CsvConverter.CsvMode csvMode = (CsvConverter.CsvMode) csvModeCombo.getSelectedItem();
        final boolean useLombok = lombokCheck.isSelected();
        final long rowWarningThreshold = ((Number) rowThresholdSpinner.getValue()).longValue();

        final boolean inferCsvTypes = inferTypesCheck.isSelected();
        final boolean detectDates   = detectDatesCheck.isSelected();

        convertBtn.setText("Cancel");
        convertBtn.setToolTipText("Cancel the running conversion");
        setStatus("Converting\u2026", true);

        java.util.concurrent.CompletableFuture
              .supplyAsync(() -> {
                  convertWorker = Thread.currentThread();
                  try {
                      String asJson = pipeline.normalizeToJson(rawInput, inFmt, inferCsvTypes);
                      if (Thread.currentThread().isInterrupted())
                          throw new CancellationException("Conversion cancelled");

                      if (FMT_CSV.equals(outFmt) && csvMode == CsvConverter.CsvMode.CROSS_JOIN) {
                          com.fasterxml.jackson.databind.JsonNode pivot = pipeline.parseJson(asJson);
                          long estimate = pipeline.estimateCsvRows(pivot, csvMode);
                          if (estimate > rowWarningThreshold) {
                              final long est = estimate;
                              java.util.concurrent.atomic.AtomicBoolean proceed =
                                    new java.util.concurrent.atomic.AtomicBoolean(false);
                              try {
                                  SwingUtilities.invokeAndWait(() -> proceed.set(confirmWarning(
                                        "Row explosion warning",
                                        String.format("CROSS_JOIN will produce ~%,d rows. Continue?", est))));
                              } catch (Exception dialogFailure) {
                                  LOG.warn("Row-explosion confirmation dialog failed; cancelling conversion",
                                        dialogFailure);
                              }
                              if (!proceed.get()) {
                                  throw new CancellationException("Conversion cancelled");
                              }
                          }
                          return pipeline.renderCsv(pivot, csvMode);
                      }

                      return pipeline.renderFromJson(asJson, outFmt, csvMode, useLombok, detectDates);
                  } catch (Exception ex) {
                      throw new java.util.concurrent.CompletionException(ex);
                  } finally {
                      convertWorker = null;
                      Thread.interrupted(); // clear a late cancel so the pooled thread stays clean
                  }
              }, com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
              .whenComplete((result, error) ->
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                        converting.set(false);
                        if (disposed) return;
                        convertBtn.setText("Convert");
                        convertBtn.setToolTipText("Convert (Ctrl+Enter)");
                        if (error != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            if (cause instanceof CancellationException) {
                                setStatusWarn("Conversion cancelled");
                            } else {
                                showError(cause.getMessage());
                            }
                        } else {
                            boolean huge = result.length() > HIGHLIGHT_LIMIT_CHARS;
                            outputArea.setSyntaxEditingStyle(
                                  huge ? SyntaxConstants.SYNTAX_STYLE_NONE : syntaxFor(outFmt));
                            outputArea.setCodeFoldingEnabled(!huge && !outputArea.getLineWrap());
                            outputArea.setText(result);
                            outputArea.setCaretPosition(0);
                            outputFormatLabel.setText(outFmt);
                            outputFormatLabel.repaint();
                            history.push(new ConversionHistory.Entry(
                                  inFmt, outFmt, rawInput, result, java.time.LocalTime.now()));
                            setStatus("Converted " + inFmt + " \u2192 " + outFmt
                                  + (huge ? "  (syntax highlighting off for large output)" : ""), true);
                        }
                    }));
    }

    /** Interrupts the background conversion worker, if any. */
    private void cancelConvert() {
        Thread worker = convertWorker;
        if (worker != null) {
            worker.interrupt();
            setStatusWarn("Cancelling…");
        }
    }

    /** Shared OK/Cancel warning dialog; returns true when the user confirms. */
    private boolean confirmWarning(String title, String message) {
        return JOptionPane.showConfirmDialog(mainPanel, message, title,
              JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    // ── Format input ──────────────────────────────────────────────────────
    private void doFormat() {
        String input = inputArea.getText().trim();
        String fmt   = (String) inputCombo.getSelectedItem();
        if (input.isEmpty()) { setStatus("Input is empty", false); return; }
        try {
            inputArea.setText(pipeline.formatInput(input, fmt, inferTypesCheck.isSelected()));
            inputArea.setCaretPosition(0);
            setStatus("\u2713  Input formatted", true);
        } catch (Exception ex) {
            showError("Format failed: " + ex.getMessage());
        }
    }

    // ── File I/O ─────────────────────────────────────────────────────────
    private void doOpenFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open input file");
        fc.setFileFilter(new FileNameExtensionFilter(
              "Data files (json, xml, yaml, csv, toml, proto)",
              "json", "xml", "yaml", "yml", "csv", "toml", "proto"));
        if (fc.showOpenDialog(mainPanel) != JFileChooser.APPROVE_OPTION) return;
        loadFile(fc.getSelectedFile());
    }

    private void loadFile(File file) {
        long size = file.length();
        if (size > LARGE_FILE_WARNING_BYTES) {
            int choice = JOptionPane.showConfirmDialog(mainPanel,
                  String.format("%s is %,d MB. Loading large files may be slow. Continue?",
                        file.getName(), size / (1024 * 1024)),
                  "Large file", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }

        setStatus("Loading " + file.getName() + "…", true);
        // Read off the EDT so a large or slow-network file cannot freeze the IDE.
        java.util.concurrent.CompletableFuture
              .supplyAsync(() -> {
                  try {
                      return Files.readString(file.toPath(), StandardCharsets.UTF_8);
                  } catch (IOException ex) {
                      throw new java.util.concurrent.CompletionException(ex);
                  }
              }, com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
              .whenComplete((content, error) ->
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                        if (disposed) return;
                        if (error != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            setStatus("Failed to open file: " + cause.getMessage(), false);
                            return;
                        }
                        inputArea.setText(content);
                        inputArea.setCaretPosition(0);

                        String ext = getExtension(file.getName()).toLowerCase();
                        String fmt = switch (ext) {
                            case "json"          -> FMT_JSON;
                            case "xml"           -> FMT_XML;
                            case "yaml", "yml"   -> FMT_YAML;
                            case "csv"           -> FMT_CSV;
                            case "toml"          -> FMT_TOML;
                            case "proto"         -> FMT_PROTO;
                            default              -> null;
                        };
                        if (fmt != null) {
                            inputCombo.setSelectedItem(fmt);
                        }
                        setStatus("Loaded " + file.getName(), true);
                    }));
    }

    private void doSaveFile() {
        String output = outputArea.getText();
        if (output.isEmpty()) { setStatus("Nothing to save", false); return; }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save output");
        // The badge reflects the format of the text actually in the output area;
        // the combo may have been changed since the last conversion.
        String outFmt = outputFormatLabel.getText();
        String ext = FORMAT_EXTENSIONS.getOrDefault(outFmt, "txt");
        fc.setSelectedFile(new File("output." + ext));
        if (fc.showSaveDialog(mainPanel) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();
        if (file.exists()) {
            int choice = JOptionPane.showConfirmDialog(mainPanel,
                  file.getName() + " already exists. Overwrite?",
                  "Confirm overwrite", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }
        try {
            Files.writeString(file.toPath(), output, StandardCharsets.UTF_8);
            setStatus("Saved to " + file.getName(), true);
        } catch (Exception ex) {
            setStatus("Failed to save: " + ex.getMessage(), false);
        }
    }

    /** Installs a file drop handler that chains with the editor's existing TransferHandler. */
    private void installFileDrop(RSyntaxTextArea area) {
        TransferHandler original = area.getTransferHandler();
        area.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true;
                return original != null && original.canImport(support);
            }
            @Override
            public boolean importData(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) support.getTransferable()
                              .getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) loadFile(files.get(0));
                        return true;
                    } catch (Exception ex) {
                        setStatus("Drop failed: " + ex.getMessage(), false);
                        return false;
                    }
                }
                return original != null && original.importData(support);
            }
        });
    }

    private static String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    // ── Utility actions ───────────────────────────────────────────────────
    private void doSwap() {
        String newInputFmt  = outputFormatLabel.getText();
        String newOutputFmt = inputFormatLabel.getText();
        if (!isValidInputFormat(newInputFmt)) {
            setStatusWarn(newInputFmt + " output cannot be used as input");
            return;
        }

        String tmpText   = inputArea.getText();
        String tmpSyntax = inputArea.getSyntaxEditingStyle();

        inputArea.setSyntaxEditingStyle(outputArea.getSyntaxEditingStyle());
        inputArea.setText(outputArea.getText());
        outputArea.setSyntaxEditingStyle(tmpSyntax);
        outputArea.setText(tmpText);

        inputFormatLabel.setText(newInputFmt);
        outputFormatLabel.setText(newOutputFmt);
        inputFormatLabel.repaint();
        outputFormatLabel.repaint();

        inputCombo.setSelectedItem(newInputFmt);
        rebuildOutputCombo(newInputFmt);

        for (int i = 0; i < outputCombo.getItemCount(); i++) {
            if (outputCombo.getItemAt(i).equals(newOutputFmt)) {
                outputCombo.setSelectedItem(newOutputFmt);
                break;
            }
        }
        setStatus("Swapped input and output", true);
    }

    private boolean isValidInputFormat(String format) {
        for (String validInput : ALL_INPUTS) {
            if (validInput.equals(format)) return true;
        }
        return false;
    }

    private void doCopy() {
        String text = outputArea.getText();
        if (!text.isEmpty()) {
            try {
                com.intellij.openapi.ide.CopyPasteManager.getInstance()
                      .setContents(new StringSelection(text));
            } catch (Throwable t) {
                // Outside a full IDE (tests, standalone), fall back to the AWT clipboard.
                Toolkit.getDefaultToolkit().getSystemClipboard()
                      .setContents(new StringSelection(text), null);
            }
            setStatus("Output copied to clipboard", true);
        }
    }

    private void doClear() {
        // Clears editors and format selection only. Persisted preferences
        // (CSV mode, Lombok, inference, …) are deliberately left untouched:
        // resetting them here would clobber the saved values.
        inputArea.setText("");
        outputArea.setText("");
        inputArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        outputArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        inputFormatLabel.setText(FMT_JSON);
        outputFormatLabel.setText(FMT_XML);
        inputFormatLabel.repaint();
        outputFormatLabel.repaint();
        inputCombo.setSelectedItem(FMT_JSON);
        rebuildOutputCombo(FMT_JSON);
        outputCombo.setSelectedItem(FMT_XML);
        setStatus("Cleared", true);
    }

    private RSyntaxTextArea buildEditor() {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setFont(editorFont());
        area.setTabSize(2);
        area.setBackground(EDITOR_BG);
        area.setCaretColor(TEXT_BRIGHT);
        area.setSelectionColor(SELECTION_BG);
        return area;
    }

    /** The user's configured IDE editor font, falling back outside a full IDE. */
    private static Font editorFont() {
        try {
            var scheme = com.intellij.openapi.editor.colors.EditorColorsManager
                  .getInstance().getGlobalScheme();
            return new Font(scheme.getEditorFontName(), Font.PLAIN, scheme.getEditorFontSize());
        } catch (Throwable t) {
            return new Font("JetBrains Mono", Font.PLAIN, 13);
        }
    }

    private void applyEditorTheme(RSyntaxTextArea area) {
        try {
            String path = JBColor.isBright()
                  ? "/org/fife/ui/rsyntaxtextarea/themes/default.xml"
                  : "/org/fife/ui/rsyntaxtextarea/themes/dark.xml";
            InputStream is = getClass().getResourceAsStream(path);
            if (is != null) Theme.load(is).apply(area);
        } catch (IOException ignored) {}
    }

    private JPanel wrapEditor(RSyntaxTextArea area, JLabel badge, String title) {
        RTextScrollPane scroll = new RTextScrollPane(area);
        scroll.setLineNumbersEnabled(true);
        scroll.setBorder(null);
        scroll.getGutter().setBackground(GUTTER_BG);
        scroll.getGutter().setLineNumberColor(GUTTER_FG);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(TEXT_DIM);
        titleLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        titleLabel.setBorder(JBUI.Borders.empty(0, 6));

        JPanel leftLabels = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        leftLabels.setOpaque(false);
        leftLabels.add(titleLabel);
        leftLabels.add(badge);

        JPanel labelBar = new JPanel(new BorderLayout());
        labelBar.setBackground(BG_LABEL_BAR);
        labelBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        labelBar.add(leftLabels, BorderLayout.WEST);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG_DARK);
        wrapper.add(labelBar, BorderLayout.NORTH);
        wrapper.add(scroll,   BorderLayout.CENTER);
        return wrapper;
    }

    /** Color-coded pill badge showing the current format name. */
    private JLabel buildFormatBadge(String text) {
        JLabel lbl = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                      RenderingHints.VALUE_ANTIALIAS_ON);
                Color pill = FORMAT_COLORS.getOrDefault(getText(), ACCENT);
                g2.setColor(pill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(),
                      JBUI.scale(10), JBUI.scale(10));

                FontMetrics fm = g2.getFontMetrics(getFont());
                g2.setColor(Color.WHITE);
                int x = (getWidth()  - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        };
        lbl.setOpaque(false);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 11));
        lbl.setBorder(JBUI.Borders.empty(3, 10));
        return lbl;
    }

    private JComboBox<String> buildCombo(String[] items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setBackground(DROPDOWN_BG);
        combo.setForeground(TEXT_BRIGHT);
        combo.setFont(new Font("SansSerif", Font.PLAIN, 13));
        combo.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                  int index, boolean isSelected, boolean hasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
                setBackground(isSelected ? ACCENT : DROPDOWN_BG);
                setForeground(TEXT_BRIGHT);
                setBorder(new EmptyBorder(4, 10, 4, 10));
                return this;
            }
        });
        return combo;
    }

    /** Rounded-corner button with hover effect. {@code utilStyle} gives theme-aware dark/light text. */
    private JButton buildButton(String label, Color bg, Color hover, boolean utilStyle) {
        JButton btn = new JButton(label) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                      RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isEnabled() ? getBackground() : UTIL_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), BUTTON_ARC, BUTTON_ARC);

                FontMetrics fm = g2.getFontMetrics(getFont());
                g2.setColor(isEnabled() ? getForeground() : TEXT_DIM);
                int x = (getWidth()  - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }

            @Override protected void paintBorder(Graphics g) { /* rounded rect is the border */ }
        };
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setForeground(utilStyle ? UTIL_TEXT : BTN_TEXT);
        btn.setBackground(bg);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setBorder(JBUI.Borders.empty(5, 14));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { if (btn.isEnabled()) btn.setBackground(hover); }
            public void mouseExited(MouseEvent e)  { if (btn.isEnabled()) btn.setBackground(bg);    }
        });
        return btn;
    }

    private JLabel toolbarLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(TEXT_DIM);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return lbl;
    }

    private JSeparator makeSep() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 24));
        sep.setForeground(new JBColor(new Color(200, 200, 200), new Color(80, 80, 80)));
        return sep;
    }

    private String syntaxFor(String fmt) {
        if (fmt == null) return SyntaxConstants.SYNTAX_STYLE_NONE;
        return switch (fmt) {
            case FMT_JSON  -> SyntaxConstants.SYNTAX_STYLE_JSON;
            case FMT_XML   -> SyntaxConstants.SYNTAX_STYLE_XML;
            case FMT_YAML  -> SyntaxConstants.SYNTAX_STYLE_YAML;
            case FMT_JAVA  -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case FMT_PROTO -> SyntaxConstants.SYNTAX_STYLE_PROTO;
            case FMT_CSV   -> SyntaxConstants.SYNTAX_STYLE_CSV;
            default        -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }

    /**
     * Shows an error in the status bar. Multi-line messages (e.g. Proto
     * validation errors with examples) don't render in a JLabel, so only the
     * first line goes to the status bar; the full text is delivered as an IDE
     * notification balloon and as the status label's tooltip.
     */
    private void showError(String message) {
        if (message == null || message.isBlank()) message = "Unknown error";
        List<String> lines = message.lines().toList();
        String first = lines.get(0);
        setStatus("Error: " + first + (lines.size() > 1 ? " …" : ""), false);
        if (lines.size() > 1) {
            statusLabel.setToolTipText("<html>" + escapeHtml(message).replace("\n", "<br>") + "</html>");
            notifyError(message);
        }
    }

    private void notifyError(String message) {
        try {
            com.intellij.notification.NotificationGroupManager.getInstance()
                  .getNotificationGroup(NOTIFICATION_GROUP)
                  .createNotification("Conversion failed", escapeHtml(message).replace("\n", "<br>"),
                        com.intellij.notification.NotificationType.ERROR)
                  .notify(project);
        } catch (Throwable outsideIde) {
            LOG.warn("Could not show error notification", outsideIde);
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void setStatus(String msg, boolean ok) {
        if (msg.length() > STATUS_MAX_LEN) {
            statusLabel.setToolTipText(msg);
            msg = msg.substring(0, STATUS_MAX_LEN) + "\u2026";
        } else {
            statusLabel.setToolTipText(null);
        }
        statusLabel.setText(msg);
        statusLabel.setForeground(ok ? OK_COLOR : ERR_COLOR);
    }

    private void setStatusWarn(String msg) {
        if (msg.length() > STATUS_MAX_LEN) {
            statusLabel.setToolTipText(msg);
            msg = msg.substring(0, STATUS_MAX_LEN) + "\u2026";
        } else {
            statusLabel.setToolTipText(null);
        }
        statusLabel.setText(msg);
        statusLabel.setForeground(WARN_COLOR);
    }

    private void updateCharCount() {
        int inLen  = inputArea.getDocument().getLength();
        int outLen = outputArea.getDocument().getLength();
        int inLines  = inLen  == 0 ? 0 : inputArea.getLineCount();
        int outLines = outLen == 0 ? 0 : outputArea.getLineCount();
        charCountLabel.setText(String.format("In: %,d lines  |  Out: %,d lines  |  %,d chars",
              inLines, outLines, (long) inLen + outLen));
    }

    public JPanel getContent() { return mainPanel; }
}
