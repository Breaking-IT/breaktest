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

package org.apache.jmeter.config;

import java.beans.BeanDescriptor;
import java.beans.PropertyDescriptor;
import java.util.Enumeration;

import org.apache.jmeter.testbeans.BeanInfoSupport;
import org.apache.jmeter.testbeans.gui.TypeEditor;

public class XMLDataSetBeanInfo extends BeanInfoSupport {
    private final BeanDescriptor beanDescriptor;

    public XMLDataSetBeanInfo() {
        super(XMLDataSet.class);
        beanDescriptor = createBeanDescriptorWithCustomizer(super.getBeanDescriptor());
        createPropertyGroup("xml_data",
                new String[] {"filename", "useXmlFromArchive", "variablePrefix", "recordXPath", "recycle", "stopThread", "shareMode"});
        PropertyDescriptor sharing = property("shareMode", TypeEditor.ComboStringEditor);
        sharing.setValue(NOT_UNDEFINED, true);
        sharing.setValue(DEFAULT, "shareMode.all");
        sharing.setValue(NOT_OTHER, false);
        sharing.setValue(NOT_EXPRESSION, false);
        sharing.setValue(TAGS, CSVDataSetBeanInfo.getShareTags());
        sharing.setValue(RESOURCE_BUNDLE, getBeanDescriptor().getValue(RESOURCE_BUNDLE));
        PropertyDescriptor filename = property("filename");
        filename.setValue(NOT_UNDEFINED, true);
        filename.setValue(DEFAULT, "");
        filename.setPropertyEditorClass(XMLDataSetCustomizer.XmlFilenameEditor.class);
        PropertyDescriptor xpath = property("recordXPath");
        xpath.setValue(NOT_UNDEFINED, true);
        xpath.setValue(DEFAULT, "/*/*");
        PropertyDescriptor prefix = property("variablePrefix");
        prefix.setValue(NOT_UNDEFINED, true);
        prefix.setValue(DEFAULT, "");
        for (String name : new String[] {"useXmlFromArchive", "recycle", "stopThread"}) {
            PropertyDescriptor option = property(name);
            option.setValue(NOT_UNDEFINED, true);
            option.setValue(DEFAULT, "recycle".equals(name));
        }
    }

    @Override
    public BeanDescriptor getBeanDescriptor() {
        return beanDescriptor == null ? super.getBeanDescriptor() : beanDescriptor;
    }

    private static BeanDescriptor createBeanDescriptorWithCustomizer(BeanDescriptor source) {
        BeanDescriptor descriptor = new BeanDescriptor(XMLDataSet.class, XMLDataSetCustomizer.class);
        descriptor.setDisplayName(source.getDisplayName());
        descriptor.setShortDescription(source.getShortDescription());
        Enumeration<String> attributeNames = source.attributeNames();
        while (attributeNames.hasMoreElements()) {
            String attributeName = attributeNames.nextElement();
            descriptor.setValue(attributeName, source.getValue(attributeName));
        }
        return descriptor;
    }

}
