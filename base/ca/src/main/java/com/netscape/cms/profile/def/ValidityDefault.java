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
package com.netscape.cms.profile.def;

import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import org.dogtagpki.server.ca.CAEngine;
import org.mozilla.jss.netscape.security.x509.CertificateValidity;
import org.mozilla.jss.netscape.security.x509.X509CertImpl;
import org.mozilla.jss.netscape.security.x509.X509CertInfo;

import com.netscape.ca.CertificateAuthority;
import com.netscape.certsrv.profile.EProfileException;
import com.netscape.certsrv.property.Descriptor;
import com.netscape.certsrv.property.EPropertyException;
import com.netscape.certsrv.property.IDescriptor;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.request.Request;

/**
 * This class implements an enrollment default policy
 * that populates a server-side configurable validity
 * into the certificate template.
 *
 * @version $Revision$, $Date$
 */
public class ValidityDefault extends EnrollDefault {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ValidityDefault.class);

    public static final String CONFIG_RANGE = "range";
    public static final String CONFIG_RANGE_UNIT = "rangeUnit";
    public static final String CONFIG_START_TIME = "startTime";

    public static final String VAL_NOT_BEFORE = "notBefore";
    public static final String VAL_NOT_AFTER = "notAfter";

    public static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    public ValidityDefault() {
        super();
        addConfigName(CONFIG_RANGE);
        addConfigName(CONFIG_RANGE_UNIT);
        addConfigName(CONFIG_START_TIME);
        addValueName(VAL_NOT_BEFORE);
        addValueName(VAL_NOT_AFTER);
    }

    @Override
    public void setConfig(String name, String value)
            throws EPropertyException {
        if (name.equals(CONFIG_RANGE)) {
            try {
                Integer.parseInt(value);
            } catch (Exception e) {
                throw new EPropertyException(CMS.getUserMessage(
                            "CMS_INVALID_PROPERTY", CONFIG_RANGE));
            }
        } else if (name.equals(CONFIG_START_TIME)) {
            try {
                Integer.parseInt(value);
            } catch (Exception e) {
                throw new EPropertyException(CMS.getUserMessage(
                            "CMS_INVALID_PROPERTY", CONFIG_START_TIME));
            }
        }
        super.setConfig(name, value);
    }

    @Override
    public IDescriptor getConfigDescriptor(Locale locale, String name) {
        if (name.equals(CONFIG_RANGE)) {
            return new Descriptor(IDescriptor.STRING,
                    null,
                    "7305",
                    CMS.getUserMessage(locale,
                            "CMS_PROFILE_VALIDITY_RANGE"));
        } else if (name.equals(CONFIG_RANGE_UNIT)) {
            return new Descriptor(IDescriptor.STRING,
                    null,
                    "day",
                    CMS.getUserMessage(locale,
                            "CMS_PROFILE_VALIDITY_RANGE_UNIT"));
        } else if (name.equals(CONFIG_START_TIME)) {
            return new Descriptor(IDescriptor.STRING,
                    null,
                    "60", /* 1 minute */
                    CMS.getUserMessage(locale,
                            "CMS_PROFILE_VALIDITY_START_TIME"));
        } else {
            return null;
        }
    }

    @Override
    public IDescriptor getValueDescriptor(Locale locale, String name) {
        if (name.equals(VAL_NOT_BEFORE)) {
            return new Descriptor(IDescriptor.STRING, null, null,
                    CMS.getUserMessage(locale, "CMS_PROFILE_NOT_BEFORE"));
        } else if (name.equals(VAL_NOT_AFTER)) {
            return new Descriptor(IDescriptor.STRING, null, null,
                    CMS.getUserMessage(locale, "CMS_PROFILE_NOT_AFTER"));
        } else {
            return null;
        }
    }

    @Override
    public void setValue(String name, Locale locale,
            X509CertInfo info, String value)
            throws EPropertyException {
        if (name == null) {
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        }
        if (value == null || value.equals("")) {
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        }
        if (name.equals(VAL_NOT_BEFORE)) {
            SimpleDateFormat formatter =
                    new SimpleDateFormat(DATE_FORMAT);
            ParsePosition pos = new ParsePosition(0);
            Date date = formatter.parse(value, pos);
            CertificateValidity validity = null;

            try {
                validity = (CertificateValidity)
                        info.get(X509CertInfo.VALIDITY);
                validity.set(CertificateValidity.NOT_BEFORE,
                        date);
            } catch (Exception e) {
                logger.error("ValidityDefault: setValue " + e.getMessage(), e);
                throw new EPropertyException(CMS.getUserMessage(
                            locale, "CMS_INVALID_PROPERTY", name));
            }
        } else if (name.equals(VAL_NOT_AFTER)) {
            SimpleDateFormat formatter =
                    new SimpleDateFormat(DATE_FORMAT);
            ParsePosition pos = new ParsePosition(0);
            Date date = formatter.parse(value, pos);
            CertificateValidity validity = null;

            try {
                validity = (CertificateValidity)
                        info.get(X509CertInfo.VALIDITY);
                validity.set(CertificateValidity.NOT_AFTER,
                        date);
            } catch (Exception e) {
                logger.error("ValidityDefault: setValue " + e.getMessage(), e);
                throw new EPropertyException(CMS.getUserMessage(
                            locale, "CMS_INVALID_PROPERTY", name));
            }
        } else {
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        }
    }

    @Override
    public String getValue(String name, Locale locale,
            X509CertInfo info)
            throws EPropertyException {

        if (name == null)
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));

        if (name.equals(VAL_NOT_BEFORE)) {
            SimpleDateFormat formatter =
                    new SimpleDateFormat(DATE_FORMAT);
            CertificateValidity validity = null;

            try {
                validity = (CertificateValidity)
                        info.get(X509CertInfo.VALIDITY);
                return formatter.format((Date)
                        validity.get(CertificateValidity.NOT_BEFORE));
            } catch (Exception e) {
                logger.error("ValidityDefault: getValue " + e.getMessage(), e);
            }
            throw new EPropertyException("Invalid value");
        } else if (name.equals(VAL_NOT_AFTER)) {
            SimpleDateFormat formatter =
                    new SimpleDateFormat(DATE_FORMAT);
            CertificateValidity validity = null;

            try {
                validity = (CertificateValidity)
                        info.get(X509CertInfo.VALIDITY);
                return formatter.format((Date)
                        validity.get(CertificateValidity.NOT_AFTER));
            } catch (Exception e) {
                logger.error("ValidityDefault: getValue " + e.getMessage(), e);
            }
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        } else {
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        }

    }

    @Override
    public String getText(Locale locale) {
        return CMS.getUserMessage(locale, "CMS_PROFILE_DEF_VALIDITY",
                getConfig(CONFIG_RANGE));
    }

    public int convertRangeUnit(String unit) throws Exception {

        if (unit.equals("year")) {
            return Calendar.YEAR;

        } else if (unit.equals("month")) {
            return Calendar.MONTH;

        } else if (unit.equals("day") || unit.equals("")) {
            return Calendar.DAY_OF_YEAR;

        } else if (unit.equals("hour")) {
            return Calendar.HOUR_OF_DAY;

        } else if (unit.equals("minute")) {
            return Calendar.MINUTE;

        } else {
            throw new Exception("Invalid range unit: " + unit);
        }
    }

    /**
     * Populates the request with this policy default.
     */
    @Override
    public void populate(Request request, X509CertInfo info)
            throws EProfileException {

        logger.debug("ValidityDefault: Calculating default cert validity");
        CAEngine engine = CAEngine.getInstance();

        // always + 60 seconds
        String startTimeStr = getConfig(CONFIG_START_TIME);
        logger.debug("ValidityDefault: start time: " + startTimeStr);
        try {
            startTimeStr = mapPattern(request, startTimeStr);
        } catch (IOException e) {
            logger.warn("ValidityDefault: populate " + e.getMessage(), e);
        }

        if (startTimeStr == null || startTimeStr.equals("")) {
            startTimeStr = "60";
        }
        long startTime = Long.parseLong(startTimeStr);

        Date notBefore = new Date(new Date().getTime() + (1000 * startTime));
        logger.info("ValidityDefault: - not before: " + notBefore);

        String rangeStr = getConfig(CONFIG_RANGE, "7305");
        logger.debug("ValidityDefault: range: " + rangeStr);

        int range;
        try {
            rangeStr = mapPattern(request, rangeStr);
            range = Integer.parseInt(rangeStr);
        } catch (IOException e) {
            logger.error("ValidityDefault: " + e.getMessage(), e);
            throw new EProfileException(CMS.getUserMessage(
                        getLocale(request), "CMS_INVALID_PROPERTY", CONFIG_RANGE));
        }

        String rangeUnitStr = getConfig(CONFIG_RANGE_UNIT, "day");
        logger.debug("ValidityDefault: range unit: " + rangeUnitStr);

        int rangeUnit;
        try {
            rangeUnit = convertRangeUnit(rangeUnitStr);
        } catch (Exception e) {
            logger.error("ValidityDefault: " + e.getMessage(), e);
            throw new EProfileException(CMS.getUserMessage(
                        getLocale(request), "CMS_INVALID_PROPERTY", CONFIG_RANGE_UNIT));
        }

        // calculate the end of validity range
        Calendar date = Calendar.getInstance();
        date.setTime(notBefore);
        date.add(rangeUnit, range);

        Date notAfter = date.getTime();
        logger.info("ValidityDefault: - not after: " + notAfter);

        // check and fix notAfter if needed
        // installAdjustValidity is set during installation if needed
        boolean adjustValidity =
                request.getExtDataInBoolean("installAdjustValidity", false);
        if (adjustValidity) {
            logger.debug("ValidityDefault: populate: adjustValidity is true");
            CertificateAuthority ca = engine.getCA();
            try {
                X509CertImpl caCert = ca.getCACert();
                Date caNotAfter = caCert.getNotAfter();
                if (notAfter.after(caNotAfter)) {
                    notAfter = caNotAfter;
                    logger.info("ValidityDefault: populate: resetting notAfter to caNotAfter");
                }
            } catch (Exception e) {
                throw new EProfileException(
                        "Unable to get ca certificate: " + e.getMessage(), e);
            }
        }

        CertificateValidity validity = new CertificateValidity(notBefore, notAfter);

        try {
            info.set(X509CertInfo.VALIDITY, validity);
        } catch (Exception e) {
            // failed to insert subject name
            logger.error("ValidityDefault: populate " + e.getMessage(), e);
            throw new EProfileException(CMS.getUserMessage(
                        getLocale(request), "CMS_INVALID_PROPERTY", X509CertInfo.VALIDITY));
        }
    }
}
