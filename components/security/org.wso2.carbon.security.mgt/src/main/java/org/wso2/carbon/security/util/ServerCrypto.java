/*
 * Copyright 2005-2007 WSO2, Inc. (http://wso2.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.security.util;

import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.CredentialException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.components.crypto.X509NameTokenizer;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.security.SecurityConstants;
import org.wso2.carbon.security.SecurityServiceHolder;
import org.wso2.carbon.security.internal.SecurityMgtServiceComponent;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

/**
 * ServerCrypto implementation to support a collection of keystores holding different trusted certs
 * and CA certs
 */
public class ServerCrypto implements Crypto {

    private static Log log = LogFactory.getLog(ServerCrypto.class);

    public final static String PROP_ID_KEY_STORE = "org.wso2.carbon.security.crypto.keystore";

    public final static String PROP_ID_PRIVATE_STORE = "org.wso2.carbon.security.crypto.privatestore";
    
    public final static String PROP_ID_TRUST_STORES = "org.wso2.carbon.security.crypto.truststores";

    public final static String PROP_ID_CERT_PROVIDER = "org.wso2.carbon.security.crypto.cert.provider";

    public final static String PROP_ID_DEFAULT_ALIAS = "org.wso2.carbon.security.crypto.alias";

    public final static String PROP_ID_REGISTRY = "org.wso2.carbon.security.crypto.registry";

    public final static String PROP_ID_CACERT_PASS = "org.wso2.carbon.security.crypto.cacert.pass";

    public final static String PROP_ID_XKMS_SERVICE_PASS_PHRASE = "org.wso2.wsas.security.wso2wsas.crypto.xkms.pass";

    public final static String PROP_ID_TENANT_ID = "org.wso2.stratos.tenant.id";
    
    public final static String PROP_ID_XKMS_SERVICE_URL = "org.wso2.carbon.security.crypto.xkms.url";
    
    private static final String SKI_OID = "2.5.29.14";

    private Properties properties = null;
    private KeyStore keystore = null;
    private KeyStore cacerts = null;
    private List<KeyStore> trustStores = new ArrayList<KeyStore>();
    private static CertificateFactory certFact = null;
    private Registry registry = null;
    private Boolean useXkms;

    public ServerCrypto(Properties prop) throws CredentialException, IOException {
        this(prop, ServerCrypto.class.getClassLoader());
    }

    public ServerCrypto(Properties prop, ClassLoader loader) throws CredentialException,
            IOException {

        try {

            int tenantId;

			String tenantIdString = (String) prop.get(PROP_ID_TENANT_ID);

			if (tenantIdString == null || tenantIdString.trim().length() == 0) {
                tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
			} else {
                tenantId = new Integer(tenantIdString);
            }

            registry = SecurityServiceHolder.getRegistryService().getGovernanceSystemRegistry(tenantId);

            this.properties = prop;
            
            KeyStoreManager keyMan = KeyStoreManager.getInstance(tenantId);
            String ksId = this.properties.getProperty(PROP_ID_PRIVATE_STORE);
            if(ksId != null){
                this.keystore = keyMan.getKeyStore(ksId);
            }
            
            // Get other keystores if available
            String trustStoreIds = this.properties.getProperty(PROP_ID_TRUST_STORES);
            if (trustStoreIds != null && trustStoreIds.trim().length() != 0) {
                String[] ids = trustStoreIds.trim().split(",");
                this.trustStores = new ArrayList(ids.length);
                for (int i = 0; i < ids.length; i++) {
                    String id = ids[i];
                    KeyStore tstks = keyMan.getKeyStore(id);
                    this.trustStores.add(i, tstks);
                }
            } 
        } catch (Exception e) {
            e.printStackTrace();
            log.error("error creating ServerCryto",e);
            throw new CredentialException(3, "secError00", e);
        }

        /**
         * Load cacerts
         */
        String cacertsPath = System.getProperty("java.home") + "/lib/security/cacerts";
        InputStream cacertsIs = new FileInputStream(cacertsPath);
        try {
            String cacertsPasswd = properties.getProperty(PROP_ID_CACERT_PASS, "changeit");
            cacerts = KeyStore.getInstance(KeyStore.getDefaultType());
            cacerts.load(cacertsIs, cacertsPasswd.toCharArray());

        } catch (GeneralSecurityException e) {
            log.warn("Unable load to cacerts from the JDK.");
			if (trustStores != null && trustStores.size() > 0) {
				cacerts = this.trustStores.get(0);
			} else {
				throw new CredentialException(3, "secError00", e);
			}
        } finally {
            cacertsIs.close();
        }

    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#loadCertificate(java.io.InputStream)
     */
    public X509Certificate loadCertificate(InputStream in) throws WSSecurityException {
        X509Certificate cert;
        try {
            cert = (X509Certificate) getCertificateFactory().generateCertificate(in);
        } catch (CertificateException e) {
            throw new WSSecurityException(WSSecurityException.SECURITY_TOKEN_UNAVAILABLE,
                    "parseError");
        }
        return cert;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getX509Certificates(byte[],boolean)
     */
    public X509Certificate[] getX509Certificates(byte[] data, boolean reverse)
            throws WSSecurityException {
        InputStream in = new ByteArrayInputStream(data);
        CertPath path;
        try {
            path = getCertificateFactory().generateCertPath(in);
        } catch (CertificateException e) {
            throw new WSSecurityException(WSSecurityException.SECURITY_TOKEN_UNAVAILABLE,
                    "parseError");
        }
        List l = path.getCertificates();
        X509Certificate[] certs = new X509Certificate[l.size()];
        Iterator iterator = l.iterator();
        for (int i = 0; i < l.size(); i++) {
            certs[(reverse) ? (l.size() - 1 - i) : i] = (X509Certificate) iterator.next();
        }
        return certs;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getCertificateData(boolean,
     *java.security.cert.X509Certificate[])
     */
    public byte[] getCertificateData(boolean reverse, X509Certificate[] certs)
            throws WSSecurityException {
        Vector list = new Vector();
        for (int i = 0; i < certs.length; i++) {
            if (reverse) {
                list.insertElementAt(certs[i], 0);
            } else {
                list.add(certs[i]);
            }
        }
        try {
            CertPath path = getCertificateFactory().generateCertPath(list);
            return path.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new WSSecurityException(WSSecurityException.SECURITY_TOKEN_UNAVAILABLE,
                    "encodeError");
        } catch (CertificateException e) {
            throw new WSSecurityException(WSSecurityException.SECURITY_TOKEN_UNAVAILABLE,
                    "parseError");
        }
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getPrivateKey(java.lang.String,
     *java.lang.String)
     */
    public PrivateKey getPrivateKey(String alias, String password) throws Exception {
        if (alias == null) {
            throw new Exception("alias is null");
        }
        boolean b = keystore.isKeyEntry(alias);

//        if (!b && useXKMS()) {
//            PrivateKey privateKey = XKMSCryptoClient.getPrivateKey(alias,
//                    properties.getProperty(PROP_ID_XKMS_SERVICE_URL),
//                    properties.getProperty(PROP_ID_XKMS_SERVICE_PASS_PHRASE));
//            if (privateKey == null) {
//                log.error("Cannot find key for alias" + alias);
//                throw new Exception("Cannot find key for alias: " + alias);
//            }
//
//            return privateKey;
//
//         } else {

             if (!b) {
                 log.error("Cannot find key for alias: " + alias);
                 throw new Exception("Cannot find key for alias: " + alias);
             }
             
             Key keyTmp = keystore.getKey(alias, password.toCharArray());
             
             if (!(keyTmp instanceof PrivateKey)) {
                 throw new Exception("Key is not a private key, alias: " + alias);
             }
        
             return (PrivateKey) keyTmp;
//        }
    }

    /**
     * This first looks into the primary keystore and then looks at the other trust stores
     *
     * @see org.apache.ws.security.components.crypto.Crypto#getCertificates(String)
     */
    public X509Certificate[] getCertificates(String alias) throws WSSecurityException {

        Certificate[] certs = new Certificate[0];
        Certificate cert = null;
        try {
            if (this.keystore != null) {
                // There's a chance that there can only be a set of trust stores
                certs = keystore.getCertificateChain(alias);
                if (certs == null || certs.length == 0) {
                    // no cert chain, so lets check if getCertificate gives us a
                    // result.
                    cert = keystore.getCertificate(alias);
                }
            }

            if (certs == null && cert == null && this.trustStores != null) {
                // Now look into the trust stores
                Iterator trustStoreIter = this.trustStores.iterator();
                while (trustStoreIter.hasNext()) {
                    KeyStore store = (KeyStore) trustStoreIter.next();
                    certs = store.getCertificateChain(alias);
                    if (certs != null) {
                        break; // found the certs
                    } else {
                        cert = store.getCertificate(alias);
                    }
                }
            }

            if (certs == null && cert == null && this.cacerts != null) {
                // There's a chance that there can only be a set of ca store
                certs = cacerts.getCertificateChain(alias);
                if (certs == null || certs.length == 0) {
                    // no cert chain, so lets check if getCertificate gives us a
                    // result.
                    cert = cacerts.getCertificate(alias);
                }
            }

            if (cert != null) {
                certs = new Certificate[] { cert };
            } else if (certs == null) {

//            if (useXKMS()) {
//                return XKMSCryptoClient.getCertificates(alias, properties
//                        .getProperty(PROP_ID_XKMS_SERVICE_URL));
//            }

                // At this pont we don't have certs or a cert
                return null;
            }
        } catch (KeyStoreException e) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "keystore");
        }

        X509Certificate[] x509certs = new X509Certificate[0];
        if (certs != null) {
            x509certs = new X509Certificate[certs.length];
            for (int i = 0; i < certs.length; i++) {
                x509certs[i] = (X509Certificate) certs[i];
            }
        }
        return x509certs;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getAliasForX509Cert(java.security.cert.Certificate)
     */
    public String getAliasForX509Cert(Certificate cert) throws WSSecurityException {
        try {
            String alias = null;

            if (this.keystore != null) {
                alias = keystore.getCertificateAlias(cert);

                // Use brute force search
                if (alias == null) {
                    alias = findAliasForCert(this.keystore, cert);
                }
            }

            // Check the trust stores
            if (alias == null && this.trustStores != null) {
                for (Iterator trustStoreIter = this.trustStores.iterator(); trustStoreIter
                        .hasNext();) {
                    KeyStore store = (KeyStore) trustStoreIter.next();
                    alias = store.getCertificateAlias(cert);
                    if (alias != null) {
                        break;
                    }
                }
            }

            // Use brute force search on the trust stores
            if (alias == null && this.trustStores != null) {
                for (Iterator trustStoreIter = this.trustStores.iterator(); trustStoreIter
                        .hasNext();) {
                    KeyStore store = (KeyStore) trustStoreIter.next();
                    alias = this.findAliasForCert(store, cert);
                    if (alias != null) {
                        break;
                    }
                }
            }

            if (alias == null && this.cacerts != null) {
                alias = cacerts.getCertificateAlias(cert);

                // Use brute force search
                if (alias == null) {
                    alias = findAliasForCert(this.cacerts, cert);
                }
            }

            if (alias != null) {
                return alias;
            }

        } catch (KeyStoreException e) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "keystore");
        }
        
//        if (useXKMS()) {
//            return XKMSCryptoClient.getAliasForX509Certificate(
//                    (X509Certificate) cert, properties
//                    .getProperty(PROP_ID_XKMS_SERVICE_URL));
//        }
        
        return null;
    }

    private String findAliasForCert(KeyStore ks, Certificate cert) throws KeyStoreException {
        Enumeration e = ks.aliases();
        while (e.hasMoreElements()) {
            String alias = (String) e.nextElement();
            X509Certificate cert2 = (X509Certificate) ks.getCertificate(alias);
            if (cert2.equals(cert)) {
                return alias;
            }
        }
        return null;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getAliasForX509Cert(java.lang.String)
     */
    public String getAliasForX509Cert(String issuer) throws WSSecurityException {
        String alias = getAliasForX509Cert(issuer, null, false, this.keystore);
        if (alias == null) {
            Iterator<KeyStore> ite = this.trustStores.iterator();
            while (ite.hasNext()) {
                KeyStore ks = ite.next();
                alias = getAliasForX509Cert(issuer, null, false, ks);
                if (alias != null) {
                    break;
                }
            }

        }
        return alias;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getAliasForX509Cert(java.lang.String,
     *java.math.BigInteger)
     */
    public String getAliasForX509Cert(String issuer, BigInteger serialNumber)
            throws WSSecurityException {
        String alias = getAliasForX509Cert(issuer, serialNumber, true, this.keystore);
        if (alias == null) {
            Iterator<KeyStore> ite = this.trustStores.iterator();
            while (ite.hasNext()) {
                KeyStore ks = ite.next();
                alias = getAliasForX509Cert(issuer, serialNumber, true, ks);
                if (alias != null) {
                    break;
                }
            }
        }
        return alias;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getAliasForX509Cert(byte[])
     */
    public String getAliasForX509Cert(byte[] skiBytes) throws WSSecurityException {
        try {

            Certificate cert;
            for (Enumeration e = keystore.aliases(); e.hasMoreElements();) {
                String alias = (String) e.nextElement();
                Certificate[] certs = this.getCertificates(alias);
                if (certs == null || certs.length == 0) {
                    return null;
                } else {
                    cert = certs[0];
                }
                if (!(cert instanceof X509Certificate)) {
                    continue;
                }
                byte[] data = getSKIBytesFromCert((X509Certificate) cert);
                if (data.length != skiBytes.length) {
                    continue;
                }
                if (Arrays.equals(data, skiBytes)) {
                    return alias;
                }
            }

        } catch (KeyStoreException e) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "keystore");
        }
        
//        if (useXKMS()) {
//            return XKMSCryptoClient.getAliasForX509Certificate(skiBytes,
//                    properties.getProperty(PROP_ID_XKMS_SERVICE_URL));
//        }
         
        return null;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getDefaultX509Alias()
     */
    public String getDefaultX509Alias() {
        return this.properties.getProperty(PROP_ID_DEFAULT_ALIAS);
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getSKIBytesFromCert(java.security.cert.X509Certificate)
     */
    public byte[] getSKIBytesFromCert(X509Certificate cert) throws WSSecurityException {
        /*
         * Gets the DER-encoded OCTET string for the extension value (extnValue)
         * identified by the passed-in oid String. The oid string is represented
         * by a set of positive whole numbers separated by periods.
         */
        byte[] derEncodedValue = cert.getExtensionValue(SKI_OID);

        if (cert.getVersion() < 3 || derEncodedValue == null) {
            PublicKey key = cert.getPublicKey();
            if (!(key instanceof RSAPublicKey)) {
                throw new WSSecurityException(1, "noSKIHandling",
                        new Object[] { "Support for RSA key only" });
            }
            byte[] encoded = key.getEncoded();
            // remove 22-byte algorithm ID and header
            byte[] value = new byte[encoded.length - 22];
            System.arraycopy(encoded, 22, value, 0, value.length);
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException ex) {
                throw new WSSecurityException(1, "noSKIHandling",
                        new Object[] { "Wrong certificate version (<3) and no "
                                + "SHA1 message digest availabe" });
            }
            sha.reset();
            sha.update(value);
            return sha.digest();
        }

        /**
         * Strip away first four bytes from the DerValue (tag and length of
         * ExtensionValue OCTET STRING and KeyIdentifier OCTET STRING)
         */
        byte abyte0[] = new byte[derEncodedValue.length - 4];

        System.arraycopy(derEncodedValue, 4, abyte0, 0, abyte0.length);
        return abyte0;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getAliasForX509CertThumb(byte[])
     */
    public String getAliasForX509CertThumb(byte[] thumb) throws WSSecurityException {
        Certificate cert;
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e1) {
            throw new WSSecurityException(0, "noSHA1availabe");
        }
        try {
            for (Enumeration e = keystore.aliases(); e.hasMoreElements();) {
                String alias = (String) e.nextElement();
                Certificate[] certs = this.getCertificates(alias);
                if (certs == null || certs.length == 0) {
                    return null;
                } else {
                    cert = certs[0];
                }
                if (!(cert instanceof X509Certificate)) {
                    continue;
                }
                sha.reset();
                try {
                    sha.update(cert.getEncoded());
                } catch (CertificateEncodingException e1) {
                    throw new WSSecurityException(WSSecurityException.SECURITY_TOKEN_UNAVAILABLE,
                            "encodeError");
                }
                byte[] data = sha.digest();

                if (Arrays.equals(data, thumb)) {
                    return alias;
                }
            }
        } catch (KeyStoreException e) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "keystore");
        }
        return null;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getKeyStore()
     */
    public KeyStore getKeyStore() {
        return this.keystore;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getCertificateFactory()
     */
    public CertificateFactory getCertificateFactory() throws WSSecurityException {
        if (certFact == null) {
            try {
                String provider = properties.getProperty(PROP_ID_CERT_PROVIDER);
                if (provider == null || provider.length() == 0) {
                    certFact = CertificateFactory.getInstance("X.509");
                } else {
                    certFact = CertificateFactory.getInstance("X.509", provider);
                }
            } catch (CertificateException e) {
                throw new WSSecurityException(WSSecurityException.SECURITY_TOKEN_UNAVAILABLE,
                        "unsupportedCertType");
            } catch (NoSuchProviderException e) {
                throw new WSSecurityException(WSSecurityException.SECURITY_TOKEN_UNAVAILABLE,
                        "noSecProvider");
            }
        }
        return certFact;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#validateCertPath(java.security.cert.X509Certificate[])
     */
    public boolean validateCertPath(X509Certificate[] certs) throws WSSecurityException {

        boolean result;
        
//        if (useXKMS()) {
//            result = XKMSCryptoClient.validateCertPath(certs, properties
//                    .getProperty(PROP_ID_XKMS_SERVICE_URL));
//            if (result) {
//                return true;
//             }
//        }

        result = this.validateCertPath(this.keystore, certs);

        if (!result) {
            Iterator trustStoreIter = this.trustStores.iterator();
            while (!result) {
                result = this.validateCertPath((KeyStore) trustStoreIter.next(), certs);
            }
        }

        if (!result && cacerts != null) {
            result = this.validateCertPath(this.cacerts, certs);
        }

        return result;
    }

    /**
     * @see org.apache.ws.security.components.crypto.Crypto#getAliasesForDN(java.lang.String)
     */
    public String[] getAliasesForDN(String subjectDN) throws WSSecurityException {

        // Store the aliases found
        Vector aliases = new Vector();
        Certificate cert;

        // The DN to search the keystore for
        Vector subjectRDN = splitAndTrim(subjectDN);

        // Look at every certificate in the keystore
        try {
            for (Enumeration e = keystore.aliases(); e.hasMoreElements();) {
                String alias = (String) e.nextElement();

                Certificate[] certs = this.getCertificates(alias);
                if (certs == null || certs.length == 0) {
                    return null;
                } else {
                    cert = certs[0];
                }
                if (cert instanceof X509Certificate) {
                    Vector foundRDN = splitAndTrim(((X509Certificate) cert).getSubjectDN()
                            .getName());

                    if (subjectRDN.equals(foundRDN)) {
                        aliases.add(alias);
                    }
                }
            }
        } catch (KeyStoreException e) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "keystore");
        }
       
//        if (aliases.isEmpty() && useXKMS()) {
//            String[] xkmsAliases = XKMSCryptoClient.getAliasesForDN(subjectDN,
//                    properties.getProperty(PROP_ID_XKMS_SERVICE_URL));
//            if (xkmsAliases != null) {
//                for (int i = 0; i < xkmsAliases.length; i++) {
//                    aliases.add(xkmsAliases[i]);
//                }
//            }
//        }
        
        
        // Convert the vector into an array
        String[] result = new String[aliases.size()];
        for (int i = 0; i < aliases.size(); i++) {
            result[i] = (String) aliases.elementAt(i);
        }
        return result;

    }

    private String getAliasForX509Cert(String issuer, BigInteger serialNumber,
            boolean useSerialNumber, KeyStore ks) throws WSSecurityException {
        Vector issuerRDN = splitAndTrim(issuer);
        X509Certificate x509cert;
        Vector certRDN;
        Certificate cert;
        try {
            for (Enumeration e = ks.aliases(); e.hasMoreElements();) {
                String alias = (String) e.nextElement();
                Certificate[] certs = this.getCertificates(alias);

                if (certs == null || certs.length == 0) {
                    return null;
                } else {
                    cert = certs[0];
                }
                if (!(cert instanceof X509Certificate)) {
                    continue;
                }
                x509cert = (X509Certificate) cert;
                if (useSerialNumber && x509cert.getSerialNumber().compareTo(serialNumber) == 0) {
                    certRDN = splitAndTrim(x509cert.getIssuerDN().getName());
                    if (certRDN.equals(issuerRDN)) {
                        return alias;
                    }
                }
            }
        } catch (KeyStoreException e) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "keystore");
        }
        return null;
    }

    private Vector splitAndTrim(String inString) {
        X509NameTokenizer nmTokens = new X509NameTokenizer(inString);
        Vector vr = new Vector();

        while (nmTokens.hasMoreTokens()) {
            vr.add(nmTokens.nextToken());
        }
        java.util.Collections.sort(vr);
        return vr;
    }

    private boolean validateCertPath(KeyStore ks, Certificate[] certs) throws WSSecurityException {

        try {

            // Generate cert path
            java.util.List certList = java.util.Arrays.asList(certs);
            CertPath path = this.getCertificateFactory().generateCertPath(certList);

            // Use the certificates in the keystore as TrustAnchors
            PKIXParameters param = new PKIXParameters(ks);

            // Do not check a revocation list
            param.setRevocationEnabled(false);

            // Verify the trust path using the above settings
            String provider = properties
                    .getProperty("org.apache.ws.security.crypto.merlin.cert.provider");
            CertPathValidator certPathValidator;
            if (provider == null || provider.length() == 0) {
                certPathValidator = CertPathValidator.getInstance("PKIX");
            } else {
                certPathValidator = CertPathValidator.getInstance("PKIX", provider);
            }
            certPathValidator.validate(path, param);
        } catch (NoSuchProviderException ex) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "certpath",
                    new Object[] { ex.getMessage() }, ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "certpath",
                    new Object[] { ex.getMessage() }, ex);
        } catch (CertificateException ex) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "certpath",
                    new Object[] { ex.getMessage() }, ex);
        } catch (InvalidAlgorithmParameterException ex) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "certpath",
                    new Object[] { ex.getMessage() }, ex);
        } catch (CertPathValidatorException ex) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "certpath",
                    new Object[] { ex.getMessage() }, ex);
        } catch (KeyStoreException ex) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "certpath",
                    new Object[] { ex.getMessage() }, ex);
        }

        return true;
    }

    //************* TODO fix these problems all of the above method ..... temporary

  

    
    /**
     * Returns the type of the keystore
     *
     * @param ksId keystore identifier
     *
     * @return The Key Store Type
     */
    private String getKeyStoreType(String ksId) throws Exception {
        String path = SecurityConstants.KEY_STORES + "/" + ksId;
        Resource resource = (Resource) registry.get(path);
        return resource.getProperty(SecurityConstants.PROP_TYPE);

    }

    /**
     * Get the provider name of the keystore
     *
     * @param ksId keystore identifier
     *
     * @return The Key Store Provider
     */
    private String getKeyStoreProvider(String ksId) throws Exception {
        String path = SecurityConstants.KEY_STORES + "/" + ksId;
        Resource resource = (Resource) registry.get(path);
        return resource.getProperty(SecurityConstants.PROP_PROVIDER);

    }

    private boolean useXKMS() {
        
         if (useXkms != null) {
             return useXkms.booleanValue();
         }

         AxisConfiguration axisConfiguration =
                 SecurityMgtServiceComponent.getServerConfigurationContext().getAxisConfiguration();
         Parameter parameter = axisConfiguration.getParameter("XKMSConfig");

         if (parameter == null) {
             useXkms = Boolean.FALSE;
             return useXkms.booleanValue();
         }

         OMElement parameterElement = parameter.getParameterElement();
         OMAttribute attribute = parameterElement.getAttribute(new QName("enabled"));

         if (attribute != null) {
             String value = attribute.getAttributeValue();
             useXkms = Boolean.valueOf(value);
         }

         OMElement urlElement = parameterElement
                 .getFirstChildWithName(new QName("URL"));
         properties.setProperty(PROP_ID_XKMS_SERVICE_URL, urlElement.getText());

         OMElement passPhraseElement = parameterElement
                 .getFirstChildWithName(new QName("PassPhrase"));
         properties.setProperty(PROP_ID_XKMS_SERVICE_PASS_PHRASE,
                                passPhraseElement.getText());

         return useXkms.booleanValue();
    }

}
