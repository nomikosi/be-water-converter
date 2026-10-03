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

import com.converter.core.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.converter.ConverterTheme.*;
import static com.converter.core.Formats.*;

public class ConverterPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ConverterPanel.class);

    /** Client-property key under which the panel registers itself on its root component. */
    public static final String PANEL_CLIENT_PROPERTY = "beWater.converterPanel";

    /**
     * A single insertion of at least this many characters is treated as a paste
     * or drop rather than typing, and triggers input-format detection.
     */
    private static final int PASTE_MIN_CHARS = 12;

    /**
     * How much of a pasted document format detection reads. Its markers are
     * structural and sit near the top, and scanning megabytes of it on the
     * EDT stalled the editor.
     */
    private static final int DETECT_SAMPLE_CHARS = 64 * 1024;

    private static final Map<String, Color> FORMAT_COLORS = new LinkedHashMap<>();
    static {
        FORMAT_COLORS.put(FMT_JSON,  new JBColor(new Color(41, 128, 185), new Color(52, 152, 219)));
        FORMAT_COLORS.put(FMT_XML,   new JBColor(new Color(211, 84,   0), new Color(230, 126, 34)));
        FORMAT_COLORS.put(FMT_YAML,  new JBColor(new Color(142, 68, 173), new Color(155, 89, 182)));
        FORMAT_COLORS.put(FMT_CSV,   new JBColor(new Color(39, 174,  96), new Color(46, 204, 113)));
        FORMAT_COLORS.put(FMT_TOML,  new JBColor(new Color(44,  62,  80), new Color(149, 165, 166)));
        FORMAT_COLORS.put(FMT_PROTO, new JBColor(new Color(192, 57,  43), new Color(231, 76,  60)));
        FORMAT_COLORS.put(FMT_JAVA,  new JBColor(new Color(142, 110, 45), new Color(243, 196, 66)));
        FORMAT_COLORS.put(FMT_SCHEMA, new JBColor(new Color(0, 121, 107), new Color(38, 166, 154)));
        FORMAT_COLORS.put(FMT_KOTLIN, new JBColor(new Color(103, 58, 183), new Color(149, 117, 205)));
    }

    private static final String[] ALL_INPUTS = Formats.inputNames();

    private static final int STATUS_MAX_LEN = 120;
    private static final String ACTION_CONVERT = "convert";
    private static final String ACTION_SAVE_FILE = "saveFile";
    private static final String ACTION_FIND      = "find";

    private final JPanel            mainPanel;
    private final RSyntaxTextArea   inputArea;
    private final RSyntaxTextArea   outputArea;
    private final ConverterEditorState editors;
    private final JLabel            statusLabel;
    private final JLabel            charCountLabel;
    private final JLabel            inputFormatLabel;
    private final JLabel            outputFormatLabel;
    private final JComboBox<String> inputCombo;
    private final JComboBox<String> outputCombo;
    private final OptionsBar options;
    private final ConversionHistory history = new ConversionHistory();
    private final JSplitPane splitPane;
    private JButton convertBtn;
    private JButton splitToggleBtn;
    private FindBar findBar;
    private ConverterFileOps fileOps;
    private RSyntaxTextArea findTarget;

    private final com.intellij.openapi.project.Project project;
    private final ConversionRun run = new ConversionRun();
    /** Theme, editor font and zoom changes; null outside a running IDE. */
    private final com.intellij.util.messages.MessageBusConnection appearance;
    /** True while a Format runs: a second one asked its question again and then reported a change. */
    private boolean formatting;
    private volatile boolean disposed;

    private final ConversionPipeline pipeline;
    private final BackgroundTasks tasks;
    private long statusRevision;

    public ConverterPanel() {
        this(null);
    }

    public ConverterPanel(com.intellij.openapi.project.Project project) {
        this(project, new ConversionPipeline(), BackgroundTasks.forIde());
    }

    ConverterPanel(com.intellij.openapi.project.Project project, ConversionPipeline pipeline,
          BackgroundTasks tasks) {
        this.project = project;
        this.pipeline = pipeline;
        this.tasks = tasks;
        mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(BG_DARK);
        mainPanel.putClientProperty(PANEL_CLIENT_PROPERTY, this);

        inputArea  = buildEditor();
        outputArea = buildEditor();
        outputArea.setEditable(false);
        inputArea.getAccessibleContext().setAccessibleName("Input");
        outputArea.getAccessibleContext().setAccessibleName("Output");
        applyEditorTheme(inputArea);
        applyEditorTheme(outputArea);
        fileOps = new ConverterFileOps(mainPanel, project, () -> disposed,
              new ConverterFileOps.Host() {
                  @Override public void status(String message, boolean ok) {
                      setStatus(message, ok);
                  }
                  @Override public long inputRevision() { return editors.revision(); }
                  @Override public void loaded(String content, String detectedFormat, String fileName) {
                      // A known extension beats content sniffing; without one, let
                      // the content detector have its say instead of suppressing it.
                      String note = "";
                      if (detectedFormat != null) {
                          setInputTextQuietly(content);
                          inputCombo.setSelectedItem(detectedFormat);
                          if (FMT_CSV.equals(detectedFormat)) note = options.applyDetectedDelimiter(content, fileName);
                      } else {
                          editors.replaceInput(content, true);
                      }
                      setStatus("Loaded " + fileName + note, true);
                  }
              }, tasks);
        inputArea.setTransferHandler(fileOps.chainFileDrop(inputArea.getTransferHandler()));

        inputFormatLabel  = buildFormatBadge(FMT_JSON);
        outputFormatLabel = buildFormatBadge(FMT_XML);
        editors = new ConverterEditorState(inputArea, outputArea, inputFormatLabel, outputFormatLabel);

        inputCombo  = buildCombo(ALL_INPUTS);
        outputCombo = buildCombo(Formats.outputsFor(FMT_JSON));
        inputCombo.getAccessibleContext().setAccessibleName("Input format");
        outputCombo.getAccessibleContext().setAccessibleName("Output format");
        outputCombo.setSelectedItem(FMT_XML);

        // Enter in the filter re-runs the conversion; the schema button picks a payload's .proto.
        options = new OptionsBar(this::doConvert, this::chooseProtoSchema);

        inputCombo.addActionListener(e -> {
            String fmt = (String) inputCombo.getSelectedItem();
            if (fmt == null) return;
            editors.setInputFormat(fmt);
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
        restoreLayout();
        options.restoreAndRemember();

        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(TEXT_DIM);
        statusLabel.setFont(JBFont.medium());
        statusLabel.setBorder(JBUI.Borders.empty(4, 10));

        charCountLabel = new JLabel("");
        charCountLabel.setForeground(TEXT_DIM);
        charCountLabel.setFont(JBFont.small());
        charCountLabel.setBorder(JBUI.Borders.empty(4, 10));
        updateCharCount();

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(BG_STATUS);
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
        statusBar.add(statusLabel,    BorderLayout.WEST);
        statusBar.add(charCountLabel, BorderLayout.EAST);

        findBar = new FindBar(() -> findTarget, new FindBar.StatusSink() {
            @Override public void ok(String message)   { setStatus(message, true); }
            @Override public void warn(String message) { setStatusWarn(message); }
        });
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
        north.add(options.component(), BorderLayout.SOUTH);

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
        installPasteDetection();

        // ── follow the IDE's theme, editor font and zoom ─────────────────
        // A UIManager listener fired inside UIManager.setLookAndFeel, before
        // the IDE had switched JBColor to the new theme, so both editors took
        // the theme being left. LafManagerListener runs after that, and the
        // work waits until the IDE has rebuilt its components' UI, which also
        // replaces the split pane's custom divider.
        var application = com.intellij.openapi.application.ApplicationManager.getApplication();
        appearance = application == null ? null : application.getMessageBus().connect();
        if (appearance != null) {
            appearance.subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC, source -> refreshAppearance());
            appearance.subscribe(com.intellij.openapi.editor.colors.EditorColorsManager.TOPIC,
                  scheme -> refreshAppearance());
            appearance.subscribe(com.intellij.ide.ui.UISettingsListener.TOPIC, settings -> refreshAppearance());
        }
    }

    private void refreshAppearance() {
        SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            applyEditorTheme(inputArea);
            applyEditorTheme(outputArea);
            installDividerUI();
            // Custom-painted components use JBColor (which resolves per theme
            // at paint time), so a repaint refreshes them.
            mainPanel.repaint();
        });
    }

    /** What the tool window focuses when it opens: the input editor. */
    public JComponent preferredFocusComponent() {
        return inputArea;
    }

    @Override
    public void dispose() {
        disposed = true;
        run.stop();
        if (appearance != null) appearance.disconnect();
        options.dispose();
        // A blinking caret runs a Swing timer that holds its editor, and through
        // it this panel, until it is stopped.
        inputArea.getCaret().setBlinkRate(0);
        outputArea.getCaret().setBlinkRate(0);
    }

    // ── Public entry points for registered IDE actions ───────────────────

    /**
     * Loads text into the input editor from outside the panel (an editor
     * selection, a Project-view file). A null {@code format} leaves detection to
     * the content sniffer; anything else is taken as authoritative.
     */
    public void loadContent(String text, String format) {
        if (format != null) {
            setInputTextQuietly(text);
            inputCombo.setSelectedItem(format);
            if (FMT_CSV.equals(format)) {
                String note = options.applyDetectedDelimiter(text, null);
                if (!note.isEmpty()) setStatus("Loaded CSV input" + note, true);
            }
        } else {
            editors.replaceInput(text, true);
        }
        inputArea.requestFocusInWindow();
    }

    public void convert()     { doConvert(); }
    public void formatInput() { doFormat(); }
    public void copyOutput()  { doCopy(); }
    public void openFile()    { doOpenFile(); }
    public void saveOutput()  { doSaveFile(); }

    /** Restores the remembered editor layout: soft-wrap and split orientation. */
    private void restoreLayout() {
        if (ConverterSettings.wrapLines()) setLineWrap(true);
        if (ConverterSettings.splitVertical()) {
            splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
            if (splitToggleBtn != null) {
                splitToggleBtn.setIcon(com.intellij.icons.AllIcons.Actions.SplitHorizontally);
            }
            installDividerUI();
        }
    }

    private void installKeyboardShortcuts() {
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
              ACTION_CONVERT, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { doConvert(); }
              });

        // No Swing bindings for Format, Copy Output or Open: the IDE's keymap
        // runs first, and its Load Context (Alt+Shift+L), Recent Changes
        // (Alt+Shift+C) and Load Gradle Changes (Ctrl+Shift+O) took those keys,
        // so they never reached this panel. The registered actions take any
        // shortcut in Settings | Keymap.
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_S,
              InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
              ACTION_SAVE_FILE, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { doSaveFile(); }
              });

        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
              ACTION_FIND, new AbstractAction() {
                  @Override public void actionPerformed(ActionEvent e) { findBar.open(); }
              });
    }

    private void bindShortcut(KeyStroke keyStroke, String actionKey, Action action) {
        List<JComponent> targets = new ArrayList<>(List.of(mainPanel, inputArea, outputArea, inputCombo, outputCombo));
        targets.addAll(List.of(options.shortcutTargets()));
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
        convertBtn.setFont(JBFont.label().asBold());
        convertBtn.setToolTipText("Convert (Ctrl+Enter)");
        convertBtn.addActionListener(e -> {
            if (run.isRunning()) cancelConvert(); else doConvert();
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

        JButton openInEditorBtn = buildIconButton(com.intellij.icons.AllIcons.Actions.MoveTo2,
              "Open the output in a real IDE editor (scratch file)");
        openInEditorBtn.addActionListener(e -> doOpenInEditor());
        bar.add(openInEditorBtn);

        JButton compareBtn = buildIconButton(com.intellij.icons.AllIcons.Actions.Diff,
              "Compare input and output as canonical JSON");
        compareBtn.addActionListener(e -> doCompare());
        bar.add(compareBtn);

        JButton historyBtn = buildIconButton(com.intellij.icons.AllIcons.Vcs.History,
              "Conversion history — restore a previous conversion");
        historyBtn.addActionListener(e -> showHistoryPopup(historyBtn));
        bar.add(historyBtn);

        return bar;
    }

    /**
     * Replaces the input text without triggering paste detection, for the cases
     * where the panel already knows the format. Detection is deferred through
     * invokeLater, so re-enabling through the same queue lands after it.
     */
    private void setInputTextQuietly(String text) {
        editors.replaceInput(text, false);
    }

    /**
     * Switches the input format to match pasted content. Only large single
     * insertions are considered a paste — reacting to ordinary typing would
     * fight the user as a document takes shape mid-keystroke. A detection that
     * matches the current selection, or that returns null, changes nothing.
     */
    private void installPasteDetection() {
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                if (e.getLength() < PASTE_MIN_CHARS) return;
                // The document is locked during the event; defer the read.
                SwingUtilities.invokeLater(() -> {
                    if (disposed || !editors.shouldAutoDetect()) return;
                    // Sniffing runs regexes over the whole string, which on a
                    // multi-megabyte paste froze the EDT for hundreds of
                    // milliseconds. Every marker detectFormat looks for is
                    // structural and appears near the top, so a prefix decides
                    // it just as well and bounds the cost. Kept synchronous:
                    // deferring it to a pool made the format flip after the
                    // paste had already settled.
                    String text = inputArea.getText();
                    String head = text.length() > DETECT_SAMPLE_CHARS
                          ? text.substring(0, DETECT_SAMPLE_CHARS) : text;
                    String detected = FormatDetector.detectFormat(head);
                    if (detected == null) return;
                    // Bytes pasted for a payload stay one: a C array of hex
                    // bytes, a line of them at a time, reads as CSV.
                    if (FMT_PROTO_PAYLOAD.equals(inputCombo.getSelectedItem())
                          && ProtoPayloadText.readsAsPayload(head)) return;
                    // The delimiter is part of what "CSV" means for a paste, so
                    // it is set even when the format itself is already right.
                    String note = FMT_CSV.equals(detected) ? options.applyDetectedDelimiter(head, null) : "";
                    if (detected.equals(inputCombo.getSelectedItem())) {
                        if (!note.isEmpty()) setStatus("Detected " + detected + " input" + note, true);
                        return;
                    }
                    inputCombo.setSelectedItem(detected);
                    setStatus("Detected " + detected + " input" + note, true);
                });
            }
            @Override public void removeUpdate(DocumentEvent e)  { }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
    }

    // ── Soft-wrap ─────────────────────────────────────────────────────────
    private void setLineWrap(boolean wrap) {
        editors.setLineWrap(wrap);
        ConverterSettings.saveWrapLines(wrap);
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
        ConverterEditorState.Snapshot previous = editors.snapshot();
        boolean previousKept = true;
        if (!previous.input().text().isEmpty() || !previous.output().text().isEmpty()) {
            previousKept = history.push(new ConversionHistory.Entry(
                  previous.input().format(), previous.output().format(),
                  previous.input().text(), previous.output().text(), java.time.LocalTime.now(),
                  options.currentOptions()));
        }
        inputCombo.setSelectedItem(entry.inputFormat());
        outputCombo.setSelectedItem(entry.outputFormat());
        options.applyOptions(entry.options());
        editors.apply(new ConverterEditorState.Snapshot(
              new ConverterEditorState.Document(entry.input(), entry.inputFormat()),
              new ConverterEditorState.Document(entry.output(), entry.outputFormat())));

        String restored = "Restored " + entry.inputFormat() + " → " + entry.outputFormat()
              + " from history";
        if (previousKept) {
            setStatus(restored, true);
        } else {
            setStatusWarn(restored + "  (previous content too large to keep for undo)");
        }
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
            ConverterSettings.saveSplitVertical(wasHorizontal);
        });
        splitToggleBtn = btn;
        return btn;
    }

    private JButton buildIconButton(Icon icon, String tooltip) {
        return ConverterWidgets.iconButton(icon, tooltip);
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
        String[] options = Formats.outputsFor(inputFmt);
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

    /** Shows the option groups the selected formats make relevant. */
    private void updateConversionOptions() {
        options.showFor((String) inputCombo.getSelectedItem(), (String) outputCombo.getSelectedItem());
    }

    // ── Convert ───────────────────────────────────────────────────────────
    private void doConvert() {
        if (!run.tryStart()) return;

        final String rawInput  = inputArea.getText();
        if (rawInput.isBlank()) {
            run.finished();
            setStatus("Input is empty", false);
            return;
        }
        final String inFmt     = (String) inputCombo.getSelectedItem();
        final String outFmt    = (String) outputCombo.getSelectedItem();
        // Snapshot every option on the EDT: the worker must not read Swing state.
        final ConversionOptions opts = options.currentOptions();
        final CsvConverter.CsvMode csvMode = opts.csvMode();
        final long rowWarningThreshold = options.rowWarningThreshold();

        convertBtn.setText("Cancel");
        convertBtn.setToolTipText("Cancel the running conversion");
        setStatus("Converting\u2026", true);

        var completion = currentCompletion(false, "Input changed; conversion discarded",
              (String result, Throwable error) -> {
            // Cancel may arrive after rendering, with completion
            // already queued on the EDT. It still owns the result.
            if (run.cancelRequested()) {
                setStatusWarn("Conversion cancelled");
            } else if (error != null) {
                Throwable cause = error;
                if (cause instanceof CancellationException) {
                    setStatusWarn("Conversion cancelled");
                } else {
                    showError(ConverterNotifications.describe(cause));
                    jumpToErrorLocation(cause);
                }
            } else {
                boolean huge = editors.replaceOutput(result, outFmt);
                // push() refuses entries over its size cap. Discarding
                // the answer meant a large conversion simply was not
                // in the history later, with nothing having said so \u2014
                // restoreFromHistory already reports the same refusal.
                boolean kept = history.push(new ConversionHistory.Entry(
                      inFmt, outFmt, rawInput, result, java.time.LocalTime.now(), opts));
                setStatus("Converted " + inFmt + " \u2192 " + outFmt
                      + (huge ? "  (syntax highlighting off for large output)" : "")
                      + (kept ? "" : "  (too large for the history)"), true);
            }
        });

        tasks.submit(() -> {
            run.attachWorker();
            try {
                // Cancel may have been pressed while this task was still queued,
                // in which case there was no thread to interrupt.
                run.checkCancelled();
                String asJson = pipeline.normalizeToJson(rawInput, inFmt, opts);
                run.checkCancelled();

                String rendered;
                if (FMT_CSV.equals(outFmt)) {
                    com.fasterxml.jackson.databind.JsonNode pivot = pipeline.parseJson(asJson);
                    long estimate = pipeline.estimateCsvRows(pivot, csvMode);
                    if (estimate > rowWarningThreshold && !confirmFromWorker(
                          "Row count warning",
                          String.format("%s will produce ~%,d rows. Continue?",
                                csvMode, estimate))) {
                        throw new CancellationException("Conversion cancelled");
                    }
                    run.checkCancelled();
                    rendered = pipeline.renderCsv(pivot, csvMode, opts.csvFormat());
                } else {
                    rendered = pipeline.renderFromJson(asJson, outFmt, opts);
                }
                run.checkCancelled();
                return rendered;
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException(ex);
            } finally {
                run.detachWorker();
            }
        }, (result, error) -> {
            run.finished();
            if (!disposed) {
                convertBtn.setText("Convert");
                convertBtn.setToolTipText("Convert (Ctrl+Enter)");
            }
            completion.accept(result, error);
        });
    }

    /** Requests cancellation of the running conversion; see {@link ConversionRun}. */
    private void cancelConvert() {
        if (run.cancel()) setStatusWarn("Cancelling…");
    }

    /** Shared Continue/Cancel warning dialog; returns true when the user continues. */
    private boolean confirmWarning(String title, String message) {
        return ConverterDialogs.confirm(project, mainPanel, title, message);
    }

    /**
     * Asks {@link #confirmWarning}'s question from a worker thread, blocking
     * until it is answered. False when the panel is gone — a disposed panel
     * must not raise a modal dialog, there is no window left to parent it to —
     * or the dialog could not be shown, so the caller cancels rather than
     * proceeding on an answer nobody gave.
     */
    private boolean confirmFromWorker(String title, String message) {
        if (disposed) return false;
        AtomicBoolean proceed = new AtomicBoolean(false);
        try {
            // Asked again on the EDT: the project can close between the check
            // above and this running, and the dialog then had no panel.
            SwingUtilities.invokeAndWait(() -> proceed.set(!disposed && confirmWarning(title, message)));
        } catch (Exception dialogFailure) {
            LOG.warn("Confirmation dialog failed; treating the answer as Cancel", dialogFailure);
        }
        return proceed.get();
    }

    // ── Format input ──────────────────────────────────────────────────────
    private void doFormat() {
        final String input = inputArea.getText();
        final String fmt   = (String) inputCombo.getSelectedItem();
        if (input.isBlank()) { setStatus("Input is empty", false); return; }
        if (formatting) { setStatusWarn("Format is already running"); return; }
        formatting = true;
        setStatus("Formatting\u2026", true);
        final ConversionOptions opts = options.currentOptions();
        // Where the user was: the whole document is replaced, which put the
        // caret at the top and scrolled a long file away from the line being
        // edited. The line survives the re-layout better than the offset does.
        final int caretLine = inputArea.getCaretLineNumber();
        // Formatting parses and re-serialises the whole document; measured in
        // the hundreds of milliseconds on multi-megabyte input, so off the EDT.
        tasks.submit(() -> {
            try {
                // Format keeps only the data. Comments and anchors do not come
                // back, so the user is asked before the editor is overwritten
                // with less than it held — silently dropping every comment from
                // a k8s manifest is a rewrite, not a tidy.
                String losses = pipeline.formatLosses(input, fmt);
                if (losses != null && !confirmFromWorker("Format",
                      "Format keeps only the data, so it would drop " + losses
                      + " from the document. Continue?"))
                    throw new CancellationException("Format cancelled");
                return pipeline.formatInput(input, fmt, opts);
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, whenFormatDone(currentCompletion(false, "Input changed while formatting; the result was discarded",
              (formatted, failure) -> {
            if (failure instanceof CancellationException) {
                setStatusWarn("Format cancelled");
                return;
            }
            if (failure != null) {
                showError("Format failed: " + ConverterNotifications.describe(failure));
                jumpToErrorLocation(failure);
                return;
            }
            // Nothing to write: replacing the document with itself only moved
            // the caret and added an undo step.
            if (formatted.equals(inputArea.getText())) {
                setStatus("\u2713  Input already formatted", true);
                return;
            }
            setInputTextQuietly(formatted);
            moveCaretToLine(caretLine);
            setStatus("\u2713  Input formatted", true);
        })));
    }

    /** A Format completion that first marks Format as no longer running, whichever way it ended. */
    private <T> java.util.function.BiConsumer<T, Throwable> whenFormatDone(
          java.util.function.BiConsumer<T, Throwable> completion) {
        return (result, failure) -> {
            formatting = false;
            completion.accept(result, failure);
        };
    }

    /** Puts the input caret at the start of {@code line}, or the last line when the document is shorter. */
    private void moveCaretToLine(int line) {
        try {
            int target = Math.min(Math.max(line, 0), Math.max(0, inputArea.getLineCount() - 1));
            inputArea.setCaretPosition(inputArea.getLineStartOffset(target));
        } catch (javax.swing.text.BadLocationException outOfRange) {
            inputArea.setCaretPosition(0);
        }
    }

    // ── File I/O (delegated to ConverterFileOps) ─────────────────────────
    private void doOpenFile() {
        fileOps.openFile();
    }

    private void doSaveFile() {
        String output = outputArea.getText();
        if (output.isEmpty()) { setStatus("Nothing to save", false); return; }
        // The badge reflects the format of the text actually in the output area;
        // the combo may have been changed since the last conversion.
        fileOps.saveOutput(output, outputFormatLabel.getText());
    }

    /**
     * Hands the current output to a scratch file, so it opens in a real IDE
     * editor with proper highlighting, folding, the user's own keymap and Save As.
     *
     * <p>This is the cheap way to get what replacing the embedded editor would
     * have bought. The machinery already existed for context-menu conversions;
     * the tool window simply never called it.
     */
    private void doOpenInEditor() {
        String output = outputArea.getText();
        if (output.isEmpty()) { setStatus("Nothing to open", false); return; }
        if (project == null) {
            setStatusWarn("Open in editor needs a project");
            return;
        }
        // The badge reflects what is actually in the pane; the combo may have
        // moved on since the last conversion.
        String format = outputFormatLabel.getText();
        try {
            // The size confirmation lives in ConverterScratchFiles, so both this
            // and the context menu get it without either having to remember.
            var result = ConverterScratchFiles.openAsScratch(project, null, format, output);
            if (result.declined()) { setStatusWarn("Open in editor cancelled"); return; }
            setStatus(result.created() ? "Opened " + result.file().getName() + " in the editor"
                  : "Could not create a scratch file", result.created());
        } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
            // Control flow, not a failure: the platform cancels this when the
            // project closes mid-open, and it must reach the platform unchanged.
            throw cancelled;
        } catch (Throwable failure) {
            showError("Open in editor failed: " + ConverterNotifications.describe(failure));
        }
    }

    /**
     * Opens the IDE diff viewer on the two editors, each normalised to canonical
     * JSON. Comparing the canonical forms rather than the raw text is what lets a
     * YAML input and a JSON output be recognised as carrying the same data.
     */
    private void doCompare() {
        String inputText  = inputArea.getText();
        String outputText = outputArea.getText();
        if (inputText.isBlank() || outputText.isBlank()) {
            setStatus("Both editors need content to compare", false);
            return;
        }
        String inFmt  = inputFormatLabel.getText();
        String outFmt = outputFormatLabel.getText();
        if (!isValidInputFormat(outFmt)) {
            setStatusWarn(outFmt + " output cannot be parsed back for comparison");
            return;
        }
        final ConversionOptions opts = options.currentOptions();
        setStatus("Comparing…", true);
        // Canonicalising is three parse/serialise passes per side — over a
        // second on a 10 MB document — so it must not run on the EDT.
        tasks.submit(() -> {
            try {
                // The output pane has already been filtered, so re-applying the
                // pointer to it would throw "Path matched nothing".
                return new String[]{
                      pipeline.canonicalJson(inputText, inFmt, opts),
                      pipeline.canonicalJson(outputText, outFmt, opts.withFilterPath("")),
                };
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, currentCompletion(true, "Editors changed; comparison discarded", (sides, failure) -> {
            if (failure != null) {
                showError("Compare failed: " + ConverterNotifications.describe(failure));
                return;
            }
            if (sides[0].equals(sides[1])) setStatus("Input and output are equivalent", true);
            else setStatus("Comparing " + inFmt + " with " + outFmt, true);
            try {
                ConverterDiff.show(project, "Be Water: " + inFmt + " vs " + outFmt,
                      inFmt + " (input)", sides[0], outFmt + " (output)", sides[1]);
            } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
                throw cancelled;   // control flow: it must reach the platform
            } catch (Throwable noIde) {
                // No running IDE (tests, standalone): the comparison itself still ran.
                showError("Compare failed: " + ConverterNotifications.describe(noIde));
            }
        }));
    }

    /** Gates results, errors and cancellation together before any UI effects. */
    private <T> java.util.function.BiConsumer<T, Throwable> currentCompletion(
          boolean includeOutput, String staleMessage,
          java.util.function.BiConsumer<T, Throwable> completion) {
        long inputRevision = editors.revision();
        long outputRevision = editors.outputRevision();
        long pendingStatusRevision = statusRevision;
        return (result, error) -> {
            if (disposed) return;
            if (editors.revision() != inputRevision
                  || (includeOutput && editors.outputRevision() != outputRevision)) {
                // A newer action owns its status as well as its document.
                if (statusRevision == pendingStatusRevision) setStatusWarn(staleMessage);
                return;
            }
            completion.accept(result, error);
        };
    }

    // ── Utility actions ───────────────────────────────────────────────────
    private void doSwap() {
        String newInputFmt  = outputFormatLabel.getText();
        String newOutputFmt = inputFormatLabel.getText();
        if (!isValidInputFormat(newInputFmt)) {
            setStatusWarn(newInputFmt + " output cannot be used as input");
            return;
        }
        // A payload is decoded, never written.
        if (!Formats.isOutput(newOutputFmt)) {
            setStatusWarn(newOutputFmt + " input cannot be used as output");
            return;
        }

        editors.apply(editors.snapshot().swapped());
        inputCombo.setSelectedItem(newInputFmt);
        outputCombo.setSelectedItem(newOutputFmt);
        setStatus("Swapped input and output", true);
    }

    /** Lets the user pick the .proto file payloads are decoded against. */
    private void chooseProtoSchema() {
        fileOps.chooseProtoSchema(schema -> {
            options.useProtoSchema(schema.name(), schema.text(), schema.messages());
            setStatus(String.format(java.util.Locale.ROOT, "Schema %s: %d message types",
                  schema.name(), schema.messages().size()), true);
        });
    }

    private boolean isValidInputFormat(String format) {
        return Formats.isInput(format);
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
        editors.clear();
        inputCombo.setSelectedItem(FMT_JSON);
        outputCombo.setSelectedItem(FMT_XML);
        options.clearFilter();
        setStatus("Cleared", true);
    }

    private RSyntaxTextArea buildEditor() {
        RSyntaxTextArea area = new RSyntaxTextArea();
        // Ctrl+D (Cmd+D on macOS) deletes the line in RSyntaxTextArea and
        // duplicates it everywhere else in the IDE, whose own action is off
        // outside its editors: the keystroke reached this one and deleted the
        // line the user meant to copy.
        javax.swing.InputMap keys = area.getInputMap();
        for (KeyStroke key : keys.allKeys())
            if (org.fife.ui.rtextarea.RTextAreaEditorKit.rtaDeleteLineAction.equals(keys.get(key)))
                keys.put(key, "none");
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

    /** The IDE's editor font at the size Zoom IDE gives it, falling back outside a full IDE. */
    private static Font editorFont() {
        try {
            var scheme = com.intellij.openapi.editor.colors.EditorColorsManager
                  .getInstance().getGlobalScheme();
            float size = com.intellij.ide.ui.UISettingsUtils.getInstance().getScaledEditorFontSize();
            return new Font(scheme.getEditorFontName(), Font.PLAIN, 1).deriveFont(size);
        } catch (Throwable t) {
            return new Font("JetBrains Mono", Font.PLAIN, 13);
        }
    }

    private void applyEditorTheme(RSyntaxTextArea area) {
        String path = JBColor.isBright()
              ? "/org/fife/ui/rsyntaxtextarea/themes/default.xml"
              : "/org/fife/ui/rsyntaxtextarea/themes/dark.xml";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            // With the editor font as the theme's base: Theme.apply sets the
            // font, and a theme loaded without one put RSyntaxTextArea's own
            // 13 pt default over the IDE's editor font.
            if (is != null) Theme.load(is, editorFont()).apply(area);
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
        titleLabel.setFont(JBFont.small());
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

    private JLabel buildFormatBadge(String text) {
        return ConverterWidgets.formatBadge(text, FORMAT_COLORS);
    }

    private JComboBox<String> buildCombo(String[] items) {
        return ConverterWidgets.combo(items);
    }

    private JButton buildButton(String label, Color bg, Color hover, boolean utilStyle) {
        return ConverterWidgets.button(label, bg, hover, utilStyle);
    }

    private JLabel toolbarLabel(String text) {
        return ConverterWidgets.toolbarLabel(text);
    }

    private JSeparator makeSep() {
        return ConverterWidgets.separator();
    }

    /**
     * Moves the input caret to the position a parse failure points at, so the
     * user lands on the offending character instead of reading a line number out
     * of a message. Silently does nothing when the exception carries no location.
     */
    private void jumpToErrorLocation(Throwable failure) {
        SourcePosition position = SourcePosition.of(failure);
        if (position == null) return;
        try {
            int lineStart = inputArea.getLineStartOffset(
                  Math.min(position.line() - 1, Math.max(0, inputArea.getLineCount() - 1)));
            int offset = position.column() > 0 ? lineStart + position.column() - 1 : lineStart;
            inputArea.setCaretPosition(Math.min(Math.max(offset, 0),
                  inputArea.getDocument().getLength()));
            inputArea.requestFocusInWindow();
        } catch (javax.swing.text.BadLocationException outOfRange) {
            // The reported position does not exist in the current document
            // (input edited since): leave the caret alone.
        }
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
            statusLabel.setToolTipText("<html>" + ConverterNotifications.html(message) + "</html>");
            ConverterNotifications.error(project, "Conversion failed", message);
        }
    }

    private void setStatus(String msg, boolean ok) {
        showStatus(msg, ok ? OK_COLOR : ERR_COLOR);
    }

    private void setStatusWarn(String msg) {
        showStatus(msg, WARN_COLOR);
    }

    /** Sets the status text in the given colour, eliding over-long messages into the tooltip. */
    private void showStatus(String msg, Color color) {
        statusRevision++;
        if (msg.length() > STATUS_MAX_LEN) {
            statusLabel.setToolTipText(msg);
            msg = msg.substring(0, STATUS_MAX_LEN) + "\u2026";
        } else {
            statusLabel.setToolTipText(null);
        }
        statusLabel.setText(msg);
        statusLabel.setForeground(color);
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
