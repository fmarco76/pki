//
// Copyright Red Hat, Inc.
//
// SPDX-License-Identifier: GPL-2.0-or-later
//
package org.dogtagpki.server.rest.v2.filters;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;

public class SelfTestACL extends ACLFilter {
    private static final long serialVersionUID = 1L;
    private static final String READ = "selftests.read";
    private static final String EXECUTE = "selftests.execute";


    @Override
    public void init() throws ServletException {
        setAcl(READ);
        Map<String, String> aclMap = new HashMap<>();
        aclMap.put("POST:", EXECUTE);
        aclMap.put("POST:run", EXECUTE);
        aclMap.put("POST:{}/run", EXECUTE);
        setAclMap(aclMap);

    }

}
