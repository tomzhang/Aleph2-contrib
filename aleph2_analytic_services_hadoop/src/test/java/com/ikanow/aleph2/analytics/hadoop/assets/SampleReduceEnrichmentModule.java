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
package com.ikanow.aleph2.analytics.hadoop.assets;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IBatchRecord;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.EnrichmentControlMetadataBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.Patterns;
import com.ikanow.aleph2.data_model.utils.SetOnce;
import com.ikanow.aleph2.data_model.utils.Tuples;

public class SampleReduceEnrichmentModule implements IEnrichmentBatchModule {
	protected static final Logger _logger = LogManager.getLogger();	

	public enum Stage { map, combine, reduce };
	
	protected SetOnce<Stage> _stage = new SetOnce<>();
	
	public static class ConfigBean {
		List<String> key_field_override;
	};
	
	protected final SetOnce<List<String>> _key_fields = new SetOnce<>();
	
	@Override
	public void onStageInitialize(IEnrichmentModuleContext context,
			DataBucketBean bucket, EnrichmentControlMetadataBean control,
			Tuple2<ProcessingStage, ProcessingStage> previous_next,
			Optional<List<String>> next_grouping_fields) {

		// Infer what the stage is from the grouping info
		
		// input -> ... -> chain (map) -> grouping -> chain (combine) -> grouping -> chain (reduce) -> ...
		// input -> ... -> chain (map) -> grouping -> chain (reduce) -> ...

		_stage.set(Patterns.match(previous_next).<Stage>andReturn()
					.when(t2 -> t2.equals(Tuples._2T(ProcessingStage.grouping, ProcessingStage.grouping)), 
							__ -> Stage.combine)
					.when(t2 -> ProcessingStage.grouping == t2._1(), // (grouping,*)
							__ -> Stage.reduce)
					.when(t2 -> ProcessingStage.grouping == t2._2(), // (*.grouping)
							__ -> Stage.map)
					.otherwiseAssert()
				)
				;
		
		_logger.info("STAGE = " + _stage);
		
		final ConfigBean config = BeanTemplateUtils.from(
				Optional.ofNullable(control.config()).orElse(Collections.emptyMap()), ConfigBean.class)
				.get()
				;
		
		if (Stage.map != _stage.get()) {
			next_grouping_fields
				.map(Optional::of)
				.orElseGet(() -> Optional.ofNullable(config.key_field_override))
				.ifPresent(kf -> _key_fields.set(kf))
				;
		}
	}

	@Override
	public void onObjectBatch(Stream<Tuple2<Long, IBatchRecord>> batch,
			Optional<Integer> batch_size, Optional<JsonNode> grouping_key) {
		
		// Just to make it simple 
		
		// 2 different cases:
		
		// 1) If I'm a combiner or a single-step reducer, then count the batchs
		//    and emit (key, count)
		// 2) If I'm the second stage of a combine-reduce then sum the counts
		
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onStageComplete(boolean is_original) {
		_logger.info("onStageComplete: " + is_original);
	}

}