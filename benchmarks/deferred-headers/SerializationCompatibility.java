/*
 * Copyright 2024-2026 Breaking IT
 *
 * Licensed under the BreakTest Community Source License 1.0.
 * You may not use this file except in compliance with that license.
 * See the LICENSE file at the root of this distribution.
 */

package org.apache.jmeter.protocol.http.sampler;

import java.io.*;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.jmeter.samplers.SampleEvent;

/** Run in separate JVMs with baseline and candidate classes. */
public class SerializationCompatibility {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("read")) {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(args[1]))) {
                SampleEvent event = (SampleEvent) in.readObject();
                check((HTTPSampleResult) event.getResult());
                check((HTTPSampleResult) event.getResult().getSubResults()[0]);
            }
        } else {
            HTTPSampleResult result = make(args[0].equals("deferred"));
            result.addSubResult(make(args[0].equals("deferred")));
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(args[1]))) {
                out.writeObject(new SampleEvent(result, "synthetic-group"));
            }
        }
        System.out.println("PASS " + args[0]);
    }

    private static HTTPSampleResult make(boolean deferred) {
        HTTPSampleResult result = new HTTPSampleResult();
        if (deferred) {
            result.setDeferredRequestHeaders(new DeferredHttpHeaders("", new Header[] {
                    new BasicHeader("X-Request", "synthetic")}, name -> true));
            result.setDeferredResponseHeaders(new DeferredHttpHeaders("HTTP/1.1 200 OK\n", new Header[] {
                    new BasicHeader("X-Response", "synthetic")}, name -> true));
        } else {
            result.setRequestHeaders("X-Request: synthetic\n");
            result.setResponseHeaders("HTTP/1.1 200 OK\nX-Response: synthetic\n");
        }
        return result;
    }

    private static void check(HTTPSampleResult result) {
        if (!result.getRequestHeaders().equals("X-Request: synthetic\n")
                || !result.getResponseHeaders().equals("HTTP/1.1 200 OK\nX-Response: synthetic\n")) {
            throw new AssertionError("Headers lost across serialization");
        }
    }
}
