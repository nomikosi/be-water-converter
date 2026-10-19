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

import java.util.List;

/**
 * Documents the generated sources are compiled for and then made to read and
 * write back unchanged, in each target language. Each is aimed at something a
 * generator decides: names, reserved words, collisions, optional and null
 * values, numbers past 64 bits, and keys no identifier can be.
 */
final class GeneratorSamples {

    private GeneratorSamples() {}

    static final String NAMES_AND_SHAPES = """
          {"id":1,"first-name":"Ada","userID":7,"imageURLs":["u"],"class":"x","type":"t",
           "nested":{"ratio":1.5,"flag":true,"when":"2024-05-01"},"matrix":[[1,2],[3]],
           "meta":{},"unknown":null,"mixed":[1,"two",true,null,{"k":1}],"empty":[]}""";

    /** Keys some elements lack, numbers that widen, and null elements. */
    static final String OPTIONAL_AND_NULL = """
          {"items":[{"a":1,"b":"x","c":[1.5]},{"a":2,"c":[2]}],"xs":[1,null,3],
           "objs":[{"v":1},null],"rows":[[{"v":1}],[{"v":2,"w":"x"}]]}""";

    /** Keys that sanitise to one name, and words some language reserves. */
    static final String COLLISIONS_AND_RESERVED = """
          {"a_b":1,"aB":2,"a-b":3,"A B":4,"default":5,"self":6,"for":7,"hashCode":8,"toJson":9,
           "model_dump":10,"model_x":11,"json":12,"list":13,"str":"s","Value":14,"value":15,"fn":16}""";

    static final String NUMBERS = """
          {"big":12345678901234567890,"long":9007199254740993,"neg":-42,"zero":0,"dbl":0.1,
           "exp":1e21,"precise":3.14159265358979323846264338327950288,"whole":[1,2.5]}""";

    /** Keys that are no identifier in any language, but every one of them a Go tag can hold. */
    static final String KEYS_GO_CAN_TAG = """
          {"-":2,"a b":3,"$ref":"#/x","名前":"x","é":"ü","1st":true,"x.y":1,"line":"a\\tb\\nc"}""";

    /** Keys a Go struct tag cannot name: empty, a quote, a backslash, a comma, an emoji. */
    static final String KEYS_GO_CANNOT_TAG = """
          {"":1,"quote\\"d":1,"back\\\\slash":2,"a,b":3,"emoji😀":"😀"}""";

    static final String DATES = """
          {"day":"2024-05-01","at":"2024-05-01T10:00:00Z","local":"2024-05-01T10:00:00",
           "offset":"2024-05-01T10:00:00+02:00","notADay":"2024-13-45"}""";

    static final String EMPTY = "{}";

    /** Every sample but the one Go has no struct for. */
    static final List<String> FOR_EVERY_LANGUAGE = List.of(NAMES_AND_SHAPES, OPTIONAL_AND_NULL,
          COLLISIONS_AND_RESERVED, NUMBERS, KEYS_GO_CAN_TAG, DATES, EMPTY);

    static final List<String> ALL = List.of(NAMES_AND_SHAPES, OPTIONAL_AND_NULL, COLLISIONS_AND_RESERVED,
          NUMBERS, KEYS_GO_CAN_TAG, KEYS_GO_CANNOT_TAG, DATES, EMPTY);
}
