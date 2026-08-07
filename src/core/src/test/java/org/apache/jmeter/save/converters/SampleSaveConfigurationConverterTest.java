/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.save.converters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.core.JVM;
import com.thoughtworks.xstream.mapper.Mapper;

class SampleSaveConfigurationConverterTest {

    @Test
    void usesReflectionProviderThatDoesNotDependOnUnsafe() {
        assertInstanceOf(PureJavaReflectionProvider.class, JVM.newReflectionProvider());

        TestableSampleSaveConfigurationConverter converter =
                new TestableSampleSaveConfigurationConverter(JMeterUtils.createXStream().getMapper());

        assertInstanceOf(PureJavaReflectionProvider.class, converter.reflectionProvider());
    }

    @Test
    void restoresSerializedAssertionResultSettingWithoutFinalFieldMutation() {
        XStream xStream = JMeterUtils.createXStream();
        xStream.registerConverter(new SampleSaveConfigurationConverter(xStream.getMapper()),
                XStream.PRIORITY_VERY_HIGH);
        SampleSaveConfiguration original = new SampleSaveConfiguration(false);

        String xml = xStream.toXML(original);
        SampleSaveConfiguration restored = (SampleSaveConfiguration) xStream.fromXML(xml);

        assertTrue(xml.contains("<assertionsResultsToSave>"));
        assertEquals(original, restored);
    }

    private static final class TestableSampleSaveConfigurationConverter
            extends SampleSaveConfigurationConverter {
        private TestableSampleSaveConfigurationConverter(Mapper mapper) {
            super(mapper);
        }

        private ReflectionProvider reflectionProvider() {
            return reflectionProvider;
        }
    }
}
