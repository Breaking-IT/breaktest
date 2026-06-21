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

package org.apache.jmeter.ai

import java.time.Duration

/**
 * Execution limits for agent-driven validation runs.
 */
public data class AgentRunOptions(
    val timeout: Duration = Duration.ofSeconds(30),
    val validationRun: Boolean = true,
    val ignoreTimers: Boolean = true,
    val responseBodyLimit: Int = 32 * 1024,
    val requestBodyLimit: Int = 16 * 1024,
    val maxSamples: Int? = null,
    val stopOnFirstFailure: Boolean = false,
)
