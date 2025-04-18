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
// (C) 2007 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.cms.servlet.connector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dogtagpki.server.authentication.AuthToken;
import org.dogtagpki.server.authorization.AuthzToken;
import org.dogtagpki.server.connector.IRemoteRequest;
import org.dogtagpki.server.kra.KRAEngine;
import org.mozilla.jss.netscape.security.util.PrettyPrintFormat;

import com.netscape.certsrv.authority.IAuthority;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.cms.servlet.base.CMSServlet;
import com.netscape.cms.servlet.common.CMSRequest;
import com.netscape.cmscore.authentication.AuthSubsystem;
import com.netscape.cmscore.request.KeyRequestRepository;
import com.netscape.cmscore.request.Request;
import com.netscape.cmscore.request.RequestQueue;

/**
 * TokenKeyRecoveryServlet
 * handles "key recovery service" requests from the
 * netkey TPS
 *
 * @author Christina Fu (cfu)
 */
//XXX add auditing later
@WebServlet(
        name = "kraTokenKeyRecovery",
        urlPatterns = "/agent/kra/TokenKeyRecovery",
        initParams = {
                @WebInitParam(name="GetClientCert", value="true"),
                @WebInitParam(name="AuthzMgr",      value="BasicAclAuthz"),
                @WebInitParam(name="authority",     value="kra"),
                @WebInitParam(name="ID",            value="kraTokenKeyRecovery"),
                @WebInitParam(name="AuthMgr",       value="certUserDBAuthMgr"),
                @WebInitParam(name="resourceID",    value="certServer.kra.TokenKeyRecovery")
        }
)
public class TokenKeyRecoveryServlet extends CMSServlet {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TokenKeyRecoveryServlet.class);

    private static final long serialVersionUID = -2322410659376501336L;
    private final static String INFO = "TokenKeyRecoveryServlet";
    public final static String PROP_AUTHORITY = "authority";
    protected ServletConfig mConfig = null;
    protected IAuthority mAuthority = null;
    public static int ERROR = 1;
    PrettyPrintFormat pp = new PrettyPrintFormat(":");
    protected AuthSubsystem mAuthSubsystem;

    /**
     * Constructs TokenKeyRecovery servlet.
     *
     */
    public TokenKeyRecoveryServlet() {
        super();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        KRAEngine engine = KRAEngine.getInstance();

        mConfig = config;
        String authority = config.getInitParameter(PROP_AUTHORITY);

        if (authority != null)
            mAuthority = (IAuthority) engine.getSubsystem(authority);

        mAuthSubsystem = engine.getAuthSubsystem();
    }

    /**
     * Returns serlvet information.
     *
     * @return name of this servlet
     */
    @Override
    public String getServletInfo() {
        return INFO;
    }

    /**
     * Process the HTTP request.
     *
     * @param s The URL to decode
     */
    protected String URLdecode(String s) {
        if (s == null)
            return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());

        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);

            if (c == '+') {
                out.write(' ');
            } else if (c == '%') {
                int c1 = Character.digit(s.charAt(++i), 16);
                int c2 = Character.digit(s.charAt(++i), 16);

                out.write((char) (c1 * 16 + c2));
            } else {
                out.write(c);
            }
        } // end for
        return out.toString();
    }

    /*
     * processTokenKeyRecovery
     *   handles netkey key recovery requests
     * input params are:
     *  CUID - the CUID of the old token where the keys/certs were initially for
     *  userid - the userid that belongs to both the old token and the new token
     *  keyid - the keyid in DRM for recovery using keyid
     *  drm_trans_desKey - the des key generated for the NEW token
     *                            wrapped with DRM transport key
     *  cert - the user cert corresponding to the key to be recovered
     *
     * operations:
     *  1. unwrap des key with transport key, then url decode it
     *  2. retrieve user private key
     *  3. wrap user priv key with des key
     *  4. send the following to RA:
     *      * des key wrapped(user priv key)
     *     (note: RA should have kek-wrapped des key from TKS)
     *      * recovery blob (used for recovery)
     *
     * output params are:
     *   status=value0
     *   publicKey=value1
     *   desKey-wrapped-userPrivateKey=value2
     */
    private void processTokenKeyRecovery(HttpServletRequest req,
            HttpServletResponse resp) throws EBaseException {
        KRAEngine engine = KRAEngine.getInstance();
        KeyRequestRepository requestRepository = engine.getKeyRequestRepository();
        RequestQueue queue = engine.getRequestQueue();
        Request thisreq = null;

        //        ConfigStore sconfig = CMS.getConfigStore();
        boolean missingParam = false;
        boolean missingTransAes = false;
        boolean missingTransDes = false;
        boolean missingAesKeyWrapAlg = false;

        String status = "0";

        logger.debug("processTokenKeyRecovery begins:");

        String rCUID = req.getParameter(IRemoteRequest.TOKEN_CUID);
        String rUserid = req.getParameter(IRemoteRequest.KRA_UserId);
        String rKeyid = req.getParameter(IRemoteRequest.KRA_RECOVERY_KEYID);
        String rdesKeyString = req.getParameter(IRemoteRequest.KRA_Trans_DesKey);
        String rCert = req.getParameter(IRemoteRequest.KRA_RECOVERY_CERT);
        
        //RedHat : make sure the key wrap alg is being processed correctly
        String aesKeyWrapAlg = req.getParameter(IRemoteRequest.KRA_Aes_Wrap_Alg);


        String raesKeyString = req.getParameter(IRemoteRequest.KRA_Trans_AesKey);

        //RedHat : make sure the key wrap alg is being processed correctly
        if ((aesKeyWrapAlg == null) || (aesKeyWrapAlg.equals(""))) {
            logger.debug("TokenKeyRecoveryServlet: processTokenKeyRecovery(): missing request parameter: AES-KeyWrap-alg");
            missingAesKeyWrapAlg = true;
        }

        if ((rCUID == null) || (rCUID.equals(""))) {
            logger.warn("TokenKeyRecoveryServlet: processTokenKeyRecovery(): missing request parameter: CUID");
            missingParam = true;
        }

        if ((raesKeyString == null) || (raesKeyString.equals(""))) {
            logger.warn("TokenKeyRecoveryServlet: processTokenKeyRecovery(): missing request parameter: DRM-transportKey-wrapped aes key");
            missingTransAes = true;
        }

        if ((rUserid == null) || (rUserid.equals(""))) {
            logger.warn("TokenKeyRecoveryServlet: processTokenKeyRecovery(): missing request parameter: userid");
            missingParam = true;
        }

        if ((rdesKeyString == null) ||
                (rdesKeyString.equals(""))) {
            logger.warn("TokenKeyRecoveryServlet: processTokenKeyRecovery(): missing request parameter: DRM-transportKey-wrapped des key");
	    missingTransDes = true;
        }
            //We expect either a trans wrapped aes or des key
        if(missingTransAes == true && missingTransDes == true) {
             missingParam = true;
        }

        if (((rCert == null) || (rCert.equals(""))) &&
            ((rKeyid == null) || (rKeyid.equals("")))) {
            logger.warn("TokenKeyRecoveryServlet: processTokenKeyRecovery(): missing request parameter: cert or keyid");
            missingParam = true;
        }

        if (!missingParam) {
            thisreq = requestRepository.createRequest(Request.NETKEY_KEYRECOVERY_REQUEST);

            thisreq.setExtData(Request.REQUESTOR_TYPE, Request.REQUESTOR_NETKEY_RA);
            thisreq.setExtData(Request.NETKEY_ATTR_CUID, rCUID);
            thisreq.setExtData(Request.NETKEY_ATTR_USERID, rUserid);
            thisreq.setExtData(Request.NETKEY_ATTR_DRMTRANS_DES_KEY, rdesKeyString);

            if(!missingTransDes) {
                 thisreq.setExtData(Request.NETKEY_ATTR_DRMTRANS_DES_KEY, rdesKeyString);
             }
            if(!missingTransAes) {
                thisreq.setExtData(Request.NETKEY_ATTR_DRMTRANS_AES_KEY, raesKeyString);
            }

            //RedHat : make sure the key wrap alg is being processed correctly
            if(!missingAesKeyWrapAlg) {
                logger.debug("TokenKeyRecoveryServlet: processTokenKeyRecovery(): aesKeyWrapAlg: " + aesKeyWrapAlg);
                thisreq.setExtData(Request.NETKEY_ATTR_SSKEYGEN_AES_KEY_WRAP_ALG,aesKeyWrapAlg);
            }

            if ((rCert != null) && (!rCert.equals(""))) {
                thisreq.setExtData(Request.NETKEY_ATTR_USER_CERT, rCert);
               logger.debug("TokenKeyRecoveryServlet: processTokenKeyRecovery(): received request parameter: cert");
            }
            if ((rKeyid != null) && (!rKeyid.equals(""))) {
                thisreq.setExtData(Request.NETKEY_ATTR_KEYID, rKeyid);
                logger.debug("TokenKeyRecoveryServlet: processTokenKeyRecovery(): received request parameter: keyid");
            }

            //XXX auto process for netkey
            queue.processRequest(thisreq);
            //	    IService svc = (IService) new TokenKeyRecoveryService(kra);
            //	    svc.serviceRequest(thisreq);

            Integer result = thisreq.getExtDataInInteger(Request.RESULT);
            if (result != null) {
                // sighs!  tps thinks 0 is good, and drm thinks 1 is good
                if (result.intValue() == 1)
                    status = "0";
                else
                    status = result.toString();
            } else
                status = "7";

            logger.debug("processTokenKeyRecovery finished");
        } // ! missingParam

        String value = "";

        resp.setContentType("application/x-www-form-urlencoded");

        String wrappedPrivKeyString = "";
        String publicKeyString = "";
        String ivString = "";
        /* if is RECOVERY_PROTOTYPE
            String recoveryBlobString = "";

            IKeyRecord kr = (IKeyRecord) thisreq.get("keyRecord");
            byte publicKey_b[] = kr.getPublicKeyData();

            BigInteger serialNo = kr.getSerialNumber();

            String serialNumberString =
                org.mozilla.jss.netscape.security.util.Utils.SpecialEncode(serialNo.toByteArray());

            recoveryBlobString = (String)
                thisreq.get("recoveryBlob");
        */

        if (thisreq == null) {
            logger.error("TokenKeyRecoveryServlet::processTokenKeyRecovery() - "
                     + "thisreq is null!");
            throw new EBaseException("thisreq is null");
        }

        publicKeyString = thisreq.getExtDataInString("public_key");
        wrappedPrivKeyString = thisreq.getExtDataInString("wrappedUserPrivate");

        ivString = thisreq.getExtDataInString("iv_s");

        /*
         * zero out fields in request
         */
        thisreq.setExtData("wrappedUserPrivate", "");
        thisreq.setExtData("public_key", "");
        thisreq.setExtData("iv_s", "");
        thisreq.setExtData(Request.NETKEY_ATTR_DRMTRANS_DES_KEY, "");

        /* delete the fields */
        thisreq.deleteExtData("wrappedUserPrivate");
        thisreq.deleteExtData("public_key");
        thisreq.deleteExtData("iv_s");
        thisreq.deleteExtData(Request.NETKEY_ATTR_DRMTRANS_DES_KEY);

        // now that fields are cleared, we can really write to ldap
        thisreq.setExtData("delayLDAPCommit", "false");
        requestRepository.updateRequest(thisreq);

        /*
          if (selectedToken == null)
          status = "4";
        */
        if (!status.equals("0"))
            value = "status=" + status;
        else {
            StringBuffer sb = new StringBuffer();
            sb.append(IRemoteRequest.RESPONSE_STATUS +"=0&");
            sb.append(IRemoteRequest.KRA_RESPONSE_Wrapped_PrivKey +"=");
            sb.append(wrappedPrivKeyString);
            sb.append("&"+ IRemoteRequest.KRA_RESPONSE_PublicKey +"=");
            sb.append(publicKeyString);
            sb.append("&"+ IRemoteRequest.KRA_RESPONSE_IV_Param +"=");
            sb.append(ivString);
            value = sb.toString();

        }
        //logger.debug("ProcessTokenKeyRecovery:outputString.encode " + value);

        try {
            resp.setContentLength(value.length());
            logger.debug("TokenKeyRecoveryServlet:outputString.length " + value.length());
            OutputStream ooss = resp.getOutputStream();
            ooss.write(value.getBytes());
            ooss.flush();
            mRenderResult = false;
        } catch (IOException e) {
            logger.warn("TokenKeyRecoveryServlet: " + e.getMessage(), e);
        }
    }

    /*
     *   For TokenKeyRecovery
     *
     *   input:
     *   CUID=value0
     *   trans-wrapped-desKey=value1
     *
     *   output:
     *   status=value0
     *   publicKey=value1
     *   desKey-wrapped-userPrivateKey=value2
     *   proofOfArchival=value3
     */

    @Override
    public void process(CMSRequest cmsReq) throws EBaseException {
        HttpServletRequest req = cmsReq.getHttpReq();
        HttpServletResponse resp = cmsReq.getHttpResp();

        AuthToken authToken = authenticate(cmsReq);
        AuthzToken authzToken = null;

        try {
            authzToken = authorize(mAclMethod, authToken,
                        mAuthzResourceName, "submit");
        } catch (Exception e) {
        }

        if (authzToken == null) {

            try {
                resp.setContentType("application/x-www-form-urlencoded");
                String value = "unauthorized=";
                logger.debug("TokenKeyRecoveryServlet: Unauthorized");

                resp.setContentLength(value.length());
                OutputStream ooss = resp.getOutputStream();
                ooss.write(value.getBytes());
                ooss.flush();
                mRenderResult = false;
            } catch (Exception e) {
                logger.warn("TokenKeyRecoveryServlet: " + e.getMessage(), e);
            }

            cmsReq.setStatus(CMSRequest.UNAUTHORIZED);
            return;
        }

        // begin Netkey serverSideKeyGen and archival
        logger.debug("TokenKeyRecoveryServlet: processTokenKeyRecovery would be called");
        processTokenKeyRecovery(req, resp);
        return;
        // end Netkey functions

    }
}
