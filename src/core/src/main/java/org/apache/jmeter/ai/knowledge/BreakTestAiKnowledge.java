/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.ai.knowledge;

import java.io.Serializable;

import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.NonTestElement;

/**
 * Stores project-local AI scripting learnings inside the test plan.
 */
public class BreakTestAiKnowledge extends AbstractTestElement implements NonTestElement, NoThreadClone, Serializable {
    private static final long serialVersionUID = 1L;

    public static final String KNOWLEDGE_JSON = "BreakTestAiKnowledge.knowledgeJson";

    public static final String DEFAULT_NAME = "BreakTest AI Knowledge";

    public static final String DEFAULT_JSON = """
            {
              "schemaVersion": 1,
              "projectHints": [],
              "correlationPatterns": [],
              "assertionPatterns": [],
              "variableMappings": [],
              "knownDynamicFields": [],
              "timestampRules": [],
              "transactionDependencies": [],
              "learnedFromThreadGroups": []
            }
            """;

    public BreakTestAiKnowledge() {
        setName(DEFAULT_NAME);
        setKnowledgeJson(DEFAULT_JSON);
    }

    public String getKnowledgeJson() {
        return getPropertyAsString(KNOWLEDGE_JSON, DEFAULT_JSON);
    }

    public void setKnowledgeJson(String knowledgeJson) {
        setProperty(KNOWLEDGE_JSON, knowledgeJson == null || knowledgeJson.isBlank() ? DEFAULT_JSON : knowledgeJson);
    }
}
