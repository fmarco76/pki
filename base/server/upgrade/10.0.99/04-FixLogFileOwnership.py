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
import pki.server.upgrade


class FixLogFileOwnership(pki.server.upgrade.PKIServerUpgradeScriptlet):

    def __init__(self):
        super(FixLogFileOwnership, self).__init__()
        self.message = 'Fix log file ownership'

    def upgrade_instance(self, instance):

        pki.util.chown(instance.actual_logs_dir, instance.uid, instance.gid)
