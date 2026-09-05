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

package org.apache.jmeter.gui;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Interface for nodes that have replaceable content.
 * <p>
 * A {@link Replaceable} component declares the user-editable text fields that
 * can be searched and replaced as one consistent operation.
 * @since 3.2
 */
public interface Replaceable {

    /**
     * Returns the user-editable text fields supported by Search and Replace.
     * Implementations should not expose internal property keys or read-only data.
     *
     * @return replaceable fields
     * @since 2026.08
     */
    default List<ReplaceableField> getReplaceableFields() {
        return List.of();
    }

    /**
     * Replace in object  by replaceBy
     *
     * @param regex Regular expression to search for
     * @param replaceBy Text used as replacement
     * @param caseSensitive flag, whether search should be done case sensitive
     * @return number of replacements
     * @throws Exception
     *             when something fails while replacing
     */
    int replace(String regex, String replaceBy, boolean caseSensitive)
        throws Exception;

    /**
     * Replace a literal value without interpreting it as a regular expression.
     * Implementations may override this when they need to protect existing
     * JMeter variable references or replace fields that are not covered by
     * their regular-expression replacement implementation.
     *
     * @param literal value to search for
     * @param replaceBy replacement value
     * @return number of replacements
     * @throws Exception when something fails while replacing
     */
    default int replaceLiteral(String literal, String replaceBy) throws Exception {
        return replace(Pattern.quote(literal), replaceBy, true);
    }
}
