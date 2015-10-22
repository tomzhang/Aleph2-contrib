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
package com.ikanow.aleph2.search_service.elasticsearch.services;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequestBuilder;
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.indices.IndexMissingException;

import scala.Tuple2;
import scala.Tuple3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.ikanow.aleph2.data_model.interfaces.data_services.IColumnarService;
import com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService;
import com.ikanow.aleph2.data_model.interfaces.data_services.ITemporalService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IExtraDependencyLoader;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISubject;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.ColumnarSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.SearchIndexSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.TemporalSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.TimeUtils;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean.SearchIndexSchemaDefaultBean;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean.SearchIndexSchemaDefaultBean.CollidePolicy;
import com.ikanow.aleph2.search_service.elasticsearch.module.ElasticsearchIndexServiceModule;
import com.ikanow.aleph2.search_service.elasticsearch.utils.ElasticsearchIndexConfigUtils;
import com.ikanow.aleph2.search_service.elasticsearch.utils.ElasticsearchIndexUtils;
import com.ikanow.aleph2.search_service.elasticsearch.utils.SearchIndexErrorUtils;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchContext;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.ElasticsearchCrudService.CreationPolicy;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.IElasticsearchCrudServiceFactory;
import com.ikanow.aleph2.shared.crud.elasticsearch.utils.ElasticsearchContextUtils;
import com.ikanow.aleph2.shared.crud.elasticsearch.utils.ElasticsearchFutureUtils;

import fj.data.Either;
import fj.data.Java8;
import fj.data.Validation;

/** Elasticsearch implementation of the SearchIndexService/TemporalService/ColumnarService
 * @author Alex
 *
 */
public class ElasticsearchIndexService implements ISearchIndexService, ITemporalService, IColumnarService, IExtraDependencyLoader {
	final static protected Logger _logger = LogManager.getLogger();

	protected final IServiceContext _service_context; // (need the security service)
	protected final IElasticsearchCrudServiceFactory _crud_factory;
	protected final ElasticsearchIndexServiceConfigBean _config;
	
	protected final static ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());
	
	protected final Cache<String, Date> _bucket_template_cache = CacheBuilder.newBuilder().expireAfterAccess(2, TimeUnit.HOURS).build();
	
	/** Guice generated constructor
	 * @param crud_factory
	 */
	@Inject
	public ElasticsearchIndexService(
			final IServiceContext service_context,
			final IElasticsearchCrudServiceFactory crud_factory,
			final ElasticsearchIndexServiceConfigBean configuration)
	{
		_service_context = service_context;
		_crud_factory = crud_factory;
		_config = configuration;
	}
		
	/** Checks if an index/set-of-indexes spawned from a bucket
	 * @param bucket
	 */
	protected Optional<JsonNode> handlePotentiallyNewIndex(
			final DataBucketBean bucket, 
			final Optional<String> secondary_buffer, final boolean is_primary, 
			final ElasticsearchIndexServiceConfigBean schema_config, 
			final String index_type)
	{
		try {
			// Will need the current mapping regardless (this is just some JSON parsing so should be pretty quick):
			final XContentBuilder mapping = ElasticsearchIndexUtils.createIndexMapping(bucket, secondary_buffer, is_primary, schema_config, _mapper, index_type);
			final JsonNode user_mapping = _mapper.readTree(mapping.bytes().toUtf8());
			
			final String cache_key = bucket._id() + secondary_buffer.map(s -> ":" + s).orElse("") + ":" + Boolean.toString(is_primary);
			final Date current_template_time = _bucket_template_cache.getIfPresent(cache_key);
			if ((null == current_template_time) || current_template_time.before(Optional.ofNullable(bucket.modified()).orElse(new Date()))) {			
				try {
					final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(ElasticsearchIndexUtils.getBaseIndexName(bucket, secondary_buffer));
					final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
					
					if (gtr.getIndexTemplates().isEmpty() 
							|| 
						!mappingsAreEquivalent(gtr.getIndexTemplates().get(0), user_mapping, _mapper))
					{
						// If no template, or it's changed, then update
						final String base_name = ElasticsearchIndexUtils.getBaseIndexName(bucket, secondary_buffer);
						_crud_factory.getClient().admin().indices().preparePutTemplate(base_name).setSource(mapping).execute().actionGet();
						
						_logger.info(ErrorUtils.get("Updated mapping for bucket={0}, base_index={1}", bucket.full_name(), base_name));
					}				
				}
				catch (Exception e) {
					_logger.error(ErrorUtils.getLongForm("Error updating mapper bucket={1} err={0}", e, bucket.full_name()));
				}
				_bucket_template_cache.put(cache_key, bucket.modified());			
			}
			return Optional.of(user_mapping);
		}
		catch (Exception e) {
			return Optional.empty();
		}
	}
	
	/** Check if a new mapping based on a schema is equivalent to a mapping previously stored (from a schema) 
	 * @param stored_mapping
	 * @param new_mapping
	 * @param mapper
	 * @return
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	protected static boolean mappingsAreEquivalent(final IndexTemplateMetaData stored_mapping, final JsonNode new_mapping, final ObjectMapper mapper) throws JsonProcessingException, IOException {		
		
		final ObjectNode stored_json_mappings = StreamSupport.stream(stored_mapping.mappings().spliterator(), false)
													.reduce(mapper.createObjectNode(), 
															Lambdas.wrap_u((acc, kv) -> (ObjectNode) acc.setAll((ObjectNode) mapper.readTree(kv.value.string()))), 
															(acc1, acc2) -> acc1); // (can't occur)
		
		final JsonNode new_json_mappings = Optional.ofNullable(new_mapping.get("mappings")).orElse(mapper.createObjectNode());
		
		final JsonNode stored_json_settings = mapper.convertValue(
												Optional.ofNullable(stored_mapping.settings()).orElse(ImmutableSettings.settingsBuilder().build())
													.getAsMap(), JsonNode.class);

		final JsonNode new_json_settings = Optional.ofNullable(new_mapping.get("settings")).orElse(mapper.createObjectNode());
				
		final ObjectNode stored_json_aliases = StreamSupport.stream(Optional.ofNullable(stored_mapping.aliases()).orElse(ImmutableOpenMap.of()).spliterator(), false)
				.reduce(mapper.createObjectNode(), 
						Lambdas.wrap_u((acc, kv) -> (ObjectNode) acc.set(kv.key, kv.value.filteringRequired()
								? mapper.createObjectNode().set("filter", mapper.readTree(kv.value.filter().string()))
								: mapper.createObjectNode()
								)),
						(acc1, acc2) -> acc1); // (can't occur)
		
		final JsonNode new_json_aliases = Optional.ofNullable(new_mapping.get("aliases")).orElse(mapper.createObjectNode());
		
		return stored_json_mappings.equals(new_json_mappings) && stored_json_settings.equals(new_json_settings) && stored_json_aliases.equals(new_json_aliases);		
	}
	
	////////////////////////////////////////////////////////////////////////////////
	
	// GENERIC DATA INTERFACE
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider#getDataService()
	 */
	@Override
	public Optional<IDataServiceProvider.IGenericDataService> getDataService() {
		return _data_service;
	}

	/** Low level utility - Performs some initializations used in a few different places
	 * @return
	 */
	protected static Tuple3<ElasticsearchIndexServiceConfigBean, String, Optional<String>> getSchemaConfigAndIndexAndType(final DataBucketBean bucket, final ElasticsearchIndexServiceConfigBean config)
	{
		final ElasticsearchIndexServiceConfigBean schema_config = ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, config, _mapper);
		
		final Optional<String> type = Optional.ofNullable(schema_config.search_technology_override()).map(t -> t.type_name_or_prefix());
		final String index_type = CollidePolicy.new_type == Optional.ofNullable(schema_config.search_technology_override())
									.map(t -> t.collide_policy()).orElse(CollidePolicy.new_type)
										? "_default_"
										: type.orElse(ElasticsearchIndexServiceConfigBean.DEFAULT_FIXED_TYPE_NAME);
		
		return Tuples._3T(schema_config, index_type, type);
	}
	
	/** Low level utility - grab a stream of JsonNodes of template mappings
	 * @param bucket
	 * @param buffer_name
	 * @return
	 */
	protected static Stream<JsonNode> getMatchingTemplatesWithMeta(final DataBucketBean bucket, final Optional<String> buffer_name, Client client) {
		
		final String base_index = ElasticsearchIndexUtils.getBaseIndexName(bucket, Optional.empty());
		final String index_glob = buffer_name
				.map(name -> ElasticsearchIndexUtils.getBaseIndexName(bucket, Optional.of(name)))
				.orElseGet(() -> {
					final String random_string = Stream.generate(() -> (long)(Math.random()*1000000L)).map(l -> Long.toString(l)).filter(s -> !base_index.contains(s)).findAny().get();						
					return ElasticsearchIndexUtils.getBaseIndexName(bucket, Optional.of(random_string)).replace(random_string, "*");
				});
		
		final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(index_glob);
		final GetIndexTemplatesResponse gtr = client.admin().indices().getTemplates(gt).actionGet();
		
		// OK for each template, grab the first element's "_meta" (requires converting to a string) and check if it has a secondary buffer
		return gtr.getIndexTemplates()
					.stream()
					.map(index -> index.getMappings())
					.filter(map -> !map.isEmpty())
					.map(map -> map.valuesIt().next()) // get the first type, will typically be _default_
					.map(comp_string -> new String(comp_string.uncompressed()))
					.flatMap(Lambdas.flatWrap_i(mapstr -> _mapper.readValue(mapstr, JsonNode.class)))
					.map(json -> json.iterator())
					.filter(json_it -> json_it.hasNext())
					.map(json_it -> json_it.next())
					.map(json -> json.get(ElasticsearchIndexUtils.CUSTOM_META))
					.filter(json -> (null != json) && json.isObject())
					;
	}
	
	/** Implementation of GenericDataService
	 * @author alex
	 */
	public class ElasticsearchDataService implements IDataServiceProvider.IGenericDataService {

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#getWritableDataService(java.lang.Class, com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional, java.util.Optional)
		 */
		@Override
		public <O> Optional<IDataWriteService<O>> getWritableDataService(
				Class<O> clazz, DataBucketBean bucket,
				Optional<String> options, Optional<String> secondary_buffer)
		{
			// There's two different cases
			// 1) Multi-bucket - equivalent to the other version of getCrudService
			// 2) Single bucket - a read/write bucket
			
			if ((null != bucket.multi_bucket_children()) && !bucket.multi_bucket_children().isEmpty()) {
				throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "ElasticsearchDataService.getWritableDataService, multi_bucket_children"));
			}
			
			// If single bucket, is the search index service enabled?
			if (!Optional.ofNullable(bucket.data_schema())
					.map(ds -> ds.search_index_schema())
						.map(sis -> Optional.ofNullable(sis.enabled())
						.orElse(true))
				.orElse(false))
			{
				return Optional.empty();
			}
			
			// OK so it's a legit single bucket ... first question ... does this already exist?
			
			final Tuple3<ElasticsearchIndexServiceConfigBean, String, Optional<String>> schema_index_type = getSchemaConfigAndIndexAndType(bucket, _config);
						
			// only create aliases within ES context if there is no primary (ie secondary buffers), or if the secondary currently points at the primary
			final boolean is_primary =  getPrimaryBufferName(bucket, Optional.empty())
											.map(primary -> Optional.of(primary).equals(secondary_buffer))
											.orElseGet(() -> { // (no primary buffer, just return true if i'm the default current 
												return !secondary_buffer.isPresent();
											});					
			
			final Optional<JsonNode> user_mapping = handlePotentiallyNewIndex(bucket, secondary_buffer, is_primary, schema_index_type._1(), schema_index_type._2());
			
			// Need to decide a) if it's a time based index b) an auto type index
			// And then build the context from there
			
			final Validation<String, ChronoUnit> time_period = TimeUtils.getTimePeriod(Optional.ofNullable(schema_index_type._1().temporal_technology_override())
																	.map(t -> t.grouping_time_period()).orElse(""));

			final Optional<Long> target_max_index_size_mb = Optionals.of(() -> schema_index_type._1().search_technology_override().target_index_size_mb());

			// LAMBDA util to pass into the ElasticsearchContext - determines dynamically if the alias should be generated
			// (ElasticsearchContext is responsible for calling it efficiently)
			final Function<String, Optional<String>> aliasCheck = index_name -> {
				final Optional<String> base_index = Optional.of(ElasticsearchIndexUtils.getBaseIndexName(bucket, Optional.empty()));
				final Optional<String> primary_buffer = 
						getPrimaryBufferName(bucket, Optional.empty())
							.<Optional<String>> map(name -> {
								String primary_index = ElasticsearchIndexUtils.getBaseIndexName(bucket, Optional.of(name));
								return primary_index.equals(index_name)
										? base_index
										: Optional.empty()
										;
							})
							.orElseGet(() -> { // (no primary buffer, just return true if i'm the default current 
								return secondary_buffer.isPresent()
										? Optional.empty()
										: base_index
										;
							})					
							;
				return primary_buffer;
			};
			
			// Index
			final String index_base_name = ElasticsearchIndexUtils.getBaseIndexName(bucket, secondary_buffer);
			final ElasticsearchContext.IndexContext.ReadWriteIndexContext index_context = time_period.validation(
					fail -> new ElasticsearchContext.IndexContext.ReadWriteIndexContext.FixedRwIndexContext(index_base_name, target_max_index_size_mb, Either.right(aliasCheck))
					, 
					success -> new ElasticsearchContext.IndexContext.ReadWriteIndexContext.TimedRwIndexContext(index_base_name + ElasticsearchContextUtils.getIndexSuffix(success), 
										Optional.ofNullable(schema_index_type._1().temporal_technology_override().time_field()), target_max_index_size_mb, Either.right(aliasCheck))
					);			
			
			final boolean auto_type = 
					CollidePolicy.new_type == Optional.ofNullable(schema_index_type._1().search_technology_override())
						.map(t -> t.collide_policy()).orElse(CollidePolicy.new_type);
			
			final Set<String> fixed_type_fields = Lambdas.get(() -> {
				if (auto_type && user_mapping.isPresent()) {
					return ElasticsearchIndexUtils.getAllFixedFields(user_mapping.get());
				}
				else {
					return Collections.<String>emptySet();
				}				
			});
			
			// Type
			final ElasticsearchContext.TypeContext.ReadWriteTypeContext type_context =
					(auto_type && user_mapping.isPresent())
						? new ElasticsearchContext.TypeContext.ReadWriteTypeContext.AutoRwTypeContext(Optional.empty(), schema_index_type._3(), fixed_type_fields)
						: new ElasticsearchContext.TypeContext.ReadWriteTypeContext.FixedRwTypeContext(schema_index_type._3().orElse(ElasticsearchIndexServiceConfigBean.DEFAULT_FIXED_TYPE_NAME));
			
			final Optional<DataSchemaBean.WriteSettings> write_settings = Optionals.of(() -> bucket.data_schema().search_index_schema().target_write_settings());
			return Optional.of(_crud_factory.getElasticsearchCrudService(clazz,
									new ElasticsearchContext.ReadWriteContext(_crud_factory.getClient(), index_context, type_context),
									Optional.empty(), 
									CreationPolicy.OPTIMIZED, 
									Optional.empty(), Optional.empty(), Optional.empty(), write_settings));
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#getReadableCrudService(java.lang.Class, java.util.Collection, java.util.Optional)
		 */
		@Override
		public <O> Optional<ICrudService<O>> getReadableCrudService(
				Class<O> clazz, Collection<DataBucketBean> buckets,
				Optional<String> options) {
			throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "ElasticsearchDataService.getReadableCrudService"));
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#getSecondaryBufferList(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean)
		 */
		@Override
		public Set<String> getSecondaryBuffers(DataBucketBean bucket, Optional<String> intermediate_step) {
			if (intermediate_step.isPresent()) {
				return Collections.emptySet();
			}
			return getMatchingTemplatesWithMeta(bucket, Optional.empty(), _crud_factory.getClient())
					.map(json -> json.get(ElasticsearchIndexUtils.CUSTOM_META_SECONDARY))
					.filter(json -> (null != json) && json.isTextual())
					.map(json -> json.asText())
					.filter(buffer_name -> !buffer_name.isEmpty())
					.collect(Collectors.toSet())
					;
		}

		/** Updates all templates corresponding to a bucket to remove the "is_primary" flag, and optionally to add it to the new primary (else add to the "default primary")
		 * @param bucket
		 * @param buffer_name
		 * @param add_not_remove
		 */
		protected void updateTemplates(final DataBucketBean bucket, final Optional<String> new_primary_buffer) {
			final Tuple3<ElasticsearchIndexServiceConfigBean, String, Optional<String>> schema_index_type = getSchemaConfigAndIndexAndType(bucket, _config);
			
			// Remove it for all the other buffers
			Stream.concat(Stream.of(""), getSecondaryBuffers(bucket, Optional.empty()).stream()).parallel()
				.<Optional<String>>map(buffer -> buffer.isEmpty() ? Optional.empty() : Optional.of(buffer))
				.forEach(buffer -> {
					final boolean is_primary = buffer.equals(new_primary_buffer);
					final XContentBuilder mapping = ElasticsearchIndexUtils.createIndexMapping(bucket, buffer, is_primary, schema_index_type._1(), _mapper, schema_index_type._2());					
					final String base_name = ElasticsearchIndexUtils.getBaseIndexName(bucket, buffer);
					_crud_factory.getClient().admin().indices().preparePutTemplate(base_name).setSource(mapping).execute().actionGet();					
				})
				;
		}
		
		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#switchCrudServiceToPrimaryBuffer(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional)
		 */
		@Override
		public CompletableFuture<BasicMessageBean> switchCrudServiceToPrimaryBuffer(
				DataBucketBean bucket, Optional<String> secondary_buffer, Optional<String> new_name_for_ex_primary, Optional<String> intermediate_step)
		{
			if (intermediate_step.isPresent()) {
				return CompletableFuture.completedFuture(
						ErrorUtils.buildErrorMessage("ElasticsearchDataService", "switchCrudServiceToPrimaryBuffer", "intermediate steps not supported in Elasticsearch (bucket {0})", bucket.full_name())); 
			}			
			
			// 0) Grab the current primary before doing anything else
			final Optional<String> curr_primary = getPrimaryBufferName(bucket, intermediate_step);
			
			// 1) Update the templates of the aliases - all but the new primary get "is_primary" set 
			updateTemplates(bucket, secondary_buffer);
			
			// 2) Delete all the existing aliases and set the new ones as transactionally as possible!
			
			final String base_index_name = ElasticsearchIndexUtils.getBaseIndexName(bucket, Optional.empty());
			final String new_primary_index_base = ElasticsearchIndexUtils.getBaseIndexName(bucket, secondary_buffer);
			
			return ElasticsearchFutureUtils.wrap(
					_crud_factory.getClient().admin().indices().prepareStats()
	                    .clear()
	                    .setIndices(new_primary_index_base + "*")
	                    .setStore(true)
	                    .execute()								
					,
					stats -> {						
						// Step 2: delete any indexes that are two far off:
						
						// (format is <base-index-signature>_<date>[_<fragment>])						
						return stats.getIndices().keySet();
					})
					.exceptionally(__ -> Collections.emptySet())
					.thenCompose(indexes -> {
						
						final IndicesAliasesRequestBuilder iarb = 
								_crud_factory.getClient().admin().indices().prepareAliases()
								.removeAlias(ElasticsearchIndexUtils.getBaseIndexName(bucket, curr_primary) + "*", ElasticsearchContext.READ_PREFIX + base_index_name);

						// Add the timestamped aliases
						indexes.stream().reduce(iarb, (acc,  v) -> acc.addAlias(v, ElasticsearchContext.READ_PREFIX + base_index_name), (acc1, acc2) -> acc1);
						
						return ElasticsearchFutureUtils.wrap(iarb.execute(),
								iar -> 
									ErrorUtils.buildSuccessMessage("ElasticsearchDataService", "switchCrudServiceToPrimaryBuffer", "Bucket {0} Added {1} Removed {2}", bucket.full_name(), secondary_buffer, curr_primary.orElse("(none)"))
								)
								.exceptionally(t -> 
									(t instanceof IndexMissingException)
										? ErrorUtils.buildSuccessMessage("ElasticsearchDataService", "switchCrudServiceToPrimaryBuffer", "Bucket {0} Added {1} Removed {2} (no indexes to switch)", bucket.full_name(), secondary_buffer, curr_primary.orElse("(none)"))
										: ErrorUtils.buildErrorMessage("ElasticsearchDataService", "switchCrudServiceToPrimaryBuffer", ErrorUtils.getLongForm("Unknown error bucket {1}: {0}", t, bucket.full_name()))											
								)
								;			
					});
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#getPrimaryBufferName(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean)
		 */
		@Override
		public Optional<String> getPrimaryBufferName(DataBucketBean bucket, Optional<String> intermediate_step) {
			if (intermediate_step.isPresent()) {
				return Optional.empty();
			}			
			// This call gives us the mappings, which includes CUSTOM_META_PRIMARY == if is primary and CUSTOM_META_SECONDARY, ie the string to return
			return getMatchingTemplatesWithMeta(bucket, Optional.empty(), _crud_factory.getClient())
				.filter(json -> {
					final JsonNode is_primary_json = json.get(ElasticsearchIndexUtils.CUSTOM_META_IS_PRIMARY);
					return Optional.ofNullable(is_primary_json)
							.filter(is_pri_json -> is_pri_json.isTextual())
							.map(is_pri_json -> is_pri_json.asText().equalsIgnoreCase("true"))
							.orElse(false);
				})
				.map(json -> json.get(ElasticsearchIndexUtils.CUSTOM_META_SECONDARY))
				.filter(json -> null != json)
				.filter(json -> json.isTextual())
				.map(json -> json.asText())
				.findFirst()
				;
		}
		
		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#handleAgeOutRequest(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean)
		 */
		@Override
		public CompletableFuture<BasicMessageBean> handleAgeOutRequest(final DataBucketBean bucket) {
			// (Age out the secondary buffers)
			getSecondaryBuffers(bucket, Optional.empty()).stream().forEach(Lambdas.wrap_consumer_i(secondary -> handleAgeOutRequest(bucket, Optional.of(secondary))));
			
			// Only return the final value for this:
			return handleAgeOutRequest(bucket, Optional.empty());
		}
		public CompletableFuture<BasicMessageBean> handleAgeOutRequest(final DataBucketBean bucket, Optional<String> secondaryBuffer) {

			// Step 0: get the deletion time
			
			final Optional<String> deletion_bound_str =
					Optionals.of(() -> bucket.data_schema().temporal_schema().exist_age_max());
			
			if (!deletion_bound_str.isPresent()) {
				return CompletableFuture.completedFuture(ErrorUtils.buildSuccessMessage("ElasticsearchDataService", "handleAgeOutRequest", "No age out period specified: {0}", deletion_bound_str.toString()));
			}
			
			final Optional<Long> deletion_bound = deletion_bound_str
						.map(s -> TimeUtils.getDuration(s, Optional.of(new Date())))
						.filter(Validation::isSuccess)
						.map(v -> v.success())
						.map(duration -> 1000L*duration.getSeconds())
					;
			
			if (!deletion_bound.isPresent()) {
				return CompletableFuture.completedFuture(ErrorUtils.buildErrorMessage("ElasticsearchDataService", "handleAgeOutRequest", "No age out period specified: {0}", deletion_bound_str.toString()));
			}
			
			// Step 1: grab all the indexes
			
			final String base_index = ElasticsearchIndexUtils.getBaseIndexName(bucket, secondaryBuffer);
			final long lower_bound = new Date().getTime() - deletion_bound.get();
			
			//(we'll use the stats since a) the alias code didn't seem to work for some reason b) i'm calling that from other places so might be more likely to be cached somewhere?!)
			return ElasticsearchFutureUtils.wrap(
					_crud_factory.getClient().admin().indices().prepareStats()
	                    .clear()
	                    .setIndices(base_index + "*")
	                    .setStore(true)
	                    .execute()								
					,
					stats -> {						
						// Step 2: delete any indexes that are two far off:
						
						// (format is <base-index-signature>_<date>[_<fragment>])						
						final long num_deleted = stats.getIndices().keySet().stream()
								.filter(s -> s.length() > base_index.length()) //(otherwise not a date suffix)
								.map(s -> s.substring(1 + base_index.length())) //(+1 for _)
								.map(s -> {
									final int index = s.lastIndexOf('_');
									return (index < 0)
											? s
											: s.substring(0, index);
								})
								.flatMap(s -> {
									final Validation<String, Date> v = TimeUtils.getDateFromSuffix(s);
									return Java8.Stream_JavaStream(v.toStream())
												.filter(d -> d.getTime() <= lower_bound)
												.map(__ -> s) //(get back to the index)
												;
								}) 
								.collect(Collectors.toSet()) // (collapse fragments)
								.stream()
								.map(date_str -> {
									_crud_factory.getClient().admin().indices()
													.prepareDelete(base_index + "_" + date_str +"*").execute();
									return 1;
								})
								.count()
								;
						
						final BasicMessageBean message = ErrorUtils.buildSuccessMessage("ElasticsearchDataService", "handleAgeOutRequest", "Deleted {0} indexes", num_deleted);
						
						return (num_deleted > 0)
								? BeanTemplateUtils.clone(message).with(BasicMessageBean::details, 
										ImmutableMap.builder().put("loggable", true).build()
										).done()
								: message
								;
						
					})
					.exceptionally(t -> {
						return ErrorUtils.buildErrorMessage("ElasticsearchDataService", "handleAgeOutRequest", ErrorUtils.getLongForm("handleAgeOutRequest error = {0}", t));												
					})
					;			
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#handleBucketDeletionRequest(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional, boolean)
		 */
		@Override
		public CompletableFuture<BasicMessageBean> handleBucketDeletionRequest(
				final DataBucketBean bucket, final Optional<String> secondary_buffer,
				final boolean bucket_or_buffer_getting_deleted) {
			
			if (bucket_or_buffer_getting_deleted && !secondary_buffer.isPresent()) { // if we're deleting the bucket and nothing else is present then
				// delete all the secondary buffers
				getSecondaryBuffers(bucket, Optional.empty()).stream().parallel().forEach(buffer -> handleBucketDeletionRequest(bucket, Optional.of(buffer), true));				
			}
			
			final Optional<ICrudService<JsonNode>> to_delete = this.getWritableDataService(JsonNode.class, bucket, Optional.empty(), secondary_buffer)
															.flatMap(IDataWriteService::getCrudService);
			if (!to_delete.isPresent()) {
				
				return CompletableFuture.completedFuture(
						ErrorUtils.buildSuccessMessage("ElasticsearchDataService", "handleBucketDeletionRequest", "(No search index for bucket {0})", bucket.full_name())
						);
			}
			else { // delete the data			
				final CompletableFuture<Boolean> data_deletion_future = to_delete.get().deleteDatastore();

				final CompletableFuture<BasicMessageBean> combined_future = Lambdas.get(() -> {				
					// delete the template if fully deleting the bucket (vs purging)
					if (bucket_or_buffer_getting_deleted) {
						final CompletableFuture<DeleteIndexTemplateResponse> cf = ElasticsearchFutureUtils.
								<DeleteIndexTemplateResponse, DeleteIndexTemplateResponse>
									wrap(_crud_factory.getClient().admin().indices().prepareDeleteTemplate(ElasticsearchIndexUtils.getBaseIndexName(bucket, secondary_buffer)).execute(),								
											x -> x);
						
						return data_deletion_future.thenCombine(cf, (Boolean b, DeleteIndexTemplateResponse ditr) -> {
							return null; // don't care what the return value is, just care about catching exceptions
						});
					}
					else {					
						return data_deletion_future; // (see above)
					}
				})
				.thenApply(__ -> {
					return ErrorUtils.buildSuccessMessage("ElasticsearchDataService", "handleBucketDeletionRequest", "Deleted search index for bucket {0}{1}", 
							bucket.full_name(),
							secondary_buffer.map(s -> " (buffer:" + s +")").orElse("")
							);
				})
				.exceptionally(t -> {
					return ErrorUtils.buildErrorMessage("ElasticsearchDataService", "handleBucketDeletionRequest", ErrorUtils.getLongForm("Error deleting search index for bucket {1}{2}: {0}", 
							t, bucket.full_name()),
							secondary_buffer.map(s -> " (buffer:" + s +")").orElse("")
							);
				})
				;
				return combined_future;
			}
		}
	}
	private final Optional<IDataServiceProvider.IGenericDataService> _data_service = Optional.of(new ElasticsearchDataService());
	
	////////////////////////////////////////////////////////////////////////////////

	// ES CLIENT ACCESS	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(final Class<T> driver_class, final Optional<String> driver_options) {
		if (Client.class.isAssignableFrom(driver_class)) {
			return (Optional<T>) Optional.of(_crud_factory.getClient());
		}
		return Optional.empty();
	}

	////////////////////////////////////////////////////////////////////////////////

	// VALIDATION	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IColumnarService#validateSchema(com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.ColumnarSchemaBean)
	 */
	@Override
	public Tuple2<String, List<BasicMessageBean>> validateSchema(final ColumnarSchemaBean schema, final DataBucketBean bucket) {
		// (Performed under search index schema)
		return Tuples._2T("", Collections.emptyList());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.ITemporalService#validateSchema(com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.TemporalSchemaBean)
	 */
	@Override
	public Tuple2<String, List<BasicMessageBean>> validateSchema(final TemporalSchemaBean schema, final DataBucketBean bucket) {
		// (time buckets aka default schema options are already validated, nothing else to do)
	
		final Validation<String, ChronoUnit> time_period = TimeUtils.getTimePeriod(Optional.ofNullable(schema)
																			.map(t -> t.grouping_time_period())
																		.orElse(""));
		
		final String time_based_suffix = time_period.validation(fail -> "", success -> ElasticsearchContextUtils.getIndexSuffix(success));
		
		return Tuples._2T(time_based_suffix, Collections.emptyList());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService#validateSchema(com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.SearchIndexSchemaBean)
	 */
	@Override
	public Tuple2<String, List<BasicMessageBean>> validateSchema(final SearchIndexSchemaBean schema, final DataBucketBean bucket) {
		final LinkedList<BasicMessageBean> errors = new LinkedList<BasicMessageBean>(); // (Warning mutable code)
		try {
			// If the user is trying to override the index name then they have to be admin:
			final Optional<String> manual_index_name = Optionals.<String>of(() -> 
				((String) bucket.data_schema().search_index_schema().technology_override_schema().get(SearchIndexSchemaDefaultBean.index_name_override_)));
			
			if (manual_index_name.isPresent()) { // (then must be admin)
				final ISubject system_user = _service_context.getSecurityService().loginAsSystem();
				try {
					_service_context.getSecurityService().runAs(system_user, Arrays.asList(bucket.owner_id())); // (Switch to bucket owner user)
					if (!_service_context.getSecurityService().hasRole(system_user, "admin")) {
						errors.add(ErrorUtils.buildErrorMessage(bucket.full_name(), "validateSchema", SearchIndexErrorUtils.NON_ADMIN_BUCKET_NAME_OVERRIDE));
					}
				}
				finally {
					_service_context.getSecurityService().releaseRunAs(system_user);
				}
			}
			
			final String index_name = ElasticsearchIndexUtils.getBaseIndexName(bucket, Optional.empty());
			boolean error = false; // (Warning mutable code)
			final boolean is_verbose = is_verbose(schema);
			final ElasticsearchIndexServiceConfigBean schema_config = ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config, _mapper);
			
			// 1) Check the schema:
			
			try {				
				final Optional<String> type = Optional.ofNullable(schema_config.search_technology_override()).map(t -> t.type_name_or_prefix());
				final String index_type = CollidePolicy.new_type == Optional.ofNullable(schema_config.search_technology_override())
											.map(t -> t.collide_policy()).orElse(CollidePolicy.new_type)
												? "_default_"
												: type.orElse(ElasticsearchIndexServiceConfigBean.DEFAULT_FIXED_TYPE_NAME);
				
				final XContentBuilder mapping = ElasticsearchIndexUtils.createIndexMapping(bucket, Optional.empty(), true, schema_config, _mapper, index_type);
				if (is_verbose) {
					errors.add(ErrorUtils.buildSuccessMessage(bucket.full_name(), "validateSchema", mapping.bytes().toUtf8()));
				}
			}
			catch (Throwable e) {
				errors.add(ErrorUtils.buildErrorMessage(bucket.full_name(), "validateSchema", ErrorUtils.getLongForm("{0}", e)));
				error = true;
			}
			
			// 2) Sanity check the max size
	
			final Optional<Long> index_max_size = Optional.ofNullable(schema_config.search_technology_override().target_index_size_mb());
			if (index_max_size.isPresent()) {
				final long max = index_max_size.get();
				if ((max > 0) && (max < 25)) {
					errors.add(ErrorUtils.buildErrorMessage(bucket.full_name(), "validateSchema", SearchIndexErrorUtils.INVALID_MAX_INDEX_SIZE, max));
					error = true;
				}
				else if (is_verbose) {
					errors.add(ErrorUtils.buildSuccessMessage(bucket.full_name(), "validateSchema", "Max index size = {0} MB", max));				
				}
			}		
			return Tuples._2T(error ? "" : index_name, errors);
		}
		catch (Exception e) { // Very early error has occurred, just report that:
			return Tuples._2T("", Arrays.asList(ErrorUtils.buildErrorMessage(bucket.full_name(), "validateSchema", ErrorUtils.getLongForm("{0}", e))));
		}
	}
	
	/** Utility function - returns verboseness of schema being validated
	 * @param schema
	 * @return
	 */
	protected static boolean is_verbose(final SearchIndexSchemaBean schema) {
		return Optional.ofNullable(schema)
					.map(SearchIndexSchemaBean::technology_override_schema)
					.map(m -> m.get("verbose"))
					.filter(b -> b.toString().equalsIgnoreCase("true") || b.toString().equals("1"))
					.map(b -> true) // (if we're here then must be true/1)
				.orElse(false);
	}
	
	////////////////////////////////////////////////////////////////////////////////
	
	/** This service needs to load some additional classes via Guice. Here's the module that defines the bindings
	 * @return
	 */
	public static List<Module> getExtraDependencyModules() {
		return Arrays.asList((Module)new ElasticsearchIndexServiceModule());
	}
	
	public void youNeedToImplementTheStaticFunctionCalled_getExtraDependencyModules() {
		//(done!)
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingArtefacts()
	 */
	@Override
	public Collection<Object> getUnderlyingArtefacts() {
		return Arrays.asList(this, _crud_factory);
	}

}
