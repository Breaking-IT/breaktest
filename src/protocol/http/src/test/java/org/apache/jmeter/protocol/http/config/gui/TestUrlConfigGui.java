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

package org.apache.jmeter.protocol.http.config.gui;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.Map;

import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.protocol.http.gui.HTTPArgumentsPanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.gui.JLabeledTextField;
import org.apache.jorphan.gui.ObjectTableModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestUrlConfigGui {

    @Test
    public void testTabCountUpdateDoesNotReenterWhileParameterCellIsEditing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UrlConfigGui gui = new UrlConfigGui();
                JTable table = argumentTable(gui);
                ObjectTableModel model = (ObjectTableModel) table.getModel();
                model.addRow(new HTTPArgument("name", "value"));

                Assertions.assertTrue(table.editCellAt(0, 1));
                ((JTextField) table.getEditorComponent()).setText("edited");

                model.setValueAt("external update", 0, 1);

                Assertions.assertFalse(table.isEditing());
                Assertions.assertEquals("edited", model.getValueAt(0, 1));
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    public void testModernUrlBarGivesHostMoreSpaceThanPort() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
                gui.setSize(1400, 700);
                layoutRecursively(gui);

                JLabeledTextField host = labeledField(gui, "domain");
                JLabeledTextField port = labeledField(gui, "port");
                Assertions.assertTrue(host.getWidth() >= 260);
                Assertions.assertEquals(100, port.getWidth());
                Assertions.assertTrue(host.getWidth() > port.getWidth() * 2);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    public void testRedirectHandlingIsStoredAsOneExclusiveChoice() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
                RedirectHandlingSelector selector = redirectHandlingSelector(gui);
                HTTPSamplerProxy sampler = new HTTPSamplerProxy();

                selector.setRedirects(false, false);
                gui.modifyTestElement(sampler);
                Assertions.assertFalse(sampler.getFollowRedirects());
                Assertions.assertFalse(sampler.getAutoRedirects());

                selector.setRedirects(true, false);
                gui.modifyTestElement(sampler);
                Assertions.assertTrue(sampler.getFollowRedirects());
                Assertions.assertFalse(sampler.getAutoRedirects());

                selector.setRedirects(false, true);
                gui.modifyTestElement(sampler);
                Assertions.assertFalse(sampler.getFollowRedirects());
                Assertions.assertTrue(sampler.getAutoRedirects());
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    public void testAutomaticRedirectHandlingWinsForLegacyInvalidCombination() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setFollowRedirects(true);
            sampler.setAutoRedirects(true);

            gui.configure(sampler);
            gui.modifyTestElement(sampler);

            Assertions.assertFalse(sampler.getFollowRedirects());
            Assertions.assertTrue(sampler.getAutoRedirects());
        });
    }

    @Test
    public void testModernPathGetsItsOwnFullWidthRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
                gui.setSize(1400, 700);
                layoutRecursively(gui);

                JLabeledTextField host = labeledField(gui, "domain");
                Field pathField = UrlConfigGui.class.getDeclaredField("path");
                pathField.setAccessible(true);
                Component path = (Component) pathField.get(gui);
                Assertions.assertTrue(path.getY() > host.getY(), "Path sits below the host row");
                Assertions.assertTrue(path.getWidth() > host.getWidth(), "Path is wider than the host field");
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    public void testQueryMovesToParamsWithoutChangingTheUrl() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                HTTPSamplerProxy sampler = getSampler("/a/b?x=1&flag&empty=&q=hello%20world&eq=a=b&s=two words+plus%25");
                HTTPArgument existing = new HTTPArgument("p", "v w");
                existing.setAlwaysEncoded(true);
                sampler.getArguments().addArgument(existing);
                String before = sampler.getUrl().toString();

                UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
                gui.configure(sampler);
                Assertions.assertTrue(gui.canMoveQueryToParams());
                gui.moveQueryToParams();

                HTTPSamplerProxy after = new HTTPSamplerProxy();
                gui.modifyTestElement(after);
                Assertions.assertEquals("/a/b", after.getPath());
                Assertions.assertEquals(7, after.getArguments().getArgumentCount());
                Assertions.assertEquals("hello%20world", after.getArguments().getArgument(3).getValue(),
                        "already-encoded text is kept, not encoded again");
                Assertions.assertEquals("two words+plus%25", after.getArguments().getArgument(5).getValue(),
                        "the value is kept as typed; the sampler sends the space as %20");
                Assertions.assertEquals(before, after.getUrl().toString());
                Assertions.assertFalse(gui.canMoveQueryToParams());
                // The existing row is URL-encoded, so it cannot be moved back as-is
                Assertions.assertFalse(gui.canMoveParamsToQuery());
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    public void testParamsMoveToQueryWithoutChangingTheUrl() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                HTTPSamplerProxy sampler = getSampler("/a?first=0");
                sampler.getArguments().addArgument(unencoded("x", "1", true));
                sampler.getArguments().addArgument(unencoded("flag", "", false));
                HTTPArgument disabled = unencoded("off", "1", true);
                disabled.setEnabled(false);
                sampler.getArguments().addArgument(disabled);
                String before = sampler.getUrl().toString();

                UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
                gui.configure(sampler);
                Assertions.assertTrue(gui.canMoveQueryToParams(), "Path query takes precedence");
                gui.moveQueryToParams();
                Assertions.assertTrue(gui.canMoveParamsToQuery());
                gui.moveParamsToQuery();

                HTTPSamplerProxy after = new HTTPSamplerProxy();
                gui.modifyTestElement(after);
                Assertions.assertEquals("/a?first=0&x=1&flag", after.getPath());
                Assertions.assertEquals(1, after.getArguments().getArgumentCount(), "Disabled row stays");
                Assertions.assertEquals(before, after.getUrl().toString());
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    public void testParamsStayInTableForDynamicPath() throws Exception {
        assertParamsMoveAtRuntime("${resource}", "y", "2", Map.of("resource", "/p?x=1"),
                false, "https://example.com/p?x=1&y=2");
        assertParamsMoveAtRuntime("${resource}", "y", "2", Map.of("resource", "https://example.com/p"),
                false, "https://example.com/p");
    }

    @Test
    public void testParamsStayInTableForOptionalName() throws Exception {
        assertParamsMoveAtRuntime("/p", "${optional}", "2", Map.of("optional", ""),
                false, "https://example.com/p");
        assertParamsMoveAtRuntime("/p", "${optional}", "2", Map.of("optional", "y"),
                false, "https://example.com/p?y=2");
    }

    @Test
    public void testParamsWithDynamicValueCanStillMove() throws Exception {
        assertParamsMoveAtRuntime("/p", "y", "${value}", Map.of("value", "two words"),
                true, "https://example.com/p?y=two%20words");
    }

    private static void assertParamsMoveAtRuntime(String path, String name, String value,
            Map<String, String> variables, boolean movable, String expectedUrl) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                HTTPSamplerProxy before = getSampler(path);
                before.getArguments().addArgument(unencoded(name, value, true));
                UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
                gui.configure(before);
                Assertions.assertEquals(movable, gui.canMoveParamsToQuery());
                // The action must also enforce the guard when invoked directly.
                gui.moveParamsToQuery();
                HTTPSamplerProxy after = new HTTPSamplerProxy();
                gui.modifyTestElement(after);
                Assertions.assertEquals(movable ? 0 : 1, after.getArguments().getArgumentCount());
                if (!movable) {
                    Assertions.assertEquals(path, after.getPath());
                }

                assertRuntimeUrls(before, after, variables, expectedUrl);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            } finally {
                JMeterContextService.getContext().clear();
            }
        });
    }

    @Test
    public void testParamsStayInTableWithVariableAfterLeadingSlash() throws Exception {
        assertParamsMoveAtRuntime("/p/${id}", "y", "2", Map.of("id", "7"),
                false, "https://example.com/p/7?y=2");
    }

    @Test
    public void testParamsStayInTableWhenPathVariableIntroducesQuery() throws Exception {
        assertParamsMoveAtRuntime("/p/${id}", "y", "2", Map.of("id", "7?x=1"),
                false, "https://example.com/p/7?x=1&y=2");
    }

    @Test
    public void testQueryStaysInPathWhenPathVariableIntroducesQuery() throws Exception {
        assertQueryMoveAtRuntime("/p/${id}?y=2", Map.of("id", "7?x=1"),
                false, "https://example.com/p/7?x=1?y=2");
    }

    @Test
    public void testQueryStaysInPathWhenPathCouldBeFullUrl() throws Exception {
        assertQueryMoveAtRuntime("${baseUrl}/p?x=1", Map.of("baseUrl", "https://example.com"),
                false, "https://example.com/p?x=1");
    }

    @Test
    public void testQueryStaysInPathForOptionalName() throws Exception {
        assertQueryMoveAtRuntime("/p?${optional}=1", Map.of("optional", ""),
                false, "https://example.com/p?=1");
    }

    @Test
    public void testQueryWithVariablesMovesWithoutChangingTheUrl() throws Exception {
        assertQueryMoveAtRuntime("/a/7/b?x=${value}&y=1", Map.of("value", "two words"),
                true, "https://example.com/a/7/b?x=two%20words&y=1");
    }

    @Test
    public void testMoveActionsCheckTheMethodWhenCalledDirectly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HTTPSamplerProxy post = getSampler("/p?x=1");
            post.setMethod(HTTPConstants.POST);
            UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
            gui.configure(post);
            gui.moveQueryToParams();

            HTTPSamplerProxy after = new HTTPSamplerProxy();
            gui.modifyTestElement(after);
            Assertions.assertEquals("/p?x=1", after.getPath());
            Assertions.assertEquals(0, after.getArguments().getArgumentCount());
        });
    }

    private static void assertQueryMoveAtRuntime(String path, Map<String, String> variables,
            boolean movable, String expectedUrl) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                HTTPSamplerProxy before = getSampler(path);
                UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
                gui.configure(before);
                Assertions.assertEquals(movable, gui.canMoveQueryToParams());
                // The action must also enforce the guard when invoked directly.
                gui.moveQueryToParams();
                HTTPSamplerProxy after = new HTTPSamplerProxy();
                gui.modifyTestElement(after);
                if (!movable) {
                    Assertions.assertEquals(path, after.getPath());
                    Assertions.assertEquals(0, after.getArguments().getArgumentCount());
                }
                assertRuntimeUrls(before, after, variables, expectedUrl);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            } finally {
                JMeterContextService.getContext().clear();
            }
        });
    }

    /** Resolve variables as a running test would, then compare the URLs both samplers send. */
    private static void assertRuntimeUrls(HTTPSamplerProxy before, HTTPSamplerProxy after,
            Map<String, String> variables, String expectedUrl) throws Exception {
        JMeterVariables vars = new JMeterVariables();
        vars.putAll(variables);
        JMeterContextService.getContext().setVariables(vars);
        JMeterContextService.getContext().setSamplingStarted(true);
        ValueReplacer replacer = new ValueReplacer();
        replacer.setUserDefinedVariables(variables);
        replacer.replaceValues(before);
        replacer.replaceValues(after);
        before.setRunningVersion(true);
        after.setRunningVersion(true);
        Assertions.assertEquals(expectedUrl, before.getUrl().toString());
        Assertions.assertEquals(expectedUrl, after.getUrl().toString());
    }

    @Test
    public void testQueryStaysInPathWhenParamsWouldChangeTheRequest() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            UrlConfigGui gui = new UrlConfigGui(true, true, true, true);

            HTTPSamplerProxy post = getSampler("/a?x=1");
            post.setMethod(HTTPConstants.POST);
            gui.configure(post);
            Assertions.assertFalse(gui.canMoveQueryToParams(), "POST sends Params as the body");

            gui.configure(getSampler("/a?&x=1"));
            Assertions.assertFalse(gui.canMoveQueryToParams(), "the sampler drops nameless Params");

            gui.configure(getSampler("/a?"));
            Assertions.assertFalse(gui.canMoveQueryToParams());

            HTTPSamplerProxy fullUrl = getSampler("https://example.com/a?x=1");
            fullUrl.getArguments().addArgument(unencoded("y", "2", true));
            gui.configure(fullUrl);
            Assertions.assertFalse(gui.canMoveQueryToParams(), "a full-URL path ignores Params");
            Assertions.assertFalse(gui.canMoveParamsToQuery(), "a full-URL path ignores Params");
        });
    }

    @Test
    public void testQuestionMarkInsideFunctionIsNotTheQueryStart() {
        String resource = "/a/${__groovy(1 ? 'b' : 'c')}/d";
        PathQuery.Split split = PathQuery.split(resource + "?x=1");
        Assertions.assertNull(split, "a dynamic resource cannot safely move its query");
        Assertions.assertEquals(resource + "?x=1", PathQuery.appendQuery(resource, "x=1"));
    }

    @Test
    public void testPathQueryKeepsVariablesTogether() {
        String query = "id=${__groovy(a&b=c, d e)}&name=${value}&plain";
        PathQuery.Split split = PathQuery.split("/p?" + query);
        Assertions.assertNotNull(split);
        Assertions.assertEquals("/p", split.path());
        Assertions.assertEquals(3, split.parameters().size());
        Assertions.assertEquals("${__groovy(a&b=c, d e)}", split.parameters().get(0).getValue());
        Assertions.assertEquals("${value}", split.parameters().get(1).getValue());
        Assertions.assertNull(PathQuery.split("/p?${name}=1"), "a dynamic name may resolve to empty");
        Assertions.assertEquals(query, PathQuery.join(split.parameters()));
    }

    @Test
    public void testPathFieldWrapsLongPathsAndStaysSingleLine() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            UrlPathField field = new UrlPathField("Path:");
            field.setText("/a\n/b\r\n/c");
            Assertions.assertEquals("/a/b/c", field.getText());

            field.setText("/segment".repeat(60));
            field.setSize(300, 200);
            layoutRecursively(field);
            Assertions.assertTrue(field.contentRows() > 1);
            Assertions.assertEquals(1, field.getVisibleRows(), "collapsed without focus");
        });
    }

    private static HTTPSamplerProxy getSampler(String path) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
        sampler.setDomain("example.com");
        sampler.setMethod(HTTPConstants.GET);
        sampler.setArguments(new Arguments());
        // As loaded from a plan: setPath() would already split a GET query into arguments
        sampler.setProperty(HTTPSamplerBase.PATH, path);
        return sampler;
    }

    private static HTTPArgument unencoded(String name, String value, boolean useEquals) {
        HTTPArgument argument = new HTTPArgument(name, value);
        argument.setAlwaysEncoded(false);
        argument.setUseEquals(useEquals);
        return argument;
    }

    private static JTable argumentTable(UrlConfigGui gui) throws ReflectiveOperationException {
        Field argsPanelField = UrlConfigGui.class.getDeclaredField("argsPanel");
        argsPanelField.setAccessible(true);
        HTTPArgumentsPanel argsPanel = (HTTPArgumentsPanel) argsPanelField.get(gui);

        Field tableField = ArgumentsPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        return (JTable) tableField.get(argsPanel);
    }

    private static JLabeledTextField labeledField(UrlConfigGui gui, String name)
            throws ReflectiveOperationException {
        Field field = UrlConfigGui.class.getDeclaredField(name);
        field.setAccessible(true);
        return (JLabeledTextField) field.get(gui);
    }

    private static RedirectHandlingSelector redirectHandlingSelector(UrlConfigGui gui)
            throws ReflectiveOperationException {
        Field field = UrlConfigGui.class.getDeclaredField("redirectHandling");
        field.setAccessible(true);
        return (RedirectHandlingSelector) field.get(gui);
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container childContainer) {
                layoutRecursively(childContainer);
            }
        }
    }
}
