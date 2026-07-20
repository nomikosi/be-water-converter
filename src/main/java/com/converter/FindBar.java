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

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

import static com.converter.ConverterTheme.*;

/**
 * Hidden-by-default search bar targeting the last-focused editor.
 * Enter finds the next match, Shift+Enter the previous, Esc closes.
 */
final class FindBar extends JPanel {

    /** Where find results are reported. */
    interface StatusSink {
        void ok(String message);
        void warn(String message);
    }

    private final JTextField field;
    private final Supplier<RSyntaxTextArea> target;
    private final StatusSink status;

    FindBar(Supplier<RSyntaxTextArea> target, StatusSink status) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        this.target = target;
        this.status = status;

        field = new JTextField(24);
        field.setFont(new Font("SansSerif", Font.PLAIN, 13));
        field.setBackground(DROPDOWN_BG);
        field.setForeground(TEXT_BRIGHT);
        field.setCaretColor(TEXT_BRIGHT);
        field.setBorder(BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(BORDER, 1), new EmptyBorder(3, 6, 3, 6)));

        field.addActionListener(e -> find(true));
        field.getInputMap().put(
              KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "findPrev");
        field.getActionMap().put("findPrev", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { find(false); }
        });
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        field.getActionMap().put("closeFind", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { close(); }
        });

        JButton prevBtn = ConverterPanel.iconButton(
              com.intellij.icons.AllIcons.Actions.PreviousOccurence, "Previous match (Shift+Enter)");
        prevBtn.addActionListener(e -> find(false));
        JButton nextBtn = ConverterPanel.iconButton(
              com.intellij.icons.AllIcons.Actions.NextOccurence, "Next match (Enter)");
        nextBtn.addActionListener(e -> find(true));
        JButton closeBtn = ConverterPanel.iconButton(
              com.intellij.icons.AllIcons.Actions.Close, "Close find bar (Esc)");
        closeBtn.addActionListener(e -> close());

        setBackground(BG_TOOLBAR);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
        JLabel label = new JLabel("Find:");
        label.setForeground(TEXT_DIM);
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        add(label);
        add(field);
        add(prevBtn);
        add(nextBtn);
        add(closeBtn);
        setVisible(false);
    }

    void open() {
        setVisible(true);
        revalidate();
        field.requestFocusInWindow();
        field.selectAll();
    }

    void close() {
        setVisible(false);
        revalidate();
        RSyntaxTextArea area = target.get();
        if (area != null) area.requestFocusInWindow();
    }

    /** Searches the target editor, wrapping around at the ends. */
    private void find(boolean forward) {
        String query = field.getText();
        RSyntaxTextArea area = target.get();
        if (query.isEmpty() || area == null) return;
        SearchContext ctx = new SearchContext(query);
        ctx.setSearchForward(forward);
        ctx.setMatchCase(false);
        ctx.setSearchWrap(true);
        if (SearchEngine.find(area, ctx).wasFound()) {
            status.ok("Found \"" + query + "\"");
        } else {
            status.warn("No matches for \"" + query + "\"");
        }
    }
}
