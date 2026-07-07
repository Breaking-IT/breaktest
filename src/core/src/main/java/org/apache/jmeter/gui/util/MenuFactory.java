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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.RenderingHints;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.Icon;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.tree.DefaultMutableTreeNode;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.UndoHistory;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.KeyStrokes;
import org.apache.jmeter.gui.menu.StaticJMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.NonTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.Printable;
import org.apache.jorphan.gui.GuiUtils;
import org.apache.jorphan.reflect.ClassFinder;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MenuFactory {
    private static final Logger log = LoggerFactory.getLogger(MenuFactory.class);

    /*
     *  Predefined strings for makeMenu().
     *  These are used as menu categories in the menuMap HashMap,
     *  and also for resource lookup in messages.properties
     *  TODO: why isn't this an enum?
    */
    public static final String THREADS = "menu_threads"; //$NON-NLS-1$
    public static final String FRAGMENTS = "menu_fragments"; //$NON-NLS-1$
    public static final String TIMERS = "menu_timer"; //$NON-NLS-1$
    public static final String CONTROLLERS = "menu_logic_controller"; //$NON-NLS-1$
    public static final String SAMPLERS = "menu_generative_controller"; //$NON-NLS-1$
    public static final String CONFIG_ELEMENTS = "menu_config_element"; //$NON-NLS-1$
    public static final String POST_PROCESSORS = "menu_post_processors"; //$NON-NLS-1$
    public static final String PRE_PROCESSORS = "menu_pre_processors"; //$NON-NLS-1$
    public static final String ASSERTIONS = "menu_assertions"; //$NON-NLS-1$
    public static final String NON_TEST_ELEMENTS = "menu_non_test_elements"; //$NON-NLS-1$
    public static final String LISTENERS = "menu_listener"; //$NON-NLS-1$
    public static final String SEPARATOR = "menu_separator"; //$NON-NLS-1$

    private static final Map<String, List<MenuInfo>> menuMap;

    static {
        menuMap = new HashMap<>();
        menuMap.put(THREADS, new ArrayList<>());
        menuMap.put(TIMERS, new ArrayList<>());
        menuMap.put(ASSERTIONS, new ArrayList<>());
        menuMap.put(CONFIG_ELEMENTS, new ArrayList<>());
        menuMap.put(CONTROLLERS, new ArrayList<>());
        menuMap.put(LISTENERS, new ArrayList<>());
        menuMap.put(NON_TEST_ELEMENTS, new ArrayList<>());
        menuMap.put(SAMPLERS, new ArrayList<>());
        menuMap.put(POST_PROCESSORS, new ArrayList<>());
        menuMap.put(PRE_PROCESSORS, new ArrayList<>());
        menuMap.put(FRAGMENTS, new ArrayList<>());
        menuMap.put(SEPARATOR, Collections.singletonList(new MenuSeparatorInfo()));

        try {
            initializeMenus(menuMap, classesToSkip());
            sortMenus(menuMap.values());
            separateItemsWithExplicitOrder(menuMap.values());
        } catch (Error | RuntimeException ex) { // NOSONAR We want to log Errors in jmeter.log
            log.error("Error initializing menus, check configuration if using 3rd party libraries", ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Error initializing menus, check configuration if using 3rd party libraries", ex);
        }
    }

    @VisibleForTesting
    static Map<String, List<MenuInfo>> getMenuMap() {
        return menuMap;
    }

    private static Set<String> classesToSkip() {
        return Arrays.stream(JMeterUtils.getPropDefault("not_in_menu", "").split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    private static void initializeMenus(
            Map<String, List<MenuInfo>> menus, Set<String> elementsToSkip) {
        try {
            // TODO: migrate to ServiceLoader or something else
            @SuppressWarnings("deprecation")
            List<String> guiClasses = ClassFinder
                    .findClassesThatExtend(
                            JMeterUtils.getSearchPaths(),
                            new Class[] {JMeterGUIComponent.class, TestBean.class})
                    .stream()
                    // JMeterTreeNode and TestBeanGUI are special GUI classes,
                    // and aren't intended to be added to menus
                    .filter(name -> !name.endsWith("JMeterTreeNode"))
                    .filter(name -> !name.endsWith("TestBeanGUI"))
                    .filter(name -> !name.equals("org.apache.jmeter.gui.menu.StaticJMeterGUIComponent"))
                    .filter(name -> !elementsToSkip.contains(name))
                    .distinct()
                    .map(String::trim)
                    .collect(Collectors.toList());

            boolean debugTimings = log.isDebugEnabled();
            Map<String, Long> times = debugTimings ? new HashMap<>() : null;
            Map<String, JMeterGUIComponent> comps = debugTimings ? new HashMap<>() : null;
            long a0 = System.currentTimeMillis();
            for (String className : guiClasses) {
                long t0 = 0;
                if (debugTimings) {
                    t0 = System.currentTimeMillis();
                }
                JMeterGUIComponent item = getGUIComponent(className, elementsToSkip);
                if (debugTimings) {
                    long t1 = System.currentTimeMillis();
                    times.put(className, t1 - t0);
                    comps.put(className, item);
                }
                if (item == null) {
                    continue;
                }

                Collection<String> categories = item.getMenuCategories();
                if (categories == null) {
                    log.debug("{} participates in no menus.", className);
                    continue;
                }
                for (Map.Entry<String, List<MenuInfo>> entry: menus.entrySet()) {
                    if (categories.contains(entry.getKey())) {
                        entry.getValue().add(new MenuInfo(item, className));
                    }
                }
            }
            if (debugTimings) {
                long a1 = System.currentTimeMillis();
                times.entrySet().stream()
                        .sorted(Comparator.comparingLong(Map.Entry::getValue))
                        .forEachOrdered(e -> {
                            String res = "";
                            JMeterGUIComponent comp = comps.get(e.getKey());
                            if (comp != null && comp.getLabelResource() != null) {
                                res = " @TestElementMetadata(labelResource = \""
                                        + comp.getLabelResource() + "\")";
                            }
                            log.debug("{}ms {} {}", e.getValue(), e.getKey(), res);
                        });
                log.debug("{}ms total menu initialization time", a1 - a0);
            }
        } catch (IOException e) {
            log.error("IO Exception while initializing menus.", e);
        }
    }

    private static JMeterGUIComponent getGUIComponent(
            String name, Set<String> elementsToSkip) {
        JMeterGUIComponent item = null;
        boolean hideBean = false; // Should the TestBean be hidden?
        try {
            Class<?> c = Class.forName(name, false, MenuFactory.class.getClassLoader());
            TestElementMetadata metadata = c.getAnnotation(TestElementMetadata.class);
            if (metadata != null) {
                item = new StaticJMeterGUIComponent(c, metadata);
            } else if (TestBean.class.isAssignableFrom(c)) {
                TestBeanGUI testBeanGUI = new TestBeanGUI(c);
                hideBean = testBeanGUI.isHidden()
                        || (testBeanGUI.isExpert() && !JMeterUtils.isExpertMode());
                item = testBeanGUI;
            } else {
                item = (JMeterGUIComponent) c.getDeclaredConstructor().newInstance();
            }
        } catch (NoClassDefFoundError e) {
            log.warn("Configuration error, probably corrupt or missing third party library(jar)? Could not create class: {}.",
                    name, e);
        } catch (HeadlessException e) {
            log.warn("Could not instantiate class: {}", name, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not instantiate class: {}", name, e);
        }
        if (hideBean || (item != null && elementsToSkip.contains(item.getStaticLabel()))) {
            log.info("Skipping {}", name);
            item = null;
        }
        return item;
    }

    private static void sortMenus(Collection<? extends List<MenuInfo>> menus) {
        for (List<MenuInfo> menu : menus) {
            menu.sort(Comparator.comparing(MenuInfo::getLabel));
            menu.sort(Comparator.comparingInt(MenuInfo::getSortOrder));
        }
    }

    private static void separateItemsWithExplicitOrder(Collection<? extends List<MenuInfo>> menus) {
        for (List<MenuInfo> menu : menus) {
            Optional<MenuInfo> firstDefaultSortItem = menu.stream()
                    .filter(info -> info.getSortOrder() == MenuInfo.SORT_ORDER_DEFAULT)
                    .findFirst();
            int index = menu.indexOf(firstDefaultSortItem.orElseThrow(IllegalStateException::new));
            if (index > 0) {
                menu.add(index, new MenuSeparatorInfo());
            }
        }
    }

    /**
     * Private constructor to prevent instantiation.
     */
    private MenuFactory() {
    }

    public static void addEditMenu(JPopupMenu menu, boolean removable) {
        addSeparator(menu);
        if (removable) {
            menu.add(makeMenuItemRes("cut", ActionNames.CUT, KeyStrokes.CUT)); //$NON-NLS-1$
        }
        menu.add(makeMenuItemRes("copy", ActionNames.COPY, KeyStrokes.COPY));  //$NON-NLS-1$
        menu.add(makeMenuItemRes("paste", ActionNames.PASTE, KeyStrokes.PASTE)); //$NON-NLS-1$
        menu.add(makeMenuItemRes("duplicate", ActionNames.DUPLICATE, KeyStrokes.DUPLICATE));  //$NON-NLS-1$
        if (removable) {
            menu.add(makeMenuItemRes("remove", ActionNames.REMOVE, KeyStrokes.REMOVE)); //$NON-NLS-1$
        }
    }

    public static void addPasteResetMenu(JPopupMenu menu) {
        addSeparator(menu);
        menu.add(makeMenuItemRes("paste", ActionNames.PASTE, KeyStrokes.PASTE)); //$NON-NLS-1$
    }

    public static void addFileMenu(JPopupMenu pop) {
        addFileMenu(pop, true);
    }

    /**
     * @param menu JPopupMenu
     * @param addSaveTestFragmentMenu Add Save as Test Fragment menu if true
     */
    public static void addFileMenu(JPopupMenu menu, boolean addSaveTestFragmentMenu) {
        // the undo/redo as a standard goes first in Edit menus
        if(UndoHistory.isEnabled()) {
            addUndoItems(menu);
        }

        addSeparator(menu);
        menu.add(makeMenuItemRes("open", ActionNames.OPEN));// $NON-NLS-1$
        menu.add(makeMenuItemRes("menu_merge", ActionNames.MERGE));// $NON-NLS-1$
        menu.add(makeMenuItemRes("save_as", ActionNames.SAVE_AS));// $NON-NLS-1$
        if(addSaveTestFragmentMenu) {
            menu.add(makeMenuItemRes("save_as_test_fragment", // $NON-NLS-1$
                    ActionNames.SAVE_AS_TEST_FRAGMENT));
        }
        addSeparator(menu);
        JMenuItem saveKotlinDsl = makeMenuItemRes("copy_code", // $NON-NLS-1$
                ActionNames.COPY_CODE);
        menu.add(saveKotlinDsl);
        JMenuItem savePicture = makeMenuItemRes("save_as_image",// $NON-NLS-1$
                ActionNames.SAVE_GRAPHICS,
                KeyStrokes.SAVE_GRAPHICS);
        menu.add(savePicture);
        if (!(GuiPackage.getInstance().getCurrentGui() instanceof Printable)) {
            savePicture.setEnabled(false);
        }

        JMenuItem savePictureAll = makeMenuItemRes("save_as_image_all",// $NON-NLS-1$
                ActionNames.SAVE_GRAPHICS_ALL,
                KeyStrokes.SAVE_GRAPHICS_ALL);
        menu.add(savePictureAll);

        addSeparator(menu);

        JMenuItem disabled = makeMenuItemRes("disable", ActionNames.DISABLE);// $NON-NLS-1$
        JMenuItem enabled = makeMenuItemRes("enable", ActionNames.ENABLE);// $NON-NLS-1$
        boolean isEnabled = GuiPackage.getInstance().getTreeListener().getCurrentNode().isEnabled();
        disabled.setEnabled(isEnabled);
        enabled.setEnabled(!isEnabled);
        menu.add(enabled);
        menu.add(disabled);
        JMenuItem toggle = makeMenuItemRes("toggle", ActionNames.TOGGLE, KeyStrokes.TOGGLE);// $NON-NLS-1$
        menu.add(toggle);
        addSeparator(menu);
        menu.add(makeMenuItemRes("help", ActionNames.HELP));// $NON-NLS-1$
    }

    /**
     * Add undo / redo to the provided menu
     *
     * @param menu JPopupMenu
     */
    private static void addUndoItems(JPopupMenu menu) {
        addSeparator(menu);

        JMenuItem undo = makeMenuItemRes("undo", ActionNames.UNDO, KeyStrokes.UNDO); //$NON-NLS-1$
        undo.setEnabled(GuiPackage.getInstance().canUndo());
        menu.add(undo);

        JMenuItem redo = makeMenuItemRes("redo", ActionNames.REDO, KeyStrokes.REDO); //$NON-NLS-1$
        // TODO: we could even show some hints on action being undone here
        // if required (by passing those hints into history records)
        redo.setEnabled(GuiPackage.getInstance().canRedo());
        menu.add(redo);
    }


    public static JMenu makeMenus(String[] categories, String label, String actionCommand) {
        JMenu addMenu = new JMenu(label);
        addMenu.setIcon(ModernMenuIcon.fromCategories(categories));
        Arrays.stream(categories)
                .map(category -> makeMenu(category, actionCommand))
                .forEach(addMenu::add);
        GuiUtils.makeScrollableMenu(addMenu);
        return addMenu;
    }

    public static JPopupMenu getDefaultControllerMenu() {
        JPopupMenu pop = new JPopupMenu();
        String addAction = ActionNames.ADD;
        JMenu addMenu = new JMenu(JMeterUtils.getResString("add")); // $NON-NLS-1$
        addMenu.setIcon(ModernMenuIcon.of(ModernMenuIcon.Kind.ADD));
        addMenu.add(MenuFactory.makeMenu(MenuFactory.SAMPLERS, addAction));
        addMenu.addSeparator();
        addMenu.add(MenuFactory.makeMenu(MenuFactory.CONTROLLERS, addAction));
        addMenu.addSeparator();
        pop.add(addDefaultAddMenuToMenu(addMenu, addAction));
        pop.add(MenuFactory.makeMenuItemRes("add_think_times",// $NON-NLS-1$
                ActionNames.ADD_THINK_TIME_BETWEEN_EACH_STEP));

        pop.add(MenuFactory.makeMenuItemRes("apply_naming",// $NON-NLS-1$
                ActionNames.APPLY_NAMING_CONVENTION));

        pop.add(makeMenus(new String[]{CONTROLLERS},
                JMeterUtils.getResString("change_parent"),// $NON-NLS-1$
                ActionNames.CHANGE_PARENT));

        pop.add(makeMenus(new String[]{CONTROLLERS},
                JMeterUtils.getResString("insert_parent"),// $NON-NLS-1$
                ActionNames.ADD_PARENT));
        MenuFactory.addEditMenu(pop, true);
        MenuFactory.addFileMenu(pop);
        return pop;
    }

    @VisibleForTesting
    static JMenu createDefaultAddMenu() {
        String addAction = ActionNames.ADD;
        JMenu addMenu = new JMenu(JMeterUtils.getResString("add")); // $NON-NLS-1$
        addMenu.setIcon(ModernMenuIcon.of(ModernMenuIcon.Kind.ADD));
        addDefaultAddMenuToMenu(addMenu, addAction);
        return addMenu;
    }

    private static JMenu addDefaultAddMenuToMenu(JMenu addMenu, String addAction) {
        addMenu.add(MenuFactory.makeMenu(MenuFactory.ASSERTIONS, addAction));
        addMenu.addSeparator();
        addMenu.add(MenuFactory.makeMenu(MenuFactory.TIMERS, addAction));
        addMenu.addSeparator();
        addMenu.add(MenuFactory.makeMenu(MenuFactory.PRE_PROCESSORS, addAction));
        addMenu.add(MenuFactory.makeMenu(MenuFactory.POST_PROCESSORS, addAction));
        addMenu.addSeparator();
        addMenu.add(MenuFactory.makeMenu(MenuFactory.CONFIG_ELEMENTS, addAction));
        addMenu.add(MenuFactory.makeMenu(MenuFactory.LISTENERS, addAction));
        return addMenu;
    }

    public static JPopupMenu getDefaultSamplerMenu() {
        JPopupMenu pop = new JPopupMenu();
        pop.add(createDefaultAddMenu());
        pop.add(makeMenus(new String[]{CONTROLLERS},
                JMeterUtils.getResString("insert_parent"),// $NON-NLS-1$
                ActionNames.ADD_PARENT));
        MenuFactory.addEditMenu(pop, true);
        MenuFactory.addFileMenu(pop);
        return pop;
    }

    public static JPopupMenu getDefaultConfigElementMenu() {
        return createDefaultPopupMenu();
    }

    public static JPopupMenu getDefaultVisualizerMenu() {
        JPopupMenu pop = new JPopupMenu();
        pop.add(MenuFactory.makeMenuItemRes(
                "clear", ActionNames.CLEAR)); //$NON-NLS-1$
        MenuFactory.addEditMenu(pop, true);
        MenuFactory.addFileMenu(pop);
        return pop;
    }

    public static JPopupMenu getDefaultTimerMenu() {
        return createDefaultPopupMenu();
    }

    public static JPopupMenu getDefaultAssertionMenu() {
        return createDefaultPopupMenu();
    }

    public static JPopupMenu getDefaultExtractorMenu() {
        return createDefaultPopupMenu();
    }

    public static JPopupMenu getDefaultMenu() { // if type is unknown
        return createDefaultPopupMenu();
    }

    private static JPopupMenu createDefaultPopupMenu() {
        JPopupMenu pop = new JPopupMenu();
        MenuFactory.addEditMenu(pop, true);
        MenuFactory.addFileMenu(pop);
        return pop;
    }

    /**
     * Create a menu from a menu category.
     *
     * @param category      predefined string (used as key for menuMap HashMap
     *                      and messages.properties lookup)
     * @param actionCommand predefined string, e.g. {@code }ActionNames.ADD}
     *                      {@link ActionNames}
     * @return the menu
     */
    public static JMenu makeMenu(String category, String actionCommand) {
        return makeMenu(
                menuMap.get(category),
                actionCommand,
                JMeterUtils.getResString(category),
                category);
    }

    /**
     * Create a menu from a collection of items.
     *
     * @param menuInfo      collection of MenuInfo items
     * @param actionCommand predefined string, e.g. ActionNames.ADD
     *                      {@link ActionNames}
     * @param menuName The name of the newly created menu
     * @return the menu
     */
    private static JMenu makeMenu(
            Collection<? extends MenuInfo> menuInfo, String actionCommand, String menuName, String category) {

        JMenu menu = new JMenu(menuName);
        menu.setIcon(ModernMenuIcon.fromCategory(category));
        menuInfo.stream()
                .map(info -> makeMenuItem(info, actionCommand, category))
                .forEach(menu::add);
        GuiUtils.makeScrollableMenu(menu);
        return menu;
    }

    public static void setEnabled(JMenu menu) {
        if (menu.getSubElements().length == 0) {
            menu.setEnabled(false);
        }
    }

    /**
     * Create a single menu item
     *
     * @param label for the MenuItem
     * @param name for the MenuItem
     * @param actionCommand predefined string, e.g. ActionNames.ADD
     *                      {@link ActionNames}
     * @return the menu item
     */
    public static JMenuItem makeMenuItem(String label, String name, String actionCommand) {
        JMenuItem newMenuChoice = new JMenuItem(label);
        newMenuChoice.setName(name);
        newMenuChoice.addActionListener(ActionRouter.getInstance());
        if (actionCommand != null) {
            newMenuChoice.setActionCommand(actionCommand);
        }

        return newMenuChoice;
    }

    /**
     * Create a single menu item from the resource name.
     *
     * @param resource for the MenuItem
     * @param actionCommand predefined string, e.g. ActionNames.ADD
     *                      {@link ActionNames}
     * @return the menu item
     */
    public static JMenuItem makeMenuItemRes(String resource, String actionCommand) {
        JMenuItem newMenuChoice = new JMenuItem(JMeterUtils.getResString(resource));
        newMenuChoice.setName(resource);
        newMenuChoice.addActionListener(ActionRouter.getInstance());
        if (actionCommand != null) {
            newMenuChoice.setActionCommand(actionCommand);
        }

        return newMenuChoice;
    }

    /**
     * Create a single menu item from a MenuInfo object
     *
     * @param info the MenuInfo object
     * @param actionCommand predefined string, e.g. ActionNames.ADD
     *                      {@link ActionNames}
     * @return the menu item
     */
    private static Component makeMenuItem(MenuInfo info, String actionCommand, String category) {
        if (info instanceof MenuSeparatorInfo) {
            return new JPopupMenu.Separator();
        }

        JMenuItem newMenuChoice = new JMenuItem(info.getLabel());
        newMenuChoice.setIcon(ModernMenuIcon.fromDescriptor(category, info.getClassName() + " " + info.getLabel())); // $NON-NLS-1$
        newMenuChoice.setIconTextGap(10);
        newMenuChoice.setName(info.getClassName());
        newMenuChoice.setEnabled(info.getEnabled(actionCommand));
        newMenuChoice.addActionListener(ActionRouter.getInstance());
        if (actionCommand != null) {
            newMenuChoice.setActionCommand(actionCommand);
        }

        return newMenuChoice;
    }

    private static JMenuItem makeMenuItemRes(String resource, String actionCommand, KeyStroke accel) {
        JMenuItem item = makeMenuItemRes(resource, actionCommand);
        item.setAccelerator(accel);
        return item;
    }

    private static void addSeparator(JPopupMenu menu) {
        MenuElement[] elements = menu.getSubElements();
        if ((elements.length > 0)
                && !(elements[elements.length - 1] instanceof JPopupMenu.Separator)) {
            menu.addSeparator();
        }
    }

    /**
     * Determine whether or not nodes can be added to this parent.
     * <p>
     * Used by Merge
     *
     * @param parentNode The {@link JMeterTreeNode} to test, if a new element
     *                   can be added to it
     * @param element    top-level test element to be added
     * @return whether it is OK to add the element to this parent
     */
    public static boolean canAddTo(JMeterTreeNode parentNode, TestElement element) {
        JMeterTreeNode node = new JMeterTreeNode(element, null);
        return canAddTo(parentNode, new JMeterTreeNode[]{node});
    }

    /**
     * Determine whether or not nodes can be added to this parent.
     * <p>
     * Used by DragNDrop and Paste.
     *
     * @param parentNode The {@link JMeterTreeNode} to test, if <code>nodes[]</code>
     *            can be added to it
     * @param nodes      array of nodes that are to be added
     * @return whether it is OK to add the dragged nodes to this parent
     */
    public static boolean canAddTo(JMeterTreeNode parentNode, JMeterTreeNode[] nodes) {
        if (parentNode == null
                || foundClass(nodes, new Class[]{TestPlan.class})) {
            return false;
        }
        TestElement parent = parentNode.getTestElement();

        // Force TestFragment to only be pastable under a Test Plan
        if (foundClass(nodes, new Class[]{TestFragmentController.class})) {
            return parent instanceof TestPlan;
        }

        // Cannot move Non-Test Elements from root of Test Plan or Test Fragment
        if (foundMenuCategories(nodes, NON_TEST_ELEMENTS)
                && !(parent instanceof TestPlan || parent instanceof TestFragmentController)) {
            return false;
        }

        if (parent instanceof TestPlan) {
            List<Class<?>> samplerAndController = Arrays.asList(Sampler.class, Controller.class);
            List<Class<?>> exceptions = Arrays.asList(AbstractThreadGroup.class, NonTestElement.class);
            return !foundClass(nodes, samplerAndController, exceptions);
        }
        // AbstractThreadGroup is only allowed under a TestPlan
        if (foundClass(nodes, new Class[]{AbstractThreadGroup.class})) {
            return false;
        }

        // Includes thread group; anything goes
        if (parent instanceof Controller) {
            return true;
        }

        // No Samplers and Controllers
        if (parent instanceof Sampler) {
            return !foundClass(nodes, new Class[]{Sampler.class, Controller.class});
        }

        // All other
        return false;
    }

    /**
     * Is any of nodes an instance of one of the classes?
     *
     * @param nodes Array of {@link JMeterTreeNode}
     * @param classes Array of {@link Class}
     * @return true if nodes is one of classes
     */
    private static boolean foundClass(JMeterTreeNode[] nodes, Class<?>[] classes) {
        for (JMeterTreeNode node : nodes) {
            for (Class<?> aClass : classes) {
                if (aClass.isInstance(node.getUserObject())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class ModernMenuIcon implements Icon {
        private static final int SIZE = 16;

        private final Kind kind;

        private ModernMenuIcon(Kind kind) {
            this.kind = kind;
        }

        static Icon of(Kind kind) {
            return new ModernMenuIcon(kind);
        }

        static Icon fromCategories(String[] categories) {
            return categories.length == 1 ? fromCategory(categories[0]) : of(Kind.ADD);
        }

        static Icon fromCategory(String category) {
            return switch (category) {
            case THREADS -> of(Kind.THREADS);
            case SAMPLERS -> of(Kind.REQUEST);
            case CONTROLLERS -> of(Kind.CONTROLLER);
            case TIMERS -> of(Kind.TIMER);
            case ASSERTIONS -> of(Kind.ASSERTION);
            case CONFIG_ELEMENTS -> of(Kind.CONFIG);
            case LISTENERS -> of(Kind.REPORT);
            case PRE_PROCESSORS -> of(Kind.PRE_PROCESSOR);
            case POST_PROCESSORS -> of(Kind.POST_PROCESSOR);
            case FRAGMENTS -> of(Kind.MODULE);
            default -> of(Kind.NODE);
            };
        }

        static Icon fromDescriptor(String category, String descriptor) {
            if (LISTENERS.equals(category)) {
                return of(Kind.REPORT);
            }
            if (PRE_PROCESSORS.equals(category)) {
                return of(Kind.PRE_PROCESSOR);
            }
            if (POST_PROCESSORS.equals(category)) {
                return of(Kind.POST_PROCESSOR);
            }
            if (SAMPLERS.equals(category)) {
                return of(Kind.REQUEST);
            }
            if (descriptor.contains("Cookie")) { // $NON-NLS-1$
                return of(Kind.COOKIE);
            }
            if (descriptor.contains("ThreadGroup")) { // $NON-NLS-1$
                return of(Kind.THREADS);
            }
            if (descriptor.contains("Visualizer") || descriptor.contains("Listener") || descriptor.contains("ResultCollector")
                    || descriptor.contains("Report")) { // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$ $NON-NLS-4$
                return of(Kind.REPORT);
            }
            if (descriptor.contains("Sampler")) { // $NON-NLS-1$
                return of(Kind.REQUEST);
            }
            Kind controllerKind = controllerKind(descriptor);
            if (controllerKind != null) {
                return of(controllerKind);
            }
            if (descriptor.contains("Config") || descriptor.contains("Defaults") || descriptor.contains("Manager")) { // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$
                return of(Kind.CONFIG);
            }
            if (descriptor.contains("Timer")) { // $NON-NLS-1$
                return of(Kind.TIMER);
            }
            if (descriptor.contains("Assertion")) { // $NON-NLS-1$
                return of(Kind.ASSERTION);
            }
            if (descriptor.contains("PreProcessor")) { // $NON-NLS-1$
                return of(Kind.PRE_PROCESSOR);
            }
            if (descriptor.contains("PostProcessor")) { // $NON-NLS-1$
                return of(Kind.POST_PROCESSOR);
            }
            return of(Kind.NODE);
        }

        private static Kind controllerKind(String descriptor) {
            if (descriptor.contains("IfController") || descriptor.contains("If Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.IF_CONTROLLER;
            }
            if (descriptor.contains("SwitchController") || descriptor.contains("Switch Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.SWITCH_CONTROLLER;
            }
            if (descriptor.contains("TransactionController") || descriptor.contains("Transaction Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.TRANSACTION;
            }
            if (descriptor.contains("LoopController") || descriptor.contains("Loop Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.LOOP;
            }
            if (descriptor.contains("WhileController") || descriptor.contains("While Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.WHILE_CONTROLLER;
            }
            if (descriptor.contains("CriticalSectionController") || descriptor.contains("Critical Section Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.CRITICAL_CONTROLLER;
            }
            if (descriptor.contains("ForeachController") || descriptor.contains("ForEachController")
                    || descriptor.contains("ForEach Controller")) { // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$
                return Kind.FOREACH_CONTROLLER;
            }
            if (descriptor.contains("IncludeController") || descriptor.contains("Include Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.INCLUDE_CONTROLLER;
            }
            if (descriptor.contains("InterleaveControl") || descriptor.contains("Interleave Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.INTERLEAVE_CONTROLLER;
            }
            if (descriptor.contains("OnceOnlyController") || descriptor.contains("Once Only Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.ONCE_CONTROLLER;
            }
            if (descriptor.contains("RandomOrderController") || descriptor.contains("Random Order Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.RANDOM_ORDER_CONTROLLER;
            }
            if (descriptor.contains("RandomController") || descriptor.contains("Random Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.RANDOM_CONTROLLER;
            }
            if (descriptor.contains("RecordingController") || descriptor.contains("Recording Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.RECORDING_CONTROLLER;
            }
            if (descriptor.contains("RuntimeController") || descriptor.contains("Runtime Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.RUNTIME_CONTROLLER;
            }
            if (descriptor.contains("ParallelController") || descriptor.contains("Parallel Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.PARALLEL_CONTROLLER;
            }
            if (descriptor.contains("ThroughputController") || descriptor.contains("Throughput Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.THROUGHPUT_CONTROLLER;
            }
            if (descriptor.contains("ForkController") || descriptor.contains("Fork Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.FORK_CONTROLLER;
            }
            if (descriptor.contains("ModuleController") || descriptor.contains("Module Controller")
                    || descriptor.contains("Test Fragment")) { // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$
                return Kind.MODULE;
            }
            if (descriptor.contains("SimpleController") || descriptor.contains("Simple Controller")
                    || descriptor.contains("Controller")) { // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$
                return Kind.SIMPLE_CONTROLLER;
            }
            return null;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color stroke = foreground(c);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                kind.paint(g2, x, y, stroke, kind.accent);
            } finally {
                g2.dispose();
            }
        }

        private static Color foreground(Component c) {
            return c == null || c.getForeground() == null ? new Color(0x4B5563) : c.getForeground();
        }

        private enum Kind {
            ADD(new Color(0x2563EB)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 3, y + 3, 10, 10);
                    g.drawLine(x + 8, y + 5, x + 8, y + 11);
                    g.drawLine(x + 5, y + 8, x + 11, y + 8);
                }
            },
            THREADS(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 5, y + 2, 6, 6);
                    g.setColor(stroke);
                    g.drawArc(x + 3, y + 8, 10, 7, 20, 140);
                    g.drawLine(x + 3, y + 13, x + 13, y + 13);
                }
            },
            REQUEST(new Color(0x16A34A)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 3, y + 12, x + 12, y + 3);
                    g.setColor(stroke);
                    g.drawLine(x + 9, y + 3, x + 13, y + 3);
                    g.drawLine(x + 12, y + 3, x + 12, y + 7);
                }
            },
            HTTP_SAMPLER(new Color(0x16A34A)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 5, 10, 7, 2, 2);
                    g.setColor(accent);
                    g.drawLine(x + 11, y + 8, x + 14, y + 8);
                    g.drawLine(x + 12, y + 6, x + 14, y + 8);
                    g.drawLine(x + 12, y + 10, x + 14, y + 8);
                }
            },
            CODE_SAMPLER(new Color(0x8B5CF6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 7, y + 5, x + 4, y + 8);
                    g.drawLine(x + 4, y + 8, x + 7, y + 11);
                    g.drawLine(x + 10, y + 5, x + 13, y + 8);
                    g.drawLine(x + 13, y + 8, x + 10, y + 11);
                    g.setColor(stroke);
                    g.drawLine(x + 9, y + 4, x + 7, y + 12);
                }
            },
            DB_SAMPLER(new Color(0x0EA5E9)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 4, y + 3, 9, 4);
                    g.drawLine(x + 4, y + 5, x + 4, y + 12);
                    g.drawLine(x + 13, y + 5, x + 13, y + 12);
                    g.drawOval(x + 4, y + 10, 9, 4);
                    g.setColor(stroke);
                    g.drawLine(x + 5, y + 8, x + 12, y + 8);
                }
            },
            MESSAGE_SAMPLER(new Color(0x14B8A6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawRoundRect(x + 3, y + 5, 10, 7, 2, 2);
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 6, x + 8, y + 9);
                    g.drawLine(x + 12, y + 6, x + 8, y + 9);
                }
            },
            FILE_SAMPLER(new Color(0xD97706)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 4, y + 4, x + 9, y + 4);
                    g.drawLine(x + 9, y + 4, x + 12, y + 7);
                    g.drawLine(x + 12, y + 7, x + 12, y + 13);
                    g.drawLine(x + 4, y + 4, x + 4, y + 13);
                    g.drawLine(x + 4, y + 13, x + 12, y + 13);
                    g.setColor(stroke);
                    g.drawLine(x + 6, y + 8, x + 10, y + 8);
                }
            },
            MAIL_SAMPLER(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawRoundRect(x + 3, y + 5, 10, 8, 2, 2);
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 6, x + 8, y + 10);
                    g.drawLine(x + 12, y + 6, x + 8, y + 10);
                }
            },
            TCP_SAMPLER(new Color(0x06B6D4)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 8, x + 12, y + 8);
                    g.setColor(accent);
                    g.drawOval(x + 2, y + 6, 4, 4);
                    g.drawOval(x + 10, y + 6, 4, 4);
                    g.drawOval(x + 6, y + 11, 4, 4);
                }
            },
            SYSTEM_SAMPLER(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 5, 10, 8, 2, 2);
                    g.setColor(accent);
                    g.drawLine(x + 6, y + 8, x + 8, y + 10);
                    g.drawLine(x + 8, y + 10, x + 11, y + 6);
                }
            },
            COOKIE(new Color(0xB45309)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 3, y + 3, 10, 10);
                    g.setColor(stroke);
                    g.fillOval(x + 6, y + 6, 2, 2);
                    g.fillOval(x + 10, y + 5, 2, 2);
                    g.fillOval(x + 8, y + 10, 2, 2);
                    g.setColor(accent);
                    g.drawArc(x + 8, y + 2, 6, 6, 190, 130);
                }
            },
            CONFIG(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 5, x + 12, y + 5);
                    g.drawLine(x + 4, y + 8, x + 12, y + 8);
                    g.drawLine(x + 4, y + 11, x + 12, y + 11);
                    g.setColor(accent);
                    g.drawOval(x + 3, y + 4, 2, 2);
                    g.drawOval(x + 10, y + 10, 2, 2);
                }
            },
            CONTROLLER(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRect(x + 3, y + 3, 10, 10);
                    g.setColor(accent);
                    g.drawLine(x + 6, y + 7, x + 10, y + 7);
                    g.drawLine(x + 6, y + 10, x + 10, y + 10);
                }
            },
            LOOP(new Color(0x8B5CF6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawArc(x + 3, y + 3, 10, 10, 35, 280);
                    g.drawLine(x + 12, y + 4, x + 13, y + 8);
                    g.drawLine(x + 12, y + 4, x + 8, y + 4);
                }
            },
            IF_CONTROLLER(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 3, x + 4, y + 13);
                    g.drawLine(x + 4, y + 8, x + 9, y + 8);
                    g.setColor(accent);
                    g.drawLine(x + 9, y + 5, x + 13, y + 8);
                    g.drawLine(x + 9, y + 11, x + 13, y + 8);
                }
            },
            SWITCH_CONTROLLER(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 8, x + 8, y + 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 8, x + 13, y + 4);
                    g.drawLine(x + 8, y + 8, x + 13, y + 12);
                    g.drawLine(x + 11, y + 3, x + 13, y + 4);
                    g.drawLine(x + 11, y + 5, x + 13, y + 4);
                    g.drawLine(x + 11, y + 11, x + 13, y + 12);
                    g.drawLine(x + 11, y + 13, x + 13, y + 12);
                }
            },
            WHILE_CONTROLLER(new Color(0x8B5CF6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawArc(x + 3, y + 3, 10, 10, 45, 250);
                    g.drawLine(x + 4, y + 6, x + 3, y + 3);
                    g.drawLine(x + 4, y + 6, x + 7, y + 5);
                    g.setColor(stroke);
                    g.fillOval(x + 7, y + 7, 3, 3);
                }
            },
            CRITICAL_CONTROLLER(new Color(0xDC2626)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawArc(x + 5, y + 2, 6, 7, 0, 180);
                    g.setColor(stroke);
                    g.drawRoundRect(x + 4, y + 7, 8, 6, 2, 2);
                    g.drawLine(x + 8, y + 9, x + 8, y + 11);
                }
            },
            FOREACH_CONTROLLER(new Color(0x14B8A6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 4, x + 10, y + 4);
                    g.drawLine(x + 4, y + 8, x + 10, y + 8);
                    g.drawLine(x + 4, y + 12, x + 10, y + 12);
                    g.setColor(accent);
                    g.drawLine(x + 10, y + 4, x + 13, y + 7);
                    g.drawLine(x + 13, y + 7, x + 10, y + 10);
                }
            },
            INCLUDE_CONTROLLER(new Color(0x14B8A6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 3, x + 4, y + 13);
                    g.drawLine(x + 12, y + 3, x + 12, y + 13);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 8, x + 11, y + 8);
                    g.drawLine(x + 9, y + 6, x + 11, y + 8);
                    g.drawLine(x + 9, y + 10, x + 11, y + 8);
                }
            },
            INTERLEAVE_CONTROLLER(new Color(0xEC4899)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 5, x + 7, y + 5);
                    g.drawLine(x + 3, y + 11, x + 7, y + 11);
                    g.setColor(accent);
                    g.drawLine(x + 7, y + 5, x + 13, y + 11);
                    g.drawLine(x + 7, y + 11, x + 13, y + 5);
                }
            },
            ONCE_CONTROLLER(new Color(0x6366F1)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 3, y + 3, 10, 10);
                    g.setColor(stroke);
                    g.drawLine(x + 8, y + 6, x + 8, y + 11);
                    g.drawLine(x + 6, y + 7, x + 8, y + 6);
                }
            },
            RANDOM_CONTROLLER(new Color(0xEC4899)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 4, y + 4, 8, 8, 2, 2);
                    g.setColor(accent);
                    g.fillOval(x + 6, y + 6, 2, 2);
                    g.fillOval(x + 9, y + 9, 2, 2);
                }
            },
            RANDOM_ORDER_CONTROLLER(new Color(0xEC4899)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 5, x + 7, y + 5);
                    g.drawLine(x + 3, y + 11, x + 7, y + 11);
                    g.setColor(accent);
                    g.drawLine(x + 7, y + 5, x + 13, y + 11);
                    g.drawLine(x + 7, y + 11, x + 13, y + 5);
                    g.drawLine(x + 11, y + 4, x + 13, y + 5);
                    g.drawLine(x + 11, y + 6, x + 13, y + 5);
                    g.drawLine(x + 11, y + 10, x + 13, y + 11);
                    g.drawLine(x + 11, y + 12, x + 13, y + 11);
                }
            },
            RECORDING_CONTROLLER(new Color(0xEF4444)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 4, 10, 8, 3, 3);
                    g.setColor(accent);
                    g.fillOval(x + 6, y + 6, 4, 4);
                }
            },
            RUNTIME_CONTROLLER(new Color(0xD97706)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawOval(x + 4, y + 4, 8, 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 8, x + 8, y + 5);
                    g.drawLine(x + 8, y + 8, x + 11, y + 8);
                }
            },
            SIMPLE_CONTROLLER(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 4, y + 3, 8, 10, 2, 2);
                    g.setColor(accent);
                    g.drawLine(x + 6, y + 6, x + 10, y + 6);
                    g.drawLine(x + 6, y + 9, x + 10, y + 9);
                }
            },
            PARALLEL_CONTROLLER(new Color(0x06B6D4)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 4, x + 4, y + 12);
                    g.drawLine(x + 8, y + 4, x + 8, y + 12);
                    g.drawLine(x + 12, y + 4, x + 12, y + 12);
                    g.setColor(accent);
                    g.drawLine(x + 3, y + 8, x + 13, y + 8);
                }
            },
            THROUGHPUT_CONTROLLER(new Color(0x22C55E)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawArc(x + 3, y + 5, 10, 10, 0, 180);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 10, x + 12, y + 6);
                    g.fillOval(x + 7, y + 9, 2, 2);
                }
            },
            FORK_CONTROLLER(new Color(0x0EA5E9)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 8, x + 8, y + 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 8, x + 13, y + 4);
                    g.drawLine(x + 8, y + 8, x + 13, y + 12);
                    g.drawOval(x + 3, y + 7, 2, 2);
                    g.drawOval(x + 12, y + 3, 2, 2);
                    g.drawOval(x + 12, y + 11, 2, 2);
                }
            },
            BRANCH(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 4, x + 4, y + 12);
                    g.drawLine(x + 4, y + 8, x + 10, y + 8);
                    g.setColor(accent);
                    g.drawLine(x + 10, y + 5, x + 13, y + 8);
                    g.drawLine(x + 10, y + 11, x + 13, y + 8);
                }
            },
            TRANSACTION(new Color(0x06B6D4)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 4, 10, 8, 3, 3);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 8, x + 11, y + 8);
                    g.drawLine(x + 9, y + 6, x + 11, y + 8);
                    g.drawLine(x + 9, y + 10, x + 11, y + 8);
                }
            },
            MODULE(new Color(0x14B8A6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRect(x + 3, y + 3, 4, 4);
                    g.drawRect(x + 9, y + 3, 4, 4);
                    g.drawRect(x + 6, y + 9, 4, 4);
                    g.setColor(accent);
                    g.drawLine(x + 7, y + 5, x + 9, y + 5);
                    g.drawLine(x + 8, y + 7, x + 8, y + 9);
                }
            },
            ROUTER(new Color(0xEC4899)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 5, x + 8, y + 5);
                    g.drawLine(x + 3, y + 11, x + 8, y + 11);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 5, x + 13, y + 3);
                    g.drawLine(x + 8, y + 11, x + 13, y + 13);
                    g.drawLine(x + 11, y + 2, x + 13, y + 3);
                    g.drawLine(x + 11, y + 4, x + 13, y + 3);
                    g.drawLine(x + 11, y + 12, x + 13, y + 13);
                    g.drawLine(x + 11, y + 14, x + 13, y + 13);
                }
            },
            TIMER(new Color(0xD97706)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawOval(x + 4, y + 4, 8, 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 8, x + 8, y + 5);
                    g.drawLine(x + 8, y + 8, x + 10, y + 10);
                }
            },
            ASSERTION(new Color(0x16A34A)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawOval(x + 3, y + 3, 10, 10);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 8, x + 7, y + 10);
                    g.drawLine(x + 7, y + 10, x + 12, y + 5);
                }
            },
            REPORT(new Color(0x2563EB)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 13, x + 13, y + 13);
                    g.drawLine(x + 3, y + 13, x + 3, y + 4);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 10, x + 8, y + 7);
                    g.drawLine(x + 8, y + 7, x + 11, y + 9);
                    g.drawLine(x + 11, y + 9, x + 13, y + 4);
                    g.setColor(new Color(0x16A34A));
                    g.fillOval(x + 4, y + 9, 2, 2);
                    g.setColor(new Color(0xF59E0B));
                    g.fillOval(x + 12, y + 3, 2, 2);
                }
            },
            PROCESSOR(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawOval(x + 4, y + 4, 8, 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 2, x + 8, y + 14);
                    g.drawLine(x + 2, y + 8, x + 14, y + 8);
                }
            },
            PRE_PROCESSOR(new Color(0x0EA5E9)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 12, y + 4, x + 5, y + 8);
                    g.drawLine(x + 5, y + 8, x + 12, y + 12);
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 4, x + 4, y + 12);
                }
            },
            POST_PROCESSOR(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 4, y + 4, x + 11, y + 8);
                    g.drawLine(x + 11, y + 8, x + 4, y + 12);
                    g.setColor(stroke);
                    g.drawLine(x + 12, y + 4, x + 12, y + 12);
                }
            },
            NODE(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 4, 10, 8, 3, 3);
                }
            };

            private final Color accent;

            Kind(Color accent) {
                this.accent = accent;
            }

            abstract void paint(Graphics2D g, int x, int y, Color stroke, Color accent);
        }
    }

    /**
     * Is any node an instance of one of the menu category?
     * @param nodes Array of {@link JMeterTreeNode}
     * @param category Category
     * @return true if nodes is in category
     */
    private static boolean foundMenuCategories(JMeterTreeNode[] nodes, String category) {
        return Arrays.stream(nodes)
                .flatMap(node -> node.getMenuCategories().stream())
                .anyMatch(category::equals);
    }

    /**
     * Is any node an instance of one of the classes, but not an exceptions?
     *
     * @param nodes array of {@link JMeterTreeNode}
     * @param classes Array of {@link Class}
     * @param exceptions Array of {@link Class}
     * @return boolean
     */
    private static boolean foundClass(
            JMeterTreeNode[] nodes, List<Class<?>> classes, List<Class<?>> exceptions) {
        return Arrays.stream(nodes)
                .map(DefaultMutableTreeNode::getUserObject)
                .filter(userObj -> exceptions.stream().noneMatch(c -> c.isInstance(userObj)))
                .anyMatch(userObj -> classes.stream().anyMatch(c -> c.isInstance(userObj)));
    }
}
