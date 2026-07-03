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

package org.apache.jmeter.ai.knowledge.gui;

import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.Collection;

import javax.swing.JLabel;
import javax.swing.JPanel;

import org.apache.jmeter.ai.knowledge.BreakTestAiKnowledge;
import org.apache.jmeter.config.gui.AbstractConfigGui;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.gui.util.JTextScrollPane;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

/**
 * GUI for project-local AI scripting knowledge stored in the JMX.
 */
@GUIMenuSortOrder(20)
@TestElementMetadata(labelResource = "breaktest_ai_knowledge_title", actionGroups = MenuFactory.CONFIG_ELEMENTS)
public class BreakTestAiKnowledgeGui extends AbstractConfigGui {
    private static final long serialVersionUID = 1L;

    private final JSyntaxTextArea knowledgeJson = JSyntaxTextArea.getInstance(28, 80, true);

    public BreakTestAiKnowledgeGui() {
        init();
    }

    @Override
    public String getLabelResource() {
        return "breaktest_ai_knowledge_title";
    }

    @Override
    public Collection<String> getMenuCategories() {
        return Arrays.asList(MenuFactory.CONFIG_ELEMENTS);
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        if (element instanceof BreakTestAiKnowledge knowledge) {
            knowledgeJson.setText(knowledge.getKnowledgeJson());
        } else {
            knowledgeJson.setText(BreakTestAiKnowledge.DEFAULT_JSON);
        }
        knowledgeJson.setCaretPosition(0);
    }

    @Override
    public TestElement createTestElement() {
        BreakTestAiKnowledge element = new BreakTestAiKnowledge();
        modifyTestElement(element);
        return element;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        if (element instanceof BreakTestAiKnowledge knowledge) {
            knowledge.setKnowledgeJson(knowledgeJson.getText());
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        knowledgeJson.setText(BreakTestAiKnowledge.DEFAULT_JSON);
        knowledgeJson.setCaretPosition(0);
    }

    private void init() {
        setLayout(new BorderLayout(0, 8));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.add(new JLabel(JMeterUtils.getResString("breaktest_ai_knowledge_description")), BorderLayout.NORTH);
        body.add(JTextScrollPane.getInstance(knowledgeJson), BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
    }
}
