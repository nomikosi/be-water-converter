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

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A FlowLayout that wraps components onto new rows AND reports the resulting
 * height in its preferred size, so parent containers (e.g., BorderLayout.NORTH)
 * grow instead of clipping the wrapped rows.
 * Based on the well-known WrapLayout by Rob Camick
 * (<a href="https://tips4java.wordpress.com/2008/11/06/wrap-layout/">...</a>).
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    @Override
    public void layoutContainer(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int maxWidth = target.getWidth() - insets.left - insets.right - getHgap() * 2;

            int x = insets.left + getHgap();
            int y = insets.top  + getVgap();
            int rowHeight = 0;

            int memberCount = target.getComponentCount();
            for (int i = 0; i < memberCount; i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;

                Dimension d = m.getPreferredSize();

                if (x > insets.left + getHgap() && x + d.width > maxWidth + insets.left + getHgap()) {
                    x = insets.left + getHgap();
                    y += rowHeight + getVgap();
                    rowHeight = 0;
                }

                m.setBounds(x, y, d.width, d.height);
                x += d.width + getHgap();
                rowHeight = Math.max(rowHeight, d.height);
            }
        }
    }

    /**
     * Computes the size of the target laying out the components in rows that
     * fit within the target's current width.
     */
    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            Container container = target;
            while (container.getSize().width == 0 && container.getParent() != null) {
                container = container.getParent();
            }
            targetWidth = container.getSize().width;
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
            int maxWidth = targetWidth - horizontalInsetsAndGap;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            int memberCount = target.getComponentCount();
            for (int i = 0; i < memberCount; i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;

                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();

                if (rowWidth + d.width > maxWidth) {
                    addRow(dim, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += hgap;
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(dim, rowWidth, rowHeight);

            dim.width += horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + vgap * 2;

            Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                dim.width -= (hgap + 1);
            }
            return dim;
        }
    }

    private void addRow(Dimension dim, int rowWidth, int rowHeight) {
        dim.width = Math.max(dim.width, rowWidth);
        if (dim.height > 0) {
            dim.height += getVgap();
        }
        dim.height += rowHeight;
    }
}
