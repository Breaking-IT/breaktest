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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jmeter.util.JMeterUtils;

/**
 * Resolves the current value of each setting from the layered JMeter
 * configuration ({@code jmeter.properties}, then {@code user.properties},
 * then runtime overrides) and writes changes back to {@code user.properties}
 * or {@code system.properties}.
 *
 * <p>{@code jmeter.properties} is never modified: it documents the shipped
 * defaults, and per-installation changes belong in {@code user.properties},
 * as recommended by the header of {@code jmeter.properties} itself.</p>
 */
public class SettingsModel {

    /** Where the value shown for a setting comes from. */
    public enum ValueSource {
        /** built-in default, not set in any file */
        DEFAULT,
        /** set in jmeter.properties */
        JMETER_PROPERTIES,
        /** overridden in user.properties (or system.properties for system settings) */
        OVERRIDE
    }

    private final SettingsCatalog catalog;
    private final PropertiesFileStore jmeterStore;
    private final PropertiesFileStore userStore;
    private final PropertiesFileStore systemStore;

    public SettingsModel() throws IOException {
        this(SettingsCatalog.load(),
                new File(JMeterUtils.getJMeterBinDir(), "jmeter.properties"),
                resolvePropertiesFile("user.properties"),
                resolvePropertiesFile("system.properties"));
    }

    public SettingsModel(SettingsCatalog catalog, File jmeterProperties, File userProperties, File systemProperties)
            throws IOException {
        this.catalog = catalog;
        this.jmeterStore = new PropertiesFileStore(jmeterProperties);
        this.userStore = new PropertiesFileStore(userProperties);
        this.systemStore = new PropertiesFileStore(systemProperties);
    }

    /**
     * Resolves the file JMeter loads for the given file-name property
     * ({@code user.properties} or {@code system.properties}), mirroring the
     * lookup done at startup: the property value as-is if it exists, otherwise
     * relative to the bin directory.
     */
    private static File resolvePropertiesFile(String propertyName) {
        String name = JMeterUtils.getPropDefault(propertyName, propertyName);
        if (name.isEmpty()) {
            name = propertyName;
        }
        File file = new File(name);
        if (file.exists()) {
            return file;
        }
        return new File(JMeterUtils.getJMeterBinDir(), name);
    }

    public SettingsCatalog getCatalog() {
        return catalog;
    }

    public File getUserFile() {
        return userStore.getFile();
    }

    public File getSystemFile() {
        return systemStore.getFile();
    }

    private PropertiesFileStore overrideStore(SettingsGroup group) {
        return overrideStore(group.getTarget());
    }

    private PropertiesFileStore overrideStore(SettingsGroup.Target target) {
        return target == SettingsGroup.Target.SYSTEM ? systemStore : userStore;
    }

    /**
     * @param group group the setting belongs to
     * @param setting the setting to resolve
     * @return the value currently effective from the properties files, or the
     *         built-in default, or the empty string
     */
    public String getValue(SettingsGroup group, SettingDefinition setting) {
        String key = setting.getKey();
        String value = overrideStore(group).getValue(key);
        if (value == null && group.getTarget() == SettingsGroup.Target.USER) {
            value = jmeterStore.getValue(key);
        }
        if (value == null && group.getTarget() == SettingsGroup.Target.SYSTEM) {
            value = System.getProperty(key);
        }
        if (value == null) {
            value = setting.getDefaultValue();
        }
        return value == null ? "" : value;
    }

    /**
     * @param group group the setting belongs to
     * @param setting the setting to resolve
     * @return where the value returned by {@link #getValue} comes from
     */
    public ValueSource getSource(SettingsGroup group, SettingDefinition setting) {
        String key = setting.getKey();
        if (overrideStore(group).containsKey(key)) {
            return ValueSource.OVERRIDE;
        }
        if (group.getTarget() == SettingsGroup.Target.USER && jmeterStore.containsKey(key)) {
            return ValueSource.JMETER_PROPERTIES;
        }
        return ValueSource.DEFAULT;
    }

    /**
     * @param group group the setting belongs to
     * @param setting the setting to resolve
     * @return the value the setting falls back to when its override is removed
     */
    public String getInheritedValue(SettingsGroup group, SettingDefinition setting) {
        String value = null;
        if (group.getTarget() == SettingsGroup.Target.USER) {
            value = jmeterStore.getValue(setting.getKey());
        }
        if (value == null) {
            value = setting.getDefaultValue();
        }
        return value == null ? "" : value;
    }

    /**
     * Keys that are set in jmeter.properties or user.properties but are not
     * described by the catalog. They are shown in a generated "Other" group so
     * that every property of the installation is visible.
     *
     * @return sorted set of unknown property keys
     */
    public Set<String> getUncataloguedKeys() {
        Set<String> keys = new TreeSet<>();
        keys.addAll(jmeterStore.getKeys());
        keys.addAll(userStore.getKeys());
        for (SettingsGroup group : catalog.getGroups()) {
            for (SettingDefinition setting : group.getSettings()) {
                keys.remove(setting.getKey());
            }
        }
        return keys;
    }

    /**
     * Builds a synthetic group for {@link #getUncataloguedKeys}, so properties
     * unknown to the catalog remain visible and editable.
     */
    public SettingsGroup buildOtherGroup(String title, String description) {
        SettingsGroup group = new SettingsGroup();
        group.setId("other");
        group.setTitle(title);
        group.setDescription(description);
        group.setTarget(SettingsGroup.Target.USER);
        List<SettingDefinition> settings = new ArrayList<>();
        for (String key : getUncataloguedKeys()) {
            SettingDefinition setting = new SettingDefinition();
            setting.setKey(key);
            setting.setType(SettingType.TEXT);
            setting.setDefaultValue(jmeterStore.getValue(key));
            settings.add(setting);
        }
        group.setSettings(settings);
        return group;
    }

    /**
     * Applies the given override changes and writes the affected file.
     *
     * @param target file that receives the changes
     * @param overrides keys to set, with their new values
     * @param removals keys whose override should be removed
     * @throws IOException when the target file cannot be written
     */
    public void apply(SettingsGroup.Target target, Map<String, String> overrides, Set<String> removals)
            throws IOException {
        if (overrides.isEmpty() && removals.isEmpty()) {
            return;
        }
        PropertiesFileStore store = overrideStore(target);
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            store.setValue(entry.getKey(), entry.getValue());
        }
        for (String key : removals) {
            store.removeValue(key);
        }
        store.save();
        refreshRuntime(target, overrides, removals);
    }

    /**
     * Best-effort update of the running JVM so settings that are read lazily
     * take effect without a restart. Settings read once at startup still
     * require a restart.
     */
    private void refreshRuntime(SettingsGroup.Target target, Map<String, String> overrides, Set<String> removals) {
        if (target == SettingsGroup.Target.SYSTEM) {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
            for (String key : removals) {
                System.clearProperty(key);
            }
            return;
        }
        Properties props = JMeterUtils.getJMeterProperties();
        if (props == null) {
            return;
        }
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            props.setProperty(entry.getKey(), entry.getValue());
        }
        for (String key : removals) {
            String inherited = jmeterStore.getValue(key);
            if (inherited != null) {
                props.setProperty(key, inherited);
            } else {
                props.remove(key);
            }
        }
    }

    /**
     * @return groups of the catalog plus the synthetic group of uncatalogued
     *         properties when there are any
     */
    public List<SettingsGroup> getGroupsWithOther(String otherTitle, String otherDescription) {
        List<SettingsGroup> groups = new ArrayList<>(catalog.getGroups());
        SettingsGroup other = buildOtherGroup(otherTitle, otherDescription);
        if (!other.getSettings().isEmpty()) {
            groups.add(other);
        }
        return Collections.unmodifiableList(groups);
    }
}
