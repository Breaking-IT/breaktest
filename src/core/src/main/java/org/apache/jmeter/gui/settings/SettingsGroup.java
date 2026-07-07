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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A named group of related settings, e.g. "SSL configuration" or
 * "Results file configuration". Groups are listed on the left-hand side of
 * the Settings dialog.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingsGroup {

    /** The properties file a group's overrides are written to. */
    public enum Target {
        /** {@code user.properties} - regular JMeter properties */
        USER,
        /** {@code system.properties} - JVM system properties */
        SYSTEM;

        @JsonCreator
        public static Target fromJson(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    private String id;
    private String title;
    private String description = "";
    private Target target = Target.USER;
    private List<SettingDefinition> settings = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public Target getTarget() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    public List<SettingDefinition> getSettings() {
        return settings;
    }

    public void setSettings(List<SettingDefinition> settings) {
        this.settings = settings == null ? new ArrayList<>() : settings;
    }

    @Override
    public String toString() {
        return title;
    }
}
