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

import java.awt.event.ActionEvent;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

import javax.swing.JOptionPane;
import javax.swing.UIManager;

import org.apache.jmeter.ai.gui.AiAutoScriptingLogWindow;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.util.JMeterMenuBar;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JFactory;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.SystemInfo;
import com.google.auto.service.AutoService;

/**
 * Implements the Look and Feel menu item.
 *
 * <p>BreakTest ships two FlatLaf based themes: Light and Dark. The legacy Swing
 * look and feels (Metal, Nimbus, System, ...) can be re-enabled with the
 * {@code breaktest.laf.legacy=true} property.</p>
 */
@AutoService(Command.class)
public class LookAndFeelCommand extends AbstractAction {
    private static final String JMETER_LAF = "jmeter.laf"; // $NON-NLS-1$

    /** Property enabling the legacy Swing look and feels in the menu. */
    private static final String LAF_LEGACY = "breaktest.laf.legacy"; // $NON-NLS-1$

    /** Pre-rename property name, still honored. */
    private static final String LAF_LEGACY_OLD_NAME = "jmeter.laf.legacy"; // $NON-NLS-1$

    /** Command activating the light theme. */
    public static final String LAF_LIGHT = ActionNames.LAF_PREFIX + "light"; // $NON-NLS-1$

    /** Command activating the dark theme. */
    public static final String LAF_DARK = ActionNames.LAF_PREFIX + "dark"; // $NON-NLS-1$

    private static final Map<String, MenuItem> items = new LinkedHashMap<>();

    private static final Preferences PREFS = Preferences.userNodeForPackage(LookAndFeelCommand.class);
    // Note: Windows user preferences are stored relative to: HKEY_CURRENT_USER\Software\JavaSoft\Prefs

    /**
     * BreakTest-specific user preference key.
     * <p>The preference node is shared with Apache JMeter installations on the same machine
     * (same package name), and vanilla JMeter crashes on startup when it reads a LaF command
     * it does not know. So BreakTest stores its theme under its own key and never writes to
     * JMeter's {@code laf.command} key.</p>
     */
    private static final String USER_PREFS_KEY = "breaktest.laf.command"; //$NON-NLS-1$

    /** Preference key used by Apache JMeter; only read for migration, never written. */
    private static final String JMETER_USER_PREFS_KEY = "laf.command"; //$NON-NLS-1$

    public static class MenuItem {
        final String title;
        final String command;
        final String lafClassName;

        private MenuItem(String title, String command, String lafClassName) {
            this.title = title;
            this.command = command;
            this.lafClassName = lafClassName;
        }

        public String getTitle() {
            return title;
        }

        public String getCommand() {
            return command;
        }

        private static MenuItem of(String title, String command, String lafClassName) {
            return new MenuItem(title, command, lafClassName);
        }
    }

    static {
        // Loads org/apache/jmeter/gui/FlatLaf.properties with BreakTest UI customizations
        FlatLaf.registerCustomDefaultsSource("org.apache.jmeter.gui"); // $NON-NLS-1$

        boolean macOS = SystemInfo.isMacOS;
        items.put(LAF_LIGHT, MenuItem.of("Light", LAF_LIGHT,
                macOS ? FlatMacLightLaf.class.getName() : FlatLightLaf.class.getName()));
        items.put(LAF_DARK, MenuItem.of("Dark", LAF_DARK,
                macOS ? FlatMacDarkLaf.class.getName() : FlatDarkLaf.class.getName()));

        if (JMeterUtils.getPropDefault(LAF_LEGACY, JMeterUtils.getPropDefault(LAF_LEGACY_OLD_NAME, false))) {
            for (UIManager.LookAndFeelInfo laf : JMeterMenuBar.getAllLAFs()) {
                String command = ActionNames.LAF_PREFIX + laf.getClassName();
                items.putIfAbsent(command, MenuItem.of(laf.getName(), command, laf.getClassName()));
            }
        }

        repairSharedJMeterPreference();
    }

    /**
     * Early BreakTest builds wrote {@code laf:light}/{@code laf:dark} into the {@code laf.command}
     * preference key shared with Apache JMeter, which makes vanilla JMeter installations crash on
     * startup (NPE on an unknown LaF command). Move such a value to the BreakTest key and remove it
     * from the shared key, restoring JMeter's default behavior. Values written by JMeter itself are
     * left untouched.
     */
    private static void repairSharedJMeterPreference() {
        String sharedValue = PREFS.get(JMETER_USER_PREFS_KEY, null);
        if (LAF_LIGHT.equals(sharedValue) || LAF_DARK.equals(sharedValue)) {
            if (PREFS.get(USER_PREFS_KEY, null) == null) {
                PREFS.put(USER_PREFS_KEY, sharedValue);
            }
            PREFS.remove(JMETER_USER_PREFS_KEY);
        }
    }

    public static Collection<MenuItem> getMenuItems() {
        return Collections.unmodifiableCollection(items.values());
    }

    /**
     * Get LookAndFeel classname from the following properties:
     * <ul>
     * <li>User preferences key: "laf"</li>
     * <li>jmeter.laf.&lt;os.name&gt; - lowercased; spaces replaced by '_'</li>
     * <li>jmeter.laf.&lt;os.family&gt; - lowercased.</li>
     * <li>jmeter.laf</li>
     * <li>UIManager.getCrossPlatformLookAndFeelClassName()</li>
     * </ul>
     * @return LAF classname
     * @deprecated see #getPreferredLafCommand
     * @see #getPreferredLafCommand
     */
    @Deprecated
    public static String getJMeterLaf() {
        MenuItem item = items.get(getPreferredLafCommand());
        if (item != null) {
            return item.lafClassName;
        }
        return items.get(LAF_LIGHT).lafClassName;
    }

    /**
     * Returns a command that would activate the preferred LaF.
     * @return command that would activate the preferred LaF
     */
    public static String getPreferredLafCommand() {
        String laf = PREFS.get(USER_PREFS_KEY, null);
        if (laf == null) {
            // Migrate the theme previously chosen in Apache JMeter or an older BreakTest
            // (read-only: the shared key stays intact for the JMeter installation)
            laf = PREFS.get(JMETER_USER_PREFS_KEY, null);
        }
        if (laf == null) {
            laf = lafCommandFromProperties();
        }
        String command = migrateLafCommand(laf);
        return items.containsKey(command) ? command : LAF_LIGHT;
    }

    /**
     * Derives a LaF command from the {@code jmeter.laf*} properties, using the
     * historic lookup order: {@code jmeter.laf.<os.name>}, {@code jmeter.laf.<os.family>},
     * {@code jmeter.laf}.
     */
    private static String lafCommandFromProperties() {
        String osName = System.getProperty("os.name") // $NON-NLS-1$
                .toLowerCase(Locale.ENGLISH);
        // Spaces are not allowed in property names read from files
        String laf = JMeterUtils.getProperty(JMETER_LAF + "." + osName.replace(' ', '_'));
        if (laf != null) {
            return toLafCommand(laf);
        }
        String[] osFamily = osName.split("\\s"); // e.g. windows xp => windows
        laf = JMeterUtils.getProperty(JMETER_LAF + "." + osFamily[0]);
        if (laf != null) {
            return toLafCommand(laf);
        }
        laf = JMeterUtils.getProperty(JMETER_LAF);
        if (laf != null) {
            return toLafCommand(laf);
        }
        return LAF_LIGHT;
    }

    /** Converts a jmeter.laf property value (theme name, LaF alias or class name) to a command. */
    private static String toLafCommand(String laf) {
        if ("light".equalsIgnoreCase(laf)) { // $NON-NLS-1$
            return LAF_LIGHT;
        }
        if ("dark".equalsIgnoreCase(laf)) { // $NON-NLS-1$
            return LAF_DARK;
        }
        if (JMeterMenuBar.SYSTEM_LAF.equalsIgnoreCase(laf)) {
            return ActionNames.LAF_PREFIX + UIManager.getSystemLookAndFeelClassName();
        }
        if (JMeterMenuBar.CROSS_PLATFORM_LAF.equalsIgnoreCase(laf)) {
            return ActionNames.LAF_PREFIX + UIManager.getCrossPlatformLookAndFeelClassName();
        }
        return ActionNames.LAF_PREFIX + laf;
    }

    /** Maps commands stored by previous versions (Darklaf themes, Darcula, ...) to Light or Dark. */
    private static String migrateLafCommand(String command) {
        if (command == null) {
            return LAF_LIGHT;
        }
        if (items.containsKey(command)) {
            return command;
        }
        if (command.contains("darklaf") || command.contains("Darcula")) { // $NON-NLS-1$ $NON-NLS-2$
            return isOldDarkTheme(command) ? LAF_DARK : LAF_LIGHT;
        }
        return command;
    }

    private static boolean isOldDarkTheme(String command) {
        return command.contains("DarculaTheme") // $NON-NLS-1$
                || command.contains("OneDarkTheme") // $NON-NLS-1$
                || command.contains("HighContrastDarkTheme") // $NON-NLS-1$
                || command.contains("SolarizedDarkTheme") // $NON-NLS-1$
                || command.endsWith("darcula.DarculaLaf"); // $NON-NLS-1$
    }

    public LookAndFeelCommand() {
        // NOOP
    }

    /**
     * @return true when Darklaf is the current look and feel
     * @deprecated Darklaf is no longer shipped with BreakTest; always returns false
     */
    @Deprecated
    public static boolean isDarklafTheme() {
        return false;
    }

    /**
     * @return true when the current look and feel is a dark theme
     */
    public static boolean isDark() {
        return UIManager.getLookAndFeel() instanceof FlatLaf laf && laf.isDark();
    }

    public static void activateLookAndFeel(String command) {
        MenuItem item = items.get(command);
        if (item == null) {
            throw new IllegalArgumentException("Unknown look and feel command: " + command);
        }
        GuiPackage instance = GuiPackage.getInstance();
        // No windows exist during startup, so skip the transition animation
        boolean animate = instance != null;
        if (instance != null) {
            instance.updateUIForHiddenComponents();
        }
        if (animate) {
            FlatAnimatedLafChange.showSnapshot();
        }
        JFactory.refreshUI(item.lafClassName);
        if (instance != null && instance.getLoggerPanel() != null) {
            instance.getLoggerPanel().refreshUi();
        }
        AiAutoScriptingLogWindow.refreshUi();
        if (animate) {
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        }
        PREFS.put(USER_PREFS_KEY, item.command);
    }

    @Override
    public void doAction(ActionEvent ev) {
        try {
            boolean wasFlat = UIManager.getLookAndFeel() instanceof FlatLaf;
            activateLookAndFeel(ev.getActionCommand());
            boolean nowFlat = UIManager.getLookAndFeel() instanceof FlatLaf;
            if (wasFlat && nowFlat) {
                // FlatLaf themes switch fully at runtime, no restart needed
                return;
            }
            int chosenOption = JOptionPane.showConfirmDialog(GuiPackage.getInstance().getMainFrame(), JMeterUtils
                    .getResString("laf_quit_after_change"), // $NON-NLS-1$
                    JMeterUtils.getResString("exit"), // $NON-NLS-1$
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (chosenOption == JOptionPane.YES_OPTION) {
                ActionRouter.getInstance().doActionNow(new ActionEvent(ev.getSource(), ev.getID(), ActionNames.RESTART));
            }
        } catch (IllegalArgumentException e) {
            JMeterUtils.reportErrorToUser(e.getMessage(), e);
        }
    }

    @Override
    public Set<String> getActionNames() {
        return Collections.unmodifiableSet(items.keySet());
    }
}
