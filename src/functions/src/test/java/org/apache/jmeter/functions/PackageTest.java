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

import static org.apache.jmeter.functions.FunctionTestHelper.makeParams;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;

import org.apache.jmeter.junit.JMeterTestCase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test cases for Functions
 */
public class PackageTest extends JMeterTestCase {

    private static final Logger log = LoggerFactory.getLogger(PackageTest.class);

    // XPathFileContainer tests

    @Test
    public void XPathtestNull() throws Exception {
        assertThrows(FileNotFoundException.class, () -> new XPathFileContainer("nosuch.xml", "/"));
    }

    @Test
    public void XPathtestrowNum() throws Exception {
        XPathFileContainer f = new XPathFileContainer(getResourceFilePath("xpathfilecontainer.xml"), "/project/target/@name");
        assertNotNull(f);

        int myRow = f.nextRow();
        assertEquals(0, myRow);
        assertEquals(1, f.getNextRow());

        myRow = f.nextRow();
        assertEquals(1, myRow);
        assertEquals(2, f.getNextRow());

        myRow = f.nextRow();
        assertEquals(2, myRow);
        assertEquals(3, f.getNextRow());
    }

    @Test
    public void XPathtestColumns() throws Exception {
        XPathFileContainer f = new XPathFileContainer(getResourceFilePath("xpathfilecontainer.xml"), "/project/target/@name");
        assertNotNull(f);
        assertTrue(f.size() > 0, "Not empty");
        int last = 0;
        for (int i = 0; i < f.size(); i++) {
            last = f.nextRow();
            log.debug("found [{}]{}", i, f.getXPathString(last));
        }
        assertEquals(last + 1, f.size());

    }

    @Test
    public void XPathtestDefault() throws Exception {
        XPathFileContainer f = new XPathFileContainer(getResourceFilePath("xpathfilecontainer.xml"), "/project/@default");
        assertNotNull(f);
        assertTrue(f.size() > 0, "Not empty");
        assertEquals("install", f.getXPathString(0));

    }

    @Test
    public void XPathEmpty() throws Exception{
        XPath xp = setupXPath("","");
        String val=xp.execute();
        assertEquals("", val);
        val=xp.execute();
        assertEquals("", val);
        val=xp.execute();
        assertEquals("", val);
    }

    @Test
    public void XPathNoFile() throws Exception{
        XPath xp = setupXPath("no-such-file","");
        String val=xp.execute();
        assertEquals("", val); // TODO - should check that error has been logged...
    }

    @Test
    public void XPathFile() throws Exception{
        XPath xp = setupXPath("testfiles/XPathTest2.xml","note/body");
        assertEquals("Don't forget me this weekend!", xp.execute());

        xp = setupXPath("testfiles/XPathTest2.xml","//note2");
        assertEquals("", xp.execute());

        xp = setupXPath("testfiles/XPathTest2.xml","//note/to");
        assertEquals("Tove", xp.execute());
    }

    @Test
    public void XPathFile1() throws Exception{
        XPath xp = setupXPath("testfiles/XPathTest.xml","//user/@username");
        assertEquals("u1", xp.execute());
        assertEquals("u2", xp.execute());
        assertEquals("u3", xp.execute());
        assertEquals("u4", xp.execute());
        assertEquals("u5", xp.execute());
        assertEquals("u1", xp.execute());
    }

    @Test
    public void XPathFile2() throws Exception{
        XPath xp1  = setupXPath("testfiles/XPathTest.xml","//user/@username");
        XPath xp1a = setupXPath("testfiles/XPathTest.xml","//user/@username");
        XPath xp2  = setupXPath("testfiles/XPathTest.xml","//user/@password");
        XPath xp2a = setupXPath("testfiles/XPathTest.xml","//user/@password");
        assertEquals("u1", xp1.execute());
        assertEquals("p1", xp2.execute());
        assertEquals("p2", xp2.execute());
        assertEquals("u2", xp1a.execute());
        assertEquals("u3", xp1.execute());
        assertEquals("u4", xp1.execute());
        assertEquals("p3", xp2a.execute());
    }

    private XPath setupXPath(String file, String expr) throws Exception{
        XPath xp = new XPath();
        xp.setParameters(makeParams(getResourceFilePath(file), expr));
        return xp;
    }

}
