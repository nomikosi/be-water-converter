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

package com.converter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ArrayShapesStressTest {
    @TempDir Path temp;

    @Test void mergesSparseObjectsWithinABoundedHeap() throws Exception {
        TestProcesses.run(temp, TestProcesses.javaExecutable(), List.of("-Xmx96m", "-cp",
              TestProcesses.configured("bewater.test.classpath"), ArrayShapeMemoryProbe.class.getName()));
    }

    @Test void nestedMergesKeepPresenceNullabilityAndSourceIdentity() throws Exception {
        var tree = PivotJson.mapper().readTree("[{\"rows\":[{\"a\":1},null],\"always\":1},{\"rows\":[{\"b\":2}],\"always\":2},{\"rows\":null,\"always\":3}]");
        String original = tree.toString();
        var shapes = new ArrayShapes();
        var merged = shapes.elementOf(tree);
        assertThat(shapes.isOptional(merged, "rows")).isTrue();
        assertThat(shapes.isOptional(merged, "always")).isFalse();
        var rows = merged.path("rows");
        assertThat(shapes.hasNullElement(rows)).isTrue();
        var item = shapes.elementOf(rows);
        assertThat(item.size()).isEqualTo(2);
        assertThat(shapes.isOptional(item, "a")).isTrue();
        assertThat(shapes.isOptional(item, "b")).isTrue();
        assertThat(shapes.elementOf(tree)).isSameAs(merged);
        assertThat(tree.toString()).isEqualTo(original);
        var single = PivotJson.mapper().readTree("[{\"a\":1}]");
        assertThat(shapes.elementOf(single)).isSameAs(single.get(0));
    }
}
