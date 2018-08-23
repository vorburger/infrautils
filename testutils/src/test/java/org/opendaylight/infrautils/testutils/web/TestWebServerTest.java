/*
 * Copyright (c) 2018 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.infrautils.testutils.web;

import static com.google.common.truth.Truth.assertThat;
import static org.opendaylight.infrautils.testutils.Asserts.assertThrows;

import com.google.common.io.CharStreams;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;

/**
 * Test for {@link TestWebServer}.
 *
 * @author Michael Vorburger.ch
 */
public class TestWebServerTest {

    // This class is a simpler version of code inspired by org.opendaylight.aaa.web.test

    @Test
    public void testWebServerWithoutServlet() throws ServletException, IOException {
        try (TestWebServer webServer = new TestWebServer()) {
            assertThrows(FileNotFoundException.class, () -> checkTestServlet(webServer.getTestContextURL() + "nada"));
        }
    }

    @Test
    public void testWebServerWithOneServlet() throws ServletException, IOException {
        try (TestWebServer webServer = new TestWebServer()) {
            webServer.registerServlet(new TestServlet(), "/testServlet");
            checkTestServlet(webServer.getTestContextURL() + "testServlet");
        }
    }

    @Test
    public void testWebServerWithTwosServlets() throws ServletException, IOException {
        try (TestWebServer webServer = new TestWebServer()) {
            webServer.registerServlet(new TestServlet(), "/firstServlet");
            webServer.registerServlet(new TestServlet(), "/secondServlet");
            checkTestServlet(webServer.getTestContextURL() + "secondServlet");
        }
    }

    private static void checkTestServlet(String theURL) throws IOException {
        URL url = new URL(theURL);
        URLConnection conn = url.openConnection();
        try (InputStream inputStream = conn.getInputStream()) {
            // The hard-coded ASCII here is strictly speaking wrong of course
            // (should interpret header from reply), but good enough for a test.
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.US_ASCII)) {
                String result = CharStreams.toString(reader);
                assertThat(result).startsWith("hello, world");
            }
        }
    }

    @SuppressWarnings("serial")
    private static class TestServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws IOException {
            response.getOutputStream().println("hello, world");
        }
    }
}