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

package com.converter.converter;

/** Runs under a small heap so an intermediate-shape leak fails deterministically. */
public final class ArrayShapeMemoryProbe {
    public static void main(String[] args) {
        var array = PivotJson.mapper().createArrayNode();
        for (int i = 0; i < 5_000; i++) array.addObject().put("field" + i, i);
        ArrayShapes shapes = new ArrayShapes();
        var result = shapes.elementOf(array);
        if (result.size() != 5_000 || result.path("field4999").intValue() != 4_999)
            throw new AssertionError("Sparse keys were lost");
        for (int i = 0; i < 5_000; i++)
            if (!shapes.isOptional(result, "field" + i)) throw new AssertionError("Optionality was lost");
        if (array.get(0).size() != 1) throw new AssertionError("Input was mutated");
        System.out.println("Merged 5,000 sparse objects under a 96 MiB heap");
    }
}
