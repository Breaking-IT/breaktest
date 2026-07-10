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

package org.apache.jmeter.recording;

/** Controls how much request and response evidence is stored in a JMX archive. */
public enum RecordingStorageMode {
    /** Store complete request and response data for every sampler. */
    ALL,
    /** Store every exchange, but omit request and response bodies for static resources. */
    OMIT_STATIC_BODIES,
    /** Store only exchanges for non-static resources. */
    OMIT_STATICS,
    /** Do not store exchanges. */
    NONE
}
