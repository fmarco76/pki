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
// (C) 2012 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---

package com.netscape.cmstools.kra;

import org.dogtagpki.cli.CLI;
import org.dogtagpki.kra.KRASystemCertClient;
import org.mozilla.jss.CryptoManager;
import org.mozilla.jss.crypto.X509Certificate;
import org.mozilla.jss.netscape.security.util.Cert;
import org.mozilla.jss.netscape.security.util.Utils;

import com.netscape.certsrv.client.PKIClient;
import com.netscape.certsrv.key.KeyClient;
import com.netscape.certsrv.key.KeyInfo;
import com.netscape.certsrv.key.KeyRequestInfo;
import com.netscape.certsrv.util.NSSCryptoProvider;
import com.netscape.cmstools.cli.MainCLI;
import com.netscape.cmstools.cli.SubsystemCLI;

/**
 * @author Endi S. Dewata
 */
public class KRAKeyCLI extends CLI {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(KRAKeyCLI.class);

    public KeyClient keyClient;

    public KRAKeyCLI(CLI parent) {
        super("key", "Key management commands", parent);

        addModule(new KRAKeyTemplateFindCLI(this));
        addModule(new KRAKeyTemplateShowCLI(this));

        addModule(new KRAKeyRequestFindCLI(this));
        addModule(new KRAKeyRequestShowCLI(this));
        addModule(new KRAKeyRequestReviewCLI(this));

        addModule(new KRAKeyFindCLI(this));
        addModule(new KRAKeyShowCLI(this));
        addModule(new KRAKeyModifyCLI(this));

        addModule(new KRAKeyGenerateCLI(this));
        addModule(new KRAKeyArchiveCLI(this));
        addModule(new KRAKeyRetrieveCLI(this));
        addModule(new KRAKeyRecoverCLI(this));
    }

    @Override
    public String getFullName() {
        // do not include MainCLI's name
        return parent instanceof MainCLI ? name : parent.getFullName() + "-" + name;
    }

    @Override
    public String getManPage() {
        return "pki-key";
    }

    public KeyClient getKeyClient() throws Exception {
        return getKeyClient(null);
    }

    public KeyClient getKeyClient(String transportNickname) throws Exception {

        if (keyClient != null) return keyClient;

        PKIClient client = getClient();

        // determine the subsystem
        String subsystem;
        if (parent instanceof SubsystemCLI) {
            SubsystemCLI subsystemCLI = (SubsystemCLI)parent;
            subsystem = subsystemCLI.getName();
        } else {
            subsystem = "kra";
        }

        // create new key client
        keyClient = new KeyClient(client, subsystem);

        // create crypto provider for key client
        keyClient.setCrypto(new NSSCryptoProvider(client.getConfig()));

        CryptoManager manager = CryptoManager.getInstance();
        X509Certificate transportCert;

        if (transportNickname == null) {
            // download transport cert
            KRASystemCertClient systemCertClient = new KRASystemCertClient(client, subsystem);
            String pemCert = systemCertClient.getTransportCert().getEncoded();
            String b64Cert = pemCert.substring(Cert.HEADER.length(), pemCert.indexOf(Cert.FOOTER));
            byte[] binCert = Utils.base64decode(b64Cert);

            transportCert = manager.importCACertPackage(binCert);

        } else {
            // load transport cert
            transportCert = manager.findCertByNickname(transportNickname);
        }

        logger.info("Transport cert: " + transportCert.getNickname());

        // set transport cert for key client
        keyClient.setTransportCert(transportCert);

        return keyClient;
    }

    public static void printKeyInfo(KeyInfo info, boolean showPublicKey) {
        System.out.println("  Key ID: "+info.getKeyId().toHexString());
        if (info.getClientKeyID() != null) System.out.println("  Client Key ID: "+info.getClientKeyID());
        if (info.getStatus() != null) System.out.println("  Status: "+info.getStatus());
        if (info.getAlgorithm() != null) System.out.println("  Algorithm: "+info.getAlgorithm());
        if (info.getSize() != null) System.out.println("  Size: "+info.getSize());
        if (info.getOwnerName() != null) System.out.println("  Owner: "+info.getOwnerName());
        if (info.getRealm() != null) System.out.println("  Realm: " + info.getRealm());

        if (info.getPublicKey() != null && showPublicKey) {
            // Print out the Base64 encoded public key in the form of a blob,
            // where the max line length is 64.
            System.out.println("  Public Key: \n");
            String publicKey = Utils.base64encode(info.getPublicKey(), true);
            System.out.println(publicKey);
            System.out.println();
        }
    }

    public static void printKeyRequestInfo(KeyRequestInfo info) {
        System.out.println("  Request ID: "+info.getRequestID().toHexString());
        if (info.getKeyId() != null) System.out.println("  Key ID: "+info.getKeyId().toHexString());
        if (info.getRequestType() != null) System.out.println("  Type: "+info.getRequestType());
        if (info.getRequestStatus() != null) System.out.println("  Status: "+info.getRequestStatus());
        if (info.getRealm() != null) System.out.println("  Realm: "+ info.getRealm());

        System.out.println("  Creation Time: " + info.getCreationTime());
        System.out.println("  Modification Time: " + info.getModificationTime());
    }
}
