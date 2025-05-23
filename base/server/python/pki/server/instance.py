# Authors:
#     Endi S. Dewata <edewata@redhat.com>
#     Dinesh Prasanth M K <dmoluguw@redhat.com>
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
# Copyright (C) 2018 Red Hat, Inc.
# All rights reserved.
#

from __future__ import absolute_import

import functools
import inspect
import io
import logging
import os
import pathlib
import pwd
import re
import shutil
import subprocess
import tempfile

from lxml import etree

import pki
import pki.cert
import pki.nssdb
import pki.util
import pki.server

logger = logging.getLogger(__name__)

parser = etree.XMLParser(remove_blank_text=True)


@functools.total_ordering
class PKIInstance(pki.server.PKIServer):

    REGISTRY_FILE = pki.server.PKIServer.SHARE_DIR + '/setup/pkidaemon_registry'
    UNIT_FILE = pki.server.LIB_SYSTEMD_DIR + '/system/pki-tomcatd@.service'
    TARGET_FILE = pki.server.LIB_SYSTEMD_DIR + '/system/pki-tomcatd.target'
    TARGET_WANTS = pki.server.ETC_SYSTEMD_DIR + '/system/pki-tomcatd.target.wants'

    def __init__(self,
                 name,
                 instance_type='pki-tomcatd',
                 user='pkiuser',
                 group='pkiuser',
                 version=10):

        super().__init__(name, instance_type, user, group)

        self.version = version

        self.external_certs = []

        # The standard conf dir at /var/lib/pki/<instance>/conf
        # will be a link to the actual folder at /etc/pki/<instance>.
        self._conf_dir = os.path.join(pki.server.PKIServer.CONFIG_DIR, self.name)

        # The standard conf dir at /var/lib/pki/<instance>/logs
        # will be a link to the actual folder at /var/log/pki/<instance>.
        self._logs_dir = os.path.join(pki.server.PKIServer.LOG_DIR, self.name)

        self.default_root_doc_base = os.path.join(
            pki.SHARE_DIR,
            'server',
            'webapps',
            'ROOT')

        self.root_doc_base = os.path.join(self.webapps_dir, 'ROOT')

        self.default_root_xml = os.path.join(
            pki.SHARE_DIR,
            'server',
            'conf',
            'Catalina',
            'localhost',
            'ROOT.xml')

        self.root_xml = os.path.join(
            self.conf_dir,
            'Catalina',
            'localhost',
            'ROOT.xml')

        self.default_pki_doc_base = os.path.join(
            pki.SHARE_DIR,
            'server',
            'webapps',
            'pki')

        self.pki_doc_base = os.path.join(self.webapps_dir, 'pki')

        self.default_pki_xml = os.path.join(
            pki.SHARE_DIR,
            'server',
            'conf',
            'Catalina',
            'localhost',
            'pki.xml')

        self.pki_xml = os.path.join(
            self.conf_dir,
            'Catalina',
            'localhost',
            'pki.xml')

        self.with_maven_deps = False

    def __eq__(self, other):
        if not isinstance(other, PKIInstance):
            return NotImplemented
        return (self.name == other.name and
                self.version == other.version)

    def __ne__(self, other):
        if not isinstance(other, PKIInstance):
            return NotImplemented
        return not self.__eq__(other)

    def __lt__(self, other):
        if not isinstance(other, PKIInstance):
            return NotImplemented
        return (self.name < other.name or
                self.version < other.version)

    def __hash__(self):
        return hash((self.name, self.version))

    @property
    def base_dir(self):
        if self.version < 10:
            return os.path.join(pki.BASE_DIR, self.name)
        return os.path.join(pki.server.PKIServer.BASE_DIR, self.name)

    @property
    def service_conf(self):
        return os.path.join(pki.server.SYSCONFIG_DIR, self.name)

    @property
    def server_cert_nick_conf(self):
        logger.warning(
            '%s:%s: The PKIInstance.server_cert_nick_conf() has '
            'been deprecated (https://github.com/dogtagpki/pki/wiki/PKI-10.9-Python-Changes).',
            inspect.stack()[1].filename, inspect.stack()[1].lineno)
        return os.path.join(self.conf_dir, 'serverCertNick.conf')

    @property
    def banner_file(self):
        return os.path.join(self.conf_dir, 'banner.txt')

    @property
    def external_certs_conf(self):
        return os.path.join(self.conf_dir, 'external_certs.conf')

    @property
    def registry_dir(self):
        return os.path.join(pki.server.PKIServer.REGISTRY_DIR, 'tomcat', self.name)

    @property
    def registry_file(self):
        return os.path.join(self.registry_dir, self.name)

    @property
    def unit_file(self):
        return PKIInstance.TARGET_WANTS + '/%s.service' % self.service_name

    def execute(
            self, command,
            as_current_user=False,
            with_jdb=False,
            with_gdb=False,
            with_valgrind=False,
            agentpath=None,
            skip_upgrade=False,
            skip_migration=False):

        if command == 'start':

            if self.type == 'pki-tomcatd':
                instance_id = self.name
            else:
                instance_id = '%s@%s' % (self.type, self.name)

            prefix = []

            # by default run pkidaemon as systemd user
            if not as_current_user:

                current_user = pwd.getpwuid(os.getuid()).pw_name

                # switch to systemd user if different from current user
                if current_user != self.user:
                    prefix.extend(['/usr/sbin/runuser', '-u', self.user, '--'])

            if not skip_upgrade:
                # run pki-server upgrade <instance>
                cmd = prefix + ['/usr/sbin/pki-server', 'upgrade']

                if logger.isEnabledFor(logging.DEBUG):
                    cmd.append('--debug')

                elif logger.isEnabledFor(logging.INFO):
                    cmd.append('--verbose')

                cmd.append(instance_id)

                logger.debug('Command: %s', ' '.join(cmd))
                subprocess.run(cmd, env=self.config, check=True)

            if not skip_migration:
                # run pki-server migrate <instance>
                cmd = prefix + ['/usr/sbin/pki-server', 'migrate']

                if logger.isEnabledFor(logging.DEBUG):
                    cmd.append('--debug')

                elif logger.isEnabledFor(logging.INFO):
                    cmd.append('--verbose')

                cmd.append(instance_id)

                logger.debug('Command: %s', ' '.join(cmd))
                subprocess.run(cmd, env=self.config, check=True)

            # run pkidaemon start <instance>
            cmd = prefix + ['/usr/bin/pkidaemon', 'start', instance_id]

            logger.debug('Command: %s', ' '.join(cmd))
            subprocess.run(cmd, env=self.config, check=True)

        return super().execute(
            command,
            as_current_user=as_current_user,
            with_jdb=with_jdb,
            with_gdb=with_gdb,
            with_valgrind=with_valgrind,
            agentpath=agentpath)

    def create(self, force=False):

        super().create(force=force)

        self.create_registry()

        self.symlink(PKIInstance.UNIT_FILE, self.unit_file, exist_ok=True)

    def create_libs(self, force=False):

        if not self.with_maven_deps:
            super().create_libs(force=force)
            return

        logger.info('Updating Maven dependencies')

        cmd = [
            'mvn',
            '-f', '/usr/share/pki/pom.xml',
            'dependency:resolve'
        ]

        logger.debug('Command: %s', ' '.join(cmd))
        subprocess.check_call(cmd)

        repo_dir = '%s/.m2/repository' % pathlib.Path.home()

        pom_xml = '/usr/share/pki/pom.xml'
        logger.info('Loading %s', pom_xml)

        document = etree.parse(pom_xml, parser)
        project = document.getroot()

        xmlns = 'http://maven.apache.org/POM/4.0.0'

        groupId = project.findtext('{%s}groupId' % xmlns)
        logger.info('Group: %s', groupId)

        artifactId = project.findtext('{%s}artifactId' % xmlns)
        logger.info('Artifact: %s', artifactId)

        version = project.findtext('{%s}version' % xmlns)
        logger.info('Version: %s', version)

        dependencies = project.findall('{%s}dependencies/{%s}dependency' % (xmlns, xmlns))

        self.makedirs(self.lib_dir, exist_ok=True)
        self.makedirs(self.common_dir, exist_ok=True)
        self.makedirs(self.common_lib_dir, exist_ok=True)

        for dependency in dependencies:

            groupId = dependency.findtext('{%s}groupId' % xmlns)
            artifactId = dependency.findtext('{%s}artifactId' % xmlns)
            version = dependency.findtext('{%s}version' % xmlns)
            fileType = dependency.findtext('{%s}type' % xmlns, default='jar')

            groupDir = groupId.replace('.', '/')
            directory = os.path.join(repo_dir, groupDir, artifactId, version)
            filename = artifactId + '-' + version + '.' + fileType
            source = os.path.join(directory, filename)

            # install Maven libraries in common/lib except slf4j
            if artifactId in ['slf4j-api', 'slf4j-jdk14']:
                dest = os.path.join(self.lib_dir, filename)
            else:
                dest = os.path.join(self.common_lib_dir, filename)

            logger.info('Copying %s to %s', source, dest)
            self.copy(source, dest, exist_ok=True, force=force)

        common_lib_dir = os.path.join(pki.server.PKIServer.SHARE_DIR, 'server', 'common', 'lib')

        # install PKI libraries in common/lib
        for filename in [
                'jss.jar',
                'jss-tomcat.jar',
                'jss-tomcat-9.0.jar',
                'ldapjdk.jar',
                'pki-common.jar',
                'pki-tomcat.jar',
                'pki-tomcat-9.0.jar']:

            source = os.path.join(common_lib_dir, filename)
            dest = os.path.join(self.common_lib_dir, filename)

            self.symlink(source, dest, exist_ok=True)

    def create_logging_properties(self, exist_ok=False):

        # Link /var/lib/pki/<instance>/conf/logging.properties
        # to /usr/share/pki/server/conf/logging.properties.

        logging_properties = os.path.join(
            pki.server.PKIServer.SHARE_DIR, 'server', 'conf', 'logging.properties')
        self.symlink(logging_properties, self.logging_properties, exist_ok=exist_ok)

    def create_registry(self):

        # Create instance registry folder at
        # /etc/sysconfig/pki/tomcat/<instance>

        self.makedirs(self.registry_dir, exist_ok=True)

        # Copy /usr/share/pki/setup/pkidaemon_registry
        # to /etc/sysconfig/pki/tomcat/<instance>/<instance>

        self.copyfile(
            PKIInstance.REGISTRY_FILE,
            self.registry_file,
            params={
                'pki_user': self.user,
                'pki_group': self.group,
                'pki_instance_name': self.name,
                'pki_instance_path': self.base_dir
            },
            exist_ok=True)

    def load(self):

        super().load()

        # load UID and GID
        if os.path.exists(self.registry_file):

            logger.info('Loading instance registry: %s', self.registry_file)

            with open(self.registry_file, 'r', encoding='utf-8') as registry:
                lines = registry.readlines()

            for line in lines:
                m = re.search('^PKI_USER=(.*)$', line)
                if m:
                    self.user = m.group(1)
                    logger.debug('- user: %s', self.user)

                m = re.search('^PKI_GROUP=(.*)$', line)
                if m:
                    self.group = m.group(1)
                    logger.debug('- group: %s', self.group)

        self.load_external_certs()

    def load_external_certs(self):
        for external_cert in PKIInstance.load_external_certs_conf(self.external_certs_conf):
            self.add_external_cert(external_cert.nickname, external_cert.token)

    def remove(self, remove_conf=False, remove_logs=False, force=False):

        logger.info('Removing %s', self.unit_file)
        pki.util.unlink(self.unit_file, force=force)

        self.remove_registry(force=force)

        super().remove(
            remove_conf=remove_conf,
            remove_logs=remove_logs,
            force=force)

    def remove_libs(self, force=False):

        logger.info('Removing %s', self.common_dir)
        pki.util.rmtree(self.common_dir, force=force)

        logger.info('Removing %s', self.lib_dir)
        if os.path.islink(self.lib_dir):
            pki.util.unlink(self.lib_dir, force=force)
        else:
            pki.util.rmtree(self.lib_dir, force=force)

    def remove_registry(self, force=False):

        # Remove /etc/sysconfig/pki/tomcat/<instance>/<instance>

        logger.info('Removing %s', self.registry_file)
        pki.util.remove(self.registry_file, force=force)

        # Remove instance registry folder at
        # /etc/sysconfig/pki/tomcat/<instance>

        logger.info('Removing %s', self.registry_dir)
        pki.util.rmtree(self.registry_dir, force=force)

    @staticmethod
    def load_external_certs_conf(conf_file):

        logger.info('Loading external certs from %s', conf_file)

        if not os.path.exists(conf_file):
            logger.info('File does not exist: %s', conf_file)
            return []

        lines = open(conf_file, encoding='utf-8').read().splitlines()

        tmp_certs = {}
        for line in lines:

            m = re.search('(\\d+)\\.(\\w+)=(.*)', line)
            if not m:
                raise pki.PKIException('Error parsing %s' % conf_file)

            indx = m.group(1)
            attr = m.group(2)
            value = m.group(3)

            if attr == 'nickname':
                logger.debug('- %s', value)

            if indx not in tmp_certs:
                tmp_certs[indx] = pki.server.ExternalCert()

            setattr(tmp_certs[indx], attr, value)

        return tmp_certs.values()

    @staticmethod
    def store_external_certs_conf(conf_file, external_certs):

        logger.info('Storing external certs into %s', conf_file)

        with open(conf_file, 'w', encoding='utf-8') as f:
            indx = 0
            for cert in external_certs:

                logger.debug('- %s', cert.nickname)

                f.write('%s.nickname=%s\n' % (str(indx), cert.nickname))
                f.write('%s.token=%s\n' % (str(indx), cert.token))
                indx += 1

    def external_cert_exists(self, nickname, token):
        for cert in self.external_certs:
            if cert.nickname == nickname and cert.token == token:
                return True
        return False

    def add_external_cert(self, nickname, token):
        if self.external_cert_exists(nickname, token):
            return
        self.external_certs.append(pki.server.ExternalCert(nickname, token))

    def delete_external_cert(self, nickname, token):
        for cert in self.external_certs:
            if cert.nickname == nickname and cert.token == token:
                self.external_certs.remove(cert)

    def store_external_certs(self):

        if len(self.external_certs) == 0:
            logger.info('Removing %s', self.external_certs_conf)
            pki.util.remove(self.external_certs_conf)
            return

        PKIInstance.store_external_certs_conf(self.external_certs_conf, self.external_certs)

    def export_external_certs(self, pkcs12_file, pkcs12_password_file,
                              append=False):
        for cert in self.external_certs:
            nickname = cert.nickname
            token = pki.nssdb.normalize_token(cert.token)

            nssdb_password = self.get_token_password(token)

            tmpdir = tempfile.mkdtemp()

            try:
                nssdb_password_file = os.path.join(tmpdir, 'password.txt')
                with open(nssdb_password_file, 'w', encoding='utf-8') as f:
                    f.write(nssdb_password)

                # add the certificate, key, and chain
                cmd = [
                    'pki',
                    '-d', self.nssdb_dir,
                    '-C', nssdb_password_file
                ]

                if token:
                    cmd.extend(['--token', token])

                cmd.extend([
                    'pkcs12-cert-import',
                    '--pkcs12-file', pkcs12_file,
                    '--pkcs12-password-file', pkcs12_password_file,
                ])

                if append:
                    cmd.extend(['--append'])

                cmd.extend([
                    nickname
                ])

                logger.debug('Command: %s', ' '.join(cmd))

                subprocess.check_call(cmd)

            finally:
                shutil.rmtree(tmpdir)

    def get_sslserver_cert_nickname(self):

        nickname = super().get_sslserver_cert_nickname()

        if nickname:
            return nickname

        # If not available, load SSL server cert nickname from serverCertNick.conf
        # TODO: Remove serverCertNick.conf

        logger.info('Getting serverCertNickFile from server.xml')

        document = etree.parse(self.server_xml, parser)
        server = document.getroot()

        connector = server.find('Service/Connector[@secure=\'true\']')

        if connector is None:
            # no secure Connector -> no nickname
            return None

        server_cert_nick_conf = connector.get('serverCertNickFile')

        if server_cert_nick_conf is None:
            # no serverCertNick.conf -> no nickname
            return None

        logger.info('Loading %s', server_cert_nick_conf)

        with open(server_cert_nick_conf, encoding='utf-8') as f:
            return f.readline().strip()

    def set_sslserver_cert_nickname(self, nickname, token=None):

        super().set_sslserver_cert_nickname(nickname, token)

        if pki.nssdb.internal_token(token):
            fullname = nickname
        else:
            fullname = token + ':' + nickname

        # Store SSL server cert nickname into serverCertNick.conf
        # TODO: Remove serverCertNick.conf

        server_cert_nick_conf = os.path.join(self.conf_dir, 'serverCertNick.conf')
        logger.info('Updating %s', server_cert_nick_conf)

        with open(server_cert_nick_conf, 'w', encoding='utf-8') as f:
            f.write(fullname + '\n')

        self.chown(server_cert_nick_conf)
        os.chmod(server_cert_nick_conf, pki.server.DEFAULT_FILE_MODE)

        logger.info('Updating serverCertNickFile in server.xml')

        document = etree.parse(self.server_xml, parser)
        server = document.getroot()

        connector = server.find('Service/Connector[@secure=\'true\']')

        if connector is None:
            # no secure Connector -> ignore
            return

        connector.set('serverCertNickFile', server_cert_nick_conf)

        with open(self.server_xml, 'wb') as f:
            document.write(f, pretty_print=True, encoding='utf-8')

    def banner_installed(self):
        return os.path.exists(self.banner_file)

    def get_banner(self):
        with io.open(self.banner_file, encoding='utf-8') as f:
            return f.read().strip()

    def validate_banner(self):

        if not self.banner_installed():
            return

        banner = self.get_banner()

        if not banner:
            raise Exception('Banner is empty')

    def __repr__(self):
        if self.version == 9:
            return "Dogtag 9 " + self.name
        return self.name

    def cert_del(self, cert_id, remove_key=False):
        """
        Delete a cert from NSS db

        :param cert_id: Cert ID
        :type cert_id: str
        :param remove_key: Remove associate private key
        :type remove_key: bool
        """

        subsystem_name, cert_tag = pki.server.PKIServer.split_cert_id(cert_id)

        if not subsystem_name:
            subsystem_name = self.get_subsystems()[0].name

        subsystem = self.get_subsystem(subsystem_name)

        cert = subsystem.get_subsystem_cert(cert_tag)
        nssdb = self.open_nssdb()

        try:
            logger.debug('Removing %s certificate from NSS database from '
                         'subsystem %s in instance %s', cert_tag, subsystem.name, self.name)
            nssdb.remove_cert(
                nickname=cert['nickname'],
                token=cert['token'],
                remove_key=remove_key)
        finally:
            nssdb.close()

    def cert_import(
            self,
            cert_id=None,
            cert_file=None,
            token=None,
            nickname=None):
        """
        Import cert from cert_file into NSS db with appropriate trust

        :param cert_id: Cert ID
        :type cert_id: str
        :param cert_file: Cert file to be imported into NSS db
        :type cert_file: str
        :param token: Token to store the certificate
        :type token: str
        :param nickname: Certificate nickname
        :type nickname: str
        :return: None
        :rtype: None
        """

        if cert_id:
            if not cert_file:
                # If cert_file is not provided, load the cert from
                # /var/lib/pki/<instance>/conf/certs/<cert_id>.crt
                cert_file = self.cert_file(cert_id)
            logger.debug('Importing cert %s from %s', cert_id, cert_file)
        else:
            if not cert_file:
                raise pki.server.PKIServerException('Missing certificate ID or file')
            logger.debug('Importing cert from %s', cert_file)

        if not os.path.isfile(cert_file):
            raise pki.server.PKIServerException('File does not exist: %s' % cert_file)

        if cert_id:
            subsystem_name, cert_tag = pki.server.PKIServer.split_cert_id(cert_id)
        else:
            subsystem_name = None
            cert_tag = None

        logger.debug('- subsystem: %s', subsystem_name)
        logger.debug('- cert tag: %s', cert_tag)

        if subsystem_name:
            # if cert ID contains subsystem name, get that subsystem
            subsystem = self.get_subsystem(subsystem_name)
        else:
            # if cert ID does not contain subsystem name (i.e. sslserver, subsystem),
            # get the first available subsystem
            subsystems = self.get_subsystems()
            if len(subsystems) > 0:
                subsystem = subsystems[0]
            else:
                subsystem = None

        if subsystem and cert_tag:
            # if the subsystem exists, use the nickname and token
            # specified in CS.cfg
            cert_info = subsystem.get_subsystem_cert(cert_tag)
            nickname = cert_info['nickname']
            token = cert_info['token']
        else:
            # if the subsystem does not exist, use the specified
            # nickname and token
            if not nickname and cert_id:
                # if nickname not specified, use the cert ID
                nickname = cert_id

        # audit and CA signing cert require special flags set in NSSDB
        trust_attributes = None
        if cert_id == 'ca_signing':
            trust_attributes = 'CT,C,C'
        elif cert_tag == 'audit_signing':
            trust_attributes = ',,P'

        logger.debug('- trust flags: %s', trust_attributes)

        nssdb = self.open_nssdb()

        try:
            logger.debug('Checking existing cert %s', nickname)

            if nssdb.get_cert(
                    nickname=nickname,
                    token=token):
                raise pki.server.PKIServerException(
                    'Certificate already exists: %s' % nickname)

            logger.debug('Importing cert %s', nickname)

            nssdb.add_cert(
                nickname=nickname,
                token=token,
                cert_file=cert_file,
                trust_attributes=trust_attributes)

        finally:
            nssdb.close()

    def cert_request(self, cert_id, subject_dn, token=None, ext_conf=None):

        token = pki.nssdb.normalize_token(token)
        csr_file = self.csr_file(cert_id)

        cmd = [
            '/usr/sbin/runuser',
            '-u', self.user, '--',
            'pki',
            '-d', self.nssdb_dir,
            '-f', self.password_conf
        ]

        if token:
            cmd.extend(['--token', token])

        cmd.extend([
            'nss-cert-request',
            '--subject', subject_dn,
            '--csr', csr_file
        ])

        if ext_conf:
            cmd.extend(['--ext', ext_conf])

        if logger.isEnabledFor(logging.DEBUG):
            cmd.append('--debug')

        elif logger.isEnabledFor(logging.INFO):
            cmd.append('--verbose')

        logger.debug('Command: %s', ' '.join(cmd))

        subprocess.check_call(cmd)

    def cert_create(
            self, cert_id=None,
            username=None, password=None,
            client_cert=None, client_nssdb=None,
            client_nssdb_pass=None, client_nssdb_pass_file=None,
            serial=None, temp_cert=False, renew=False, output=None,
            secure_port='8443',
            token=None,
            issuer=None,
            ext_conf=None):
        """
        Create a new cert for the cert_id provided

        :param cert_id: New cert's ID
        :type cert_id: str
        :param username: Username (must also supply password)
        :type username: str
        :param password: Password (must also supply username)
        :type password: str
        :param client_cert: Client cert nickname
        :type client_cert: str
        :param client_nssdb: Path to nssdb
        :type client_nssdb: str
        :param client_nssdb_pass: Password to the nssdb
        :type client_nssdb_pass: str
        :param client_nssdb_pass_file: File containing nssdb's password
        :type client_nssdb_pass_file: str
        :param serial: Serial number of the cert to be renewed.  If creating
                       a temporary certificate (temp_cert == True), the serial
                       number will be reused.  If not supplied, the cert_id is
                       used to look it up.
        :type serial: str
        :param temp_cert: Whether new cert is a temporary cert
        :type temp_cert: bool
        :param renew: Whether to place a renewal request to ca
        :type renew: bool
        :param output: Path to which new cert needs to be written to
        :type output: str
        :param secure_port: Secure port number in case of renewing a certificate
        :type secure_port: str
        :param token: Token that stores the signing key
        :type token: str
        :param issuer: Issuer certificate nickname
        :type issuer: str
        :param ext_conf: Configuration file for certificate extension
        :type ext_conf: str
        :return: None
        :rtype: None
        :raises pki.server.PKIServerException

        Either supply both username and password, or supply
        client_cert and (client_nssdb_pass or client_nssdb_pass_file).

        Note that client_nssdb should be specified in either case, as it
        contains the CA Certificate.
        """

        if not temp_cert and not renew:
            # creating permanent cert

            token = pki.nssdb.normalize_token(token)
            csr_file = self.csr_file(cert_id)
            cert_file = self.cert_file(cert_id)

            cmd = [
                '/usr/sbin/runuser',
                '-u', self.user, '--',
                'pki',
                '-d', self.nssdb_dir,
                '-f', self.password_conf
            ]

            if token:
                cmd.extend(['--token', token])

            cmd.extend([
                'nss-cert-issue',
                '--csr', csr_file,
                '--cert', cert_file
            ])

            if issuer:
                cmd.extend(['--issuer', issuer])

            if ext_conf:
                cmd.extend(['--ext', ext_conf])

            if logger.isEnabledFor(logging.DEBUG):
                cmd.append('--debug')

            elif logger.isEnabledFor(logging.INFO):
                cmd.append('--verbose')

            logger.debug('Command: %s', ' '.join(cmd))

            subprocess.check_call(cmd)

            return

        if not temp_cert:
            # For permanent certificate, password of either NSS DB OR agent is required.
            if not client_nssdb_pass and not client_nssdb_pass_file and not password:
                raise Exception('NSS database or agent password is required.')

        nssdb = self.open_nssdb()
        tmpdir = tempfile.mkdtemp()
        subsystem = None  # used for system certs

        try:
            if cert_id:
                new_cert_file = output if output else self.cert_file(cert_id)

                subsystem_name, cert_tag = pki.server.PKIServer.split_cert_id(cert_id)
                if not subsystem_name:
                    subsystem_name = self.get_subsystems()[0].name
                subsystem = self.get_subsystem(subsystem_name)

                if serial is None:
                    # If admin doesn't provide a serial number, set the serial to
                    # the same serial number available in the nssdb
                    serial = subsystem.get_subsystem_cert(cert_tag)["serial_number"]

            else:
                if serial is None:
                    raise pki.server.PKIServerException(
                        "Must provide either 'cert_id' or 'serial'")
                if output is None:
                    raise pki.server.PKIServerException(
                        "Must provide 'output' when renewing by serial")
                if temp_cert:
                    raise pki.server.PKIServerException(
                        "'temp_cert' must be used with 'cert_id'")
                new_cert_file = output

            if temp_cert:
                assert subsystem is not None  # temp_cert only supported with cert_id

                logger.info('Creating temp cert for %s', cert_id)

                # Create Temp Cert and write it to new_cert_file
                subsystem.temp_cert_create(nssdb, cert_tag, serial, new_cert_file)

                logger.info('Storing temp cert into %s', new_cert_file)

            else:
                # Create permanent certificate
                if not renew:
                    # TODO: Support rekey
                    raise pki.server.PKIServerException('Rekey is not supported yet.')

                logger.debug('Setting up secure connection to CA')
                if username and password:
                    connection = pki.server.PKIServer.setup_password_authentication(
                        username, password, subsystem_name='ca', secure_port=secure_port,
                        client_nssdb=client_nssdb)
                else:
                    if not client_cert:
                        raise pki.server.PKIServerException('Client cert nick name required.')
                    if not client_nssdb_pass and not client_nssdb_pass_file:
                        raise pki.server.PKIServerException('NSS db password required.')
                    connection = pki.server.PKIServer.setup_cert_authentication(
                        client_nssdb_pass=client_nssdb_pass,
                        client_cert=client_cert,
                        client_nssdb_pass_file=client_nssdb_pass_file,
                        client_nssdb=client_nssdb,
                        tmpdir=tmpdir,
                        secure_port=secure_port
                    )

                pki.server.PKIServer.renew_certificate(connection, new_cert_file, serial)

        finally:
            nssdb.close()
            shutil.rmtree(tmpdir)

    def verify_cert(self, cert_data):

        nssdb = self.open_nssdb()

        try:
            nssdb.verify_cert(cert_data=cert_data)

        finally:
            nssdb.close()

    def configure_ajp_connectors_secret(self):

        logger.info('Configuring AJP connectors secret')

        document = etree.parse(self.server_xml, parser)
        server = document.getroot()

        # replace 'requiredSecret' with 'secret' in comments

        services = server.findall('Service')
        for service in services:

            children = list(service)
            for child in children:

                if not isinstance(child, etree._Comment):  # pylint: disable=protected-access
                    # not a comment -> skip
                    continue

                if 'protocol="AJP/1.3"' not in child.text:
                    # not an AJP connector -> skip
                    continue

                child.text = re.sub(r'requiredSecret=',
                                    r'secret=',
                                    child.text,
                                    flags=re.MULTILINE)

        # replace 'requiredSecret' with 'secret' in Connectors

        connectors = server.findall('Service/Connector')
        for connector in connectors:

            if connector.get('protocol') != 'AJP/1.3':
                # not an AJP connector -> skip
                continue

            # remove existing 'requiredSecret' if any
            value = connector.attrib.pop('requiredSecret', None)
            print('AJP connector requiredSecret: %s' % value)

            if connector.get('secret'):
                # already has a 'secret' -> skip
                continue

            if not value:
                raise Exception('Missing AJP connector secret in %s' % self.server_xml)

            # store 'secret'
            connector.set('secret', value)

        with open(self.server_xml, 'wb') as f:
            document.write(f, pretty_print=True, encoding='utf-8')

    def configure_ajp_connectors_required_secret(self):

        logger.info('Configuring AJP connectors requiredSecret')

        document = etree.parse(self.server_xml, parser)
        server = document.getroot()

        # replace 'secret' with 'requiredSecret' in comments

        services = server.findall('Service')
        for service in services:

            children = list(service)
            for child in children:

                if not isinstance(child, etree._Comment):  # pylint: disable=protected-access
                    # not a comment -> skip
                    continue

                if 'protocol="AJP/1.3"' not in child.text:
                    # not an AJP connector -> skip
                    continue

                child.text = re.sub(r'secret=',
                                    r'requiredSecret=',
                                    child.text,
                                    flags=re.MULTILINE)

        # replace 'secret' with 'requiredSecret' in Connectors

        connectors = server.findall('Service/Connector')
        for connector in connectors:

            if connector.get('protocol') != 'AJP/1.3':
                # not an AJP connector -> skip
                continue

            # remove existing 'secret' if any
            value = connector.attrib.pop('secret', None)
            print('AJP connector secret: %s' % value)

            if connector.get('requiredSecret'):
                # already has a 'requiredSecret' -> skip
                continue

            if not value:
                raise Exception('Missing AJP connector requiredSecret in %s' % self.server_xml)

            # store 'requiredSecret'
            connector.set('requiredSecret', value)

        with open(self.server_xml, 'wb') as f:
            document.write(f, pretty_print=True, encoding='utf-8')

    def configure_ajp_connectors(self):

        tomcat_version = pki.server.Tomcat.get_version()

        if tomcat_version >= pki.util.Version('9.0.31'):
            self.configure_ajp_connectors_secret()
        else:
            self.configure_ajp_connectors_required_secret()

    def init(self):
        super().init()
        self.validate_banner()
        self.configure_ajp_connectors()

    @classmethod
    def instances(cls):

        instances = []

        if not os.path.exists(os.path.join(pki.server.PKIServer.REGISTRY_DIR, 'tomcat')):
            return instances

        for instance_name in os.listdir(pki.server.PKIServer.BASE_DIR):
            instance = PKIInstance(instance_name)
            instance.load()
            instances.append(instance)

        return instances
