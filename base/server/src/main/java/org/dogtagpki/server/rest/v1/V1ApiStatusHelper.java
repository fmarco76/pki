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
// (C) 2025 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---

package org.dogtagpki.server.rest.v1;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;

/**
 * Helper class for v1 REST API status checking.
 * Centralizes the logic for reading build-time defaults and handling disabled/deprecated states.
 */
public class V1ApiStatusHelper {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(V1ApiStatusHelper.class);

    /**
     * Read the default v1 API status from build.properties.
     * This value is set at build time via Maven resource filtering.
     *
     * @return The default status: "enabled", "deprecated", "disabled", or "dropped"
     */
    public static String getDefaultV1ApiStatus() {
        try (InputStream is = V1ApiStatusHelper.class.getClassLoader().getResourceAsStream("build.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("v1.api.status.default", "enabled");
            }
        } catch (IOException e) {
            logger.warn("V1ApiStatusHelper: Unable to read build.properties, defaulting to enabled", e);
        }
        return "enabled";
    }

    /**
     * Check v1 API status and handle disabled/deprecated states.
     *
     * @param subsystemName The name of the subsystem (e.g., "CA", "KRA", "PKI")
     * @param classes The set of classes to modify if disabled
     * @param subsystemLogger The logger to use for warnings
     * @return true if the Application constructor should return early (disabled state), false otherwise
     */
    public static boolean checkV1ApiStatus(String subsystemName, Set<Class<?>> classes, org.slf4j.Logger subsystemLogger) {
        // Use build-time default unless overridden by system property
        String v1ApiStatus = System.getProperty("v1.api.status", getDefaultV1ApiStatus());

        if ("disabled".equals(v1ApiStatus)) {
            subsystemLogger.warn("======================================================================");
            subsystemLogger.warn(subsystemName + " v1 REST API has been DISABLED.");
            subsystemLogger.warn("All v1 endpoints will return HTTP 410 Gone.");
            subsystemLogger.warn("Please use v2 API instead.");
            subsystemLogger.warn("======================================================================");
            // Register only the disabled resource which returns clean error messages
            classes.add(V1ApiDisabledResource.class);
            return true; // return early
        }

        if ("deprecated".equals(v1ApiStatus)) {
            subsystemLogger.warn("======================================================================");
            subsystemLogger.warn("WARNING: v1 REST API is DEPRECATED and will be removed in a future release.");
            subsystemLogger.warn("Please migrate to v2 API as soon as possible.");
            subsystemLogger.warn("======================================================================");
        }

        return false; // continue normally
    }
}
