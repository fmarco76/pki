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
// (C) 2011 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.cmstools;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.spec.MGF1ParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Vector;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.PatternSyntaxException;

import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import org.dogtagpki.util.logging.PKILogger;
import org.dogtagpki.util.logging.PKILogger.LogLevel;
import org.mozilla.jss.CertDatabaseException;
import org.mozilla.jss.CryptoManager;
import org.mozilla.jss.KeyDatabaseException;
import org.mozilla.jss.crypto.AlreadyInitializedException;
import org.mozilla.jss.crypto.CryptoToken;
import org.mozilla.jss.crypto.InvalidKeyFormatException;
import org.mozilla.jss.crypto.KeyWrapAlgorithm;
import org.mozilla.jss.crypto.KeyWrapper;
import org.mozilla.jss.crypto.ObjectNotFoundException;
import org.mozilla.jss.crypto.PrivateKey;
import org.mozilla.jss.crypto.SymmetricKey;
import org.mozilla.jss.crypto.TokenException;
import org.mozilla.jss.crypto.X509Certificate;
import org.mozilla.jss.netscape.security.provider.RSAPublicKey;
import org.mozilla.jss.netscape.security.util.DerInputStream;
import org.mozilla.jss.netscape.security.util.DerOutputStream;
import org.mozilla.jss.netscape.security.util.DerValue;
import org.mozilla.jss.netscape.security.util.Utils;
import org.mozilla.jss.netscape.security.x509.X509CertImpl;
import org.mozilla.jss.pkcs11.PK11Cert;
import org.mozilla.jss.pkcs11.PK11PubKey;
import org.mozilla.jss.util.Password;

import com.netscape.cmsutil.crypto.CryptoUtil;

/**
 * The KRATool class is a utility program designed to operate on an LDIF file
 * to perform one or more of the following tasks:
 *
 * <PRE>
 *     (A) Use a new storage key (e. g. - a 2048-bit key to replace a
 *         1024-bit key) to rewrap the existing triple DES symmetric key
 *         that was used to wrap a user's private key.
 *
 *         STARTING INVENTORY:
 *
 *             (1) a KRATOOL configuration file containing KRA LDIF record
 *                 types and the processing status of their associated fields
 *
 *             (2) an LDIF file containing 'exported' KRA data
 *                 (referred to as the "source" KRA)
 *
 *                 NOTE:  If this LDIF file contains data that was originally
 *                        from a KRA instance that was prior to RHCS 8, it
 *                        must have previously undergone the appropriate
 *                        migration steps.
 *
 *             (3) the NSS security databases associated with the data
 *                 contained in the source LDIF file
 *
 *                 NOTE:  If the storage key was located on an HSM, then the
 *                        HSM must be available to the machine on which the
 *                        KRATool is being executed (since the RSA private
 *                        storage key is required for unwrapping the
 *                        symmetric triple DES key).  Additionally, a
 *                        password may be required to unlock access to
 *                        this key (e. g. - which may be located in
 *                        the source KRA's 'password.conf' file).
 *
 *             (4) a file containing the ASCII BASE-64 storage certificate
 *                 from the KRA instance for which the output LDIF file is
 *                 intended (referred to as the "target")
 *
 *         ENDING INVENTORY:
 *
 *             (1) all items listed in the STARTING INVENTORY (unchanged)
 *
 *             (2) a log file containing information suitable for audit
 *                 purposes
 *
 *             (3) an LDIF file containing the revised data suitable for
 *                 'import' into a new KRA (referred to as the "target" KRA)
 *
 *         KRATool PARAMETERS:
 *
 *             (1) the name of the KRATOOL configuration file containing
 *                 KRA LDIF record types and the processing status of their
 *                 associated fields
 *
 *             (2) the name of the input LDIF file containing data which was
 *                 'exported' from the source KRA instance
 *
 *             (3) the name of the output LDIF file intended to contain the
 *                 revised data suitable for 'import' to a target KRA instance
 *
 *             (4) the name of the log file that may be used for auditing
 *                 purposes
 *
 *             (5) the path to the security databases that were used by
 *                 the source KRA instance
 *
 *             (6) the name of the token that was used by
 *                 the source KRA instance
 *
 *             (7) the name of the storage certificate that was used by
 *                 the source KRA instance
 *
 *             (8) the name of the file containing the ASCII BASE-64 storage
 *                 certificate from the target KRA instance for which the
 *                 output LDIF file is intended
 *
 *             (9) OPTIONALLY, the name of a file which ONLY contains the
 *                 password needed to access the source KRA instance's
 *                 security databases
 *
 *            (10) OPTIONALLY, choose to change the specified source KRA naming
 *                 context to the specified target KRA naming context
 *
 *            (11) OPTIONALLY, choose to ONLY process CA enrollment requests,
 *                 CA recovery requests, CA key records, TPS netkeyKeygen
 *                 enrollment requests, TPS recovery requests, and
 *                 TPS key records
 *
 *         DATA FIELDS AFFECTED (using default config file values):
 *
 *             (1) CA KRA enrollment request
 *
 *                 (a) dateOfModify
 *                 (b) extdata-requestnotes
 *
 *             (2) CA KRA key record
 *
 *                 (a) dateOfModify
 *                 (b) privateKeyData
 *
 *             (3) CA KRA recovery request
 *
 *                 (a) dateOfModify
 *                 (b) extdata-requestnotes (NEW)
 *
 *             (4) TPS KRA netkeyKeygen (enrollment) request
 *
 *                 (a) dateOfModify
 *                 (b) extdata-requestnotes (NEW)
 *
 *             (5) TPS KRA key record
 *
 *                 (a) dateOfModify
 *                 (b) privateKeyData
 *
 *             (6) TPS KRA recovery request
 *
 *                 (a) dateOfModify
 *                 (b) extdata-requestnotes (NEW)
 *
 *     (B) Specify an ID offset to append to existing numeric data
 *         (e. g. - to renumber data for use in KRA consolidation efforts).
 *
 *         STARTING INVENTORY:
 *
 *             (1) a KRATOOL configuration file containing KRA LDIF record
 *                 types and the processing status of their associated fields
 *
 *             (2) an LDIF file containing 'exported' KRA data
 *                 (referred to as the "source" KRA)
 *
 *                 NOTE:  If this LDIF file contains data that was originally
 *                        from a KRA instance that was prior to RHCS 8, it
 *                        must have previously undergone the appropriate
 *                        migration steps.
 *
 *         ENDING INVENTORY:
 *
 *             (1) all items listed in the STARTING INVENTORY (unchanged)
 *
 *             (2) a log file containing information suitable for audit
 *                 purposes
 *
 *             (3) an LDIF file containing the revised data suitable for
 *                 'import' into a new KRA (referred to as the "target" KRA)
 *
 *         KRATool PARAMETERS:
 *
 *             (1) the name of the KRATOOL configuration file containing
 *                 KRA LDIF record types and the processing status of their
 *                 associated fields
 *
 *             (2) the name of the input LDIF file containing data which was
 *                 'exported' from the source KRA instance
 *
 *             (3) the name of the output LDIF file intended to contain the
 *                 revised data suitable for 'import' to a target KRA instance
 *
 *             (4) the name of the log file that may be used for auditing
 *                 purposes
 *
 *             (5) a large numeric ID offset (mask) to be appended to existing
 *                 numeric data in the source KRA instance's LDIF file
 *
 *             (6) OPTIONALLY, choose to change the specified source KRA naming
 *                 context to the specified target KRA naming context
 *
 *             (7) OPTIONALLY, choose to ONLY process CA enrollment requests,
 *                 CA recovery requests, CA key records, TPS netkeyKeygen
 *                 enrollment requests, TPS recovery requests, and
 *                 TPS key records
 *
 *         DATA FIELDS AFFECTED (using default config file values):
 *
 *             (1) CA KRA enrollment request
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) extdata-keyrecord
 *                 (d) extdata-requestnotes
 *                 (e) requestId
 *
 *             (2) CA KRA key record
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) serialno
 *
 *             (3) CA KRA recovery request
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) extdata-requestid
 *                 (d) extdata-requestnotes (NEW)
 *                 (e) extdata-serialnumber
 *                 (f) requestId
 *
 *             (4) TPS KRA netkeyKeygen (enrollment) request
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) extdata-keyrecord
 *                 (d) extdata-requestid
 *                 (e) extdata-requestnotes (NEW)
 *                 (f) requestId
 *
 *             (5) TPS KRA key record
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) serialno
 *
 *             (6) TPS KRA recovery request
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) extdata-requestid
 *                 (d) extdata-requestnotes (NEW)
 *                 (e) extdata-serialnumber
 *                 (f) requestId
 *
 *     (C) Specify an ID offset to be removed from existing numeric data
 *         (e. g. - to undo renumbering used in KRA consolidation efforts).
 *
 *         STARTING INVENTORY:
 *
 *             (1) a KRATOOL configuration file containing KRA LDIF record
 *                 types and the processing status of their associated fields
 *
 *             (2) an LDIF file containing 'exported' KRA data
 *                 (referred to as the "source" KRA)
 *
 *                 NOTE:  If this LDIF file contains data that was originally
 *                        from a KRA instance that was prior to RHCS 8, it
 *                        must have previously undergone the appropriate
 *                        migration steps.
 *
 *         ENDING INVENTORY:
 *
 *             (1) all items listed in the STARTING INVENTORY (unchanged)
 *
 *             (2) a log file containing information suitable for audit
 *                 purposes
 *
 *             (3) an LDIF file containing the revised data suitable for
 *                 'import' into a new KRA (referred to as the "target" KRA)
 *
 *         KRATool PARAMETERS:
 *
 *             (1) the name of the KRATOOL configuration file containing
 *                 KRA LDIF record types and the processing status of their
 *                 associated fields
 *
 *             (2) the name of the input LDIF file containing data which was
 *                 'exported' from the source KRA instance
 *
 *             (3) the name of the output LDIF file intended to contain the
 *                 revised data suitable for 'import' to a target KRA instance
 *
 *             (4) the name of the log file that may be used for auditing
 *                 purposes
 *
 *             (5) a large numeric ID offset (mask) to be removed from existing
 *                 numeric data in the source KRA instance's LDIF file
 *
 *             (6) OPTIONALLY, choose to change the specified source KRA naming
 *                 context to the specified target KRA naming context
 *
 *             (7) OPTIONALLY, choose to ONLY process CA enrollment requests,
 *                 CA recovery requests, CA key records, TPS netkeyKeygen
 *                 enrollment requests, TPS recovery requests, and
 *                 TPS key records
 *
 *         DATA FIELDS AFFECTED (using default config file values):
 *
 *             (1) CA KRA enrollment request
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) extdata-keyrecord
 *                 (d) extdata-requestnotes
 *                 (e) requestId
 *
 *             (2) CA KRA key record
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) serialno
 *
 *             (3) CA KRA recovery request
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) extdata-requestid
 *                 (d) extdata-requestnotes (NEW)
 *                 (e) extdata-serialnumber
 *                 (f) requestId
 *
 *             (4) TPS KRA netkeyKeygen (enrollment) request
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) extdata-keyrecord
 *                 (d) extdata-requestid
 *                 (e) extdata-requestnotes (NEW)
 *                 (f) requestId
 *
 *             (5) TPS KRA key record
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) serialno
 *
 *             (6) TPS KRA recovery request
 *
 *                 (a) cn
 *                 (b) dateOfModify
 *                 (c) extdata-requestid
 *                 (d) extdata-requestnotes (NEW)
 *                 (e) extdata-serialnumber
 *                 (f) requestId
 *
 * </PRE>
 *
 * <P>
 * KRATool may be invoked as follows:
 *
 * <PRE>
 *
 *    KRATool
 *    -kratool_config_file &lt;path + kratool config file&gt;
 *    -source_ldif_file &lt;path + source ldif file&gt;
 *    -target_ldif_file &lt;path + target ldif file&gt;
 *    -log_file &lt;path + log file&gt;
 *    [-source_pki_security_database_path &lt;path to PKI source database&gt;]
 *    [-source_storage_token_name '&lt;source token&gt;']
 *    [-source_storage_certificate_nickname '&lt;source nickname&gt;']
 *    [-target_storage_certificate_file &lt;path to target certificate file&gt;]
 *    [-source_pki_security_database_pwdfile &lt;path to PKI password file&gt;]
 *    [-append_id_offset &lt;numeric offset&gt;]
 *    [-remove_id_offset &lt;numeric offset&gt;]
 *    [-source_kra_naming_context '&lt;original source KRA naming context&gt;']
 *    [-target_kra_naming_context '&lt;renamed target KRA naming context&gt;']
 *    [-process_requests_and_key_records_only]
 *
 *    where the following options are 'Mandatory':
 *
 *    -kratool_config_file &lt;path + kratool config file&gt;
 *    -source_ldif_file &lt;path + source ldif file&gt;
 *    -target_ldif_file &lt;path + target ldif file&gt;
 *    -log_file &lt;path + log file&gt;
 *
 *    AND at least ONE of the following are a 'Mandatory' set of options:
 *
 *        (a) options for using a new storage key for rewrapping:
 *
 *            [-source_pki_security_database_path
 *             &lt;path to PKI source database&gt;]
 *            [-source_storage_token_name '&lt;source token&gt;']
 *            [-source_storage_certificate_nickname '&lt;source nickname&gt;']
 *            [-target_storage_certificate_file
 *             &lt;path to target certificate file&gt;]
 *
 *            AND OPTIONALLY, specify the name of a file which ONLY contains
 *            the password needed to access the source KRA instance's
 *            security databases:
 *
 *            [-source_pki_security_database_pwdfile
 *             &lt;path to PKI password file&gt;]
 *
 *            AND OPTIONALLY, rename source KRA naming context --&gt; target
 *            KRA naming context:
 *
 *            [-source_kra_naming_context '&lt;source KRA naming context&gt;']
 *            [-target_kra_naming_context '&lt;target KRA naming context&gt;']
 *
 *            AND OPTIONALLY, process requests and key records ONLY:
 *
 *            [-process_requests_and_key_records_only]
 *
 *        (b) option for appending the specified numeric ID offset
 *            to existing numerical data:
 *
 *            [-append_id_offset &lt;numeric offset&gt;]
 *
 *            AND OPTIONALLY, rename source KRA naming context --&gt; target
 *            KRA naming context:
 *
 *            [-source_kra_naming_context '&lt;source KRA naming context&gt;']
 *            [-target_kra_naming_context '&lt;target KRA naming context&gt;']
 *
 *            AND OPTIONALLY, process requests and key records ONLY:
 *
 *            [-process_requests_and_key_records_only]
 *
 *        (c) option for removing the specified numeric ID offset
 *            from existing numerical data:
 *
 *            AND OPTIONALLY, rename source KRA naming context --&gt; target
 *            KRA naming context:
 *
 *            [-source_kra_naming_context '&lt;source KRA naming context&gt;']
 *            [-target_kra_naming_context '&lt;target KRA naming context&gt;']
 *
 *            [-remove_id_offset &lt;numeric offset&gt;]
 *
 *            AND OPTIONALLY, process requests and key records ONLY:
 *
 *            [-process_requests_and_key_records_only]
 *
 *        (d) (a) rewrap AND (b) append ID offset
 *            [AND OPTIONALLY, rename source KRA naming context --&gt; target
 *            KRA naming context]
 *            [AND OPTIONALLY process requests and key records ONLY]
 *
 *        (e) (a) rewrap AND (c) remove ID offset
 *            [AND OPTIONALLY, rename source KRA naming context --&gt; target
 *            KRA naming context]
 *            [AND OPTIONALLY process requests and key records ONLY]
 *
 *        NOTE:  Options (b) and (c) are mutually exclusive!
 *
 * </PRE>
 *
 * @author mharmsen
 * @version $Revision$, $Date$
 */
public class KRATool {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(KRATool.class);

    /*************/
    /* Constants */
    /*************/

    // Constants:  Miscellaneous
    private static final boolean FAILURE = false;
    private static final boolean SUCCESS = true;
    private static final String COLON = ":";
    private static final String COMMA = ",";
    private static final String DOT = ".";
    private static final String EQUAL_SIGN = "=";
    private static final String LEFT_BRACE = "[";
    private static final String NEWLINE = "\n";
    private static final String PLUS = "+";
    private static final String RIGHT_BRACE = "]";
    private static final String SPACE = " ";
    private static final String TIC = "'";

    // Constants:  Calendar
    private static final String DATE_OF_MODIFY_PATTERN = "yyyyMMddHHmmss'Z'";

    // Constants:  Command-line Options
    private static final int ID_OFFSET_NAME_VALUE_PAIRS = 1;
    private static final int PWDFILE_NAME_VALUE_PAIRS = 1;
    private static final int NAMING_CONTEXT_NAME_VALUE_PAIRS = 2;
    private static final int MANDATORY_NAME_VALUE_PAIRS = 4;
    private static final int REWRAP_NAME_VALUE_PAIRS = 4;
    private static final int ID_OFFSET_ARGS = 10;
    private static final int REWRAP_ARGS = 16;
    private static final int REWRAP_AND_ID_OFFSET_ARGS = 18;

    // Constants:  Command-line Options (Mandatory)
    private static final String KRA_TOOL = "KRATool";

    private static final String KRATOOL_CFG_FILE = "-kratool_config_file";

    private static final String KRATOOL_CFG_DESCRIPTION = " <complete path to the kratool config file"
                            + NEWLINE
                            + "        "
                            + "  ending with the kratool config file name>";

    private static final String KRATOOL_CFG_FILE_EXAMPLE = KRATOOL_CFG_FILE
                             + " "
                             + "/usr/share/pki/tools/KRATool.cfg";

    private static final String SOURCE_LDIF_FILE = "-source_ldif_file";

    private static final String SOURCE_LDIF_DESCRIPTION = " <complete path to the source LDIF input file"
                            + NEWLINE
                            + "        "
                            + "  ending with the source LDIF file name>";

    private static final String SOURCE_LDIF_FILE_EXAMPLE = SOURCE_LDIF_FILE
                             + " "
                             + "/export/pki/source.ldif";

    private static final String TARGET_LDIF_FILE = "-target_ldif_file";

    private static final String TARGET_LDIF_DESCRIPTION = " <complete path to the target LDIF output file"
                            + NEWLINE
                            + "        "
                            + "  ending with the target LDIF file name>";

    private static final String TARGET_LDIF_FILE_EXAMPLE = TARGET_LDIF_FILE
                             + " "
                             + "/export/pki/target.ldif";

    private static final String LOG_FILE = "-log_file";

    private static final String LOG_DESCRIPTION = " <complete path to the log file"
                    + NEWLINE
                    + "        "
                    + "  ending with the log file name>";

    private static final String LOG_FILE_EXAMPLE = LOG_FILE
                     + " "
                     + "/export/pki/KRATool.log";

    // Constants:  Command-line Options (Rewrap)
    private static final String SOURCE_NSS_DB_PATH = "-source_pki_security_database_path";

    private static final String SOURCE_NSS_DB_DESCRIPTION = "  <complete path to the "
                              + "source security databases"
                              + NEWLINE
                              + "        "
                              + "   used by data in the source LDIF file>";

    private static final String SOURCE_NSS_DB_PATH_EXAMPLE = SOURCE_NSS_DB_PATH
                               + " "
                               + "/export/pki";

    private static final String SOURCE_STORAGE_TOKEN_NAME = "-source_storage_token_name";

    private static final String SOURCE_STORAGE_TOKEN_DESCRIPTION = "  <name of the token containing "
                                     + "the source storage token>";

    private static final String SOURCE_STORAGE_TOKEN_NAME_EXAMPLE = SOURCE_STORAGE_TOKEN_NAME
                                      + " "
                                      + TIC
                                      + CryptoUtil.INTERNAL_TOKEN_FULL_NAME
                                      + TIC;

    private static final String SOURCE_STORAGE_CERT_NICKNAME = "-source_storage_certificate_nickname";

    private static final String SOURCE_STORAGE_CERT_NICKNAME_DESCRIPTION = "  <nickname of the source "
                                             + "storage certificate>";

    private static final String SOURCE_STORAGE_CERT_NICKNAME_EXAMPLE = SOURCE_STORAGE_CERT_NICKNAME
                                         + " "
                                         + TIC
                                         + "storageCert cert-pki-kra"
                                         + TIC;

    private static final String TARGET_STORAGE_CERTIFICATE_FILE = "-target_storage_certificate_file";

    private static final String TARGET_STORAGE_CERTIFICATE_DESCRIPTION = "  <complete path to the target "
                                           + "storage certificate file"
                                           + NEWLINE
                                           + "        "
                                           + "   ending with the target "
                                           + "storage certificate file name;"
                                           + NEWLINE
                                           + "        "
                                           + "   the target storage "
                                           + "certificate is stored in"
                                           + NEWLINE
                                           + "        "
                                           + "   an ASCII format between a "
                                           + "header and footer>";

    private static final String TARGET_STORAGE_CERTIFICATE_FILE_EXAMPLE = TARGET_STORAGE_CERTIFICATE_FILE
                                            + " "
                                            + "/export/pki/target_storage.cert";

    private static final String SOURCE_NSS_DB_PWDFILE = "-source_pki_security_database_pwdfile";

    private static final String SOURCE_NSS_DB_PWDFILE_DESCRIPTION = "  <complete path to the password "
                                      + "file which ONLY contains the"
                                      + NEWLINE
                                      + "        "
                                      + "   password used to access the "
                                      + "source security databases>";

    private static final String SOURCE_NSS_DB_PWDFILE_EXAMPLE = SOURCE_NSS_DB_PWDFILE
                                  + " "
                                  + "/export/pki/pwdfile";

    // Constants:  Command-line Options (ID Offset)
    private static final String APPEND_ID_OFFSET = "-append_id_offset";

    private static final String APPEND_ID_OFFSET_DESCRIPTION = "  <ID offset that is appended to "
                                 + "each record's source ID>";

    private static final String APPEND_ID_OFFSET_EXAMPLE = APPEND_ID_OFFSET
                             + " "
                             + "100000000000";

    private static final String REMOVE_ID_OFFSET = "-remove_id_offset";

    private static final String REMOVE_ID_OFFSET_DESCRIPTION = "  <ID offset that is removed from "
                                 + "each record's source ID>";

    private static final String REMOVE_ID_OFFSET_EXAMPLE = REMOVE_ID_OFFSET
                             + " "
                             + "100000000000";

    // Constants:  Command-line Options
    private static final String USE_OAEP_RSA_KEY_WRAP = "-use_rsa_oaep_keywrap";
    private static final String SOURCE_KRA_NAMING_CONTEXT = "-source_kra_naming_context";

    private static final String SOURCE_KRA_NAMING_CONTEXT_DESCRIPTION = "  <source KRA naming context>";

    private static final String SOURCE_KRA_NAMING_CONTEXT_EXAMPLE = SOURCE_KRA_NAMING_CONTEXT
                                      + " "
                                      + TIC
                                      + "alpha.example.com-pki-kra"
                                      + TIC;

    private static final String TARGET_KRA_NAMING_CONTEXT = "-target_kra_naming_context";

    private static final String TARGET_KRA_NAMING_CONTEXT_DESCRIPTION = "  <target KRA naming context>";

    private static final String TARGET_KRA_NAMING_CONTEXT_EXAMPLE = TARGET_KRA_NAMING_CONTEXT
                                      + " "
                                      + TIC
                                      + "omega.example.com-pki-kra"
                                      + TIC;

    private static final String PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY =
            "-process_requests_and_key_records_only";

    private static final String KEY_UNWRAP_ALGORITHM = "-unwrap_algorithm";

    private static final String KEY_UNWRAP_ALGORITHM_DESCRIPTION = "  <key unwrap algorithm> (default: DES3)";

    // Constants:  KRATOOL Config File
    private static final String KRATOOL_CFG_PREFIX = "kratool.ldif";
    private static final String KRATOOL_CFG_ENROLLMENT = "caEnrollmentRequest";
    private static final String KRATOOL_CFG_CA_KEY_RECORD = "caKeyRecord";
    private static final String KRATOOL_CFG_RECOVERY = "recoveryRequest";
    private static final String KRATOOL_CFG_TPS_KEY_RECORD = "tpsKeyRecord";
    private static final String KRATOOL_CFG_KEYGEN = "tpsNetkeyKeygenRequest";
    private static final String KRATOOL_CFG_KEYRECOVERY = "tpsNetkeyKeyRecoveryRequest";

    // Constants:  KRATOOL Config File (KRA CA Enrollment Request Fields)
    private static final String KRATOOL_CFG_ENROLLMENT_CN = KRATOOL_CFG_PREFIX
                                  + DOT
                                  + KRATOOL_CFG_ENROLLMENT
                                  + DOT
                                  + "cn";
    private static final String KRATOOL_CFG_ENROLLMENT_DATE_OF_MODIFY = KRATOOL_CFG_PREFIX
                                              + DOT
                                              + KRATOOL_CFG_ENROLLMENT
                                              + DOT
                                              + "dateOfModify";
    private static final String KRATOOL_CFG_ENROLLMENT_DN = KRATOOL_CFG_PREFIX
                                  + DOT
                                  + KRATOOL_CFG_ENROLLMENT
                                  + DOT
                                  + "dn";
    private static final String KRATOOL_CFG_ENROLLMENT_EXTDATA_KEY_RECORD = KRATOOL_CFG_PREFIX
                                                  + DOT
                                                  + KRATOOL_CFG_ENROLLMENT
                                                  + DOT
                                                  + "extdata.keyRecord";
    private static final String KRATOOL_CFG_ENROLLMENT_EXTDATA_REQUEST_NOTES = KRATOOL_CFG_PREFIX
                                                     + DOT
                                                     + KRATOOL_CFG_ENROLLMENT
                                                     + DOT
                                                     + "extdata.requestNotes";
    private static final String KRATOOL_CFG_ENROLLMENT_REQUEST_ID = KRATOOL_CFG_PREFIX
                                          + DOT
                                          + KRATOOL_CFG_ENROLLMENT
                                          + DOT
                                          + "requestId";

    // Constants:  KRATOOL Config File (KRA CA Key Record Fields)
    private static final String KRATOOL_CFG_CA_KEY_RECORD_CN = KRATOOL_CFG_PREFIX
                                     + DOT
                                     + KRATOOL_CFG_CA_KEY_RECORD
                                     + DOT
                                     + "cn";
    private static final String KRATOOL_CFG_CA_KEY_RECORD_DATE_OF_MODIFY = KRATOOL_CFG_PREFIX
                                                 + DOT
                                                 + KRATOOL_CFG_CA_KEY_RECORD
                                                 + DOT
                                                 + "dateOfModify";
    private static final String KRATOOL_CFG_CA_KEY_RECORD_DN = KRATOOL_CFG_PREFIX
                                     + DOT
                                     + KRATOOL_CFG_ENROLLMENT
                                     + DOT
                                     + "dn";
    private static final String KRATOOL_CFG_CA_KEY_RECORD_PRIVATE_KEY_DATA = KRATOOL_CFG_PREFIX
                                                   + DOT
                                                   + KRATOOL_CFG_CA_KEY_RECORD
                                                   + DOT
                                                   + "privateKeyData";
    private static final String KRATOOL_CFG_CA_KEY_RECORD_SERIAL_NO = KRATOOL_CFG_PREFIX
                                            + DOT
                                            + KRATOOL_CFG_CA_KEY_RECORD
                                            + DOT
                                            + "serialno";

    // Constants:  KRATOOL Config File (KRA CA / TPS Recovery Request Fields)
    private static final String KRATOOL_CFG_RECOVERY_CN = KRATOOL_CFG_PREFIX
                                + DOT
                                + KRATOOL_CFG_RECOVERY
                                + DOT
                                + "cn";
    private static final String KRATOOL_CFG_RECOVERY_DATE_OF_MODIFY = KRATOOL_CFG_PREFIX
                                            + DOT
                                            + KRATOOL_CFG_RECOVERY
                                            + DOT
                                            + "dateOfModify";
    private static final String KRATOOL_CFG_RECOVERY_DN = KRATOOL_CFG_PREFIX
                                + DOT
                                + KRATOOL_CFG_RECOVERY
                                + DOT
                                + "dn";
    private static final String KRATOOL_CFG_RECOVERY_EXTDATA_REQUEST_ID = KRATOOL_CFG_PREFIX
                                                + DOT
                                                + KRATOOL_CFG_RECOVERY
                                                + DOT
                                                + "extdata.requestId";
    private static final String KRATOOL_CFG_RECOVERY_EXTDATA_REQUEST_NOTES = KRATOOL_CFG_PREFIX
                                                   + DOT
                                                   + KRATOOL_CFG_RECOVERY
                                                   + DOT
                                                   + "extdata.requestNotes";
    private static final String KRATOOL_CFG_RECOVERY_EXTDATA_SERIAL_NUMBER = KRATOOL_CFG_PREFIX
                                                   + DOT
                                                   + KRATOOL_CFG_RECOVERY
                                                   + DOT
                                                   + "extdata.serialnumber";
    private static final String KRATOOL_CFG_RECOVERY_REQUEST_ID = KRATOOL_CFG_PREFIX
                                        + DOT
                                        + KRATOOL_CFG_RECOVERY
                                        + DOT
                                        + "requestId";

    // Constants:  KRATOOL Config File (KRA TPS Key Record Fields)
    private static final String KRATOOL_CFG_TPS_KEY_RECORD_CN = KRATOOL_CFG_PREFIX
                                      + DOT
                                      + KRATOOL_CFG_TPS_KEY_RECORD
                                      + DOT
                                      + "cn";
    private static final String KRATOOL_CFG_TPS_KEY_RECORD_DATE_OF_MODIFY = KRATOOL_CFG_PREFIX
                                                  + DOT
                                                  + KRATOOL_CFG_TPS_KEY_RECORD
                                                  + DOT
                                                  + "dateOfModify";
    private static final String KRATOOL_CFG_TPS_KEY_RECORD_DN = KRATOOL_CFG_PREFIX
                                      + DOT
                                      + KRATOOL_CFG_TPS_KEY_RECORD
                                      + DOT
                                      + "dn";
    private static final String KRATOOL_CFG_TPS_KEY_RECORD_PRIVATE_KEY_DATA = KRATOOL_CFG_PREFIX
                                                    + DOT
                                                    + KRATOOL_CFG_TPS_KEY_RECORD
                                                    + DOT
                                                    + "privateKeyData";
    private static final String KRATOOL_CFG_TPS_KEY_RECORD_SERIAL_NO = KRATOOL_CFG_PREFIX
                                             + DOT
                                             + KRATOOL_CFG_TPS_KEY_RECORD
                                             + DOT
                                             + "serialno";

    // Constants:  KRATOOL Config File (KRA TPS Netkey Keygen Request Fields)
    private static final String KRATOOL_CFG_KEYGEN_CN = KRATOOL_CFG_PREFIX
                              + DOT
                              + KRATOOL_CFG_KEYGEN
                              + DOT
                              + "cn";
    private static final String KRATOOL_CFG_KEYGEN_DATE_OF_MODIFY = KRATOOL_CFG_PREFIX
                                          + DOT
                                          + KRATOOL_CFG_KEYGEN
                                          + DOT
                                          + "dateOfModify";
    private static final String KRATOOL_CFG_KEYGEN_DN = KRATOOL_CFG_PREFIX
                              + DOT
                              + KRATOOL_CFG_KEYGEN
                              + DOT
                              + "dn";
    private static final String KRATOOL_CFG_KEYGEN_EXTDATA_KEY_RECORD = KRATOOL_CFG_PREFIX
                                              + DOT
                                              + KRATOOL_CFG_KEYGEN
                                              + DOT
                                              + "extdata.keyRecord";
    private static final String KRATOOL_CFG_KEYGEN_EXTDATA_REQUEST_ID = KRATOOL_CFG_PREFIX
                                              + DOT
                                              + KRATOOL_CFG_KEYGEN
                                              + DOT
                                              + "extdata.requestId";
    private static final String KRATOOL_CFG_KEYGEN_EXTDATA_REQUEST_NOTES = KRATOOL_CFG_PREFIX
                                                 + DOT
                                                 + KRATOOL_CFG_KEYGEN
                                                 + DOT
                                                 + "extdata.requestNotes";
    private static final String KRATOOL_CFG_KEYGEN_REQUEST_ID = KRATOOL_CFG_PREFIX
                                      + DOT
                                      + KRATOOL_CFG_KEYGEN
                                      + DOT
                                      + "requestId";

    private static final String KRATOOL_CFG_KEYRECOVERY_REQUEST_ID = KRATOOL_CFG_PREFIX
            + DOT
            + KRATOOL_CFG_KEYRECOVERY
            + DOT
            + "requestId";

    private static final String KRATOOL_CFG_KEYRECOVERY_DN = KRATOOL_CFG_PREFIX
            + DOT
            + KRATOOL_CFG_KEYRECOVERY
            + DOT
            + "dn";

    private static final String KRATOOL_CFG_KEYRECOVERY_DATE_OF_MODIFY = KRATOOL_CFG_PREFIX
            + DOT
            + KRATOOL_CFG_KEYRECOVERY
            + DOT
            + "dateOfModify";

    private static final String KRATOOL_CFG_KEYRECOVERY_EXTDATA_REQUEST_ID = KRATOOL_CFG_PREFIX
            + DOT
            + KRATOOL_CFG_KEYRECOVERY
            + DOT
            + "extdata.requestId";

    private static final String KRATOOL_CFG_KEYRECOVERY_CN = KRATOOL_CFG_PREFIX
            + DOT
            + KRATOOL_CFG_KEYRECOVERY
            + DOT
            + "cn";

    private static final String KRATOOL_CFG_KEYRECOVERY_EXTDATA_REQUEST_NOTES = KRATOOL_CFG_PREFIX
            + DOT
            + KRATOOL_CFG_KEYRECOVERY
            + DOT
            + "extdata.requestNotes";



    // Constants:  Target Certificate Information
    private static final String HEADER = "-----BEGIN";
    private static final String TRAILER = "-----END";

    // Constants:  KRA LDIF Record Fields
    private static final String KRA_LDIF_ARCHIVED_BY = "archivedBy:";
    private static final String KRA_LDIF_CN = "cn:";
    private static final String KRA_LDIF_DATE_OF_MODIFY = "dateOfModify:";
    private static final String KRA_LDIF_DN = "dn:";
    private static final String KRA_LDIF_DN_EMBEDDED_CN_DATA = "dn: cn";
    private static final String KRA_LDIF_EXTDATA_AUTH_TOKEN_USER = "extdata-auth--005ftoken;user:";
    private static final String KRA_LDIF_EXTDATA_AUTH_TOKEN_USER_DN = "extdata-auth--005ftoken;userdn:";
    private static final String KRA_LDIF_EXTDATA_KEY_RECORD = "extdata-keyrecord:";
    private static final String KRA_LDIF_EXTDATA_REQUEST_ID = "extdata-requestid:";
    private static final String KRA_LDIF_EXTDATA_REQUEST_NOTES = "extdata-requestnotes:";
    private static final String KRA_LDIF_EXTDATA_REQUEST_TYPE = "extdata-requesttype:";
    private static final String KRA_LDIF_EXTDATA_SERIAL_NUMBER = "extdata-serialnumber:";
    private static final String KRA_LDIF_PRIVATE_KEY_DATA = "privateKeyData::";
    private static final String KRA_LDIF_REQUEST_ID = "requestId:";
    private static final String KRA_LDIF_REQUEST_TYPE = "requestType:";
    private static final String KRA_LDIF_SERIAL_NO = "serialno:";

    // Constants:  KRA LDIF Record Values
    private static final int INITIAL_LDIF_RECORD_CAPACITY = 0;
    private static final int EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH = 56;
    private static final int PRIVATE_KEY_DATA_FIRST_LINE_DATA_LENGTH = 60;
    private static final String KRA_LDIF_RECORD = "Generic";
    private static final String KRA_LDIF_CA_KEY_RECORD = "CA";
    private static final String KRA_LDIF_ENROLLMENT = "enrollment";
    private static final String KRA_LDIF_KEYGEN = "netkeyKeygen";
    private static final String KRA_LDIF_RECOVERY = "recovery";
    private static final String KRA_LDIF_TPS_KEY_RECORD = "TPS";
    private static final String KRA_LDIF_KEYRECOVERY = "netkeyKeyRecovery";

    // Constants:  KRA LDIF Record Messages
    private static final String KRA_LDIF_REWRAP_MESSAGE = "REWRAPPED the '"
                                                         + "existing "
                                                         + "symmetric "
                                                         + "session key"
                                                         + "' with the '";
    private static final String KRA_LDIF_RSA_MESSAGE = "-bit RSA public key' "
                                                     + "obtained from the "
                                                     + "target storage "
                                                     + "certificate";
    private static final String KRA_LDIF_USED_PWDFILE_MESSAGE =
                                    "USED source PKI security database "
                                            + "password file";
    private static final String KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE =
                                    "APPENDED ID offset";
    private static final String KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE =
                                    "REMOVED ID offset";
    private static final String KRA_LDIF_SOURCE_NAME_CONTEXT_MESSAGE =
                                    "RENAMED source KRA naming context '";
    private static final String KRA_LDIF_TARGET_NAME_CONTEXT_MESSAGE =
                                    "' to target KRA naming context '";
    private static final String KRA_LDIF_PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY_MESSAGE =
            "PROCESSED requests and key records ONLY";

    /*************/
    /* Variables */
    /*************/

    // Variables:  Calendar
    private static String mDateOfModify = null;

    // Variables: Command-Line Options
    private static boolean mRewrapFlag = false;
    private static boolean mPwdfileFlag = false;
    private static boolean mAppendIdOffsetFlag = false;
    private static boolean mRemoveIdOffsetFlag = false;
    private static boolean mKraNamingContextsFlag = false;
    private static boolean mProcessRequestsAndKeyRecordsOnlyFlag = false;
    private static boolean mUseOAEPKeyWrapAlg = false;
    private static int mMandatoryNameValuePairs = 0;
    private static int mRewrapNameValuePairs = 0;
    private static int mPKISecurityDatabasePwdfileNameValuePairs = 0;
    private static int mAppendIdOffsetNameValuePairs = 0;
    private static int mRemoveIdOffsetNameValuePairs = 0;
    private static int mKraNamingContextNameValuePairs = 0;

    // Variables: Command-Line Values (Mandatory)
    private static String mKratoolCfgFilename = null;
    private static String mSourceLdifFilename = null;
    private static String mTargetLdifFilename = null;
    private static String mLogFilename = null;

    // Variables: Command-Line Values (Rewrap)
    private static String mSourcePKISecurityDatabasePath = null;
    private static String mSourceStorageTokenName = null;
    private static String mSourceStorageCertNickname = null;
    private static String mTargetStorageCertificateFilename = null;

    // Variables: Command-Line Values (Rewrap Password File)
    private static String mSourcePKISecurityDatabasePwdfile = null;

    // Variables: Command-Line Values (ID Offset)
    private static BigInteger mAppendIdOffset = null;
    private static BigInteger mRemoveIdOffset = null;

    // Variables: Command-Line Values (KRA Naming Contexts)
    private static String mSourceKraNamingContext = null;
    private static String mTargetKraNamingContext = null;

    // Variables:  KRATOOL Config File Parameters of Interest
    private static Hashtable<String, Boolean> kratoolCfg = null;

    // Variables:  KRATOOL LDIF File Parameters of Interest
    private static Vector<String> record = null;
    private static Iterator<String> ldif_record = null;

    // Variables:  Logging
    private static boolean mDebug = false; // set 'true' for debug messages

    // Variables:  PKCS #11 Information
    private static CryptoToken mSourceToken = null;
    private static X509Certificate mUnwrapCert = null;
    private static PrivateKey mUnwrapPrivateKey = null;
    private static PublicKey mWrapPublicKey = null;
    private static int mPublicKeySize = 0;
    private static SymmetricKey.Type keyUnwrapAlgorithm = SymmetricKey.DES3;

    // Variables:  KRA LDIF Record Messages
    private static String mSourcePKISecurityDatabasePwdfileMessage = null;
    private static String mKraNamingContextMessage = null;
    private static String mProcessRequestsAndKeyRecordsOnlyMessage = null;

    /********************/
    /* Calendar Methods */
    /********************/

    /**
     * This method is used to get the current date and time.
     * <P>
     *
     * @param pattern string containing desired format of date and time
     * @return a formatted string containing the current date and time
     */
    private static String now(String pattern) {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        return sdf.format(cal.getTime());
    }

    /*****************/
    /* Usage Methods */
    /*****************/

    /**
     * This method prints out the proper command-line usage required to
     * execute KRATool.
     */
    private static void printUsage() {
        System.out.println("Usage:  "
                          + KRA_TOOL
                          + NEWLINE
                          + "        "
                          + KRATOOL_CFG_FILE
                          + NEWLINE
                          + "        "
                          + KRATOOL_CFG_DESCRIPTION
                          + NEWLINE
                          + "        "
                          + SOURCE_LDIF_FILE
                          + NEWLINE
                          + "        "
                          + SOURCE_LDIF_DESCRIPTION
                          + NEWLINE
                          + "        "
                          + TARGET_LDIF_FILE
                          + NEWLINE
                          + "        "
                          + TARGET_LDIF_DESCRIPTION
                          + NEWLINE
                          + "        "
                          + LOG_FILE
                          + NEWLINE
                          + "        "
                          + LOG_DESCRIPTION
                          + NEWLINE
                          + "        "
                          + "["
                          + SOURCE_NSS_DB_PATH
                          + NEWLINE
                          + "        "
                          + SOURCE_NSS_DB_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + SOURCE_STORAGE_TOKEN_NAME
                          + NEWLINE
                          + "        "
                          + SOURCE_STORAGE_TOKEN_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + SOURCE_STORAGE_CERT_NICKNAME
                          + NEWLINE
                          + "        "
                          + SOURCE_STORAGE_CERT_NICKNAME_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + TARGET_STORAGE_CERTIFICATE_FILE
                          + NEWLINE
                          + "        "
                          + TARGET_STORAGE_CERTIFICATE_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + SOURCE_NSS_DB_PWDFILE
                          + NEWLINE
                          + "        "
                          + SOURCE_NSS_DB_PWDFILE_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + APPEND_ID_OFFSET
                          + NEWLINE
                          + "        "
                          + APPEND_ID_OFFSET_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + REMOVE_ID_OFFSET
                          + NEWLINE
                          + "        "
                          + REMOVE_ID_OFFSET_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + SOURCE_KRA_NAMING_CONTEXT
                          + NEWLINE
                          + "        "
                          + SOURCE_KRA_NAMING_CONTEXT_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + TARGET_KRA_NAMING_CONTEXT
                          + NEWLINE
                          + "        "
                          + TARGET_KRA_NAMING_CONTEXT_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + KEY_UNWRAP_ALGORITHM
                          + NEWLINE
                          + "        "
                          + KEY_UNWRAP_ALGORITHM_DESCRIPTION
                          + "]"
                          + NEWLINE
                          + "        "
                          + "["
                          + PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY
                          + "]"
                          + NEWLINE);

        System.out.println("Example of 'Rewrap and Append ID Offset':"
                          + NEWLINE
                          + NEWLINE
                          + "        "
                          + KRA_TOOL
                          + NEWLINE
                          + "        "
                          + KRATOOL_CFG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + LOG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_NSS_DB_PATH_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_STORAGE_TOKEN_NAME_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_STORAGE_CERT_NICKNAME_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_STORAGE_CERTIFICATE_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_NSS_DB_PWDFILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + APPEND_ID_OFFSET_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY
                          + NEWLINE);

        System.out.println("Example of 'Rewrap and Remove ID Offset':"
                          + NEWLINE
                          + NEWLINE
                          + "        "
                          + KRA_TOOL
                          + NEWLINE
                          + "        "
                          + KRATOOL_CFG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + LOG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_NSS_DB_PATH_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_STORAGE_TOKEN_NAME_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_STORAGE_CERT_NICKNAME_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_STORAGE_CERTIFICATE_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_NSS_DB_PWDFILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + REMOVE_ID_OFFSET_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY
                          + NEWLINE);

        System.out.println("Example of 'Rewrap':"
                          + NEWLINE
                          + NEWLINE
                          + "        "
                          + KRA_TOOL
                          + NEWLINE
                          + "        "
                          + KRATOOL_CFG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + LOG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_NSS_DB_PATH_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_STORAGE_TOKEN_NAME_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_STORAGE_CERT_NICKNAME_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_STORAGE_CERTIFICATE_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_NSS_DB_PWDFILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY
                          + NEWLINE);

        System.out.println("Example of 'Append ID Offset':"
                          + NEWLINE
                          + NEWLINE
                          + "        "
                          + KRA_TOOL
                          + NEWLINE
                          + "        "
                          + KRATOOL_CFG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + LOG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + APPEND_ID_OFFSET_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY
                          + NEWLINE);

        System.out.println("Example of 'Remove ID Offset':"
                          + NEWLINE
                          + NEWLINE
                          + "        "
                          + KRA_TOOL
                          + NEWLINE
                          + "        "
                          + KRATOOL_CFG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_LDIF_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + LOG_FILE_EXAMPLE
                          + NEWLINE
                          + "        "
                          + REMOVE_ID_OFFSET_EXAMPLE
                          + NEWLINE
                          + "        "
                          + SOURCE_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + TARGET_KRA_NAMING_CONTEXT_EXAMPLE
                          + NEWLINE
                          + "        "
                          + PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY
                          + NEWLINE);
    }

    /*******************/
    /* Logging Methods */
    /*******************/

    /**
     * Configure logger.
     *
     * @param logfile string containing the name of the log file to be opened
     */
    private static void configureLogger(String logfile) throws IOException {

        PKILogger.setLevel(LogLevel.INFO);

        // log everything to file
        Handler handler = new FileHandler(logfile);
        handler.setLevel(Level.ALL);
        handler.setFormatter(new SimpleFormatter());

        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(handler);
    }

    /*********************************************/
    /* PKCS #11:  Rewrap RSA Storage Key Methods */
    /*********************************************/

    /**
     * Helper method to determine if two arrays contain the same values.
     *
     * This method is based upon code from 'com.netscape.kra.StorageKeyUnit'.
     * <P>
     *
     * @param bytes first array of bytes
     * @param ints second array of bytes
     * @return true if the two arrays are identical
     */
    private static boolean arraysEqual(byte[] bytes, byte[] ints) {
        if (bytes == null || ints == null) {
            return false;
        }

        if (bytes.length != ints.length) {
            return false;
        }

        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != ints[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * This method is used to obtain the private RSA storage key from
     * the "source" KRA instance's security databases.
     *
     * This method is based upon code from 'com.netscape.kra.StorageKeyUnit'.
     * <P>
     *
     * @return the private RSA storage key from the "source" KRA
     */
    private static PrivateKey getPrivateKey() {
        try {
            PrivateKey pk[] = mSourceToken.getCryptoStore().getPrivateKeys();

            for (int i = 0; i < pk.length; i++) {
                if (arraysEqual(pk[i].getUniqueID(),
                                  ((PK11Cert)
                                    mUnwrapCert).getUniqueID())) {
                    return pk[i];
                }
            }
        } catch (TokenException exToken) {
            logger.error("Unable to get private key: "
                    + exToken.getMessage(),
                    exToken);
            System.exit(0);
        }

        return null;
    }

    /**
     * This method gets the public key from the certificate stored
     * in the "target" KRA storage certificate file. It also obtains
     * the keysize of this RSA key.
     *
     * This method is based upon code from
     * 'com.netscape.cmstools.PrettyPrintCert'.
     * <P>
     *
     * @return the public RSA storage key from the "target" KRA
     */
    private static PublicKey getPublicKey() {
        BufferedReader inputCert = null;
        String encodedBASE64CertChunk;
        StringBuffer encodedBASE64Cert = new StringBuffer();
        byte decodedBASE64Cert[] = null;
        X509CertImpl cert = null;
        PublicKey key = null;
        RSAPublicKey rsakey = null;

        // Create a DataInputStream() object to the BASE 64
        // encoded certificate contained within the file
        // specified on the command line
        try {
            inputCert = new BufferedReader(
                            new InputStreamReader(
                                    new BufferedInputStream(
                                            new FileInputStream(
                                                    mTargetStorageCertificateFilename
                                            ))));
        } catch (FileNotFoundException exWrapFileNotFound) {
            logger.error("No target storage "
                    + "certificate file named '"
                    + mTargetStorageCertificateFilename
                    + "' exists: "
                    + exWrapFileNotFound.getMessage(),
                    exWrapFileNotFound);
            System.exit(0);
        }

        // Read the entire contents of the specified BASE 64 encoded
        // certificate into a String() object throwing away any
        // headers beginning with HEADER and any trailers beginning
        // with TRAILER
        try {
            while ((encodedBASE64CertChunk = inputCert.readLine()) != null) {
                if (!(encodedBASE64CertChunk.startsWith(HEADER)) &&
                        !(encodedBASE64CertChunk.startsWith(TRAILER))) {
                    encodedBASE64Cert.append(encodedBASE64CertChunk.trim());
                }
            }
        } catch (IOException exWrapReadLineIO) {
            logger.error("Unexpected BASE64 "
                    + "encoded error encountered while reading '"
                    + mTargetStorageCertificateFilename
                    + "': "
                    + exWrapReadLineIO.getMessage(),
                    exWrapReadLineIO);
            System.exit(0);
        }

        // Close the DataInputStream() object
        try {
            inputCert.close();
        } catch (IOException exWrapCloseIO) {
            logger.error("Unexpected BASE64 "
                    + "encoded error encountered in closing '"
                    + mTargetStorageCertificateFilename
                    + "': "
                    + exWrapCloseIO.getMessage(),
                    exWrapCloseIO);
            System.exit(0);
        }

        // Decode the ASCII BASE 64 certificate enclosed in the
        // String() object into a BINARY BASE 64 byte[] object
        decodedBASE64Cert = Utils.base64decode(
                                encodedBASE64Cert.toString());

        // Create an X509CertImpl() object from
        // the BINARY BASE 64 byte[] object
        try {
            cert = new X509CertImpl(decodedBASE64Cert);
        } catch (CertificateException exWrapCertificate) {
            logger.error("Error encountered "
                    + "in parsing certificate in '"
                    + mTargetStorageCertificateFilename
                    + "': "
                    + exWrapCertificate.getMessage(),
                    exWrapCertificate);
            System.exit(0);
        }

        // Extract the Public Key
        key = cert.getPublicKey();
        if (key == null) {
            logger.error("Unable to extract public key "
                    + "from certificate that was stored in "
                    + mTargetStorageCertificateFilename);
            System.exit(0);
        }

        // Convert this X.509 public key --> RSA public key
        try {
            rsakey = new RSAPublicKey(key.getEncoded());
        } catch (InvalidKeyException exInvalidKey) {
            logger.error("Converting X.509 public key --> RSA public key: "
                    + exInvalidKey.getMessage(),
                    exInvalidKey);
            System.exit(0);
        }

        // Obtain the Public Key's keysize
        mPublicKeySize = rsakey.getKeySize();

        return key;
    }

    /**
     * This method is used to obtain the private RSA storage key
     * from the "source" KRA instance's security databases and
     * the public RSA storage key from the certificate stored in
     * the "target" KRA storage certificate file.
     * <P>
     *
     * @return true if successfully able to obtain both keys
     */
    private static boolean obtain_RSA_rewrapping_keys() {
        CryptoManager cm = null;

        // Initialize the source security databases
        try {
            logger.info("Initializing source PKI security databases in "
                    + mSourcePKISecurityDatabasePath);

            CryptoManager.initialize(mSourcePKISecurityDatabasePath);
        } catch (KeyDatabaseException exKey) {
            logger.error("source_pki_security_database_path='"
                    + mSourcePKISecurityDatabasePath
                    + "': "
                    + exKey.getMessage(),
                    exKey);
            System.exit(0);
        } catch (CertDatabaseException exCert) {
            logger.error("source_pki_security_database_path='"
                    + mSourcePKISecurityDatabasePath
                    + "': "
                    + exCert.getMessage(),
                    exCert);
            System.exit(0);
        } catch (AlreadyInitializedException exAlreadyInitialized) {
            logger.error("source_pki_security_database_path='"
                    + mSourcePKISecurityDatabasePath
                    + "': "
                    + exAlreadyInitialized.getMessage(),
                    exAlreadyInitialized);
            System.exit(0);
        } catch (GeneralSecurityException exSecurity) {
            logger.error("source_pki_security_database_path='"
                    + mSourcePKISecurityDatabasePath
                    + "': "
                    + exSecurity.getMessage(),
                    exSecurity);
            System.exit(0);
        }

        // Retrieve the source storage token by its name
        try {
            logger.info("Retrieving token from CryptoManager");
            cm = CryptoManager.getInstance();

            logger.info("Retrieving source storage token called " + mSourceStorageTokenName);

            mSourceToken = CryptoUtil.getKeyStorageToken(mSourceStorageTokenName);

            if (mSourceToken == null) {
                return FAILURE;
            }

            if (mPwdfileFlag) {
                BufferedReader in = null;
                String pwd = null;

                try {
                    in = new BufferedReader(
                             new FileReader(
                                     mSourcePKISecurityDatabasePwdfile));
                    pwd = in.readLine();
                    if (pwd == null) {
                        pwd = "";
                    }

                    Password mPwd = new Password(pwd.toCharArray());
                    try {
                        mSourceToken.login(mPwd);
                    } finally {
                        mPwd.clear();
                    }
                } catch (Exception exReadPwd) {
                    logger.error("Failed to read the keydb password from "
                            + "the file '"
                            + mSourcePKISecurityDatabasePwdfile
                            + "': "
                            + exReadPwd.getMessage(),
                            exReadPwd);
                    System.exit(0);
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (Exception exUninitialized) {
            logger.error("Uninitialized CryptoManager: "
                    + exUninitialized.getMessage(),
                    exUninitialized);
            System.exit(0);
        }

        // Retrieve the source storage cert by its nickname
        try {
            if (mSourceStorageTokenName.equals(CryptoUtil.INTERNAL_TOKEN_FULL_NAME)) {
                logger.info("Retrieving source storage cert with nickname of "
                        + mSourceStorageCertNickname);

                mUnwrapCert = cm.findCertByNickname(mSourceStorageCertNickname);
            } else {
                logger.info("Retrieving source storage cert with nickname of "
                        + mSourceStorageTokenName
                        + ":"
                        + mSourceStorageCertNickname);
                mUnwrapCert = cm.findCertByNickname(mSourceStorageTokenName
                                                   + ":"
                                                   + mSourceStorageCertNickname
                                                   );
            }

            if (mUnwrapCert == null) {
                return FAILURE;
            }
        } catch (ObjectNotFoundException exUnwrapObjectNotFound) {
            if (mSourceStorageTokenName.equals(CryptoUtil.INTERNAL_TOKEN_FULL_NAME)) {
                logger.error("No internal "
                        + "source storage cert named '"
                        + mSourceStorageCertNickname
                        + "' exists: "
                        + exUnwrapObjectNotFound.getMessage(),
                        exUnwrapObjectNotFound);
            } else {
                logger.error("No "
                        + "source storage cert named '"
                        + mSourceStorageTokenName
                        + ":"
                        + mSourceStorageCertNickname
                        + "' exists: "
                        + exUnwrapObjectNotFound.getMessage(),
                        exUnwrapObjectNotFound);
            }
            System.exit(0);
        } catch (TokenException exUnwrapToken) {
            if (mSourceStorageTokenName.equals(CryptoUtil.INTERNAL_TOKEN_FULL_NAME)) {
                logger.error("No internal "
                        + "source storage cert named '"
                        + mSourceStorageCertNickname
                        + "' exists: "
                        + exUnwrapToken.getMessage(),
                        exUnwrapToken);
            } else {
                logger.error("No "
                        + "source storage cert named '"
                        + mSourceStorageTokenName
                        + ":"
                        + mSourceStorageCertNickname
                        + "' exists: "
                        + exUnwrapToken.getMessage(),
                        exUnwrapToken);
            }
            System.exit(0);
        }

        // Extract the private key from the source storage token
        logger.info("BEGIN: Obtaining the private key from the source storage token");

        mUnwrapPrivateKey = getPrivateKey();

        if (mUnwrapPrivateKey == null) {
            logger.error("Failed extracting private key from the source storage token");
            System.exit(0);
        }

        logger.info("FINISHED: Obtaining the private key from the source storage token");

        // Extract the public key from the target storage certificate
        try {
            logger.info("BEGIN: Obtaining the public key from the target storage certificate");

            mWrapPublicKey = PK11PubKey.fromSPKI(
                     getPublicKey().getEncoded());

            if (mWrapPublicKey == null) {
                logger.error("Failed extracting "
                        + "public key from target storage certificate stored in "
                        + mTargetStorageCertificateFilename);
                System.exit(0);
            }

            logger.info("FINISHED: Obtaining the public key from the target storage certificate");
        } catch (InvalidKeyFormatException exInvalidPublicKey) {
            logger.error("Failed extracting "
                    + "public key from target storage certificate stored in '"
                    + mTargetStorageCertificateFilename
                    + "': "
                    + exInvalidPublicKey.getMessage(),
                    exInvalidPublicKey);
            System.exit(0);
        }

        return SUCCESS;
    }

    /**
     * This method basically rewraps the "wrappedKeyData" by implementiing
     * "mStorageUnit.decryptInternalPrivate( byte wrappedKeyData[] )" and
     * "mStorageUnit.encryptInternalPrivate( byte priKey[] )", where
     * "wrappedKeyData" uses the following structure:
     *
     * SEQUENCE {
     * encryptedSession OCTET STRING,
     * encryptedPrivate OCTET STRING
     * }
     *
     * This method is based upon code from
     * 'com.netscape.kra.EncryptionUnit'.
     * <P>
     *
     * @return a byte[] containing the rewrappedKeyData
     */
    private static byte[] rewrap_wrapped_key_data(byte[] wrappedKeyData)
            throws Exception {
        DerValue val = null;
        DerInputStream in = null;
        DerValue dSession = null;
        byte source_session[] = null;
        DerValue dPri = null;
        byte pri[] = null;
        KeyWrapper source_rsaWrap = null;
        SymmetricKey sk = null;
        KeyWrapper target_rsaWrap = null;
        byte target_session[] = null;
        DerOutputStream tmp = null;
        byte[] rewrappedKeyData = null;

        // public byte[]
        // mStorageUnit.decryptInternalPrivate( byte wrappedKeyData[] );
        // throws EBaseException
        try {
            val = new DerValue(wrappedKeyData);
            in = val.data;
            dSession = in.getDerValue();
            source_session = dSession.getOctetString();
            dPri = in.getDerValue();
            pri = dPri.getOctetString();

            KeyWrapAlgorithm wrapAlg = KeyWrapAlgorithm.RSA;

            if(mUseOAEPKeyWrapAlg == true) {
                wrapAlg = KeyWrapAlgorithm.RSA_OAEP;
            }

            source_rsaWrap = mSourceToken.getKeyWrapper(
                                 wrapAlg);
            OAEPParameterSpec config =  null;
            if(mUseOAEPKeyWrapAlg == true) {
                config = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            }
            source_rsaWrap.initUnwrap(mUnwrapPrivateKey, config);
            sk = source_rsaWrap.unwrapSymmetric(source_session,
                                                 keyUnwrapAlgorithm,
                                                 SymmetricKey.Usage.DECRYPT,
                                                 0);
            if (mDebug) {
                logger.debug("sk = '"
                        + Utils.base64encode(sk.getEncoded(), true)
                        + "' length = '"
                        + sk.getEncoded().length
                        + "'");
                logger.debug("pri = '"
                        + Utils.base64encode(pri, true)
                        + "' length = '"
                        + pri.length
                        + "'");
            }
        } catch (IOException exUnwrapIO) {
            logger.error("Unwrapping key data: "
                    + exUnwrapIO.getMessage(),
                    exUnwrapIO);
            System.exit(0);
        } catch (NoSuchAlgorithmException exUnwrapAlgorithm) {
            logger.error("Unwrapping key data: "
                    + exUnwrapAlgorithm.getMessage(),
                    exUnwrapAlgorithm);
            System.exit(0);
        } catch (TokenException exUnwrapToken) {
            logger.error("Unwrapping key data: "
                    + exUnwrapToken.getMessage(),
                    exUnwrapToken);
            System.exit(0);
        } catch (InvalidKeyException exUnwrapInvalidKey) {
            logger.error("Unwrapping key data: "
                    + exUnwrapInvalidKey.getMessage(),
                    exUnwrapInvalidKey);
            System.exit(0);
        } catch (InvalidAlgorithmParameterException exUnwrapInvalidAlgorithm) {
            logger.error("Unwrapping key data: "
                    + exUnwrapInvalidAlgorithm.getMessage(),
                    exUnwrapInvalidAlgorithm);
            System.exit(0);
        } catch (IllegalStateException exUnwrapState) {
            logger.error("Unwrapping key data: "
                    + exUnwrapState.getMessage(),
                    exUnwrapState);
            System.exit(0);
        }

        // public byte[]
        // mStorageUnit.encryptInternalPrivate( byte priKey[] )
        // throws EBaseException
        KeyWrapAlgorithm wrapAlg = KeyWrapAlgorithm.RSA;
        if(mUseOAEPKeyWrapAlg == true) {
            wrapAlg = KeyWrapAlgorithm.RSA_OAEP;
        }
        try (DerOutputStream out = new DerOutputStream()) {
            // Use "mSourceToken" to get "KeyWrapAlgorithm.RSA"
            target_rsaWrap = mSourceToken.getKeyWrapper(
                                 wrapAlg);
            OAEPParameterSpec config = null;
            if(mUseOAEPKeyWrapAlg == true) {
                config = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            }
            target_rsaWrap.initWrap(mWrapPublicKey, config);
            target_session = target_rsaWrap.wrap(sk);

            tmp = new DerOutputStream();

            tmp.putOctetString(target_session);
            tmp.putOctetString(pri);
            out.write(DerValue.tag_Sequence, tmp);

            rewrappedKeyData = out.toByteArray();
        } catch (NoSuchAlgorithmException exWrapAlgorithm) {
            logger.error("Wrapping key data: "
                    + exWrapAlgorithm.getMessage(),
                    exWrapAlgorithm);
            System.exit(0);
        } catch (TokenException exWrapToken) {
            logger.error("Wrapping key data: "
                    + exWrapToken.getMessage(),
                    exWrapToken);
            System.exit(0);
        } catch (InvalidKeyException exWrapInvalidKey) {
            logger.error("Wrapping key data: "
                    + exWrapInvalidKey.getMessage(),
                    exWrapInvalidKey);
            System.exit(0);
        } catch (InvalidAlgorithmParameterException exWrapInvalidAlgorithm) {
            logger.error("Wrapping key data: "
                    + exWrapInvalidAlgorithm.getMessage(),
                    exWrapInvalidAlgorithm);
            System.exit(0);
        } catch (IllegalStateException exWrapState) {
            logger.error("Wrapping key data: "
                    + exWrapState.getMessage(),
                    exWrapState);
            System.exit(0);
        } catch (IOException exWrapIO) {
            logger.error("Wrapping key data: "
                    + exWrapIO.getMessage(),
                    exWrapIO);
            System.exit(0);
        }

        return rewrappedKeyData;
    }

    /**
     * Helper method used to remove all EOLs ('\n' and '\r')
     * from the passed in string.
     * <P>
     *
     * @param data consisting of a string containing EOLs
     * @return a string consisting of a string with no EOLs
     */
    private static String stripEOL(String data) {
        StringBuffer buffer = new StringBuffer();
        String revised_data = null;

        for (int i = 0; i < data.length(); i++) {
            if ((data.charAt(i) != '\n') &&
                    (data.charAt(i) != '\r')) {
                buffer.append(data.charAt(i));
            }
        }

        revised_data = buffer.toString();

        return revised_data;
    }

    /**
     * Helper method used to format a string containing unformatted data
     * into a string containing formatted data suitable as an entry for
     * an LDIF file.
     * <P>
     *
     * @param length the length of the first line of data
     * @param data a string containing unformatted data
     * @return formatted data consisting of data formatted for an LDIF record
     *         suitable for an LDIF file
     */
    private static String format_ldif_data(int length, String data) {
        StringBuffer revised_data = new StringBuffer();

        if (data.length() > length) {
            // process first line
            for (int i = 0; i < length; i++) {
                revised_data.append(data.charAt(i));
            }

            // terminate first line
            revised_data.append("\n");

            // process remaining lines
            int j = 0;
            for (int i = length; i < data.length(); i++) {
                if (j == 0) {
                    revised_data.append(' ');
                }

                revised_data.append(data.charAt(i));

                j++;

                if (j == 76) {
                    revised_data.append("\n");
                    j = 0;
                }
            }
        }

        return revised_data.toString().replaceAll("\\s+$", "");
    }

    /*********************/
    /* ID Offset Methods */
    /*********************/

    /**
     * Helper method which converts an "indexed" BigInteger into
     * its String representation.
     *
     * <PRE>
     *
     *     NOTE:  Indexed data means that the numeric data
     *            is stored with a prepended length
     *            (e. g. - record '73' is stored as '0273').
     *
     *            Indexed data is currently limited to '99' digits
     *            (an index of '00' is invalid).  See
     *            'com.netscape.cmscore.dbs.BigIntegerMapper.java'
     *            for details.
     *
     * </PRE>
     *
     * This method is based upon code from
     * 'com.netscape.cmscore.dbs.BigIntegerMapper'.
     * <P>
     *
     * @param i an "indexed " BigInteger
     * @return the string representation of the "indexed" BigInteger
     */
    private static String BigIntegerToDB(BigInteger i) {
        int len = i.toString().length();
        String ret = null;

        if (len < 10) {
            ret = "0" + Integer.toString(len) + i.toString();
        } else {
            ret = Integer.toString(len) + i.toString();
        }
        return ret;
    }

    /**
     * Helper method which converts the string representation of an
     * "indexed" integer into a BigInteger.
     *
     * <PRE>
     *     NOTE:  Indexed data means that the numeric data
     *            is stored with a prepended length
     *            (e. g. - record '73' is stored as '0273').
     *
     *            Indexed data is currently limited to '99' digits
     *            (an index of '00' is invalid).  See
     *            'com.netscape.cmscore.dbs.BigIntegerMapper.java'
     *            for details.
     * </PRE>
     *
     * This method is based upon code from
     * 'com.netscape.cmscore.dbs.BigIntegerMapper'.
     * <P>
     *
     * @param i the string representation of the "indexed" integer
     * @return an "indexed " BigInteger
     */
    private static BigInteger BigIntegerFromDB(String i) {
        String s = i.substring(2);

        // possibly check length
        return new BigInteger(s);
    }

    /**
     * This method accepts an "attribute", its "delimiter", a string
     * representation of numeric data, and a flag indicating whether
     * or not the string representation is "indexed".
     *
     * An "attribute" consists of one of the following values:
     *
     * <PRE>
     *     KRA_LDIF_CN = "cn:";
     *     KRA_LDIF_DN_EMBEDDED_CN_DATA = "dn: cn";
     *     KRA_LDIF_EXTDATA_KEY_RECORD = "extdata-keyrecord:";
     *     KRA_LDIF_EXTDATA_REQUEST_ID = "extdata-requestid:";
     *     KRA_LDIF_EXTDATA_SERIAL_NUMBER = "extdata-serialnumber:";
     *     KRA_LDIF_REQUEST_ID = "requestId:";
     *     KRA_LDIF_SERIAL_NO = "serialno:";
     *
     *
     *     NOTE:  Indexed data means that the numeric data
     *            is stored with a prepended length
     *            (e. g. - record '73' is stored as '0273').
     *
     *            Indexed data is currently limited to '99' digits
     *            (an index of '00' is invalid).  See
     *            'com.netscape.cmscore.dbs.BigIntegerMapper.java'
     *            for details.
     * </PRE>
     *
     * <P>
     *
     * @param attribute the string representation of the "name"
     * @param delimiter the separator between the attribute and its contents
     * @param source_line the string containing the "name" and "value"
     * @param indexed boolean flag indicating if the "value" is "indexed"
     * @return a revised line containing the "name" and "value" with the
     *         specified ID offset applied as a "mask" to the "value"
     */
    private static String compose_numeric_line(String attribute,
                                                String delimiter,
                                                String source_line,
                                                boolean indexed) {
        String target_line = null;
        String data = null;
        String revised_data = null;
        BigInteger value = null;

        // Since both "-append_id_offset" and "-remove_id_offset" are OPTIONAL
        // parameters, first check to see if either has been selected
        if (!mAppendIdOffsetFlag &&
                !mRemoveIdOffsetFlag) {
            return source_line;
        }

        try {
            // extract the data
            data = source_line.substring(attribute.length() + 1).trim();

            // skip values which are non-numeric
            if (!data.matches("[0-9]++")) {
                // set the target_line to the unchanged source_line
                target_line = source_line;

                // log this information
                logger.info("Skipped changing non-numeric line " + source_line);
            } else {
                // if indexed, first strip the index from the data
                if (indexed) {
                    // NOTE:  Indexed data means that the numeric data
                    //        is stored with a prepended length
                    //        (e. g. - record '73' is stored as '0273').
                    //
                    //        Indexed data is currently limited to '99' digits
                    //        (an index of '00' is invalid).  See
                    //        'com.netscape.cmscore.dbs.BigIntegerMapper.java'
                    //        for details.
                    value = BigIntegerFromDB(data);
                } else {
                    value = new BigInteger(data);
                }

                // compare the specified target ID offset
                // with the actual value of the attribute
                if (mAppendIdOffsetFlag) {
                    if (mAppendIdOffset.compareTo(value) == 1) {
                        // add the target ID offset to this value
                        if (indexed) {
                            revised_data = BigIntegerToDB(
                                               value.add(mAppendIdOffset)
                                               ).toString();
                        } else {
                            revised_data = value.add(
                                               mAppendIdOffset).toString();
                        }
                    } else {
                        logger.error("attribute='"
                                + attribute
                                + "' is greater than the specified "
                                + "append_id_offset="
                                + mAppendIdOffset);
                        System.exit(0);
                    }
                } else if (mRemoveIdOffsetFlag) {
                    if (mRemoveIdOffset.compareTo(value) <= 0) {
                        // subtract the target ID offset to this value
                        if (indexed) {
                            revised_data = BigIntegerToDB(
                                               value.subtract(mRemoveIdOffset)
                                               ).toString();
                        } else {
                            revised_data = value.subtract(mRemoveIdOffset
                                               ).toString();
                        }
                    } else {
                        logger.error("attribute='"
                                + attribute
                                + "' is less than the specified "
                                + "remove_id_offset="
                                + mRemoveIdOffset);
                        System.exit(0);
                    }
                }

                // set the target_line to the revised data
                target_line = attribute + delimiter + revised_data;

                // log this information
                logger.info("Changed numeric data "
                        + data
                        + " to "
                        + revised_data);
            }
        } catch (IndexOutOfBoundsException exBounds) {
            logger.error("source_line='"
                    + source_line
                    + "': "
                    + exBounds.getMessage(),
                    exBounds);
            System.exit(0);
        } catch (PatternSyntaxException exPattern) {
            logger.error("data='"
                    + data
                    + "': "
                    + exPattern.getMessage(),
                    exPattern);
            System.exit(0);
        }

        return target_line;
    }

    /***********************/
    /* LDIF Parser Methods */
    /***********************/

    /**
     * Helper method which composes the output line for KRA_LDIF_CN.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_cn(String record_type,
                                     String line) {
        String output = null;

        if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
            if (kratoolCfg.get(KRATOOL_CFG_ENROLLMENT_CN)) {
                output = compose_numeric_line(KRA_LDIF_CN,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_CA_KEY_RECORD)) {
            if (kratoolCfg.get(KRATOOL_CFG_CA_KEY_RECORD_CN)) {
                output = compose_numeric_line(KRA_LDIF_CN,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_RECOVERY)) {
            if (kratoolCfg.get(KRATOOL_CFG_RECOVERY_CN)) {
                output = compose_numeric_line(KRA_LDIF_CN,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_TPS_KEY_RECORD)) {
            if (kratoolCfg.get(KRATOOL_CFG_TPS_KEY_RECORD_CN)) {
                output = compose_numeric_line(KRA_LDIF_CN,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_KEYGEN)) {
            if (kratoolCfg.get(KRATOOL_CFG_KEYGEN_CN)) {
                output = compose_numeric_line(KRA_LDIF_CN,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else if (record_type.equals( KRA_LDIF_KEYRECOVERY ) ) {
            if( kratoolCfg.get(KRATOOL_CFG_KEYRECOVERY_CN ) ) {
                output = compose_numeric_line(KRA_LDIF_CN,
                    SPACE,
                    line,
                    false );
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_RECORD)) {
            // Non-Request / Non-Key Record:
            //     Pass through the original
            //     'cn' line UNCHANGED
            //     so that it is ALWAYS written
            output = line;
        } else {
            logger.error("Mismatched record field='"
                    + KRA_LDIF_CN
                    + "' for record type="
                    + record_type);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for KRA_LDIF_DATE_OF_MODIFY.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_date_of_modify(String record_type,
                                                 String line) {
        String output = null;

        if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
            if (kratoolCfg.get(KRATOOL_CFG_ENROLLMENT_DATE_OF_MODIFY)) {
                output = KRA_LDIF_DATE_OF_MODIFY
                        + SPACE
                        + mDateOfModify;

                logger.info("Changed "
                        + line
                        + " to "
                        + output);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_CA_KEY_RECORD)) {
            if (kratoolCfg.get(KRATOOL_CFG_CA_KEY_RECORD_DATE_OF_MODIFY)) {
                output = KRA_LDIF_DATE_OF_MODIFY
                        + SPACE
                        + mDateOfModify;

                logger.info("Changed "
                        + line
                        + " to "
                        + output);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_RECOVERY)) {
            if (kratoolCfg.get(KRATOOL_CFG_RECOVERY_DATE_OF_MODIFY)) {
                output = KRA_LDIF_DATE_OF_MODIFY
                        + SPACE
                        + mDateOfModify;

                logger.info("Changed "
                        + line
                        + " to "
                        + output);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_TPS_KEY_RECORD)) {
            if (kratoolCfg.get(KRATOOL_CFG_TPS_KEY_RECORD_DATE_OF_MODIFY)) {
                output = KRA_LDIF_DATE_OF_MODIFY
                        + SPACE
                        + mDateOfModify;

                logger.info("Changed "
                        + line
                        + " to "
                        + output);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_KEYGEN)) {
            if (kratoolCfg.get(KRATOOL_CFG_KEYGEN_DATE_OF_MODIFY)) {
                output = KRA_LDIF_DATE_OF_MODIFY
                        + SPACE
                        + mDateOfModify;

                logger.info("Changed "
                        + line
                        + " to "
                        + output);
            } else {
                output = line;
            }
        } else if (record_type.equals( KRA_LDIF_KEYRECOVERY ) ) {
            if( kratoolCfg.get( KRATOOL_CFG_KEYRECOVERY_DATE_OF_MODIFY ) ) {
                output = KRA_LDIF_DATE_OF_MODIFY
                        + SPACE
                        + mDateOfModify;

                 logger.info("Changed "
                    + line
                    + " to "
                    + output);
            } else {
                    output = line;
            }
        } else {
            logger.error("Mismatched record field='"
                    + KRA_LDIF_DATE_OF_MODIFY
                    + "' for record type="
                    + record_type);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for KRA_LDIF_DN.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_dn(String record_type,
                                     String line) {
        String embedded_cn_data[] = null;
        String embedded_cn_output = null;
        String input = null;
        String output = null;

        try {
            if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
                if (kratoolCfg.get(KRATOOL_CFG_ENROLLMENT_DN)) {

                    // First check for an embedded "cn=<value>"
                    // name-value pair
                    if (line.startsWith(KRA_LDIF_DN_EMBEDDED_CN_DATA)) {
                        // At this point, always extract
                        // the embedded "cn=<value>" name-value pair
                        // which will ALWAYS be the first
                        // portion of the "dn: " attribute
                        embedded_cn_data = line.split(COMMA, 2);

                        embedded_cn_output = compose_numeric_line(
                                                 KRA_LDIF_DN_EMBEDDED_CN_DATA,
                                                 EQUAL_SIGN,
                                                 embedded_cn_data[0],
                                                 false);

                        input = embedded_cn_output
                                + COMMA
                                + embedded_cn_data[1];
                    } else {
                        input = line;
                    }

                    // Since "-source_kra_naming_context", and
                    // "-target_kra_naming_context" are OPTIONAL
                    // parameters, ONLY process this portion of the field
                    // if both of these options have been selected
                    if (mKraNamingContextsFlag) {
                        output = input.replace(mSourceKraNamingContext,
                                                mTargetKraNamingContext);
                    } else {
                        output = input;
                    }
                } else {
                    output = line;
                }
            } else if (record_type.equals(KRA_LDIF_CA_KEY_RECORD)) {
                if (kratoolCfg.get(KRATOOL_CFG_CA_KEY_RECORD_DN)) {

                    // First check for an embedded "cn=<value>"
                    // name-value pair
                    if (line.startsWith(KRA_LDIF_DN_EMBEDDED_CN_DATA)) {
                        // At this point, always extract
                        // the embedded "cn=<value>" name-value pair
                        // which will ALWAYS be the first
                        // portion of the "dn: " attribute
                        embedded_cn_data = line.split(COMMA, 2);

                        embedded_cn_output = compose_numeric_line(
                                                 KRA_LDIF_DN_EMBEDDED_CN_DATA,
                                                 EQUAL_SIGN,
                                                 embedded_cn_data[0],
                                                 false);

                        input = embedded_cn_output
                                + COMMA
                                + embedded_cn_data[1];
                    } else {
                        input = line;
                    }

                    // Since "-source_kra_naming_context", and
                    // "-target_kra_naming_context" are OPTIONAL
                    // parameters, ONLY process this portion of the field
                    // if both of these options have been selected
                    if (mKraNamingContextsFlag) {
                        output = input.replace(mSourceKraNamingContext,
                                                mTargetKraNamingContext);
                    } else {
                        output = input;
                    }
                } else {
                    output = line;
                }
            } else if (record_type.equals(KRA_LDIF_RECOVERY)) {
                if (kratoolCfg.get(KRATOOL_CFG_RECOVERY_DN)) {

                    // First check for an embedded "cn=<value>"
                    // name-value pair
                    if (line.startsWith(KRA_LDIF_DN_EMBEDDED_CN_DATA)) {
                        // At this point, always extract
                        // the embedded "cn=<value>" name-value pair
                        // which will ALWAYS be the first
                        // portion of the "dn: " attribute
                        embedded_cn_data = line.split(COMMA, 2);

                        embedded_cn_output = compose_numeric_line(
                                                 KRA_LDIF_DN_EMBEDDED_CN_DATA,
                                                 EQUAL_SIGN,
                                                 embedded_cn_data[0],
                                                 false);

                        input = embedded_cn_output
                                + COMMA
                                + embedded_cn_data[1];
                    } else {
                        input = line;
                    }

                    // Since "-source_kra_naming_context", and
                    // "-target_kra_naming_context" are OPTIONAL
                    // parameters, ONLY process this portion of the field
                    // if both of these options have been selected
                    if (mKraNamingContextsFlag) {
                        output = input.replace(mSourceKraNamingContext,
                                                mTargetKraNamingContext);
                    } else {
                        output = input;
                    }
                } else {
                    output = line;
                }
            } else if (record_type.equals(KRA_LDIF_TPS_KEY_RECORD)) {
                if (kratoolCfg.get(KRATOOL_CFG_TPS_KEY_RECORD_DN)) {

                    // First check for an embedded "cn=<value>"
                    // name-value pair
                    if (line.startsWith(KRA_LDIF_DN_EMBEDDED_CN_DATA)) {
                        // At this point, always extract
                        // the embedded "cn=<value>" name-value pair
                        // which will ALWAYS be the first
                        // portion of the "dn: " attribute
                        embedded_cn_data = line.split(COMMA, 2);

                        embedded_cn_output = compose_numeric_line(
                                                 KRA_LDIF_DN_EMBEDDED_CN_DATA,
                                                 EQUAL_SIGN,
                                                 embedded_cn_data[0],
                                                 false);

                        input = embedded_cn_output
                                + COMMA
                                + embedded_cn_data[1];
                    } else {
                        input = line;
                    }

                    // Since "-source_kra_naming_context", and
                    // "-target_kra_naming_context" are OPTIONAL
                    // parameters, ONLY process this portion of the field
                    // if both of these options have been selected
                    if (mKraNamingContextsFlag) {
                        output = input.replace(mSourceKraNamingContext,
                                                mTargetKraNamingContext);
                    } else {
                        output = input;
                    }
                } else {
                    output = line;
                }
            } else if (record_type.equals(KRA_LDIF_KEYGEN)) {
                if (kratoolCfg.get(KRATOOL_CFG_KEYGEN_DN)) {

                    // First check for an embedded "cn=<value>"
                    // name-value pair
                    if (line.startsWith(KRA_LDIF_DN_EMBEDDED_CN_DATA)) {
                        // At this point, always extract
                        // the embedded "cn=<value>" name-value pair
                        // which will ALWAYS be the first
                        // portion of the "dn: " attribute
                        embedded_cn_data = line.split(COMMA, 2);

                        embedded_cn_output = compose_numeric_line(
                                                 KRA_LDIF_DN_EMBEDDED_CN_DATA,
                                                 EQUAL_SIGN,
                                                 embedded_cn_data[0],
                                                 false);

                        input = embedded_cn_output
                                + COMMA
                                + embedded_cn_data[1];
                    } else {
                        input = line;
                    }

                    // Since "-source_kra_naming_context", and
                    // "-target_kra_naming_context" are OPTIONAL
                    // parameters, ONLY process this portion of the field
                    // if both of these options have been selected
                    if (mKraNamingContextsFlag) {
                        output = input.replace(mSourceKraNamingContext,
                                                mTargetKraNamingContext);
                    } else {
                        output = input;
                    }
                } else {
                    output = line;
                }
            } else if (record_type.equals( KRA_LDIF_KEYRECOVERY ) ) {
                if( kratoolCfg.get( KRATOOL_CFG_KEYRECOVERY_DN ) ) {
                    // First check for an embedded "cn=<value>"
                    // name-value pair
                    if( line.startsWith( KRA_LDIF_DN_EMBEDDED_CN_DATA ) ) {
                        // At this point, always extract
                        // the embedded "cn=<value>" name-value pair
                        // which will ALWAYS be the first
                        // portion of the "dn: " attribute
                        embedded_cn_data = line.split( COMMA, 2 );

                        embedded_cn_output = compose_numeric_line(
                                                 KRA_LDIF_DN_EMBEDDED_CN_DATA,
                                                 EQUAL_SIGN,
                                                 embedded_cn_data[0],
                                                 false );

                        input = embedded_cn_output
                              + COMMA
                              + embedded_cn_data[1];
                    } else {
                        input = line;
                    }

                    // Since "-source_kra_naming_context", and
                    // "-target_kra_naming_context" are OPTIONAL
                    // parameters, ONLY process this portion of the field
                    // if both of these options have been selected
                    if( mKraNamingContextsFlag ) {
                        output = input.replace( mSourceKraNamingContext,
                                                mTargetKraNamingContext );
                    } else {
                        output = input;
                    }

                } else {
                        output = line;
                }
            } else if (record_type.equals(KRA_LDIF_RECORD)) {
                // Non-Request / Non-Key Record:
                //     Pass through the original
                //     'dn' line UNCHANGED
                //     so that it is ALWAYS written
                output = line;
            } else {
                logger.error("Mismatched record field='"
                        + KRA_LDIF_DN
                        + "' for record type="
                        + record_type);
            }
        } catch (PatternSyntaxException exDnEmbeddedCnNameValuePattern) {
            logger.error("line='"
                    + line
                    + "': "
                    + exDnEmbeddedCnNameValuePattern.getMessage(),
                    exDnEmbeddedCnNameValuePattern);
        } catch (NullPointerException exNullPointerException) {
            logger.error("Unable to replace source KRA naming context '"
                    + mSourceKraNamingContext
                    + "' with target KRA naming context '"
                    + mTargetKraNamingContext
                    + "': "
                    + exNullPointerException.getMessage(),
                    exNullPointerException);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for
     * KRA_LDIF_EXTDATA_KEY_RECORD.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_extdata_key_record(String record_type,
                                                     String line) {
        String output = null;

        if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
            if (kratoolCfg.get(KRATOOL_CFG_ENROLLMENT_EXTDATA_KEY_RECORD)) {
                output = compose_numeric_line(KRA_LDIF_EXTDATA_KEY_RECORD,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_KEYGEN)) {
            if (kratoolCfg.get(KRATOOL_CFG_KEYGEN_EXTDATA_KEY_RECORD)) {
                output = compose_numeric_line(KRA_LDIF_EXTDATA_KEY_RECORD,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else {
            logger.error("Mismatched record field='"
                    + KRA_LDIF_EXTDATA_KEY_RECORD
                    + "' for record type="
                    + record_type);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for
     * KRA_LDIF_EXTDATA_REQUEST_ID.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_extdata_request_id(String record_type,
                                                     String line) {
        String output = null;

        if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
            // ALWAYS pass-through "extdata-requestId" for
            // KRA_LDIF_ENROLLMENT records UNCHANGED because the
            // value in this field is associated with the issuing CA!
            output = line;
        } else if (record_type.equals(KRA_LDIF_RECOVERY)) {
            if (kratoolCfg.get(KRATOOL_CFG_RECOVERY_EXTDATA_REQUEST_ID)) {
                output = compose_numeric_line(KRA_LDIF_EXTDATA_REQUEST_ID,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_KEYGEN)) {
            if (kratoolCfg.get(KRATOOL_CFG_KEYGEN_EXTDATA_REQUEST_ID)) {
                output = compose_numeric_line(KRA_LDIF_EXTDATA_REQUEST_ID,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else if (record_type.equals( KRA_LDIF_KEYRECOVERY ) ) {
            if( kratoolCfg.get(KRATOOL_CFG_KEYRECOVERY_EXTDATA_REQUEST_ID ) ) {
                output = compose_numeric_line(KRA_LDIF_EXTDATA_REQUEST_ID,
                        SPACE,
                        line,
                        false );
            } else {
                output = line;
            }
        } else {
            logger.error("Mismatched record field='"
                    + KRA_LDIF_EXTDATA_REQUEST_ID
                    + "' for record type="
                    + record_type);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for
     * KRA_LDIF_EXTDATA_REQUEST_NOTES.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_extdata_request_notes(String record_type,
            String line) {
        StringBuffer input = new StringBuffer();

        String data = null;
        String unformatted_data = null;
        String output = null;
        String next_line = null;

        // extract the data
        if (line.length() > KRA_LDIF_EXTDATA_REQUEST_NOTES.length()) {
            input.append(line.substring(
                        KRA_LDIF_EXTDATA_REQUEST_NOTES.length() + 1
                    ).trim());
        } else {
            input.append(line.substring(
                        KRA_LDIF_EXTDATA_REQUEST_NOTES.length()
                    ).trim());
        }

        while ((line = ldif_record.next()) != null) {
            if (line.startsWith(SPACE)) {
                // Do NOT use "trim()";
                // remove single leading space and
                // trailing carriage returns and newlines ONLY!
                input.append(line.replaceFirst(" ", "").replace('\r', '\0').replace('\n', '\0'));
            } else {
                next_line = line;
                break;
            }
        }

        if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
            if (kratoolCfg.get(KRATOOL_CFG_ENROLLMENT_EXTDATA_REQUEST_NOTES)) {
                // write out a revised 'extdata-requestnotes' line
                if (mRewrapFlag && mAppendIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + SPACE
                            + PLUS + SPACE
                            + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mAppendIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRewrapFlag && mRemoveIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + SPACE
                            + PLUS + SPACE
                            + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mRemoveIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRewrapFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mAppendIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mAppendIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRemoveIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mRemoveIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                }

                // log this information
                logger.info("Changed:"
                        + NEWLINE
                        + TIC
                        + KRA_LDIF_EXTDATA_REQUEST_NOTES
                        + SPACE
                        + format_ldif_data(
                                EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                input.toString())
                        + TIC
                        + NEWLINE
                        + "--->"
                        + NEWLINE
                        + TIC
                        + output
                        + TIC);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_RECOVERY)) {
            if (kratoolCfg.get(KRATOOL_CFG_RECOVERY_EXTDATA_REQUEST_NOTES)) {
                // write out a revised 'extdata-requestnotes' line
                if (mRewrapFlag && mAppendIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + SPACE
                            + PLUS + SPACE
                            + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mAppendIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRewrapFlag && mRemoveIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + SPACE
                            + PLUS + SPACE
                            + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mRemoveIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRewrapFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mAppendIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mAppendIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRemoveIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mRemoveIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                }

                // log this information
                logger.info("Changed:"
                        + NEWLINE
                        + TIC
                        + KRA_LDIF_EXTDATA_REQUEST_NOTES
                        + SPACE
                        + format_ldif_data(
                                EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                input.toString())
                        + TIC
                        + NEWLINE
                        + "--->"
                        + NEWLINE
                        + TIC
                        + output
                        + TIC);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_KEYGEN)) {
            if (kratoolCfg.get(KRATOOL_CFG_KEYGEN_EXTDATA_REQUEST_NOTES)) {
                // write out a revised 'extdata-requestnotes' line
                if (mRewrapFlag && mAppendIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + SPACE
                            + PLUS + SPACE
                            + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mAppendIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRewrapFlag && mRemoveIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + SPACE
                            + PLUS + SPACE
                            + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mRemoveIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRewrapFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REWRAP_MESSAGE
                            + mPublicKeySize
                            + KRA_LDIF_RSA_MESSAGE
                            + mSourcePKISecurityDatabasePwdfileMessage
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mAppendIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mAppendIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                } else if (mRemoveIdOffsetFlag) {
                    data = input.toString()
                            + SPACE
                            + LEFT_BRACE
                            + mDateOfModify
                            + RIGHT_BRACE
                            + COLON + SPACE
                            + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                            + SPACE
                            + TIC
                            + mRemoveIdOffset.toString()
                            + TIC
                            + mKraNamingContextMessage
                            + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL(data);

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                            + SPACE
                            + format_ldif_data(
                                    EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                    unformatted_data);
                }

                // log this information
                logger.info("Changed:"
                        + NEWLINE
                        + TIC
                        + KRA_LDIF_EXTDATA_REQUEST_NOTES
                        + SPACE
                        + format_ldif_data(
                                EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                input.toString())
                        + TIC
                        + NEWLINE
                        + "--->"
                        + NEWLINE
                        + TIC
                        + output
                        + TIC);
            } else {
                output = line;
            }
        } else if (record_type.equals( KRA_LDIF_KEYRECOVERY ) ) {
            if( kratoolCfg.get( KRATOOL_CFG_KEYRECOVERY_EXTDATA_REQUEST_NOTES ) ) {
                // write out a revised 'extdata-requestnotes' line
                if( mRewrapFlag && mAppendIdOffsetFlag ) {
                    data = input
                         + SPACE
                         + LEFT_BRACE
                         + mDateOfModify
                         + RIGHT_BRACE
                         + COLON + SPACE
                         + KRA_LDIF_REWRAP_MESSAGE
                         + mPublicKeySize
                         + KRA_LDIF_RSA_MESSAGE
                         + mSourcePKISecurityDatabasePwdfileMessage
                         + SPACE
                         + PLUS + SPACE
                         + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                         + SPACE
                         + TIC
                         + mAppendIdOffset.toString()
                         + TIC
                         + mKraNamingContextMessage
                         + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL( data );

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                           + SPACE
                           + format_ldif_data(
                                 EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                 unformatted_data );
                } else if( mRewrapFlag && mRemoveIdOffsetFlag ) {
                    data = input
                         + SPACE
                         + LEFT_BRACE
                         + mDateOfModify
                         + RIGHT_BRACE
                         + COLON + SPACE
                         + KRA_LDIF_REWRAP_MESSAGE
                         + mPublicKeySize
                         + KRA_LDIF_RSA_MESSAGE
                         + mSourcePKISecurityDatabasePwdfileMessage
                         + SPACE
                         + PLUS + SPACE
                         + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                         + SPACE
                         + TIC
                         + mRemoveIdOffset.toString()
                         + TIC
                         + mKraNamingContextMessage
                         + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL( data );

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                           + SPACE
                           + format_ldif_data(
                                 EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                 unformatted_data );
                } else if( mRewrapFlag ) {
                    data = input
                         + SPACE
                         + LEFT_BRACE
                         + mDateOfModify
                         + RIGHT_BRACE
                         + COLON + SPACE
                         + KRA_LDIF_REWRAP_MESSAGE
                         + mPublicKeySize
                         + KRA_LDIF_RSA_MESSAGE
                         + mSourcePKISecurityDatabasePwdfileMessage
                         + mKraNamingContextMessage
                         + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL( data );

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                           + SPACE
                           + format_ldif_data(
                                 EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                 unformatted_data );
                } else if( mAppendIdOffsetFlag ) {
                    data = input
                         + SPACE
                         + LEFT_BRACE
                         + mDateOfModify
                         + RIGHT_BRACE
                         + COLON + SPACE
                         + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                         + SPACE
                         + TIC
                         + mAppendIdOffset.toString()
                         + TIC
                         + mKraNamingContextMessage
                         + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL( data );

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                           + SPACE
                           + format_ldif_data(
                                 EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                 unformatted_data );
                } else if( mRemoveIdOffsetFlag ) {
                    data = input
                         + SPACE
                         + LEFT_BRACE
                         + mDateOfModify
                         + RIGHT_BRACE
                         + COLON + SPACE
                         + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                         + SPACE
                         + TIC
                         + mRemoveIdOffset.toString()
                         + TIC
                         + mKraNamingContextMessage
                         + mProcessRequestsAndKeyRecordsOnlyMessage;

                    // Unformat the data
                    unformatted_data = stripEOL( data );

                    // Format the unformatted_data
                    // to match the desired LDIF format
                    output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                           + SPACE
                           + format_ldif_data(
                                 EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                 unformatted_data );
                }

                // log this information
                logger.info( "Changed:"
                   + NEWLINE
                   + TIC
                   + KRA_LDIF_EXTDATA_REQUEST_NOTES
                   + SPACE
                   + format_ldif_data(
                         EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                         input.toString() )
                   + TIC
                   + NEWLINE
                   + "--->"
                   + NEWLINE
                   + TIC
                   + output
                   + TIC);
            } else {
                output = line;
            }
        } else {
            logger.error("Mismatched record field='"
                    + KRA_LDIF_EXTDATA_REQUEST_NOTES
                    + "' for record type="
                    + record_type);
        }

        if (output != null) {
            output += NEWLINE + next_line;
        }

        return output;
    }

    /**
     * Helper method which composes the output line for
     * KRA_LDIF_EXTDATA_REQUEST_NOTES.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param previous_line the string representation of the previous input line
     * @param writer the PrintWriter used to output this new LDIF line
     * @return the composed output line
     */
    private static void create_extdata_request_notes(String record_type,
                                                      String previous_line,
                                                      PrintWriter writer) {
        String data = null;
        String unformatted_data = null;
        String output = null;

        if (record_type.equals(KRA_LDIF_RECOVERY)) {
            if (kratoolCfg.get(KRATOOL_CFG_RECOVERY_EXTDATA_REQUEST_NOTES)) {
                if (!previous_line.startsWith(KRA_LDIF_EXTDATA_REQUEST_NOTES)) {
                    // write out the missing 'extdata-requestnotes' line
                    if (mRewrapFlag && mAppendIdOffsetFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_REWRAP_MESSAGE
                                + mPublicKeySize
                                + KRA_LDIF_RSA_MESSAGE
                                + mSourcePKISecurityDatabasePwdfileMessage
                                + SPACE
                                + PLUS + SPACE
                                + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                                + SPACE
                                + TIC
                                + mAppendIdOffset.toString()
                                + TIC
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    } else if (mRewrapFlag && mRemoveIdOffsetFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_REWRAP_MESSAGE
                                + mPublicKeySize
                                + KRA_LDIF_RSA_MESSAGE
                                + mSourcePKISecurityDatabasePwdfileMessage
                                + SPACE
                                + PLUS + SPACE
                                + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                                + SPACE
                                + TIC
                                + mRemoveIdOffset.toString()
                                + TIC
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    } else if (mRewrapFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_REWRAP_MESSAGE
                                + mPublicKeySize
                                + KRA_LDIF_RSA_MESSAGE
                                + mSourcePKISecurityDatabasePwdfileMessage
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    } else if (mAppendIdOffsetFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                                + SPACE
                                + TIC
                                + mAppendIdOffset.toString()
                                + TIC
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    } else if (mRemoveIdOffsetFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                                + SPACE
                                + TIC
                                + mRemoveIdOffset.toString()
                                + TIC
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    }

                    // log this information
                    logger.info("Created:"
                            + NEWLINE
                            + TIC
                            + output
                            + TIC);

                    // Write out this revised line
                    // and flush the buffer
                    writer.write(output + NEWLINE);
                    writer.flush();
                }
            }
        } else if (record_type.equals(KRA_LDIF_KEYGEN)) {
            if (kratoolCfg.get(KRATOOL_CFG_KEYGEN_EXTDATA_REQUEST_NOTES)) {
                if (!previous_line.startsWith(KRA_LDIF_EXTDATA_REQUEST_NOTES)) {
                    // write out the missing 'extdata-requestnotes' line
                    if (mRewrapFlag && mAppendIdOffsetFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_REWRAP_MESSAGE
                                + mPublicKeySize
                                + KRA_LDIF_RSA_MESSAGE
                                + mSourcePKISecurityDatabasePwdfileMessage
                                + SPACE
                                + PLUS + SPACE
                                + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                                + SPACE
                                + TIC
                                + mAppendIdOffset.toString()
                                + TIC
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    } else if (mRewrapFlag && mRemoveIdOffsetFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_REWRAP_MESSAGE
                                + mPublicKeySize
                                + KRA_LDIF_RSA_MESSAGE
                                + mSourcePKISecurityDatabasePwdfileMessage
                                + SPACE
                                + PLUS + SPACE
                                + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                                + SPACE
                                + TIC
                                + mRemoveIdOffset.toString()
                                + TIC
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    } else if (mRewrapFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_REWRAP_MESSAGE
                                + mPublicKeySize
                                + KRA_LDIF_RSA_MESSAGE
                                + mSourcePKISecurityDatabasePwdfileMessage
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    } else if (mAppendIdOffsetFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                                + SPACE
                                + TIC
                                + mAppendIdOffset.toString()
                                + TIC
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    } else if (mRemoveIdOffsetFlag) {
                        data = LEFT_BRACE
                                + mDateOfModify
                                + RIGHT_BRACE
                                + COLON + SPACE
                                + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                                + SPACE
                                + TIC
                                + mRemoveIdOffset.toString()
                                + TIC
                                + mKraNamingContextMessage
                                + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL(data);

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                                + SPACE
                                + format_ldif_data(
                                        EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                        unformatted_data);
                    }

                    // log this information
                    logger.info("Created:"
                            + NEWLINE
                            + TIC
                            + output
                            + TIC);

                    // Write out this revised line
                    // and flush the buffer
                    writer.write(output + NEWLINE);
                    writer.flush();
                }
            }
        } else if (record_type.equals(KRA_LDIF_KEYRECOVERY)) {
            if( kratoolCfg.get( KRATOOL_CFG_KEYRECOVERY_EXTDATA_REQUEST_NOTES ) ) {
                if(!previous_line.startsWith( KRA_LDIF_EXTDATA_REQUEST_NOTES)) {
                    // write out the missing 'extdata-requestnotes' line
                    if( mRewrapFlag && mAppendIdOffsetFlag ) {
                        data = LEFT_BRACE
                             + mDateOfModify
                             + RIGHT_BRACE
                             + COLON + SPACE
                             + KRA_LDIF_REWRAP_MESSAGE
                             + mPublicKeySize
                             + KRA_LDIF_RSA_MESSAGE
                             + mSourcePKISecurityDatabasePwdfileMessage
                             + SPACE
                             + PLUS + SPACE
                             + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                             + SPACE
                             + TIC
                             + mAppendIdOffset.toString()
                             + TIC
                             + mKraNamingContextMessage
                             + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL( data );

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                               + SPACE
                               + format_ldif_data(
                                   EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                   unformatted_data );
                    } else if( mRewrapFlag && mRemoveIdOffsetFlag ) {
                        data = LEFT_BRACE
                             + mDateOfModify
                             + RIGHT_BRACE
                             + COLON + SPACE
                             + KRA_LDIF_REWRAP_MESSAGE
                             + mPublicKeySize
                             + KRA_LDIF_RSA_MESSAGE
                             + mSourcePKISecurityDatabasePwdfileMessage
                             + SPACE
                             + PLUS + SPACE
                             + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                             + SPACE
                             + TIC
                             + mRemoveIdOffset.toString()
                             + TIC
                             + mKraNamingContextMessage
                             + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL( data );

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                               + SPACE
                               + format_ldif_data(
                                   EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                   unformatted_data );
                    } else if( mRewrapFlag ) {
                        data = LEFT_BRACE
                             + mDateOfModify
                             + RIGHT_BRACE
                             + COLON + SPACE
                             + KRA_LDIF_REWRAP_MESSAGE
                             + mPublicKeySize
                             + KRA_LDIF_RSA_MESSAGE
                             + mSourcePKISecurityDatabasePwdfileMessage
                             + mKraNamingContextMessage
                             + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL( data );

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                               + SPACE
                               + format_ldif_data(
                                   EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                   unformatted_data );
                    } else if( mAppendIdOffsetFlag ) {
                        data = LEFT_BRACE
                             + mDateOfModify
                             + RIGHT_BRACE
                             + COLON + SPACE
                             + KRA_LDIF_APPENDED_ID_OFFSET_MESSAGE
                             + SPACE
                             + TIC
                             + mAppendIdOffset.toString()
                             + TIC
                             + mKraNamingContextMessage
                             + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL( data );

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                               + SPACE
                               + format_ldif_data(
                                   EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                   unformatted_data );
                    } else if( mRemoveIdOffsetFlag ) {
                        data = LEFT_BRACE
                             + mDateOfModify
                             + RIGHT_BRACE
                             + COLON + SPACE
                             + KRA_LDIF_REMOVED_ID_OFFSET_MESSAGE
                             + SPACE
                             + TIC
                             + mRemoveIdOffset.toString()
                             + TIC
                             + mKraNamingContextMessage
                             + mProcessRequestsAndKeyRecordsOnlyMessage;

                        // Unformat the data
                        unformatted_data = stripEOL( data );

                        // Format the unformatted_data
                        // to match the desired LDIF format
                        output = KRA_LDIF_EXTDATA_REQUEST_NOTES
                               + SPACE
                               + format_ldif_data(
                                   EXTDATA_REQUEST_NOTES_FIRST_LINE_DATA_LENGTH,
                                   unformatted_data );
                    }

                    // log this information
                    logger.info("Created:"
                       + NEWLINE
                       + TIC
                       + output
                       + TIC);

                    // Write out this revised line
                    // and flush the buffer
                    writer.write( output + NEWLINE );
                    writer.flush();
                }
            }
        }
    }

    /**
     * Helper method which composes the output line for
     * KRA_LDIF_EXTDATA_SERIAL_NUMBER.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_extdata_serial_number(String record_type,
                                                        String line) {
        String output = null;

        if (record_type.equals(KRA_LDIF_RECOVERY)) {
            if (kratoolCfg.get(KRATOOL_CFG_RECOVERY_EXTDATA_SERIAL_NUMBER)) {
                output = compose_numeric_line(KRA_LDIF_EXTDATA_SERIAL_NUMBER,
                                               SPACE,
                                               line,
                                               false);
            } else {
                output = line;
            }
        } else {
            logger.error("Mismatched record field='"
                    + KRA_LDIF_EXTDATA_SERIAL_NUMBER
                    + "' for record type="
                    + record_type);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for
     * KRA_LDIF_PRIVATE_KEY_DATA.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_private_key_data(String record_type,
                                                   String line) {
        byte source_wrappedKeyData[] = null;
        byte target_wrappedKeyData[] = null;
        StringBuffer data = new StringBuffer();
        String revised_data = null;
        String unformatted_data = null;
        String formatted_data = null;
        String output = null;

        try {
            if (record_type.equals(KRA_LDIF_CA_KEY_RECORD)) {
                if (kratoolCfg.get(KRATOOL_CFG_CA_KEY_RECORD_PRIVATE_KEY_DATA)) {
                    // Since "-source_pki_security_database_path",
                    // "-source_storage_token_name",
                    // "-source_storage_certificate_nickname", and
                    // "-target_storage_certificate_file" are OPTIONAL
                    // parameters, ONLY process this field if all of
                    // these options have been selected
                    if (mRewrapFlag) {
                        // extract the data
                        data.append(line.substring(
                                KRA_LDIF_PRIVATE_KEY_DATA.length() + 1
                                ).trim());

                        while ((line = ldif_record.next()) != null) {
                            if (line.startsWith(SPACE)) {
                                data.append(line.trim());
                            } else {
                                break;
                            }
                        }

                        // Decode the ASCII BASE 64 certificate
                        // enclosed in the String() object
                        // into a BINARY BASE 64 byte[] object
                        source_wrappedKeyData =
                                Utils.base64decode(data.toString());

                        // rewrap the source wrapped private key data
                        target_wrappedKeyData = rewrap_wrapped_key_data(
                                                    source_wrappedKeyData);

                        // Encode the BINARY BASE 64 byte[] object
                        // into an ASCII BASE 64 certificate
                        // enclosed in a String() object
                        revised_data = Utils.base64encode(
                                           target_wrappedKeyData, true);

                        // Unformat the ASCII BASE 64 certificate
                        // for the log file
                        unformatted_data = stripEOL(revised_data);

                        // Format the ASCII BASE 64 certificate
                        // to match the desired LDIF format
                        formatted_data = format_ldif_data(
                                PRIVATE_KEY_DATA_FIRST_LINE_DATA_LENGTH,
                                unformatted_data);

                        // construct a revised 'privateKeyData' line
                        output = KRA_LDIF_PRIVATE_KEY_DATA
                                + SPACE
                                + formatted_data
                                + NEWLINE
                                + line;

                        // log this information
                        logger.info("Changed privateKeyData");
                    } else {
                        output = line;
                    }
                } else {
                    output = line;
                }
            } else if (record_type.equals(KRA_LDIF_TPS_KEY_RECORD)) {
                if (kratoolCfg.get(KRATOOL_CFG_TPS_KEY_RECORD_PRIVATE_KEY_DATA)) {
                    // Since "-source_pki_security_database_path",
                    // "-source_storage_token_name",
                    // "-source_storage_certificate_nickname", and
                    // "-target_storage_certificate_file" are OPTIONAL
                    // parameters, ONLY process this field if all of
                    // these options have been selected
                    if (mRewrapFlag) {
                        // extract the data
                        data.append(line.substring(
                                   KRA_LDIF_PRIVATE_KEY_DATA.length() + 1
                                ).trim());

                        while ((line = ldif_record.next()) != null) {
                            if (line.startsWith(SPACE)) {
                                data.append(line.trim());
                            } else {
                                break;
                            }
                        }

                        // Decode the ASCII BASE 64 certificate
                        // enclosed in the String() object
                        // into a BINARY BASE 64 byte[] object
                        source_wrappedKeyData =
                                Utils.base64decode(data.toString());

                        // rewrap the source wrapped private key data
                        target_wrappedKeyData = rewrap_wrapped_key_data(
                                                    source_wrappedKeyData);

                        // Encode the BINARY BASE 64 byte[] object
                        // into an ASCII BASE 64 certificate
                        // enclosed in a String() object
                        revised_data = Utils.base64encode(
                                           target_wrappedKeyData, true);

                        // Unformat the ASCII BASE 64 certificate
                        // for the log file
                        unformatted_data = stripEOL(revised_data);

                        // Format the ASCII BASE 64 certificate
                        // to match the desired LDIF format
                        formatted_data = format_ldif_data(
                                PRIVATE_KEY_DATA_FIRST_LINE_DATA_LENGTH,
                                unformatted_data);

                        // construct a revised 'privateKeyData' line
                        output = KRA_LDIF_PRIVATE_KEY_DATA
                                + SPACE
                                + formatted_data
                                + NEWLINE
                                + line;

                        // log this information
                        logger.info("Changed privateKeyData");
                    } else {
                        output = line;
                    }
                } else {
                    output = line;
                }
            } else {
                logger.error("Mismatched record field='"
                        + KRA_LDIF_PRIVATE_KEY_DATA
                        + "' for record type="
                        + record_type);
            }
        } catch (Exception exRewrap) {
            logger.error("Unable to rewrap BINARY BASE 64 data: "
                    + exRewrap.getMessage(),
                    exRewrap);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for KRA_LDIF_REQUEST_ID.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_request_id(String record_type,
                                             String line) {
        String output = null;

        if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
            if (kratoolCfg.get(KRATOOL_CFG_ENROLLMENT_REQUEST_ID)) {
                output = compose_numeric_line(KRA_LDIF_REQUEST_ID,
                                               SPACE,
                                               line,
                                               true);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_RECOVERY)) {
            if (kratoolCfg.get(KRATOOL_CFG_RECOVERY_REQUEST_ID)) {
                output = compose_numeric_line(KRA_LDIF_REQUEST_ID,
                                               SPACE,
                                               line,
                                               true);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_KEYGEN)) {
            if (kratoolCfg.get(KRATOOL_CFG_KEYGEN_REQUEST_ID)) {
                output = compose_numeric_line(KRA_LDIF_REQUEST_ID,
                                               SPACE,
                                               line,
                                               true);
            } else {
                output = line;
            }
        } else if ( record_type.equals( KRA_LDIF_KEYRECOVERY ) ) {
            if ( kratoolCfg.get( KRATOOL_CFG_KEYRECOVERY_REQUEST_ID ) ) {
                    output = compose_numeric_line(KRA_LDIF_REQUEST_ID,
                                                  SPACE,
                                                  line,
                                                  true);
            } else {
                    output = line;
            }
        } else {
            logger.error("Mismatched record field='"
                    + KRA_LDIF_REQUEST_ID
                    + "' for record type="
                    + record_type);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for KRA_LDIF_SERIAL_NO.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_serial_no(String record_type,
                                            String line) {
        String output = null;

        if (record_type.equals(KRA_LDIF_CA_KEY_RECORD)) {
            if (kratoolCfg.get(KRATOOL_CFG_CA_KEY_RECORD_SERIAL_NO)) {
                output = compose_numeric_line(KRA_LDIF_SERIAL_NO,
                                               SPACE,
                                               line,
                                               true);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_TPS_KEY_RECORD)) {
            if (kratoolCfg.get(KRATOOL_CFG_TPS_KEY_RECORD_SERIAL_NO)) {
                output = compose_numeric_line(KRA_LDIF_SERIAL_NO,
                                               SPACE,
                                               line,
                                               true);
            } else {
                output = line;
            }
        } else if (record_type.equals(KRA_LDIF_RECORD)) {
            // Non-Request / Non-Key Record:
            //     Pass through the original
            //     'serialno' line UNCHANGED
            //     so that it is ALWAYS written
            output = line;
        } else {
            logger.error("Mismatched record field='"
                    + KRA_LDIF_SERIAL_NO
                    + "' for record type="
                    + record_type);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for
     * KRA_LDIF_EXTDATA_AUTH_TOKEN_USER.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_extdata_auth_token_user(String record_type,
                                                          String line) {
        String output = null;

        try {
            if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
                // Since "-source_kra_naming_context", and
                // "-target_kra_naming_context" are OPTIONAL
                // parameters, ONLY process this field if both of
                // these options have been selected
                if (mKraNamingContextsFlag) {
                    output = line.replace(mSourceKraNamingContext,
                                           mTargetKraNamingContext);
                } else {
                    output = line;
                }
            } else {
                logger.error("Mismatched record field='"
                        + KRA_LDIF_EXTDATA_AUTH_TOKEN_USER
                        + "' for record type="
                        + record_type);
            }
        } catch (NullPointerException exNullPointerException) {
            logger.error("Unable to replace source KRA naming context '"
                    + mSourceKraNamingContext
                    + "' with target KRA naming context '"
                    + mTargetKraNamingContext
                    + "': "
                    + exNullPointerException.getMessage(),
                    exNullPointerException);
        }

        return output;
    }

    /**
     * Helper method which composes the output line for
     * KRA_LDIF_EXTDATA_AUTH_TOKEN_USER_DN.
     * <P>
     *
     * @param record_type the string representation of the input record type
     * @param line the string representation of the input line
     * @return the composed output line
     */
    private static String output_extdata_auth_token_user_dn(String record_type,
                                                             String line) {
        String output = null;

        try {
            if (record_type.equals(KRA_LDIF_ENROLLMENT)) {
                // Since "-source_kra_naming_context", and
                // "-target_kra_naming_context" are OPTIONAL
                // parameters, ONLY process this field if both of
                // these options have been selected
                if (mKraNamingContextsFlag) {
                    output = line.replace(mSourceKraNamingContext,
                                           mTargetKraNamingContext);
                } else {
                    output = line;
                }
            } else {
                logger.error("Mismatched record field='"
                        + KRA_LDIF_EXTDATA_AUTH_TOKEN_USER_DN
                        + "' for record type="
                        + record_type);
            }
        } catch (NullPointerException exNullPointerException) {
            logger.error("Unable to replace source KRA naming context '"
                    + mSourceKraNamingContext
                    + "' with target KRA naming context '"
                    + mTargetKraNamingContext
                    + "': "
                    + exNullPointerException.getMessage(),
                    exNullPointerException);
        }

        return output;
    }

    /**
     * This method performs the actual parsing of the "source" LDIF file
     * and produces the "target" LDIF file.
     * <P>
     *
     * @return true if the "target" LDIF file is successfully created
     */
    private static boolean convert_source_ldif_to_target_ldif() {
        boolean success = false;
        BufferedReader reader = null;
        PrintWriter writer = null;
        String input = null;
        String line = null;
        String previous_line = null;
        String output = null;
        String data = null;
        String record_type = null;

        if (mRewrapFlag) {
            success = obtain_RSA_rewrapping_keys();
            if (!success) {
                return FAILURE;
            }
        }

        // Create a vector for LDIF input
        record = new Vector<>(INITIAL_LDIF_RECORD_CAPACITY);

        // Process each line in the source LDIF file
        // and store it in the target LDIF file
        try {
            // Open source LDIF file for reading
            reader = new BufferedReader(
                         new FileReader(mSourceLdifFilename));

            // Open target LDIF file for writing
            writer = new PrintWriter(
                         new BufferedWriter(
                                 new FileWriter(mTargetLdifFilename)));

            logger.info("PROCESSING: ");
            while ((input = reader.readLine()) != null) {
                // Read in a record from the source LDIF file and
                // add this line of input into the record vector
                success = record.add(input);
                if (!success) {
                    return FAILURE;
                }

                // Check for the end of an LDIF record
                if (!input.equals("")) {
                    // Check to see if input line identifies the record type
                    if (input.startsWith(KRA_LDIF_REQUEST_TYPE)) {
                        // set the record type:
                        //
                        //     * KRA_LDIF_ENROLLMENT
                        //     * KRA_LDIF_KEYGEN
                        //     * KRA_LDIF_RECOVERY
                        //
                        record_type = input.substring(
                                          KRA_LDIF_REQUEST_TYPE.length() + 1
                                      ).trim();
                        if (!record_type.equals(KRA_LDIF_ENROLLMENT) &&
                                !record_type.equals(KRA_LDIF_KEYGEN) &&
                                !record_type.equals(KRA_LDIF_RECOVERY) &&
                                !record_type.equals( KRA_LDIF_KEYRECOVERY)) {
                            logger.error("Unknown LDIF record type=" + record_type);
                            return FAILURE;
                        }
                    } else if (input.startsWith(KRA_LDIF_ARCHIVED_BY)) {
                        // extract the data
                        data = input.substring(
                                   KRA_LDIF_ARCHIVED_BY.length() + 1
                                ).trim();

                        // set the record type:
                        //
                        //     * KRA_LDIF_CA_KEY_RECORD
                        //     * KRA_LDIF_TPS_KEY_RECORD
                        //
                        if (data.startsWith(KRA_LDIF_TPS_KEY_RECORD)) {
                            record_type = KRA_LDIF_TPS_KEY_RECORD;
                        } else if (data.startsWith(KRA_LDIF_CA_KEY_RECORD)) {
                            record_type = KRA_LDIF_CA_KEY_RECORD;
                        } else {
                            logger.error("Unable to determine LDIF record type "
                                    + "from data="
                                    + data);
                            return FAILURE;
                        }
                    }

                    // continue adding input lines into this record
                    continue;
                }

                // If record type is unset, then this record is neither
                // an LDIF request record nor an LDIF key record; check
                // to see if it needs to be written out to the target
                // LDIF file or thrown away.
                if ((record_type == null) &&
                        mProcessRequestsAndKeyRecordsOnlyFlag) {

                    // log this information
                    logger.info("Throwing away an LDIF record which is "
                            + "neither a Request nor a Key Record");

                    // clear this LDIF record from the record vector
                    record.clear();

                    // NOTE:  there is no need to reset the record type

                    // begin adding input lines into a new record
                    continue;
                } else if (record_type == null) {
                    // Set record type to specify a "generic" LDIF record
                    record_type = KRA_LDIF_RECORD;
                }

                ldif_record = record.iterator();

                // Process each line of the record:
                //   * If LDIF Record Type for this line is 'valid'
                //     * If KRATOOL Configuration File Parameter is 'true'
                //       * Process this data
                //     * Else If KRATOOL Configuration File Parameter is 'false'
                //       * Pass through this data unchanged
                //   * Else If LDIF Record Type for this line is 'invalid'
                //     * Log error and leave method returning 'false'
                while (ldif_record.hasNext()) {

                    line = ldif_record.next();

                    if (line.startsWith(KRA_LDIF_CN)) {
                        output = output_cn(record_type, line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (line.startsWith(KRA_LDIF_DATE_OF_MODIFY)) {
                        output = output_date_of_modify(record_type, line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (line.startsWith(KRA_LDIF_DN)) {
                        output = output_dn(record_type, line);
                        if (output == null) {
                            return FAILURE;
                        }
                        logger.info(output);
                    } else if (line.startsWith(KRA_LDIF_EXTDATA_KEY_RECORD)) {
                        output = output_extdata_key_record(record_type,
                                                            line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (line.startsWith(KRA_LDIF_EXTDATA_REQUEST_ID)) {
                        output = output_extdata_request_id(record_type,
                                                            line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (line.startsWith(KRA_LDIF_EXTDATA_REQUEST_NOTES)) {
                        output = output_extdata_request_notes(record_type,
                                                               line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (line.startsWith(KRA_LDIF_EXTDATA_REQUEST_TYPE)) {
                        // if one is not already present,
                        // compose and write out the missing
                        // 'extdata_requestnotes' line
                        if (previous_line != null) {
                            create_extdata_request_notes(record_type,
                                    previous_line,
                                    writer);
                        } else {
                            return FAILURE;
                        }

                        // ALWAYS pass through the original
                        // 'extdata-requesttype' line UNCHANGED
                        // so that it is ALWAYS written
                        output = line;
                    } else if (line.startsWith(KRA_LDIF_EXTDATA_SERIAL_NUMBER)) {
                        output = output_extdata_serial_number(record_type,
                                                               line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (line.startsWith(KRA_LDIF_PRIVATE_KEY_DATA)) {
                        output = output_private_key_data(record_type,
                                                          line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (line.startsWith(KRA_LDIF_REQUEST_ID)) {
                        output = output_request_id(record_type, line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (line.startsWith(KRA_LDIF_SERIAL_NO)) {
                        output = output_serial_no(record_type, line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (previous_line != null &&
                               previous_line.startsWith(
                                       KRA_LDIF_EXTDATA_AUTH_TOKEN_USER)) {
                        output = output_extdata_auth_token_user(record_type,
                                                                 line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else if (previous_line != null &&
                               previous_line.startsWith(
                                       KRA_LDIF_EXTDATA_AUTH_TOKEN_USER_DN)) {
                        output = output_extdata_auth_token_user_dn(record_type,
                                                                    line);
                        if (output == null) {
                            return FAILURE;
                        }
                    } else {
                        // Pass through line unchanged
                        output = line;
                    }

                    // Always save a copy of this line
                    previous_line = output;

                    // Always write out the output line and flush the buffer
                    writer.write(output + NEWLINE);
                    writer.flush();
                }

                // clear this LDIF record from the record vector
                record.clear();
            }
            logger.info("FINISHED");
        } catch (IOException exIO) {
            logger.error("line='"
                    + line
                    + "' OR output='"
                    + output
                    + "': "
                    + exIO.getMessage(),
                    exIO);
            return FAILURE;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (writer != null) {
                writer.close();
            }
        }

        return SUCCESS;
    }

    /**************************************/
    /* KRATOOL Config File Parser Methods */
    /**************************************/

    /**
     * This method performs the actual parsing of the KRATOOL config file
     * and initializes how the KRA Record Fields should be processed.
     * <P>
     *
     * @return true if the KRATOOL config file is successfully processed
     */
    private static boolean process_kratool_config_file() {
        BufferedReader reader = null;
        String line = null;
        String name_value_pair[] = null;
        String name = null;
        Boolean value = null;

        // Process each line containing a name/value pair
        // in the KRATOOL config file
        try {
            // Open KRATOOL config file for reading
            logger.info("Loading {}", mKratoolCfgFilename);
            reader = new BufferedReader(
                         new FileReader(mKratoolCfgFilename));

            // Create a hashtable for relevant name/value pairs
            kratoolCfg = new Hashtable<>();

            while ((line = reader.readLine()) != null) {
                if (line.startsWith(KRATOOL_CFG_PREFIX)) {
                    // obtain "name=value" pair
                    name_value_pair = line.split(EQUAL_SIGN);

                    // obtain "name"
                    name = name_value_pair[0];

                    // compute "boolean" value
                    if (name_value_pair[1].equals("true")) {
                        value = Boolean.TRUE;
                    } else {
                        value = Boolean.FALSE;
                    }

                    // store relevant KRA LDIF fields for processing
                    if (name.equals(KRATOOL_CFG_ENROLLMENT_CN)
                            || name.equals(KRATOOL_CFG_ENROLLMENT_DATE_OF_MODIFY)
                            || name.equals(KRATOOL_CFG_ENROLLMENT_DN)
                            || name.equals(KRATOOL_CFG_ENROLLMENT_EXTDATA_KEY_RECORD)
                            || name.equals(KRATOOL_CFG_ENROLLMENT_EXTDATA_REQUEST_NOTES)
                            || name.equals(KRATOOL_CFG_ENROLLMENT_REQUEST_ID)
                            || name.equals(KRATOOL_CFG_CA_KEY_RECORD_CN)
                            || name.equals(KRATOOL_CFG_CA_KEY_RECORD_DATE_OF_MODIFY)
                            || name.equals(KRATOOL_CFG_CA_KEY_RECORD_DN)
                            || name.equals(KRATOOL_CFG_CA_KEY_RECORD_PRIVATE_KEY_DATA)
                            || name.equals(KRATOOL_CFG_CA_KEY_RECORD_SERIAL_NO)
                            || name.equals(KRATOOL_CFG_RECOVERY_CN)
                            || name.equals(KRATOOL_CFG_RECOVERY_DATE_OF_MODIFY)
                            || name.equals(KRATOOL_CFG_RECOVERY_DN)
                            || name.equals(KRATOOL_CFG_RECOVERY_EXTDATA_REQUEST_ID)
                            || name.equals(KRATOOL_CFG_RECOVERY_EXTDATA_REQUEST_NOTES)
                            || name.equals(KRATOOL_CFG_RECOVERY_EXTDATA_SERIAL_NUMBER)
                            || name.equals(KRATOOL_CFG_RECOVERY_REQUEST_ID)
                            || name.equals(KRATOOL_CFG_TPS_KEY_RECORD_CN)
                            || name.equals(KRATOOL_CFG_TPS_KEY_RECORD_DATE_OF_MODIFY)
                            || name.equals(KRATOOL_CFG_TPS_KEY_RECORD_DN)
                            || name.equals(KRATOOL_CFG_TPS_KEY_RECORD_PRIVATE_KEY_DATA)
                            || name.equals(KRATOOL_CFG_TPS_KEY_RECORD_SERIAL_NO)
                            || name.equals(KRATOOL_CFG_KEYGEN_CN)
                            || name.equals(KRATOOL_CFG_KEYGEN_DATE_OF_MODIFY)
                            || name.equals(KRATOOL_CFG_KEYGEN_DN)
                            || name.equals(KRATOOL_CFG_KEYGEN_EXTDATA_KEY_RECORD)
                            || name.equals(KRATOOL_CFG_KEYGEN_EXTDATA_REQUEST_ID)
                            || name.equals(KRATOOL_CFG_KEYGEN_EXTDATA_REQUEST_NOTES)
                            || name.equals(KRATOOL_CFG_KEYGEN_REQUEST_ID)
                            || name.equals(KRATOOL_CFG_KEYRECOVERY_REQUEST_ID )
                            || name.equals(KRATOOL_CFG_KEYRECOVERY_DN )
                            || name.equals(KRATOOL_CFG_KEYRECOVERY_DATE_OF_MODIFY)
                            || name.equals(KRATOOL_CFG_KEYRECOVERY_EXTDATA_REQUEST_ID)
                            || name.equals(KRATOOL_CFG_KEYRECOVERY_CN)
                            || name.equals(KRATOOL_CFG_KEYRECOVERY_EXTDATA_REQUEST_NOTES) ) {
                        kratoolCfg.put(name, value);
                    }
                }
            }

        } catch (FileNotFoundException exKratoolCfgFileNotFound) {
            logger.error("No KRATOOL config file named '"
                    + mKratoolCfgFilename
                    + "' exists: "
                    + exKratoolCfgFileNotFound.getMessage(),
                    exKratoolCfgFileNotFound);
            return FAILURE;
        } catch (IOException exKratoolCfgIO) {
            logger.error("line='"
                    + line
                    + "': "
                    + exKratoolCfgIO.getMessage(),
                    exKratoolCfgIO);
            return FAILURE;
        } catch (PatternSyntaxException exKratoolCfgNameValuePattern) {
            logger.error("line='"
                    + line
                    + "': "
                    + exKratoolCfgNameValuePattern.getMessage(),
                    exKratoolCfgNameValuePattern);
            return FAILURE;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return SUCCESS;
    }

    /************/
    /* KRA Tool */
    /************/

    /**
     * The main KRATool method.
     * <P>
     *
     * @param args KRATool options
     */
    public static void main(String[] args) throws Exception {
        // Variables
        String append_id_offset = null;
        String remove_id_offset = null;
        String process_kra_naming_context_fields = null;
        String process_requests_and_key_records_only = null;
        String use_PKI_security_database_pwdfile = null;
        String keyUnwrapAlgorithmName = null;
        File cfgFile = null;
        File sourceFile = null;
        File sourceDBPath = null;
        File sourceDBPwdfile = null;
        File targetStorageCertFile = null;
        File targetFile = null;
        boolean success = false;

        // Get current date and time
        mDateOfModify = now(DATE_OF_MODIFY_PATTERN);

        // Check that the correct number of arguments were
        // submitted to the program
        if ((args.length != ID_OFFSET_ARGS) &&
                (args.length != (ID_OFFSET_ARGS + 1)) &&
                (args.length != (ID_OFFSET_ARGS + 4)) &&
                (args.length != (ID_OFFSET_ARGS + 5)) &&
                (args.length != (ID_OFFSET_ARGS + 7)) &&
                (args.length != REWRAP_ARGS) &&
                (args.length != (REWRAP_ARGS + 1)) &&
                (args.length != (REWRAP_ARGS + 2)) &&
                (args.length != (REWRAP_ARGS + 3)) &&
                (args.length != (REWRAP_ARGS + 4)) &&
                (args.length != (REWRAP_ARGS + 5)) &&
                (args.length != (REWRAP_ARGS + 6)) &&
                (args.length != (REWRAP_ARGS + 7)) &&
                (args.length != (REWRAP_ARGS + 9)) &&
                (args.length != REWRAP_AND_ID_OFFSET_ARGS) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 1)) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 2)) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 3)) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 4)) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 5)) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 6)) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 7)) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 8)) &&
                (args.length != (REWRAP_AND_ID_OFFSET_ARGS + 9))) {
            logger.error("Incorrect number of arguments");
            printUsage();
            System.exit(0);
        }

        // Process command-line arguments
        for (int i = 0; i < args.length; i += 2) {
            if (args[i].equals(KRATOOL_CFG_FILE)) {
                mKratoolCfgFilename = args[i + 1];
                mMandatoryNameValuePairs++;
            } else if (args[i].equals(SOURCE_LDIF_FILE)) {
                mSourceLdifFilename = args[i + 1];
                mMandatoryNameValuePairs++;
            } else if (args[i].equals(TARGET_LDIF_FILE)) {
                mTargetLdifFilename = args[i + 1];
                mMandatoryNameValuePairs++;
            } else if (args[i].equals(LOG_FILE)) {
                mLogFilename = args[i + 1];
                mMandatoryNameValuePairs++;
            } else if (args[i].equals(SOURCE_NSS_DB_PATH)) {
                mSourcePKISecurityDatabasePath = args[i + 1];
                mRewrapNameValuePairs++;
            } else if (args[i].equals(SOURCE_STORAGE_TOKEN_NAME)) {
                mSourceStorageTokenName = args[i + 1];
                mRewrapNameValuePairs++;
            } else if (args[i].equals(SOURCE_STORAGE_CERT_NICKNAME)) {
                mSourceStorageCertNickname = args[i + 1];
                mRewrapNameValuePairs++;
            } else if (args[i].equals(TARGET_STORAGE_CERTIFICATE_FILE)) {
                mTargetStorageCertificateFilename = args[i + 1];
                mRewrapNameValuePairs++;
            } else if (args[i].equals(SOURCE_NSS_DB_PWDFILE)) {
                mSourcePKISecurityDatabasePwdfile = args[i + 1];
                mPKISecurityDatabasePwdfileNameValuePairs++;
            } else if (args[i].equals(APPEND_ID_OFFSET)) {
                append_id_offset = args[i + 1];
                mAppendIdOffsetNameValuePairs++;
            } else if (args[i].equals(REMOVE_ID_OFFSET)) {
                remove_id_offset = args[i + 1];
                mRemoveIdOffsetNameValuePairs++;
            } else if (args[i].equals(SOURCE_KRA_NAMING_CONTEXT)) {
                mSourceKraNamingContext = args[i + 1];
                mKraNamingContextNameValuePairs++;
            } else if (args[i].equals(TARGET_KRA_NAMING_CONTEXT)) {
                mTargetKraNamingContext = args[i + 1];
                mKraNamingContextNameValuePairs++;
            } else if (args[i].equals(PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY)) {
                mProcessRequestsAndKeyRecordsOnlyFlag = true;
                i -= 1;
            } else if (args[i].contentEquals(KEY_UNWRAP_ALGORITHM)) {
                keyUnwrapAlgorithmName = args[i + 1];
            } else if (args[i].contentEquals(USE_OAEP_RSA_KEY_WRAP)) {
                mUseOAEPKeyWrapAlg = true;
            } else {
                logger.error("Unknown argument: " + args[i]);
                printUsage();
                System.exit(0);
            }
        }

        configureLogger(mLogFilename);

        // Verify that correct number of valid mandatory
        // arguments were submitted to the program
        if (mMandatoryNameValuePairs != MANDATORY_NAME_VALUE_PAIRS ||
                mKratoolCfgFilename == null ||
                mKratoolCfgFilename.length() == 0 ||
                mSourceLdifFilename == null ||
                mSourceLdifFilename.length() == 0 ||
                mTargetLdifFilename == null ||
                mTargetLdifFilename.length() == 0 ||
                mLogFilename == null ||
                mLogFilename.length() == 0) {
            logger.error("Missing mandatory arguments");
            printUsage();
            System.exit(0);
        } else {
            // Check for a valid KRATOOL config file
            cfgFile = new File(mKratoolCfgFilename);
            if (!cfgFile.exists() ||
                    !cfgFile.isFile() ||
                    (cfgFile.length() == 0)) {
                logger.error(mKratoolCfgFilename
                                  + " does NOT exist, is NOT a file, "
                                  + "or is empty");
                printUsage();
                System.exit(0);
            }

            // Check for a valid source LDIF file
            sourceFile = new File(mSourceLdifFilename);
            if (!sourceFile.exists() ||
                    !sourceFile.isFile() ||
                    (sourceFile.length() == 0)) {
                logger.error(mSourceLdifFilename
                                  + " does NOT exist, is NOT a file, "
                                  + "or is empty");
                printUsage();
                System.exit(0);
            }

            // Check that the target LDIF file does NOT exist
            targetFile = new File(mTargetLdifFilename);
            if (targetFile.exists()) {
                logger.error(mTargetLdifFilename + " ALREADY exists");
                printUsage();
                System.exit(0);
            }
        }

        // Check to see that if the 'Rewrap' command-line options were
        // specified, that they are all present and accounted for
        if (mRewrapNameValuePairs > 0) {
            if (mRewrapNameValuePairs != REWRAP_NAME_VALUE_PAIRS ||
                    mSourcePKISecurityDatabasePath == null ||
                    mSourcePKISecurityDatabasePath.length() == 0 ||
                    mSourceStorageTokenName == null ||
                    mSourceStorageTokenName.length() == 0 ||
                    mSourceStorageCertNickname == null ||
                    mSourceStorageCertNickname.length() == 0 ||
                    mTargetStorageCertificateFilename == null ||
                    mTargetStorageCertificateFilename.length() == 0) {
                logger.error("Missing 'Rewrap' arguments");
                printUsage();
                System.exit(0);
            } else {
                // Check for a valid path to the PKI security databases
                sourceDBPath = new File(mSourcePKISecurityDatabasePath);
                if (!sourceDBPath.exists() ||
                        !sourceDBPath.isDirectory()) {
                    logger.error(mSourcePKISecurityDatabasePath
                                      + " does NOT exist or "
                                      + "is NOT a directory");
                    printUsage();
                    System.exit(0);
                }

                // Check for a valid target storage certificate file
                targetStorageCertFile = new File(
                                            mTargetStorageCertificateFilename);
                if (!targetStorageCertFile.exists() ||
                        !targetStorageCertFile.isFile() ||
                        (targetStorageCertFile.length() == 0)) {
                    logger.error(mTargetStorageCertificateFilename
                                      + " does NOT exist, is NOT a file, "
                                      + "or is empty");
                    printUsage();
                    System.exit(0);
                }

                // Mark the 'Rewrap' flag true
                mRewrapFlag = true;
            }
        }

        // Check to see that BOTH append 'ID Offset' command-line options
        // and remove 'ID Offset' command-line options were NOT specified
        // since these two command-line options are mutually exclusive!
        if ((mAppendIdOffsetNameValuePairs > 0) &&
                (mRemoveIdOffsetNameValuePairs > 0)) {
            logger.error("The 'append ID Offset' option "
                                  + "and the 'remove ID Offset' option are "
                                  + "mutually exclusive");
            printUsage();
            System.exit(0);
        }

        // Check to see that if the 'append ID Offset' command-line options
        // were specified, that they are all present and accounted for
        if (mAppendIdOffsetNameValuePairs > 0) {
            if (mAppendIdOffsetNameValuePairs == ID_OFFSET_NAME_VALUE_PAIRS &&
                    append_id_offset != null &&
                    append_id_offset.length() != 0) {
                try {
                    if (!append_id_offset.matches("[0-9]++")) {
                        logger.error(append_id_offset
                                          + " contains non-numeric "
                                          + "characters");
                        printUsage();
                        System.exit(0);
                    } else {
                        mAppendIdOffset = new BigInteger(
                                              append_id_offset);

                        // Mark the 'append ID Offset' flag true
                        mAppendIdOffsetFlag = true;
                    }
                } catch (PatternSyntaxException exAppendPattern) {
                    logger.error("append_id_offset='"
                                      + append_id_offset
                                      + "': "
                                      + exAppendPattern.getMessage(),
                                      exAppendPattern);
                    System.exit(0);
                }
            } else {
                logger.error("Missing 'append ID Offset' arguments");
                printUsage();
                System.exit(0);
            }
        }

        // Check to see that if the 'remove ID Offset' command-line options
        // were specified, that they are all present and accounted for
        if (mRemoveIdOffsetNameValuePairs > 0) {
            if (mRemoveIdOffsetNameValuePairs == ID_OFFSET_NAME_VALUE_PAIRS &&
                    remove_id_offset != null &&
                    remove_id_offset.length() != 0) {
                try {
                    if (!remove_id_offset.matches("[0-9]++")) {
                        logger.error(remove_id_offset
                                          + " contains non-numeric "
                                          + "characters");
                        printUsage();
                        System.exit(0);
                    } else {
                        mRemoveIdOffset = new BigInteger(
                                              remove_id_offset);

                        // Mark the 'remove ID Offset' flag true
                        mRemoveIdOffsetFlag = true;
                    }
                } catch (PatternSyntaxException exRemovePattern) {
                    logger.error("remove_id_offset='"
                                      + remove_id_offset
                                      + "': "
                                      + exRemovePattern.getMessage(),
                                      exRemovePattern);
                    System.exit(0);
                }
            } else {
                logger.error("Missing 'remove ID Offset' arguments");
                printUsage();
                System.exit(0);
            }
        }

        // Make certain that at least one of the "Rewrap", "Append ID Offset",
        // or "Remove ID Offset" options has been specified
        if (!mRewrapFlag &&
                !mAppendIdOffsetFlag &&
                !mRemoveIdOffsetFlag) {
            logger.error("At least one of the 'rewrap', "
                              + "'append ID Offset', or 'remove ID Offset' "
                              + "options MUST be specified");
            printUsage();
            System.exit(0);
        }

        // Check to see that if the OPTIONAL
        // 'PKI Security Database Password File'
        // command-line options were specified,
        // that they are all present and accounted for
        if (mPKISecurityDatabasePwdfileNameValuePairs > 0) {
            if (mPKISecurityDatabasePwdfileNameValuePairs !=
                    PWDFILE_NAME_VALUE_PAIRS ||
                    mSourcePKISecurityDatabasePwdfile == null ||
                    mSourcePKISecurityDatabasePwdfile.length() == 0) {
                logger.error("Missing 'Password File' arguments");
                printUsage();
                System.exit(0);
            } else {
                if (mRewrapFlag) {
                    // Check for a valid source PKI
                    // security database password file
                    sourceDBPwdfile = new
                                      File(mSourcePKISecurityDatabasePwdfile);
                    if (!sourceDBPwdfile.exists() ||
                            !sourceDBPwdfile.isFile() ||
                            (sourceDBPwdfile.length() == 0)) {
                        logger.error(mSourcePKISecurityDatabasePwdfile
                                          + " does NOT exist, is NOT a file, "
                                          + "or is empty");
                        printUsage();
                        System.exit(0);
                    }

                    use_PKI_security_database_pwdfile = SPACE
                                             + SOURCE_NSS_DB_PWDFILE
                                             + SPACE
                                             + TIC
                                             + mSourcePKISecurityDatabasePwdfile
                                             + TIC;

                    mSourcePKISecurityDatabasePwdfileMessage = SPACE
                                             + PLUS
                                             + SPACE
                                             + KRA_LDIF_USED_PWDFILE_MESSAGE;

                    // Mark the 'Password File' flag true
                    mPwdfileFlag = true;
                } else {
                    logger.error("The "
                                      + TIC
                                      + SOURCE_NSS_DB_PWDFILE
                                      + TIC
                                      + " option is ONLY valid when "
                                      + "performing rewrapping");
                    printUsage();
                    System.exit(0);
                }
            }
        } else {
            use_PKI_security_database_pwdfile = "";
            mSourcePKISecurityDatabasePwdfileMessage = "";
        }

        // Check to see that if the OPTIONAL 'KRA Naming Context' command-line
        // options were specified, that they are all present and accounted for
        if (mKraNamingContextNameValuePairs > 0) {
            if (mKraNamingContextNameValuePairs !=
                    NAMING_CONTEXT_NAME_VALUE_PAIRS ||
                    mSourceKraNamingContext == null ||
                    mSourceKraNamingContext.length() == 0 ||
                    mTargetKraNamingContext == null ||
                    mTargetKraNamingContext.length() == 0) {
                logger.error("Both 'source KRA naming context' "
                                  + "and 'target KRA naming context' "
                                  + "options MUST be specified");
                printUsage();
                System.exit(0);
            } else {
                process_kra_naming_context_fields = SPACE
                                                  + SOURCE_KRA_NAMING_CONTEXT
                                                  + SPACE
                                                  + TIC
                                                  + mSourceKraNamingContext
                                                  + TIC
                                                  + SPACE
                                                  + TARGET_KRA_NAMING_CONTEXT
                                                  + SPACE
                                                  + TIC
                                                  + mTargetKraNamingContext
                                                  + TIC;

                mKraNamingContextMessage = SPACE
                                         + PLUS
                                         + SPACE
                                         + KRA_LDIF_SOURCE_NAME_CONTEXT_MESSAGE
                                         + mSourceKraNamingContext
                                         + KRA_LDIF_TARGET_NAME_CONTEXT_MESSAGE
                                         + mTargetKraNamingContext
                                         + TIC;

                // Mark the 'KRA Naming Contexts' flag true
                mKraNamingContextsFlag = true;
            }
        } else {
            process_kra_naming_context_fields = "";
            mKraNamingContextMessage = "";
        }

        // Check for the Key Unwrap Algorithm provided by user.
        // If unprovided, choose DES3 as the default (to maintain consistency with old code)
        if (keyUnwrapAlgorithmName != null) {
            if (keyUnwrapAlgorithmName.equalsIgnoreCase("DES3")) {
                keyUnwrapAlgorithm = SymmetricKey.DES3;
            } else if (keyUnwrapAlgorithmName.equalsIgnoreCase("AES")) {
                keyUnwrapAlgorithm = SymmetricKey.AES;
            } else {
                logger.error("Unsupported key unwrap algorithm: " + keyUnwrapAlgorithmName);
                System.exit(1);
            }
        }

        // Check for OPTIONAL "Process Requests and Key Records ONLY" option
        if (mProcessRequestsAndKeyRecordsOnlyFlag) {
            process_requests_and_key_records_only = SPACE
                                                  + PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY;
            mProcessRequestsAndKeyRecordsOnlyMessage = SPACE + PLUS + SPACE +
                    KRA_LDIF_PROCESS_REQUESTS_AND_KEY_RECORDS_ONLY_MESSAGE;
        } else {
            process_requests_and_key_records_only = "";
            mProcessRequestsAndKeyRecordsOnlyMessage = "";
        }

        // Begin logging progress . . .
        if (mRewrapFlag && mAppendIdOffsetFlag) {
            logger.info("BEGIN "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + SOURCE_NSS_DB_PATH + SPACE
                    + mSourcePKISecurityDatabasePath + SPACE
                    + SOURCE_STORAGE_TOKEN_NAME + SPACE
                    + TIC + mSourceStorageTokenName + TIC + SPACE
                    + SOURCE_STORAGE_CERT_NICKNAME + SPACE
                    + TIC + mSourceStorageCertNickname + TIC + SPACE
                    + TARGET_STORAGE_CERTIFICATE_FILE + SPACE
                    + mTargetStorageCertificateFilename + SPACE
                    + use_PKI_security_database_pwdfile
                    + APPEND_ID_OFFSET + SPACE
                    + append_id_offset
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only
                    + SPACE + KEY_UNWRAP_ALGORITHM + SPACE
                    + keyUnwrapAlgorithmName);
        } else if (mRewrapFlag && mRemoveIdOffsetFlag) {
            logger.info("BEGIN "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + SOURCE_NSS_DB_PATH + SPACE
                    + mSourcePKISecurityDatabasePath + SPACE
                    + SOURCE_STORAGE_TOKEN_NAME + SPACE
                    + TIC + mSourceStorageTokenName + TIC + SPACE
                    + SOURCE_STORAGE_CERT_NICKNAME + SPACE
                    + TIC + mSourceStorageCertNickname + TIC + SPACE
                    + TARGET_STORAGE_CERTIFICATE_FILE + SPACE
                    + mTargetStorageCertificateFilename + SPACE
                    + use_PKI_security_database_pwdfile
                    + REMOVE_ID_OFFSET + SPACE
                    + remove_id_offset
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only
                    + SPACE + KEY_UNWRAP_ALGORITHM + SPACE
                    + keyUnwrapAlgorithmName);
        } else if (mRewrapFlag) {
            logger.info("BEGIN "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + SOURCE_NSS_DB_PATH + SPACE
                    + mSourcePKISecurityDatabasePath + SPACE
                    + SOURCE_STORAGE_TOKEN_NAME + SPACE
                    + TIC + mSourceStorageTokenName + TIC + SPACE
                    + SOURCE_STORAGE_CERT_NICKNAME + SPACE
                    + TIC + mSourceStorageCertNickname + TIC + SPACE
                    + TARGET_STORAGE_CERTIFICATE_FILE + SPACE
                    + mTargetStorageCertificateFilename
                    + use_PKI_security_database_pwdfile
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only
                    + SPACE + KEY_UNWRAP_ALGORITHM + SPACE
                    + keyUnwrapAlgorithmName);
        } else if (mAppendIdOffsetFlag) {
            logger.info("BEGIN "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + APPEND_ID_OFFSET + SPACE
                    + append_id_offset
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only);
        } else if (mRemoveIdOffsetFlag) {
            logger.info("BEGIN "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + REMOVE_ID_OFFSET + SPACE
                    + remove_id_offset
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only);
        }

        // Process the KRATOOL config file
        success = process_kratool_config_file();
        if (!success) {
            logger.error("Unable to process kratool config file");
        } else {
            logger.info("Successfully processed kratool config file");

            // Convert the source LDIF file to a target LDIF file
            success = convert_source_ldif_to_target_ldif();
            if (!success) {
                logger.error("Unable to convert source LDIF file into target LDIF file");
            } else {
                logger.info("Successfully converted source LDIF file into target LDIF file");
            }
        }

        // Finish logging progress
        if (mRewrapFlag && mAppendIdOffsetFlag) {
            logger.info("FINISHED "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + SOURCE_NSS_DB_PATH + SPACE
                    + mSourcePKISecurityDatabasePath + SPACE
                    + SOURCE_STORAGE_TOKEN_NAME + SPACE
                    + TIC + mSourceStorageTokenName + TIC + SPACE
                    + SOURCE_STORAGE_CERT_NICKNAME + SPACE
                    + TIC + mSourceStorageCertNickname + TIC + SPACE
                    + TARGET_STORAGE_CERTIFICATE_FILE + SPACE
                    + mTargetStorageCertificateFilename + SPACE
                    + use_PKI_security_database_pwdfile
                    + APPEND_ID_OFFSET + SPACE
                    + append_id_offset
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only
                    + SPACE + KEY_UNWRAP_ALGORITHM + SPACE
                    + keyUnwrapAlgorithmName);
        } else if (mRewrapFlag && mRemoveIdOffsetFlag) {
            logger.info("FINISHED "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + SOURCE_NSS_DB_PATH + SPACE
                    + mSourcePKISecurityDatabasePath + SPACE
                    + SOURCE_STORAGE_TOKEN_NAME + SPACE
                    + TIC + mSourceStorageTokenName + TIC + SPACE
                    + SOURCE_STORAGE_CERT_NICKNAME + SPACE
                    + TIC + mSourceStorageCertNickname + TIC + SPACE
                    + TARGET_STORAGE_CERTIFICATE_FILE + SPACE
                    + mTargetStorageCertificateFilename + SPACE
                    + use_PKI_security_database_pwdfile
                    + REMOVE_ID_OFFSET + SPACE
                    + remove_id_offset
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only
                    + SPACE + KEY_UNWRAP_ALGORITHM + SPACE
                    + keyUnwrapAlgorithmName);
        } else if (mRewrapFlag) {
            logger.info("FINISHED "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + SOURCE_NSS_DB_PATH + SPACE
                    + mSourcePKISecurityDatabasePath + SPACE
                    + SOURCE_STORAGE_TOKEN_NAME + SPACE
                    + TIC + mSourceStorageTokenName + TIC + SPACE
                    + SOURCE_STORAGE_CERT_NICKNAME + SPACE
                    + TIC + mSourceStorageCertNickname + TIC + SPACE
                    + TARGET_STORAGE_CERTIFICATE_FILE + SPACE
                    + mTargetStorageCertificateFilename
                    + use_PKI_security_database_pwdfile
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only
                    + SPACE + KEY_UNWRAP_ALGORITHM + SPACE
                    + keyUnwrapAlgorithmName);
        } else if (mAppendIdOffsetFlag) {
            logger.info("FINISHED "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + APPEND_ID_OFFSET + SPACE
                    + append_id_offset
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only);
        } else if (mRemoveIdOffsetFlag) {
            logger.info("FINISHED "
                    + KRA_TOOL + SPACE
                    + KRATOOL_CFG_FILE + SPACE
                    + mKratoolCfgFilename + SPACE
                    + SOURCE_LDIF_FILE + SPACE
                    + mSourceLdifFilename + SPACE
                    + TARGET_LDIF_FILE + SPACE
                    + mTargetLdifFilename + SPACE
                    + LOG_FILE + SPACE
                    + mLogFilename + SPACE
                    + REMOVE_ID_OFFSET + SPACE
                    + remove_id_offset
                    + process_kra_naming_context_fields
                    + process_requests_and_key_records_only);
        }
    }
}
