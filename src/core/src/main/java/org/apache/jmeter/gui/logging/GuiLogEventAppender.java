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

package org.apache.jmeter.gui.logging;

import java.io.Serializable;

import org.apache.jorphan.util.StringUtilities;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.StringLayout;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Posts log events to a {@link GuiLogEventBus}.
 * @since 3.2
 */
@Plugin(name = "GuiLogEvent", category = "Core", elementType = "appender", printObject = true)
public class GuiLogEventAppender extends AbstractAppender {

    protected GuiLogEventAppender(final String name, final Filter filter, final Layout<? extends Serializable> layout,
            final boolean ignoreExceptions) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent logEvent) {
        final String serializedString = getStringLayout().toSerializable(logEvent);
        if (StringUtilities.isNotEmpty(serializedString)) {
            // Log4j may reuse mutable events after append returns. Retain a snapshot
            // because the GUI can subscribe after startup logging has finished.
            LogEventObject logEventObject = new LogEventObject(logEvent.toImmutable(), serializedString);
            GuiLogEventBus.getInstance().postEvent(logEventObject);
        }
    }

    @PluginFactory
    public static GuiLogEventAppender createAppender(@PluginAttribute("name") String name,
            @PluginAttribute("ignoreExceptions") boolean ignoreExceptions,
            @PluginElement("Layout") Layout<? extends Serializable> layout, @PluginElement("Filters") Filter filter) {
        if (name == null) {
            LOGGER.error("No name provided for GuiLogEventAppender");
            return null;
        }

        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }

        return new GuiLogEventAppender(name, filter, layout, ignoreExceptions);
    }

    public StringLayout getStringLayout() {
        return (StringLayout) getLayout();
    }
}
