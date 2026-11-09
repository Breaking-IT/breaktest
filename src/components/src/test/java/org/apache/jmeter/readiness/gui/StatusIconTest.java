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

package org.apache.jmeter.readiness.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.List;

import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckResult;
import org.apache.jmeter.readiness.CheckState;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.ReadinessVerdict;
import org.apache.jmeter.readiness.Severity;
import org.junit.jupiter.api.Test;

class StatusIconTest {
    private static final ElementRef REF = new ElementRef("1:1", "x", "X", List.of());

    @Test
    void statesSeveritiesAndVerdictsMapToDistinctIcons() {
        assertEquals(StatusIcon.Kind.PASSED, StatusIcon.kindOf(CheckState.PASSED));
        assertEquals(StatusIcon.Kind.FAILED, StatusIcon.kindOf(CheckState.BLOCKER));
        assertEquals(StatusIcon.Kind.WARNING, StatusIcon.kindOf(CheckState.WARNING));
        assertEquals(StatusIcon.Kind.REVIEW, StatusIcon.kindOf(CheckState.REVIEW));
        assertEquals(StatusIcon.Kind.NOT_VERIFIED, StatusIcon.kindOf(CheckState.NOT_VERIFIED));
        assertEquals(StatusIcon.Kind.ACCEPTED, StatusIcon.kindOf(CheckState.ACCEPTED));
        assertEquals(StatusIcon.Kind.PASSED, StatusIcon.kindOf(ReadinessVerdict.READY));
        assertEquals(StatusIcon.Kind.FAILED, StatusIcon.kindOf(ReadinessVerdict.NEEDS_ATTENTION));
        assertEquals(StatusIcon.Kind.NOT_VERIFIED, StatusIcon.kindOf(ReadinessVerdict.NOT_VERIFIED));
        Finding warning = new Finding(CheckId.UNUSED_EXTRACTIONS, REF, "k", Severity.WARNING, "", List.of(), null);
        assertEquals(StatusIcon.Kind.WARNING, StatusIcon.kindOf(warning));
        assertEquals(StatusIcon.Kind.ACCEPTED, StatusIcon.kindOf(warning.withException(
                new AcceptedException(CheckId.UNUSED_EXTRACTIONS, "k", "ok", "", ""))));
        CheckResult stale = CheckResult.of(CheckId.VALIDATION, List.of(), true, "").asStale("changed");
        assertEquals(StatusIcon.Kind.NOT_VERIFIED, StatusIcon.kindOf(stale));
    }

    @Test
    void groupsShowTheirWorstItem() {
        assertEquals(StatusIcon.Kind.FAILED, StatusIcon.worst(List.of(StatusIcon.Kind.PASSED,
                StatusIcon.Kind.FAILED, StatusIcon.Kind.WARNING)));
        assertEquals(StatusIcon.Kind.WARNING, StatusIcon.worst(List.of(StatusIcon.Kind.ACCEPTED,
                StatusIcon.Kind.WARNING)));
        assertEquals(StatusIcon.Kind.PASSED, StatusIcon.worst(List.of()));
    }

    @Test
    void everyIconPaintsInItsColour() {
        for (StatusIcon.Kind kind : EnumSet.allOf(StatusIcon.Kind.class)) {
            for (int size : new int[]{StatusIcon.SMALL, StatusIcon.LARGE}) {
                StatusIcon icon = new StatusIcon(kind, size);
                BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                icon.paintIcon(null, g, 0, 0);
                g.dispose();
                boolean painted = false;
                for (int x = 0; x < size && !painted; x++) {
                    for (int y = 0; y < size && !painted; y++) {
                        painted = (image.getRGB(x, y) >>> 24) != 0;
                    }
                }
                assertEquals(true, painted, kind + " at " + size);
            }
        }
        BufferedImage failed = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = failed.createGraphics();
        StatusIcon.of(StatusIcon.Kind.FAILED).paintIcon(null, g, 0, 0);
        g.dispose();
        // Near the rim of the filled circle: the failure colour, not the white cross
        assertEquals(StatusIcon.color(StatusIcon.Kind.FAILED).getRGB(), failed.getRGB(8, 3) | 0xFF000000);
        assertNotEquals(StatusIcon.color(StatusIcon.Kind.PASSED), StatusIcon.color(StatusIcon.Kind.FAILED));
        assertNotEquals(Color.WHITE.getRGB(), failed.getRGB(8, 3));
    }
}
