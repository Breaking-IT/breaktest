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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.protocol.http.har.HarEntry.PostData;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;

class HarProtocolCorrelationTest extends JMeterTestCase {

    @Test
    void findsCasTicketsFromFormsAndRedirects() {
        HarEntry form = entry(0, "/login");
        form.setResponseContentText("<input value='LT-login-ticket-123' type='hidden' name='lt'>");
        HarEntry login = entry(1, "/login");
        login.setPostData(new PostData("application/x-www-form-urlencoded", "",
                List.of(new NameValue("lt", "LT-login-ticket-123"))));
        login.getResponseHeaders().add(new NameValue("Location", "https://example.test/app?ticket=ST-service-ticket-456"));
        HarEntry app = entry(2, "/app?ticket=ST-service-ticket-456");
        assertEquals(List.of("cas-login-ticket", "cas-service-ticket"), ids(find(form, login, app)));
    }

    @Test
    void followsCognitoChallengesAndNestedAuthenticationTokens() {
        HarEntry challenge = entry(0, "/challenge");
        challenge.setResponseContentText("""
                {"ChallengeName":"SMS_MFA","Session":"challenge-session-123"}
                """);
        HarEntry answer = entry(1, "/answer");
        answer.setPostData(new PostData("application/json", "{\"Session\":\"challenge-session-123\"}", List.of()));
        answer.setResponseContentText("""
                {"AuthenticationResult":{"AccessToken":"cognito-access-123", "IdToken":"cognito-id-456",
                  "RefreshToken":"cognito-refresh-789"}}
                """);
        HarEntry api = entry(2, "/api");
        api.getRequestHeaders().add(new NameValue("Authorization", "Bearer cognito-access-123"));
        api.setPostData(new PostData("application/json",
                "{\"id\":\"cognito-id-456\",\"refresh\":\"cognito-refresh-789\"}", List.of()));
        assertEquals(Set.of("cognito-challenge-session", "cognito-access-token", "cognito-id-token",
                "cognito-refresh-token"), Set.copyOf(ids(find(challenge, answer, api))));
    }

    @Test
    void findsJenkinsCrumbAndFlaskFormTokenInHeaders() {
        HarEntry jenkins = entry(0, "/crumbIssuer/api/json");
        jenkins.setResponseContentText("{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"jenkins-crumb-123\"}");
        HarEntry flask = entry(1, "/form");
        flask.setResponseContentText("<input value='flask-token-456' name='csrf_token'>".repeat(3));
        HarEntry submit = entry(2, "/submit");
        submit.getRequestHeaders().add(new NameValue("Jenkins-Crumb", "jenkins-crumb-123"));
        submit.getRequestHeaders().add(new NameValue("X-CSRFToken", "flask-token-456"));
        assertEquals(List.of("jenkins-crumb", "flask-csrf-token"), ids(find(jenkins, flask, submit)));
    }

    @Test
    void findsEngineIoOpenPacketButNotGenericSessionJsonOrSocketIoConnectPackets() {
        HarEntry handshake = entry(0, "/socket.io/?EIO=4&transport=polling");
        String payload = "{\"sid\":\"engine-session-123\",\"upgrades\":[\"websocket\"],"
                + "\"pingInterval\":25000,\"pingTimeout\":20000,\"maxPayload\":1000000}";
        handshake.setResponseContentText("0" + payload);
        HarEntry poll = entry(1, "/socket.io/?EIO=4&transport=polling&sid=engine-session-123");
        assertEquals(List.of("engineio-session-id"), ids(find(handshake, poll)));
        for (String invalid : List.of(payload, "40" + payload, "0{\"sid\":\"engine-session-123\"}")) {
            handshake.setResponseContentText(invalid);
            assertTrue(find(handshake, poll).isEmpty(), invalid);
        }
    }

    @Test
    void findsCsrfCookiesAndResponseHeadersButLeavesCookieReplayToCookieManager() {
        HarEntry source = entry(0, "/csrf");
        source.getResponseHeaders().add(new NameValue("Set-Cookie", "XSRF-TOKEN=xsrf-token-123; Path=/"));
        source.getResponseHeaders().add(new NameValue("Set-Cookie", "csrftoken=django-token-456; Path=/"));
        source.getResponseHeaders().add(new NameValue("X-XSRF-TOKEN", "xsrf-header-789"));
        source.getResponseHeaders().add(new NameValue("X-CSRFToken", "django-header-123"));
        HarEntry target = entry(1, "/submit");
        target.getRequestHeaders().add(new NameValue("X-XSRF-TOKEN", "xsrf-token-123"));
        target.getRequestHeaders().add(new NameValue("X-CSRFToken", "django-token-456"));
        target.setPostData(new PostData("application/json", "[\"xsrf-header-789\",\"django-header-123\"]", List.of()));
        assertEquals(Set.of("xsrf-cookie-token", "django-csrf-cookie", "xsrf-header-token", "django-csrf-header"),
                Set.copyOf(ids(find(source, target))));
        target.getRequestHeaders().clear();
        target.setPostData(null);
        target.getRequestHeaders().add(new NameValue("Cookie", "XSRF-TOKEN=xsrf-token-123; csrftoken=django-token-456"));
        assertTrue(find(source, target).isEmpty());
    }

    @Test
    void preservesDecodedXsrfHeadersDuringImportAndProcessing() {
        HarEntry source = entry(0, "/csrf");
        source.getResponseHeaders().add(new NameValue("Set-Cookie", "XSRF-TOKEN=encoded%2Btoken%3D%3D; Path=/"));
        HarEntry target = entry(1, "/submit");
        target.getRequestHeaders().add(new NameValue("X-XSRF-TOKEN", "encoded+token=="));
        var matches = find(source, target);
        assertEquals(List.of("xsrf-cookie-token"), ids(matches));
        var correlation = matches.get(0);
        var replacement = correlation.getReplacements().get(0);
        String reference = "${__urldecode(${xsrf_cookie_token})}";
        assertEquals(reference, HarPredefinedCorrelation.variableReference(correlation, replacement));
        assertEquals(List.of("encoded+token=="), HarPredefinedCorrelation.replacementVariants(correlation, replacement));
        HarImportOptions options = new HarImportOptions();
        options.setPredefinedCorrelations(matches);
        HashTree tree = new HarConverter(List.of(source, target), options, "synthetic.har", "test")
                .convert(Set.of("example.test"));
        var imported = samplers(tree).stream().filter(s -> s.getPath().equals("/submit")).findFirst().orElseThrow();
        assertEquals(reference, imported.getNativeHeaderList().get(0).getValue());
        HTTPSamplerProxy existing = new HTTPSamplerProxy();
        existing.setNativeHeaders(List.of(new Header("X-XSRF-TOKEN", "encoded+token==")));
        assertEquals(1, FindPredefinedCorrelationsAction.applyReplacement(existing, correlation, replacement));
        assertEquals(reference, existing.getNativeHeaderList().get(0).getValue());
    }

    @Test
    void rejectsLookalikeFieldsUnusedValuesAndAmbiguousTickets() {
        HarEntry source = entry(0, "/source");
        HarEntry target = entry(1, "/target");
        target.setPostData(new PostData("application/json", "[\"generic-value-123\",\"LT-one-ticket\",\"LT-two-ticket\"]", List.of()));
        for (String body : List.of("{\"Session\":\"generic-value-123\"}", "{\"crumb\":\"generic-value-123\"}",
                "<input name='lt' value='generic-value-123'>", "<input name='csrf_token' value='unused-token-123'>",
                "<input name='lt' value='LT-one-ticket'><input name='lt' value='LT-two-ticket'>")) {
            source.setResponseContentText(body);
            assertTrue(find(source, target).isEmpty(), body);
        }
        source.setResponseContentText("");
        source.getResponseHeaders().add(new NameValue("Set-Cookie", "not-XSRF-TOKEN=generic-value-123; Path=/"));
        assertTrue(find(source, target).isEmpty());
    }

    private static List<HarPredefinedCorrelation> find(HarEntry... entries) {
        return HarPredefinedCorrelation.find(List.of(entries), HarCorrelationRuleCatalog.builtInRules());
    }

    private static List<String> ids(List<HarPredefinedCorrelation> matches) {
        return matches.stream().map(m -> m.getRule().getId()).toList();
    }

    private static HarEntry entry(int index, String path) {
        HarEntry entry = new HarEntry();
        entry.setOriginalIndex(index);
        entry.setStartMs(index * 100);
        entry.setEndMs(index * 100 + 50);
        entry.setMethod("GET");
        entry.setUrl("https://example.test" + path);
        entry.setServerIpAddress("127.0.0.1");
        entry.setHasPositiveTiming(true);
        entry.setResponseStatus(200);
        return entry;
    }

    private static List<HTTPSamplerProxy> samplers(HashTree tree) {
        List<HTTPSamplerProxy> result = new ArrayList<>();
        for (Object item : tree.list()) {
            if (item instanceof HTTPSamplerProxy sampler) {
                result.add(sampler);
            }
            result.addAll(samplers(tree.getTree(item)));
        }
        return result;
    }
}
