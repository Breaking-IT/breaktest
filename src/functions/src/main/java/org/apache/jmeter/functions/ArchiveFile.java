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

package org.apache.jmeter.functions;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.save.ArchiveFiles;

/** Returns a local path for a shared file in the current JMX archive. */
public class ArchiveFile extends AbstractFunction {
    private CompoundVariable filename;

    @Override
    public String execute(SampleResult previousResult, Sampler currentSampler) throws InvalidVariableException {
        try {
            return ArchiveFiles.resolve(filename.execute()).toString();
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidVariableException(e.getMessage());
        }
    }

    @Override
    public void setParameters(Collection<CompoundVariable> parameters) throws InvalidVariableException {
        checkParameterCount(parameters, 1);
        filename = parameters.iterator().next();
    }

    @Override
    public String getReferenceKey() {
        return "__archiveFile";
    }

    @Override
    public List<String> getArgumentDesc() {
        return List.of("Filename in the archive files directory");
    }
}
