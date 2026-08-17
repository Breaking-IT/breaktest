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

package org.apache.jmeter.samplers;

/**
 * Declares optional metadata that a sample-result consumer needs before it is notified.
 * Consumers should request only metadata they use since preparing it adds work to the sampling
 * thread.
 *
 * @since 2026.08
 */
public interface SampleResultMetadataConsumer {

    /**
     * Whether this consumer needs a snapshot of JMeter variables attached to each result.
     *
     * @return {@code true} when variable snapshots are required
     */
    default boolean needsJMeterVariables() {
        return false;
    }

    /**
     * Whether this consumer needs the configured test-element path that produced each result.
     *
     * @return {@code true} when source test-element paths are required
     */
    default boolean needsSourceTestElementPath() {
        return false;
    }
}
