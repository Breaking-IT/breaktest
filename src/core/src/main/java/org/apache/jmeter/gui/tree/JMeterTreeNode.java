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

package org.apache.jmeter.gui.tree;

import java.awt.Image;
import java.beans.BeanDescriptor;
import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.swing.ImageIcon;
import javax.swing.JPopupMenu;
import javax.swing.tree.DefaultMutableTreeNode;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.gui.AbstractAssertionGui;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.config.gui.AbstractConfigGui;
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.gui.AbstractControllerGui;
import org.apache.jmeter.gui.GUIFactory;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.processor.gui.AbstractPostProcessorGui;
import org.apache.jmeter.processor.gui.AbstractPreProcessorGui;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.timers.gui.AbstractTimerGui;
import org.apache.jmeter.visualizers.Visualizer;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMeterTreeNode extends DefaultMutableTreeNode implements NamedTreeNode {
    private static final long serialVersionUID = 241L;

    private static final Logger log = LoggerFactory.getLogger(JMeterTreeNode.class);

    private static final int TEST_PLAN_LEVEL = 1;

    // See Bug 54648
    private final transient Optional<JMeterTreeModel> treeModel;

    private boolean markedBySearch;

    private boolean childrenMarkedBySearch;

    public JMeterTreeNode() {// Allow serializable test to work
        // TODO: is the serializable test necessary now that JMeterTreeNode is
        // no longer a GUI component?
        this(null, null);
    }

    public JMeterTreeNode(TestElement userObj, JMeterTreeModel treeModel) {
        super(userObj);
        this.treeModel = Optional.ofNullable(treeModel);
    }

    public boolean isEnabled() {
        return getTestElement().isEnabled();
    }

    public void setEnabled(boolean enabled) {
        getTestElement().setEnabled(enabled);
        treeModel.ifPresent(tm -> tm.nodeChanged(this));
    }

    /**
     * Return nodes to level 2
     * @return {@link List} of {@link JMeterTreeNode}s
     */
    public List<JMeterTreeNode> getPathToThreadGroup() {
        if (!treeModel.isPresent()) {
            return new ArrayList<>();
        }

        return Arrays.stream(treeModel.get().getPathToRoot(this))
                .map(node -> (JMeterTreeNode) node)
                .filter(node -> node.getLevel() >= TEST_PLAN_LEVEL)
                .collect(Collectors.toList());
    }

    /**
     * One of the children of this node have matched a search
     * @param tagged true if we must tag
     */
    public void setChildrenNodesHaveMatched(boolean tagged) {
        if (childrenMarkedBySearch == tagged) {
            return;
        }
        this.childrenMarkedBySearch = tagged;
        treeModel.ifPresent(tm -> tm.nodeChanged(this));
    }
    /**
     * Tag Node as result of a search
     * @param tagged The flag to be used for tagging
     */
    public void setMarkedBySearch(boolean tagged) {
        if (this.markedBySearch == tagged) {
            return;
        }
        this.markedBySearch = tagged;
        if (tagged) {
            List<JMeterTreeNode> nodesToParent = getPathToThreadGroup();
            for (JMeterTreeNode jMeterTreeNode : nodesToParent) {
                // Ignore me
                if(jMeterTreeNode != this) {
                    jMeterTreeNode.setChildrenNodesHaveMatched(true);
                }
            }
        }

        treeModel.ifPresent(tm -> tm.nodeChanged(this));
    }

    /**
     * Node is markedBySearch by a search
     * @return true if marked by search
     */
    public boolean isMarkedBySearch() {
        return this.markedBySearch;
    }

    /**
     * Node has children marked by search
     * @return boolean are children marked by search
     */
    public boolean isChildrenMarkedBySearch() {
        return this.childrenMarkedBySearch;
    }

    public ImageIcon getIcon() {
        return getIcon(true);
    }

    public ImageIcon getIcon(boolean enabled) {
        TestElement testElement = getTestElement();
        try {
            if (testElement instanceof TestBean) {
                Class<?> testClass = testElement.getClass();
                try {
                    Image img = Introspector.getBeanInfo(testClass).getIcon(BeanInfo.ICON_COLOR_16x16);
                    // If icon has not been defined, then use GUI_CLASS property
                    if (img == null) {
                        String guiClassName = getTestBeanGuiClassName(
                                Introspector.getBeanInfo(testClass).getBeanDescriptor(), testClass);
                        if (guiClassName == null) {
                            log.warn("getIcon(): Can't obtain GUI class from {}", testClass);
                            return null;
                        }
                        return GUIFactory.getIcon(Class.forName(guiClassName), enabled);
                    }
                    return new ImageIcon(img);
                } catch (IntrospectionException e1) {
                    log.error("Can't obtain icon for class {}", testElement, e1);
                    throw new org.apache.jorphan.util.JMeterError(e1);
                }
            }
            return GUIFactory.getIcon(Class.forName(testElement.getPropertyAsString(TestElement.GUI_CLASS)),
                        enabled);
        } catch (ClassNotFoundException e) {
            log.warn("Can't get icon for class {}", testElement, e);
            return null;
        }
    }

    static String getTestBeanGuiClassName(BeanDescriptor beanDescriptor, Class<?> testClass) {
        Object guiClassName = beanDescriptor.getValue(TestElement.GUI_CLASS);
        if (guiClassName instanceof String guiClassNameString && !guiClassNameString.isEmpty()) {
            return guiClassNameString;
        }

        Class<?> guiClass = null;
        if (Assertion.class.isAssignableFrom(testClass)) {
            guiClass = AbstractAssertionGui.class;
        }
        if (ConfigElement.class.isAssignableFrom(testClass)) {
            guiClass = AbstractConfigGui.class;
        }
        if (Controller.class.isAssignableFrom(testClass)) {
            guiClass = AbstractControllerGui.class;
        }
        if (Visualizer.class.isAssignableFrom(testClass)) {
            guiClass = AbstractVisualizer.class;
        }
        if (PostProcessor.class.isAssignableFrom(testClass)) {
            guiClass = AbstractPostProcessorGui.class;
        }
        if (PreProcessor.class.isAssignableFrom(testClass)) {
            guiClass = AbstractPreProcessorGui.class;
        }
        if (Sampler.class.isAssignableFrom(testClass)) {
            guiClass = AbstractSamplerGui.class;
        }
        if (Timer.class.isAssignableFrom(testClass)) {
            guiClass = AbstractTimerGui.class;
        }
        if (guiClass == null) {
            return null;
        }

        String inferredGuiClassName = guiClass.getName();
        beanDescriptor.setValue(TestElement.GUI_CLASS, inferredGuiClassName);
        return inferredGuiClassName;
    }

    public Collection<String> getMenuCategories() {
        try {
            return GuiPackage.getInstance().getGui(getTestElement()).getMenuCategories();
        } catch (Exception e) {
            log.error("Can't get popup menu for gui", e);
            return null;
        }
    }

    public JPopupMenu createPopupMenu() {
        try {
            return GuiPackage.getInstance().getGui(getTestElement()).createPopupMenu();
        } catch (Exception e) {
            log.error("Can't get popup menu for gui", e);
            return null;
        }
    }

    public TestElement getTestElement() {
        return (TestElement) getUserObject();
    }

    public String getStaticLabel() {
        return GuiPackage.getInstance().getGui((TestElement) getUserObject()).getStaticLabel();
    }

    public String getDocAnchor() {
        return GuiPackage.getInstance().getGui((TestElement) getUserObject()).getDocAnchor();
    }

    /** {@inheritDoc} */
    @Override
    public void setName(String name) {
        ((TestElement) getUserObject()).setName(name);
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return ((TestElement) getUserObject()).getName();
    }

    /** {@inheritDoc} */
    @Override
    public void nameChanged() {
        treeModel.ifPresent(tm -> tm.nodeChanged(this));
    }
}
