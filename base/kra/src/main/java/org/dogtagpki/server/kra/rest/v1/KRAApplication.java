package org.dogtagpki.server.kra.rest.v1;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

import org.dogtagpki.server.rest.v1.ACLInterceptor;
import org.dogtagpki.server.rest.v1.AccountService;
import org.dogtagpki.server.rest.v1.AuditService;
import org.dogtagpki.server.rest.v1.AuthMethodInterceptor;
import org.dogtagpki.server.rest.v1.GroupService;
import org.dogtagpki.server.rest.v1.JobService;
import org.dogtagpki.server.rest.v1.MessageFormatInterceptor;
import org.dogtagpki.server.rest.v1.PKIExceptionMapper;
import org.dogtagpki.server.rest.v1.SecurityDomainService;
import org.dogtagpki.server.rest.v1.SelfTestService;
import org.dogtagpki.server.rest.v1.SessionContextInterceptor;
import org.dogtagpki.server.rest.v1.UserService;

@ApplicationPath("/v1")
public class KRAApplication extends Application {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(KRAApplication.class);

    private Set<Object> singletons = new LinkedHashSet<>();
    private Set<Class<?>> classes = new LinkedHashSet<>();

    private static String getDefaultV1ApiStatus() {
        try (InputStream is = KRAApplication.class.getClassLoader().getResourceAsStream("build.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("v1.api.status.default", "enabled");
            }
        } catch (IOException e) {
            logger.warn("KRAApplication: Unable to read build.properties, defaulting to enabled", e);
        }
        return "enabled";
    }

    public KRAApplication() {

        // Check v1 API status
        // Use build-time default unless overridden by system property
        String v1ApiStatus = System.getProperty("v1.api.status", getDefaultV1ApiStatus());

        if ("disabled".equals(v1ApiStatus)) {
            logger.warn("======================================================================");
            logger.warn("KRA v1 REST API has been DISABLED.");
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
        classes.add(AccountService.class);

        // audit
        classes.add(AuditService.class);

        // security domain
        classes.add(SecurityDomainService.class);

        // keys and keyrequests
        classes.add(KeyService.class);
        classes.add(KeyRequestService.class);

        // job management
        classes.add(JobService.class);

        // selftests
        classes.add(SelfTestService.class);

        // user and group management
        classes.add(GroupService.class);
        classes.add(UserService.class);

        // system certs
        classes.add(KRASystemCertService.class);

        // exception mapper
        classes.add(PKIExceptionMapper.class);

        // info service
        classes.add(KRAInfoService.class);

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
