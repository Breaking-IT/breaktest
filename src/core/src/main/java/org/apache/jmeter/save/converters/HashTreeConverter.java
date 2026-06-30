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

import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.MissingTestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.collections.AbstractCollectionConverter;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.CannotResolveClassException;
import com.thoughtworks.xstream.mapper.Mapper;

public class HashTreeConverter extends AbstractCollectionConverter {
    private static final Logger log = LoggerFactory.getLogger(HashTreeConverter.class);

    /**
     * Returns the converter version; used to check for possible
     * incompatibilities
     *
     * @return the version of this converter
     */
    public static String getVersion() {
        return "$Revision$";  //$NON-NLS-1$
    }

    /** {@inheritDoc} */
    @Override
    public boolean canConvert(@SuppressWarnings("rawtypes") Class arg0) { // superclass does not use types
        return HashTree.class.isAssignableFrom(arg0);
    }

    /** {@inheritDoc} */
    @Override
    public void marshal(Object arg0, HierarchicalStreamWriter writer, MarshallingContext context) {
        HashTree tree = (HashTree) arg0;
        for (Object item : tree.list()) {
            writeCompleteItem(item, context, writer);
            writeCompleteItem(tree.getTree(item), context, writer);
        }

    }

    /** {@inheritDoc} */
    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        boolean isKey = true;
        Object current = null;
        HashTree tree = (HashTree) createCollection(context.getRequiredType());
        while (reader.hasMoreChildren()) {
            reader.moveDown();
            Object item = isKey ? readHashTreeKey(reader, context, tree) : readBareItem(reader, context, tree);
            if (isKey) {
                tree.add(item);
                current = item;
                isKey = false;
            } else {
                tree.set(current, (HashTree) item);
                isKey = true;
            }
            reader.moveUp();
        }
        return tree;
    }

    private Object readHashTreeKey(HierarchicalStreamReader reader, UnmarshallingContext context, Object current) {
        String guiClass = reader.getAttribute(ConversionHelp.ATT_TE_GUICLASS);
        if (guiClass == null) {
            return readBareItem(reader, context, current);
        }

        String elementName = reader.getNodeName();
        String testClassName = aliasToClass(reader.getAttribute("testclass")); // $NON-NLS-1$
        if (testClassName == null) {
            testClassName = aliasToClass(reader.getAttribute(ConversionHelp.ATT_CLASS));
        }
        if (testClassName == null) {
            testClassName = aliasToClass(elementName);
        }
        String guiClassName = aliasToClass(guiClass);

        Throwable missingClass = missingClass(testClassName);
        if (missingClass == null) {
            missingClass = missingClass(guiClassName);
        }
        if (missingClass == null) {
            return readBareItem(reader, context, current);
        }
        return readMissingTestElement(reader, context, elementName, testClassName, guiClassName, missingClass);
    }

    private static String aliasToClass(String alias) {
        return alias == null ? null : SaveService.aliasToClass(alias);
    }

    private Throwable missingClass(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        try {
            mapper().realClass(className);
            return null;
        } catch (CannotResolveClassException | NoClassDefFoundError e) {
            return e;
        }
    }

    private Object readMissingTestElement(HierarchicalStreamReader reader, UnmarshallingContext context,
            String elementName, String testClassName, String guiClassName, Throwable cause) {
        log.warn("Replacing unavailable JMX element '{}' with missing-element placeholder: testClass='{}', guiClass='{}'",
                elementName, testClassName, guiClassName, cause);
        context.put(SaveService.TEST_CLASS_NAME, testClassName);
        MissingTestElement el = new MissingTestElement();
        ConversionHelp.restoreSpecialProperties(el, reader);
        while (reader.hasMoreChildren()) {
            reader.moveDown();
            JMeterProperty prop = (JMeterProperty) readBareItem(reader, context, el);
            if (prop != null) {
                el.setProperty(prop);
            }
            reader.moveUp();
        }
        el.configureMissingElement(elementName, testClassName, guiClassName, cause);
        return el;
    }

    public HashTreeConverter(Mapper arg0) {
        super(arg0);
    }
}
