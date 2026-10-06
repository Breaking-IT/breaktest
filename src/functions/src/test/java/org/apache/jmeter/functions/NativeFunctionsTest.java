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

package org.apache.jmeter.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NativeFunctionsTest extends JMeterTestCase {
    private JMeterVariables variables;

    @BeforeEach
    void prepareVariables() {
        variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
    }

    @AfterEach
    void clearContext() {
        JMeterContextService.getContext().clear();
    }

    static Stream<Arguments> expressions() {
        return Stream.of(
                Arguments.of("${__MD5(test)}", "098f6bcd4621d373cade4e832627b4f6"),
                Arguments.of("${__SHA256(abc)}", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                Arguments.of("${__SHA256(,)}", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                Arguments.of("${__base64Encode(test string)}", "dGVzdCBzdHJpbmc="),
                Arguments.of("${__base64Decode(dGVzdCBzdHJpbmc=)}", "test string"),
                Arguments.of("${__base64Encode(é)}", "w6k="),
                Arguments.of("${__base64Decode(w6k=)}", "é"),
                Arguments.of("${__base64UrlEncode(\u083f)}", "4KC_"),
                Arguments.of("${__base64UrlDecode(4KC_)}", "\u083f"),
                Arguments.of("${__base64UrlDecode(w6k=)}", "é"),
                Arguments.of("${__stringToHex(é🙂)}", "c3a9f09f9982"),
                Arguments.of("${__hexToString(C3A9F09F9982)}", "é🙂"),
                Arguments.of("${__strLen(é🙂)}", "3"),
                Arguments.of("${__substring(test string,5,8)}", "str"),
                Arguments.of("${__substring(test,4,4)}", ""),
                Arguments.of("${__strReplace(a.b.a,.,$1)}", "a$1b$1a"),
                Arguments.of("${__strReplaceRegex(ab12cd34,[0-9]+,-)}", "ab-cd-"),
                Arguments.of("${__strReplaceRegex(ab12,([a-z]+)([0-9]+),$2$1)}", "12ab"),
                Arguments.of("${__uppercase(test)}", "TEST"),
                Arguments.of("${__lowercase(TEST)}", "test"),
                Arguments.of("${__trim(\u2003  hello \u2003)}", "hello"),
                Arguments.of("${__doubleSum(3.5,4.7,sum)}", "8.2"),
                Arguments.of("${__doubleSum(1.5,2.5,-1,)}", "3.0"),
                Arguments.of("${__doubleSum(1,2,123)}", "3.0"),
                Arguments.of("${__chooseRandom(only,chosen)}", "only"),
                Arguments.of("${__isDefined(missing)}", "0"),
                Arguments.of("${__if(a,a,yes,no)}", "yes"),
                Arguments.of("${__if(a,A,yes,no)}", "no"),
                Arguments.of("${__caseFormat(my string)}", "myString"),
                Arguments.of("${__caseFormat(HTTPServer_name,LOWER_HYPHEN)}", "http-server-name"),
                Arguments.of("${__caseFormat(my-string,UPPER_CAMEL_CASE)}", "MyString"),
                Arguments.of("${__caseFormat(myString,SNAKE_CASE)}", "my_string"),
                Arguments.of("${__caseFormat(myString,LOWER_UNDERSCORE)}", "my_string"),
                Arguments.of("${__caseFormat(myString,KEBAB_CASE)}", "my-string"),
                Arguments.of("${__caseFormat(myString,LISP_CASE)}", "my-string"),
                Arguments.of("${__caseFormat(myString,SPINAL_CASE)}", "my-string"),
                Arguments.of("${__caseFormat(myString,TRAIN_CASE)}", "MY-STRING"),
                Arguments.of("${__caseFormat(myString,upper_underscore)}", "MY_STRING"),
                Arguments.of("${__caseFormat(myString,unknown)}", "myString"),
                Arguments.of("${__caseFormat(,LOWER_CAMEL_CASE)}", ""),
                Arguments.of("${__iterationNum()}", "0"),
                Arguments.of("${__strReplace(a\\,b,\\,,-)}", "a-b")
        );
    }

    @ParameterizedTest
    @MethodSource("expressions")
    void evaluatesThroughTheRealParser(String expression, String expected) {
        assertEquals(expected, new CompoundVariable(expression).execute());
    }

    @Test
    void resolvesNestedValuesOnEveryExecutionAndStoresResults() {
        CompoundVariable expression = new CompoundVariable("${__SHA256(${__trim(${payload})}, digest )}");
        variables.put("payload", " abc ");
        String first = expression.execute();
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", first);
        assertEquals(first, variables.get("digest"));
        variables.put("payload", "");
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", expression.execute());
        assertFalse(first.equals(variables.get("digest")));
    }

    @Test
    void doesNotEvaluateTheUnusedConditionalBranch() {
        assertEquals("yes", new CompoundVariable("${__if(a,a,yes,${__uppercase(no,unselected)},result)}").execute());
        assertEquals("yes", variables.get("result"));
        assertEquals(null, variables.get("unselected"));
        assertEquals("no", new CompoundVariable("${__if(a,b,${__uppercase(yes,unselected)},no)}").execute());
        assertEquals(null, variables.get("unselected"));
    }

    @Test
    void randomChoiceUsesOnlyCandidatesAndStoresTheChoice() {
        CompoundVariable expression = new CompoundVariable("${__chooseRandom(red,green,blue,result)}");
        for (int i = 0; i < 100; i++) {
            String result = expression.execute();
            assertTrue(Set.of("red", "green", "blue").contains(result));
            assertEquals(result, variables.get("result"));
        }
        assertEquals("", new CompoundVariable("${__chooseRandom(,result)}").execute());
        assertEquals("", variables.get("result"));
    }

    @Test
    void environmentUsesDocumentedFallbackOrder() {
        String absent = "BREAKTEST_NATIVE_FUNCTION_TEST_UNSET_7B46C3";
        assertFalse(System.getenv().containsKey(absent));
        assertEquals(absent, new CompoundVariable("${__env(" + absent + ")}").execute());
        assertEquals("fallback", new CompoundVariable("${__env(" + absent + ",result,fallback)}").execute());
        assertEquals("fallback", variables.get("result"));
        assertEquals("", new CompoundVariable("${__env(" + absent + ",,)}").execute());
        System.getenv().entrySet().stream().findFirst().ifPresent(entry -> {
            assertEquals(entry.getValue(), new CompoundVariable("${__env(" + entry.getKey() + ",,fallback)}").execute());
        });
    }

    @Test
    void readsCurrentThreadState() {
        variables.put("empty", "");
        assertEquals("1", new CompoundVariable("${__isDefined(empty)}").execute());
        CompoundVariable iteration = new CompoundVariable("${__iterationNum()}");
        variables.incIteration();
        assertEquals("1", iteration.execute());
        variables.incIteration();
        assertEquals("2", iteration.execute());
    }

    @Test
    void epochIsInSecondsAndCanStoreItsResult() {
        long before = Instant.now().getEpochSecond();
        long value = Long.parseLong(new CompoundVariable("${__epochSeconds(epoch)}").execute());
        long after = Instant.now().getEpochSecond();
        assertTrue(before <= value && value <= after);
        assertEquals(Long.toString(value), variables.get("epoch"));
        assertTrue(Long.parseLong(new CompoundVariable("${__epochSeconds()}").execute()) >= value);
    }

    @Test
    void epochMillisecondsUsesTheSameUnitsAsTimeAndCanStoreItsResult() {
        long before = Long.parseLong(new CompoundVariable("${__time()}").execute());
        long value = Long.parseLong(new CompoundVariable("${__epochMilliSeconds(epoch)}").execute());
        long withoutVariable = Long.parseLong(new CompoundVariable("${__epochMilliSeconds()}").execute());
        long after = Long.parseLong(new CompoundVariable("${__time()}").execute());
        assertTrue(before <= value && value <= after);
        assertTrue(before <= withoutVariable && withoutVariable <= after);
        assertEquals(Long.toString(value), variables.get("epoch"));
    }

    @Test
    void caseConversionDoesNotDependOnMachineLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("I", new CompoundVariable("${__uppercase(i)}").execute());
            assertEquals("i", new CompoundVariable("${__lowercase(I)}").execute());
            assertEquals("FIRST_ITEM", new CompoundVariable("${__caseFormat(firstItem,UPPER_UNDERSCORE)}").execute());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void emptyCaseModeUsesTheDefaultAndStoresTheResult() {
        assertEquals("myString", new CompoundVariable("${__caseFormat(my string,,formatted)}").execute());
        assertEquals("myString", variables.get("formatted"));
        assertEquals("myString", new CompoundVariable("${__caseFormat(my string,   ,formatted)}").execute());
        assertEquals("myString", variables.get("formatted"));
    }

    @Test
    void invalidInputWarnsAndClearsThePreviousResultThroughTheExpressionParser() {
        List<LogEvent> events = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("native-function-errors", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        Logger logger = (Logger) LogManager.getLogger(AbstractNativeFunction.class);
        appender.start();
        logger.addAppender(appender);
        try {
            CompoundVariable expression = new CompoundVariable("${__base64Decode(${token},decoded)}");
            variables.put("token", "dGVzdA==");
            assertEquals("test", expression.execute());
            assertEquals("test", variables.get("decoded"));
            variables.put("token", "private-token!");
            assertEquals("", expression.execute());
            assertEquals("", variables.get("decoded"));
            assertEquals(1, events.size());
            assertEquals(Level.WARN, events.get(0).getLevel());
            String message = events.get(0).getMessage().getFormattedMessage();
            assertTrue(message.contains("__base64Decode"));
            assertFalse(message.contains("private-token"));
            assertEquals(null, events.get(0).getThrown());
            variables.put("token", "b2s=");
            assertEquals("ok", expression.execute());
            assertEquals("ok", variables.get("decoded"));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void invalidInputClearsTheFinalResultArgumentAndSupportsNoResultVariable() {
        variables.put("number", "2");
        CompoundVariable expression = new CompoundVariable("${__doubleSum(1,${number},sum)}");
        assertEquals("3.0", expression.execute());
        assertEquals("3.0", variables.get("sum"));
        variables.put("number", "invalid");
        assertEquals("", expression.execute());
        assertEquals("", variables.get("sum"));
        assertEquals("", new CompoundVariable("${__hexToString(zz)}").execute());
        assertEquals("", new CompoundVariable("${__hexToString(zz,)}").execute());
        JMeterContextService.getContext().setVariables(null);
        assertEquals("", new CompoundVariable("${__hexToString(zz,result)}").execute());
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of(new Base64Decode(), new Object[]{"!"}),
                Arguments.of(new Base64UrlDecode(), new Object[]{"+/"}),
                Arguments.of(new HexToString(), new Object[]{"abc"}),
                Arguments.of(new HexToString(), new Object[]{"zz"}),
                Arguments.of(new Substring(), new Object[]{"text", "-1", "2"}),
                Arguments.of(new Substring(), new Object[]{"text", "2", "1"}),
                Arguments.of(new Substring(), new Object[]{"text", "0", "5"}),
                Arguments.of(new Substring(), new Object[]{"text", "no", "2"}),
                Arguments.of(new StringReplaceRegex(), new Object[]{"text", "[", ""}),
                Arguments.of(new DoubleSum(), new Object[]{"no", "1", "result"})
        );
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void invalidInputsReportTheFunctionName(AbstractNativeFunction function, Object[] arguments) throws Exception {
        function.setParameters(FunctionTestHelper.makeParams(arguments));
        InvalidVariableException error = assertThrows(InvalidVariableException.class, function::execute);
        assertTrue(error.getMessage().startsWith(function.getReferenceKey()));
    }

    @Test
    void validatesArgumentCounts() {
        for (AbstractNativeFunction function : List.of(new Sha256(), new Base64Encode(), new Substring(),
                new ChooseRandom(), new DoubleSum(), new If(), new Environment(), new CaseFormat())) {
            assertThrows(InvalidVariableException.class, () -> function.setParameters(List.of()), function.getReferenceKey());
        }
        assertThrows(InvalidVariableException.class,
                () -> new Sha256().setParameters(FunctionTestHelper.makeParams("a", "b", "c")));
        assertThrows(InvalidVariableException.class,
                () -> new EpochSeconds().setParameters(FunctionTestHelper.makeParams("a", "b")));
        assertThrows(InvalidVariableException.class,
                () -> new EpochMilliSeconds().setParameters(FunctionTestHelper.makeParams("a", "b")));
        assertThrows(InvalidVariableException.class,
                () -> new IterationNumber().setParameters(FunctionTestHelper.makeParams("a")));
    }

    @Test
    void supportsEvaluationWithoutThreadVariables() {
        JMeterContextService.getContext().setVariables(null);
        assertEquals("TEST", new CompoundVariable("${__uppercase(test,result)}").execute());
        assertEquals("0", new CompoundVariable("${__isDefined(missing)}").execute());
        assertEquals("0", new CompoundVariable("${__iterationNum()}").execute());
    }
}
