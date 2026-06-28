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

package org.apache.jmeter.threads.gui;

import static org.apache.jmeter.util.JMeterUtils.labelFor;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.gui.JBooleanPropertyEditor;
import org.apache.jmeter.gui.JTextComponentBinding;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.util.InfoButton;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.AbstractThreadGroupSchema;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ThreadGroupSchema;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroupController;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroupSchema;
import org.apache.jmeter.threads.openmodel.ThreadScheduleUtils;
import org.apache.jmeter.threads.openmodel.gui.TargetRateChart;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JFactory;
import org.apache.jorphan.gui.JEditableCheckBox;

import net.miginfocom.swing.MigLayout;

@TestElementMetadata(labelResource = "threadgroup")
public class ThreadGroupGui extends AbstractThreadGroupGui implements ItemListener {
    private static final long serialVersionUID = 240L;

    private LoopControlPanel loopPanel;

    private static final String THREAD_NAME = "Thread Field";

    private static final String RAMP_NAME = "Ramp Up Field";

    private static final String CLOSED_MODEL_CARD = "closed"; // $NON-NLS-1$

    private static final String OPEN_MODEL_CARD = "open"; // $NON-NLS-1$

    private static final String PREVIEW_CLOSED_CARD = "previewClosed"; // $NON-NLS-1$

    private static final String PREVIEW_OPEN_CARD = "previewOpen"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_CONSTANT = "constantThreadsPerMinDuring"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_RAMP = "rampThreadsPerMinDuring"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_RATE = "rate"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_EVEN = "even_arrival"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_RANDOM = "random_arrival"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_PAUSE = "pause"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_COMMENT = "comment"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_CARD_CONSTANT = "constant"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_CARD_RAMP = "ramp"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_CARD_RATE = "rate"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_CARD_DURATION = "duration"; // $NON-NLS-1$

    private static final String SCHEDULE_HELPER_CARD_EMPTY = "empty"; // $NON-NLS-1$

    private final JTextField threadInput = new JTextField();

    private final JTextField rampInput = new JTextField();

    private final boolean showDelayedStart;

    private final JComboBox<String> threadGroupModel = new JComboBox<>(
            new String[] { closedModelLabel(), openModelLabel() });

    private final JPanel modelCards = new JPanel(new CardLayout());

    private final JPanel previewCards = new JPanel(new CardLayout());

    private final TargetRateChart closedModelPreview = new TargetRateChart();

    private final TargetRateChart openModelPreview = new TargetRateChart();

    private final JTextArea openModelSchedule = JFactory.tabMovesFocus(new JTextArea(8, 42));

    private final JTextField openModelRandomSeed = new JTextField(12);

    private final JTextField openModelMaxThreads = new JTextField(12);

    private final JComboBox<String> openModelMaxThreadsScope = new JComboBox<>(new String[] {
            openModelMaxThreadsScopeThreadGroupLabel(),
            openModelMaxThreadsScopeAllOpenModelLabel()
    });

    private final JComboBox<String> openModelScheduleFunction = new JComboBox<>(new String[] {
            SCHEDULE_HELPER_CONSTANT,
            SCHEDULE_HELPER_RAMP,
            SCHEDULE_HELPER_RATE,
            SCHEDULE_HELPER_EVEN,
            SCHEDULE_HELPER_RANDOM,
            SCHEDULE_HELPER_PAUSE,
            SCHEDULE_HELPER_COMMENT
    });

    private final JPanel openModelScheduleFunctionCards = new JPanel(new CardLayout());

    private final JTextField openModelConstantRate = new JTextField("20", 6); // $NON-NLS-1$

    private final JTextField openModelConstantDuration = new JTextField("15", 6); // $NON-NLS-1$

    private final JTextField openModelRampFromRate = new JTextField("10", 6); // $NON-NLS-1$

    private final JTextField openModelRampToRate = new JTextField("20", 6); // $NON-NLS-1$

    private final JTextField openModelRampDuration = new JTextField("10", 6); // $NON-NLS-1$

    private final JTextField openModelRateValue = new JTextField("1", 6); // $NON-NLS-1$

    private final JComboBox<String> openModelRateUnit = new JComboBox<>(
            new String[] {"min", "sec", "hour"}); // $NON-NLS-1$ // $NON-NLS-2$ // $NON-NLS-3$

    private final JTextField openModelDurationValue = new JTextField("10", 6); // $NON-NLS-1$

    private final JComboBox<String> openModelDurationUnit = new JComboBox<>(
            new String[] {"min", "sec", "hour"}); // $NON-NLS-1$ // $NON-NLS-2$ // $NON-NLS-3$

    private final JComboBox<String> pacingMode = new JComboBox<>(new String[] {
            AbstractThreadGroup.PACING_DISABLED,
            AbstractThreadGroup.PACING_FIXED,
            AbstractThreadGroup.PACING_RANDOM,
            AbstractThreadGroup.PACING_GAUSSIAN_RANDOM,
    });

    private final JComboBox<String> durationPolicy = new JComboBox<>(new String[] {
            durationPolicyNoLimitLabel(),
            durationPolicyLoopCountLabel(),
            durationPolicyDurationLabel()
    });

    private final JTextField fixedPacing = new JTextField(12);

    private final JTextField pacingMin = new JTextField(12);

    private final JTextField pacingMax = new JTextField(12);

    private final JLabel fixedPacingLabel =
            new JLabel(JMeterUtils.getResString("thread_group_pacing_fixed"));

    private final JLabel pacingMinLabel =
            new JLabel(JMeterUtils.getResString("thread_group_pacing_min"));

    private final JLabel pacingMaxLabel =
            new JLabel(JMeterUtils.getResString("thread_group_pacing_max"));

    private final JLabel pacingRate = new JLabel();

    private final InfoButton pacingInfo = new InfoButton(
            JMeterUtils.getResString("thread_group_pacing_info_title"),
            JMeterUtils.getResString("thread_group_pacing_info"));

    private final InfoButton sameUserInfo = new InfoButton(
            JMeterUtils.getResString("threadgroup_same_user_info_title"),
            JMeterUtils.getResString("threadgroup_same_user_info"));

    private JPanel fixedPacingFieldPanel;

    private JPanel pacingMinFieldPanel;

    private JPanel pacingMaxFieldPanel;

    private JBooleanPropertyEditor delayedStart;

    private final JBooleanPropertyEditor scheduler =
            new JBooleanPropertyEditor(
                    ThreadGroupSchema.INSTANCE.getUseScheduler(),
                    "scheduler",
                    JMeterUtils::getResString);

    private final JTextField duration = new JTextField();
    private final JLabel durationLabel = labelFor(duration, "duration");

    private final JTextField delay = new JTextField(); // Relative start-up time
    private final JLabel delayLabel = labelFor(delay, "delay");

    private final JBooleanPropertyEditor sameUserBox =
            new JBooleanPropertyEditor(
                    AbstractThreadGroupSchema.INSTANCE.getSameUserOnNextIteration(),
                    "threadgroup_same_user",
                    JMeterUtils::getResString);

    public ThreadGroupGui() {
        this(true);
    }

    public ThreadGroupGui(boolean showDelayedStart) {
        super();
        this.showDelayedStart = showDelayedStart;
        init();
        initGui();
        pacingMode.setEditable(true);
        if (showDelayedStart) {
            bindingGroup.add(delayedStart);
        }
        bindingGroup.addAll(
                Arrays.asList(
                        new JTextComponentBinding(threadInput, AbstractThreadGroupSchema.INSTANCE.getNumThreads()),
                        new JTextComponentBinding(rampInput, ThreadGroupSchema.INSTANCE.getRampTime()),
                        new JTextComponentBinding(duration, ThreadGroupSchema.INSTANCE.getDuration()),
                        new JTextComponentBinding(delay, ThreadGroupSchema.INSTANCE.getDelay()),
                        sameUserBox,
                        scheduler
                )
        );
    }

    @Override
    public TestElement makeTestElement() {
        return new ThreadGroup();
    }

    @Override
    public void assignDefaultValues(TestElement element) {
        super.assignDefaultValues(element);
        if (element instanceof OpenModelThreadGroup openModelThreadGroup) {
            openModelThreadGroup.setScheduleString(""); // $NON-NLS-1$
            openModelThreadGroup.setRandomSeedString("0"); // $NON-NLS-1$
            openModelThreadGroup.setProperty(ThreadGroup.OPEN_MODEL_MAX_THREADS, ""); // $NON-NLS-1$
            openModelThreadGroup.setProperty(ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE,
                    ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP);
            openModelThreadGroup.set(OpenModelThreadGroupSchema.INSTANCE.getMainController(), new OpenModelThreadGroupController());
            return;
        }
        if (element instanceof ThreadGroup threadGroup) {
            threadGroup.setThreadGroupModel(ThreadGroup.MODEL_CLOSED);
        }
        element.set(ThreadGroupSchema.INSTANCE.getNumThreads(), 1);
        element.set(ThreadGroupSchema.INSTANCE.getRampTime(), 1);
        if (showDelayedStart) {
            element.set(ThreadGroupSchema.INSTANCE.getDelayedStart(), true);
        }
        element.set(AbstractThreadGroupSchema.INSTANCE.getSameUserOnNextIteration(), true);
        element.set(AbstractThreadGroupSchema.INSTANCE.getPacingMode(), AbstractThreadGroup.PACING_DISABLED);
        element.set(AbstractThreadGroupSchema.INSTANCE.getFixedPacing(), "0"); // $NON-NLS-1$
        element.set(AbstractThreadGroupSchema.INSTANCE.getPacingMin(), "0"); // $NON-NLS-1$
        element.set(AbstractThreadGroupSchema.INSTANCE.getPacingMax(), "0"); // $NON-NLS-1$
        ((AbstractThreadGroup) element).setSamplerController((LoopController) loopPanel.createTestElement());
    }

    /**
     * Modifies a given TestElement to mirror the data in the gui components.
     *
     * @see org.apache.jmeter.gui.JMeterGUIComponent#modifyTestElement(TestElement)
     */
    @Override
    @SuppressWarnings("deprecation")
    public void modifyTestElement(TestElement tg) {
        if (tg instanceof OpenModelThreadGroup openModelThreadGroup) {
            openModelThreadGroup.clear();
            configureTestElement(openModelThreadGroup);
            openModelThreadGroup.setScheduleString(openModelSchedule.getText());
            openModelThreadGroup.setRandomSeedString(openModelRandomSeed.getText());
            openModelThreadGroup.setProperty(ThreadGroup.OPEN_MODEL_MAX_THREADS, openModelMaxThreads.getText());
            openModelThreadGroup.setProperty(ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE, selectedOpenModelMaxThreadsScope());
            openModelThreadGroup.set(OpenModelThreadGroupSchema.INSTANCE.getMainController(), new OpenModelThreadGroupController());
            setLegacyOpenModelPacingIfConfigured(openModelThreadGroup);
            return;
        }
        boolean openModel = isOpenModelSelected();
        if (!openModel) {
            applyDurationPolicyToFields();
        }
        super.modifyTestElement(tg);
        if (tg instanceof ThreadGroup threadGroup) {
                threadGroup.setThreadGroupModel(openModel ? ThreadGroup.MODEL_OPEN : ThreadGroup.MODEL_CLOSED);
            if (openModel) {
                threadGroup.setOpenModelSchedule(openModelSchedule.getText());
                threadGroup.setOpenModelRandomSeedString(openModelRandomSeed.getText());
                threadGroup.setOpenModelMaxThreadsString(openModelMaxThreads.getText());
                threadGroup.setOpenModelMaxThreadsScope(selectedOpenModelMaxThreadsScope());
                threadGroup.set(ThreadGroupSchema.INSTANCE.getMainController(), new OpenModelThreadGroupController());
            }
        }
        if (tg instanceof AbstractThreadGroup abstractThreadGroup) {
            abstractThreadGroup.setPacingMode((String) pacingMode.getSelectedItem());
            abstractThreadGroup.setFixedPacing(fixedPacing.getText());
            abstractThreadGroup.setPacingMin(pacingMin.getText());
            abstractThreadGroup.setPacingMax(pacingMax.getText());
            if (!openModel) {
                abstractThreadGroup.setSamplerController((LoopController) loopPanel.createTestElement());
            }
        }
    }

    @Override
    public void configure(TestElement tg) {
        super.configure(tg);
        boolean openModel = isOpenModelElement(tg);
        setSelectedThreadGroupModel(openModel ? ThreadGroup.MODEL_OPEN : ThreadGroup.MODEL_CLOSED);
        if (!openModel) {
            loopPanel.configure((TestElement) tg.getProperty(AbstractThreadGroup.MAIN_CONTROLLER).getObjectValue());
            configureDurationPolicyFromCurrentValues();
        }
        configureOpenModelFields(tg);
        if (tg instanceof AbstractThreadGroup abstractThreadGroup) {
            pacingMode.setSelectedItem(abstractThreadGroup.getPacingMode());
            fixedPacing.setText(abstractThreadGroup.getFixedPacing());
            pacingMin.setText(abstractThreadGroup.getPacingMin());
            pacingMax.setText(abstractThreadGroup.getPacingMax());
            updatePacingFields();
        }
        updateDurationPolicyFields();
        updateModelFields();
    }

    @Override
    public void itemStateChanged(ItemEvent ie) {
        // Method kept for backward compatibility
    }

    private JPanel createControllerPanel() {
        loopPanel = new LoopControlPanel(false);
        LoopController looper = (LoopController) loopPanel.createTestElement();
        looper.setLoops(1);
        loopPanel.configure(looper);
        return loopPanel;
    }

    private static String closedModelLabel() {
        return JMeterUtils.getResString("thread_group_model_closed"); // $NON-NLS-1$
    }

    private static String openModelLabel() {
        return JMeterUtils.getResString("thread_group_model_open"); // $NON-NLS-1$
    }

    private static String durationPolicyNoLimitLabel() {
        return JMeterUtils.getResString("thread_group_duration_policy_no_limit"); // $NON-NLS-1$
    }

    private static String durationPolicyLoopCountLabel() {
        return JMeterUtils.getResString("thread_group_duration_policy_loop_count"); // $NON-NLS-1$
    }

    private static String durationPolicyDurationLabel() {
        return JMeterUtils.getResString("thread_group_duration_policy_duration"); // $NON-NLS-1$
    }

    private static String openModelMaxThreadsScopeThreadGroupLabel() {
        return JMeterUtils.getResString("thread_group_open_model_max_threads_scope_thread_group"); // $NON-NLS-1$
    }

    private static String openModelMaxThreadsScopeAllOpenModelLabel() {
        return JMeterUtils.getResString("thread_group_open_model_max_threads_scope_all_open_model"); // $NON-NLS-1$
    }

    private boolean isOpenModelSelected() {
        return openModelLabel().equals(threadGroupModel.getSelectedItem());
    }

    private boolean isNoLimitPolicySelected() {
        return durationPolicyNoLimitLabel().equals(durationPolicy.getSelectedItem());
    }

    private boolean isLoopCountPolicySelected() {
        return durationPolicyLoopCountLabel().equals(durationPolicy.getSelectedItem());
    }

    private boolean isDurationPolicySelected() {
        return durationPolicyDurationLabel().equals(durationPolicy.getSelectedItem());
    }

    private void setSelectedThreadGroupModel(String model) {
        threadGroupModel.setSelectedItem(ThreadGroup.MODEL_OPEN.equals(model) ? openModelLabel() : closedModelLabel());
    }

    private void configureDurationPolicyFromCurrentValues() {
        if (!scheduler.getValue().equals(JEditableCheckBox.Value.of(false))) {
            durationPolicy.setSelectedItem(durationPolicyDurationLabel());
        } else if (loopPanel.getInfinite().isSelected()) {
            durationPolicy.setSelectedItem(durationPolicyNoLimitLabel());
        } else {
            durationPolicy.setSelectedItem(durationPolicyLoopCountLabel());
        }
    }

    private void updateDurationPolicyFields() {
        boolean durationSelected = isDurationPolicySelected();
        boolean loopCountSelected = isLoopCountPolicySelected();

        loopPanel.getLoopsLabel().setVisible(loopCountSelected);
        loopPanel.getLoops().setVisible(loopCountSelected);
        loopPanel.getLoops().setEnabled(loopCountSelected);
        loopPanel.getInfinite().setSelected(!loopCountSelected);
        if (loopCountSelected && loopPanel.getLoops().getText().trim().isEmpty()) {
            loopPanel.getLoops().setText("1"); // $NON-NLS-1$
        }
        duration.setVisible(durationSelected);
        duration.setEnabled(durationSelected);
        durationLabel.setVisible(durationSelected);
        durationLabel.setEnabled(durationSelected);
        delay.setVisible(true);
        delay.setEnabled(true);
        delayLabel.setVisible(true);
        delayLabel.setEnabled(true);
    }

    private void applyDurationPolicyToFields() {
        if (isDurationPolicySelected()) {
            scheduler.setValue(JEditableCheckBox.Value.of(true));
            setLoopCountInfinite();
        } else if (isNoLimitPolicySelected()) {
            scheduler.setValue(JEditableCheckBox.Value.of(false));
            setLoopCountInfinite();
        } else {
            scheduler.setValue(JEditableCheckBox.Value.of(false));
            if (loopPanel.getLoops().getText().trim().isEmpty()) {
                loopPanel.getLoops().setText("1"); // $NON-NLS-1$
            }
            loopPanel.getInfinite().setSelected(false);
            loopPanel.getLoops().setEnabled(true);
        }
    }

    private void setLoopCountInfinite() {
        loopPanel.getInfinite().setSelected(true);
        loopPanel.getLoops().setText(""); // $NON-NLS-1$
        loopPanel.getLoops().setEnabled(false);
    }

    private static boolean isOpenModelElement(TestElement element) {
        return element instanceof OpenModelThreadGroup
                || element instanceof ThreadGroup threadGroup && threadGroup.isOpenModel();
    }

    private void configureOpenModelFields(TestElement tg) {
        if (tg instanceof ThreadGroup threadGroup) {
            openModelSchedule.setText(threadGroup.getOpenModelSchedule());
            openModelRandomSeed.setText(threadGroup.getOpenModelRandomSeedString());
            openModelMaxThreads.setText(threadGroup.getOpenModelMaxThreadsString());
            setSelectedOpenModelMaxThreadsScope(threadGroup.getOpenModelMaxThreadsScope());
        } else if (tg instanceof OpenModelThreadGroup openModelThreadGroup) {
            openModelSchedule.setText(openModelThreadGroup.getScheduleString());
            openModelRandomSeed.setText(openModelThreadGroup.getRandomSeedString());
            openModelMaxThreads.setText(openModelThreadGroup.getPropertyAsString(ThreadGroup.OPEN_MODEL_MAX_THREADS));
            setSelectedOpenModelMaxThreadsScope(
                    openModelThreadGroup.getPropertyAsString(ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE));
        }
    }

    private String selectedOpenModelMaxThreadsScope() {
        return openModelMaxThreadsScopeAllOpenModelLabel().equals(openModelMaxThreadsScope.getSelectedItem())
                ? ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL
                : ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP;
    }

    private void setSelectedOpenModelMaxThreadsScope(String scope) {
        openModelMaxThreadsScope.setSelectedItem(
                ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL.equals(scope)
                        ? openModelMaxThreadsScopeAllOpenModelLabel()
                        : openModelMaxThreadsScopeThreadGroupLabel());
    }

    private void setLegacyOpenModelPacingIfConfigured(OpenModelThreadGroup openModelThreadGroup) {
        String mode = (String) pacingMode.getSelectedItem();
        if (AbstractThreadGroup.PACING_DISABLED.equals(mode)
                && "0".equals(fixedPacing.getText())
                && "0".equals(pacingMin.getText())
                && "0".equals(pacingMax.getText())) {
            return;
        }
        openModelThreadGroup.setPacingMode(mode);
        openModelThreadGroup.setFixedPacing(fixedPacing.getText());
        openModelThreadGroup.setPacingMin(pacingMin.getText());
        openModelThreadGroup.setPacingMax(pacingMax.getText());
    }


    @Override
    public String getLabelResource() {
        return "threadgroup"; // $NON-NLS-1$
    }

    @Override
    public void clearGui(){
        super.clearGui();
        initGui();
    }

    // Initialise the gui field values
    private void initGui(){
        loopPanel.clearGui();
        setSelectedThreadGroupModel(ThreadGroup.MODEL_CLOSED);
        openModelSchedule.setText(""); // $NON-NLS-1$
        openModelRandomSeed.setText("0"); // $NON-NLS-1$
        openModelMaxThreads.setText(""); // $NON-NLS-1$
        setSelectedOpenModelMaxThreadsScope(ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP);
        durationPolicy.setSelectedItem(durationPolicyLoopCountLabel());
        pacingMode.setSelectedItem(AbstractThreadGroup.PACING_DISABLED);
        fixedPacing.setText("0"); // $NON-NLS-1$
        pacingMin.setText("0"); // $NON-NLS-1$
        pacingMax.setText("0"); // $NON-NLS-1$
        updateDurationPolicyFields();
        updatePacingFields();
        updateModelFields();
    }

    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        JPanel contentPanel = new JPanel(new MigLayout("fillx, wrap 2, hidemode 3", "[][fill,grow]"));
        contentPanel.add(new JLabel(JMeterUtils.getResString("thread_group_model")));
        contentPanel.add(threadGroupModel, "w pref!, growx 0");
        threadGroupModel.addActionListener(e -> updateModelFields());

        modelCards.add(createClosedModelPanel(), CLOSED_MODEL_CARD);
        modelCards.add(createOpenModelPanel(), OPEN_MODEL_CARD);
        contentPanel.add(modelCards, "span 2, growx");
        previewCards.add(closedModelPreview, PREVIEW_CLOSED_CARD);
        previewCards.add(openModelPreview, PREVIEW_OPEN_CARD);
        contentPanel.add(previewCards, "span 2, grow, h 220!");
        addPreviewDocumentListener(threadInput);
        addPreviewDocumentListener(rampInput);
        addPreviewDocumentListener(duration);
        addPreviewDocumentListener(delay);
        openModelSchedule.getDocument().addDocumentListener(new PreviewDocumentListener());
        add(contentPanel, BorderLayout.CENTER);
    }

    private JPanel createClosedModelPanel() {
        // THREAD PROPERTIES
        JPanel threadPropsPanel = new JPanel(new MigLayout("fillx, wrap 2, hidemode 3", "[][fill,grow]"));
        threadPropsPanel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("thread_properties"))); // $NON-NLS-1$

        // NUMBER OF THREADS
        threadPropsPanel.add(labelFor(threadInput, "number_of_threads")); // $NON-NLS-1$
        threadInput.setName(THREAD_NAME);
        threadPropsPanel.add(threadInput);

        // RAMP-UP
        threadPropsPanel.add(labelFor(rampInput, "ramp_up"));
        rampInput.setName(RAMP_NAME);
        threadPropsPanel.add(rampInput);

        threadPropsPanel.add(new JLabel(JMeterUtils.getResString("thread_group_duration_policy")));
        threadPropsPanel.add(durationPolicy, "w pref!, growx 0");
        durationPolicy.addActionListener(e -> {
            updateDurationPolicyFields();
            updatePreviewGraph();
        });

        // LOOP COUNT
        LoopControlPanel loopController = (LoopControlPanel) createControllerPanel();
        loopController.getInfinite().setVisible(false);
        threadPropsPanel.add(loopController.getLoopsLabel());
        threadPropsPanel.add(loopController.getLoops());

        threadPropsPanel.add(durationLabel);
        threadPropsPanel.add(duration);
        threadPropsPanel.add(delayLabel);
        threadPropsPanel.add(delay);
        threadPropsPanel.add(createSameUserPanel(), "span 2");
        if (showDelayedStart) {
            delayedStart = new JBooleanPropertyEditor(
                    ThreadGroupSchema.INSTANCE.getDelayedStart(),
                    "delayed_start",
                    JMeterUtils::getResString); // $NON-NLS-1$
            threadPropsPanel.add(delayedStart, "span 2");
        }
        addPacingControls(threadPropsPanel);
        return threadPropsPanel;
    }

    private JPanel createOpenModelPanel() {
        JPanel panel = new JPanel(new MigLayout("fillx, wrap 1", "[fill,grow]"));
        panel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("thread_group_model_open")));

        JPanel scheduleHeader = new JPanel(new MigLayout("insets 0, fillx", "[][6][fill,grow]"));
        scheduleHeader.add(labelFor(openModelSchedule, "openmodelthreadgroup_schedule_string"), "grow 0");
        scheduleHeader.add(createScheduleHelperPanel(), "growx");
        panel.add(scheduleHeader, "growx");

        panel.add(new JScrollPane(openModelSchedule), "w 720!, growx 0");

        JPanel randomSeedPanel = new JPanel(new MigLayout("insets 0", "[][fill]"));
        randomSeedPanel.add(labelFor(openModelRandomSeed, "openmodelthreadgroup_random_seed"), "grow 0");
        randomSeedPanel.add(openModelRandomSeed, "w pref!, growx 0");
        panel.add(randomSeedPanel, "growx");

        JPanel maxThreadsPanel = new JPanel(new MigLayout("insets 0", "[][fill][6][]"));
        maxThreadsPanel.add(labelFor(openModelMaxThreads, "thread_group_open_model_max_threads"), "grow 0");
        maxThreadsPanel.add(openModelMaxThreads, "w pref!, growx 0");
        maxThreadsPanel.add(new JLabel(JMeterUtils.getResString("thread_group_open_model_max_threads_scope")), "grow 0");
        maxThreadsPanel.add(openModelMaxThreadsScope, "w pref!, growx 0");
        panel.add(maxThreadsPanel, "growx");
        return panel;
    }

    private JPanel createScheduleHelperPanel() {
        JPanel helperPanel = new JPanel(new MigLayout("insets 0, fillx", "[][6][][6][]push"));
        helperPanel.add(openModelScheduleFunction, "w 260!, growx 0");
        helperPanel.add(openModelScheduleFunctionCards, "growx 0");
        JButton addButton = new JButton(JMeterUtils.getResString("add")); // $NON-NLS-1$
        addButton.setRequestFocusEnabled(false);
        addButton.addActionListener(event -> insertOpenModelScheduleExpression(buildOpenModelScheduleExpression()));
        helperPanel.add(addButton, "w pref!, growx 0");

        openModelScheduleFunctionCards.add(createConstantScheduleFields(), SCHEDULE_HELPER_CARD_CONSTANT);
        openModelScheduleFunctionCards.add(createRampScheduleFields(), SCHEDULE_HELPER_CARD_RAMP);
        openModelScheduleFunctionCards.add(createRateScheduleFields(), SCHEDULE_HELPER_CARD_RATE);
        openModelScheduleFunctionCards.add(createDurationScheduleFields(), SCHEDULE_HELPER_CARD_DURATION);
        openModelScheduleFunctionCards.add(new JPanel(new MigLayout("insets 0")), SCHEDULE_HELPER_CARD_EMPTY);
        openModelScheduleFunction.addActionListener(event -> updateScheduleHelperFields());
        updateScheduleHelperFields();
        return helperPanel;
    }

    private JPanel createConstantScheduleFields() {
        JPanel panel = new JPanel(new MigLayout("insets 0", "[][pref!][3][][6][][pref!][3][]"));
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_at")));
        panel.add(openModelConstantRate, "w 48!, growx 0");
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_threads_per_min")));
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_in")));
        panel.add(openModelConstantDuration, "w 48!, growx 0");
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_seconds_lower")));
        return panel;
    }

    private JPanel createRampScheduleFields() {
        JPanel panel = new JPanel(new MigLayout("insets 0", "[][pref!][3][][6][][pref!][3][][6][][pref!][3][]"));
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_from")));
        panel.add(openModelRampFromRate, "w 48!, growx 0");
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_threads_per_min")));
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_to")));
        panel.add(openModelRampToRate, "w 48!, growx 0");
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_threads_per_min")));
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_in")));
        panel.add(openModelRampDuration, "w 48!, growx 0");
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_seconds_lower")));
        return panel;
    }

    private JPanel createRateScheduleFields() {
        JPanel panel = new JPanel(new MigLayout("insets 0", "[][pref!][8][][pref!]"));
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_rate")));
        panel.add(openModelRateValue, "w 48!, growx 0");
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_unit")));
        panel.add(openModelRateUnit, "w 76!, growx 0");
        return panel;
    }

    private JPanel createDurationScheduleFields() {
        JPanel panel = new JPanel(new MigLayout("insets 0", "[][pref!][8][][pref!]"));
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_duration")));
        panel.add(openModelDurationValue, "w 48!, growx 0");
        panel.add(new JLabel(JMeterUtils.getResString("thread_group_schedule_unit")));
        panel.add(openModelDurationUnit, "w 76!, growx 0");
        return panel;
    }

    private void updateScheduleHelperFields() {
        String function = (String) openModelScheduleFunction.getSelectedItem();
        String card;
        if (SCHEDULE_HELPER_CONSTANT.equals(function)) {
            card = SCHEDULE_HELPER_CARD_CONSTANT;
        } else if (SCHEDULE_HELPER_RAMP.equals(function)) {
            card = SCHEDULE_HELPER_CARD_RAMP;
        } else if (SCHEDULE_HELPER_RATE.equals(function)) {
            card = SCHEDULE_HELPER_CARD_RATE;
        } else if (SCHEDULE_HELPER_COMMENT.equals(function)) {
            card = SCHEDULE_HELPER_CARD_EMPTY;
        } else {
            card = SCHEDULE_HELPER_CARD_DURATION;
        }
        ((CardLayout) openModelScheduleFunctionCards.getLayout()).show(openModelScheduleFunctionCards, card);
        openModelScheduleFunctionCards.revalidate();
        openModelScheduleFunctionCards.repaint();
    }

    private String buildOpenModelScheduleExpression() {
        String function = (String) openModelScheduleFunction.getSelectedItem();
        if (SCHEDULE_HELPER_RAMP.equals(function)) {
            return SCHEDULE_HELPER_RAMP + "(" + helperValue(openModelRampFromRate, "10") + ", " // $NON-NLS-1$ // $NON-NLS-2$
                    + helperValue(openModelRampToRate, "20") + ", " // $NON-NLS-1$ // $NON-NLS-2$
                    + helperValue(openModelRampDuration, "10") + ")"; // $NON-NLS-1$ // $NON-NLS-2$
        }
        if (SCHEDULE_HELPER_RATE.equals(function)) {
            return SCHEDULE_HELPER_RATE + "(" + helperValue(openModelRateValue, "1") + "/" // $NON-NLS-1$ // $NON-NLS-2$ // $NON-NLS-3$
                    + openModelRateUnit.getSelectedItem() + ")"; // $NON-NLS-1$
        }
        if (SCHEDULE_HELPER_EVEN.equals(function) || SCHEDULE_HELPER_RANDOM.equals(function)
                || SCHEDULE_HELPER_PAUSE.equals(function)) {
            return function + "(" + helperValue(openModelDurationValue, "10") + " " // $NON-NLS-1$ // $NON-NLS-2$ // $NON-NLS-3$
                    + openModelDurationUnit.getSelectedItem() + ")"; // $NON-NLS-1$
        }
        if (SCHEDULE_HELPER_COMMENT.equals(function)) {
            return "/* comment */"; // $NON-NLS-1$
        }
        return SCHEDULE_HELPER_CONSTANT + "(" + helperValue(openModelConstantRate, "20") + ", " // $NON-NLS-1$ // $NON-NLS-2$
                + helperValue(openModelConstantDuration, "15") + ")"; // $NON-NLS-1$ // $NON-NLS-2$
    }

    private static String helperValue(JTextField field, String defaultValue) {
        String value = field.getText().trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private void insertOpenModelScheduleExpression(String expression) {
        String originalText = openModelSchedule.getText();
        String replacement = expression;
        int selectionStart = openModelSchedule.getSelectionStart();
        int selectionEnd = openModelSchedule.getSelectionEnd();
        if (selectionStart == selectionEnd) {
            if (selectionStart > 0 && originalText.charAt(selectionStart - 1) != '\n') {
                replacement = "\n" + replacement; // $NON-NLS-1$
            }
            if (selectionEnd < originalText.length() && originalText.charAt(selectionEnd) != '\n') {
                replacement = replacement + "\n"; // $NON-NLS-1$
            }
        }
        openModelSchedule.replaceSelection(replacement);
    }

    private void updateModelFields() {
        CardLayout layout = (CardLayout) modelCards.getLayout();
        layout.show(modelCards, isOpenModelSelected() ? OPEN_MODEL_CARD : CLOSED_MODEL_CARD);
        CardLayout previewLayout = (CardLayout) previewCards.getLayout();
        previewLayout.show(previewCards, isOpenModelSelected() ? PREVIEW_OPEN_CARD : PREVIEW_CLOSED_CARD);
        updatePreviewGraph();
        revalidate();
        repaint();
    }

    private void updatePreviewGraph() {
        if (isOpenModelSelected()) {
            updateOpenModelPreview();
        } else {
            updateClosedModelPreview();
        }
    }

    private void updateOpenModelPreview() {
        try {
            openModelPreview.updateSchedule(ThreadScheduleUtils.ThreadSchedule(openModelSchedule.getText()));
        } catch (Exception e) {
            // Keep the last valid chart visible while the user is editing an incomplete schedule.
        }
    }

    private void updateClosedModelPreview() {
        Long threads = parsePositiveLong(threadInput.getText());
        Long rampSeconds = parsePositiveLong(rampInput.getText());
        boolean finiteDuration = isDurationPolicySelected() && !duration.getText().trim().isEmpty();
        boolean delayedStart = !delay.getText().trim().isEmpty();
        Long durationSeconds = finiteDuration ? parsePositiveLong(duration.getText()) : null;
        Long delaySeconds = delayedStart ? parsePositiveLong(delay.getText()) : 0L;
        if (threads == null || rampSeconds == null || delaySeconds == null || threads <= 0 || rampSeconds < 0
                || delaySeconds < 0 || finiteDuration && durationSeconds == null) {
            closedModelPreview.showMessage(JMeterUtils.getResString("thread_group_preview_unavailable"));
            return;
        }

        ClosedModelPreviewData previewData = createClosedModelPreviewData(
                threads, rampSeconds, delaySeconds, durationSeconds, finiteDuration, isNoLimitPolicySelected());
        closedModelPreview.updateData(
                previewData.timeSeconds,
                previewData.threads,
                previewData.title,
                JMeterUtils.getResString("thread_group_preview_threads_axis"),
                isNoLimitPolicySelected());
    }

    private static ClosedModelPreviewData createClosedModelPreviewData(
            long threads, long rampSeconds, long delaySeconds, Long durationSeconds, boolean finiteDuration,
            boolean noLimitPolicy) {
        boolean finite = finiteDuration && durationSeconds != null && durationSeconds > 0;
        long rampEndSeconds = delaySeconds + rampSeconds;
        double endSeconds = finite
                ? delaySeconds + Math.max(durationSeconds, rampSeconds)
                : Math.max(rampEndSeconds + 1d, delaySeconds + Math.max(60d, rampSeconds * 1.35d));
        String title = JMeterUtils.getResString(closedModelPreviewTitleKey(finite, noLimitPolicy));

        if (rampSeconds == 0) {
            if (delaySeconds > 0) {
                return new ClosedModelPreviewData(
                        new double[] {0d, delaySeconds, delaySeconds, endSeconds},
                        new double[] {0d, 0d, threads, threads},
                        title);
            }
            return new ClosedModelPreviewData(
                    new double[] {0d, endSeconds},
                    new double[] {threads, threads},
                    title);
        }

        if (delaySeconds > 0) {
            return new ClosedModelPreviewData(
                    new double[] {0d, delaySeconds, rampEndSeconds, endSeconds},
                    new double[] {0d, 0d, threads, threads},
                    title);
        }
        return new ClosedModelPreviewData(
                new double[] {0d, rampEndSeconds, endSeconds},
                new double[] {0d, threads, threads},
                title);
    }

    private static String closedModelPreviewTitleKey(boolean finite, boolean noLimitPolicy) {
        if (finite) {
            return "thread_group_preview_closed_title"; // $NON-NLS-1$
        }
        return noLimitPolicy
                ? "thread_group_preview_closed_title_forever" // $NON-NLS-1$
                : "thread_group_preview_closed_title_loop_count"; // $NON-NLS-1$
    }

    private void addPreviewDocumentListener(JTextField field) {
        field.getDocument().addDocumentListener(new PreviewDocumentListener());
    }

    private JPanel createSameUserPanel() {
        JPanel sameUserPanel = new JPanel(new MigLayout("insets 0", "[][6][]"));
        sameUserPanel.add(sameUserBox);
        sameUserPanel.add(sameUserInfo);
        return sameUserPanel;
    }

    private void addPacingControls(JPanel threadPropsPanel) {
        JPanel pacingOptionPanel = new JPanel(new MigLayout("insets 0, fillx", "[][10][][6][]push"));
        pacingOptionPanel.add(pacingMode, "w pref!, growx 0");
        pacingOptionPanel.add(pacingRate);
        pacingOptionPanel.add(pacingInfo);
        pacingMode.addActionListener(e -> updatePacingFields());

        fixedPacingFieldPanel = createPacingFieldPanel(fixedPacing);
        pacingMinFieldPanel = createPacingFieldPanel(pacingMin);
        pacingMaxFieldPanel = createPacingFieldPanel(pacingMax);

        threadPropsPanel.add(new JLabel(JMeterUtils.getResString("thread_group_pacing_option")));
        threadPropsPanel.add(pacingOptionPanel, "growx");
        threadPropsPanel.add(fixedPacingLabel);
        threadPropsPanel.add(fixedPacingFieldPanel, "growx");
        threadPropsPanel.add(pacingMinLabel);
        threadPropsPanel.add(pacingMinFieldPanel, "growx");
        threadPropsPanel.add(pacingMaxLabel);
        threadPropsPanel.add(pacingMaxFieldPanel, "growx");

        addPacingDocumentListener(fixedPacing);
        addPacingDocumentListener(pacingMin);
        addPacingDocumentListener(pacingMax);
        updatePacingFields();
    }

    private static JPanel createPacingFieldPanel(JTextField field) {
        JPanel panel = new JPanel(new MigLayout("insets 0", "[pref!]"));
        panel.add(field, "w pref!, growx 0");
        return panel;
    }

    private void updatePacingFields() {
        String mode = (String) pacingMode.getSelectedItem();
        boolean fixed = AbstractThreadGroup.PACING_FIXED.equals(mode);
        boolean random = AbstractThreadGroup.PACING_RANDOM.equals(mode)
                || AbstractThreadGroup.PACING_GAUSSIAN_RANDOM.equals(mode);

        fixedPacingLabel.setVisible(fixed);
        fixedPacingFieldPanel.setVisible(fixed);
        pacingMinLabel.setVisible(random);
        pacingMinFieldPanel.setVisible(random);
        pacingMaxLabel.setVisible(random);
        pacingMaxFieldPanel.setVisible(random);
        pacingRate.setText(formatPacingRate(mode, fixedPacing.getText(), pacingMin.getText(), pacingMax.getText()));
        pacingRate.setVisible(!pacingRate.getText().isEmpty());
        pacingInfo.setVisible(!AbstractThreadGroup.PACING_DISABLED.equals(mode));
        revalidate();
        repaint();
    }

    private void addPacingDocumentListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updatePacingFields();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updatePacingFields();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updatePacingFields();
            }
        });
    }

    static String formatPacingRate(String mode, String fixedPacing, String pacingMin, String pacingMax) {
        Long pacing = switch (mode) {
            case AbstractThreadGroup.PACING_FIXED -> parsePositiveLong(fixedPacing);
            case AbstractThreadGroup.PACING_RANDOM, AbstractThreadGroup.PACING_GAUSSIAN_RANDOM ->
                    averagePacing(pacingMin, pacingMax);
            default -> null;
        };
        if (pacing == null || pacing <= 0) {
            return ""; // $NON-NLS-1$
        }
        double perMinute = 60_000d / pacing;
        String pattern = JMeterUtils.getResString("thread_group_pacing_rate");
        if (pattern.startsWith("[res_key=")) { // $NON-NLS-1$
            pattern = "{0} iterations/min"; // $NON-NLS-1$
        }
        return MessageFormat.format(pattern, formatPacingRateValue(perMinute));
    }

    private static String formatPacingRateValue(double perMinute) {
        return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(perMinute);
    }

    private static Long averagePacing(String pacingMin, String pacingMax) {
        Long min = parsePositiveLong(pacingMin);
        Long max = parsePositiveLong(pacingMax);
        if (min == null || max == null || max < min) {
            return null;
        }
        return min + (max - min) / 2;
    }

    private static Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class ClosedModelPreviewData {
        private final double[] timeSeconds;
        private final double[] threads;
        private final String title;

        private ClosedModelPreviewData(double[] timeSeconds, double[] threads, String title) {
            this.timeSeconds = timeSeconds;
            this.threads = threads;
            this.title = title;
        }
    }

    private final class PreviewDocumentListener implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent event) {
            updatePreviewGraph();
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            updatePreviewGraph();
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
            updatePreviewGraph();
        }
    }

}
