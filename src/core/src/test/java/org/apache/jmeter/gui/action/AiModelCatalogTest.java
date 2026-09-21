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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AiModelCatalogTest {
    @Test
    void parsesPiProviderQualifiedIdsWithoutHeadersOrWarnings() {
        assertEquals(List.of("openrouter/deepseek/flash", "omlx/local-model"), AiModelCatalog.parseOutput("pi", """
                provider model context max-out thinking images
                openrouter deepseek/flash 1M 64K yes no
                omlx local-model 32K 8K no no
                openrouter deepseek/flash 1M 64K yes no
                Warning: cannot load optional extension
                """));
    }

    @Test
    void parsesOtherCliFormatsAndStripsColor() {
        assertEquals(List.of("openrouter/deepseek/flash"), AiModelCatalog.parseOutput("opencode",
                "\033[32mopenrouter/deepseek/flash\033[0m\nWarning: catalog issue\n"));
        assertEquals(List.of("auto", "model-low"), AiModelCatalog.parseOutput("cursor", """
                Available models
                auto - Auto (default)
                model-low - A Model Low
                Tip: use --model <id>
                """));
    }

    @Test
    void codexCacheExcludesHiddenModelsAndDeduplicates() throws Exception {
        assertEquals(List.of("visible-model"), AiModelCatalog.parseCodexCache("""
                {"models":[{"slug":"visible-model","visibility":"list"},
                {"slug":"hidden-model","visibility":"hide"},{"slug":"visible-model"}]}
                """));
    }

    @Test
    @Timeout(10)
    void catalogCommandClosesStdinAndReturnsOutput() throws Exception {
        assertEquals("model-id", AiModelCatalog.query(fixtureCommand("output"), new File("."), 3));
    }

    @Test
    @Timeout(10)
    void catalogCommandTimesOutInsteadOfBlockingDialogForever() {
        long start = System.nanoTime();
        assertThrows(Exception.class, () -> AiModelCatalog.query(fixtureCommand("sleep"), new File("."), 1));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
    }

    private static List<String> fixtureCommand(String action) throws Exception {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of(CatalogProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                CatalogProcess.class.getName(), action);
    }

    public static class CatalogProcess {
        public static void main(String[] args) throws Exception {
            if ("sleep".equals(args[0])) {
                Thread.sleep(30000);
            } else {
                System.in.readAllBytes();
                System.out.print("model-id");
            }
        }
    }
}
