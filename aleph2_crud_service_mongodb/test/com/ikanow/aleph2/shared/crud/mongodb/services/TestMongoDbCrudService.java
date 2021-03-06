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
package com.ikanow.aleph2.shared.crud.mongodb.services;

import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import org.apache.metamodel.data.DataSet;
import org.bson.types.ObjectId;
import org.junit.Before;
import org.junit.Test;
import org.mongojack.JacksonDBCollection;

import scala.Tuple2;
import static org.hamcrest.CoreMatchers.instanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService.Cursor;
import com.ikanow.aleph2.data_model.utils.CrudUtils;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils.BeanTemplate;
import com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent;
import com.ikanow.aleph2.data_model.utils.CrudUtils.UpdateComponent;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.shared.crud.mongodb.data_model.MongoDbConfigurationBean;
import com.ikanow.aleph2.shared.crud.mongodb.services.MongoDbCrudService;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoException;

import static org.junit.Assert.*;

public class TestMongoDbCrudService {

	public static class TestBean {
		public String _id() { return _id; }
		public String test_string() { return test_string; }

		String _id;
		String test_string;
		Boolean test_bool;
		Long test_long;
		List<String> test_string_list;
		Set<NestedTestBean> test_object_set;
		LinkedHashMap<String, Long> test_map;
		
		public static class NestedTestBean {
			String test_string;			
		}
	}

	////////////////////////////////////////////////

	// UTILS
	
	// Set this string to connect vs a real D
	private String _real_mongodb_connection = null;
	//private String _real_mongodb_connection = "db001.dev.ikanow.com:27017";
	
	@Before
	public void setupCrudServiceFactory() throws UnknownHostException {
		if (null == _factory) {
			if (null == _real_mongodb_connection) {
				_factory = new MockMongoDbCrudServiceFactory();
			}
			else {
				MongoDbConfigurationBean config_bean = new MongoDbConfigurationBean(_real_mongodb_connection);
				_factory = new MongoDbCrudServiceFactory(config_bean);
			}
			// Couple of quick tests:
			final DB db1 = _factory.getMongoDb("db_test");
			assertEquals("db_test", db1.getName());
			final DBCollection dbc1 = _factory.getMongoDbCollection("db_test.test_collection");
			assertEquals("db_test.test_collection", dbc1.getFullName());
		}
	}
	
	protected IMongoDbCrudServiceFactory _factory = null; 
	
	public <O, K> MongoDbCrudService<O, K> getTestService(String test_name, Class<O> bean_clazz, Class<K> key_clazz) {
		
		final MongoDbCrudService<O, K> service = _factory.getMongoDbCrudService(
				bean_clazz, key_clazz, 
				_factory.getMongoDbCollection("test_db", test_name),
				Optional.empty(), Optional.empty(), Optional.empty());
		
		service._state.orig_coll.drop();
		
		return service;
	}
	
	////////////////////////////////////////////////
	
	// CREATION
	
	@Test
	public void testCreateSingleObject() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestBean, String> service = getTestService("testCreateSingleObject", TestBean.class, String.class);

		assertEquals(0, service._state.orig_coll.count());		
		
		// 1) Add a new object to an empty DB
		
		final TestBean test = new TestBean();
		test._id = "_id_1";
		test.test_string = "test_string_1";
		
		final Future<Supplier<Object>> result = service.storeObject(test);
		
		final Supplier<Object> val = result.get();
		
		assertEquals("_id_1", val.get());
		
		final DBObject retval = service._state.orig_coll.findOne();
		
		assertEquals(1, service._state.orig_coll.count());
		
		assertEquals("{ \"_id\" : \"_id_1\" , \"test_string\" : \"test_string_1\"}", retval.toString());
		
		// 2) Add the _id again, should fail
		
		final TestBean test2 = BeanTemplateUtils.clone(test).with("test_string", "test_string_2").done();
		
		final Future<Supplier<Object>> result2 = service.storeObject(test2);
				
		Exception expected_ex = null;
		try {
			result2.get();
			fail("Should have thrown exception on duplicate insert");
		}
		catch (Exception e) {
			expected_ex = e;
		}
		if (null != expected_ex)
			assertThat(expected_ex.getCause(), instanceOf(MongoException.class));
		
		assertEquals(1, service._state.orig_coll.count());
		
		final DBObject retval2 = service._state.orig_coll.findOne();		
		
		assertEquals("{ \"_id\" : \"_id_1\" , \"test_string\" : \"test_string_1\"}", retval2.toString());		

		// 3) Add the same with override set 
		
		@SuppressWarnings("unused")
		final Future<Supplier<Object>> result3 = service.storeObject(test2, true);
		
		assertEquals(1, service._state.orig_coll.count());
		
		final DBObject retval3 = service._state.orig_coll.findOne();		
		
		assertEquals("{ \"_id\" : \"_id_1\" , \"test_string\" : \"test_string_2\"}", retval3.toString());		
		
		//4) add with no id
		
		final TestBean test4 = new TestBean();
		test4.test_string = "test_string_4";
		
		final Future<Supplier<Object>> result4 = service.storeObject(test4, true);
		
		assertEquals(2, service._state.orig_coll.count());
		
		final String id = result4.get().get().toString();
		
		final DBObject retval4 = service._state.orig_coll.findOne(id);
		
		assertEquals("test_string_4", retval4.get("test_string"));
	}
	
	public static class TestObjectIdBean {
		ObjectId _id;
	}

	@Test
	public void testCreateSingleObject_ObjectId() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestObjectIdBean, ObjectId> service = getTestService("testCreateSingleObject_ObjectId", TestObjectIdBean.class, ObjectId.class);
		
		assertEquals(0, service._state.orig_coll.count());		
		
		// 1) Add a new object to an empty DB
		
		final TestObjectIdBean test = new TestObjectIdBean();
		
		final Future<Supplier<Object>> result = service.storeObject(test);
		
		assertEquals(1, service._state.orig_coll.count());
		
		final Supplier<Object> val = result.get();
		
		ObjectId id = (ObjectId)val.get();
		
		final DBObject retval = service._state.orig_coll.findOne(id);
		
		assertEquals(new BasicDBObject("_id", id), retval);		
	}	
	
	@Test
	public void testCreateMultipleObjects() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestBean, String> service = getTestService("testCreateMultipleObjects", TestBean.class, String.class);
		
		// 1) Insertion without ids
		
		assertEquals(0, service._state.orig_coll.count());		
		
		final List<TestBean> l = IntStream.rangeClosed(1, 10).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class).with("test_string", "test_string" + i).done().get())
				.collect(Collectors.toList());

		final Future<Tuple2<Supplier<List<Object>>, Supplier<Long>>> result = service.storeObjects(l);
		
		assertEquals(10, service._state.orig_coll.count());
		assertEquals((Long)(long)10, result.get()._2().get());
		
		final List<Object> ids = result.get()._1().get();
		IntStream.rangeClosed(1, 10).boxed().map(i -> Tuples._2T(i, ids.get(i-1)))
					.forEach(io -> {
						final DBObject val = service._state.orig_coll.findOne(io._2());
						assertNotEquals(null, val);
						assertEquals("test_string" + io._1(), val.get("test_string"));
					});
		
		// 2) Insertion with ids
		
		service._state.orig_coll.drop();

		final List<TestBean> l2 = IntStream.rangeClosed(51, 100).boxed()
								.map(i -> BeanTemplateUtils.build(TestBean.class).with("_id", "id" + i).with("test_string", "test_string" + i).done().get())
								.collect(Collectors.toList());
				
		final Future<Tuple2<Supplier<List<Object>>, Supplier<Long>>> result_2 = service.storeObjects(l2);
		
		assertEquals(50, service._state.orig_coll.count());
		assertEquals((Long)(long)50, result_2.get()._2().get());
		
		// 3) Check storeObjects(..., true) will succeed
		
		final List<TestBean> l3 = IntStream.rangeClosed(41, 110).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class).with("_id", "id" + i).with("test_string", "test_string" + i).done().get())
				.collect(Collectors.toList());

		final Future<Tuple2<Supplier<List<Object>>, Supplier<Long>>> result_3 = service.storeObjects(l3, true);
		
		assertEquals(70, service._state.orig_coll.count());
		assertEquals((Long)(long)70, result_3.get()._2().get());
		
		// 4) Insertion with dups - fail and continue
		
		final List<TestBean> l4 = IntStream.rangeClosed(21, 120).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class).with("_id", "id" + i).with("test_string", "test_string" + i).done().get())
				.collect(Collectors.toList());
		
		final Future<Tuple2<Supplier<List<Object>>, Supplier<Long>>> result_4 = service.storeObjects(l4);

		try {
			assertEquals(100, service._state.orig_coll.count());
			assertEquals(100L, (long)result_4.get()._2().get());			
			
			// Fongo and Mongo behave differently here:
			if (null != this._real_mongodb_connection) {
				fail("Should have thrown exception on duplicate insert, even though docs have been inserted");
			}
		}
		catch (Exception e) {}		
	}

	////////////////////////////////////////////////
	
	// RETRIEVAL
	
	@Test
	public void testIndexes() throws InterruptedException, ExecutionException {		
		
		final MongoDbCrudService<TestBean, String> service = getTestService("testIndexes", TestBean.class, String.class);

		// Insert some objects to index
		
		final List<TestBean> l = IntStream.rangeClosed(1, 1000).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class).with("test_string", "test_string" + i).done().get())
				.collect(Collectors.toList());

		service.storeObjects(l);
		
		assertEquals(1000, service._state.orig_coll.count());
		
		// 1) Add a new index
		
		final List<DBObject> initial_indexes = service._state.orig_coll.getIndexInfo();
		if (null == this._real_mongodb_connection) { // slightly different format:
			assertEquals("[{ \"v\" : 1 , \"key\" : { \"_id\" : 1} , \"ns\" : \"test_db.testIndexes\" , \"name\" : \"_id_\"}]", initial_indexes.toString());
		}
		else {
			assertEquals("[{ \"v\" : 1 , \"key\" : { \"_id\" : 1} , \"name\" : \"_id_\" , \"ns\" : \"test_db.testIndexes\"}]", initial_indexes.toString());			
		}
		
		final Future<Boolean> done = service.optimizeQuery(Arrays.asList("test_string", "_id"));
		
		assertEquals(true, done.get());

		final List<DBObject> new_indexes = service._state.orig_coll.getIndexInfo();		
		
		final BasicDBObject expected_index_nested = new BasicDBObject("test_string", 1);
		expected_index_nested.put("_id", 1);
		final BasicDBObject expected_index = new BasicDBObject("v", 1);
		expected_index.put("key", expected_index_nested);
		if (null == this._real_mongodb_connection) { // slightly different format:
			expected_index.put("ns", "test_db.testIndexes");
			expected_index.put( "name", "test_string_1__id_1");
		}
		else {
			expected_index.put( "name", "test_string_1__id_1");
			expected_index.put("ns", "test_db.testIndexes");
		}
		expected_index.put("background", true);
		
		final List<DBObject> expected_new_indexes = Arrays.asList(initial_indexes.get(0), expected_index);
		
		assertEquals(expected_new_indexes.toString(), new_indexes.toString());
		
		// 3) Remove an index that doesn't exist
		
		final boolean index_existed = service.deregisterOptimizedQuery(Arrays.asList("test_string", "test_long"));
		
		assertEquals(false, index_existed);

		final List<DBObject> nearly_final_indexes = service._state.orig_coll.getIndexInfo();		
		
		assertEquals(expected_new_indexes.toString(), nearly_final_indexes.toString());		
		
		// 4) Remove the index we just added
		
		final boolean index_existed_4 = service.deregisterOptimizedQuery(Arrays.asList("test_string", "_id"));
		
		assertEquals(true, index_existed_4);
		
		final List<DBObject> expected_new_indexes_4 = Arrays.asList(initial_indexes.get(0));
		
		final List<DBObject> final_indexes = service._state.orig_coll.getIndexInfo();		
		
		assertEquals(expected_new_indexes_4.toString(), final_indexes.toString());
		
		//Finally check that you get an error if the index is too long:
		
		try {
			@SuppressWarnings("unused")
			final Future<Boolean> error = service.optimizeQuery(Arrays.asList(
					"XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX01", "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX02",
					"XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX03", "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX04",
					"XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX05"
					));
			
			fail("Should have errored");
		}
		catch (Exception e) {
			System.out.println("Correctly got error: " + e.getMessage());
		}
	}
	
	protected String copyableOutput(Object o) {
		return o.toString().replace("\"", "\\\"");
	}
	protected void sysOut(String s) {
		System.out.println(copyableOutput(s));
	}
	
	@Test
	public void singleObjectRetrieve() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestBean, String> service = getTestService("singleObjectRetrieve", TestBean.class, String.class);

		final List<TestBean> l = IntStream.rangeClosed(1, 10).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class)
								.with("_id", "id" + i)
								.with("test_string", "test_string" + i)
								.with("test_long", (Long)(long)i)
								.done().get())
				.collect(Collectors.toList());

		service.storeObjects(l);
		
		assertEquals(10, service._state.orig_coll.count());
		
		service.optimizeQuery(Arrays.asList("test_string")).get(); // (The get() waits for completion)
		
		// For asserting vs strings where possible:
		final JacksonDBCollection<TestBean, String> mapper = service._state.coll;

		// 1) Get object by _id, exists
		
		final Future<Optional<TestBean>> obj1 = service.getObjectById("id1");
		
		//DEBUG
		//sysOut(mapper.convertToDbObject(obj1.get().get()).toString());
		
		assertEquals("{ \"_id\" : \"id1\" , \"test_string\" : \"test_string1\" , \"test_long\" : 1}", mapper.convertToDbObject(obj1.get().get()).toString());
		
		// 2) Get object by _id, exists, subset of fields

		// 2a) inclusive:
		
		final Future<Optional<TestBean>> obj2a = service.getObjectById("id2", Arrays.asList("_id", "test_string"), true);		
		
		//DEBUG
		//sysOut(mapper.convertToDbObject(obj2a.get().get()).toString());
		
		assertEquals("{ \"_id\" : \"id2\" , \"test_string\" : \"test_string2\"}", mapper.convertToDbObject(obj2a.get().get()).toString());
		
		// 2a.1) inclusive without _id (which mongodb treats differently)
		
		final Future<Optional<TestBean>> obj2a_1 = service.getObjectById("id2", Arrays.asList("test_string"), true);		
		
		assertEquals("{ \"test_string\" : \"test_string2\"}", mapper.convertToDbObject(obj2a_1.get().get()).toString());
		
		// 2b) exclusive:

		final Future<Optional<TestBean>> obj2b = service.getObjectById("id3", Arrays.asList("_id", "test_string"), false);		
		
		//DEBUG
		//sysOut(mapper.convertToDbObject(obj2b.get().get()).toString());
		
		assertEquals("{ \"test_long\" : 3}", mapper.convertToDbObject(obj2b.get().get()).toString());
		
		// 3) Get object by _id, doesn't exist
		
		final Future<Optional<TestBean>> obj3 = service.getObjectById("id100", Arrays.asList("_id", "test_string"), false);		
		
		assertEquals(false, obj3.get().isPresent());
		
		// 4) Get object by spec, exists
		
		final QueryComponent<TestBean> query = CrudUtils.allOf(TestBean.class)
					.when("_id", "id4")
					.withAny("test_string", Arrays.asList("test_string1", "test_string4"))
					.withPresent("test_long");
		
		final Future<Optional<TestBean>> obj4 = service.getObjectBySpec(query);
		
		assertEquals("{ \"_id\" : \"id4\" , \"test_string\" : \"test_string4\" , \"test_long\" : 4}", mapper.convertToDbObject(obj4.get().get()).toString());
		
		// 5) Get object by spec, exists, subset of fields
		
		final Future<Optional<TestBean>> obj5 = service.getObjectBySpec(query, Arrays.asList("_id", "test_string"), true);
		
		assertEquals("{ \"_id\" : \"id4\" , \"test_string\" : \"test_string4\"}", mapper.convertToDbObject(obj5.get().get()).toString());
				
		// 6) Get object by spec, doesn't exist

		final QueryComponent<TestBean> query6 = CrudUtils.allOf(TestBean.class)
				.when("_id", "id3")
				.withAny("test_string", Arrays.asList("test_string1", "test_string4"))
				.withPresent("test_long");
	
		
		final Future<Optional<TestBean>> obj6 = service.getObjectBySpec(query6, Arrays.asList("_id", "test_string"), false);		
		assertEquals(false, obj6.get().isPresent());
	}

	@Test
	public void multiObjectRetrieve() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestBean, String> service = getTestService("multiObjectRetrieve", TestBean.class, String.class);
		
		final List<TestBean> l = IntStream.rangeClosed(0, 9).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class)
								.with("_id", "id" + i)
								.with("test_string", "test_string" + i)
								.with("test_long", (Long)(long)i)
								.done().get())
				.collect(Collectors.toList());

		service.storeObjects(l);
		
		assertEquals(10, service._state.orig_coll.count());
		
		service.optimizeQuery(Arrays.asList("test_string")).get(); // (The get() waits for completion)
		
		// For asserting vs strings where possible:
		final JacksonDBCollection<TestBean, String> mapper = service._state.coll;

		// 1) Simple retrieve, no fields specified - sort

		final QueryComponent<TestBean> query = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id4", true)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_string", -1));
		
		try (Cursor<TestBean> cursor = service.getObjectsBySpec(query).get()) {
		
			assertEquals(5, cursor.count());
			
			final List<TestBean> objs = StreamSupport.stream(Optionals.ofNullable(cursor).spliterator(), false).collect(Collectors.toList());
			
			assertEquals(5, objs.size());
			
			final DBObject first_obj = mapper.convertToDbObject(objs.get(0));
			
			assertEquals("{ \"_id\" : \"id9\" , \"test_string\" : \"test_string9\" , \"test_long\" : 9}", first_obj.toString());			
		} 
		catch (Exception e) {
			//(fail on close, normally carry on - but here error out)
			fail("getObjectsBySpec errored on close"); 
		}
		
		// 2) Simple retrieve, field specified (exclusive) - sort and limit

		final QueryComponent<TestBean> query_2 = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", 1)).limit(4);
		
		try (Cursor<TestBean> cursor = service.getObjectsBySpec(query_2, Arrays.asList("test_string"), false).get()) {
		
			assertEquals(6, cursor.count()); // (count ignores limit)
			
			final List<TestBean> objs = StreamSupport.stream(Optionals.ofNullable(cursor).spliterator(), false).collect(Collectors.toList());
			
			assertEquals(4, objs.size());
			
			final DBObject first_obj = mapper.convertToDbObject(objs.get(0));
			
			assertEquals("{ \"_id\" : \"id4\" , \"test_long\" : 4}", first_obj.toString());			
		} 
		catch (Exception e) {
			//(fail on close, normally carry on - but here error out)
			fail("getObjectsBySpec errored on close"); 
		}
		
		// 3) Simple retrieve, no docs returned
		
		final QueryComponent<TestBean> query_3 = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id9", true)
				.withPresent("test_long")
				.limit(4);
		
		try (Cursor<TestBean> cursor = service.getObjectsBySpec(query_3, Arrays.asList("test_string"), false).get()) {
			final List<TestBean> objs = StreamSupport.stream(Optionals.ofNullable(cursor).spliterator(), false).collect(Collectors.toList());
			
			assertEquals(0, objs.size());
		}
		catch (Exception e) {
			//(fail on close, normally carry on - but here error out)
			fail("getObjectsBySpec errored on close"); 
		}
	}
	
	@Test
	public void testCounting() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestBean, String> service = getTestService("testCounting", TestBean.class, String.class);

		final List<TestBean> l = IntStream.rangeClosed(0, 9).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class)
								.with("_id", "id" + i)
								.with("test_string", "test_string" + i)
								.with("test_long", (Long)(long)i)
								.done().get())
				.collect(Collectors.toList());

		service.storeObjects(l);
		
		assertEquals(10, service._state.orig_coll.count());
		
		service.optimizeQuery(Arrays.asList("test_string")).get(); // (The get() waits for completion)
		
		// 1) all docs
		
		assertEquals(10L, (long)service.countObjects().get());
		
		// 2) count subset of docs

		final QueryComponent<TestBean> query_2 = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", 1));

		assertEquals(6L, (long)service.countObjectsBySpec(query_2).get());		
		
		// 3) subset of docs (limit)
		
		final QueryComponent<TestBean> query_3 = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", 1)).limit(4);

		assertEquals(4L, (long)service.countObjectsBySpec(query_3).get());
		
		// 4) no docs
		
		final QueryComponent<TestBean> query_4 = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id99", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", 1)).limit(4);

		assertEquals(0L, (long)service.countObjectsBySpec(query_4).get());
	}
	
	////////////////////////////////////////////////
	////////////////////////////////////////////////
	
	// UPDATES

	public static class UpdateTestBean {
		public String _id;
		public static class NestedNestedTestBean {
			public String nested_nested_string_field() { return nested_nested_string_field; }
			
			private String nested_nested_string_field;
		}
		public static class NestedTestBean {
			public String nested_string_field() { return nested_string_field; }
			public NestedNestedTestBean nested_object() { return nested_object; }
			public List<String> nested_string_list() { return nested_string_list; }
			
			private List<String> nested_string_list;
			private String nested_string_field;
			private NestedNestedTestBean nested_object;
		}		
		public String string_field() { return string_field; }
		public List<String> string_fields() { return string_fields; }
		public Boolean bool_field() { return bool_field; }
		public Long long_field() { return long_field; }
		public List<NestedTestBean> nested_list() { return nested_list; }
		public Map<String, String> map() { return map; }
		public NestedTestBean nested_object() { return nested_object; }
		
		protected UpdateTestBean() {}
		protected String string_field;
		protected List<String> string_fields;
		protected List<String> string_fields2;
		protected Boolean bool_field;
		protected Long long_field;
		protected List<NestedTestBean> nested_list;
		protected Map<String, String> map;
		protected NestedTestBean nested_object;
	}
	
	
	@Test
	public void testUpdateDocs() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<UpdateTestBean, String> service = getTestService("testUpdateDocs", UpdateTestBean.class, String.class);

		// Build an object to modify
		final UpdateTestBean.NestedNestedTestBean to_update_nested_nested = new UpdateTestBean.NestedNestedTestBean();
		to_update_nested_nested.nested_nested_string_field = "nested_nested_string_field";
		final UpdateTestBean.NestedTestBean to_update_nested = new UpdateTestBean.NestedTestBean();
		to_update_nested.nested_string_list = Arrays.asList("nested_string_list1", "nested_string_list2");
		to_update_nested.nested_string_field = "nested_string_field";
		to_update_nested.nested_object = to_update_nested_nested;
		final UpdateTestBean.NestedTestBean to_update_nested2 = BeanTemplateUtils.clone(to_update_nested)
																	.with("nested_string_field", "nested_string_field2").done(); 
		final UpdateTestBean to_update = new UpdateTestBean();
		to_update.string_field = "string_field";
		to_update.string_fields = Arrays.asList("string_fields1", "string_fields2");
		to_update.string_fields2 = Arrays.asList("string_fields2_1", "string_fields2_2");
		to_update.bool_field = true;
		to_update.long_field = 1L;
		to_update.nested_list = Arrays.asList(to_update_nested, to_update_nested2);
		to_update.map = ImmutableMap.<String, String>builder().put("mapkey", "mapval").build();
		to_update.nested_object = to_update_nested;
		to_update._id = "test1";
		
		final CompletableFuture<Supplier<Object>> ret_val_0 = service.storeObject(to_update);
		ret_val_0.get(); // (just check it succeeded)
		
		// Update test object:

		// Test 1 - getter fields ... update this will error 
		
		final BeanTemplate<UpdateTestBean.NestedTestBean> nested1 = BeanTemplateUtils.build(UpdateTestBean.NestedTestBean.class)
																	.with("nested_string_field", "test1")
																	.done(); 
		
		// Lots of things break here: attempt to do any operations on nested_list.*, multiple atomic operations
		final UpdateComponent<UpdateTestBean> test1 = 
				CrudUtils.update(UpdateTestBean.class)
					.add(UpdateTestBean::string_fields, "AA", false) 
					.increment(UpdateTestBean::long_field, 4) 
					.nested(UpdateTestBean::nested_list, 
							CrudUtils.update(nested1) 
								.unset(UpdateTestBean.NestedTestBean::nested_string_field) 
								.remove(UpdateTestBean.NestedTestBean::nested_string_list, Arrays.asList("x", "y", "z")) 
								.add(UpdateTestBean.NestedTestBean::nested_string_list, "A", true) 
							)
					.unset(UpdateTestBean::bool_field) 
					.unset(UpdateTestBean::nested_object) 
					.remove(UpdateTestBean::nested_list, CrudUtils.allOf(UpdateTestBean.NestedTestBean.class).when("nested_string_field", "1")) //6)
					;

		try {
			CompletableFuture<Boolean> ret_val_1 = service.updateObjectById("test1", test1);		
			ret_val_1.get();
			assertFalse("Should have thrown an exception", true);
		}
		catch (Exception e) {} // (this is just tmep until I can get the update working)
		
		// TEST 2 - Same but will succeed
		
		final QueryComponent<UpdateTestBean> query2 = CrudUtils.allOf(UpdateTestBean.class).when("_id", "test1");
		
		final BeanTemplate<UpdateTestBean.NestedTestBean> nested2 = BeanTemplateUtils.build(UpdateTestBean.NestedTestBean.class)
				.with("nested_string_field", "test1")
				.done(); //(2)
				
		// Tested: addToSet (collection) add (single val), set, unset, nested, increment, pull
		//TODO: pullAll
		final UpdateComponent<UpdateTestBean> test2 = 
				CrudUtils.update(UpdateTestBean.class)
					.add(UpdateTestBean::string_fields, Arrays.asList("AA", "string_fields1"), true) 
					.increment(UpdateTestBean::long_field, 4) 
					.nested(UpdateTestBean::nested_object, 
							CrudUtils.update(nested2) 
								.add(UpdateTestBean.NestedTestBean::nested_string_list, "A", false) 
							)
					.unset(UpdateTestBean::bool_field) 
					.remove(UpdateTestBean::nested_list, CrudUtils.allOf(UpdateTestBean.NestedTestBean.class).when("nested_string_field", "nested_string_field"))
					.remove("string_fields2", Arrays.asList("XXX", "string_fields2_1"))
					;

		//DEBUG
		//System.out.println(service._state.orig_coll.findOne().toString());
		//System.out.println(MongoDbUtils.createUpdateObject(test2));
		
		CompletableFuture<Boolean> ret_val_2 = service.updateObjectBySpec(query2, Optional.of(false), test2);		
		assertTrue("update succeeded", ret_val_2.get());
		
		final String expected_2 = "{ \"_id\" : \"test1\" , \"string_field\" : \"string_field\" , \"string_fields\" : [ \"string_fields1\" , \"string_fields2\" , \"AA\"] , \"string_fields2\" : [ \"string_fields2_2\"] , \"long_field\" : 5 , \"nested_list\" : [ { \"nested_string_list\" : [ \"nested_string_list1\" , \"nested_string_list2\"] , \"nested_string_field\" : \"nested_string_field2\" , \"nested_object\" : { \"nested_nested_string_field\" : \"nested_nested_string_field\"}}] , \"map\" : { \"mapkey\" : \"mapval\"} , \"nested_object\" : { \"nested_string_list\" : [ \"nested_string_list1\" , \"nested_string_list2\" , \"A\"] , \"nested_string_field\" : \"test1\" , \"nested_object\" : { \"nested_nested_string_field\" : \"nested_nested_string_field\"}}}";
		
		assertEquals(1L, (long)service.countObjects().get());
		assertEquals(expected_2, service._state.orig_coll.findOne().toString());
		
		// Tests where no matching object is found
		
		// Fail
		
		final QueryComponent<UpdateTestBean> query3 = CrudUtils.allOf(UpdateTestBean.class).when("_id", "test2");
		
		CompletableFuture<Boolean> ret_val_3 = service.updateObjectBySpec(query3, Optional.of(false), test2);		
		
		assertEquals(1L, (long)service.countObjects().get());
		assertFalse("update did nothing", ret_val_3.get());

		// Upsert
		
		CompletableFuture<Boolean> ret_val_4 = service.updateObjectBySpec(query3, Optional.of(true), test2);		
		
		assertEquals(2L, (long)service.countObjects().get());
		assertTrue("update upserted", ret_val_4.get());

		// (clear out this object)
		if (null == this._real_mongodb_connection) { // (upsert doens't work properly in fongo)
			service.deleteObjectsBySpec(
					CrudUtils.allOf(UpdateTestBean.class).whenNot("_id", "test1")
					);
		}
		else {
			assertTrue("Delete corrupted object I just inserted", service.deleteObjectById("test2").get());			
		}
		assertEquals(1L, (long)service.countObjects().get());
		
		// Multi updates:

		for (int i = 2; i < 10; ++i) {
			UpdateTestBean to_insert = BeanTemplateUtils.clone(to_update).with("_id", "test" + i).done();
			final CompletableFuture<Supplier<Object>> ret_val = service.storeObject(to_insert);
			ret_val.get(); // (just check it succeeded)
		}
		assertEquals(9L, (long)service.countObjects().get());
		
		final QueryComponent<UpdateTestBean> query5 = CrudUtils.allOf(UpdateTestBean.class).rangeAbove("_id", "test4", true);
		
		CompletableFuture<Long> ret_val_5 = service.updateObjectsBySpec(query5, Optional.of(false), test2);
		
		assertEquals(5L, (long)ret_val_5.get());
	
		// check one of the objects we updated was in fact updated
		assertEquals(expected_2.replace("\"_id\" : \"test1\"", "\"_id\" : \"test6\"")
				, service._state.orig_coll.findOne(new BasicDBObject("_id", "test6")).toString());
		 
	}
	
	@Test
	public void testUpdateAndReturnDocs() throws InterruptedException, ExecutionException {
		
		//TODO: upsert, before+after updated, delete doc, field_list/include, field_list/exclude
		
		//service.updateAndReturnObjectBySpec(unique_spec, upsert, update, before_updated, field_list, include)
	}
	
	////////////////////////////////////////////////
	////////////////////////////////////////////////
	
	// DELETION

	protected static void replenishDocsForDeletion(MongoDbCrudService<TestBean, String> service) {
		
		final List<TestBean> l = IntStream.rangeClosed(0, 9).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class)
								.with("_id", "id" + i)
								.with("test_string", "test_string" + i)
								.with("test_long", (Long)(long)i)
								.done().get())
				.collect(Collectors.toList());

		service.storeObjects(l, false);
		
		assertEquals(10, service._state.orig_coll.count());
	}
	
	protected static void replenishDocsForDeletion_JSON(MongoDbCrudService<JsonNode, String> service) {
		
		final List<JsonNode> l = IntStream.rangeClosed(0, 9).boxed()
				.map(i -> BeanTemplateUtils.build(TestBean.class)
								.with("_id", "id" + i)
								.with("test_string", "test_string" + i)
								.with("test_long", (Long)(long)i)
								.done().get())
				.map(b -> BeanTemplateUtils.toJson(b))
				.collect(Collectors.toList());

		service.storeObjects(l, false);
		
		assertEquals(10, service._state.orig_coll.count());
	}
	
	@Test
	public void testDeletion() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestBean, String> service = getTestService("testDeletion", TestBean.class, String.class);

		service.optimizeQuery(Arrays.asList("test_string")).get(); // (The get() waits for completion)
		
		// 1) Doc by id
		
		// 1a) No such doc exists
		
		replenishDocsForDeletion(service);
		
		assertEquals(false, service.deleteObjectById("hgfhghfg").get());
		
		assertEquals(10L, (long)service._state.coll.count());		
		
		// 1b) Deletes doc
		
		assertEquals(true, service.deleteObjectById("id3").get());

		assertEquals(9L, (long)service._state.coll.count());		
		
		assertEquals(Optional.empty(), service.getObjectById("id3").get());
		
		// 2) Doc by spec

		// 2a) Does match
		
		replenishDocsForDeletion(service);

		assertEquals(false, service.deleteObjectBySpec(CrudUtils.allOf(TestBean.class).when("_id", "fhgfhjg")).get());
		
		assertEquals(10L, (long)service._state.coll.count());		
		
		// 2b) Matches >1, only deletes the first
		
		assertEquals(true, service.deleteObjectBySpec(CrudUtils.allOf(TestBean.class).rangeAbove("_id", "id1", false)).get());
		
		assertEquals(9L, (long)service._state.coll.count());		
		
		// 3) all docs
		
		replenishDocsForDeletion(service);
		
		assertEquals(10L, (long)service.deleteObjectsBySpec(CrudUtils.anyOf(TestBean.class)).get());
		
		assertEquals(0L, (long)service._state.coll.count());		
		
		// (check index is still present)
		
		assertEquals(2, service._state.coll.getIndexInfo().size());
		
		// 4) subset of docs

		replenishDocsForDeletion(service);
		
		final QueryComponent<TestBean> query_4 = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", 1));

		assertEquals(6L, (long)service.deleteObjectsBySpec(query_4).get());		

		assertEquals(4L, (long)service._state.coll.count());		

		// 5) subset of docs (limit and sort combos)
		
		// 5a) Sort - no limit
		
		replenishDocsForDeletion(service);
		
		final QueryComponent<TestBean> query_5a = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", -1));

		assertEquals(6L, (long)service.deleteObjectsBySpec(query_5a).get());		
		
		assertEquals(4L, (long)service._state.coll.count());		

		assertEquals(Optional.empty(), service.getObjectById("id9").get());
		
		// 5b) Limit - no sort
		
		replenishDocsForDeletion(service);
		
		final QueryComponent<TestBean> query_5b = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", 1)).limit(4);

		assertEquals(4L, (long)service.deleteObjectsBySpec(query_5b).get());
		
		assertEquals(6L, (long)service._state.coll.count());				
		
		// 5c) Limit and sort
		
		replenishDocsForDeletion(service);
		
		final QueryComponent<TestBean> query_5c = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_string", 1)).limit(3);

		assertEquals(3L, (long)service.deleteObjectsBySpec(query_5c).get());		
		
		assertEquals(7L, (long)service._state.coll.count());		

		assertEquals(Optional.empty(), service.getObjectById("id4").get());
		assertEquals(Optional.empty(), service.getObjectById("id5").get());
		assertEquals(Optional.empty(), service.getObjectById("id6").get());
				
		// 6) no docs
		
		replenishDocsForDeletion(service);
		
		final QueryComponent<TestBean> query_6 = CrudUtils.allOf(TestBean.class)
				.rangeAbove("_id", "id99", false)
				.withPresent("test_long");

		assertEquals(0L, (long)service.deleteObjectsBySpec(query_6).get());
		
		assertEquals(10L, (long)service._state.coll.count());				
		
		// 7) erase data store
		
		replenishDocsForDeletion(service);
				
		service.deleteDatastore().get();
		
		assertEquals(0L, (long)service._state.coll.count());		
		
		// (check index has gone)

		if (null != this._real_mongodb_connection) { // (doesn't work with fongo?)
			assertEquals(0, service._state.coll.getIndexInfo().size());
		}
	}
	
	// (Removed this code for now, since it's easier just to use getUnderlyingPlatformDriver(DBCollection.class and com.mongodb.util.bson.parse)
//	@Test
//	public void test_extendedBsonGenerator() throws InterruptedException, ExecutionException {
//		final MongoDbCrudService<JsonNode, Object> json_service = getTestService("test_extendedBsonGenerator", JsonNode.class, Object.class);
//		
//		ObjectNode o1 = json_service._object_mapper.createObjectNode();
//		o1.put("$oid", "550b189ae4b0e58fb26f71eb");
//		ObjectNode o2 = json_service._object_mapper.createObjectNode();
//		o2.set("_id", o1);
//		o2.put("alex", "alex");
//		ObjectNode o3 = json_service._object_mapper.createObjectNode();
//		o3.put("$date", "2015-03-19T18:42:34.000Z");
//		o2.set("created", o3);
//		
//		json_service.storeObject(o2).get();
//		assertTrue(json_service.getObjectById(new ObjectId("550b189ae4b0e58fb26f71eb")).get().isPresent());
//		JsonNode x = json_service.getObjectById(new ObjectId("550b189ae4b0e58fb26f71eb")).get().get();
//		assertEquals("alex", x.get("alex").asText());
//	}
	
	@Test
	public void testJsonRepositoryCalls() throws InterruptedException, ExecutionException {
		final MongoDbCrudService<TestBean, String> bean_service = getTestService("testJsonRepositoryCalls", TestBean.class, String.class);
		final MongoDbCrudService<JsonNode, String> json_service = getTestService("testJsonRepositoryCalls", JsonNode.class, String.class);
		
		replenishDocsForDeletion_JSON(json_service);

		testJsonRepositoryCalls_common(json_service, json_service);

		replenishDocsForDeletion(bean_service);
		
		testJsonRepositoryCalls_common(bean_service.getRawService(), json_service);
	}
	
	public void testJsonRepositoryCalls_common(final ICrudService<JsonNode> service,  MongoDbCrudService<JsonNode, String> original) throws InterruptedException, ExecutionException {
		
		// Single object get

		final Future<Optional<JsonNode>> obj1 = service.getObjectById("id1");

		assertEquals("{ \"_id\" : \"id1\" , \"test_string\" : \"test_string1\" , \"test_long\" : 1}", original.convertToBson(obj1.get().get()).toString());

		// Multi object get
		
		final QueryComponent<JsonNode> query_2 = CrudUtils.allOf()
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", 1)).limit(4);
		
		try (Cursor<JsonNode> cursor = service.getObjectsBySpec(query_2, Arrays.asList("test_string"), false).get()) {
		
			assertEquals(6, cursor.count()); // (count ignores limit)
			
			final List<JsonNode> objs = StreamSupport.stream(Optionals.ofNullable(cursor).spliterator(), false).collect(Collectors.toList());
			
			assertEquals(4, objs.size());
			
			final DBObject first_obj = original.convertToBson(objs.get(0));
			
			assertEquals("{ \"_id\" : \"id4\" , \"test_long\" : 4}", first_obj.toString());			
		} 
		catch (Exception e) {
			//(fail on close, normally carry on - but here error out)
			fail("getObjectsBySpec errored on close"); 
		}
		
		// Delete
		
		assertEquals(10L, (long)service.countObjects().get());				
		
		final QueryComponent<JsonNode> query_5b = CrudUtils.allOf()
				.rangeAbove("_id", "id4", false)
				.withPresent("test_long")
				.orderBy(Tuples._2T("test_long", 1)).limit(4);

		assertEquals(4L, (long)service.deleteObjectsBySpec(query_5b).get());
		
		assertEquals(6L, (long)original._state.coll.count());				
		
		//TODO: also need to do an update and a findAndModify
	}
	
	@Test
	public void testMiscFunctions() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestBean, String> service = getTestService("testMiscFunctions", TestBean.class, String.class);

		service.optimizeQuery(Arrays.asList("test_string")).get(); // (The get() waits for completion)
		
		replenishDocsForDeletion(service);
		
		// Search service - currently not implemented
		
		assertEquals(Optional.empty(), service.getSearchService());
		
		// Myself
		
		final ICrudService<?> myself = service.getUnderlyingPlatformDriver(ICrudService.class, Optional.empty()).get();
		
		assertEquals(myself, service);
		
		// Mongo DB collection
		
		final com.mongodb.DBCollection dbc = service.getUnderlyingPlatformDriver(com.mongodb.DBCollection.class, Optional.empty()).get();
		
		assertEquals(2, dbc.getIndexInfo().size());

		// Mongojack DB collection
		
		final JacksonDBCollection<?, ?> dbc2 = service.getUnderlyingPlatformDriver(JacksonDBCollection.class, Optional.empty()).get();

		assertEquals(10, dbc2.count());		

		// Nothing else
		
		final Optional<String> fail = service.getUnderlyingPlatformDriver(String.class, Optional.empty());
		
		assertEquals(Optional.empty(), fail);
		
		// Meta model - more complicated, tested below
		// JSON service - more complicated, tested below...		
	}
	
	@Test
	public void testMetaModelInterface() throws InterruptedException, ExecutionException {
		
		final MongoDbCrudService<TestBean, String> service = getTestService("testMetaModelInterface", TestBean.class, String.class);

		service.optimizeQuery(Arrays.asList("test_string")).get(); // (The get() waits for completion)
		
		replenishDocsForDeletion(service);
			
		final ICrudService.IMetaModel meta_model_1 = service.getUnderlyingPlatformDriver(ICrudService.IMetaModel.class, Optional.empty()).get();	
		final ICrudService.IMetaModel meta_model_2 = service.getUnderlyingPlatformDriver(ICrudService.IMetaModel.class, Optional.empty()).get();
		
		// Check the object is created just once
		assertEquals(meta_model_1, meta_model_2);

		DataSet data = meta_model_1.getContext().query().from(meta_model_1.getTable()).select(meta_model_1.getTable().getColumns()).where("_id").greaterThan("id5").execute();

		int count = 0;
		while (data.next()) {			
		    org.apache.metamodel.data.Row row = data.getRow();
		    assertEquals(row.getValue(2), "test_string" + (count + 6));
		    count++;
		}		
		assertEquals(4,count);
	}
	
	public static class DateBean {
		DateBean() {}
		DateBean(Date d) { this.d = d; }
		Date d;
	}
	
	@Test
	public void dateTest() {
		
		final MongoDbCrudService<DateBean, String> service = getTestService("dateTest", DateBean.class, String.class);
				
		service.storeObject(new DateBean(new Date(new Date().getTime() - 1000L))).join();
		assertEquals(1L, service.countObjects().join().longValue());
		
		assertEquals(1L, service._state.orig_coll.count(new BasicDBObject("d", new BasicDBObject("$lt", new Date()))));
		assertEquals(1L, service._state.coll.count(new BasicDBObject("d", new BasicDBObject("$lt", new Date()))));
		
		assertEquals(1L, service.countObjectsBySpec(CrudUtils.allOf(DateBean.class)
							.rangeBelow("d", new Date(), true)).join().longValue()
				);
		
		assertEquals(1L, service.deleteObjectsBySpec(CrudUtils.allOf(DateBean.class)
				.rangeBelow("d", new Date(), true)).join().longValue());
				
		assertEquals(0L, service._state.orig_coll.count(new BasicDBObject("d", new BasicDBObject("$lt", new Date()))));
	}
}
