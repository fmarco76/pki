project(server-webapp)

# build server-webapp classes
javac(pki-server-webapp-classes
    SOURCES
        src/main/java/*.java
    CLASSPATH
        ${SERVLET_JAR}
        ${JAXRS_API_JAR}
        ${SLF4J_API_JAR}
        ${JACKSON2_ANNOTATIONS_JAR}
        ${JACKSON2_CORE_JAR}
        ${PKI_COMMON_JAR}
        ${PKI_SERVER_JAR}
    OUTPUT_DIR
        ${CMAKE_CURRENT_BINARY_DIR}/classes
    DEPENDS
        pki-server-jar
)

set(PKI_SERVER_WEBAPP_JAR ${CMAKE_BINARY_DIR}/dist/pki-server-webapp.jar
    CACHE INTERNAL "pki-server-webapp.jar"
)

configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/resources/META-INF/MANIFEST.MF
    ${CMAKE_CURRENT_BINARY_DIR}/MANIFEST.MF
)

jar(pki-server-webapp-jar
    CREATE
        ${PKI_SERVER_WEBAPP_JAR}
    OPTIONS
        m
    PARAMS
        ${CMAKE_CURRENT_BINARY_DIR}/MANIFEST.MF
    INPUT_DIR
        ${CMAKE_CURRENT_BINARY_DIR}/classes
    INPUT_DIR
        ${CMAKE_CURRENT_SOURCE_DIR}/src/main/resources
    DEPENDS
        pki-server-webapp-classes
)

# Create /usr/share/pki/server/webapps/pki/WEB-INF/lib. This can be customized for different platforms in RPM spec.

add_custom_target(pki-server-webapp-lib ALL
    COMMENT "Creating links for PKI server webapp library")

add_custom_command(
    TARGET pki-server-webapp-lib
    COMMAND ${CMAKE_COMMAND} -E make_directory webapp/lib
    COMMAND ln -sf ../../../../../../../..${SLF4J_API_JAR} webapp/lib/slf4j-api.jar
    COMMAND ln -sf ../../../../../../../..${SLF4J_JDK14_JAR} webapp/lib/slf4j-jdk14.jar
    COMMAND ln -sf ../../../../../../../..${JAVA_JAR_INSTALL_DIR}/pki/pki-server.jar webapp/lib/pki-server.jar
    COMMAND ln -sf ../../../../../../../..${JAVA_JAR_INSTALL_DIR}/pki/pki-server-webapp.jar webapp/lib/pki-server-webapp.jar
    COMMAND ln -sf ../../../../../../../..${RESTEASY_SERVLET_INITIALIZER_JAR} webapp/lib/resteasy-servlet-intializer.jar
)

install(
    DIRECTORY
        webapps
    DESTINATION
        ${DATA_INSTALL_DIR}/server
)

install(
    FILES
        ${PKI_SERVER_WEBAPP_JAR}
    DESTINATION
        ${JAVA_JAR_INSTALL_DIR}/pki
)

install(
    DIRECTORY
        ${CMAKE_CURRENT_BINARY_DIR}/webapp/lib/
    DESTINATION
        ${DATA_INSTALL_DIR}/server/webapps/pki/WEB-INF/lib
)