/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.monitoring.reporting.web.handler;

import org.apache.commons.monitoring.reporting.web.handler.api.Regex;
import org.apache.commons.monitoring.reporting.web.handler.api.TemplateHelper;
import org.apache.commons.monitoring.reporting.web.template.Templates;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.RuntimeSingleton;
import org.apache.velocity.runtime.resource.loader.ResourceLoader;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class FilteringEndpoints {
    private static final String BOOTSTRAP_CSS = "/resources/css/bootstrap.min.css";
    private static final String MONITORING_CSS = "/resources/css/monitoring.css";

    private ResourceLoader rl;

    public FilteringEndpoints() {
        try {
            rl = ResourceLoader.class.cast(FilteringEndpoints.class.getClassLoader().loadClass((String) RuntimeSingleton.getProperty(Templates.RESOURCE_LOADER_KEY)).newInstance());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Regex(MONITORING_CSS)
    public void filterCss(final TemplateHelper helper) {
        helper.renderPlain(MONITORING_CSS);
    }

    @Regex(BOOTSTRAP_CSS)
    public void filterBootstrapCss(final TemplateHelper helper) {
        helper.renderPlain(BOOTSTRAP_CSS);
    }

    @Regex("/resources/.*")
    public void filterOtherResources(final HttpServletRequest req, final HttpServletResponse resp) {
        final String requestURI = req.getRequestURI();

        final InputStream is;
        try {
            is = rl.getResourceStream(requestURI.substring(((String) req.getAttribute("baseUri")).length()));
        } catch (final ResourceNotFoundException rnfe) {
            return;
        }

        try {
            byte[] buffer = new byte[1024];
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            int length;
            while ((length = is.read(buffer)) != -1) {
                os.write(buffer, 0, length);
            }
            req.setAttribute("resourceCache", os);
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        } finally {
            try {
                is.close();
            } catch (final IOException e) {
                // no-op
            }
        }
    }
}
