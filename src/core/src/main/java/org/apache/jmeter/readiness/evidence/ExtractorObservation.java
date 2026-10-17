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

package org.apache.jmeter.readiness.evidence;

/**
 * One execution of a post-processor during a readiness validation run.
 *
 * @param extractorId source id of the post-processor
 * @param samplerId source id of the sampler whose result it processed
 * @param thread thread name
 * @param iteration iteration of the thread
 * @param matched whether a supported extractor matched; {@code null} when the post-processor does not report its
 *     outcome (unsupported or ended before matching)
 * @param nanos processing time, response decoding excluded
 * @param warmUp whether this was the first invocation of the post-processor in the run
 * @param expression expression of the extractor, for evidence
 * @param excerpt redacted response excerpt around the expected area, empty when unavailable
 */
public record ExtractorObservation(String extractorId, String samplerId, String thread, int iteration,
        Boolean matched, long nanos, boolean warmUp, String expression, String excerpt) {
}
