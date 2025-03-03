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
package org.apache.spark.sql.execution.benchmarks

import io.glutenproject.GlutenConfig
import io.glutenproject.backendsapi.BackendsApiManager
import io.glutenproject.execution.{FileSourceScanExecTransformer, WholestageTransformContext}
import io.glutenproject.expression.ConverterUtils
import io.glutenproject.substrait.SubstraitContext
import io.glutenproject.substrait.plan.PlanBuilder
import io.glutenproject.utils.UTSystemParameters
import io.glutenproject.vectorized.{BlockNativeConverter, CHNativeBlock, JniLibLoader}

import org.apache.spark.SparkConf
import org.apache.spark.benchmark.Benchmark
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{UnsafeProjection, UnsafeRow}
import org.apache.spark.sql.execution.FileSourceScanExec
import org.apache.spark.sql.execution.benchmark.SqlBasedBenchmark
import org.apache.spark.sql.execution.datasources.{FilePartition, FileScanRDD, PartitionedFile}
import org.apache.spark.sql.execution.datasources.v2.clickhouse.ClickHouseLog
import org.apache.spark.sql.vectorized.ColumnarBatch

import com.google.common.collect.Lists

import scala.collection.JavaConverters._

/**
 * Benchmark to measure Clickhouse parquet read performance. To run this benchmark:
 * {{{
 *   1. Run in IDEA: run this class directly;
 *   2. Run without IDEA: bin/spark-submit --class <this class>
 *        --jars <spark core test jar>,<spark catalyst test jar>,<spark sql test jar>
 *        --conf xxxx=xxx
 *        backends-clickhouse-XXX-tests.jar
 *        parameters
 *
 *   Parameters:
 *     1. parquet files dir;
 *     2. the count of the parquet file to read;
 *     3. the fields to read;
 *     4. the execution count;
 *     5. whether to run vanilla spark benchmarks;
 * }}}
 */
object CHParquetReadBenchmark extends SqlBasedBenchmark {

  protected lazy val thrdNum = "1"
  protected lazy val memorySize = "4G"
  protected lazy val offheapSize = "4G"

  def beforeAll(): Unit = {}

  override def getSparkSession: SparkSession = {
    beforeAll();
    val conf = new SparkConf()
      .setAppName("CHParquetReadBenchmark")
      .setIfMissing("spark.master", s"local[$thrdNum]")
      .set("spark.plugins", "io.glutenproject.GlutenPlugin")
      .set(
        "spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.execution.datasources.v2.clickhouse.ClickHouseSparkCatalog")
      .set("spark.memory.offHeap.enabled", "true")
      .setIfMissing("spark.memory.offHeap.size", offheapSize)
      .setIfMissing("spark.sql.columnVector.offheap.enabled", "true")
      .set("spark.databricks.delta.maxSnapshotLineageLength", "20")
      .set("spark.databricks.delta.snapshotPartitions", "1")
      .set("spark.databricks.delta.properties.defaults.checkpointInterval", "5")
      .set("spark.databricks.delta.stalenessLimit", "3600000")
      .set("spark.gluten.sql.columnar.columnartorow", "true")
      .set(GlutenConfig.GLUTEN_LOAD_NATIVE, "true")
      .setIfMissing(GlutenConfig.GLUTEN_LIB_PATH, UTSystemParameters.getClickHouseLibPath())
      .set("spark.gluten.sql.enable.native.validation", "false")
      .set("spark.gluten.sql.columnar.separate.scan.rdd.for.ch", "true")
      .set("spark.sql.adaptive.enabled", "false")
      .setIfMissing("spark.driver.memory", memorySize)
      .setIfMissing("spark.executor.memory", memorySize)
      .setIfMissing("spark.sql.files.maxPartitionBytes", "1G")
      .setIfMissing("spark.sql.files.openCostInBytes", "1073741824")

    SparkSession.builder.config(conf).getOrCreate()
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val (parquetDir, readFileCnt, scanSchema, executedCnt, executedVanilla) =
      if (mainArgs.isEmpty) {
        ("/data1/test_output/tpch-data-sf10/lineitem", 3, "l_orderkey,l_receiptdate", 5, true)
      } else {
        (mainArgs(0), mainArgs(1).toInt, mainArgs(2), mainArgs(3).toInt, mainArgs(4).toBoolean)
      }

    val chParquet = spark.sql(s"""
                                 |select $scanSchema from parquet.`$parquetDir`
                                 |
                                 |""".stripMargin)

    // Get the `FileSourceScanExecTransformer`
    val chScanPlan = chParquet.queryExecution.executedPlan.collect {
      case scan: FileSourceScanExecTransformer => scan
    }

    val chFileScan = chScanPlan.head
    val outputAttrs = chFileScan.outputAttributes()
    val filePartitions = chFileScan.getFlattenPartitions
      .take(readFileCnt)
      .map(_.asInstanceOf[FilePartition])

    val numOutputRows = chFileScan.longMetric("outputRows")
    val numOutputVectors = chFileScan.longMetric("outputVectors")
    val scanTime = chFileScan.longMetric("scanTime")
    // Generate Substrait plan
    val substraitContext = new SubstraitContext
    val transformContext = chFileScan.doTransform(substraitContext)
    val outNames = new java.util.ArrayList[String]()
    for (attr <- outputAttrs) {
      outNames.add(ConverterUtils.genColumnNameWithExprId(attr))
    }
    val planNode =
      PlanBuilder.makePlan(substraitContext, Lists.newArrayList(transformContext.root), outNames)
    val fileFormat = ConverterUtils.getFileFormat(chFileScan)

    val nativeFileScanRDD = BackendsApiManager.getIteratorApiInstance.genNativeFileScanRDD(
      spark.sparkContext,
      WholestageTransformContext(outputAttrs, outputAttrs, planNode, substraitContext),
      fileFormat,
      filePartitions,
      numOutputRows,
      numOutputVectors,
      scanTime
    )

    // Get the total row count
    val chRowCnt = nativeFileScanRDD
      .mapPartitionsInternal(batches => batches.map(batch => batch.numRows().toLong))
      .collect()
      .sum

    val parquetReadBenchmark =
      new Benchmark(
        s"Parquet Read $readFileCnt files, fields: $scanSchema, total $chRowCnt records",
        chRowCnt,
        output = output)

    parquetReadBenchmark.addCase(s"ClickHouse Parquet Read", executedCnt) {
      _ =>
        val resultRDD: RDD[Long] = nativeFileScanRDD.mapPartitionsInternal {
          batches =>
            batches.map {
              batch =>
                CHNativeBlock
                  .fromColumnarBatch(batch)
                  .ifPresent(
                    block => {
                      block.totalBytes()
                      block.close()
                    })
                batch.numRows().toLong
            }
        }
        resultRDD.collect()
    }

    parquetReadBenchmark.addCase(s"ClickHouse Parquet Read to Rows", executedCnt) {
      _ =>
        val resultRDD: RDD[Long] = nativeFileScanRDD.mapPartitionsInternal {
          batches =>
            val jniWrapper = new BlockNativeConverter()
            batches.map {
              batch =>
                CHNativeBlock
                  .fromColumnarBatch(batch)
                  .ifPresent(
                    block => {
                      val info = jniWrapper.convertColumnarToRow(block.blockAddress())
                      new Iterator[InternalRow] {
                        var rowId = 0
                        val row = new UnsafeRow(batch.numCols())
                        var closed = false

                        override def hasNext: Boolean = {
                          val result = rowId < batch.numRows()
                          if (!result && !closed) {
                            jniWrapper.freeMemory(info.memoryAddress, info.totalSize)
                            closed = true
                          }
                          return result
                        }

                        override def next: UnsafeRow = {
                          if (rowId >= batch.numRows()) throw new NoSuchElementException

                          val (offset, length) = (info.offsets(rowId), info.lengths(rowId))
                          row.pointTo(null, info.memoryAddress + offset, length.toInt)
                          rowId += 1
                          row
                        }
                      }.foreach(_.numFields)
                      block.close()
                    })
                batch.numRows().toLong
            }
        }
        resultRDD.collect()
    }

    if (executedVanilla) {
      spark.conf.set("spark.gluten.sql.enable.native.engine", "false")

      val vanillaParquet = spark.sql(s"""
                                        |select $scanSchema from parquet.`$parquetDir`
                                        |
                                        |""".stripMargin)

      val vanillaScanPlan = vanillaParquet.queryExecution.executedPlan.collect {
        case scan: FileSourceScanExec => scan
      }

      val fileScan = vanillaScanPlan.head
      val fileScanOutput = fileScan.output
      val relation = fileScan.relation
      val readFile: (PartitionedFile) => Iterator[InternalRow] =
        relation.fileFormat.buildReaderWithPartitionValues(
          sparkSession = relation.sparkSession,
          dataSchema = relation.dataSchema,
          partitionSchema = relation.partitionSchema,
          requiredSchema = fileScan.requiredSchema,
          filters = Seq.empty,
          options = relation.options,
          hadoopConf = relation.sparkSession.sessionState.newHadoopConfWithOptions(relation.options)
        )

      val newFileScanRDD = new FileScanRDD(spark, readFile, filePartitions)
        .asInstanceOf[RDD[ColumnarBatch]]

      val rowCnt = newFileScanRDD
        .mapPartitionsInternal(batches => batches.map(batch => batch.numRows().toLong))
        .collect()
        .sum
      assert(chRowCnt == rowCnt, "The row count of the benchmark is not equal.")

      parquetReadBenchmark.addCase(s"Vanilla Spark Parquet Read", executedCnt) {
        _ =>
          val resultRDD: RDD[Long] = newFileScanRDD.mapPartitionsInternal {
            batches => batches.map(_.numRows().toLong)
          }
          resultRDD.collect()
      }

      parquetReadBenchmark.addCase(s"Vanilla Spark Parquet Read to Rows", executedCnt) {
        _ =>
          val resultRDD: RDD[Long] = newFileScanRDD.mapPartitionsInternal {
            batches =>
              val toUnsafe = UnsafeProjection.create(fileScanOutput, fileScanOutput)
              batches.map {
                batch =>
                  // Convert to row and decode parquet value
                  batch.rowIterator().asScala.map(toUnsafe).foreach(_.numFields)
                  batch.numRows().toLong
              }
          }
          resultRDD.collect()
      }
    }

    parquetReadBenchmark.run()
  }

  override def afterAll(): Unit = {
    ClickHouseLog.clearCache()
    val libPath = spark.conf.get(
      GlutenConfig.GLUTEN_LIB_PATH,
      UTSystemParameters
        .getClickHouseLibPath())
    JniLibLoader.unloadFromPath(libPath)
    super.afterAll()
  }
}
