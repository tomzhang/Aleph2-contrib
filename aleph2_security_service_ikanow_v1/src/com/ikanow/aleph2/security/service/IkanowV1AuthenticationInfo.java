/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.ikanow.aleph2.security.service;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;

public class IkanowV1AuthenticationInfo implements AuthenticationInfo {

	
	private AuthenticationBean authenticationBean;
	public AuthenticationBean getAuthenticationBean() {
		return authenticationBean;
	}
	private SimplePrincipalCollection principalCollection;

	public IkanowV1AuthenticationInfo(AuthenticationBean ab){
		this.authenticationBean = ab;
		this.principalCollection =  new SimplePrincipalCollection(ab.get_id(),IkanowV1Realm.class.getSimpleName());
	}
	/**
	 * 
	 */
	private static final long serialVersionUID = -8123608158013803489L;

	@Override
	public PrincipalCollection getPrincipals() {
		
		return principalCollection;
	}

	@Override
	public Object getCredentials() {
		return authenticationBean.getPassword();
	}

}