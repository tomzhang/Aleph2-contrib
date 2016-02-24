/*******************************************************************************
 * Copyright 2016, The IKANOW Open Source Project.
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

package com.ikanow.aleph2.search_service.elasticsearch.utils;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

import scala.Tuple3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;

import fj.data.Validation;

/**
 * @author Alex
 *
 */
public class TestElasticsearchHiveUtils {

	public static ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());
	
	@Test
	public void test_validateSchema() {
		
		// Few different cases
		
		final DataBucketBean base_bucket = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/test")
				.done().get();
				
		final DataSchemaBean.DataWarehouseSchemaBean base_schema =
				BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.class)
				.done().get();
		
		// No main table at all		
		{
			final List<String> l = ElasticsearchHiveUtils.validateSchema(base_schema, base_bucket, null);
			assertEquals(1, l.size());
			assertEquals(l.get(0), ErrorUtils.get(ElasticsearchHiveUtils.ERROR_INVALID_MAIN_TABLE, base_bucket.full_name()));
		}

		// No schema, sql selected, tried to set a view in the main table
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::sql_query, "SELECT * from table")
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::view_name, "invalid")
					.done().get();
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.clone(base_schema)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
					.done();			
			
			final List<String> l = ElasticsearchHiveUtils.validateSchema(test_schema, base_bucket, null);
			assertEquals(3, l.size());
			assertEquals(l.get(0), ErrorUtils.get(ElasticsearchHiveUtils.ERROR_AUTO_SCHEMA_NOT_YET_SUPPORTED, base_bucket.full_name(), "main_table"));			
			assertEquals(l.get(1), ErrorUtils.get(ElasticsearchHiveUtils.ERROR_SQL_QUERY_NOT_YET_SUPPORTED, base_bucket.full_name()));			
			assertEquals(l.get(2), ErrorUtils.get(ElasticsearchHiveUtils.ERROR_INVALID_MAIN_TABLE_FIELD, base_bucket.full_name(), "view_name"));			
		}
		
		// Views, invalid schema
		
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::table_format, 
								ImmutableMap.<String, Object>of("test", "NOT_VALID_FIELD"))
					.done().get();
			
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.clone(base_schema)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
						.with(DataSchemaBean.DataWarehouseSchemaBean::views, Arrays.asList(test_table))
					.done();			
			
			final List<String> l = ElasticsearchHiveUtils.validateSchema(test_schema, base_bucket, null);
			assertEquals(2, l.size());
			assertEquals(l.get(0), ErrorUtils.get(ElasticsearchHiveUtils.ERROR_SCHEMA_ERROR, base_bucket.full_name(), "main_table", "Unrecognized element in schema declaration after CREATE EXTERNAL TABLE test__f911f6d77ac9 (test : \"NOT_VALID_FIELD\""));			
			assertEquals(l.get(1), ErrorUtils.get(ElasticsearchHiveUtils.ERROR_NO_VIEWS_ALLOWED_YET, base_bucket.full_name()));			
		}		
		
		// Finally valid:
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::table_format, 
								ImmutableMap.<String, Object>of("test", "STRING"))
					.done().get();
			
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.clone(base_schema)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
						.with(DataSchemaBean.DataWarehouseSchemaBean::views, Arrays.asList())
					.done();			
			
			final List<String> l = ElasticsearchHiveUtils.validateSchema(test_schema, base_bucket, null);
			assertEquals(0, l.size());
		}		
	}
	
	@Test
	public void test_getTableName() {
		
		final DataBucketBean bucket = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/test")
				.done().get();		
		
		// name and DB specified
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::database_name, "testdb")
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::name_override, "test_name")
					.done().get();
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
					.done().get();
			
			assertEquals("testdb.test_name", ElasticsearchHiveUtils.getTableName(bucket, test_schema));
		}
		// name only specified
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::name_override, "test_name")
					.done().get();
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
					.done().get();
			
			assertEquals("test_name", ElasticsearchHiveUtils.getTableName(bucket, test_schema));
		}
		// db only specified
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::database_name, "testdb")
					.done().get();
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
					.done().get();
			
			assertEquals("testdb.test__f911f6d77ac9", ElasticsearchHiveUtils.getTableName(bucket, test_schema));
		}
		// neither specified
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
					.done().get();
			
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
					.done().get();
			
			assertEquals("test__f911f6d77ac9", ElasticsearchHiveUtils.getTableName(bucket, test_schema));
		}
	}
	
	@Test
	public void test_deleteHiveSchema() {		
		final DataBucketBean bucket = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/test")
				.done().get();
		
		final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
				BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
					.with(DataSchemaBean.DataWarehouseSchemaBean.Table::database_name, "testdb")
					.with(DataSchemaBean.DataWarehouseSchemaBean.Table::name_override, "test_name")
				.done().get();
		
		final DataSchemaBean.DataWarehouseSchemaBean test_schema =
				BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.class)
					.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
				.done().get();
		
		assertEquals("DROP TABLE IF EXISTS testdb.test_name", ElasticsearchHiveUtils.deleteHiveSchema(bucket, test_schema));
	}	
	
	@Test
	public void test_generateFullHiveSchema() {		
		
		final DataBucketBean base_bucket = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/test")
				.done().get();
				
		final DataSchemaBean.DataWarehouseSchemaBean base_schema =
				BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.class)
				.done().get();
		
		// Normal case
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::table_format, 
								ImmutableMap.<String, Object>of("test", "STRING"))
					.done().get();
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.clone(base_schema)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
						.with(DataSchemaBean.DataWarehouseSchemaBean::views, Arrays.asList())
					.done();			
			
			Validation<String, String> res = ElasticsearchHiveUtils.generateFullHiveSchema(base_bucket, test_schema);
			
			assertTrue(res.isSuccess());
			final String expected = "CREATE EXTERNAL TABLE test__f911f6d77ac9 (test STRING) STORED BY 'org.elasticsearch.hadoop.hive.EsStorageHandler' TBLPROPERTIES('es.resource' = 'r__test__f911f6d77ac9/_all') ";
			assertEquals(expected, res.success());
		}
		
		// Just check that an errored case fails out normally
		{
			final DataSchemaBean.DataWarehouseSchemaBean.Table test_table =
					BeanTemplateUtils.build(DataSchemaBean.DataWarehouseSchemaBean.Table.class)
						.with(DataSchemaBean.DataWarehouseSchemaBean.Table::table_format, 
								ImmutableMap.<String, Object>of("test", "FAIL"))
					.done().get();
			
			final DataSchemaBean.DataWarehouseSchemaBean test_schema =
					BeanTemplateUtils.clone(base_schema)
						.with(DataSchemaBean.DataWarehouseSchemaBean::main_table, test_table)
						.with(DataSchemaBean.DataWarehouseSchemaBean::views, Arrays.asList())
					.done();			
			
			Validation<String, String> res = ElasticsearchHiveUtils.generateFullHiveSchema(base_bucket, test_schema);
			
			assertTrue(res.isFail());			
		}
	}
	
	@Test
	public void test_generatePartialHiveSchema() throws IOException {
		final String hive_schema = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/utils/sample_hive_schema.json"), Charsets.UTF_8);
		final JsonNode hive_schema_json = _mapper.readTree(hive_schema);
		
		final Validation<String, String> test_success = ElasticsearchHiveUtils.generatePartialHiveSchema("PREFIX-", hive_schema_json, true);
		assertTrue("Failed: " + (test_success.isFail() ? test_success.fail() : "(no error)"), test_success.isSuccess());
		final String hand_checked_results = "PREFIX-(primitive_field BIGINT,raw_struct STRUCT<raw_field_1: VARCHAR,raw_field_2: DATE>,raw_map MAP<STRING, TIMESTAMP>,raw_array ARRAY<TINYINT>,raw_union_1 UNIONTYPE< SMALLINT>,raw_union_2 UNIONTYPE< INT, BOOLEAN>,nested_struct STRUCT<raw_field_1: FLOAT,nested_field2: STRUCT<nested_raw_1: DOUBLE,nested_nested_2: ARRAY<BINARY>>>,nested_map MAP<STRING, STRUCT<raw_field_1: CHAR,nested_nested_2: UNIONTYPE< STRING, STRING, DATE>>>,nested_array_1 ARRAY<ARRAY<STRING>>,nested_array_2 ARRAY<STRUCT<raw_field_1: STRING>>,nested_union UNIONTYPE< STRUCT<raw_field_1: STRING>>)";
		assertEquals(hand_checked_results, test_success.success());		
	}

	////////////////////////////////////	
	
	// (these are a bit harder to test because they are tied in to external resources, just do a bare minimum)
	
	@Test
	public void test_getParamsFromHiveConfig() {
		
		{
			final Configuration config = new Configuration();
			
			config.set("javax.jdo.option.ConnectionURL", "jdbc:mysql://hive-server.network.ikanow.com/hive?createDatabaseIfNotExist=true");
			config.set("hive.server2.thrift.port", "10000");
			config.set("javax.jdo.option.ConnectionUserName", "test_user");
			
			final Tuple3<String, String, String> t3 = ElasticsearchHiveUtils.getParamsFromHiveConfig(config);
			final String connection_url = t3._1();
			final String user_name = t3._2();
			final String password = t3._3();
			
			assertEquals("jdbc:hive2://hive-server.network.ikanow.com:10000", connection_url);
			assertEquals("test_user", user_name);
			assertEquals("", password);
		}
	}
	
	@Test 
	public void test_getHiveConfiguration() {
		//TODO
	}
	
	@Test
	public void test_registerHiveTable() {
		//TODO
	}
	
}
