package org.dogtagpki.est;

import java.io.File;
import java.io.FileReader;
import java.util.Properties;

import org.apache.catalina.realm.RealmBase;

import com.netscape.cms.realm.RealmCommon;
import com.netscape.cms.realm.RealmConfig;
import com.netscape.cms.tomcat.ProxyRealm;


/**
 * Engine that manages the EST backend(s) according to configuration.
 *
 * @author Fraser Tweedale
 */
public class ESTEngine {

    private static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ESTEngine.class);

    private static ESTEngine INSTANCE;

    private String id;

    private RealmCommon realm;

    private ESTBackend backend;
    private ESTRequestAuthorizer requestAuthorizer;

    public static ESTEngine getInstance() {
        return INSTANCE;
    }

    public ESTEngine() {
        INSTANCE = this;
    }

    public ESTBackend getBackend() {
        return backend;
    }

    public ESTRequestAuthorizer getRequestAuthorizer() {
        return requestAuthorizer;
    }

    public void start(String contextPath) throws Throwable {
        logger.info("Starting EST engine");

        String contextPathDirName = "".equals(contextPath) ? "ROOT" : contextPath.substring(1);
        String catalinaBase = System.getProperty("catalina.base");
        String serverConfDir = catalinaBase + File.separator + "conf";
        String estConfDir = serverConfDir + File.separator + contextPathDirName;

        logger.info("EST configuration directory: " + estConfDir);

        initBackend(estConfDir + File.separator + "backend.conf");
        initRequestAuthorizer(estConfDir + File.separator + "authorizer.conf");
        initRealm(estConfDir + File.separator + "realm.conf");

        logger.info("EST engine started");
    }

    public void stop() throws Throwable {
        logger.info("Stopping EST engine");

        if (backend != null) {
            backend.stop();
        }
        if (requestAuthorizer != null) {
            requestAuthorizer.stop();
        }

        if (realm != null) {
            realm.stop();
        }
        logger.info("EST engine stopped");
    }

    private void initBackend(String filename) throws Throwable {
        File file = new File(filename);
        if (!file.exists()) {
            throw new RuntimeException("Missing backend configuration file " + filename);
        }

        logger.info("Loading EST backend config from " + filename);
        Properties props = new Properties();
        try (FileReader reader = new FileReader(file)) {
            props.load(reader);
        }
        ESTBackendConfig config = ESTBackendConfig.fromProperties(props);

        logger.info("Initializing EST backend");

        String className = config.getClassName();
        Class<ESTBackend> backendClass = (Class<ESTBackend>) Class.forName(className);

        backend = backendClass.getDeclaredConstructor().newInstance();
        backend.setConfig(config);
        backend.start();
    }

    private void initRequestAuthorizer(String filename) throws Throwable {
        File file = new File(filename);
        if (!file.exists()) {
            throw new RuntimeException("Missing request authorizer configuration file " + filename);
        }

        logger.info("Loading EST request authorizer config from " + filename);
        Properties props = new Properties();
        try (FileReader reader = new FileReader(file)) {
            props.load(reader);
        }
        ESTRequestAuthorizerConfig config = ESTRequestAuthorizerConfig.fromProperties(props);

        logger.info("Initializing EST request authorizer");

        String className = config.getClassName();
        Class<ESTRequestAuthorizer> clazz = (Class<ESTRequestAuthorizer>) Class.forName(className);

        requestAuthorizer = clazz.getDeclaredConstructor().newInstance();
        requestAuthorizer.setConfig(config);
        requestAuthorizer.start();
    }

    private void initRealm(String filename) throws Throwable {
        RealmConfig realmConfig = null;
        File realmConfigFile = new File(filename);
        
        if (realmConfigFile.exists()) {
            logger.info("Loading EST realm config from " + realmConfigFile);
            Properties props = new Properties();
            try (FileReader reader = new FileReader(realmConfigFile)) {
                props.load(reader);
            }
            realmConfig = RealmConfig.fromProperties(props);

        } else {
            logger.info("Loading default realm config");
            realmConfig = new RealmConfig();
        }

        logger.info("Initializing EST realm");
        String className = realmConfig.getClassName();
        if (className == null) {
            throw new RuntimeException("File " + filename + " misses 'class' property");
        }
        Class<RealmCommon> realmClass = (Class<RealmCommon>) Class.forName(className);
        realm = realmClass.getDeclaredConstructor().newInstance();
        realm.setConfig(realmConfig);
        realm.start();
        ProxyRealm.registerRealm(id, realm);
    }

    public void setId(String id) {
        this.id = id;
    }

}
