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

package org.apache.jmeter.threads.openmodel.gui

import org.apache.jmeter.gui.TestElementMetadata
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.threads.gui.ThreadGroupGui
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup
import org.apiguardian.api.API

@API(status = API.Status.EXPERIMENTAL, since = "5.5")
@TestElementMetadata(labelResource = "threadgroup", actionGroups = [""])
public class OpenModelThreadGroupGui : ThreadGroupGui() {
    override fun getLabelResource(): String = "threadgroup"

    override fun makeTestElement(): TestElement = OpenModelThreadGroup()
}
