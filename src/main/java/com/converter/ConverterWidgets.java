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

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;

import static com.converter.ConverterTheme.*;

/**
 * Stateless factories for the converter's custom-painted Swing controls.
 * Kept apart from {@link ConverterPanel} so the panel holds behaviour rather
 * than widget construction; nothing here touches panel state.
 */
final class ConverterWidgets {

    private ConverterWidgets() {}

    private static final int BUTTON_ARC = 8;

    /** Flat icon button with a hover highlight. */
    static JButton iconButton(Icon icon, String tooltip) {
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
            @Override public void mouseEntered(MouseEvent e) {
                btn.setContentAreaFilled(true);
                btn.setBackground(UTIL_HOVER);
                btn.setOpaque(true);
            }
            @Override public void mouseExited(MouseEvent e) {
                btn.setContentAreaFilled(false);
                btn.setOpaque(false);
            }
        });
        return btn;
    }

    /** Rounded-corner button with hover effect. {@code utilStyle} gives theme-aware dark/light text. */
    static JButton button(String label, Color bg, Color hover, boolean utilStyle) {
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
            @Override public void mouseEntered(MouseEvent e) { if (btn.isEnabled()) btn.setBackground(hover); }
            @Override public void mouseExited(MouseEvent e)  { if (btn.isEnabled()) btn.setBackground(bg);    }
        });
        return btn;
    }

    /** Combo box styled to match the toolbar, with a theme-aware cell renderer. */
    static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> combo = new JComboBox<>(items);
        styleCombo(combo);
        return combo;
    }

    /** Applies the toolbar combo styling to an existing combo box. */
    static void styleCombo(JComboBox<?> combo) {
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
    }

    /**
     * Color-coded pill badge showing the current format name. The pill colour is
     * resolved from {@code colors} at paint time, so changing the label text is
     * enough to recolour it.
     */
    static JLabel formatBadge(String text, Map<String, Color> colors) {
        JLabel lbl = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                      RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(colors.getOrDefault(getText(), ACCENT));
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

    /** Dimmed caption used for toolbar and options-bar labels. */
    static JLabel toolbarLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(TEXT_DIM);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return lbl;
    }

    /** Vertical rule separating toolbar groups. */
    static JSeparator separator() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 24));
        sep.setForeground(new JBColor(new Color(200, 200, 200), new Color(80, 80, 80)));
        return sep;
    }
}
