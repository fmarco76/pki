project(tps_library CXX)

set(TPS_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include)

set(TPS_PUBLIC_INCLUDE_DIRS
  ${CMAKE_CURRENT_BINARY_DIR}
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${TPS_INCLUDE_DIR}
  CACHE INTERNAL "TPS public include directories"
)

set(TPS_PRIVATE_INCLUDE_DIRS
  ${TPS_PUBLIC_INCLUDE_DIRS}
  ${CMAKE_BINARY_DIR}
  ${JNI_INCLUDE_DIRS}
  ${NSS_INCLUDE_DIRS}
  ${NSPR_INCLUDE_DIRS}
  ${APR_INCLUDE_DIRS}
  ${LDAP_INCLUDE_DIRS}
)

set(TPS_SHARED_LIBRARY
  tps_library
  CACHE INTERNAL "TPS shared library"
)

set(TPS_LINK_LIBRARIES
  ${NSPR_LIBRARIES}
  ${NSS_LIBRARIES}
  ${APR_LIBRARIES}
  ${LDAP_LIBRARIES}
)

set(tps_library_SRCS
    main/Buffer.cpp
    main/NameValueSet.cpp
    main/Util.cpp
    main/RA_Client.cpp
    main/RA_Conn.cpp
    main/RA_Msg.cpp
    main/RA_Token.cpp
    main/Memory.cpp
    main/AuthParams.cpp
    main/TPSClientCLI.cpp
    apdu/APDU.cpp
    apdu/Unblock_Pin_APDU.cpp
    apdu/Create_Object_APDU.cpp
    apdu/Set_Pin_APDU.cpp
    apdu/Set_IssuerInfo_APDU.cpp
    apdu/Get_IssuerInfo_APDU.cpp
    apdu/Create_Pin_APDU.cpp
    apdu/List_Pins_APDU.cpp
    apdu/Initialize_Update_APDU.cpp
    apdu/Get_Version_APDU.cpp
    apdu/Get_Status_APDU.cpp
    apdu/Get_Data_APDU.cpp
    apdu/External_Authenticate_APDU.cpp
    apdu/Generate_Key_APDU.cpp
    apdu/Generate_Key_ECC_APDU.cpp
    apdu/Read_Buffer_APDU.cpp
    apdu/Read_Object_APDU.cpp
    apdu/Write_Object_APDU.cpp
    apdu/Put_Key_APDU.cpp
    apdu/Select_APDU.cpp
    apdu/Delete_File_APDU.cpp
    apdu/Install_Applet_APDU.cpp
    apdu/Format_Muscle_Applet_APDU.cpp
    apdu/Load_File_APDU.cpp
    apdu/Install_Load_APDU.cpp
    apdu/Lifecycle_APDU.cpp
    apdu/List_Objects_APDU.cpp
    apdu/Import_Key_APDU.cpp
    apdu/Import_Key_Enc_APDU.cpp
    apdu/APDU_Response.cpp
    apdu/Get_Lifecycle_APDU.cpp
    msg/RA_Begin_Op_Msg.cpp
    msg/RA_End_Op_Msg.cpp
    msg/RA_Login_Request_Msg.cpp
    msg/RA_Login_Response_Msg.cpp
    msg/RA_SecureId_Request_Msg.cpp
    msg/RA_SecureId_Response_Msg.cpp
    msg/RA_ASQ_Request_Msg.cpp
    msg/RA_ASQ_Response_Msg.cpp
    msg/RA_New_Pin_Request_Msg.cpp
    msg/RA_New_Pin_Response_Msg.cpp
    msg/RA_Token_PDU_Request_Msg.cpp
    msg/RA_Token_PDU_Response_Msg.cpp
    msg/RA_Status_Update_Request_Msg.cpp
    msg/RA_Status_Update_Response_Msg.cpp
    msg/RA_Extended_Login_Request_Msg.cpp
    msg/RA_Extended_Login_Response_Msg.cpp
)

include_directories(${TPS_PRIVATE_INCLUDE_DIRS})

add_library(${TPS_SHARED_LIBRARY} SHARED ${tps_library_SRCS})
target_link_libraries(${TPS_SHARED_LIBRARY} ${TPS_LINK_LIBRARIES})

set_target_properties(
    ${TPS_SHARED_LIBRARY}
    PROPERTIES
        OUTPUT_NAME
            pki-tps
)

install(
    TARGETS
        ${TPS_SHARED_LIBRARY}
    LIBRARY DESTINATION ${LIB_INSTALL_DIR}
)

