/*
 *Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *WSO2 Inc. licenses this file to you under the Apache License,
 *Version 2.0 (the "License"); you may not use this file except
 *in compliance with the License.
 *You may obtain a copy of the License at
 *
 *http://www.apache.org/licenses/LICENSE-2.0
 *
 *Unless required by applicable law or agreed to in writing,
 *software distributed under the License is distributed on an
 *"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *KIND, either express or implied.  See the License for the
 *specific language governing permissions and limitations
 *under the License.
 */

package org.wso2.carbon.identity.application.common.model;

import java.io.Serializable;
import java.util.Iterator;

import org.apache.axiom.om.OMElement;

public class JustInTimeProvisioningConfig extends InboundProvisioningConfig implements Serializable {

    /**
     * 
     */
    private static final long serialVersionUID = 5899735295450764828L;

    private String userStoreClaimUri;

    /**
     * 
     * @return
     */
    public String getUserStoreClaimUri() {
        return userStoreClaimUri;
    }

    /**
     * 
     * @param userStoreClaimUri
     */
    public void setUserStoreClaimUri(String userStoreClaimUri) {
        this.userStoreClaimUri = userStoreClaimUri;
    }

    /*
     * <JustInTimeProvisioningConfig> <UserStoreClaimUri></UserStoreClaimUri>
     * <ProvisioningUserStore></ProvisioningUserStore> <ProvisioningEnabled></ProvisioningEnabled>
     * </JustInTimeProvisioningConfig>
     */
    public static JustInTimeProvisioningConfig build(OMElement justInTimeProvisioningConfigOM) {
        JustInTimeProvisioningConfig justInTimeProvisioningConfig = new JustInTimeProvisioningConfig();

        if (justInTimeProvisioningConfigOM == null) {
            return justInTimeProvisioningConfig;
        }

        Iterator<?> iter = justInTimeProvisioningConfigOM.getChildElements();

        while (iter.hasNext()) {
            OMElement element = (OMElement) (iter.next());
            String elementName = element.getLocalName();

            if (elementName.equals("UserStoreClaimUri")) {
                justInTimeProvisioningConfig.setUserStoreClaimUri(element.getText());
            } else if (elementName.equals("ProvisioningUserStore")) {
                justInTimeProvisioningConfig.setProvisioningUserStore(element.getText());
            } else if (elementName.equals("IsProvisioningEnabled")) {
                if (element.getText() != null && element.getText().trim().length() > 0) {
                    justInTimeProvisioningConfig.setProvisioningEnabled(Boolean
                            .parseBoolean(element.getText()));
                }
            }
        }

        return justInTimeProvisioningConfig;
    }

}
