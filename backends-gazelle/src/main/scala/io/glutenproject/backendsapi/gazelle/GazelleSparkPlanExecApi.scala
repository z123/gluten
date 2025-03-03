/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.glutenproject.backendsapi.gazelle

import io.glutenproject.backendsapi.velox.VeloxSparkPlanExecApi

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.ArrowColumnarRules.ArrowWritePostRule
import org.apache.spark.sql.catalyst.rules.Rule

// FIXME Methods in this class never get called since no service file is registered for it,
// marked deprecated
@deprecated
class GazelleSparkPlanExecApi extends VeloxSparkPlanExecApi {

  override def genExtendedColumnarPostRules(): List[SparkSession => Rule[SparkPlan]] = {
    val arrowRule = (spark: SparkSession)
    => ArrowWritePostRule(spark)
    super.genExtendedColumnarPostRules():+ arrowRule
  }
}
