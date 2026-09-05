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


package org.apache.jmeter.gui.util;

import javax.swing.tree.TreePath;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.assertions.gui.AssertionGui;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.gui.RegexExtractorGui;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.oro.text.regex.Perl5Compiler;

/** Shared creation defaults and navigation for actions on response selections. */
public final class ResponseSelectionActions {
    private ResponseSelectionActions() {
    }

    public static ResponseAssertion assertionForSelection(String headers, int start, int end, String text) {
        Boolean fromHeaders = selectionFromHeaders(headers, start, end, text);
        if (fromHeaders == null) {
            return null;
        }
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setName(JMeterUtils.getResString(fromHeaders
                ? "view_results_assert_headers" : "view_results_assert_body")); //$NON-NLS-1$ //$NON-NLS-2$
        assertion.setProperty(TestElement.GUI_CLASS, AssertionGui.class.getName());
        assertion.setProperty(TestElement.TEST_CLASS, ResponseAssertion.class.getName());
        assertion.setEnabled(true);
        assertion.setScopeParent();
        assertion.setToSubstringType();
        if (fromHeaders) {
            assertion.setTestFieldResponseHeaders();
        } else {
            assertion.setTestFieldResponseData();
        }
        assertion.addTestString(text);
        return assertion;
    }

    private static Boolean selectionFromHeaders(String headers, int start, int end, String text) {
        if (text == null || text.isEmpty() || end <= start) {
            return null;
        }
        int headerEnd = headers == null ? 0 : headers.length();
        boolean fromHeaders = headerEnd > 0 && end <= headerEnd;
        if (!fromHeaders && headerEnd > 0 && start < headerEnd + 1) {
            return null;
        }
        return fromHeaders;
    }

    public static RegexExtractor extractorForSelection(String headers, int start, int end, String text) {
        Boolean fromHeaders = selectionFromHeaders(headers, start, end, text);
        if (fromHeaders == null) {
            return null;
        }
        RegexExtractor extractor = new RegexExtractor();
        extractor.setName(JMeterUtils.getResString("regex_extractor_title")); //$NON-NLS-1$
        extractor.setRefName(""); //$NON-NLS-1$
        extractor.setProperty(TestElement.GUI_CLASS, RegexExtractorGui.class.getName());
        extractor.setProperty(TestElement.TEST_CLASS, RegexExtractor.class.getName());
        extractor.setEnabled(true);
        extractor.setScopeParent();
        extractor.setUseField(fromHeaders ? RegexExtractor.USE_HDRS : RegexExtractor.USE_BODY);
        extractor.setRegex(Perl5Compiler.quotemeta(text));
        extractor.setTemplate("$1$"); //$NON-NLS-1$
        extractor.setMatchNumber(1);
        extractor.setFailOnNoMatch(true);
        return extractor;
    }

    public static void addResponseChild(TestElement child, JMeterTreeNode node) {
        GuiPackage gui = GuiPackage.getInstance();
        if (child == null || gui == null || node == null || !(node.getTestElement() instanceof Sampler)) {
            return;
        }
        gui.updateCurrentNode();
        JMeterTreeNode addedNode;
        gui.beginUndoTransaction();
        try {
            addedNode = gui.getTreeModel().addComponent(child, node);
            gui.setDirty(true);
        } catch (org.apache.jmeter.exceptions.IllegalUserActionException e) {
            JMeterUtils.reportErrorToUser(e.getMessage());
            return;
        } finally {
            gui.endUndoTransaction();
        }
        if (child instanceof RegexExtractor) {
            var tree = gui.getTreeListener().getJTree();
            TreePath path = new TreePath(addedNode.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            tree.requestFocusInWindow();
        }
    }
}

