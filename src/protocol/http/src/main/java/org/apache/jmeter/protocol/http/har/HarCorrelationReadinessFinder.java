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

package org.apache.jmeter.protocol.http.har;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.readiness.Redactor;
import org.apache.jmeter.readiness.check.HardcodedValues;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jorphan.util.StringUtilities;

import com.google.auto.service.AutoService;

/**
 * Script readiness: finds recorded dynamic values that requests still send literally, with the same rules and
 * recorded exchanges as "Find predefined correlations". Only reports candidates; never changes the plan.
 */
@AutoService(HardcodedValues.Finder.class)
public final class HarCorrelationReadinessFinder implements HardcodedValues.Finder {

    @Override
    public HardcodedValues.Scan find(List<TestElement> threadGroups) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return HardcodedValues.Scan.unavailable("The correlation tooling needs the GUI test plan");
        }
        Path testPlanFile = StringUtilities.isEmpty(gui.getTestPlanFile()) ? null : Path.of(gui.getTestPlanFile());
        List<HardcodedValues.Candidate> candidates = new ArrayList<>();
        boolean scanned = false;
        int unavailable = 0;
        for (TestElement group : threadGroups) {
            JMeterTreeNode node = gui.getTreeModel().getNodeOf(group);
            if (node == null || !FindPredefinedCorrelationsAction.hasRecordedSampler(node)) {
                continue;
            }
            scanned = true;
            FindPredefinedCorrelationsAction.ScanResult scan = FindPredefinedCorrelationsAction.scan(node,
                    testPlanFile);
            unavailable += scan.unavailableCount();
            for (HarPredefinedCorrelation correlation : scan.correlations()) {
                JMeterTreeNode source = scan.nodesByEntryIndex().get(correlation.getSourceEntryIndex());
                for (HarPredefinedCorrelation.Replacement replacement : correlation.getReplacements()) {
                    JMeterTreeNode target = scan.nodesByEntryIndex().get(replacement.getTargetEntryIndex());
                    if (target == null) {
                        continue;
                    }
                    candidates.add(new HardcodedValues.Candidate(
                            ValidationCheckpoints.sourceIdOfSource(target.getTestElement()),
                            mask(replacement.getLocationName(), correlation.getExtractedValue()),
                            correlation.getRule().getName(),
                            replacement.getLocation().getDisplayName()
                                    + (replacement.getLocationName().isEmpty() ? ""
                                            : " '" + replacement.getLocationName() + "'"),
                            source == null ? correlation.getSourceUrl() : source.getName()));
                }
            }
        }
        if (!scanned) {
            return HardcodedValues.Scan.unavailable("no thread group in scope has linked recorded responses");
        }
        return new HardcodedValues.Scan(candidates, true,
                unavailable == 0 ? "" : unavailable + " recorded exchange(s) could not be read");
    }

    /**
     * Never shows recorded secrets: values are cut to a prefix, and fully masked for sensitive names.
     */
    static String mask(String name, String value) {
        String text = value == null ? "" : value;
        if (name != null && name.toLowerCase(Locale.ROOT).matches(".*(pass|pwd|secret|token|key|auth|session).*")) {
            return Redactor.MASK + " (" + text.length() + " characters)";
        }
        return Redactor.bound((text.length() <= 4 ? text : text.substring(0, 4) + "…") + " ("
                + text.length() + " characters)");
    }
}
