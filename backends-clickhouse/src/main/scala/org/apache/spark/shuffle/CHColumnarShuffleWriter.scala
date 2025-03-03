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
package org.apache.spark.shuffle

import io.glutenproject.GlutenConfig
import io.glutenproject.vectorized._

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.MapStatus
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.Utils

import java.io.IOException

class CHColumnarShuffleWriter[K, V](
    shuffleBlockResolver: IndexShuffleBlockResolver,
    handle: BaseShuffleHandle[K, V, V],
    mapId: Long,
    writeMetrics: ShuffleWriteMetricsReporter)
  extends ShuffleWriter[K, V]
  with Logging {

  private val dep = handle.dependency.asInstanceOf[ColumnarShuffleDependency[K, V, V]]

  private val conf = SparkEnv.get.conf

  private val blockManager = SparkEnv.get.blockManager
  private val localDirs = blockManager.diskBlockManager.localDirs.mkString(",")
  private val offheapSize = conf.getSizeAsBytes("spark.memory.offHeap.size", 0)
  private val executorNum = conf.getInt("spark.executor.cores", 1)
  private val offheapPerTask = offheapSize / executorNum;
  private val nativeBufferSize = GlutenConfig.getConf.shuffleSplitDefaultSize
  private val customizedCompressCodec =
    GlutenConfig.getConf.columnarShuffleUseCustomizedCompressionCodec
  private val defaultCompressionCodec = if (conf.getBoolean("spark.shuffle.compress", true)) {
    conf.get("spark.io.compression.codec", "lz4")
  } else {
    "uncompressed"
  }
  private val batchCompressThreshold =
    GlutenConfig.getConf.columnarShuffleBatchCompressThreshold;
  private val preferSpill = GlutenConfig.getConf.columnarShufflePreferSpill
  private val writeSchema = GlutenConfig.getConf.columnarShuffleWriteSchema
  private val jniWrapper = new CHShuffleSplitterJniWrapper
  // Are we in the process of stopping? Because map tasks can call stop() with success = true
  // and then call stop() with success = false if they get an exception, we want to make sure
  // we don't try deleting files, etc twice.
  private var stopping = false
  private var mapStatus: MapStatus = _
  private var nativeSplitter: Long = 0

  private var splitResult: SplitResult = _

  private var partitionLengths: Array[Long] = _

  private var rawPartitionLengths: Array[Long] = _

  private var firstRecordBatch: Boolean = true

  @throws[IOException]
  override def write(records: Iterator[Product2[K, V]]): Unit = {
    internalCHWrite(records)
  }

  def internalCHWrite(records: Iterator[Product2[K, V]]): Unit = {
    val splitterJniWrapper: CHShuffleSplitterJniWrapper =
      jniWrapper.asInstanceOf[CHShuffleSplitterJniWrapper]
    if (!records.hasNext) {
      partitionLengths = new Array[Long](dep.partitioner.numPartitions)
      shuffleBlockResolver.writeMetadataFileAndCommit(
        dep.shuffleId,
        mapId,
        partitionLengths,
        Array[Long](),
        null)
      mapStatus = MapStatus(blockManager.shuffleServerId, partitionLengths, mapId)
      return
    }
    val dataTmp = Utils.tempFileWith(shuffleBlockResolver.getDataFile(dep.shuffleId, mapId))
    if (nativeSplitter == 0) {
      nativeSplitter = splitterJniWrapper.make(
        dep.nativePartitioning,
        mapId,
        nativeBufferSize,
        defaultCompressionCodec,
        dataTmp.getAbsolutePath,
        localDirs)
    }
    while (records.hasNext) {
      val cb = records.next()._2.asInstanceOf[ColumnarBatch]
      if (cb.numRows == 0 || cb.numCols == 0) {
        logInfo(s"Skip ColumnarBatch of ${cb.numRows} rows, ${cb.numCols} cols")
      } else {
        val startTimeForPrepare = System.nanoTime()

        val startTime = System.nanoTime()
        firstRecordBatch = false
        dep.prepareTime.add(System.nanoTime() - startTimeForPrepare)
        val col = cb.column(0).asInstanceOf[CHColumnVector]
        val block = col.getBlockAddress
        splitterJniWrapper
          .split(nativeSplitter, cb.numRows, block)
        dep.splitTime.add(System.nanoTime() - startTime)
        dep.numInputRows.add(cb.numRows)
        writeMetrics.incRecordsWritten(cb.numRows)
      }
    }
    val startTime = System.nanoTime()
    splitResult = splitterJniWrapper.stop(nativeSplitter)

    dep.splitTime.add(System.nanoTime() - startTime)
    dep.spillTime.add(splitResult.getTotalSpillTime)
    dep.compressTime.add(splitResult.getTotalCompressTime)
    dep.computePidTime.add(splitResult.getTotalComputePidTime)
    dep.bytesSpilled.add(splitResult.getTotalBytesSpilled)
    dep.dataSize.add(splitResult.getTotalBytesWritten)
    writeMetrics.incBytesWritten(splitResult.getTotalBytesWritten)
    writeMetrics.incWriteTime(splitResult.getTotalWriteTime)

    partitionLengths = splitResult.getPartitionLengths
    rawPartitionLengths = splitResult.getRawPartitionLengths
    try {
      shuffleBlockResolver.writeMetadataFileAndCommit(
        dep.shuffleId,
        mapId,
        partitionLengths,
        Array[Long](),
        dataTmp)
    } finally {
      if (dataTmp.exists() && !dataTmp.delete()) {
        logError(s"Error while deleting temp file ${dataTmp.getAbsolutePath}")
      }
    }

    mapStatus = MapStatus(blockManager.shuffleServerId, partitionLengths, mapId)
  }

  override def stop(success: Boolean): Option[MapStatus] = {
    try {
      if (stopping) {
        None
      }
      stopping = true
      if (success) {
        Option(mapStatus)
      } else {
        None
      }
    } finally {
      if (nativeSplitter != 0) {
        closeCHSplitter()
        nativeSplitter = 0
      }
    }
  }

  def closeCHSplitter(): Unit = {
    jniWrapper.asInstanceOf[CHShuffleSplitterJniWrapper].close(nativeSplitter)
  }

  // VisibleForTesting
  def getPartitionLengths: Array[Long] = partitionLengths

}
