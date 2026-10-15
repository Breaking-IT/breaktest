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

import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;

/**
 * Moves a query string between the Path field and the Params table without changing
 * the URL the sampler sends. Params rows are created with URL encoding off, so
 * {@code HTTPSamplerBase.getUrl()} rebuilds exactly the original characters, sending
 * spaces as {@code %20} in both places.
 */
final class PathQuery {

    private static final char QUERY_PREFIX = '?';
    private static final char PARAM_SEPARATOR = '&';
    private static final char NAME_VALUE_SEPARATOR = '=';
    private static final String URL_SCHEME_SEPARATOR = "://"; // $NON-NLS-1$

    private PathQuery() {
    }

    /** A path split into its resource part and query parameters. */
    record Split(String path, List<HTTPArgument> parameters) {
    }

    /**
     * Split the query string off a path.
     *
     * @param path the Path field value
     * @return the split, or {@code null} when there is no query or it cannot move to
     *         Params without changing the request (a full URL, or an empty parameter
     *         name, which the sampler would drop)
     */
    static Split split(String path) {
        if (isFullUrl(path)) {
            return null;
        }
        // Same test the sampler uses to decide between '?' and '&' when appending Params
        int queryStart = path.indexOf(QUERY_PREFIX);
        if (queryStart < 0 || queryStart == path.length() - 1) {
            return null;
        }
        List<HTTPArgument> parameters = new ArrayList<>();
        for (String segment : splitOutsideVariables(path.substring(queryStart + 1), PARAM_SEPARATOR)) {
            int equals = indexOutsideVariables(segment, NAME_VALUE_SEPARATOR);
            String name = equals < 0 ? segment : segment.substring(0, equals);
            if (name.isEmpty()) {
                return null;
            }
            HTTPArgument argument = new HTTPArgument(name, equals < 0 ? "" : segment.substring(equals + 1));
            argument.setAlwaysEncoded(false);
            argument.setUseEquals(equals >= 0);
            parameters.add(argument);
        }
        return new Split(path.substring(0, queryStart), parameters);
    }

    /**
     * @param parameters rows to append; all must be unencoded with a non-empty name
     * @return the query string these rows produce at runtime
     */
    static String join(List<HTTPArgument> parameters) {
        StringBuilder query = new StringBuilder();
        for (HTTPArgument parameter : parameters) {
            if (!query.isEmpty()) {
                query.append(PARAM_SEPARATOR);
            }
            query.append(parameter.getName());
            if (parameter.isUseEquals()) {
                query.append(NAME_VALUE_SEPARATOR).append(parameter.getValue());
            }
        }
        return query.toString();
    }

    /**
     * @return whether the sampler uses the path as the entire URL and ignores Params
     */
    static boolean isFullUrl(String path) {
        return path.startsWith(HTTPConstants.PROTOCOL_HTTP + URL_SCHEME_SEPARATOR)
                || path.startsWith(HTTPConstants.PROTOCOL_HTTPS + URL_SCHEME_SEPARATOR);
    }

    /**
     * @return whether the row is sent as-is, so it can live in the path unchanged
     */
    static boolean isMovableToPath(HTTPArgument parameter) {
        // A dynamic name can resolve to empty, in which case the sampler omits the
        // entire row. A query embedded in Path cannot preserve that behavior.
        return !parameter.isAlwaysEncoded() && !containsExpression(parameter.getName());
    }

    /**
     * @return whether the path's URL form and query separator are known before execution
     */
    static boolean canAppendQuery(String path) {
        // Expressions may introduce a query or turn the path into a full URL, for
        // which the sampler ignores Params entirely. Keep that decision at runtime.
        return !isFullUrl(path) && !containsExpression(path);
    }

    private static boolean containsExpression(String text) {
        return text.contains("${"); // $NON-NLS-1$
    }

    /**
     * @return {@code path} with {@code query} appended the way the sampler appends Params
     */
    static String appendQuery(String path, String query) {
        return path + (path.indexOf(QUERY_PREFIX) >= 0 ? PARAM_SEPARATOR : QUERY_PREFIX) + query;
    }

    // Keep "&" and "=" inside ${...} (variables and functions) as part of the value
    private static List<String> splitOutsideVariables(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = indexOutsideVariables(text, separator, start)) >= 0) {
            parts.add(text.substring(start, index));
            start = index + 1;
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int indexOutsideVariables(String text, char target) {
        return indexOutsideVariables(text, target, 0);
    }

    private static int indexOutsideVariables(String text, char target, int from) {
        int depth = 0;
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == target && depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }
}
