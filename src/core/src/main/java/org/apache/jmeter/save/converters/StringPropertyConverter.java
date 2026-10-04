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

import java.util.Base64;

import org.apache.jmeter.testelement.property.StringProperty;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

public class StringPropertyConverter implements Converter {

    /**
     * Returns the converter version; used to check for possible
     * incompatibilities
     *
     * @return the version of this converter
     */
    public static String getVersion() {
        return "$Revision$"; // $NON-NLS-1$
    }

    /** {@inheritDoc} */
    @Override
    public boolean canConvert(@SuppressWarnings("rawtypes") Class arg0) { // superclass does not use types
        return StringProperty.class.equals(arg0);
    }

    /** {@inheritDoc} */
    @Override
    public void marshal(Object obj, HierarchicalStreamWriter writer, MarshallingContext arg2) {
        StringProperty prop = (StringProperty) obj;
        writer.addAttribute(ConversionHelp.ATT_NAME, ConversionHelp.encode(prop.getName()));
        String encoded = ConversionHelp.encode(prop.getStringValue());
        if (encoded != null && !encoded.isEmpty()) {
            if (encoded.codePoints().anyMatch(c -> !(c == 9 || c == 10 || c == 13
                    || c >= 0x20 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD
                    || c >= 0x10000 && c <= 0x10FFFF))) {
                // XML 1.0 cannot represent these characters, even as numeric entities.
                // Store UTF-16 code units verbatim to preserve every Java String value.
                writer.addAttribute("encoding", "base64-utf16be");
                byte[] bytes = new byte[encoded.length() * 2];
                for (int i = 0; i < encoded.length(); i++) {
                    bytes[2 * i] = (byte) (encoded.charAt(i) >>> 8);
                    bytes[2 * i + 1] = (byte) encoded.charAt(i);
                }
                writer.setValue(Base64.getEncoder().encodeToString(bytes));
            } else {
                writer.setValue(encoded);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        final String name = ConversionHelp.getPropertyName(reader, context);
        if (name == null) {
            return null;
        }
        final String value;
        if ("base64-utf16be".equals(reader.getAttribute("encoding"))) {
            byte[] bytes = Base64.getDecoder().decode(reader.getValue());
            if (bytes.length % 2 != 0) {
                throw new IllegalArgumentException("Invalid UTF-16 string property length");
            }
            char[] chars = new char[bytes.length / 2];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = (char) ((bytes[2 * i] & 255) << 8 | bytes[2 * i + 1] & 255);
            }
            value = ConversionHelp.getUpgradePropertyValue(name, ConversionHelp.decode(new String(chars)), context);
        } else {
            value = ConversionHelp.getPropertyValue(reader, context, name);
        }
        StringProperty prop = new StringProperty(name, value);
        return prop;
    }
}
