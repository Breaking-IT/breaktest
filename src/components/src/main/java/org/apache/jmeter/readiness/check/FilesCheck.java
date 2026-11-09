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

package org.apache.jmeter.readiness.check;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.config.StructuredDataSet;
import org.apache.jmeter.control.ModuleController;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.util.ScriptingTestElement;

/**
 * Files and configuration: CSV and structured data set files, upload files, script files and archive entries
 * referenced in scope exist and are readable (relative paths resolve as {@link FileServer} resolves them), and the
 * configuration a request needs is present (a server name, a Module Controller target, a data set file name).
 */
public final class FilesCheck implements ReadinessCheck {
    private static final String ARCHIVE_PREFIX = "archive:"; // $NON-NLS-1$

    @Override
    public CheckId id() {
        return CheckId.FILES;
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        List<Finding> findings = new ArrayList<>();
        int files = 0;
        int ok = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PlanNode node : context.model().nodes()) {
            if (!seen.add(node.sourceId())) {
                continue;
            }
            if (node.element() instanceof CSVDataSet bean) {
                CSVDataSet csv = Beans.prepared(bean);
                files++;
                ok += csv.isUseCsvFromArchive()
                        ? checkArchive(node, csv.getCsvArchiveEntry().isEmpty()
                                ? ArchiveFiles.importEntryName(csv.getFilename()) : csv.getCsvArchiveEntry(),
                                findings)
                        : checkFile(node, "CSV file", csv.getFilename(), true, findings);
            } else if (node.element() instanceof StructuredDataSet data) {
                files++;
                ok += checkFile(node, "data file", data.getFilename(), true, findings);
            } else if (node.element() instanceof ScriptingTestElement script
                    && !VariableSources.isEmpty(Beans.prepared(script).getFilename())) {
                files++;
                ok += checkFile(node, "script file", Beans.prepared(script).getFilename(), false, findings);
            } else if (node.element() instanceof ModuleController && node.children().isEmpty()) {
                findings.add(CheckContext.finding(id(), node, "module-target", Severity.BLOCKER,
                        "Module Controller '" + node.name() + "' has no (enabled) target", List.of()));
            }
            if (node.isRequestSampler()) {
                for (Map.Entry<String, String> text : PropertyTexts.of(node.element()).entrySet()) {
                    if (text.getKey().endsWith("File.path") && !text.getValue().isBlank()) { // $NON-NLS-1$
                        for (String path : text.getValue().split("\n")) {
                            files++;
                            ok += checkFile(node, "upload file", path, false, findings);
                        }
                    }
                }
                checkServer(node, findings);
            }
        }
        return CheckOutcome.of(findings, files > 0 || !findings.isEmpty(),
                ok + " of " + files + " referenced files found");
    }

    private void checkServer(PlanNode sampler, List<Finding> findings) {
        if (!sampler.element().getClass().getName().startsWith("org.apache.jmeter.protocol.http.sampler.")) { // $NON-NLS-1$
            return;
        }
        String path = sampler.element().getPropertyAsString("HTTPSampler.path"); // $NON-NLS-1$
        if (!sampler.element().getPropertyAsString("HTTPSampler.domain").isBlank() // $NON-NLS-1$
                || path.startsWith("http://") || path.startsWith("https://") || path.startsWith("${")) {
            return;
        }
        for (PlanNode config : sampler.applicable(ConfigTestElement.class)) {
            if (!config.element().getPropertyAsString("HTTPSampler.domain").isBlank()) { // $NON-NLS-1$
                return;
            }
        }
        findings.add(CheckContext.finding(id(), sampler, "server", Severity.BLOCKER,
                "'" + sampler.name() + "' has no server name, and no HTTP Request Defaults provide one", List.of()));
    }

    private int checkFile(PlanNode node, String what, String filename, boolean required, List<Finding> findings) {
        String name = filename == null ? "" : filename.trim();
        if (name.isEmpty()) {
            if (required) {
                findings.add(CheckContext.finding(id(), node, "file", Severity.BLOCKER,
                        "'" + node.name() + "' has no " + what + " configured", List.of()));
            }
            return 0;
        }
        if (name.contains("${")) {
            findings.add(CheckContext.finding(id(), node, name, Severity.NOT_VERIFIED,
                    "The " + what + " of '" + node.name() + "' is computed at run time: " + name, List.of()));
            return 0;
        }
        if (name.startsWith(ARCHIVE_PREFIX)) {
            return checkArchive(node, name.substring(ARCHIVE_PREFIX.length()), findings);
        }
        File file;
        try {
            file = FileServer.getFileServer().resolveFile(name);
        } catch (IllegalArgumentException e) {
            findings.add(CheckContext.finding(id(), node, name, Severity.BLOCKER,
                    "The " + what + " of '" + node.name() + "' cannot be resolved: " + name,
                    List.of(String.valueOf(e.getMessage()))));
            return 0;
        }
        if (!file.isFile() || !file.canRead()) {
            findings.add(CheckContext.finding(id(), node, name, Severity.BLOCKER,
                    "The " + what + " of '" + node.name() + "' does not exist or is not readable: " + name,
                    List.of("Resolved to " + file.getPath())));
            return 0;
        }
        return 1;
    }

    private int checkArchive(PlanNode node, String entry, List<Finding> findings) {
        try {
            ArchiveFiles.resolve(entry);
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            findings.add(CheckContext.finding(id(), node, entry, Severity.BLOCKER,
                    "Archive entry '" + entry + "' of '" + node.name() + "' is missing", List.of(
                            String.valueOf(e.getMessage()))));
            return 0;
        }
    }
}
