project(tools)

add_subdirectory(src/main/native/pistool)
add_subdirectory(src/main/native/setpin)
add_subdirectory(src/main/native/tkstool)
add_subdirectory(src/main/native/tpsclient)
add_subdirectory(templates)

javac(pki-tools-classes
    SOURCES
        src/main/java/*.java
    CLASSPATH
        ${JACKSON_ANNOTATIONS_JAR}
        ${JACKSON_CORE_JAR}
        ${JACKSON_DATABIND_JAR}
        ${COMMONS_CODEC_JAR} ${COMMONS_IO_JAR}
        ${COMMONS_CLI_JAR} ${COMMONS_LANG3_JAR}
        ${JAXRS_API_JAR}
        ${RESTEASY_JAXRS_JAR}
        ${HTTPCLIENT_JAR} ${HTTPCORE_JAR}
        ${SLF4J_API_JAR} ${JAXB_API_JAR}
        ${JSS_JAR}
        ${LDAPJDK_JAR}
        ${PKI_COMMON_JAR}
    OUTPUT_DIR
        ${CMAKE_CURRENT_BINARY_DIR}/classes
    DEPENDS
        pki-common-jar
)

add_dependencies(java pki-tools-classes)

set(PKI_TOOLS_JAR ${CMAKE_BINARY_DIR}/dist/pki-tools.jar
    CACHE INTERNAL "pki-tools.jar"
)

configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/resources/META-INF/MANIFEST.MF
    ${CMAKE_CURRENT_BINARY_DIR}/MANIFEST.MF
)

jar(pki-tools-jar
    CREATE
        ${PKI_TOOLS_JAR}
    OPTIONS
        m
    PARAMS
        ${CMAKE_CURRENT_BINARY_DIR}/MANIFEST.MF
    INPUT_DIR
        ${CMAKE_CURRENT_BINARY_DIR}/classes
    DEPENDS
        pki-tools-classes
)

add_dependencies(java pki-tools-jar)

if(WITH_JAVA)
    # install pki-tools.jar in /usr/lib/java/pki
    install(
        FILES
            ${PKI_TOOLS_JAR}
        DESTINATION
            ${JAVA_LIB_INSTALL_DIR}/pki
    )

    # create link to pki-tools.jar in /usr/share/java/pki
    install(
        CODE "
            MESSAGE(
                \"-- Installing: \$ENV{DESTDIR}${JAVA_JAR_INSTALL_DIR}/pki/pki-tools.jar\"
            )
            execute_process(
                COMMAND ln -sf ../../../..${JAVA_LIB_INSTALL_DIR}/pki/pki-tools.jar \$ENV{DESTDIR}${JAVA_JAR_INSTALL_DIR}/pki
            )
        "
    )
endif(WITH_JAVA)

install(
    FILES
        src/main/resources/KRATool.cfg
    DESTINATION
        ${SHARE_INSTALL_PREFIX}/pki/tools
)

add_custom_target(pki-tools-man ALL
    COMMENT "Creating PKI tools manuals")

add_custom_command(
    TARGET pki-tools-man POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory man/man1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/AtoB.1.md -out man/man1/AtoB.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/AuditVerify.1.md -out man/man1/AuditVerify.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/BtoA.1.md -out man/man1/BtoA.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/CMCEnroll.1.md -out man/man1/CMCEnroll.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/CMCRequest.1.md -out man/man1/CMCRequest.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/CMCResponse.1.md -out man/man1/CMCResponse.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/CMCSharedToken.1.md -out man/man1/CMCSharedToken.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/KRATool.1.md -out man/man1/KRATool.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/PKCS10Client.1.md -out man/man1/PKCS10Client.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/PKICertImport.1.md -out man/man1/PKICertImport.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/PrettyPrintCert.1.md -out man/man1/PrettyPrintCert.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/PrettyPrintCrl.1.md -out man/man1/PrettyPrintCrl.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki.1.md -out man/man1/pki.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-audit.1.md -out man/man1/pki-audit.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-ca-cert.1.md -out man/man1/pki-ca-cert.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-ca-kraconnector.1.md -out man/man1/pki-ca-kraconnector.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-ca-profile.1.md -out man/man1/pki-ca-profile.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-client.1.md -out man/man1/pki-client.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-group.1.md -out man/man1/pki-group.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-group-member.1.md -out man/man1/pki-group-member.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-kra-key.1.md -out man/man1/pki-kra-key.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-pkcs12.1.md -out man/man1/pki-pkcs12.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-pkcs12-cert.1.md -out man/man1/pki-pkcs12-cert.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-pkcs12-key.1.md -out man/man1/pki-pkcs12-key.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-securitydomain.1.md -out man/man1/pki-securitydomain.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-tps-profile.1.md -out man/man1/pki-tps-profile.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-user.1.md -out man/man1/pki-user.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-user-cert.1.md -out man/man1/pki-user-cert.1
    COMMAND go-md2man -in ${CMAKE_SOURCE_DIR}/docs/manuals/man1/pki-user-membership.1.md -out man/man1/pki-user-membership.1
)

# install man pages generated by md2man
install(
    DIRECTORY
        ${CMAKE_CURRENT_BINARY_DIR}/man/
    DESTINATION
        ${MAN_INSTALL_DIR}
    FILE_PERMISSIONS
        OWNER_READ OWNER_WRITE
        GROUP_READ
        WORLD_READ
)

install(
    DIRECTORY
        examples/
    DESTINATION
        ${DATA_INSTALL_DIR}/tools/examples/
)

install(
    DIRECTORY
        ${CMAKE_CURRENT_SOURCE_DIR}/bin/
    DESTINATION
        ${BIN_INSTALL_DIR}
    FILE_PERMISSIONS
        OWNER_EXECUTE OWNER_WRITE OWNER_READ
        GROUP_EXECUTE GROUP_READ
        WORLD_EXECUTE WORLD_READ
)

install(
    FILES
        src/main/shell/PKICertImport.bash
    DESTINATION
        ${BIN_INSTALL_DIR}
    RENAME
        PKICertImport
    PERMISSIONS
        OWNER_READ OWNER_WRITE OWNER_EXECUTE
        GROUP_READ GROUP_EXECUTE
        WORLD_READ WORLD_EXECUTE
)

if(WITH_TESTS)
    install(
        FILES
            src/test/shell/test_PKICertImport.bash
        DESTINATION
            ${SHARE_INSTALL_PREFIX}/${APPLICATION_NAME}/tests/util/bin
        PERMISSIONS
            OWNER_READ OWNER_WRITE OWNER_EXECUTE
            GROUP_READ GROUP_EXECUTE
            WORLD_READ WORLD_EXECUTE
    )

    install(
        DIRECTORY
            src/test/resources/certs/
        DESTINATION
            ${SHARE_INSTALL_PREFIX}/${APPLICATION_NAME}/tests/util/resources/certs
    )
endif(WITH_TESTS)
