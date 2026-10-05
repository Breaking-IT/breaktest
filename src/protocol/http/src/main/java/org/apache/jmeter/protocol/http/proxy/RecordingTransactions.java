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

package org.apache.jmeter.protocol.http.proxy;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/** Resolve captured grouping settings before host/failure selection can remove boundary evidence. */
final class RecordingTransactions {
    private RecordingTransactions() { }

    static void assign(List<RecordedSampler> samples) {
        if (samples.stream().allMatch(sample -> !sample.entry.getTransactionId().isBlank())) {
            return;
        }
        Map<JMeterTreeNode, Boundary> targets = new LinkedHashMap<>();
        samples.stream().sorted(Comparator.comparingDouble((RecordedSampler sample) -> sample.entry.getStartMs())
                .thenComparingLong(RecordedSampler::sequence)).forEach(sample -> {
                    Boundary boundary = targets.computeIfAbsent(sample.target, ignored -> new Boundary());
                    if (!sample.prefix.equals(boundary.prefix)
                            || sample.entry.getStartMs() - boundary.end > (double) sample.transactionGapMillis) {
                        boundary.number++;
                    }
                    sample.entry.setTransactionId("proxy-" + boundary.number);
                    sample.entry.setTransactionName(sample.prefix.isBlank() ? "Transaction " + boundary.number : sample.prefix);
                    boundary.prefix = sample.prefix;
                    boundary.end = Math.max(boundary.end, sample.entry.getEndMs());
                });
    }

    private static final class Boundary {
        private String prefix;
        private double end;
        private int number;
    }
}
