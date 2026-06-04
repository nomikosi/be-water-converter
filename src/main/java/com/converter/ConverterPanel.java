
package com.converter;

import com.converter.converter.*;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class ConverterPanel {

    private static final Color BG_DARK      = new Color(43,  43,  43);
    private static final Color BG_TOOLBAR   = new Color(55,  58,  60);
    private static final Color BG_LABEL_BAR = new Color(37,  37,  38);
    private static final Color BG_STATUS    = new Color(30,  30,  30);
    private static final Color ACCENT       = new Color(75, 110, 175);
    private static final Color ACCENT_HOVER = new Color(95, 135, 205);
    private static final Color UTIL_BG      = new Color(70,  73,  75);
    private static final Color UTIL_HOVER   = new Color(90,  93,  95);
    private static final Color FORMAT_BG    = new Color(50, 100,  60);
    private static final Color FORMAT_HOVER = new Color(65, 125,  75);
    private static final Color TEXT_BRIGHT  = new Color(220, 220, 220);
    private static final Color TEXT_DIM     = new Color(130, 130, 130);
    private static final Color OK_COLOR     = new Color(98,  151,  85);
    private static final Color ERR_COLOR    = new Color(204,  60,  53);
    private static final Color BORDER       = new Color(25,  25,  25);
    private static final Color DROPDOWN_BG  = new Color(60,  63,  65);

    static final String FMT_JSON  = "JSON";
    static final String FMT_XML   = "XML";
    static final String FMT_YAML  = "YAML";
    static final String FMT_CSV   = "CSV";
    static final String FMT_TOML  = "TOML";
    static final String FMT_PROTO = "Protobuf";
    static final String FMT_JAVA  = "Java POJO";

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

    private final JPanel            mainPanel;
    private final RSyntaxTextArea   inputArea;
    private final RSyntaxTextArea   outputArea;
    private final JLabel            statusLabel;
    private final JLabel            inputFormatLabel;
    private final JLabel            outputFormatLabel;
    private final JComboBox<String> inputCombo;
    private final JComboBox<String> outputCombo;

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
        applyDarkTheme(inputArea);
        applyDarkTheme(outputArea);

        inputFormatLabel  = buildFormatBadge(FMT_JSON);
        outputFormatLabel = buildFormatBadge(FMT_XML);

        inputCombo  = buildCombo(ALL_INPUTS);
        outputCombo = buildCombo(VALID_OUTPUTS.get(FMT_JSON));
        outputCombo.setSelectedItem(FMT_XML);

        inputCombo.addActionListener(e -> {
            String fmt = (String) inputCombo.getSelectedItem();
            if (fmt == null) return;
            inputArea.setSyntaxEditingStyle(syntaxFor(fmt));
            inputFormatLabel.setText(fmt);
            rebuildOutputCombo(fmt);
        });

        JPanel toolbar    = buildToolbar();
        JPanel inputWrap  = wrapEditor(inputArea,  inputFormatLabel);
        JPanel outputWrap = wrapEditor(outputArea, outputFormatLabel);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputWrap, outputWrap);
        split.setResizeWeight(0.5);
        split.setDividerSize(4);
        split.setBorder(null);
        split.setBackground(BG_DARK);

        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(TEXT_DIM);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusLabel.setBorder(new EmptyBorder(4, 10, 4, 10));

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(BG_STATUS);
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
        statusBar.add(statusLabel, BorderLayout.WEST);

        mainPanel.add(toolbar,   BorderLayout.NORTH);
        mainPanel.add(split,     BorderLayout.CENTER);
        mainPanel.add(statusBar, BorderLayout.SOUTH);
    }

    // ── Toolbar ───────────────────────────────────────────────────────────
    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 7));
        bar.setBackground(BG_TOOLBAR);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));

        bar.add(toolbarLabel("From:"));
        bar.add(inputCombo);

        JLabel arrow = new JLabel("to");
        arrow.setForeground(TEXT_DIM);
        arrow.setFont(new Font("SansSerif", Font.BOLD, 13));
        bar.add(arrow);

        bar.add(toolbarLabel("To:"));
        bar.add(outputCombo);

        JButton convertBtn = buildButton("Convert", ACCENT, ACCENT_HOVER);
        convertBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        convertBtn.addActionListener(e -> doConvert());
        bar.add(convertBtn);

        bar.add(makeSep());

        JButton formatBtn = buildButton("Format", FORMAT_BG, FORMAT_HOVER);
        formatBtn.setToolTipText("Pretty-print / format the input");
        formatBtn.addActionListener(e -> doFormat());
        bar.add(formatBtn);

        bar.add(makeSep());

        JButton swapBtn  = buildButton("Swap",  UTIL_BG, UTIL_HOVER);
        JButton copyBtn  = buildButton("Copy",  UTIL_BG, UTIL_HOVER);
        JButton clearBtn = buildButton("Clear", UTIL_BG, UTIL_HOVER);
        swapBtn.addActionListener(e  -> doSwap());
        copyBtn.addActionListener(e  -> doCopy());
        clearBtn.addActionListener(e -> doClear());
        bar.add(swapBtn);
        bar.add(copyBtn);
        bar.add(clearBtn);
        return bar;
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
    }

    // ── Convert ───────────────────────────────────────────────────────────
    private void doConvert() {
        String input  = inputArea.getText().trim();
        String inFmt  = (String) inputCombo.getSelectedItem();
        String outFmt = (String) outputCombo.getSelectedItem();

        if (input.isEmpty())  { setStatus("Input is empty", false); return; }
        if (inFmt == null || outFmt == null) return;

        try {
            String result = dispatch(input, inFmt, outFmt);
            outputArea.setSyntaxEditingStyle(syntaxFor(outFmt));
            outputArea.setText(result);
            outputArea.setCaretPosition(0);
            inputFormatLabel.setText(inFmt);
            outputFormatLabel.setText(outFmt);
            setStatus("\u2713  " + inFmt + " to " + outFmt + " done", true);
        } catch (Exception ex) {
            outputArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
            outputArea.setText("Error: " + ex.getMessage());
            setStatus("\u2717  Failed: " + ex.getMessage(), false);
        }
    }

    /**
     * Central dispatcher.
     *
     * KEY DESIGN: autoClose is applied to the RAW INPUT here, ONCE,
     * before it is handed to any converter. This means every converter
     * always receives structurally complete text regardless of format.
     * Converters themselves no longer need to call autoClose.
     */
    private String dispatch(String rawInput, String inFmt, String outFmt) throws Exception {
        // ── Step 0: repair truncated input (works for JSON; harmless for others) ──
        String input = (inFmt.equals(FMT_JSON)) ? autoClose(rawInput) : rawInput;

        // ── Step 1: normalise everything to JSON ──────────────────────────
        String asJson = switch (inFmt) {
            case FMT_JSON  -> input;
            case FMT_XML   -> jsonXml.xmlToJson(input);
            case FMT_YAML  -> jsonYaml.yamlToJson(input);
            case FMT_CSV   -> csv.csvToJson(input);
            case FMT_TOML  -> toml.tomlToJson(input);
            case FMT_PROTO -> proto.protoToJson(input);
            default -> throw new UnsupportedOperationException("Unknown input: " + inFmt);
        };

        // ── Step 2: JSON → desired output ─────────────────────────────────
        return switch (outFmt) {
            case FMT_JSON  -> prettyJson(asJson);
            case FMT_XML   -> jsonXml.jsonToXml(asJson);
            case FMT_YAML  -> jsonYaml.jsonToYaml(asJson);
            case FMT_CSV   -> csv.jsonToCsv(asJson);
            case FMT_TOML  -> toml.jsonToToml(asJson);
            case FMT_PROTO -> proto.jsonToProto(asJson);
            case FMT_JAVA  -> pojo.fromJson(asJson);
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
                case FMT_XML  -> {
                    com.fasterxml.jackson.dataformat.xml.XmlMapper xm =
                          new com.fasterxml.jackson.dataformat.xml.XmlMapper();
                    xm.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
                    yield xm.writer().withRootName("root")
                          .writeValueAsString(xm.readTree(input.getBytes()));
                }
                case FMT_YAML -> jsonYaml.jsonToYaml(jsonYaml.yamlToJson(input));
                case FMT_TOML -> toml.jsonToToml(toml.tomlToJson(input));
                default       -> input;
            };
            inputArea.setText(formatted);
            inputArea.setCaretPosition(0);
            setStatus("\u2713  Input formatted", true);
        } catch (Exception ex) {
            setStatus("\u2717  Format failed: " + ex.getMessage(), false);
        }
    }

    // ── Utility actions ───────────────────────────────────────────────────
    private void doSwap() {
        String tmpText   = inputArea.getText();
        String tmpSyntax = inputArea.getSyntaxEditingStyle();
        String tmpLabel  = inputFormatLabel.getText();

        inputArea.setSyntaxEditingStyle(outputArea.getSyntaxEditingStyle());
        inputArea.setText(outputArea.getText());
        outputArea.setSyntaxEditingStyle(tmpSyntax);
        outputArea.setText(tmpText);

        inputFormatLabel.setText(outputFormatLabel.getText());
        outputFormatLabel.setText(tmpLabel);

        String newIn = inputFormatLabel.getText();
        for (int i = 0; i < inputCombo.getItemCount(); i++) {
            if (inputCombo.getItemAt(i).equals(newIn)) {
                inputCombo.setSelectedItem(newIn);
                break;
            }
        }
        setStatus("Swapped input and output", true);
    }

    private void doCopy() {
        String text = outputArea.getText();
        if (!text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                  .setContents(new StringSelection(text), null);
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
        inputCombo.setSelectedItem(FMT_JSON);
        rebuildOutputCombo(FMT_JSON);
        outputCombo.setSelectedItem(FMT_XML);
        setStatus("Cleared", true);
    }

    // ── autoClose — lives ONLY here now ──────────────────────────────────
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
        com.fasterxml.jackson.databind.ObjectMapper m =
              new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        return m.writeValueAsString(m.readTree(json));
    }

    private RSyntaxTextArea buildEditor() {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setFont(new Font("JetBrains Mono", Font.PLAIN, 13));
        area.setTabSize(2);
        area.setBackground(new Color(30, 31, 34));
        area.setCaretColor(new Color(220, 220, 220));
        area.setSelectionColor(new Color(33, 66, 131));
        return area;
    }

    private void applyDarkTheme(RSyntaxTextArea area) {
        try {
            InputStream is = getClass().getResourceAsStream(
                  "/org/fife/ui/rsyntaxtextarea/themes/dark.xml");
            if (is != null) Theme.load(is).apply(area);
        } catch (IOException ignored) {}
    }

    private JPanel wrapEditor(RSyntaxTextArea area, JLabel badge) {
        RTextScrollPane scroll = new RTextScrollPane(area);
        scroll.setLineNumbersEnabled(true);
        scroll.setBorder(null);
        scroll.getGutter().setBackground(new Color(43, 43, 43));
        scroll.getGutter().setLineNumberColor(new Color(90, 90, 90));

        JPanel labelBar = new JPanel(new BorderLayout());
        labelBar.setBackground(BG_LABEL_BAR);
        labelBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        labelBar.add(badge, BorderLayout.WEST);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG_DARK);
        wrapper.add(labelBar, BorderLayout.NORTH);
        wrapper.add(scroll,   BorderLayout.CENTER);
        return wrapper;
    }

    private JLabel buildFormatBadge(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(TEXT_DIM);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 11));
        lbl.setBorder(new EmptyBorder(4, 10, 4, 10));
        return lbl;
    }

    private JComboBox<String> buildCombo(String[] items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setBackground(DROPDOWN_BG);
        combo.setForeground(TEXT_BRIGHT);
        combo.setFont(new Font("SansSerif", Font.PLAIN, 13));
        combo.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        combo.setFocusable(false);
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

    private JButton buildButton(String label, Color bg, Color hover) {
        JButton btn = new JButton(label);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setForeground(TEXT_BRIGHT);
        btn.setBackground(bg);
        btn.setOpaque(true);
        btn.setBorderPainted(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(BORDER, 1),
              new EmptyBorder(4, 10, 4, 10)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) { btn.setBackground(hover); }
            public void mouseExited(java.awt.event.MouseEvent e)  { btn.setBackground(bg);    }
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
        sep.setForeground(new Color(80, 80, 80));
        return sep;
    }

    private String syntaxFor(String fmt) {
        if (fmt == null) return SyntaxConstants.SYNTAX_STYLE_NONE;
        return switch (fmt) {
            case FMT_JSON  -> SyntaxConstants.SYNTAX_STYLE_JSON;
            case FMT_XML   -> SyntaxConstants.SYNTAX_STYLE_XML;
            case FMT_YAML  -> SyntaxConstants.SYNTAX_STYLE_YAML;
            case FMT_JAVA  -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            default        -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }

    private void setStatus(String msg, boolean ok) {
        statusLabel.setText(msg);
        statusLabel.setForeground(ok ? OK_COLOR : ERR_COLOR);
    }

    public JPanel getContent() { return mainPanel; }
}
