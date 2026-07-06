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

package org.apache.jmeter.gui.settings;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * The kind of value a JMeter property holds. It determines which editor
 * component the Settings dialog shows for the property.
 */
public enum SettingType {
    /** true/false value, shown as a checkbox */
    BOOLEAN,
    /** whole number, shown as a validated text field */
    INTEGER,
    /** floating point number, shown as a validated text field */
    DECIMAL,
    /** free-form text, shown as a text field */
    TEXT,
    /** one value out of a fixed list, shown as a drop-down */
    ENUM,
    /** delimiter-separated list of values, shown as a text field */
    LIST,
    /** path to a file, shown as a text field with a Browse button */
    FILE,
    /** path to a directory, shown as a text field with a Browse button */
    DIRECTORY;

    @JsonCreator
    public static SettingType fromJson(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
