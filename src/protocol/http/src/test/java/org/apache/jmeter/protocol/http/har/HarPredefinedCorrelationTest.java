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

package org.apache.jmeter.protocol.http.har;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.protocol.http.har.HarEntry.PostData;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ExtractorType;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.ResponseField;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jorphan.collections.HashTree;
import org.apache.oro.text.regex.Perl5Compiler;
import org.junit.jupiter.api.Test;

class HarPredefinedCorrelationTest {

    @Test
    void predefinedRegexesCompileWithJMeterRegexEngine() {
        Perl5Compiler compiler = new Perl5Compiler();

        HarPredefinedCorrelation.rules().stream()
                .filter(rule -> rule.getExtractorType() == HarPredefinedCorrelation.ExtractorType.REGEX)
                .forEach(rule -> assertDoesNotThrow(() -> compiler.compile(rule.getExpression()), rule.getId()));
    }

    @Test
    void predefinedRulesUseDistinctVariableNames() {
        List<String> variableNames = HarPredefinedCorrelation.rules().stream()
                .map(HarPredefinedCorrelation.Rule::getVariableName)
                .toList();

        assertEquals(Set.copyOf(variableNames).size(), variableNames.size(),
                "two rules writing one variable overwrite each other: " + variableNames);
    }

    @Test
    void findsHiddenFieldsRegardlessOfAttributeOrder() {
        HarEntry source = entry(0, 0, "GET", "https://example.test/form");
        source.setResponseContentText("<form>"
                + "<input type=\"hidden\" value=\"reversed-token-123\" name=\"_csrf\">"
                + "<meta name=\"csrf-token\" content=\"meta-token-456\">"
                + "</form>");
        HarEntry target = entry(1, 100, "POST", "https://example.test/form");
        target.getRequestHeaders().add(new NameValue("X-CSRF-TOKEN", "meta-token-456"));
        target.setPostData(new PostData("application/x-www-form-urlencoded", "", List.of(
                new NameValue("_csrf", "reversed-token-123"))));

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(List.of(source, target));

        assertEquals(List.of("spring-csrf-token", "meta-csrf-token"),
                matches.stream().map(match -> match.getRule().getId()).toList());
    }

    @Test
    void findsSamlKeycloakAndSapValuesUsedByLaterRequests() {
        HarEntry source = entry(0, 0, "GET", "https://sso.example.test/login");
        source.getResponseHeaders().add(new NameValue("Content-Type", "text/html"));
        source.getResponseHeaders().add(new NameValue("X-CSRF-Token", "sap-csrf-token-123"));
        source.setResponseContentText("<form action=\"/auth?session_code=keycloak-code-123\">"
                + "<input name=\"SAMLRequest\" value=\"saml-request-456\">"
                + "</form>");
        HarEntry target = entry(1, 100, "POST", "https://sso.example.test/auth");
        target.getRequestHeaders().add(new NameValue("X-CSRF-Token", "sap-csrf-token-123"));
        target.getQueryString().add(new NameValue("session_code", "keycloak-code-123"));
        target.setPostData(new PostData("application/x-www-form-urlencoded", "", List.of(
                new NameValue("SAMLRequest", "saml-request-456"))));

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(List.of(source, target));

        assertEquals(List.of("saml-request", "keycloak-session-code", "sap-csrf-token"),
                matches.stream().map(match -> match.getRule().getId()).toList());
    }

    @Test
    void skipsBodyRulesForBinaryResponses() {
        HarEntry source = entry(0, 0, "GET", "https://example.test/logo.png");
        source.getResponseHeaders().add(new NameValue("Content-Type", "image/png"));
        source.setResponseContentText("<input name=\"_csrf\" value=\"binary-token-123\">");
        HarEntry target = entry(1, 100, "POST", "https://example.test/form");
        target.setPostData(new PostData("application/x-www-form-urlencoded", "", List.of(
                new NameValue("_csrf", "binary-token-123"))));

        assertTrue(HarPredefinedCorrelation.find(List.of(source, target)).isEmpty());
    }

    @Test
    void findsAspNetResponseFieldsUsedByLaterFormParameters() {
        String viewState = "/wEPDwUKMTIzNDU2Nzg5MA==";
        HarEntry source = entry(0, 0, "GET", "https://example.test/form");
        source.setResponseContentText("<form>"
                + "<input type=\"hidden\" name=\"__VIEWSTATE\" value=\"" + viewState + "\">"
                + "<input type=\"hidden\" name=\"__VIEWSTATEGENERATOR\" value=\"C2EE9ABB\">"
                + "<input type=\"hidden\" name=\"__EVENTVALIDATION\" value=\"event-token-123\">"
                + "</form>");
        HarEntry target = entry(1, 100, "POST", "https://example.test/form");
        target.setPostData(new PostData("application/x-www-form-urlencoded", "", List.of(
                new NameValue("__VIEWSTATE", viewState),
                new NameValue("__VIEWSTATEGENERATOR", "C2EE9ABB"),
                new NameValue("__EVENTVALIDATION", "event-token-123"))));

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(
                List.of(source, target), Set.of("example.test"));

        assertEquals(3, matches.size());
        assertEquals(List.of("aspnet-viewstate", "aspnet-viewstate-generator", "aspnet-event-validation"),
                matches.stream().map(match -> match.getRule().getId()).toList());
        assertTrue(matches.stream().allMatch(match -> match.getReplacements().size() == 1));
        assertTrue(matches.stream().allMatch(match ->
                match.getReplacements().get(0).getLocation()
                        == HarPredefinedCorrelation.RequestLocation.POST_PARAMETER));
    }

    @Test
    void findsOauthJsonValuesInLaterHeadersAndRequestBodies() {
        HarEntry source = entry(0, 0, "POST", "https://auth.example.test/token");
        source.setResponseContentText("{\"access_token\":\"access-token-123\","
                + "\"refresh_token\":\"refresh-token-456\"}");
        HarEntry target = entry(1, 100, "POST", "https://api.example.test/session");
        target.getRequestHeaders().add(new NameValue("Authorization", "Bearer access-token-123"));
        target.setPostData(new PostData("application/json",
                "{\"refreshToken\":\"refresh-token-456\"}", List.of()));

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(
                List.of(source, target), Set.of("auth.example.test", "api.example.test"));

        assertEquals(List.of("oauth-access-token", "oauth-refresh-token"),
                matches.stream().map(match -> match.getRule().getId()).toList());
        assertEquals(HarPredefinedCorrelation.RequestLocation.REQUEST_HEADER,
                matches.get(0).getReplacements().get(0).getLocation());
        assertEquals(HarPredefinedCorrelation.RequestLocation.REQUEST_BODY,
                matches.get(1).getReplacements().get(0).getLocation());
    }

    @Test
    void findsOauthCodeAndStateInRedirectHeaders() {
        HarEntry source = entry(0, 0, "GET", "https://auth.example.test/authorize");
        source.getResponseHeaders().add(new NameValue("Location",
                "https://client.example.test/callback?code=authorization-code-123&state=state-456"));
        HarEntry target = entry(1, 100, "GET",
                "https://client.example.test/callback?code=authorization-code-123&state=state-456");
        target.getQueryString().add(new NameValue("code", "authorization-code-123"));
        target.getQueryString().add(new NameValue("state", "state-456"));

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(
                List.of(source, target), Set.of("auth.example.test", "client.example.test"));

        assertEquals(List.of("oauth-code-header", "oauth-state-header"),
                matches.stream().map(match -> match.getRule().getId()).toList());
        assertTrue(matches.stream().allMatch(match ->
                match.getRule().getResponseField() == HarPredefinedCorrelation.ResponseField.HEADERS));
    }

    @Test
    void findsGroupedFrameworkViewStateAndCsrfFields() {
        HarEntry source = entry(0, 0, "GET", "https://example.test/form");
        source.setResponseContentText("<form>"
                + "<input name=\"javax.faces.ViewState\" value=\"legacy-view-state\">"
                + "<input name=\"jakarta.faces.ViewState\" value=\"current-view-state\">"
                + "<input name=\"csrfmiddlewaretoken\" value=\"django-token\">"
                + "<input name=\"authenticity_token\" value=\"rails-token\">"
                + "<input name=\"_csrf\" value=\"spring-token\">"
                + "<input name=\"_token\" value=\"laravel-token\">"
                + "</form>");
        HarEntry target = entry(1, 100, "POST", "https://example.test/form");
        target.setPostData(new PostData("application/x-www-form-urlencoded", "", List.of(
                new NameValue("javax.faces.ViewState", "legacy-view-state"),
                new NameValue("jakarta.faces.ViewState", "current-view-state"),
                new NameValue("csrfmiddlewaretoken", "django-token"),
                new NameValue("authenticity_token", "rails-token"),
                new NameValue("_csrf", "spring-token"),
                new NameValue("_token", "laravel-token"))));

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(List.of(source, target));

        assertEquals(List.of(
                "jsf-viewstate", "jakarta-faces-viewstate", "django-csrf-token",
                "rails-authenticity-token", "spring-csrf-token", "laravel-csrf-token"),
                matches.stream().map(match -> match.getRule().getId()).toList());
        assertEquals(List.of(
                "JavaServer Faces", "JavaServer Faces", "Django",
                "Ruby on Rails", "Spring Security", "Laravel"),
                matches.stream().map(match -> match.getRule().getGroup()).toList());
    }

    @Test
    void findsDocumentedSiebelAndOracleNcaValues() {
        HarEntry source = entry(0, 0, "GET", "https://example.test/start");
        source.getResponseHeaders().add(new NameValue("Set-Cookie", "_sn=siebel-session-123; Path=/"));
        source.setResponseContentText("launch?ICX_TICKET=oracle-ticket-456;RESP_APP=AR");
        HarEntry target = entry(1, 100, "POST", "https://example.test/next");
        // Siebel repeats the session in the URL; the Cookie header itself is replayed by the
        // Cookie Manager, so the converter never writes it and a replacement there would be lost.
        target.getQueryString().add(new NameValue("_sn", "siebel-session-123"));
        target.getQueryString().add(new NameValue("ICX_TICKET", "oracle-ticket-456"));

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(List.of(source, target));

        assertEquals(List.of("siebel-session-cookie", "oracle-nca-icx-ticket"),
                matches.stream().map(match -> match.getRule().getId()).toList());
    }

    @Test
    void ignoresExtractionWhenTheValueIsNotUsedByASubsequentRequest() {
        HarEntry earlierRequest = entry(0, 0, "GET", "https://api.example.test/old?token=unused-token");
        earlierRequest.getQueryString().add(new NameValue("token", "unused-token"));
        HarEntry source = entry(1, 100, "POST", "https://auth.example.test/token");
        source.setResponseContentText("{\"access_token\":\"unused-token\"}");

        assertTrue(HarPredefinedCorrelation.find(
                List.of(earlierRequest, source), Set.of("api.example.test", "auth.example.test")).isEmpty());
    }

    @Test
    void splitsTheParallelControllerWhenAConsumerSharesItWithTheExtractor() {
        HarEntry source = entry(0, 0, "GET", "https://example.test/form");
        source.setResponseContentText("<input name=\"_csrf\" value=\"spring-token-123\">");
        // Started before the source response finished, so both are in one recorded wave.
        HarEntry sibling = entry(1, 10, "GET", "https://example.test/asset.js");
        HarEntry consumer = entry(2, 20, "POST", "https://example.test/submit");
        consumer.setPostData(new PostData("application/x-www-form-urlencoded", "", List.of(
                new NameValue("_csrf", "spring-token-123"))));
        List<HarEntry> entries = List.of(source, sibling, consumer);

        List<HarPredefinedCorrelation> correlations =
                HarPredefinedCorrelation.find(entries, Set.of("example.test"));
        assertEquals(1, correlations.size(), "the correlation is kept, not dropped");

        HarImportOptions options = new HarImportOptions();
        options.setPredefinedCorrelations(correlations);
        HashTree tree = new HarConverter(entries, options, "correlations.har", "md5")
                .convert(Set.of("example.test"));

        List<ParallelController> parallelControllers = collect(tree, ParallelController.class);
        assertEquals(1, parallelControllers.size(),
                "the extractor and its sibling stay parallel, the consumer moves out");
        HashTree transaction = treeOf(tree, TransactionController.class);
        List<Object> transactionChildren = new ArrayList<>(transaction.list());
        assertEquals(2, transactionChildren.size());
        assertTrue(transactionChildren.get(0) instanceof ParallelController);
        assertEquals("/submit", ((HTTPSamplerProxy) transactionChildren.get(1)).getPath(),
                "a lone consumer is placed directly under the transaction");
        assertEquals(1, collect(tree, RegexExtractor.class).size());
        assertEquals("${spring_csrf_token}", collect(tree, HTTPSamplerProxy.class).stream()
                .filter(sampler -> "/submit".equals(sampler.getPath()))
                .findFirst()
                .orElseThrow()
                .getArguments()
                .getArgument(0)
                .getValue());
    }

    @Test
    void namesEveryExtractionOfARuleSeparately() {
        // An OAuth login hands out a new state at each hop, so one rule matches several responses
        // with different values. Sharing one variable made every extractor overwrite the previous.
        HarEntry first = entry(0, 0, "GET", "https://example.test/login");
        first.getResponseHeaders().add(new NameValue("Location",
                "https://sso.example.test/authorize?state=first-state-111"));
        HarEntry second = entry(1, 100, "GET", "https://sso.example.test/authorize?state=first-state-111");
        second.getQueryString().add(new NameValue("state", "first-state-111"));
        second.getResponseHeaders().add(new NameValue("Location",
                "https://sso.example.test/u/login?state=second-state-222"));
        HarEntry third = entry(2, 200, "GET", "https://sso.example.test/u/login?state=second-state-222");
        third.getQueryString().add(new NameValue("state", "second-state-222"));
        List<HarEntry> entries = List.of(first, second, third);

        List<HarPredefinedCorrelation> correlations = HarPredefinedCorrelation.find(entries);

        assertEquals(List.of("oauth_state", "oauth_state_2"),
                correlations.stream().map(HarPredefinedCorrelation::getVariableName).toList());

        HarImportOptions options = new HarImportOptions();
        options.setPredefinedCorrelations(correlations);
        HashTree tree = new HarConverter(entries, options, "oauth.har", "md5")
                .convert(Set.of("example.test", "sso.example.test"));

        assertEquals(List.of("oauth_state", "oauth_state_2"),
                collect(tree, RegexExtractor.class).stream().map(RegexExtractor::getRefName).toList());
        assertEquals("${oauth_state}", queryValue(tree, "/authorize", "state"));
        assertEquals("${oauth_state_2}", queryValue(tree, "/u/login", "state"));
    }

    @Test
    void replacesWithTheExtractionClosestBeforeTheRequestThatUsesIt() {
        // The same state is readable from the redirect header and from the page that embeds it.
        // Only the last extraction before the request still holds the value when it runs.
        HarEntry redirect = entry(0, 0, "GET", "https://sso.example.test/authorize");
        redirect.getResponseHeaders().add(new NameValue("Location",
                "https://sso.example.test/u/login?state=shared-state-123"));
        HarEntry loginPage = entry(1, 100, "GET", "https://sso.example.test/u/login?state=shared-state-123");
        loginPage.getQueryString().add(new NameValue("state", "shared-state-123"));
        loginPage.setResponseContentText("<input name=\"state\" value=\"shared-state-123\">");
        HarEntry submit = entry(2, 200, "POST", "https://sso.example.test/u/login");
        submit.setPostData(new PostData("application/x-www-form-urlencoded", "", List.of(
                new NameValue("state", "shared-state-123"))));
        List<HarEntry> entries = List.of(redirect, loginPage, submit);

        List<HarPredefinedCorrelation> correlations = HarPredefinedCorrelation.find(entries);

        assertEquals(List.of("oauth_state", "oauth_state_form"),
                correlations.stream().map(HarPredefinedCorrelation::getVariableName).toList());
        assertEquals(List.of(2), correlations.stream()
                .filter(correlation -> "oauth_state_form".equals(correlation.getVariableName()))
                .flatMap(correlation -> correlation.getReplacements().stream())
                .map(HarPredefinedCorrelation.Replacement::getTargetEntryIndex)
                .toList());
        assertTrue(correlations.get(0).getReplacements().stream()
                .noneMatch(replacement -> replacement.getTargetEntryIndex() == 2),
                "the redirect header extraction no longer claims the form submit");
    }

    @Test
    void ignoresRequestHeadersTheConverterDoesNotExport() {
        HarEntry source = entry(0, 0, "GET", "https://sso.example.test/authorize");
        source.getResponseHeaders().add(new NameValue("Location",
                "https://sso.example.test/u/login?state=cookie-state-123"));
        HarEntry target = entry(1, 100, "GET", "https://sso.example.test/u/login?state=cookie-state-123");
        target.getQueryString().add(new NameValue("state", "cookie-state-123"));
        target.getRequestHeaders().add(new NameValue(":path", "/u/login?state=cookie-state-123"));
        target.getRequestHeaders().add(new NameValue("Cookie", "last_state=cookie-state-123"));

        List<HarPredefinedCorrelation> correlations = HarPredefinedCorrelation.find(List.of(source, target));

        assertEquals(1, correlations.size());
        assertEquals(List.of(HarPredefinedCorrelation.RequestLocation.QUERY_PARAMETER),
                correlations.get(0).getReplacements().stream()
                        .map(HarPredefinedCorrelation.Replacement::getLocation)
                        .toList());
    }

    @Test
    void neverGeneratesAVariableNameAnotherRuleOwns() {
        // A custom rule named like the generated suffix of another rule: both extractions must
        // still get a variable of their own instead of overwriting each other.
        Rule first = new Rule("first", "Custom", "First", "token",
                ExtractorType.REGEX, ResponseField.BODY, "first=([^;]+)", "$1$",
                "", false, false, true);
        Rule collidingName = new Rule("second", "Custom", "Second", "token_2",
                ExtractorType.REGEX, ResponseField.BODY, "second=([^;]+)", "$1$",
                "", false, false, true);
        HarEntry firstSource = entry(0, 0, "GET", "https://example.test/one");
        firstSource.setResponseContentText("first=first-value-111;second=second-value-222;");
        HarEntry secondSource = entry(1, 100, "GET", "https://example.test/two");
        secondSource.setResponseContentText("first=third-value-333;");
        HarEntry target = entry(2, 200, "POST", "https://example.test/use");
        target.setPostData(new PostData("application/json",
                "{\"a\":\"first-value-111\",\"b\":\"second-value-222\",\"c\":\"third-value-333\"}",
                List.of()));

        List<HarPredefinedCorrelation> correlations = HarPredefinedCorrelation.find(
                List.of(firstSource, secondSource, target), List.of(first, collidingName));

        List<String> variableNames = correlations.stream()
                .map(HarPredefinedCorrelation::getVariableName)
                .toList();
        assertEquals(Set.copyOf(variableNames).size(), variableNames.size(),
                "each extraction writes its own variable: " + variableNames);
        assertTrue(variableNames.contains("token") && variableNames.contains("token_2"),
                variableNames.toString());
    }

    @Test
    void findsKeycloakParametersInAnEscapedFormAction() {
        HarEntry source = entry(0, 0, "GET", "https://sso.example.test/auth");
        source.setResponseContentText("<form id=\"kc-form-login\" method=\"post\" "
                + "action=\"https://sso.example.test/login-actions/authenticate"
                + "?session_code=session-code-111&amp;execution=execution-222"
                + "&amp;client_id=account&amp;tab_id=tab-id-333&amp;client_data=client-data-444\">"
                + "</form>");
        HarEntry target = entry(1, 100, "POST", "https://sso.example.test/login-actions/authenticate");
        target.getQueryString().add(new NameValue("session_code", "session-code-111"));
        target.getQueryString().add(new NameValue("execution", "execution-222"));
        target.getQueryString().add(new NameValue("tab_id", "tab-id-333"));
        target.getQueryString().add(new NameValue("client_data", "client-data-444"));

        List<HarPredefinedCorrelation> matches = HarPredefinedCorrelation.find(List.of(source, target));

        assertEquals(List.of("keycloak-session-code", "keycloak-execution",
                "keycloak-tab-id", "keycloak-client-data"),
                matches.stream().map(match -> match.getRule().getId()).toList());
    }

    @Test
    void ignoresExtractedValuesThatAreTooShortToBeEvidence() {
        HarEntry source = entry(0, 0, "GET", "https://example.test/form");
        source.setResponseContentText("<input name=\"_csrf\" value=\"nl\">");
        HarEntry target = entry(1, 100, "GET", "https://example.test/page?lang=nl");
        target.getQueryString().add(new NameValue("lang", "nl"));

        assertTrue(HarPredefinedCorrelation.find(List.of(source, target)).isEmpty());
    }

    @Test
    void converterAddsNativeExtractorsAndReplacesOnlyLaterRequestData() {
        String viewState = "/wEPDwUKMTIzNDU2Nzg5MA==";
        HarEntry source = entry(0, 0, "GET", "https://example.test/form");
        source.setResponseContentText("<input name=\"__VIEWSTATE\" value=\"" + viewState + "\">");
        HarEntry tokenSource = entry(1, 100, "POST", "https://example.test/token");
        tokenSource.setResponseContentText("{\"access_token\":\"access-token-123\"}");
        HarEntry target = entry(2, 200, "POST", "https://example.test/form");
        target.getRequestHeaders().add(new NameValue("Authorization", "Bearer access-token-123"));
        target.setPostData(new PostData("application/x-www-form-urlencoded", "", List.of(
                new NameValue("__VIEWSTATE", viewState))));
        List<HarEntry> entries = List.of(source, tokenSource, target);

        HarImportOptions options = new HarImportOptions();
        options.setPredefinedCorrelations(HarPredefinedCorrelation.find(entries, Set.of("example.test")));
        HashTree tree = new HarConverter(entries, options, "correlations.har", "md5")
                .convert(Set.of("example.test"));

        List<RegexExtractor> regexExtractors = collect(tree, RegexExtractor.class);
        assertEquals(1, regexExtractors.size());
        assertEquals("aspnet_viewstate", regexExtractors.get(0).getRefName());
        assertTrue(regexExtractors.get(0).isFailOnNoMatch());

        List<JSONPostProcessor> jsonExtractors = collect(tree, JSONPostProcessor.class);
        assertEquals(1, jsonExtractors.size());
        assertEquals("oauth_access_token", jsonExtractors.get(0).getRefNames());
        assertTrue(jsonExtractors.get(0).isFailOnNoMatch());

        HTTPSamplerProxy targetSampler = collect(tree, HTTPSamplerProxy.class).stream()
                .filter(sampler -> "/form".equals(sampler.getPath())
                        && "POST".equals(sampler.getMethod()))
                .findFirst()
                .orElseThrow();
        assertEquals("${aspnet_viewstate}", targetSampler.getArguments().getArgument(0).getValue());
        Header authorization = targetSampler.getNativeHeaderList().stream()
                .filter(header -> "Authorization".equals(header.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(authorization);
        assertEquals("Bearer ${oauth_access_token}", authorization.getValue());
    }

    private static HarEntry entry(int index, double startMs, String method, String url) {
        HarEntry entry = new HarEntry();
        entry.setOriginalIndex(index);
        entry.setStartMs(startMs);
        entry.setEndMs(startMs + 50);
        entry.setMethod(method);
        entry.setUrl(url);
        entry.setServerIpAddress("127.0.0.1");
        entry.setHasPositiveTiming(true);
        entry.setResponseStatus(200);
        return entry;
    }

    private static String queryValue(HashTree tree, String path, String parameter) {
        return collect(tree, HTTPSamplerProxy.class).stream()
                .filter(sampler -> path.equals(sampler.getPath()))
                .findFirst()
                .orElseThrow()
                .getArguments()
                .getArgumentsAsMap()
                .get(parameter);
    }

    private static HashTree treeOf(HashTree tree, Class<?> type) {
        for (Object item : tree.list()) {
            if (type.isInstance(item)) {
                return tree.getTree(item);
            }
            HashTree nested = treeOf(tree.getTree(item), type);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> collect(HashTree tree, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Object item : tree.list()) {
            if (type.isInstance(item)) {
                result.add((T) item);
            }
            result.addAll(collect(tree.getTree(item), type));
        }
        return result;
    }
}
