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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classes plugin.xml names. Nothing else in CI loads them: the structure
 * check reads the descriptor without resolving a class, and only the verifier,
 * which ran on release tags alone, did. Renaming an action class kept the
 * build green and broke the plugin.
 */
@DisplayName("Plugin descriptor")
class PluginDescriptorTest {

    @Test @DisplayName("every class plugin.xml names exists, and actions can be created as the IDE creates them")
    void classesResolve() throws Exception {
        Map<String, String> named = new LinkedHashMap<>();    // class name -> element naming it
        try (InputStream in = getClass().getResourceAsStream("/META-INF/plugin.xml")) {
            assertThat(in).as("plugin.xml on the classpath").isNotNull();
            NodeList elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in)
                  .getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                NamedNodeMap attributes = element.getAttributes();
                for (int j = 0; j < attributes.getLength(); j++) {
                    String value = attributes.item(j).getNodeValue();
                    if (value.startsWith("com.converter.")) named.put(value, element.getTagName());
                }
            }
        }
        assertThat(named).as("classes named in plugin.xml").isNotEmpty();
        for (Map.Entry<String, String> entry : named.entrySet()) {
            Class<?> type = Class.forName(entry.getKey(), false, getClass().getClassLoader());
            if (entry.getValue().equals("action") || entry.getValue().equals("group")) {
                // The IDE instantiates these reflectively, through a public no-argument constructor.
                assertThat(Modifier.isPublic(type.getModifiers())).as(entry.getKey() + " is public").isTrue();
                assertThat(type.getConstructor()).as(entry.getKey() + "()").isNotNull();
            }
        }
    }
}
