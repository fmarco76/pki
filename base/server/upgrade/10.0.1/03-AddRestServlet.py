# Authors:
#     Ade Lee <alee@redhat.com>
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation; version 2 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along
# with this program; if not, write to the Free Software Foundation, Inc.,
# 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Copyright (C) 2013 Red Hat, Inc.
# All rights reserved.
#

from __future__ import absolute_import
import os
from lxml import etree as ET

import pki
import pki.server.upgrade


class AddRestServlet(pki.server.upgrade.PKIServerUpgradeScriptlet):

    restServicesServletData = """
        <servlet>
            <servlet-name> rest-services </servlet-name>
            <servlet-class> com.netscape.cms.servlet.base.RESTServlet </servlet-class>
        </servlet>"""

    restServicesMappingData = """
        <servlet-mapping>
            <servlet-name>  rest-services </servlet-name>
            <url-pattern>   /rest/*  </url-pattern>
        </servlet-mapping> """

    def __init__(self):
        super(AddRestServlet, self).__init__()
        self.message = 'Add dummy REST servlet to upgraded Dogtag 9 instances'
        self.doc = None
        self.root = None

    def upgrade_subsystem(self, instance, subsystem):
        if instance.version >= 10:
            return

        web_xml = os.path.join(
            instance.base_dir,
            'webapps', subsystem.name,
            'WEB-INF', 'web.xml')

        self.backup(web_xml)

        self.doc = ET.parse(web_xml)
        self.root = self.doc.getroot()
        self.add_rest_services_servlet()

        self.doc.write(web_xml)

    def add_rest_services_servlet(self):
        # add rest-services servlet and mapping
        found = False
        index = 0
        for servlet in self.doc.findall('.//servlet'):
            name = servlet.find('servlet-name').text.strip()
            if name == 'rest-services':
                found = True
            if name == 'services':
                index = self.root.index(servlet) + 1
        if not found:
            servlet = ET.fromstring(self.restServicesServletData)
            self.root.insert(index, servlet)

        found = False
        index = 0
        for mapping in self.doc.findall('.//servlet-mapping'):
            name = mapping.find('servlet-name').text.strip()
            if name == 'rest-services':
                found = True
            if name == 'services':
                index = self.root.index(mapping) + 1
        if not found:
            mapping = ET.fromstring(self.restServicesMappingData)
            self.root.insert(index, mapping)
