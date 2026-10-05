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

package org.apache.jmeter.protocol.http.proxy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.gui.settings.PropertiesFileStore;
import org.apache.jmeter.protocol.http.har.HarImportOptions;
import org.apache.jmeter.util.JMeterUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Cross-plan recorder preferences; only explicit UI settings are persisted, never capture data. */
public final class RecorderSettings {
    private static final String PREFIX = "proxy.recorder.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] KEYS = {
        "port", "domains", "notify_child_sl_filtered", "store_recorded_exchanges", "grouping_mode", "sampler_redirect_automatically",
        "sampler_follow_redirects", "use_keepalive", "detect_graphql_request", "sampler_download_images",
        "proxy_http_sampler_naming_mode", "proxy_http_sampler_format", "proxy_prefix_http_sampler_name",
        "default_encoding", "content_type_include", "content_type_exclude"
    };

    private RecorderSettings() { }

    public static void applyDefaults(ProxyControl recorder) {
        for (String key : KEYS) {
            String value = JMeterUtils.getProperty(PREFIX + key);
            if (value != null) {
                recorder.setProperty("ProxyControlGui." + key, value);
            }
        }
        recorder.setAddPreflightSuffix(JMeterUtils.getPropDefault(PREFIX + "add_preflight_suffix", true));
        recorder.setIgnoreHttpErrors(JMeterUtils.getPropDefault(PREFIX + "ignore_http_errors", false));
        recorder.setIncludeList(patterns("include_patterns"));
        recorder.setExcludeList(patterns("exclude_patterns"));
    }

    public static void save(ProxyControl recorder, HarImportOptions options) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : KEYS) {
            values.put(PREFIX + key, recorder.getPropertyAsString("ProxyControlGui." + key));
        }
        values.put(PREFIX + "include_patterns", JSON.writeValueAsString(patterns(recorder.getIncludePatterns())));
        values.put(PREFIX + "exclude_patterns", JSON.writeValueAsString(patterns(recorder.getExcludePatterns())));
        values.put(PREFIX + "notify_child_sl_filtered", Boolean.toString(recorder.getNotifyChildSamplerListenerOfFilteredSamplers()));
        values.put(PREFIX + "grouping_mode", Integer.toString(recorder.getGroupingMode()));
        values.put(PREFIX + "sampler_follow_redirects", Boolean.toString(recorder.getSamplerFollowRedirects()));
        values.put(PREFIX + "sampler_redirect_automatically", Boolean.toString(recorder.getSamplerRedirectAutomatically()));
        values.put(PREFIX + "use_keepalive", Boolean.toString(recorder.getUseKeepalive()));
        values.put(PREFIX + "detect_graphql_request", Boolean.toString(recorder.getDetectGraphQLRequest()));
        values.put(PREFIX + "proxy_http_sampler_format", recorder.getHttpSampleNameFormat());
        values.put(PREFIX + "add_preflight_suffix", Boolean.toString(recorder.getAddPreflightSuffix()));
        values.put(PREFIX + "ignore_http_errors", Boolean.toString(recorder.getIgnoreHttpErrors()));
        values.put(PREFIX + "store_recorded_exchanges", Boolean.toString(recorder.getStoreRecordedExchanges()));
        if (options != null) {
            values.put(PREFIX + "delay_mode", options.getDelayMode().name());
            values.put(PREFIX + "delay_fixed", options.getFixedDelay());
            values.put(PREFIX + "delay_min", options.getDelayMin());
            values.put(PREFIX + "delay_max", options.getDelayMax());
            values.put(PREFIX + "delay_spread", Integer.toString(options.getRecordedRandomPercent()));
        }
        save(values, userFile());
    }

    private static List<String> patterns(String key) {
        String value = JMeterUtils.getPropDefault(PREFIX + key, "[]");
        try {
            return JSON.readValue(value, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> patterns(org.apache.jmeter.testelement.property.CollectionProperty properties) {
        List<String> result = new ArrayList<>();
        for (var property : properties) {
            result.add(property.getStringValue());
        }
        return result;
    }

    static void save(Map<String, String> values, File file) throws IOException {
        PropertiesFileStore store = new PropertiesFileStore(file);
        values.forEach(store::setValue);
        store.save();
        values.forEach(JMeterUtils::setProperty);
    }

    private static File userFile() {
        String name = JMeterUtils.getPropDefault("user.properties", "user.properties");
        if (name.isEmpty()) {
            name = "user.properties";
        }
        File file = new File(name);
        return file.isAbsolute() || file.exists() ? file : new File(JMeterUtils.getJMeterBinDir(), name);
    }

    public static HarImportOptions options(ProxyControl recorder) {
        HarImportOptions options = new HarImportOptions();
        options.setIgnoreErrors(recorder.getIgnoreHttpErrors());
        options.setRecordedRandomPercent(JMeterUtils.getPropDefault(PREFIX + "delay_spread", 0));
        try {
            options.setDelayMode(HarImportOptions.DelayMode.valueOf(JMeterUtils.getPropDefault(PREFIX + "delay_mode", "AS_RECORDED")));
        } catch (IllegalArgumentException ignored) {
            options.setDelayMode(HarImportOptions.DelayMode.AS_RECORDED);
        }
        options.setFixedDelay(JMeterUtils.getPropDefault(PREFIX + "delay_fixed", "1000"));
        options.setDelayMin(JMeterUtils.getPropDefault(PREFIX + "delay_min", "5000"));
        options.setDelayMax(JMeterUtils.getPropDefault(PREFIX + "delay_max", "25000"));
        options.setIdleTimeSeconds((int) Math.min(Integer.MAX_VALUE, RecordingRequestSettings.transactionGapMillis(recorder) / 1000));
        return options;
    }
}
