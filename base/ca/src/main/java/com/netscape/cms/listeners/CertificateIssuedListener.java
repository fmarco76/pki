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
package com.netscape.cms.listeners;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Hashtable;

import org.dogtagpki.server.ca.CAConfig;
import org.dogtagpki.server.ca.CAEngine;
import org.dogtagpki.server.ca.CAEngineConfig;
import org.mozilla.jss.netscape.security.x509.X509CertImpl;

import com.netscape.ca.CertificateAuthority;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.EPropertyNotFound;
import com.netscape.certsrv.base.Subsystem;
import com.netscape.certsrv.listeners.EListenersException;
import com.netscape.certsrv.notification.ENotificationException;
import com.netscape.certsrv.notification.EmailResolver;
import com.netscape.certsrv.request.RequestId;
import com.netscape.certsrv.request.RequestListener;
import com.netscape.cms.notification.MailNotification;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.base.ConfigStore;
import com.netscape.cmscore.notification.EmailFormProcessor;
import com.netscape.cmscore.notification.EmailResolverKeys;
import com.netscape.cmscore.notification.EmailTemplate;
import com.netscape.cmscore.notification.ReqCertSANameEmailResolver;
import com.netscape.cmscore.request.Request;

/**
 * a listener for every completed enrollment request
 *
 * Here is a list of available $TOKENs for email notification templates if certificate is successfully issued:
 * <UL>
 * <LI>$InstanceID
 * <LI>$SerialNumber
 * <LI>$HexSerialNumber
 * <LI>$HttpHost
 * <LI>$HttpPort
 * <LI>$RequestId
 * <LI>$IssuerDN
 * <LI>$SubjectDN
 * <LI>$NotBefore
 * <LI>$NotAfter
 * <LI>$SenderEmail
 * <LI>$RecipientEmail
 * </UL>
 *
 * Here is a list of available $TOKENs for email notification templates if certificate request is rejected:
 * <UL>
 * <LI>$RequestId
 * <LI>$InstanceID
 * </UL>
 */
public class CertificateIssuedListener extends RequestListener {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CertificateIssuedListener.class);

    protected final static String PROP_CERT_ISSUED_SUBSTORE = "certIssued";
    protected static final String PROP_ENABLED = "enabled";
    protected final static String PROP_NOTIFY_SUBSTORE = "notification";

    protected final static String PROP_SENDER_EMAIL = "senderEmail";
    protected final static String PROP_EMAIL_SUBJECT = "emailSubject";
    public final static String PROP_EMAIL_TEMPLATE = "emailTemplate";

    protected final static String REJECT_FILE_NAME = "certRequestRejected";

    private boolean mEnabled = false;
    private String mSenderEmail = null;
    private String mSubject = null;
    private String mSubject_Success = null;
    private String mFormPath = null;
    private String mRejectPath = null;
    private Hashtable<String, Object> mContentParams = new Hashtable<>();

    private CAConfig mConfig;
    private DateFormat mDateFormat = null;
    private String mHttpHost = null;
    private String mHttpPort = null;
    private RequestId mReqId = null;

    public CertificateIssuedListener() {
    }

    @Override
    public void init(Subsystem sub, ConfigStore config)
            throws EListenersException, EPropertyNotFound, EBaseException {

        CAEngine engine = CAEngine.getInstance();
        CAEngineConfig cs = engine.getConfig();

        CertificateAuthority ca = engine.getCA();
        mConfig = ca.getConfigStore();

        ConfigStore nc = mConfig.getSubStore(PROP_NOTIFY_SUBSTORE, ConfigStore.class);
        ConfigStore rc = nc.getSubStore(PROP_CERT_ISSUED_SUBSTORE, ConfigStore.class);

        mEnabled = rc.getBoolean(PROP_ENABLED, false);

        mSenderEmail = rc.getString(PROP_SENDER_EMAIL);
        if (mSenderEmail == null) {
            throw new EListenersException(CMS.getLogMessage("NO_NOTIFY_SENDER_EMAIL_CONFIG_FOUND"));
        }

        mFormPath = rc.getString(PROP_EMAIL_TEMPLATE);
        String mDir = null;

        // figure out the reject email path: same dir as form path,
        //		same ending as form path
        int ridx = mFormPath.lastIndexOf(File.separator);

        if (ridx == -1) {
            logger.debug("CertificateIssuedListener: file separator: " + File.separator
                    +
                    " not found. Use default /");
            ridx = mFormPath.lastIndexOf("/");
            mDir = mFormPath.substring(0, ridx + 1);
        } else {
            mDir = mFormPath.substring(0, ridx +
                            File.separator.length());
        }
        logger.debug("CertificateIssuedListener: template file directory: " + mDir);
        mRejectPath = mDir + REJECT_FILE_NAME;
        if (mFormPath.endsWith(".html"))
            mRejectPath += ".html";
        else if (mFormPath.endsWith(".HTML"))
            mRejectPath += ".HTML";
        else if (mFormPath.endsWith(".htm"))
            mRejectPath += ".htm";
        else if (mFormPath.endsWith(".HTM"))
            mRejectPath += ".HTM";

        logger.debug("CertificateIssuedListener: Reject file path: " + mRejectPath);

        mDateFormat = DateFormat.getDateTimeInstance();

        mSubject_Success = rc.getString(PROP_EMAIL_SUBJECT,
                    "Your Certificate Request");
        mSubject = new String(mSubject_Success);

        // form the cert retrieval URL for the notification
        mHttpHost = cs.getHostname();
        mHttpPort = engine.getEESSLPort();

        // register for this event listener
        engine.registerRequestListener(this);
    }

    @Override
    public void accept(Request r) {
        logger.debug("CertificateIssuedListener: accept " +
                r.getRequestId().toString());
        if (mEnabled != true)
            return;

        mSubject = mSubject_Success;
        mReqId = r.getRequestId();
        // is it rejected?
        String rs = r.getRequestStatus().toString();

        if (rs.equals("rejected")) {
            logger.debug("CertificateIssuedListener: Request status: " + rs);
            rejected(r);
            return;
        }

        logger.debug("CertificateIssuedListener: accept check status ");

        // check if it is profile request
        String profileId = r.getExtDataInString(Request.PROFILE_ID);

        // check if request failed.
        if (profileId == null) {
            if (r.getExtDataInInteger(Request.RESULT) == null)
                return;
            if ((r.getExtDataInInteger(Request.RESULT)).equals(Request.RES_ERROR)) {
                logger.debug("CertificateIssuedListener: Request errored. " +
                        "No need to email notify for enrollment request id " +
                        mReqId);
                return;
            }
        }
        String requestType = r.getRequestType();

        if (requestType.equals(Request.ENROLLMENT_REQUEST) ||
                requestType.equals(Request.RENEWAL_REQUEST)) {
            logger.debug("accept() enrollment/renewal request...");
            // Get the certificate from the request
            X509CertImpl issuedCert[] = null;

            // handle profile-based enrollment's notification
            if (profileId == null) {
                issuedCert = r.getExtDataInCertArray(Request.ISSUED_CERTS);
            } else {
                issuedCert = new X509CertImpl[1];
                issuedCert[0] = r.getExtDataInCert(Request.REQUEST_ISSUED_CERT);
            }

            if (issuedCert != null) {
                logger.debug("CertificateIssuedListener: Sending email notification..");

                // do we have an email to send?
                String mEmail = null;
                EmailResolverKeys keys = new EmailResolverKeys();

                try {
                    keys.set(EmailResolverKeys.KEY_REQUEST, r);
                    keys.set(EmailResolverKeys.KEY_CERT, issuedCert[0]);

                } catch (EBaseException e) {
                    logger.warn("CertificateIssuedListener: setting email resolver: "+ e.getMessage(), e);
                    logger.warn(CMS.getLogMessage("LISTENERS_CERT_ISSUED_SET_RESOLVER", e.toString()));
                }

                EmailResolver er = new ReqCertSANameEmailResolver();

                try {
                    mEmail = er.getEmail(keys);

                } catch (ENotificationException e) {
                    logger.warn("CertificateIssuedListener: getting email: " + e.getMessage(), e);
                    logger.warn(CMS.getLogMessage("LISTENERS_CERT_ISSUED_EXCEPTION", e.toString()));

                } catch (EBaseException e) {
                    logger.warn("CertificateIssuedListener: getting email: " + e.getMessage(), e);
                    logger.warn(CMS.getLogMessage("LISTENERS_CERT_ISSUED_EXCEPTION", e.toString()));

                } catch (Exception e) {
                    logger.warn("CertificateIssuedListener: getting email: " + e.getMessage(), e);
                    logger.warn(CMS.getLogMessage("LISTENERS_CERT_ISSUED_EXCEPTION", e.toString()));
                }

                // now we can mail
                if ((mEmail != null) && (!mEmail.equals(""))) {
                    logger.debug("CertificateIssuedListener: found email: "+ mEmail);
                    mailIt(mEmail, issuedCert);

                } else {
                    logger.warn("CertificateIssuedListener: failed finding email");
                    logger.warn(CMS.getLogMessage("LISTENERS_CERT_ISSUED_NOTIFY_ERROR",
                                    issuedCert[0].getSerialNumber().toString(), mReqId.toString()));
                    // send failure notification to "sender"
                    logger.debug("CertificateIssuedListener: notifying sender...");
                    mSubject = "Certificate Issued notification undeliverable";
                    mailIt(mSenderEmail, issuedCert);
                }
            }
        }
    }

    private void mailIt(String mEmail, X509CertImpl issuedCert[]) {
        CAEngine engine = CAEngine.getInstance();
        MailNotification mn = engine.getMailNotification();

        mn.setFrom(mSenderEmail);
        mn.setTo(mEmail);
        mn.setSubject(mSubject);

        /*
         * get template file from disk
         */
        EmailTemplate template = new EmailTemplate(mFormPath);

        /*
         * parse and process the template
         */
        if (!template.init()) {
            return;
        }

        buildContentParams(issuedCert, mEmail);
        EmailFormProcessor et = new EmailFormProcessor();
        String c = et.getEmailContent(template.toString(), mContentParams);

        if (template.isHTML()) {
            mn.setContentType("text/html");
        }
        mn.setContent(c);

        try {
            mn.sendNotification();

        } catch (ENotificationException e) {
            logger.warn("CertificateIssuedListener: mailIt: " + e.getMessage(), e);
            logger.warn(CMS.getLogMessage("OPERATION_ERROR", e.toString()));

        } catch (IOException e) {
            logger.warn("CertificateIssuedListener: mailIt: " + e.getMessage(), e);
            logger.warn(CMS.getLogMessage("OPERATION_ERROR", e.toString()));
        }
    }

    private void rejected(Request r) {
        // do we have an email to send?
        String mEmail = null;
        EmailResolverKeys keys = new EmailResolverKeys();

        try {
            keys.set(EmailResolverKeys.KEY_REQUEST, r);
        } catch (EBaseException e) {
            logger.warn(CMS.getLogMessage("LISTENERS_CERT_ISSUED_SET_RESOLVER", e.toString()), e);
        }

        EmailResolver er = new ReqCertSANameEmailResolver();

        try {
            mEmail = er.getEmail(keys);

        } catch (ENotificationException e) {
            logger.warn(CMS.getLogMessage("OPERATION_ERROR", e.toString()), e);

        } catch (EBaseException e) {
            logger.warn(CMS.getLogMessage("OPERATION_ERROR", e.toString()), e);

        } catch (Exception e) {
            logger.warn(CMS.getLogMessage("OPERATION_ERROR", e.toString()), e);
        }

        // now we can mail
        if ((mEmail != null) && !mEmail.equals("")) {
            CAEngine engine = CAEngine.getInstance();
            MailNotification mn = engine.getMailNotification();

            mn.setFrom(mSenderEmail);
            mn.setTo(mEmail);
            mn.setSubject(mSubject);

            /*
             * get rejection file from disk
             */
            EmailTemplate template = new EmailTemplate(mRejectPath);

            if (!template.init()) {
                return;
            }

            if (template.isHTML()) {
                mn.setContentType("text/html");
            }

            // build some token data
            mContentParams.put(EmailFormProcessor.TOKEN_ID, mConfig.getName());
            mReqId = r.getRequestId();
            mContentParams.put(EmailFormProcessor.TOKEN_REQUEST_ID, mReqId.toString());
            EmailFormProcessor et = new EmailFormProcessor();
            String c = et.getEmailContent(template.toString(), mContentParams);

            mn.setContent(c);

            try {
                mn.sendNotification();

            } catch (ENotificationException e) {
                // already logged, lets audit
                logger.warn(CMS.getLogMessage("OPERATION_ERROR", e.toString()), e);

            } catch (IOException e) {
                logger.warn(CMS.getLogMessage("OPERATION_ERROR", e.toString()), e);
            }

        } else {
            logger.warn(CMS.getLogMessage("LISTENERS_CERT_ISSUED_REJECTION_NOTIFICATION", mReqId.toString()));
        }
    }

    private void buildContentParams(X509CertImpl issuedCert[], String mEmail) {
        mContentParams.put(EmailFormProcessor.TOKEN_ID, mConfig.getName());
        mContentParams.put(EmailFormProcessor.TOKEN_SERIAL_NUM,
                issuedCert[0].getSerialNumber().toString());
        mContentParams.put(EmailFormProcessor.TOKEN_HEX_SERIAL_NUM,
                Long.toHexString(issuedCert[0].getSerialNumber().longValue()));
        mContentParams.put(EmailFormProcessor.TOKEN_REQUEST_ID,
                mReqId.toString());
        mContentParams.put(EmailFormProcessor.TOKEN_HTTP_HOST, mHttpHost);
        mContentParams.put(EmailFormProcessor.TOKEN_HTTP_PORT, mHttpPort);
        mContentParams.put(EmailFormProcessor.TOKEN_ISSUER_DN,
                issuedCert[0].getIssuerName().toString());
        mContentParams.put(EmailFormProcessor.TOKEN_SUBJECT_DN,
                issuedCert[0].getSubjectName().toString());

        Date date = issuedCert[0].getNotAfter();

        mContentParams.put(EmailFormProcessor.TOKEN_NOT_AFTER,
                mDateFormat.format(date));

        date = issuedCert[0].getNotBefore();
        mContentParams.put(EmailFormProcessor.TOKEN_NOT_BEFORE,
                mDateFormat.format(date));

        mContentParams.put(EmailFormProcessor.TOKEN_SENDER_EMAIL, mSenderEmail);
        mContentParams.put(EmailFormProcessor.TOKEN_RECIPIENT_EMAIL, mEmail);
        // ... and more
    }

    /**
     * sets the configurable parameters
     */
    @Override
    public void set(String name, String val) {
        if (name.equalsIgnoreCase(PROP_ENABLED)) {
            if (val.equalsIgnoreCase("true")) {
                mEnabled = true;
            } else {
                mEnabled = false;
            }
        } else if (name.equalsIgnoreCase(PROP_SENDER_EMAIL)) {
            mSenderEmail = val;
        } else if (name.equalsIgnoreCase(PROP_EMAIL_SUBJECT)) {
            mSubject_Success = val;
            mSubject = mSubject_Success;
        } else if (name.equalsIgnoreCase(PROP_EMAIL_TEMPLATE)) {
            mFormPath = val;
        } else {
            logger.warn(CMS.getLogMessage("LISTENERS_CERT_ISSUED_SET"));
        }
    }
}
