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
package com.ikanow.aleph2.analytics.storm.modules;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import com.google.inject.AbstractModule;
import com.ikanow.aleph2.analytics.storm.data_model.IStormController;
import com.ikanow.aleph2.analytics.storm.services.NoStormController;
import com.ikanow.aleph2.analytics.storm.services.RemoteStormController;
import com.ikanow.aleph2.analytics.storm.utils.StormControllerUtil;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.data_model.utils.PropertiesUtils;

import fj.data.Either;

/** Defines guice dependencies
 *  NO TEST COVERAGE - TEST BY HAND IF CHANGED
 * @author Alex
 */
public class StormAnalyticTechnologyModule extends AbstractModule {
	private static final Logger _logger = LogManager.getLogger();	

	private static IStormController _controller;
	
	/* (non-Javadoc)
	 * @see com.google.inject.AbstractModule#configure()
	 */
	@Override
	public void configure() {
		
		_logger.info("initializing storm harvest technology");
		
		final IStormController instance = getController();
		this.bind(IStormController.class).toInstance(instance);					
	}
	
	/** Initializes the storm instance
	 * @return a real storm controller if possible, else a no controller
	 */
	@SuppressWarnings({ "unchecked" })
	public static IStormController getController() {
		synchronized (IStormController.class) {
			if (null != _controller) {
				return _controller;
			}
			final GlobalPropertiesBean globals = Lambdas.get(() -> {
				try {
					return BeanTemplateUtils.from(PropertiesUtils.getSubConfig(ModuleUtils.getStaticConfig(), GlobalPropertiesBean.PROPERTIES_ROOT).orElse(null), GlobalPropertiesBean.class);
				} catch (IOException e) {
					_logger.error(ErrorUtils.getLongForm("Couldn't set globals property bean in storm harvest tech onInit: {0}", e));
					return null;
				}
			});
			if (null == globals) {
				return new NoStormController();
			}
			_logger.info("Loading storm config from: " + globals.local_yarn_config_dir() + File.separator + "storm.yaml");
			Yaml yaml = new Yaml();
			InputStream input;
			Map<String, Object> object;
			try {
				input = new FileInputStream(new File(globals.local_yarn_config_dir() + File.separator + "storm.yaml"));
				object = (Map<String, Object>) yaml.load(input);
			} catch (FileNotFoundException e) {
				_logger.error(ErrorUtils.getLongForm("Error reading storm.yaml in storm harvest tech onInit: {0}", e));
				object = new HashMap<String, Object>();
			}
						
			if ( object.containsKey(backtype.storm.Config.NIMBUS_HOST) ) {
				_logger.info("starting in remote mode v6 - pre storm 0.10.x (nimbus_host)");
				_logger.info(object.get(backtype.storm.Config.NIMBUS_HOST));
				//run in distributed mode - hdp 2.2
				IStormController storm_controller = StormControllerUtil.getRemoteStormController(
						Either.left((String)object.get(backtype.storm.Config.NIMBUS_HOST)), 
						(int)object.get(backtype.storm.Config.NIMBUS_THRIFT_PORT), 
						(String)object.get(backtype.storm.Config.STORM_THRIFT_TRANSPORT_PLUGIN));
				
				return (_controller = storm_controller);
			} else if (object.containsKey(RemoteStormController.NIMBUS_SEEDS)) {
				_logger.info("starting in remote mode v6 - post storm 0.10.x (nimbus_seeds)");
				_logger.info(object.get(RemoteStormController.NIMBUS_SEEDS));
				//run in distributed mode - hdp 2.3
				IStormController storm_controller = StormControllerUtil.getRemoteStormController(
						Either.right((List<String>)object.get(RemoteStormController.NIMBUS_SEEDS)), 
						(int)object.get(backtype.storm.Config.NIMBUS_THRIFT_PORT), 
						(String)object.get(backtype.storm.Config.STORM_THRIFT_TRANSPORT_PLUGIN));
				
				return (_controller = storm_controller);
			}
			else {
				return (_controller = new NoStormController());	
			}		
		}
	}
}
