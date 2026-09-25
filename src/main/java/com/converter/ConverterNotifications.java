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

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

/**
 * The plugin's error balloons, and how a failure is put into words. The tool
 * window and the context menu each had their own copy, and only one of them
 * escaped the message.
 */
final class ConverterNotifications {

    private static final Logger LOG = Logger.getInstance(ConverterNotifications.class);

    /** Registered in plugin.xml. */
    static final String GROUP_ID = "Be Water Converter";

    private ConverterNotifications() {}

    /** Shows {@code message} as an error balloon. Does nothing outside a running IDE. */
    static void error(Project project, String title, String message) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
                  .createNotification(title, html(message), NotificationType.ERROR)
                  .notify(project);
        } catch (Throwable outsideIde) {
            LOG.warn("Could not show error notification", outsideIde);
        }
    }

    /**
     * Notification content is HTML. A parse error quoting its input —
     * "Unexpected close tag &lt;/items&gt;" — lost the tag names to the renderer,
     * and a multi-line Protobuf error ran together on one line.
     */
    static String html(String text) {
        return escape(text).replace("\n", "<br>");
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * A failure's message, or its class name when it carries none. "null" and
     * "Unknown error" told the user nothing about what had actually gone wrong.
     */
    static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
