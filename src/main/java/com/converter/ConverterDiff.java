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

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;

/**
 * Shows two documents in the IDE's own diff viewer. Both sides are canonical
 * JSON — normalised and key-sorted — so a YAML file and a JSON file carrying the
 * same data compare as identical, and only real differences show up.
 */
final class ConverterDiff {

    private ConverterDiff() {}

    static void show(Project project, String title,
          String leftTitle, String leftJson,
          String rightTitle, String rightJson) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        var jsonType = FileTypeManager.getInstance().getFileTypeByExtension("json");
        DiffContent left  = factory.create(project, leftJson,  jsonType);
        DiffContent right = factory.create(project, rightJson, jsonType);
        DiffManager.getInstance().showDiff(project,
              new SimpleDiffRequest(title, left, right, leftTitle, rightTitle));
    }
}
