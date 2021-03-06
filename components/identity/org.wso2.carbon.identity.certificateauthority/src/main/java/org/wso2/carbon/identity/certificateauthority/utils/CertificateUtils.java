/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.certificateauthority.utils;

import org.bouncycastle.openssl.PEMWriter;
import org.wso2.carbon.identity.certificateauthority.data.Certificate;
import org.wso2.carbon.identity.certificateauthority.data.CertificateDTO;

import java.io.IOException;
import java.io.StringWriter;
import java.security.cert.X509Certificate;

public class CertificateUtils {

    public static String getEncodedCertificate(X509Certificate certificate) {
        try {
            StringWriter stringWriter = new StringWriter();
            PEMWriter writer = new PEMWriter(stringWriter);
            writer.writeObject(certificate);
            writer.close();
            return stringWriter.toString();
        } catch (IOException ignored) {
            return "";
        }
    }


    public static CertificateDTO getCertificateDTO(Certificate certificate) {
        return new CertificateDTO(certificate.getCertificateMetaInfo(), getEncodedCertificate(certificate
                .getPublicCertificate()), certificate.getTenantID(), certificate.getUserStoreDomain());
    }
}
