// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2017 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---

package org.dogtagpki.server.rest.v1;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

import org.dogtagpki.server.rest.v1.MessageFormatInterceptor;
import org.dogtagpki.server.rest.v1.PKIExceptionMapper;

@ApplicationPath("/v1")
public class PKIApplication extends Application {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PKIApplication.class);

    private Set<Object> singletons = new LinkedHashSet<>();
    private Set<Class<?>> classes = new LinkedHashSet<>();

    private static String getDefaultV1ApiStatus() {
        try (InputStream is = PKIApplication.class.getClassLoader().getResourceAsStream("build.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("v1.api.status.default", "enabled");
            }
        } catch (IOException e) {
            logger.warn("PKIApplication: Unable to read build.properties, defaulting to enabled", e);
        }
        return "enabled";
    }

    public PKIApplication() {

        // Check v1 API status
        // Use build-time default unless overridden by system property
        String v1ApiStatus = System.getProperty("v1.api.status", getDefaultV1ApiStatus());

        if ("disabled".equals(v1ApiStatus)) {
            logger.warn("======================================================================");
            logger.warn("v1 REST API has been DISABLED.");
            logger.warn("All v1 endpoints will return HTTP 410 Gone.");
            logger.warn("Please use v2 API instead.");
            logger.warn("======================================================================");
            // Register only the disabled resource which returns clean error messages
            classes.add(V1ApiDisabledResource.class);
            return;
        }

        if ("deprecated".equals(v1ApiStatus)) {
            logger.warn("======================================================================");
            logger.warn("WARNING: v1 REST API is DEPRECATED and will be removed in a future release.");
            logger.warn("Please migrate to v2 API as soon as possible.");
            logger.warn("======================================================================");
        }

        // services
        classes.add(AppService.class);
        classes.add(InfoService.class);
        classes.add(LoginService.class);

        // exception mappers
        classes.add(PKIExceptionMapper.class);

        // interceptors
        singletons.add(new MessageFormatInterceptor());
    }

    @Override
    public Set<Class<?>> getClasses() {
        return classes;
    }

    @Override
    public Set<Object> getSingletons() {
        return singletons;
    }

}
