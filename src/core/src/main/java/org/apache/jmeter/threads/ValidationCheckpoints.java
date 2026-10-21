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

package org.apache.jmeter.threads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestIterationListener;
import org.apache.jorphan.collections.HashTree;

/** In-memory checkpoints belonging to the most recent GUI validation. Never saved in a JMX. */
public final class ValidationCheckpoints {
    private static final String SOURCE_ID = "__breaktest_validation_source_id";
    /** Source sampler id to the latest execution id per thread group source id. */
    private static final Map<String, Map<String, String>> LATEST = new HashMap<>();
    private static final Map<String, String> FAILED_SAMPLERS = new HashMap<>();
    private static final Map<String, AbstractThreadGroup> GROUPS = new HashMap<>();
    // Weak keys: a result cleared from the results tree releases its replay target.
    private static final Map<SampleResult, ReplayTarget> RESULTS = new WeakHashMap<>();
    private static final String ID = "__breaktest_validation_id";
    private static final IdentityHashMap<TestElement, String> IDS = new IdentityHashMap<>();
    private static final Map<String, Checkpoint> CHECKPOINTS = new LinkedHashMap<>();
    private static final Map<String, String> FAILURES = new HashMap<>();
    private static final Set<String> STATELESS_CONFIGS = Set.of(
            "org.apache.jmeter.config.ConfigTestElement",
            "org.apache.jmeter.config.Arguments",
            "org.apache.jmeter.config.LoginConfig",
            "org.apache.jmeter.protocol.http.control.HeaderManager");
    private static final Map<String, List<String>> LAYOUTS = new HashMap<>();
    private static final Map<String, Checkpoint> PENDING = new HashMap<>();
    private static long generation;
    /** Copies to the elements they copy, while {@link #withSourceIdentity} runs */
    private static final ThreadLocal<IdentityHashMap<TestElement, TestElement>> COPIES = new ThreadLocal<>();

    private ValidationCheckpoints() {
    }

    public record Checkpoint(String samplerId, Map<String, Object> variables,
            Map<String, Object> managers, SampleResult previous, List<String> structure) {
    }

    public static final class RestoreException extends IllegalStateException {
        private RestoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record ReplayTarget(Checkpoint checkpoint, AbstractThreadGroup group) {
    }

    public static synchronized ReplayTarget getReplayTarget(SampleResult result) {
        ReplayTarget target = RESULTS.get(result);
        if (target == null) {
            throw new IllegalStateException("No checkpoint is available for this result. Run a fresh validation first.");
        }
        return target;
    }

    public static synchronized boolean canReplay(SampleResult result) {
        return RESULTS.containsKey(result);
    }

    public static synchronized void clear() {
        IDS.clear();
        GROUPS.clear();
        RESULTS.clear();
        LATEST.clear();
        FAILED_SAMPLERS.clear();
        CHECKPOINTS.clear();
        FAILURES.clear();
        PENDING.clear();
        LAYOUTS.clear();
        generation++;
    }

    public static synchronized void inheritSourceIdentity(TestElement source, TestElement clone) {
        if (JMeterContextService.isValidationRun()) {
            IDS.put(clone, sourceId(source));
        } else if (COPIES.get() != null) {
            COPIES.get().put(clone, source);
        }
    }

    /**
     * Runs an action in which copies made outside a validation run (profile configuration, flattened sections) have
     * the identity of the element they copy, as they do during a validation run. Script readiness builds and analyses
     * the tree validation runs this way. The copies are only remembered until the outermost action ends.
     *
     * @param action builds and uses the tree
     * @param <T> result type
     * @return the result of the action
     */
    public static <T> T withSourceIdentity(Supplier<T> action) {
        boolean outermost = COPIES.get() == null;
        if (outermost) {
            COPIES.set(new IdentityHashMap<>());
        }
        try {
            return action.get();
        } finally {
            if (outermost) {
                COPIES.remove();
            }
        }
    }

    /**
     * Stable identity of a test plan element for the current validation generation, the same id the validation
     * clones of the element carry. Assigned on first use; never derived from the element's name.
     *
     * @param source element of the edited test plan
     * @return its source id
     */
    public static synchronized String sourceIdOfSource(TestElement source) {
        TestElement original = source;
        IdentityHashMap<TestElement, TestElement> copies = COPIES.get();
        if (copies != null) {
            for (int i = 0; i < 16 && copies.containsKey(original); i++) {
                original = copies.get(original);
            }
        }
        return sourceId(original);
    }

    /**
     * @param clone element of a validation tree
     * @return the source id of the element it was cloned from, empty when it is not a validation clone
     */
    public static String sourceIdOfClone(TestElement clone) {
        return clone.getPropertyAsString(SOURCE_ID);
    }

    /**
     * @param clone element of a validation tree
     * @return its execution id: the source ids of its enclosing controllers and its own, separated by {@code /}
     */
    public static String executionIdOf(TestElement clone) {
        return clone.getPropertyAsString(ID);
    }

    /**
     * @return the generation of source ids; it changes whenever validation history is cleared
     */
    public static synchronized long generation() {
        return generation;
    }

    private static String sourceId(TestElement source) {
        if (source instanceof AbstractThreadGroup group && !group.getThreadGroupId().isEmpty()) {
            return "group:" + group.getThreadGroupId();
        }
        return IDS.computeIfAbsent(source, ignored -> generation + ":" + IDS.size());
    }

    public static void register(TestElement source, TestElement clone) {
        register(source, clone, "");
    }

    public static synchronized String register(TestElement source, TestElement clone, String parent) {
        String sourceId = sourceId(source);
        if (source instanceof AbstractThreadGroup group) {
            GROUPS.put(sourceId, group);
        }
        String executionId = parent + "/" + sourceId;
        clone.setProperty(ID, executionId);
        clone.setProperty(SOURCE_ID, sourceId);
        return executionId;
    }

    private static String groupKey(AbstractThreadGroup group) {
        return group.getThreadGroupId().isEmpty() ? IDS.get(group) : "group:" + group.getThreadGroupId();
    }

    public static synchronized boolean canResumeFailedSampler(TestElement selected) {
        if (!(selected instanceof AbstractThreadGroup group)) {
            return false;
        }
        return CHECKPOINTS.containsKey(FAILED_SAMPLERS.get(groupKey(group)));
    }

    /** Whether validation reached this sampler, so resuming it can either start or explain why not. */
    public static synchronized boolean canResume(TestElement selected) {
        return selected != null && LATEST.containsKey(IDS.get(selected));
    }

    public static Checkpoint get(TestElement selected) {
        return get(selected, null);
    }

    /**
     * @param selected a thread group or a sampler
     * @param group the thread group the selected sampler belongs to, or {@code null} inside a test fragment
     */
    public static synchronized Checkpoint get(TestElement selected, AbstractThreadGroup group) {
        String samplerId;
        if (selected instanceof AbstractThreadGroup threadGroup) {
            samplerId = FAILED_SAMPLERS.get(sourceId(threadGroup));
            if (samplerId == null) {
                throw new IllegalStateException("This thread group has no failed sampler from the latest validation.");
            }
        } else {
            Map<String, String> invocations = LATEST.getOrDefault(IDS.get(selected), Map.of());
            if (group != null) {
                samplerId = invocations.get(groupKey(group));
            } else if (invocations.size() > 1) {
                throw new IllegalStateException("This sampler ran in several thread groups. "
                        + "Resume from the failed thread group or use Replay from here in the results tree.");
            } else {
                samplerId = invocations.values().stream().findFirst().orElse(null);
            }
        }
        Checkpoint checkpoint = CHECKPOINTS.get(samplerId);
        if (checkpoint == null) {
            throw new IllegalStateException(FAILURES.getOrDefault(samplerId,
                    "No checkpoint is available. Run validation until this sampler is reached first."));
        }
        return checkpoint;
    }

    /** The thread group whose validation captured the checkpoint. */
    public static synchronized AbstractThreadGroup groupOf(Checkpoint checkpoint) {
        // Execution ids start with the thread group's source id: "/<group>/<controller>/.../<sampler>".
        String[] path = checkpoint.samplerId().split("/");
        AbstractThreadGroup group = path.length > 1 ? GROUPS.get(path[1]) : null;
        if (group == null) {
            throw new IllegalStateException("No thread group is available for this checkpoint. Run a fresh validation first.");
        }
        return group;
    }

    public static synchronized void afterSampler(Sampler sampler, AbstractThreadGroup group, SampleResult result) {
        String groupId = group.getPropertyAsString(SOURCE_ID);
        Checkpoint checkpoint = CHECKPOINTS.get(id(sampler));
        AbstractThreadGroup sourceGroup = GROUPS.get(groupId);
        if (checkpoint != null && sourceGroup != null && !result.isIgnore()) {
            RESULTS.put(result, new ReplayTarget(checkpoint, sourceGroup));
        }
        if (!id(sampler).isEmpty() && !result.isSuccessful() && !result.isIgnore()) {
            FAILED_SAMPLERS.putIfAbsent(groupId, id(sampler));
        }
    }

    private static String id(TestElement element) {
        return element.getPropertyAsString(ID);
    }

    public static void beforeSampler(HashTree tree, Sampler sampler, JMeterContext context) {
        String samplerId = id(sampler);
        if (!JMeterContextService.isValidationRun() || samplerId.isEmpty()) {
            return;
        }
        AbstractThreadGroup threadGroup = context.getThreadGroup();
        String groupId = threadGroup == null ? "" : threadGroup.getPropertyAsString(SOURCE_ID);
        Checkpoint pending;
        List<String> layout;
        synchronized (ValidationCheckpoints.class) {
            LATEST.computeIfAbsent(sampler.getPropertyAsString(SOURCE_ID), ignored -> new LinkedHashMap<>())
                    .put(groupId, samplerId);
            pending = PENDING.remove(samplerId);
            layout = LAYOUTS.get(samplerId);
        }
        if (pending != null) {
            restore(tree, pending, context, groupId);
        }
        // The tree and the context belong to this thread, so only the shared maps need the lock.
        try {
            validateTree(tree, samplerId);
            Map<String, Object> variables = new LinkedHashMap<>();
            for (var entry : context.getVariables().entrySet()) {
                if (entry.getKey().equals(JMeterThread.PACKAGE_OBJECT)) {
                    continue;
                }
                Object value = entry.getValue();
                if (value != null && !(value instanceof String || value instanceof Boolean
                        || value instanceof Integer || value instanceof Long || value instanceof Double
                        || value instanceof Float || value instanceof Short || value instanceof Byte)) {
                    throw new IllegalStateException("Cannot restore object variable: " + entry.getKey());
                }
                variables.put(entry.getKey(), value);
            }
            Map<String, Object> managers = new HashMap<>();
            visit(tree, element -> {
                if (element instanceof ValidationCheckpointParticipant participant) {
                    managers.put(id(element), participant.captureValidationState());
                }
            });
            SampleResult previous = context.getPreviousResult();
            Checkpoint checkpoint = new Checkpoint(samplerId, variables, managers,
                    previous == null ? null : new SampleResult(previous),
                    layout == null ? layoutFor(tree, samplerId) : layout);
            synchronized (ValidationCheckpoints.class) {
                CHECKPOINTS.remove(samplerId);
                CHECKPOINTS.put(samplerId, checkpoint);
                while (CHECKPOINTS.size() > 256) {
                    String oldest = CHECKPOINTS.keySet().iterator().next();
                    CHECKPOINTS.remove(oldest);
                    FAILURES.put(oldest, "This checkpoint was evicted from the 256-checkpoint validation history.");
                }
                FAILURES.remove(samplerId);
            }
        } catch (IllegalStateException ex) {
            synchronized (ValidationCheckpoints.class) {
                CHECKPOINTS.remove(samplerId);
                FAILURES.put(samplerId, ex.getMessage());
            }
        }
    }

    private static void restore(HashTree tree, Checkpoint pending, JMeterContext context, String groupId) {
        try {
            context.getVariables().clear();
            context.getVariables().putAll(pending.variables());
            context.setPreviousResult(pending.previous() == null ? null : new SampleResult(pending.previous()));
            visit(tree, element -> {
                if (element instanceof ValidationCheckpointParticipant participant
                        && pending.managers().containsKey(id(element))) {
                    participant.restoreValidationState(pending.managers().get(id(element)));
                }
            });
        } catch (RuntimeException ex) {
            throw new RestoreException("Unable to restore validation checkpoint: " + ex.getMessage(), ex);
        }
        synchronized (ValidationCheckpoints.class) {
            String failedSampler = FAILED_SAMPLERS.get(groupId);
            // Only retire a previous failure if this replay will execute it again.
            if (failedSampler != null && contains(tree, failedSampler)) {
                FAILED_SAMPLERS.remove(groupId);
            }
        }
    }

    private static void validateTree(HashTree tree, String samplerId) {
        visit(tree, element -> {
            String className = element.getClass().getName();
            if (className.contains(".websocket.") || className.contains(".sse.")
                    || element instanceof ForkController
                    || element instanceof ParallelController) {
                throw new IllegalStateException("Resume cannot restore live or concurrent execution state: "
                        + element.getName());
            }
            if (element instanceof AbstractThreadGroup group) {
                if (group.getNumThreads() != 1
                        || !(group.getSamplerController() instanceof LoopController loop)
                        || loop.getLoops() != 1) {
                    throw new IllegalStateException("Resume validation requires one validation thread and one iteration.");
                }
            }
            // HTTP iteration hooks initialize a fresh transport; its managers are restored separately.
            boolean httpSampler = className.equals("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy")
                    || className.equals("org.apache.jmeter.protocol.http.sampler.HTTPSampler");
            if (!httpSampler && !(element instanceof Controller) && !(element instanceof ValidationCheckpointParticipant)
                    && (element instanceof LoopIterationListener
                            || element instanceof TestIterationListener)) {
                throw new IllegalStateException("Resume cannot restore iteration state: " + element.getName());
            }
            if (element instanceof ConfigTestElement
                    && !(element instanceof ValidationCheckpointParticipant)
                    && !STATELESS_CONFIGS.contains(element.getClass().getName())) {
                throw new IllegalStateException("Resume cannot restore configuration state: " + element.getName());
            }
        });
        validateAncestors(tree, samplerId);
    }

    private static void validateAncestors(HashTree tree, String samplerId) {
        for (Object key : tree.list()) {
            HashTree children = tree.getTree(key);
            if (!contains(children, samplerId)) {
                continue;
            }
            if (key instanceof Controller && !(key instanceof AbstractThreadGroup)
                    && key.getClass() != GenericController.class
                    && key.getClass() != TransactionController.class
                    && key.getClass() != TestFragmentController.class
                    && !key.getClass().getName().equals("org.apache.jmeter.control.ModuleController")) {
                throw new IllegalStateException("Resume cannot restore controller state: "
                        + ((TestElement) key).getName());
            }
            validateAncestors(children, samplerId);
        }
    }

    /** Keep configuration and listeners in scope while removing completed samplers. */
    public static synchronized void prepareResume(HashTree tree, Checkpoint checkpoint) {
        prepareResume(tree, List.of(checkpoint));
    }

    public static synchronized void prepareResume(HashTree tree, List<Checkpoint> checkpoints) {
        for (Checkpoint checkpoint : checkpoints) {
            if (!layoutFor(tree, checkpoint.samplerId()).equals(checkpoint.structure())) {
                throw new IllegalStateException("The validation structure changed. Run a fresh validation first.");
            }
            validateTree(tree, checkpoint.samplerId());
        }
        visit(tree, element -> {
            if (element instanceof ValidationCheckpointParticipant participant) {
                participant.checkResumable();
            }
        });
        // Resume runs record new checkpoints too; give them layouts of the unpruned tree.
        rememberStructure(tree);
        pruneGroups(tree, checkpoints);
        PENDING.clear();
        for (Checkpoint checkpoint : checkpoints) {
            PENDING.put(checkpoint.samplerId(), checkpoint);
        }
    }

    private static void pruneGroups(HashTree tree, List<Checkpoint> checkpoints) {
        for (Object key : new ArrayList<>(tree.list())) {
            HashTree children = tree.getTree(key);
            if (key instanceof AbstractThreadGroup) {
                Checkpoint checkpoint = checkpoints.stream()
                        .filter(candidate -> contains(children, candidate.samplerId())).findFirst().orElse(null);
                if (checkpoint == null) {
                    tree.remove(key);
                } else {
                    prune(children, checkpoint.samplerId(), false);
                }
            } else {
                pruneGroups(children, checkpoints);
            }
        }
    }

    public static synchronized void rememberStructure(HashTree tree) {
        Map<List<String>, List<String>> layouts = new HashMap<>();
        visit(tree, element -> {
            if (element instanceof Sampler) {
                List<String> layout = layoutFor(tree, id(element));
                LAYOUTS.put(id(element), layouts.computeIfAbsent(layout, List::copyOf));
            }
        });
    }

    private static List<String> layoutFor(HashTree tree, String target) {
        List<String> result = new ArrayList<>();
        appendLayout(tree, target, result);
        return result;
    }

    private static void appendLayout(HashTree tree, String target, List<String> result) {
        for (Object key : tree.list()) {
            if (key instanceof AbstractThreadGroup && !contains(tree.getTree(key), target)) {
                continue;
            }
            if (key instanceof TestElement element && !id(element).isEmpty()
                    && (element instanceof Sampler || element instanceof Controller
                            || element instanceof ValidationCheckpointParticipant)) {
                result.add(element.getClass().getName() + ":"
                        + (element instanceof Sampler || element instanceof ValidationCheckpointParticipant
                                ? id(element) : element.getName()));
                appendLayout(tree.getTree(key), target, result);
                result.add("/");
            } else {
                appendLayout(tree.getTree(key), target, result);
            }
        }
    }

    private static boolean contains(HashTree tree, String samplerId) {
        for (Object key : tree.list()) {
            if (key instanceof TestElement element && id(element).equals(samplerId)
                    || contains(tree.getTree(key), samplerId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean prune(HashTree tree, String samplerId, boolean reached) {
        for (Object key : new ArrayList<>(tree.list())) {
            if (key instanceof Sampler sampler) {
                reached |= id(sampler).equals(samplerId);
                if (!reached) {
                    tree.remove(key);
                }
            } else if (key instanceof Controller && !(key instanceof AbstractThreadGroup)
                    && !reached && !contains(tree.getTree(key), samplerId)) {
                tree.remove(key);
            } else {
                reached = prune(tree.getTree(key), samplerId, reached);
            }
        }
        return reached;
    }

    private static void visit(HashTree tree, Consumer<TestElement> consumer) {
        for (Object key : tree.list()) {
            if (key instanceof TestElement element) {
                consumer.accept(element);
            }
            visit(tree.getTree(key), consumer);
        }
    }
}
