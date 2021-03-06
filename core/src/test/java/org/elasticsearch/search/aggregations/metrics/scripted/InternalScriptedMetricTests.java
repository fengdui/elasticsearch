/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics.scripted;

import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.script.MockScriptEngine;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.aggregations.Aggregation.CommonFields;
import org.elasticsearch.search.aggregations.ParsedAggregation;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.test.InternalAggregationTestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class InternalScriptedMetricTests extends InternalAggregationTestCase<InternalScriptedMetric> {

    private static final String REDUCE_SCRIPT_NAME = "reduceScript";
    private boolean hasReduceScript;
    private Supplier<Object>[] valueTypes;
    private final Supplier<Object>[] leafValueSuppliers = new Supplier[] { () -> randomInt(), () -> randomLong(), () -> randomDouble(),
            () -> randomFloat(), () -> randomBoolean(), () -> randomAlphaOfLength(5), () -> new GeoPoint(randomDouble(), randomDouble()),
            () -> null };
    private final Supplier<Object>[] nestedValueSuppliers = new Supplier[] { () -> new HashMap<String, Object>(),
            () -> new ArrayList<>() };

    @Override
    public void setUp() throws Exception {
        super.setUp();
        hasReduceScript = randomBoolean();
        // we want the same value types (also for nested lists, maps) for all random aggregations
        int levels = randomIntBetween(1, 3);
        valueTypes = new Supplier[levels];
        for (int i = 0; i < levels; i++) {
            if (i < levels - 1) {
                valueTypes[i] = randomFrom(nestedValueSuppliers);
            } else {
                // the last one needs to be a leaf value, not map or list
                valueTypes[i] = randomFrom(leafValueSuppliers);
            }
        }
    }

    @Override
    protected InternalScriptedMetric createTestInstance(String name, List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData) {
        Map<String, Object> params = new HashMap<>();
        if (randomBoolean()) {
            params.put(randomAlphaOfLength(5), randomAlphaOfLength(5));
        }
        Script reduceScript = null;
        if (hasReduceScript) {
            reduceScript = new Script(ScriptType.INLINE, MockScriptEngine.NAME, REDUCE_SCRIPT_NAME, params);
        }
        Object randomValue = randomValue(valueTypes, 0);
        return new InternalScriptedMetric(name, randomValue, reduceScript, pipelineAggregators, metaData);
    }

    @SuppressWarnings("unchecked")
    private static Object randomValue(Supplier<Object>[] valueTypes, int level) {
        Object value = valueTypes[level].get();
        if (value instanceof Map) {
            int elements = randomIntBetween(1, 5);
            Map<String, Object> map = (Map<String, Object>) value;
            for (int i = 0; i < elements; i++) {
                map.put(randomAlphaOfLength(5), randomValue(valueTypes, level + 1));
            }
        } else if (value instanceof List) {
            int elements = randomIntBetween(1,5);
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < elements; i++) {
                list.add(randomValue(valueTypes, level + 1));
            }
        }
        return value;
    }

    @Override
    protected void assertFromXContent(InternalScriptedMetric aggregation, ParsedAggregation parsedAggregation) {
        assertTrue(parsedAggregation instanceof ParsedScriptedMetric);
        ParsedScriptedMetric parsed = (ParsedScriptedMetric) parsedAggregation;

        assertValues(aggregation.aggregation(), parsed.aggregation());
    }

    private static void assertValues(Object expected, Object actual) {
        if (expected instanceof Long) {
            // longs that fit into the integer range are parsed back as integer
            if (actual instanceof Integer) {
                assertEquals(((Long) expected).intValue(), actual);
            } else {
                assertEquals(expected, actual);
            }
        } else if (expected instanceof Float) {
            // based on the xContent type, floats are sometimes parsed back as doubles
            if (actual instanceof Double) {
                assertEquals(expected, ((Double) actual).floatValue());
            } else {
                assertEquals(expected, actual);
            }
        } else if (expected instanceof GeoPoint) {
            assertTrue(actual instanceof Map);
            GeoPoint point = (GeoPoint) expected;
            Map<String, Object> pointMap = (Map<String, Object>) actual;
            assertEquals(point.getLat(), pointMap.get("lat"));
            assertEquals(point.getLon(), pointMap.get("lon"));
        } else if (expected instanceof Map) {
            Map<String, Object> expectedMap = (Map<String, Object>) expected;
            Map<String, Object> actualMap = (Map<String, Object>) actual;
            assertEquals(expectedMap.size(), actualMap.size());
            for (String key : expectedMap.keySet()) {
                assertValues(expectedMap.get(key), actualMap.get(key));
            }
        } else if (expected instanceof List) {
                List<Object> expectedList = (List<Object>) expected;
                List<Object> actualList = (List<Object>) actual;
                assertEquals(expectedList.size(), actualList.size());
                Iterator<Object> actualIterator = actualList.iterator();
                for (Object element : expectedList) {
                    assertValues(element, actualIterator.next());
                }
        } else {
            assertEquals(expected, actual);
        }
    }

    @Override
    protected Predicate<String> excludePathsFromXContentInsertion() {
        return path -> path.contains(CommonFields.VALUE.getPreferredName());
    }
}
