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

import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.Topic;

/**
 * Passes a change of the IDE's UI settings — zoom, presentation mode, the UI
 * font — on to every open converter panel.
 *
 * <p>Declared in plugin.xml rather than subscribed to by each panel:
 * {@code UISettingsListener.TOPIC} is internal API in 2024.2, the oldest IDE
 * the plugin supports, and a declared listener names only the interface.
 */
public final class ConverterUiSettingsListener implements UISettingsListener {

    /** Published once for each change; each panel refreshes its appearance. */
    static final Topic<Runnable> CHANGED = new Topic<>("Be Water UI settings changed", Runnable.class);

    @Override
    public void uiSettingsChanged(UISettings settings) {
        ApplicationManager.getApplication().getMessageBus().syncPublisher(CHANGED).run();
    }
}
