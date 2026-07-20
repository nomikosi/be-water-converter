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
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConverterPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ConverterPanel.class);

    private static final Color BG_DARK      = new JBColor(new Color(245, 245, 245), new Color(43,  43,  43));
    private static final Color BG_TOOLBAR   = new JBColor(new Color(235, 237, 240), new Color(55,  58,  60));
    private static final Color BG_LABEL_BAR = new JBColor(new Color(240, 240, 242), new Color(37,  37,  38));
    private static final Color BG_STATUS    = new JBColor(new Color(232, 232, 232), new Color(30,  30,  30));
    private static final Color ACCENT       = new JBColor(new Color(55, 100, 180),  new Color(75, 110, 175));
    private static final Color ACCENT_HOVER = new JBColor(new Color(70, 120, 210),  new Color(95, 135, 205));
    private static final Color UTIL_BG      = new JBColor(new Color(212, 214, 216), new Color(70,  73,  75));
    private static final Color UTIL_HOVER   = new JBColor(new Color(195, 198, 200), new Color(90,  93,  95));
    private static final Color FORMAT_BG    = new JBColor(new Color(46, 139,  87),  new Color(50, 100,  60));
    private static final Color FORMAT_HOVER = new JBColor(new Color(56, 160, 100),  new Color(65, 125,  75));
    private static final Color TEXT_BRIGHT  = new JBColor(new Color(40,  40,  40),  new Color(220, 220, 220));
    private static final Color TEXT_DIM     = new JBColor(new Color(120, 120, 120), new Color(130, 130, 130));
    private static final Color OK_COLOR     = new JBColor(new Color(40, 130,  40),  new Color(98,  151,  85));
    private static final Color WARN_COLOR   = new JBColor(new Color(180, 130,  0),  new Color(222, 166,  62));
    private static final Color ERR_COLOR    = new JBColor(new Color(200, 50,  50),  new Color(204,  60,  53));
    private static final Color BORDER       = new JBColor(new Color(210, 210, 210), new Color(25,  25,  25));
    private static final Color DROPDOWN_BG  = new JBColor(new Color(255, 255, 255), new Color(60,  63,  65));
    private static final Color BTN_TEXT     = new JBColor(new Color(255, 255, 255), new Color(220, 220, 220));
    private static final Color UTIL_TEXT    = new JBColor(new Color(50,  50,  50),  new Color(220, 220, 220));
    private static final Color EDITOR_BG    = new JBColor(new Color(255, 255, 255), new Color(30,  31,  34));
    private static final Color GUTTER_BG    = new JBColor(new Color(245, 245, 245), new Color(43,  43,  43));
    private static final Color GUTTER_FG    = new JBColor(new Color(170, 170, 170), new Color(90,  90,  90));
    private static final Color DIVIDER_BG   = new JBColor(new Color(220, 220, 220), new Color(50,  50,  50));
    private static final Color DIVIDER_GRIP = new JBColor(new Color(170, 170, 170), new Color(90,  90,  90));
    private static final Color SELECTION_BG = new JBColor(new Color(173, 214, 255), new Color(33,  66, 131));

    static final String FMT_JSON  = "JSON";
    static final String FMT_XML   = "XML";
    static final String FMT_YAML  = "YAML";
    static final String FMT_CSV   = "CSV";
    static final String FMT_TOML  = "TOML";
    static final String FMT_PROTO = "Protobuf";
    static final String FMT_JAVA  = "Java POJO";

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

    /** Shared, thread-safe mapper for JSON pretty-printing. */
    private static final com.fasterxml.jackson.databind.ObjectMapper PRETTY_JSON =
          new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    private static final int BUTTON_ARC = 8;
    private static final int STATUS_MAX_LEN = 120;
    private static final String ACTION_CONVERT = "convert";
    private static final String ACTION_FORMAT = "format";
    private static final String ACTION_COPY_OUTPUT = "copyOutput";
    private static final String ACTION_OPEN_FILE = "openFile";
    private static final String ACTION_SAVE_FILE = "saveFile";

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
    private final JPanel    csvOptions;
    private final JPanel    javaOptions;
    private final JPanel    optionsBar;
    private final JSplitPane splitPane;
    private JButton convertBtn;

    private final AtomicBoolean converting = new AtomicBoolean(false);
    private final PropertyChangeListener lafListener;
    private volatile boolean disposed;

    private final JsonXmlConverter  jsonXml  = new JsonXmlConverter();
    private final JsonYamlConverter jsonYaml = new JsonYamlConverter();
    private final CsvConverter      csv      = new CsvConverter();
    private final TomlConverter     toml     = new TomlConverter();
    private final ProtoConverter    proto    = new ProtoConverter();
    private final JavaPojoGenerator pojo     = new JavaPojoGenerator();

    public ConverterPanel() {
        mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(BG_DARK);

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

        csvOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        csvOptions.setOpaque(false);
        csvOptions.add(toolbarLabel("CSV mode:"));
        csvOptions.add(csvModeCombo);
        csvOptions.add(csvModeHint);
        csvOptions.add(rowThresholdLabel);
        csvOptions.add(rowThresholdSpinner);
        rowThresholdLabel.setVisible(false);
        rowThresholdSpinner.setVisible(false);

        javaOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        javaOptions.setOpaque(false);
        javaOptions.add(toolbarLabel("Java POJO:"));
        javaOptions.add(lombokCheck);

        optionsBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 5));
        optionsBar.setBackground(BG_LABEL_BAR);
        optionsBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        optionsBar.add(toolbarLabel("Options:"));
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
        mainPanel.add(statusBar, BorderLayout.SOUTH);

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
            }
        };
        UIManager.addPropertyChangeListener(lafListener);
    }

    @Override
    public void dispose() {
        disposed = true;
        UIManager.removePropertyChangeListener(lafListener);
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
        convertBtn.addActionListener(e -> doConvert());
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

        return bar;
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
        });
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
        boolean isCsv  = FMT_CSV.equals(outFmt);
        boolean isJava = FMT_JAVA.equals(outFmt);
        csvOptions.setVisible(isCsv);
        javaOptions.setVisible(isJava);
        optionsBar.setVisible(isCsv || isJava);
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

        convertBtn.setEnabled(false);
        setStatus("Converting\u2026", true);

        java.util.concurrent.CompletableFuture
              .supplyAsync(() -> {
                  try {
                      String asJson = normalizeToJson(rawInput, inFmt);

                      if (FMT_CSV.equals(outFmt) && csvMode == CsvConverter.CsvMode.CROSS_JOIN) {
                          long estimate = csv.estimateRowCount(asJson, csvMode);
                          if (estimate > rowWarningThreshold) {
                              final long est = estimate;
                              java.util.concurrent.atomic.AtomicBoolean proceed =
                                    new java.util.concurrent.atomic.AtomicBoolean(false);
                              try {
                                  SwingUtilities.invokeAndWait(() -> {
                                      int choice = JOptionPane.showConfirmDialog(mainPanel,
                                            String.format("CROSS_JOIN will produce ~%,d rows. Continue?", est),
                                            "Row explosion warning",
                                            JOptionPane.OK_CANCEL_OPTION,
                                            JOptionPane.WARNING_MESSAGE);
                                      proceed.set(choice == JOptionPane.OK_OPTION);
                                  });
                              } catch (Exception dialogFailure) {
                                  LOG.warn("Row-explosion confirmation dialog failed; cancelling conversion",
                                        dialogFailure);
                              }
                              if (!proceed.get()) {
                                  throw new CancellationException("Conversion cancelled");
                              }
                          }
                      }

                      return renderFromJson(asJson, outFmt, csvMode, useLombok);
                  } catch (Exception ex) {
                      throw new java.util.concurrent.CompletionException(ex);
                  }
              }, com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
              .whenComplete((result, error) ->
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                        converting.set(false);
                        if (disposed) return;
                        convertBtn.setEnabled(true);
                        if (error != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            if (cause instanceof CancellationException) {
                                setStatusWarn("Conversion cancelled");
                            } else {
                                setStatus("Error: " + cause.getMessage(), false);
                            }
                        } else {
                            outputArea.setSyntaxEditingStyle(syntaxFor(outFmt));
                            outputArea.setText(result);
                            outputArea.setCaretPosition(0);
                            outputFormatLabel.setText(outFmt);
                            outputFormatLabel.repaint();
                            setStatus("Converted " + inFmt + " \u2192 " + outFmt, true);
                        }
                    }));
    }

    /**
     * Normalise input to JSON as the internal pivot format.
     * autoClose is applied once for JSON input to repair truncated brackets.
     */
    private String normalizeToJson(String rawInput, String inFmt) throws Exception {
        String input = (inFmt.equals(FMT_JSON)) ? autoClose(rawInput) : rawInput;
        return switch (inFmt) {
            case FMT_JSON  -> input;
            case FMT_XML   -> jsonXml.xmlToJson(input);
            case FMT_YAML  -> jsonYaml.yamlToJson(input);
            case FMT_CSV   -> csv.csvToJson(input);
            case FMT_TOML  -> toml.tomlToJson(input);
            case FMT_PROTO -> proto.protoToJson(input);
            default -> throw new UnsupportedOperationException("Unknown input: " + inFmt);
        };
    }

    /** JSON pivot -> desired output format. */
    private String renderFromJson(String asJson, String outFmt,
          CsvConverter.CsvMode csvMode, boolean useLombok) throws Exception {
        return switch (outFmt) {
            case FMT_JSON  -> prettyJson(asJson);
            case FMT_XML   -> jsonXml.jsonToXml(asJson);
            case FMT_YAML  -> jsonYaml.jsonToYaml(asJson);
            case FMT_CSV   -> csv.jsonToCsv(asJson, csvMode);
            case FMT_TOML  -> toml.jsonToToml(asJson);
            case FMT_PROTO -> proto.jsonToProto(asJson);
            case FMT_JAVA  -> pojo.fromJson(asJson, useLombok);
            default -> throw new UnsupportedOperationException("Unknown output: " + outFmt);
        };
    }

    // ── Format input ──────────────────────────────────────────────────────
    private void doFormat() {
        String input = inputArea.getText().trim();
        String fmt   = (String) inputCombo.getSelectedItem();
        if (input.isEmpty()) { setStatus("Input is empty", false); return; }
        try {
            String formatted = switch (fmt) {
                case FMT_JSON -> prettyJson(autoClose(input));
                case FMT_XML  -> prettyXml(input);
                case FMT_YAML -> jsonYaml.jsonToYaml(jsonYaml.yamlToJson(input));
                case FMT_TOML -> toml.jsonToToml(toml.tomlToJson(input));
                case FMT_CSV  -> {
                    String json = csv.csvToJson(input);
                    yield csv.jsonToCsv(json, CsvConverter.CsvMode.FLAT_FIRST);
                }
                case FMT_PROTO -> input.replaceAll("[ \t]+\n", "\n")
                      .replaceAll("\n{3,}", "\n\n").trim();
                default       -> input;
            };
            inputArea.setText(formatted);
            inputArea.setCaretPosition(0);
            setStatus("\u2713  Input formatted", true);
        } catch (Exception ex) {
            setStatus("\u2717  Format failed: " + ex.getMessage(), false);
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
        csvModeCombo.setSelectedItem(CsvConverter.CsvMode.FLAT_FIRST);
        lombokCheck.setSelected(false);
        setStatus("Cleared", true);
    }

    // ── autoClose ────────────────────────────────────────────────────────
    /**
     * Leniently closes any unclosed JSON { or [ brackets.
     * Called once per conversion on the raw input before anything else.
     * Only applied when the input format is JSON.
     */
    private String autoClose(String json) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escape   = false;
        for (char c : json.toCharArray()) {
            if (escape)        { escape = false; continue; }
            if (c == '\\')     { if (inString) escape = true; continue; }
            if (c == '"')      { inString = !inString; continue; }
            if (inString)      continue;
            if (c == '{')      stack.push('}');
            else if (c == '[') stack.push(']');
            else if (c == '}' || c == ']') { if (!stack.isEmpty()) stack.pop(); }
        }
        StringBuilder sb = new StringBuilder(json);
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();
    }

    // ── Builder helpers ───────────────────────────────────────────────────
    private String prettyJson(String json) throws Exception {
        return PRETTY_JSON.writeValueAsString(PRETTY_JSON.readTree(json));
    }

    /**
     * Pretty-prints XML via DOM + Transformer so the original root element,
     * attributes and structure are preserved (Jackson's tree model drops the
     * root element name). External entities and DTDs are disabled.
     */
    private String prettyXml(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf =
              javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setExpandEntityReferences(false);
        org.w3c.dom.Document doc = dbf.newDocumentBuilder()
              .parse(new org.xml.sax.InputSource(new StringReader(xml)));
        doc.getDocumentElement().normalize();
        stripWhitespaceNodes(doc.getDocumentElement());

        javax.xml.transform.TransformerFactory tf =
              javax.xml.transform.TransformerFactory.newInstance();
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        javax.xml.transform.Transformer t = tf.newTransformer();
        t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION,
              xml.stripLeading().startsWith("<?xml") ? "no" : "yes");

        StringWriter out = new StringWriter();
        t.transform(new javax.xml.transform.dom.DOMSource(doc),
              new javax.xml.transform.stream.StreamResult(out));
        return out.toString();
    }

    /** Removes whitespace-only text nodes so re-indenting doesn't stack blank lines. */
    private void stripWhitespaceNodes(org.w3c.dom.Node node) {
        org.w3c.dom.NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                  && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                stripWhitespaceNodes(child);
            }
        }
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
