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
package com.ikanow.aleph2.storage_service_hdfs.services;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.AccessControlException;
import org.apache.http.impl.cookie.DateUtils;
import org.junit.Test;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadBean;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.StorageSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.TimeUtils;

public class TestMockHdfsStorageSystem {
	
	@Test
	public void test(){
		final String temp_dir = System.getProperty("java.io.tmpdir") + File.separator;
		
		GlobalPropertiesBean globals = BeanTemplateUtils.build(GlobalPropertiesBean.class)
											.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
											.with(GlobalPropertiesBean::local_yarn_config_dir, System.getenv("HADOOP_CONF_DIR")).done().get();
	
		MockHdfsStorageService storageService = new MockHdfsStorageService(globals);
	
		assertEquals(globals.distributed_root_dir(), storageService.getRootPath());
		assertEquals(1, storageService.getUnderlyingArtefacts().size());
		
		FileContext fs1 = storageService.getUnderlyingPlatformDriver(FileContext.class, Optional.<String>empty()).get();
		assertNotNull(fs1);

		RawLocalFileSystem fs2 = storageService.getUnderlyingPlatformDriver(org.apache.hadoop.fs.RawLocalFileSystem.class,Optional.<String>empty()).get();
		assertNotNull(fs2); 

		AbstractFileSystem fs3 = storageService.getUnderlyingPlatformDriver(AbstractFileSystem.class,Optional.<String>empty()).get();
		assertNotNull(fs3); 			
		
		assertFalse("Not found", storageService.getUnderlyingPlatformDriver(null, Optional.empty()).isPresent());
		assertFalse("Not found", storageService.getUnderlyingPlatformDriver(String.class, Optional.empty()).isPresent());			
		assertFalse("Not found", storageService.getUnderlyingPlatformDriver(String.class, Optional.of("{}")).isPresent());			
	}

	@Test
	public void test_validate(){
		
		final String temp_dir = System.getProperty("java.io.tmpdir") + File.separator;
		
		GlobalPropertiesBean globals = BeanTemplateUtils.build(GlobalPropertiesBean.class)
											.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
											.with(GlobalPropertiesBean::local_yarn_config_dir, System.getenv("HADOOP_CONF_DIR")).done().get();
	
		MockHdfsStorageService storageService = new MockHdfsStorageService(globals);
	
		// Works
		{
			final DataBucketBean bucket = 
					BeanTemplateUtils.build(DataBucketBean.class)
						.with(DataBucketBean::full_name, "/test/validate/bucket")
					.done().get();
			
			Tuple2<String, List<BasicMessageBean>> res = storageService.validateSchema(null, bucket);
			assertEquals("Validation: " + res._2().stream().map(BasicMessageBean::message).collect(Collectors.joining("\n")), 0, res._2().size());
			assertEquals((temp_dir.replace(File.separator,  "/") + "/data/test/validate/bucket/managed_bucket/").replaceAll("//", "/"), 
							res._1().replace(File.separator, "/").replaceAll("//", "/"));
		}
		// Works some more
		
		Arrays.asList("gz", "gzip", "sz", "snappy", "fr.sz", "snappy_framed")
				.stream()
				.map(s -> buildBucketWithCodec(s))
				.forEach(bucket -> {
					Tuple2<String, List<BasicMessageBean>> res = storageService.validateSchema(bucket.data_schema().storage_schema(), bucket);
					assertEquals("Validation: " + res._2().stream().map(BasicMessageBean::message).collect(Collectors.joining("\n")), 0, res._2().size());
					assertEquals((temp_dir.replace(File.separator,  "/") + "/data/" + bucket.full_name() + IStorageService.BUCKET_SUFFIX).replaceAll("//", "/"), 
							res._1().replace(File.separator, "/").replaceAll("//", "/"));
				});
		
		// Fails
		
		Arrays.asList("banana")
				.stream()
				.map(s -> buildBucketWithCodec(s))
				.forEach(bucket -> {
					Tuple2<String, List<BasicMessageBean>> res = storageService.validateSchema(bucket.data_schema().storage_schema(), bucket);
					assertEquals("Validation: " + res._2().stream().map(BasicMessageBean::message).collect(Collectors.joining("\n")), 1, res._2().size());
					assertEquals("", res._1());
				});
	}

	private DataBucketBean buildBucketWithCodec(String codec) {
		return BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, "/test/bucket/codec/" + codec)
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
							.with(DataSchemaBean::storage_schema,
								BeanTemplateUtils.build(StorageSchemaBean.class)
									.with(StorageSchemaBean::json, 
											BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
												.with(StorageSchemaBean.StorageSubSchemaBean::codec, codec)
											.done().get())
								.done().get()
							)
						.done().get())
				.done().get();
	}	
	
	@Test
	public void test_handleBucketDeletionRequest() throws InterruptedException, ExecutionException, IOException {
		// 0) Setup
		final String temp_dir = System.getProperty("java.io.tmpdir") + File.separator;
		
		final GlobalPropertiesBean globals = BeanTemplateUtils.build(GlobalPropertiesBean.class)
				.with(GlobalPropertiesBean::local_yarn_config_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.with(GlobalPropertiesBean::local_root_dir, temp_dir)
				.with(GlobalPropertiesBean::local_cached_jar_dir, temp_dir)
				.done().get();
		
		final MockHdfsStorageService storage_service = new MockHdfsStorageService(globals);
		
		// 1) Set up bucket (code taken from management_db_service)
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class).with(DataBucketBean::full_name, "/test/delete/bucket").done().get();
		final String bucket_path = System.getProperty("java.io.tmpdir") + "/data/" + bucket.full_name();
		try {
			FileUtils.deleteDirectory(new File(bucket_path));
		}
		catch (Exception e) {}
		setup_bucket(storage_service, bucket, 
				Arrays.asList( //(create some secondary buffers also)
						"$sec_test1",
						"$sec_test2",
						"$sec_test3"
						));
						
		// Check nothing goes wrong when bucket doesn't exist
		final DataBucketBean bucket2 = BeanTemplateUtils.build(DataBucketBean.class).with(DataBucketBean::full_name, "/test/delete/bucket_not_exist").done().get();
		final String bucket_path2 = System.getProperty("java.io.tmpdir") + "/data/" + bucket2.full_name();
		FileUtils.deleteDirectory(new File(bucket_path2));
		assertFalse("The file path for bucket2 does not exist", new File(bucket_path2).exists());
		
		final CompletableFuture<BasicMessageBean> res2 = storage_service.getDataService().get().handleBucketDeletionRequest(bucket2, Optional.empty(), false);
		assertEquals(true, res2.get().success());
		
		//(check didn't create anything)
		
		assertFalse("No bucket2 paths were created", new File(bucket_path2).exists());		

		// Check main bucket deletion:						
						
		check_handleBucketDeletion_preChecks(storage_service, bucket, "current", true);
		check_handleBucketDeletion_preChecks(storage_service, bucket, "sec_test1", true);
		check_handleBucketDeletion_preChecks(storage_service, bucket, "sec_test2", true);
		check_handleBucketDeletion_preChecks(storage_service, bucket, "sec_test3", true);
		{
			final CompletableFuture<BasicMessageBean> res1 = storage_service.getDataService().get().handleBucketDeletionRequest(bucket, Optional.empty(), false);
			assertEquals("Handle deletion bucket requesed should have worked:" + res1.get().message(), true, res1.get().success());		
			System.out.println("handleDeletion output: " + res1.get().message());
		}		
		check_handleBucketDeletion_postChecks(storage_service, bucket, "current", false);
		check_handleBucketDeletion_preChecks(storage_service, bucket, "sec_test1", false);
		check_handleBucketDeletion_preChecks(storage_service, bucket, "sec_test2", false);
		check_handleBucketDeletion_preChecks(storage_service, bucket, "sec_test3", false);
		
		// OK now delete a secondary buffer		
		{
			final CompletableFuture<BasicMessageBean> res1 = storage_service.getDataService().get().handleBucketDeletionRequest(bucket, Optional.of("sec_test2"), false);
			assertEquals("Handle deletion bucket requesed should have worked:" + res1.get().message(), true, res1.get().success());		
			System.out.println("handleDeletion output: " + res1.get().message());
		}		
		check_handleBucketDeletion_preChecks(storage_service, bucket, "sec_test1", false);
		check_handleBucketDeletion_postChecks(storage_service, bucket, "sec_test2", false);
		check_handleBucketDeletion_preChecks(storage_service, bucket, "sec_test3", false);
		
		// OK now delete a secondary buffer with prejudice
		 {
			final CompletableFuture<BasicMessageBean> res1 = storage_service.getDataService().get().handleBucketDeletionRequest(bucket, Optional.of("sec_test1"), true);
			assertEquals("Handle deletion bucket requesed should have worked:" + res1.get().message(), true, res1.get().success());		
			System.out.println("handleDeletion output: " + res1.get().message());
		}		
		check_handleBucketDeletion_postChecks(storage_service, bucket, "sec_test1", true);
		
		// Finally: check that deleting a bucket does nothing:
		{
			final CompletableFuture<BasicMessageBean> res1 = storage_service.getDataService().get().handleBucketDeletionRequest(bucket, Optional.empty(), true);
			assertEquals("Handle deletion bucket requesed should have worked:" + res1.get().message(), true, res1.get().success());
			System.out.println("handleDeletion output: " + res1.get().message());
			assertTrue(res1.get().message().contains("Done nothing"));
		}		
		 
	}
	
	protected void check_handleBucketDeletion_preChecks(final MockHdfsStorageService storage_service, DataBucketBean bucket, String buffer_name, boolean create) throws IOException, InterruptedException, ExecutionException {
		final String bucket_path = System.getProperty("java.io.tmpdir") + "/data/" + bucket.full_name();
		assertTrue("The file path has been created", new File(bucket_path + "/managed_bucket").exists());
		assertTrue("The raw data path has been created", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + buffer_name).exists());
		assertTrue("The json data path has been created", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + buffer_name).exists());
		assertTrue("The processed data path has been created", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + buffer_name).exists());
		assertTrue("The transient data path has been created", new File(bucket_path + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "test_job/" + buffer_name).exists());
		if (create) {
			FileUtils.writeStringToFile(new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + buffer_name + "/test"), "");
			FileUtils.writeStringToFile(new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + buffer_name + "/test"), "");
			FileUtils.writeStringToFile(new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + buffer_name + "/test"), "");
			FileUtils.writeStringToFile(new File(bucket_path + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "test_job/" + buffer_name + "/test"), "");
		}
		assertTrue("The raw data path extra file has been created", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + buffer_name + "/test").exists());
		assertTrue("The json data path extra file has been created", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + buffer_name + "/test").exists());
		assertTrue("The processed data path extra file has been created", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + buffer_name + "/test").exists());
		assertTrue("The transient data path extra file has been created", new File(bucket_path + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "test_job/"  + buffer_name + "/test").exists());
	}	

	protected void check_handleBucketDeletion_postChecks(final MockHdfsStorageService storage_service, DataBucketBean bucket, String buffer_name, boolean full_delete) throws IOException, InterruptedException, ExecutionException {
		final String bucket_path = System.getProperty("java.io.tmpdir") + "/data/" + bucket.full_name();

		// Test:
		
		// Full filesystem exists
		assertEquals("The raw data path still present", !full_delete, new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + buffer_name).exists());
		assertEquals("The json data path still present", !full_delete, new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + buffer_name).exists());
		assertEquals("The processed data path still present", !full_delete, new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + buffer_name).exists());
		assertEquals("The transient data path still present", !full_delete, new File(bucket_path + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "test_job/"  + buffer_name).exists());
		
		if (full_delete) {
			//(the main path still exists)
			assertTrue("Tha main bucket path still present", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX).exists());
		}
		
		// Data directories no longer exist
		if (!full_delete) {
			assertFalse("The raw data path has been deleted (and re-created)", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + buffer_name + "/test").exists());
			assertFalse("The json data path has been deleted (and re-created)", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + buffer_name + "/test").exists());
			assertFalse("The processed data path has been deleted (and re-created)", new File(bucket_path + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + buffer_name + "/test").exists());
			assertFalse("The transient data path has been deleted (and re-created)", new File(bucket_path + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "test_job/"  + buffer_name + "/test").exists());
		}				
	}
	
	@Test
	public void test_ageOut() throws IOException, InterruptedException, ExecutionException {
		// 0) Setup
		final String temp_dir = System.getProperty("java.io.tmpdir") + File.separator;
		
		final GlobalPropertiesBean globals = BeanTemplateUtils.build(GlobalPropertiesBean.class)
				.with(GlobalPropertiesBean::local_yarn_config_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.with(GlobalPropertiesBean::local_root_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.done().get();
		
		final MockHdfsStorageService storage_service = new MockHdfsStorageService(globals);
		
		// 1) Set up bucket (code taken from management_db_service)
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class)
											.with(DataBucketBean::full_name, "/test/age/out/bucket")
											.with(DataBucketBean::data_schema,
													BeanTemplateUtils.build(DataSchemaBean.class)
														.with(DataSchemaBean::storage_schema,
															BeanTemplateUtils.build(StorageSchemaBean.class)
																.with(StorageSchemaBean::raw, 
																		BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
																			.with(StorageSchemaBean.StorageSubSchemaBean::exist_age_max, "9 days")
																		.done().get())
																.with(StorageSchemaBean::json, 
																		BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
																			.with(StorageSchemaBean.StorageSubSchemaBean::exist_age_max, "6 days")
																		.done().get())
																.with(StorageSchemaBean::processed, 
																		BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
																			.with(StorageSchemaBean.StorageSubSchemaBean::exist_age_max, "1 week")
																		.done().get())
															.done().get()
														)
													.done().get())
										.done().get();
		
		FileUtils.deleteDirectory(new File(System.getProperty("java.io.tmpdir") + File.separator + "/data/" + File.separator + bucket.full_name()));		
		setup_bucket(storage_service, bucket, Arrays.asList("$sec_test"));
		final String bucket_path = System.getProperty("java.io.tmpdir") + File.separator + "/data/" + File.separator + bucket.full_name();
		assertTrue("The file path has been created", new File(bucket_path + "/managed_bucket").exists());

		final long now = new Date().getTime();
		IntStream.range(4, 10).boxed().map(i -> now - (i*1000L*3600L*24L))
			.forEach(Lambdas.wrap_consumer_u(n -> {
				final String pattern = TimeUtils.getTimeBasedSuffix(TimeUtils.getTimePeriod("1 day").success(), Optional.empty());
				final String dir = DateUtils.formatDate(new Date(n), pattern);
				
				FileUtils.forceMkdir(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_RAW + "/" + dir));
				FileUtils.forceMkdir(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_JSON + "/" + dir));
				FileUtils.forceMkdir(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_PROCESSED + "/" + dir));
				FileUtils.forceMkdir(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "/sec_test/" + dir)); // (mini test for secondary)
			}));
		
		// (7 cos includes root)
		assertEquals(7, FileUtils.listFilesAndDirs(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_RAW), DirectoryFileFilter.DIRECTORY, TrueFileFilter.INSTANCE).size());
		assertEquals(7, FileUtils.listFilesAndDirs(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_JSON), DirectoryFileFilter.DIRECTORY, TrueFileFilter.INSTANCE).size());
		assertEquals(7, FileUtils.listFilesAndDirs(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_PROCESSED), DirectoryFileFilter.DIRECTORY, TrueFileFilter.INSTANCE).size());
		assertEquals(7, FileUtils.listFilesAndDirs(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "/sec_test/"), DirectoryFileFilter.DIRECTORY, TrueFileFilter.INSTANCE).size());
		
		// 1) Normal run:
		
		CompletableFuture<BasicMessageBean> cf = storage_service.getDataService().get().handleAgeOutRequest(bucket);
		
		BasicMessageBean res = cf.get();
		
		assertEquals(true, res.success());
		assertTrue("sensible message: " + res.message(), res.message().contains("raw: deleted 1 "));
		assertTrue("sensible message: " + res.message(), res.message().contains("json: deleted 4 "));
		assertTrue("sensible message: " + res.message(), res.message().contains("processed: deleted 3 "));

		assertTrue("Message marked as loggable: " + res.details(), Optional.ofNullable(res.details()).filter(m -> m.containsKey("loggable")).isPresent());
		
		System.out.println("Return from to delete: " + res.message());		
		
		//(+1 including root)
		assertEquals(6, FileUtils.listFilesAndDirs(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_RAW), DirectoryFileFilter.DIRECTORY, TrueFileFilter.INSTANCE).size());
		assertEquals(3, FileUtils.listFilesAndDirs(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_JSON), DirectoryFileFilter.DIRECTORY, TrueFileFilter.INSTANCE).size());
		assertEquals(4, FileUtils.listFilesAndDirs(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_PROCESSED), DirectoryFileFilter.DIRECTORY, TrueFileFilter.INSTANCE).size());
		assertEquals(4, FileUtils.listFilesAndDirs(new File(bucket_path + "/" + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "/sec_test/"), DirectoryFileFilter.DIRECTORY, TrueFileFilter.INSTANCE).size());
		
		// 2) Run it again, returns success but not loggable:
		
		CompletableFuture<BasicMessageBean> cf2 = storage_service.getDataService().get().handleAgeOutRequest(bucket);
		
		BasicMessageBean res2 = cf2.get();
		
		assertEquals(true, res2.success());
		assertTrue("sensible message: " + res2.message(), res2.message().contains("raw: deleted 0 "));
		assertTrue("sensible message: " + res2.message(), res2.message().contains("json: deleted 0 "));
		assertTrue("sensible message: " + res2.message(), res2.message().contains("processed: deleted 0 "));
		assertTrue("Message _not_ marked as loggable: " + res2.details(), !Optional.ofNullable(res2.details()).map(m -> m.get("loggable")).isPresent());
				
		// 3) No temporal settings
		
		final DataBucketBean bucket3 = BeanTemplateUtils.build(DataBucketBean.class)
				.with("full_name", "/test/handle/age/out/delete/not/temporal")
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
						.done().get())
				.done().get();
		
		CompletableFuture<BasicMessageBean> cf3 = storage_service.getDataService().get().handleAgeOutRequest(bucket3);		
		BasicMessageBean res3 = cf3.get();
		// no temporal settings => returns success
		assertEquals(true, res3.success());
		
		// 4) Unparseable temporal settings (in theory won't validate but we can test here)

		final DataBucketBean bucket4 = BeanTemplateUtils.build(DataBucketBean.class)
				.with("full_name", "/test/handle/age/out/delete/temporal/malformed")
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
							.with(DataSchemaBean::storage_schema,
								BeanTemplateUtils.build(StorageSchemaBean.class)
									.with(StorageSchemaBean::json, 
											BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
												.with(StorageSchemaBean.StorageSubSchemaBean::exist_age_max, "bananas")
											.done().get())
								.done().get()
							)
						.done().get())
				.done().get();
		
		CompletableFuture<BasicMessageBean> cf4 = storage_service.getDataService().get().handleAgeOutRequest(bucket4);		
		BasicMessageBean res4 = cf4.get();
		// no temporal settings => returns success
		assertEquals(false, res4.success());
		
	}
	
	@Test
	public void test_getDataWriter() {
		// 0) Setup
		final String temp_dir = System.getProperty("java.io.tmpdir") + File.separator;
		
		final GlobalPropertiesBean globals = BeanTemplateUtils.build(GlobalPropertiesBean.class)
				.with(GlobalPropertiesBean::local_yarn_config_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.with(GlobalPropertiesBean::local_root_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.done().get();
		
		final MockHdfsStorageService storage_service = new MockHdfsStorageService(globals);
		
		// 1) Set up buckets
		
		final DataBucketBean bucket_no_storage = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, "/test/storage/bucket")
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
					.done().get())
				.done().get();
		
		
		final DataBucketBean bucket_full = BeanTemplateUtils.build(DataBucketBean.class)
											.with(DataBucketBean::full_name, "/test/storage/bucket")
											.with(DataBucketBean::data_schema,
													BeanTemplateUtils.build(DataSchemaBean.class)
														.with(DataSchemaBean::storage_schema,
															BeanTemplateUtils.build(StorageSchemaBean.class)
																//(no enabled defaults to true)
																.with(StorageSchemaBean::raw, 
																		BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
																			.with(StorageSchemaBean.StorageSubSchemaBean::enabled, false)
																		.done().get())
																.with(StorageSchemaBean::json, 
																		BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
																			.with(StorageSchemaBean.StorageSubSchemaBean::enabled, false)
																		.done().get())
																.with(StorageSchemaBean::processed, 
																		BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
																			// (no enabled, defaults to true)
																		.done().get())
															.done().get()
														)
													.done().get())
											.with(DataBucketBean::analytic_thread, 
													BeanTemplateUtils.build(AnalyticThreadBean.class)
														.with(AnalyticThreadBean::jobs,
																Arrays.asList(
																	BeanTemplateUtils.build(AnalyticThreadJobBean.class)
																		.with(AnalyticThreadJobBean::name, "test_job")
																	.done().get()
																)
														)
													.done().get()
												)
										.done().get();
		
		final DataBucketBean bucket_disabled = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, "/test/storage/bucket")
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
							.with(DataSchemaBean::storage_schema,
								BeanTemplateUtils.build(StorageSchemaBean.class)
									.with(StorageSchemaBean::enabled, false)
									.with(StorageSchemaBean::raw, 
											BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
												.with(StorageSchemaBean.StorageSubSchemaBean::enabled, true)
											.done().get())
									.with(StorageSchemaBean::json, 
											BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
												.with(StorageSchemaBean.StorageSubSchemaBean::enabled, true)
											.done().get())
									.with(StorageSchemaBean::processed, 
											BeanTemplateUtils.build(StorageSchemaBean.StorageSubSchemaBean.class)
												// (no enabled, defaults to true)
											.done().get())
								.done().get()
							)
						.done().get())
			.done().get();
		
		// Try with invalid stage:

		try {
			storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.of("banana"), Optional.empty()));
			fail("Should have thrown exception");
		}
		catch (Exception e) {}
		
		// Try with invalid stage:job combo
		try {
			storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.of("processing:test_job"), Optional.empty()));
			fail("Should have thrown exception");
		}
		catch (Exception e) {}
		
		// Try with missing job
		try {
			storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.of("transient_output:not_test_job"), Optional.empty()));
			fail("Should have thrown exception");
		}
		catch (Exception e) {}
		
		// Finally check that works with transient and a valid job
		assertTrue("Returns transient_output:test_job",
				storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.of("transient_output:test_job"), Optional.empty())).isPresent()
				);
		
		// Check some disabled cases:
		
		assertEquals(Optional.empty(), storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_no_storage, Optional.of("processed"), Optional.empty())));
		assertEquals(Optional.empty(), storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_disabled, Optional.of("processed"), Optional.empty())));
		assertEquals(Optional.empty(), storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.of("json"), Optional.empty())));
		
		// Check the default case:
		
		assertTrue("Returns processing",
				storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.of("processed"), Optional.empty())).isPresent()
				);
		assertTrue("No JSON",
				!storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.of("json"), Optional.empty())).isPresent()
				);
		assertTrue("No raw",
				!storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.of("raw"), Optional.empty())).isPresent()
				);
		assertTrue("Default returns processing",
				storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, bucket_full, Optional.empty(), Optional.empty())).isPresent()
				);
		
		// Transient input cases
		{
			final DataSchemaBean.StorageSchemaBean.StorageSubSchemaBean test_override = 
					BeanTemplateUtils.build(DataSchemaBean.StorageSchemaBean.StorageSubSchemaBean.class)
						.with(DataSchemaBean.StorageSchemaBean.StorageSubSchemaBean::target_write_settings, 
								BeanTemplateUtils.build(DataSchemaBean.WriteSettings.class)
									.with(DataSchemaBean.WriteSettings::batch_max_objects, 10)
									.with(DataSchemaBean.WriteSettings::batch_flush_interval, 100)
								.done().get()
								)
					.done().get();
			
			final String test_override_str = BeanTemplateUtils.toJson(test_override).toString();
			
			final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/aleph2_testing/test")
					.done().get();
			
			final DataBucketBean non_test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/non_testing")
					.done().get();
			
			{
				HfdsDataWriteService<JsonNode> test1 = (HfdsDataWriteService<JsonNode>)storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, non_test_bucket, Optional.of("transient_input"), Optional.empty())).get();			
				assertEquals(null, HfdsDataWriteService.getStorageSubSchema(test1._bucket.data_schema().storage_schema(), test1._stage).target_write_settings());
			}
			{
				HfdsDataWriteService<JsonNode> test1 = (HfdsDataWriteService<JsonNode>)storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, test_bucket, Optional.of("transient_input"), Optional.empty())).get();			
				assertEquals(10, HfdsDataWriteService.getStorageSubSchema(test1._bucket.data_schema().storage_schema(), test1._stage).target_write_settings().batch_flush_interval().intValue());
				assertEquals(null, HfdsDataWriteService.getStorageSubSchema(test1._bucket.data_schema().storage_schema(), test1._stage).target_write_settings().batch_max_objects());
			}
			{
				HfdsDataWriteService<JsonNode> test1 = (HfdsDataWriteService<JsonNode>)storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, non_test_bucket, Optional.of("transient_input:" + test_override_str), Optional.empty())).get();			
				assertEquals(100, HfdsDataWriteService.getStorageSubSchema(test1._bucket.data_schema().storage_schema(), test1._stage).target_write_settings().batch_flush_interval().intValue());
				assertEquals(10, HfdsDataWriteService.getStorageSubSchema(test1._bucket.data_schema().storage_schema(), test1._stage).target_write_settings().batch_max_objects().intValue());
			}
			{
				HfdsDataWriteService<JsonNode> test1 = (HfdsDataWriteService<JsonNode>)storage_service.getDataService().flatMap(ds -> ds.getWritableDataService(JsonNode.class, test_bucket, Optional.of("transient_input:" + test_override_str), Optional.empty())).get();			
				assertEquals(100, HfdsDataWriteService.getStorageSubSchema(test1._bucket.data_schema().storage_schema(), test1._stage).target_write_settings().batch_flush_interval().intValue());
				assertEquals(10, HfdsDataWriteService.getStorageSubSchema(test1._bucket.data_schema().storage_schema(), test1._stage).target_write_settings().batch_max_objects().intValue());
			}
		}		
	}
	
	@Test
	public void test_basic_secondaryBuffers() throws AccessControlException, FileAlreadyExistsException, FileNotFoundException, ParentNotDirectoryException, UnsupportedFileSystemException, IllegalArgumentException, IOException {
		// 0) Setup
		final String temp_dir = System.getProperty("java.io.tmpdir") + File.separator;
		
		final GlobalPropertiesBean globals = BeanTemplateUtils.build(GlobalPropertiesBean.class)
				.with(GlobalPropertiesBean::local_yarn_config_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.with(GlobalPropertiesBean::local_root_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.done().get();
		
		final MockHdfsStorageService storage_service = new MockHdfsStorageService(globals);
		
		// Some buckets
		
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, "/test/storage/bucket")
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
					.done().get())
				.done().get();		
		
		setup_bucket(storage_service, bucket, Collections.emptyList());
		
		// Get primary buffer doesn't work:
		
		assertFalse(storage_service.getDataService().get().getPrimaryBufferName(bucket, Optional.empty()).isPresent());		
		
		// Add some secondary buffers and check they get picked up
		
		final FileContext dfs = storage_service.getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get();
		final String bucket_root = storage_service.getBucketRootPath() + "/" + bucket.full_name();		
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + "test1"), FsPermission.getDirDefault(), true);
		//(skip the current dir once just to check it doesn't cause problems)
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + "test2"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "test3"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED), FsPermission.getDirDefault(), true);
		
		assertEquals(Arrays.asList("test1", "test2", "test3"), storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.empty()).stream().sorted().collect(Collectors.toList()));
		assertEquals(Arrays.asList(), storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.of("transient1")).stream().sorted().collect(Collectors.toList()));

		//(check dedups)
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + "test1"), FsPermission.getDirDefault(), true);
		
		assertEquals(Arrays.asList("test1", "test2", "test3"), storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.empty()).stream().sorted().collect(Collectors.toList()));
		assertEquals(Arrays.asList(), storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.of("transient2")).stream().sorted().collect(Collectors.toList()));
		
		try {
			dfs.delete(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "test3"), true);
		}
		catch (Exception e) {}
		
		assertEquals(Arrays.asList("test1", "test2"), storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.empty()).stream().sorted().collect(Collectors.toList()));
		assertEquals(Arrays.asList(), storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.of("transient3")).stream().sorted().collect(Collectors.toList()));
		
		try {
			dfs.delete(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + "test1"), true);
		}
		catch (Exception e) {}
		
		assertEquals(Arrays.asList("test1", "test2"), storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.empty()).stream().sorted().collect(Collectors.toList()));
		
		// Add some transients:
		
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/transient1"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/transient2"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job2/transient1"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job3/transient3"), FsPermission.getDirDefault(), true);
		
		assertEquals(Arrays.asList("test1", "test2"), 
				storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.empty()).stream().sorted().collect(Collectors.toList()));
		assertEquals(Arrays.asList("transient1", "transient2"), 
				storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.of("job1")).stream().sorted().collect(Collectors.toList()));
		assertEquals(Arrays.asList("transient1"), 
				storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.of("job2")).stream().sorted().collect(Collectors.toList()));
		assertEquals(Arrays.asList("transient3"), 
				storage_service.getDataService().get().getSecondaryBuffers(bucket, Optional.of("job3")).stream().sorted().collect(Collectors.toList()));
	}
	
	@Test
	public void test_switching_secondaryBuffers() throws AccessControlException, FileAlreadyExistsException, FileNotFoundException, ParentNotDirectoryException, UnsupportedFileSystemException, IllegalArgumentException, IOException, InterruptedException, ExecutionException {
		// 0) Setup
		final String temp_dir = System.getProperty("java.io.tmpdir") + File.separator;
		
		final GlobalPropertiesBean globals = BeanTemplateUtils.build(GlobalPropertiesBean.class)
				.with(GlobalPropertiesBean::local_yarn_config_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.with(GlobalPropertiesBean::local_root_dir, temp_dir)
				.with(GlobalPropertiesBean::distributed_root_dir, temp_dir)
				.done().get();
		
		final MockHdfsStorageService storage_service = new MockHdfsStorageService(globals);
		
		// Some buckets
		
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, "/test/storage/bucket")
				.with(DataBucketBean::data_schema,
						BeanTemplateUtils.build(DataSchemaBean.class)
					.done().get())
				.done().get();		
		
		setup_bucket(storage_service, bucket, Collections.emptyList());
		
		final FileContext dfs = storage_service.getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get();
		final String bucket_root = storage_service.getBucketRootPath() + "/" + bucket.full_name();		
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW + "test_exdir"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + "test1"), FsPermission.getDirDefault(), true);
		//(skip the current dir once just to check it doesn't cause problems)
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + "test2"), FsPermission.getDirDefault(), true);
		dfs.create(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + "test2/test2.json"), EnumSet.of(CreateFlag.CREATE, CreateFlag.OVERWRITE)).close();
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON + "test_exdir"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "test3"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED + "test_exdir"), FsPermission.getDirDefault(), true);
		//(transient)
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/current"), FsPermission.getDirDefault(), true);
		//(leave job2 current out to check it doesn't get deleted)
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job3/current"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/test1"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job2/test2"), FsPermission.getDirDefault(), true);
		dfs.mkdir(new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job3/test3"), FsPermission.getDirDefault(), true);
		
		// (retire the primary, copy test2 across)
		{
			BasicMessageBean res1 = storage_service.getDataService().get().switchCrudServiceToPrimaryBuffer(bucket, Optional.of("test2"), Optional.empty(), Optional.empty()).get();
			System.out.println("(res1 = " + res1.message() + ")");
			assertTrue("Request returns: " + res1.message(), res1.success());
		}		
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON)));
		assertTrue(doesFileExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON + "test2.json")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW)));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED)));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + "test2")));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + "test2")));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "test2")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + "former_current/test_exdir")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + "former_current/test_exdir")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "former_current/test_exdir")));

		//(transient - nothing's changed because I only switched primary)
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/test1")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job2/test2")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job3/test3")));
		
		
		//(transient - jobs 1 and 2)
		{
			BasicMessageBean res1 = storage_service.getDataService().get().switchCrudServiceToPrimaryBuffer(bucket, Optional.of("test1"), Optional.empty(), Optional.of("job1")).get();
			BasicMessageBean res2 = storage_service.getDataService().get().switchCrudServiceToPrimaryBuffer(bucket, Optional.of("test2"), Optional.empty(), Optional.of("job2")).get();
			System.out.println("(res1 = " + res1.message() + ")");
			System.out.println("(expecting file not found error: res2 = " + res2.message() + ")");
			assertTrue("Request returns: " + res1.message(), res1.success());
			assertTrue("Request returns: " + res2.message(), res2.success());
		}		
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/current")));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/test1")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job2/current")));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job2/test2")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job3/current")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job3/test3")));
		
		//(transient - job 3)
		{
			BasicMessageBean res1 = storage_service.getDataService().get().switchCrudServiceToPrimaryBuffer(bucket, Optional.of("test3"), Optional.empty(), Optional.of("job3")).get();
			System.out.println("(res1 = " + res1.message() + ")");
			assertTrue("Request returns: " + res1.message(), res1.success());
		}		
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job3/current")));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "test3/job3")));
		
		{
			BasicMessageBean res2 = storage_service.getDataService().get().switchCrudServiceToPrimaryBuffer(bucket, Optional.of("test3"), Optional.of("ex_primary"), Optional.empty()).get();
			System.out.println("(res2 = " + res2.message() + ")");
			assertTrue("Request returns: " + res2.message(), res2.success());
		}		
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + "ex_primary")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + "ex_primary")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + "ex_primary")));
		assertTrue(doesFileExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + "ex_primary/test2.json")));

		//(transient)
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/current")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job2/current")));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job1/test1")));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job2/test2")));
		assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "job3/current")));
		assertFalse(doesDirExist(dfs, new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + "test3/job3")));
		
		// return to the primary, delete the current
		{
			BasicMessageBean res3 = storage_service.getDataService().get().switchCrudServiceToPrimaryBuffer(bucket, Optional.of("former_current"), Optional.of(""), Optional.empty()).get();
			System.out.println("(res3 = " + res3.message() + ")");
			assertTrue("Request returns: " + res3.message(), res3.success());
			assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON + "/test_exdir")));
			assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW + "/test_exdir")));
			assertTrue(doesDirExist(dfs, new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED + "/test_exdir")));
		}		
	}
	
	/////////////////////////////////////////////////////////////////
	
	// UTILS
	
	protected boolean doesDirExist(FileContext fc, Path p) {
		try {
			return fc.getFileStatus(p).isDirectory();
		}
		catch (Exception e) { return false; }
	}
	protected boolean doesFileExist(FileContext fc, Path p) {
		try {
			return fc.getFileStatus(p).isFile();
		}
		catch (Exception e) { return false; }
	}
	
	/**
	 * @param storage_service
	 * @param bucket
	 * @param extra_suffixes - start with $ to indicate a new secondary buffer, else is a normal suffix 
	 */
	protected void setup_bucket(MockHdfsStorageService storage_service, final DataBucketBean bucket, List<String> extra_suffixes) {
		final FileContext dfs = storage_service.getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get();
				
		final String bucket_root = storage_service.getBucketRootPath() + "/" + bucket.full_name();		

		//(first delete root path)
		try {
			dfs.delete(new Path(bucket_root), true);
		}
		catch (Exception e) {}
		
		Stream.concat(
				Arrays.asList(
					"/managed_bucket",
					"/managed_bucket/logs",
					"/managed_bucket/logs/harvest",
					"/managed_bucket/logs/enrichment",
					"/managed_bucket/logs/storage",
					"/managed_bucket/assets",
					"/managed_bucket/import",
					"/managed_bucket/import/stored",
					"/managed_bucket/import/stored/raw/current",
					"/managed_bucket/import/stored/json/current",
					"/managed_bucket/import/stored/processed/current",
					"/managed_bucket/import/transient/test_job/current",
					"/managed_bucket/import/ready",
					"/managed_bucket/import/temp"
				)
				.stream()
				,
				extra_suffixes.stream()
					.flatMap(s -> s.startsWith("$")
									? Stream.of(
										"/managed_bucket/import/stored/raw/" + s.substring(1),
										"/managed_bucket/import/stored/json/" + s.substring(1),
										"/managed_bucket/import/stored/processed/" + s.substring(1),
										"/managed_bucket/import/transient/test_job/" + s.substring(1)
									)
									: Stream.of(s)
					)
				)
				.map(s -> new Path(bucket_root + s))
				.forEach(Lambdas.wrap_consumer_u(p -> dfs.mkdir(p, FsPermission.getDefault(), true)));		
	}
	
}
