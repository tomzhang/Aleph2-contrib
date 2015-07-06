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
 ******************************************************************************/
package com.ikanow.aleph2.search_service.elasticsearch.utils;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;

import scala.Tuple2;
import scala.Tuple3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.Patterns;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean.SearchIndexSchemaDefaultBean;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean.SearchIndexSchemaDefaultBean.CollidePolicy;

import fj.data.Either;

/** A collection of utilities for converting buckets into Elasticsearch attributes
 * @author Alex
 */
public class ElasticsearchIndexUtils {


	/////////////////////////////////////////////////////////////////////
	
	// INDEX NAMES
	
	/** Returns the base index name (before any date strings, splits etc) have been appended
	 * @param bucket
	 * @return
	 */
	public static String getBaseIndexName(final DataBucketBean bucket) {
		return bucket._id().toLowerCase().replace("-", "_");
	}
	
	/** Returns either a specifc type name, or "_default_" if auto types are used
	 * @param bucket
	 * @return
	 */
	public static String getTypeKey(final DataBucketBean bucket, final ObjectMapper mapper) {
		return Optional.ofNullable(bucket.data_schema())
					.map(DataSchemaBean::search_index_schema)				
					.filter(s -> Optional.ofNullable(s.enabled()).orElse(true))
					.map(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema)
					.map(t -> BeanTemplateUtils.from(mapper.convertValue(t, JsonNode.class), SearchIndexSchemaDefaultBean.class).get())
					.<String>map(cfg -> {
						return Patterns.match(cfg.collide_policy()).<String>andReturn()
								.when(cp -> SearchIndexSchemaDefaultBean.CollidePolicy.error == cp, 
										__ -> Optional.ofNullable(cfg.type_name_or_prefix()).orElse(ElasticsearchIndexServiceConfigBean.DEFAULT_FIXED_TYPE_NAME)) // (ie falls through to default below)
								.otherwise(__ -> null);
					})
				.orElse("_default_"); // (the default - "auto type")
	}
	
	/** Converts any index back to its spawning bucket
	 * @param index_name - the elasticsearch index name
	 * @return
	 */
	public static String getBucketIdFromIndexName(String index_name) {
		return index_name.replaceFirst("([a-z0-9]+_[a-z0-9]+_[a-z0-9]+_[a-z0-9]+_[a-z0-9]+).*", "$1").replace("_", "-");
	}
	
	/////////////////////////////////////////////////////////////////////
	
	// MAPPINGS - DEFAULTS
	
	/** Builds a lookup table of settings 
	 * @param mapping - the mapping to use
	 * @param type - if the index has a specific type, lookup that and _default_ ; otherwise just _default
	 * @return
	 */
	public static LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> parseDefaultMapping(final JsonNode mapping, final Optional<String> type) {
		final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> ret = 
				Optional.ofNullable(mapping.get("mappings"))
					.map(m -> {
						if (!m.isObject()) throw new RuntimeException("mappings must be object");
						return m;
					})
					.map(m -> Optional.ofNullable(m.get(type.orElse("_default_")))
												.map(mm -> !mm.isNull() ? mm : m.get("_default_"))
											.orElse(m.get("_default_")))
					.filter(m -> !m.isNull())
					.map(i -> {
						if (!i.isObject()) throw new RuntimeException(type + " must be object");
						return i;
					})
					.map(i -> {
						final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> props = getProperties(i);						
						props.putAll(getTemplates(i));
						return props;
					})
					.orElse(new LinkedHashMap<>());
		
		return ret;
	}
	
	/** Get a set of field mappings from the "properties" section of a mapping
	 * @param index
	 * @return
	 */
	protected static LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> getProperties(final JsonNode index) {
		return Optional.ofNullable(index.get("properties"))
					.filter(p -> !p.isNull())
					.map(p -> {
						if (!p.isObject()) throw new RuntimeException("properties must be object");
						return p;
					})
					.map(p -> {
						return StreamSupport.stream(Spliterators.spliteratorUnknownSize(p.fields(), Spliterator.ORDERED), false)
							.map(kv -> {
								if (!kv.getValue().has("type")) throw new RuntimeException("type must have a field");
								return kv;
							})
							.collect(Collectors.
									<Map.Entry<String, JsonNode>, Either<String, Tuple2<String, String>>, JsonNode, LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>>
									toMap(
										kv -> Either.<String, Tuple2<String, String>>left(kv.getKey()),
										kv -> kv.getValue(),
										(v1, v2) -> v1, // (should never happen)
										() -> new LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>()
									));
					})
					.orElse(new LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>());
	}
	
	/** Get a set of field mappings from the "dynamic_templates" section of a mapping
	 * @param index
	 * @return
	 */
	protected static LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> getTemplates(final JsonNode index) {
		return Optional.ofNullable(index.get("dynamic_templates"))
					.filter(p -> !p.isNull())					
					.map(p -> {
						if (!p.isArray()) throw new RuntimeException("dynamic_templates must be object");
						return p;
					})
					.map(p -> {
						return StreamSupport.stream(Spliterators.spliteratorUnknownSize(p.elements(), Spliterator.ORDERED), false)
							.map(pf -> {
								if (!pf.isObject()) throw new RuntimeException("dynamic_templates[*] must be object");
								return pf;
							})
							.flatMap(pp -> StreamSupport.stream(Spliterators.spliteratorUnknownSize(pp.fields(), Spliterator.ORDERED), false))
							.collect(Collectors.
								<Map.Entry<String, JsonNode>, Either<String, Tuple2<String, String>>, JsonNode, LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>>
								toMap(
									kv -> Either.right(buildMatchPair(kv.getValue())),
									kv -> kv.getValue(),
									(v1, v2) -> v1, // (should never happen)
									() -> new LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>()
								));
					})
					.orElse(new LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>());
	}
	
	private static final String RAW_MATCH_NAME = "match";	
	private static final String PATH_MATCH_NAME = "path_match";	
	private static final String TYPE_MATCH_NAME = "match_mapping_type";
	
	/** Builds a match pair from a field mapping
	 * @param template
	 * @return
	 */
	protected static Tuple2<String, String> buildMatchPair(final JsonNode template) {
		return Tuples._2T(
				Optional.ofNullable(template.get(RAW_MATCH_NAME)).map(j -> j.asText())
					.orElse(Optional.ofNullable(template.get(PATH_MATCH_NAME)).map(j -> j.asText()).orElse("*"))				
				,
				Optional.ofNullable(template.get(TYPE_MATCH_NAME)).map(j -> j.asText()).orElse("*")
				);
	}
	
	/** Creates a single string from a match/match_mapping_type pair
	 * @param field_info
	 * @return
	 */
	protected static String getFieldNameFromMatchPair(final Tuple2<String, String> field_info) {
		return field_info._1().replace("*", "STAR").replace("_", "BAR") + "_" + field_info._2().replace("*", "STAR");
	};
	
	
	
	/////////////////////////////////////////////////////////////////////
	
	// MAPPINGS - CREATION
	
	// Quick guide to mappings
	// under mappings you can specify either
	// - specific types
	// - _default_, which applies to anything that doesn't match that type
	//   - then under each type (or _default_)..
	//      - you can specify dynamic_templates/properties/_all/dynamic_date_formats/date_detection/numeric_detection
	//         - under properties you can then specify types and then fields
	//         - under dynamic_templates you can specify fields
	//           - under fields you can specify type/fielddata(*)/similarity/analyzer/etc
	//
	// (*) https://www.elastic.co/guide/en/elasticsearch/reference/current/fielddata-formats.html
	//
	// OK so we can specify parts of mappings in the following ways:
	// - COLUMNAR: 
	//   - based on field name .. maps to path_match
	//   - based on type .. maps to match_mapping_type
	//   (and then for these columnar types we want to specify 
	//      "type": "{dynamic_type}", "index": "no", "fielddata": { "format": "doc_values" } // (or disabled)
	//      but potentially would like to be able to add more info as well/instead
	//      so maybe need a default and then per-key override
	//
	// OK ... then in addition, we want to be able to set other elements of the search from the search override schema
	// The simplest way of doing this is probably just to force matching on fields/patterns and then to merge them
	
	
	///////////////////////////////////////////////////////////////
	
	// TEMPORAL PROCESSING
	
	/** Creates a mapping for the bucket - temporal elements
	 * @param bucket
	 * @return
	 * @throws IOException 
	 */
	public static XContentBuilder getTemporalMapping(final DataBucketBean bucket, final Optional<XContentBuilder> to_embed) {
		try {
			final XContentBuilder start = to_embed.orElse(XContentFactory.jsonBuilder().startObject());
			if (!Optional.ofNullable(bucket.data_schema())
					.map(DataSchemaBean::temporal_schema)
					.filter(s -> Optional.ofNullable(s.enabled()).orElse(true))
					.isPresent()) 
						return start;			
			
			// Nothing to be done here
			
			return start;
		}
		catch (IOException e) {
			//Handle fake "IOException"
			return null;
		}
	}

	///////////////////////////////////////////////////////////////
	
	// COLUMNAR PROCESSING
	
	// (Few constants to tidy stuff up)
	protected final static String BACKUP_FIELD_MAPPING_PROPERTIES = "{\"index\":\"not_analyzed\"}";
	protected final static String BACKUP_FIELD_MAPPING_TEMPLATES = "{\"mapping\":" + BACKUP_FIELD_MAPPING_PROPERTIES + "}";
	protected final static String DEFAULT_FIELDDATA_NAME = "_default_";
	protected final static String DISABLED_FIELDDATA = "{\"format\":\"disabled\"}";
	
	
	/** Creates a mapping for the bucket - columnar elements
	 * @param bucket
	 * @return
	 * @throws IOException 
	 */
	public static XContentBuilder getColumnarMapping(final DataBucketBean bucket, Optional<XContentBuilder> to_embed,
														final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> field_lookups,
														final JsonNode enabled_not_analyzed, final JsonNode enabled_analyzed,
														final JsonNode default_not_analyzed, final JsonNode default_analyzed,
														final ObjectMapper mapper, final String index_type)
	{
		try {
			final XContentBuilder start = to_embed.orElse(XContentFactory.jsonBuilder().startObject());
			if (!Optional.ofNullable(bucket.data_schema())
					.map(DataSchemaBean::columnar_schema)
					.filter(s -> Optional.ofNullable(s.enabled()).orElse(true))
					.isPresent()) 
						return start;			
			
			final Map<Either<String, Tuple2<String, String>>, JsonNode> column_lookups = Stream.of(			
				createFieldIncludeLookups(Optionals.ofNullable(bucket.data_schema().columnar_schema().field_include_list()).stream(),
						fn -> Either.left(fn), 
						field_lookups, enabled_not_analyzed, enabled_analyzed, true, mapper, index_type
						)
						,
				createFieldExcludeLookups(Optionals.ofNullable(bucket.data_schema().columnar_schema().field_exclude_list()).stream(),
						fn -> Either.left(fn), 
						field_lookups, mapper, index_type
						)
						,
				createFieldIncludeLookups(Optionals.ofNullable(bucket.data_schema().columnar_schema().field_include_pattern_list()).stream(),
						fn -> Either.right(Tuples._2T(fn, "*")), 
						field_lookups, enabled_not_analyzed, enabled_analyzed, true, mapper, index_type
						)
						,	
				createFieldIncludeLookups(Optionals.ofNullable(bucket.data_schema().columnar_schema().field_type_include_list()).stream(),
						fn -> Either.right(Tuples._2T("*", fn)), 
						field_lookups, enabled_not_analyzed, enabled_analyzed, true, mapper, index_type
						)
						,			
				createFieldExcludeLookups(Optionals.ofNullable(bucket.data_schema().columnar_schema().field_exclude_pattern_list()).stream(),
						fn -> Either.right(Tuples._2T(fn, "*")), 
						field_lookups, mapper, index_type
						)
						,
				createFieldExcludeLookups(Optionals.ofNullable(bucket.data_schema().columnar_schema().field_type_exclude_list()).stream(),
						fn -> Either.right(Tuples._2T("*", fn)), 
						field_lookups, mapper, index_type
					),
					
				// Finally add the default columnar lookups to the unmentioned strings
					
				field_lookups.entrySet().stream()
					.flatMap(kv -> createFieldIncludeLookups(Stream.of(kv.getKey().toString()), __ -> kv.getKey(),
							field_lookups, default_not_analyzed, default_analyzed, false, mapper, index_type
							))
			)
			.flatMap(x -> x)
			.collect(Collectors.toMap(
					t2 -> t2._1(),
					t2 -> t2._2(),
					(v1, v2) -> v1 // (ie ignore duplicates)
					));
			;			
			
			// Build the mapping in 2 stages - left (properties) then right (dynamic_templates)
			
			final XContentBuilder properties = column_lookups.entrySet().stream()
							// properties not dynamic_templates
							.filter(kv -> kv.getKey().isLeft())
							// overwrite with version built using columns if it exists
							.map(kv -> Tuples._2T(kv.getKey(), column_lookups.getOrDefault(kv.getKey(), kv.getValue())))
							.reduce(
								start.startObject("properties"), 
								Lambdas.wrap_u((acc, t2) -> acc.rawField(t2._1().left().value(), t2._2().toString().getBytes())), // (left by construction) 
								(acc1, acc2) -> acc1) // (not actually possible)
							.endObject();

			final XContentBuilder templates = column_lookups.entrySet().stream()
					// properties not dynamic_templates
					.filter(kv -> kv.getKey().isRight())
					// overwrite with version built using columns if it exists
					.map(kv -> Tuples._2T(kv.getKey(), column_lookups.getOrDefault(kv.getKey(), kv.getValue())))
					.reduce(
							properties.startArray("dynamic_templates"),
							Lambdas.wrap_u((acc, t2) -> acc.startObject()
															.rawField(getFieldNameFromMatchPair(t2._1().right().value()), t2._2().toString().getBytes()) // (right by construction)
														.endObject()),  						
							(acc1, acc2) -> acc1) // (not actually possible)
					.endArray();
						
			return templates;
		}
		catch (IOException e) {
			//Handle in-practice-impossible "IOException"
			return null;
		}
	}
	
	/** Creates a list of JsonNodes containing the mapping for fields that will enable field data
	 * @param instream
	 * @param f
	 * @param field_lookups
	 * @param fielddata_not_analyzed
	 * @param fielddata_analyzed
	 * @param override_existing
	 * @param mapper
	 * @return
	 */
	protected static Stream<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>> createFieldIncludeLookups(final Stream<String> instream,
								final Function<String, Either<String, Tuple2<String, String>>> f,
								final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> field_lookups,
								final JsonNode fielddata_not_analyzed, final JsonNode fielddata_analyzed,
								final boolean override_existing,
								final ObjectMapper mapper, final String index_type)
	{
		return createFieldLookups(instream, f, field_lookups, Optional.of(Tuples._3T(fielddata_not_analyzed, fielddata_analyzed, override_existing)), mapper, index_type);
	}
	
	/** Creates a list of JsonNodes containing the mapping for fields that will _disable_ field data
	 * @param instream
	 * @param f
	 * @param field_lookups
	 * @param mapper
	 * @return
	 */
	protected static Stream<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>> createFieldExcludeLookups(final Stream<String> instream,
			final Function<String, Either<String, Tuple2<String, String>>> f,
			final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> field_lookups,
			final ObjectMapper mapper, final String index_type)
	{
		return createFieldLookups(instream, f, field_lookups, Optional.empty(), mapper, index_type);
	}

	/** Creates a list of JsonNodes containing the mapping for fields that will _enable_ or _disable_ field data depending on fielddata_info is present 
	 * @param instream
	 * @param f
	 * @param field_lookups
	 * @param fielddata_info 3tuple containing not_analyzed, analyzed, and override
	 * @param mapper
	 * @return
	 */
	protected static Stream<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>> createFieldLookups(final Stream<String> instream,
			final Function<String, Either<String, Tuple2<String, String>>> f,
			final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> field_lookups,
			final Optional<Tuple3<JsonNode,JsonNode,Boolean>> fielddata_info, 
			final ObjectMapper mapper, final String index_type)
	{
		return instream.<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>>
		map(Lambdas.wrap_u(fn -> {
			final Either<String, Tuple2<String, String>> either = f.apply(fn);
			
			final ObjectNode mutable_field_metadata = (ObjectNode) Optional.ofNullable(field_lookups.get(either))
													.map(j -> j.deepCopy())
													.orElse(either.either(
															Lambdas.wrap_fj_u(__ -> mapper.readTree(BACKUP_FIELD_MAPPING_PROPERTIES)),
															Lambdas.wrap_fj_u(__ -> mapper.readTree(BACKUP_FIELD_MAPPING_TEMPLATES))
															));

			final ObjectNode mutable_field_mapping_tmp = either.isLeft()
								? mutable_field_metadata
								: (ObjectNode) mutable_field_metadata.get("mapping");
								
			final boolean has_type = mutable_field_mapping_tmp.has("type");
			
			final Tuple2<ObjectNode, Either<String, Tuple2<String, String>>> toplevel_eithermod = Lambdas.get(() -> {
				if (either.isLeft() && !has_type) {
					final ObjectNode top_level = (ObjectNode) mapper.createObjectNode().set("mapping", mutable_field_metadata);
					return Tuples._2T(top_level, Either.<String, Tuple2<String, String>>right(Tuples._2T(fn, "*")));
				}
				else {
					return Tuples._2T(mutable_field_metadata, either);
				}
			});
			
			final ObjectNode mutable_field_mapping = toplevel_eithermod._2().isLeft()
								? toplevel_eithermod._1()
								: (ObjectNode) toplevel_eithermod._1().get("mapping");				
			
					
			if (toplevel_eithermod._2().isRight()) {
				if (!toplevel_eithermod._1().has(PATH_MATCH_NAME) && !toplevel_eithermod._1().has(RAW_MATCH_NAME)) {
					toplevel_eithermod._1()
						.put(PATH_MATCH_NAME, toplevel_eithermod._2().right().value()._1());
						
					if (!toplevel_eithermod._1().has(TYPE_MATCH_NAME))														
						toplevel_eithermod._1()
							.put(TYPE_MATCH_NAME, toplevel_eithermod._2().right().value()._2());
				}					
				if (!has_type) {
					if (toplevel_eithermod._2().right().value()._2().equals("*")) { // type is mandatory
						mutable_field_mapping.put("type", "{dynamic_type}");
					}
					else {
						mutable_field_mapping.put("type", toplevel_eithermod._2().right().value()._2());
					}
				}
			}			
			handleMappingFields(mutable_field_mapping, fielddata_info, mapper, index_type);			
			setMapping(mutable_field_mapping, fielddata_info, mapper, index_type);
			return Tuples._2T(toplevel_eithermod._2(), toplevel_eithermod._1()); 
		}));


	}
	
	/** Utility function to handle fields
	 * TODO (ALEPH-14): need to be able to specify different options for different fields via columnar settings
	 * @param mutable_mapping
	 * @param fielddata_info
	 * @param mapper
	 */
	protected static void handleMappingFields(final ObjectNode mutable_mapping, final Optional<Tuple3<JsonNode,JsonNode,Boolean>> fielddata_info, 
										final ObjectMapper mapper, final String index_type)
	{
		Optional.ofNullable(mutable_mapping.get("fields")).filter(j -> !j.isNull() && j.isObject()).ifPresent(j -> {
			StreamSupport.stream(Spliterators.spliteratorUnknownSize(j.fields(), Spliterator.ORDERED), false)
				.forEach(Lambdas.wrap_consumer_u(kv -> {
					final ObjectNode mutable_o = (ObjectNode)kv.getValue();
					setMapping(mutable_o, fielddata_info, mapper, index_type);
				}));
		});
	}
	
	/** More levels of utility for code reuse
	 * @param mutable_o
	 * @param fielddata_info
	 * @param mapper
	 * @param index_type
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	protected static void setMapping(final ObjectNode mutable_o, final Optional<Tuple3<JsonNode,JsonNode,Boolean>> fielddata_info, 
			final ObjectMapper mapper, final String index_type) throws JsonProcessingException, IOException
	{
		if (fielddata_info.isPresent()) {
			final JsonNode fielddata_not_analyzed = fielddata_info.get()._1();
			final JsonNode fielddata_analyzed = fielddata_info.get()._2();
			final boolean override_existing = fielddata_info.get()._3();
			
			final boolean is_analyzed = Optional.ofNullable(mutable_o.get("index"))
											.filter(jj -> !jj.isNull() && jj.isTextual())
											.map(jt -> jt.asText().equalsIgnoreCase("analyzed") || jt.asText().equalsIgnoreCase("yes"))
											.orElse(true); 
			
			final JsonNode fielddata_settings = is_analyzed ? fielddata_analyzed : fielddata_not_analyzed;
			
			Optional.ofNullable(						
				Optional.ofNullable(fielddata_settings.get(index_type))
						.filter(jj -> !jj.isNull())
						.orElse(fielddata_settings.get(DEFAULT_FIELDDATA_NAME))
				)
				.ifPresent(jj -> { if (override_existing || !mutable_o.has("fielddata")) mutable_o.set("fielddata", jj); } );						
		}
		else {
			mutable_o.set("fielddata", mapper.readTree(DISABLED_FIELDDATA));										
		}
	}
	
	///////////////////////////////////////////////////////////////
	
	// SEARCH PROCESSING
		
	/** Creates a mapping for the bucket - search service elements .. up to but not including the mapping + type
	 *  NOTE: creates an embedded object that is {{, ie must be closed twice subsequently in order to be a well formed JSON object
	 * @param bucket
	 * @return
	 * @throws IOException 
	 */
	public static XContentBuilder getSearchServiceMapping(final DataBucketBean bucket,
															final ElasticsearchIndexServiceConfigBean schema_config,
															final Optional<XContentBuilder> to_embed,
															final ObjectMapper mapper)
	{
		try {
			final XContentBuilder start = to_embed.orElse(XContentFactory.jsonBuilder().startObject());

			// (Nullable)
			final ElasticsearchIndexServiceConfigBean.SearchIndexSchemaDefaultBean search_schema = schema_config.search_technology_override();
			
			//(very briefly Nullable)
			final JsonNode settings = Optional.ofNullable(search_schema)
											.map(s -> s.settings())
											.map(o -> mapper.convertValue(o, JsonNode.class))
											.orElse(null);
			
			//(very briefly Nullable)
			final JsonNode aliases = Optional.ofNullable(search_schema)
											.map(s -> s.aliases())
											.map(o -> mapper.convertValue(o, JsonNode.class))
											.orElse(null);
			
			// Settings
			
			final String type_key = getTypeKey(bucket, mapper);
			
			return Lambdas.wrap_u(__ -> {
				if (null == settings) { // nothing to do
					return start;
				}
				else {
					return start.rawField("settings", settings.toString().getBytes());
				}
			})
			// Aliases
			.andThen(Lambdas.wrap_u(json -> {
				if (null == aliases) { // nothing to do
					return json;
				}
				else {
					return start.rawField("aliases", aliases.toString().getBytes());
				}
			}))
			// Mappings and overrides
			.andThen(Lambdas.wrap_u(json -> json.startObject("mappings").startObject(type_key)))
			// More mapping overrides
			.andThen(Lambdas.wrap_u(json -> {
				
				return Optional.ofNullable(search_schema)
								.map(ss -> ss.mapping_overrides())
								.map(m -> m.getOrDefault(type_key, m.get("*")))
							.orElse(Collections.emptyMap())
							.entrySet().stream()
							.reduce(json, 
									Lambdas.wrap_u(
											(acc, kv) -> acc.rawField(kv.getKey(), mapper.convertValue(kv.getValue(), JsonNode.class).toString().getBytes())), 
									(acc1, acc2) -> acc1 // (can't actually ever happen)
									)
							;
			}))
			.apply(null);
		}
		catch (IOException e) {
			//Handle fake "IOException"
			return null;
		}
	}

	///////////////////////////////////////////////////////////////
	
	// TEMPLATE CREATION
	
	/** Create a template to be applied to all indexes generated from this bucket
	 * @param bucket
	 * @return
	 */
	public static XContentBuilder getTemplateMapping(final DataBucketBean bucket) {
		try {		
			final XContentBuilder start = XContentFactory.jsonBuilder().startObject()
											.field("template", 
													getBaseIndexName(bucket) + "*"
													);
			
			return start;
		}
		catch (IOException e) {
			//Handle fake "IOException"
			return null;
		}
	}
	
	/** The control method to build up the mapping from the constituent parts
	 * @param bucket
	 * @param field_lookups
	 * @param enabled_not_analyzed
	 * @param enabled_analyzed
	 * @param mapper
	 * @return
	 */
	protected static XContentBuilder getFullMapping(final DataBucketBean bucket, final ElasticsearchIndexServiceConfigBean schema_config,
			final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> field_lookups,
			final JsonNode enabled_not_analyzed, final JsonNode enabled_analyzed,
			final JsonNode default_not_analyzed, final JsonNode default_analyzed,
			final ObjectMapper mapper, final String index_type)
	{
		return Lambdas.wrap_u(__ -> getTemplateMapping(bucket))
				.andThen(json -> getSearchServiceMapping(bucket, schema_config, Optional.of(json), mapper))
				.andThen(json -> getColumnarMapping(bucket, Optional.of(json), field_lookups, enabled_not_analyzed, enabled_analyzed, default_not_analyzed, default_analyzed, mapper, index_type))
				.andThen(Lambdas.wrap_u(json -> json.endObject().endObject())) // (close the objects from the search service mapping)
				.andThen(json -> getTemporalMapping(bucket, Optional.of(json)))
			.apply(null);
	}
	
	/** Utility function to create a mapping out of all the different system components (see also ElasticsearchUtils)
	 * @param bucket
	 * @param config
	 * @return
	 */
	public static XContentBuilder createIndexMapping(final DataBucketBean bucket, final ElasticsearchIndexServiceConfigBean schema_config, final ObjectMapper mapper, final String index_type) {
		
		final JsonNode default_mapping = mapper.convertValue(schema_config.search_technology_override(), JsonNode.class);
		
		// Also get JsonNodes for the default field config bit
		
		// (these can't be null by construction)
		final JsonNode enabled_analyzed_field = mapper.convertValue(schema_config.columnar_technology_override().enabled_field_data_analyzed(), JsonNode.class);
		final JsonNode enabled_not_analyzed_field = mapper.convertValue(schema_config.columnar_technology_override().enabled_field_data_notanalyzed(), JsonNode.class);
		final JsonNode default_analyzed_field = mapper.convertValue(schema_config.columnar_technology_override().default_field_data_analyzed(), JsonNode.class);
		final JsonNode default_not_analyzed_field = mapper.convertValue(schema_config.columnar_technology_override().default_field_data_notanalyzed(), JsonNode.class);
		
		// Get a list of field overrides Either<String,Tuple2<String,String>> for dynamic/real fields
		
		final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> 
			field_lookups = ElasticsearchIndexUtils.parseDefaultMapping(default_mapping, 
					(CollidePolicy.new_type == Optional.ofNullable(schema_config.search_technology_override().collide_policy()).orElse(CollidePolicy.new_type))
							? Optional.empty()
							: Optional.ofNullable(schema_config.search_technology_override().type_name_or_prefix())
						);
		
		final XContentBuilder test_result = getFullMapping(
				bucket, schema_config, field_lookups, 
				enabled_not_analyzed_field, enabled_analyzed_field, 
				default_not_analyzed_field, default_analyzed_field,  
				mapper, index_type);		

		return test_result;
	}
}