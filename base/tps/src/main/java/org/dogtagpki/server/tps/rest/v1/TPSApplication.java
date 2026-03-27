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
// (C) 2013 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package org.dogtagpki.server.tps.rest.v1;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Properties;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

import org.dogtagpki.server.rest.v1.ACLInterceptor;
import org.dogtagpki.server.rest.v1.AuditService;
import org.dogtagpki.server.rest.v1.AuthMethodInterceptor;
import org.dogtagpki.server.rest.v1.GroupService;
import org.dogtagpki.server.rest.v1.JobService;
import org.dogtagpki.server.rest.v1.MessageFormatInterceptor;
import org.dogtagpki.server.rest.v1.PKIExceptionMapper;
import org.dogtagpki.server.rest.v1.SelfTestService;
import org.dogtagpki.server.rest.v1.SessionContextInterceptor;
import org.dogtagpki.server.rest.v1.UserService;
import org.dogtagpki.server.tps.TPSAccountService;
import org.dogtagpki.server.tps.config.ConfigService;

/**
 * @author Endi S. Dewata <edewata@redhat.com>
 */
@ApplicationPath("/v1")
public class TPSApplication extends Application {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TPSApplication.class);

    private Set<Object> singletons = new LinkedHashSet<>();
    private Set<Class<?>> classes = new LinkedHashSet<>();

    private static String getDefaultV1ApiStatus() {
        try (InputStream is = TPSApplication.class.getClassLoader().getResourceAsStream("build.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("v1.api.status.default", "enabled");
            }
        } catch (IOException e) {
            logger.warn("TPSApplication: Unable to read build.properties, defaulting to enabled", e);
        }
        return "enabled";
    }

    public TPSApplication() {

        // Check v1 API status
        // Use build-time default unless overridden by system property
        String v1ApiStatus = System.getProperty("v1.api.status", getDefaultV1ApiStatus());

        if ("disabled".equals(v1ApiStatus)) {
            logger.warn("======================================================================");
            logger.warn("TPS v1 REST API has been DISABLED.");
            logger.warn("All v1 endpoints will return HTTP 410 Gone.");
            logger.warn("Please use v2 API instead.");
            logger.warn("======================================================================");
            // Register only the disabled resource which returns clean error messages
            classes.add(org.dogtagpki.server.rest.v1.V1ApiDisabledResource.class);
            return;
        }

        if ("deprecated".equals(v1ApiStatus)) {
            logger.warn("======================================================================");
            logger.warn("WARNING: v1 REST API is DEPRECATED and will be removed in a future release.");
            logger.warn("Please migrate to v2 API as soon as possible.");
            logger.warn("======================================================================");
        }

        // account
        classes.add(TPSAccountService.class);

        // audit
        classes.add(AuditService.class);

        // user and group management
        classes.add(GroupService.class);
        classes.add(UserService.class);

        // activities
        classes.add(ActivityService.class);

        // authenticators
        classes.add(AuthenticatorService.class);

        // certificates
        classes.add(TPSCertService.class);

        // config
        classes.add(ConfigService.class);

        // connections
        classes.add(ConnectorService.class);

        // profiles
        classes.add(TPSProfileService.class);
        classes.add(ProfileMappingService.class);

        // job management
        classes.add(JobService.class);

        // selftests
        classes.add(SelfTestService.class);

        // tokens
        classes.add(TokenService.class);

        // exception mapper
        classes.add(PKIExceptionMapper.class);

        // interceptors
        singletons.add(new SessionContextInterceptor());
        singletons.add(new AuthMethodInterceptor());
        singletons.add(new ACLInterceptor());
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
