//
// Copyright Red Hat, Inc.
//
// SPDX-License-Identifier: GPL-2.0-or-later
//
package org.dogtagpki.server.ca.rest.v2;

import javax.servlet.annotation.WebServlet;

import org.dogtagpki.server.rest.v2.UserServlet;

/**
 * @author Marco Fargetta {@literal <mfargett@redhat.com>}
 */
@WebServlet(
        name = "caUser",
        urlPatterns = "/v2/admin/users/*")
public class CAUserServlet extends UserServlet {
    private static final long serialVersionUID = 1L;
}
