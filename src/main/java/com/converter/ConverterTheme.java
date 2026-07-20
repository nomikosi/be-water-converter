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

import java.awt.Color;

/**
 * Theme-aware color palette for the converter UI. Each JBColor pairs a
 * light-theme value with a dark-theme value.
 */
final class ConverterTheme {

    private ConverterTheme() {}

    static final Color BG_DARK      = new JBColor(new Color(245, 245, 245), new Color(43,  43,  43));
    static final Color BG_TOOLBAR   = new JBColor(new Color(235, 237, 240), new Color(55,  58,  60));
    static final Color BG_LABEL_BAR = new JBColor(new Color(240, 240, 242), new Color(37,  37,  38));
    static final Color BG_STATUS    = new JBColor(new Color(232, 232, 232), new Color(30,  30,  30));
    static final Color ACCENT       = new JBColor(new Color(55, 100, 180),  new Color(75, 110, 175));
    static final Color ACCENT_HOVER = new JBColor(new Color(70, 120, 210),  new Color(95, 135, 205));
    static final Color UTIL_BG      = new JBColor(new Color(212, 214, 216), new Color(70,  73,  75));
    static final Color UTIL_HOVER   = new JBColor(new Color(195, 198, 200), new Color(90,  93,  95));
    static final Color FORMAT_BG    = new JBColor(new Color(46, 139,  87),  new Color(50, 100,  60));
    static final Color FORMAT_HOVER = new JBColor(new Color(56, 160, 100),  new Color(65, 125,  75));
    static final Color TEXT_BRIGHT  = new JBColor(new Color(40,  40,  40),  new Color(220, 220, 220));
    static final Color TEXT_DIM     = new JBColor(new Color(120, 120, 120), new Color(130, 130, 130));
    static final Color OK_COLOR     = new JBColor(new Color(40, 130,  40),  new Color(98,  151,  85));
    static final Color WARN_COLOR   = new JBColor(new Color(180, 130,  0),  new Color(222, 166,  62));
    static final Color ERR_COLOR    = new JBColor(new Color(200, 50,  50),  new Color(204,  60,  53));
    static final Color BORDER       = new JBColor(new Color(210, 210, 210), new Color(25,  25,  25));
    static final Color DROPDOWN_BG  = new JBColor(new Color(255, 255, 255), new Color(60,  63,  65));
    static final Color BTN_TEXT     = new JBColor(new Color(255, 255, 255), new Color(220, 220, 220));
    static final Color UTIL_TEXT    = new JBColor(new Color(50,  50,  50),  new Color(220, 220, 220));
    static final Color EDITOR_BG    = new JBColor(new Color(255, 255, 255), new Color(30,  31,  34));
    static final Color GUTTER_BG    = new JBColor(new Color(245, 245, 245), new Color(43,  43,  43));
    static final Color GUTTER_FG    = new JBColor(new Color(170, 170, 170), new Color(90,  90,  90));
    static final Color DIVIDER_BG   = new JBColor(new Color(220, 220, 220), new Color(50,  50,  50));
    static final Color DIVIDER_GRIP = new JBColor(new Color(170, 170, 170), new Color(90,  90,  90));
    static final Color SELECTION_BG = new JBColor(new Color(173, 214, 255), new Color(33,  66, 131));
}
