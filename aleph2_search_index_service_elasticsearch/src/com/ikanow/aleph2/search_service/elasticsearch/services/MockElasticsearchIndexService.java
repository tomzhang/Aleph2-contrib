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
package com.ikanow.aleph2.search_service.elasticsearch.services;

import java.util.Arrays;
import java.util.List;

import com.google.inject.Inject;
import com.google.inject.Module;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean;
import com.ikanow.aleph2.search_service.elasticsearch.module.MockElasticsearchIndexServiceModule;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.IElasticsearchCrudServiceFactory;

/** Elasticsearch implementation of the SearchIndexService/TemporalService/ColumnarService (mock implementation)
 * @author Alex
 *
 */
public class MockElasticsearchIndexService extends ElasticsearchIndexService {

	/** Guice generated constructor
	 * @param crud_factory
	 */
	@Inject
	public MockElasticsearchIndexService(
			final IServiceContext service_context,
			final IElasticsearchCrudServiceFactory crud_factory,
			final ElasticsearchIndexServiceConfigBean configuration
			)
	{
		super(service_context, crud_factory, configuration);
	}
	
	/** This service needs to load some additional classes via Guice. Here's the module that defines the bindings
	 * @return
	 */
	public static List<Module> getExtraDependencyModules() {
		return Arrays.asList((Module)new MockElasticsearchIndexServiceModule());
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IExtraDependencyLoader#youNeedToImplementTheStaticFunctionCalled_getExtraDependencyModules()
	 */
	@Override
	public void youNeedToImplementTheStaticFunctionCalled_getExtraDependencyModules() {
		// (done see above)		
	}
}
