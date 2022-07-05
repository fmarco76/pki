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
package com.netscape.admin.certsrv.config;

import javax.swing.JFrame;

import com.netscape.admin.certsrv.CMSAdminResources;
import com.netscape.admin.certsrv.connection.AdminConnection;
import com.netscape.certsrv.common.DestDef;
import com.netscape.certsrv.common.ScopeDef;

/**
 * Publishing Rule Plugin Selection Dialog
 *
 * @author Jack Pan-Chen
 * @version $Revision$, $Date$
 * @see com.netscape.admin.certsrv.config
 * @deprecated The PKI console will be removed once there are CLI equivalents of desired console features.
 */
@Deprecated
public class RulePluginSelectionDialog extends PluginSelectionDialog
{
    /*==========================================================
     * variables
     *==========================================================*/
    private static final String PREFIX = "RULESELECTIONDIALOG";
    private static final String CAHELPINDEX =
      "configuration-ca-add-rulerule-dbox-help";
    private static final String RAHELPINDEX =
      "configuration-ra-add-rulerule-dbox-help";

    /*==========================================================
     * constructors
     *==========================================================*/
    public RulePluginSelectionDialog(
			JFrame parent,
			AdminConnection conn,
			String dest,
			CMSPluginInstanceTab pluginType)
	{
        super(PREFIX, parent,conn, dest, pluginType);
        mScope = ScopeDef.SC_RULE_IMPLS;
        mInstanceScope = ScopeDef.SC_RULE_RULES;
        mImageName = CMSAdminResources.IMAGE_RULE_PLUGIN;
        if (dest.equals(DestDef.DEST_RA_RULE_ADMIN))
            mHelpToken = RAHELPINDEX;
        else
            mHelpToken = CAHELPINDEX;
        setDisplay();
    }

    /*==========================================================
     * EVENT HANDLER METHODS
     *==========================================================*/


}
