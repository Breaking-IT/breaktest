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

package org.apache.jmeter.gui.util;

import java.awt.Desktop;
import java.awt.desktop.ScreenSleepEvent;
import java.awt.desktop.ScreenSleepListener;
import java.awt.desktop.SystemSleepEvent;
import java.awt.desktop.SystemSleepListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Requests a complete redraw when a window's drawing surface may have been lost during sleep.
 */
public final class WindowRepaintRecovery extends WindowAdapter
        implements ScreenSleepListener, SystemSleepListener {
    private final JFrame frame;
    private final Timer delayedRepaint;
    private Desktop desktop;
    private boolean closed;

    private WindowRepaintRecovery(JFrame frame) {
        this.frame = frame;
        // The native display surface may still be recovering when the wake event arrives.
        delayedRepaint = new Timer(500, event -> repaint());
        delayedRepaint.setRepeats(false);
    }

    /**
     * Installs wake and restore recovery for the lifetime of the frame.
     *
     * @param frame the frame to redraw
     */
    public static void install(JFrame frame) {
        WindowRepaintRecovery recovery = new WindowRepaintRecovery(frame);
        frame.addWindowListener(recovery);
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)
                    || desktop.isSupported(Desktop.Action.APP_EVENT_SCREEN_SLEEP)) {
                desktop.addAppEventListener(recovery);
                recovery.desktop = desktop;
            }
        }
    }

    private void requestRepaint() {
        // Desktop callbacks are not assumed to run on Swing's event dispatch thread.
        SwingUtilities.invokeLater(() -> {
            if (!closed) {
                repaint();
                // Coalesce overlapping restore, display-wake and system-wake notifications.
                delayedRepaint.restart();
            }
        });
    }

    private void repaint() {
        if (!closed && frame.isShowing()) {
            frame.repaint();
        }
    }

    @Override
    public void windowDeiconified(WindowEvent event) {
        requestRepaint();
    }

    @Override
    public void systemAwoke(SystemSleepEvent event) {
        requestRepaint();
    }

    @Override
    public void screenAwoke(ScreenSleepEvent event) {
        requestRepaint();
    }

    @Override
    public void systemAboutToSleep(SystemSleepEvent event) {
        // Recovery is only needed after waking.
    }

    @Override
    public void screenAboutToSleep(ScreenSleepEvent event) {
        // Recovery is only needed after waking.
    }

    @Override
    public void windowClosed(WindowEvent event) {
        closed = true;
        delayedRepaint.stop();
        if (desktop != null) {
            desktop.removeAppEventListener(this);
        }
        frame.removeWindowListener(this);
    }
}
