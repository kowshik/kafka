/**
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

package kafka.log

import java.io.{File, IOException}
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.TimeUnit

import kafka.api.{ApiVersion, KAFKA_0_10_0_IV0}
import kafka.common.{LongRef, OffsetsOutOfOrderException, UnexpectedAppendOffsetException}
import kafka.log.AppendOrigin.RaftLeader
import kafka.message.{BrokerCompressionCodec, CompressionCodec, NoCompressionCodec}
import kafka.metrics.KafkaMetricsGroup
import kafka.server.checkpoints.LeaderEpochCheckpointFile
import kafka.server.epoch.LeaderEpochFileCache
import kafka.server.{BrokerTopicStats, FetchDataInfo, FetchHighWatermark, FetchIsolation, FetchLogEnd, FetchTxnCommitted, LogDirFailureChannel, LogOffsetMetadata, OffsetAndEpoch, PartitionMetadataFile}
import kafka.utils._
import org.apache.kafka.common.errors._
import org.apache.kafka.common.message.{DescribeProducersResponseData, FetchResponseData}
import org.apache.kafka.common.record.FileRecords.TimestampAndOffset
import org.apache.kafka.common.record._
import org.apache.kafka.common.requests.ListOffsetsRequest
import org.apache.kafka.common.requests.OffsetsForLeaderEpochResponse.UNDEFINED_EPOCH_OFFSET
import org.apache.kafka.common.requests.ProduceResponse.RecordError
import org.apache.kafka.common.utils.Time
import org.apache.kafka.common.{InvalidRecordException, KafkaException, TopicPartition, Uuid}

import scala.jdk.CollectionConverters._
import scala.collection.mutable.ListBuffer
import scala.collection.{Seq, mutable}

object LogAppendInfo {
  val UnknownLogAppendInfo = LogAppendInfo(None, -1, None, RecordBatch.NO_TIMESTAMP, -1L, RecordBatch.NO_TIMESTAMP, -1L,
    RecordConversionStats.EMPTY, NoCompressionCodec, NoCompressionCodec, -1, -1, offsetsMonotonic = false, -1L)

  def unknownLogAppendInfoWithLogStartOffset(logStartOffset: Long): LogAppendInfo =
    LogAppendInfo(None, -1, None, RecordBatch.NO_TIMESTAMP, -1L, RecordBatch.NO_TIMESTAMP, logStartOffset,
      RecordConversionStats.EMPTY, NoCompressionCodec, NoCompressionCodec, -1, -1,
      offsetsMonotonic = false, -1L)

  /**
   * In ProduceResponse V8+, we add two new fields record_errors and error_message (see KIP-467).
   * For any record failures with InvalidTimestamp or InvalidRecordException, we construct a LogAppendInfo object like the one
   * in unknownLogAppendInfoWithLogStartOffset, but with additiona fields recordErrors and errorMessage
   */
  def unknownLogAppendInfoWithAdditionalInfo(logStartOffset: Long, recordErrors: Seq[RecordError], errorMessage: String): LogAppendInfo =
    LogAppendInfo(None, -1, None, RecordBatch.NO_TIMESTAMP, -1L, RecordBatch.NO_TIMESTAMP, logStartOffset,
      RecordConversionStats.EMPTY, NoCompressionCodec, NoCompressionCodec, -1, -1,
      offsetsMonotonic = false, -1L, recordErrors, errorMessage)
}

sealed trait LeaderHwChange
object LeaderHwChange {
  case object Increased extends LeaderHwChange
  case object Same extends LeaderHwChange
  case object None extends LeaderHwChange
}

/**
 * Struct to hold various quantities we compute about each message set before appending to the local log
 *
 * @param firstOffset The first offset in the message set unless the message format is less than V2 and we are appending
 *                    to the follower. If the message is a duplicate message the segment base offset and relative position
 *                    in segment will be unknown.
 * @param lastOffset The last offset in the message set
 * @param lastLeaderEpoch The partition leader epoch corresponding to the last offset, if available.
 * @param maxTimestamp The maximum timestamp of the message set.
 * @param offsetOfMaxTimestamp The offset of the message with the maximum timestamp.
 * @param logAppendTime The log append time (if used) of the message set, otherwise Message.NoTimestamp
 * @param logStartOffset The start offset of the log at the time of this append.
 * @param recordConversionStats Statistics collected during record processing, `null` if `assignOffsets` is `false`
 * @param sourceCodec The source codec used in the message set (send by the producer)
 * @param targetCodec The target codec of the message set(after applying the broker compression configuration if any)
 * @param shallowCount The number of shallow messages
 * @param validBytes The number of valid bytes
 * @param offsetsMonotonic Are the offsets in this message set monotonically increasing
 * @param lastOffsetOfFirstBatch The last offset of the first batch
 * @param leaderHwChange Incremental if the high watermark needs to be increased after appending record.
 *                       Same if high watermark is not changed. None is the default value and it means append failed
 *
 */
case class LogAppendInfo(var firstOffset: Option[LogOffsetMetadata],
                         var lastOffset: Long,
                         var lastLeaderEpoch: Option[Int],
                         var maxTimestamp: Long,
                         var offsetOfMaxTimestamp: Long,
                         var logAppendTime: Long,
                         var logStartOffset: Long,
                         var recordConversionStats: RecordConversionStats,
                         sourceCodec: CompressionCodec,
                         targetCodec: CompressionCodec,
                         shallowCount: Int,
                         validBytes: Int,
                         offsetsMonotonic: Boolean,
                         lastOffsetOfFirstBatch: Long,
                         recordErrors: Seq[RecordError] = List(),
                         errorMessage: String = null,
                         leaderHwChange: LeaderHwChange = LeaderHwChange.None) {
  /**
   * Get the first offset if it exists, else get the last offset of the first batch
   * For magic versions 2 and newer, this method will return first offset. For magic versions
   * older than 2, we use the last offset of the first batch as an approximation of the first
   * offset to avoid decompressing the data.
   */
  def firstOrLastOffsetOfFirstBatch: Long = firstOffset.map(_.messageOffset).getOrElse(lastOffsetOfFirstBatch)

  /**
   * Get the (maximum) number of messages described by LogAppendInfo
   * @return Maximum possible number of messages described by LogAppendInfo
   */
  def numMessages: Long = {
    firstOffset match {
      case Some(firstOffsetVal) if (firstOffsetVal.messageOffset >= 0 && lastOffset >= 0) =>
        (lastOffset - firstOffsetVal.messageOffset + 1)
      case _ => 0
    }
  }
}

/**
 * Container class which represents a snapshot of the significant offsets for a partition. This allows fetching
 * of these offsets atomically without the possibility of a leader change affecting their consistency relative
 * to each other. See [[Log.fetchOffsetSnapshot()]].
 */
case class LogOffsetSnapshot(logStartOffset: Long,
                             logEndOffset: LogOffsetMetadata,
                             highWatermark: LogOffsetMetadata,
                             lastStableOffset: LogOffsetMetadata)

/**
 * Another container which is used for lower level reads using  [[kafka.cluster.Partition.readRecords()]].
 */
case class LogReadInfo(fetchedData: FetchDataInfo,
                       divergingEpoch: Option[FetchResponseData.EpochEndOffset],
                       highWatermark: Long,
                       logStartOffset: Long,
                       logEndOffset: Long,
                       lastStableOffset: Long)

/**
 * A class used to hold useful metadata about a completed transaction. This is used to build
 * the transaction index after appending to the local log.
 *
 * @param producerId The ID of the producer
 * @param firstOffset The first offset (inclusive) of the transaction
 * @param lastOffset The last offset (inclusive) of the transaction. This is always the offset of the
 *                   COMMIT/ABORT control record which indicates the transaction's completion.
 * @param isAborted Whether or not the transaction was aborted
 */
case class CompletedTxn(producerId: Long, firstOffset: Long, lastOffset: Long, isAborted: Boolean) {
  override def toString: String = {
    "CompletedTxn(" +
      s"producerId=$producerId, " +
      s"firstOffset=$firstOffset, " +
      s"lastOffset=$lastOffset, " +
      s"isAborted=$isAborted)"
  }
}

/**
 * A class used to hold params required to decide to rotate a local log segment or not.
 */
case class RollParams(maxSegmentMs: Long,
                      maxSegmentBytes: Int,
                      maxTimestampInMessages: Long,
                      maxOffsetInMessages: Long,
                      messagesSize: Int,
                      now: Long)

object RollParams {
  def apply(config: LogConfig, appendInfo: LogAppendInfo, messagesSize: Int, now: Long): RollParams = {
   new RollParams(config.maxSegmentMs,
     config.segmentSize,
     appendInfo.maxTimestamp,
     appendInfo.lastOffset,
     messagesSize, now)
  }
}

sealed trait LogStartOffsetIncrementReason
case object ClientRecordDeletion extends LogStartOffsetIncrementReason {
  override def toString: String = "client delete records request"
}
case object LeaderOffsetIncremented extends LogStartOffsetIncrementReason {
  override def toString: String = "leader offset increment"
}
case object SegmentDeletion extends LogStartOffsetIncrementReason {
  override def toString: String = "segment deletion"
}
case object SnapshotGenerated extends LogStartOffsetIncrementReason {
  override def toString: String = "snapshot generated"
}

/**
 * A log which presents a unified view of local and tiered log segments.
 *
 * The log consists of tiered and local segments with the tiered portion of the log being optional. There could be an
 * overlap between the tiered and local segments. The active segment is always guaranteed to be local. If tiered segments
 * are present, they always appear at the beginning of the log, followed by an optional region of overlap, followed by the local
 * segments including the active segment.
 *
 * NOTE: this class handles state and behavior specific to tiered segments as well as any behavior combining both tiered
 * and local segments. The state and behavior specific to local segments are handled by the encapsulated LocalLog instance.
 *
 * @param localLog The LocalLog instance
 * @param config The log configuration settings
 * @param logStartOffset Start offset of the unified log. This is the earliest offset allowed to be exposed to a kafka client.
 *                       LocalLog only maintains the local segments, and therefore the start offset could thus diverge from
 *                       the true start offset. The logStartOffset can be updated by :
 *                       - user's DeleteRecordsRequest
 *                       - broker's log retention
 *                       - broker's log truncation
 *                       The logStartOffset is used to decide the following:
 *                       - Log deletion. LogSegment whose nextOffset <= log's logStartOffset can be deleted.
 *                         It may trigger log rolling if the active segment is deleted.
 *                       - Earliest offset of the log in response to ListOffsetRequest. To avoid OffsetOutOfRange exception after
 *                         user seeks to earliest offset, we make sure that logStartOffset <= log's highWatermark.
 *                         Other activities such as log cleaning are not affected by logStartOffset.
 * @param scheduler The thread pool scheduler used for background actions
 * @param brokerTopicStats Container for Broker Topic Yammer Metrics
 * @param time The time instance used for checking the clock
 * @param maxProducerIdExpirationMs The maximum amount of time to wait before a producer id is considered expired
 * @param producerIdExpirationCheckIntervalMs How often to check for producer ids which need to be expired
 * @param topicPartition The topic partition associated with this log
 * @param producerStateManager The ProducerStateManager instance
 * @param logDirFailureChannel The LogDirFailureChannel instance to asynchronously handle Log dir failure
 * @param hadCleanShutdown boolean flag to indicate if the Log had a clean/graceful shutdown last time. true means
 *                         clean shutdown whereas false means a crash.
 * @param keepPartitionMetadataFile boolean flag to indicate whether the partition.metadata file should be kept in the
 *                                  log directory. A partition.metadata file is only created when the controller's
 *                                  inter-broker protocol version is at least 2.8. This file will persist the topic ID on
 *                                  the broker. If inter-broker protocol is downgraded below 2.8, a topic ID may be lost
 *                                  and a new ID generated upon re-upgrade. If the inter-broker protocol version is below
 *                                  2.8, partition.metadata will be deleted to avoid ID conflicts upon re-upgrade.
 */
@threadsafe
class Log(val localLog: LocalLog,
          @volatile var config: LogConfig,
          @volatile var logStartOffset: Long,
          scheduler: Scheduler,
          brokerTopicStats: BrokerTopicStats,
          val time: Time,
          val maxProducerIdExpirationMs: Int,
          val producerIdExpirationCheckIntervalMs: Int,
          val topicPartition: TopicPartition,
          val producerStateManager: ProducerStateManager,
          logDirFailureChannel: LogDirFailureChannel,
          private val hadCleanShutdown: Boolean = true,
          val keepPartitionMetadataFile: Boolean = true) extends Logging with KafkaMetricsGroup {

  this.logIdent = s"[Log partition=$topicPartition, dir=${localLog.dir.getParent}] "

  /* A lock that guards all modifications to the log */
  private val lock = new Object

  /* The earliest offset which is part of an incomplete transaction. This is used to compute the
   * last stable offset (LSO) in ReplicaManager. Note that it is possible that the "true" first unstable offset
   * gets removed from the log (through record or segment deletion). In this case, the first unstable offset
   * will point to the log start offset, which may actually be either part of a completed transaction or not
   * part of a transaction at all. However, since we only use the LSO for the purpose of restricting the
   * read_committed consumer to fetching decided data (i.e. committed, aborted, or non-transactional), this
   * temporary abuse seems justifiable and saves us from scanning the log after deletion to find the first offsets
   * of each ongoing transaction in order to compute a new first unstable offset. It is possible, however,
   * that this could result in disagreement between replicas depending on when they began replicating the log.
   * In the worst case, the LSO could be seen by a consumer to go backwards.
   */
  @volatile private var firstUnstableOffsetMetadata: Option[LogOffsetMetadata] = None

  /* Keep track of the current high watermark in order to ensure that segments containing offsets at or above it are
   * not eligible for deletion. This means that the active segment is only eligible for deletion if the high watermark
   * equals the log end offset (which may never happen for a partition under consistent load). This is needed to
   * prevent the log start offset (which is exposed in fetch responses) from getting ahead of the high watermark.
   */
  @volatile private var highWatermarkMetadata: LogOffsetMetadata = LogOffsetMetadata(logStartOffset)

  // Visible for testing
  @volatile var leaderEpochCache: Option[LeaderEpochFileCache] = None

  @volatile var partitionMetadataFile : PartitionMetadataFile = null

  @volatile var topicId : Uuid = Uuid.ZERO_UUID

  locally {
    initializeLeaderEpochCache()
    initializePartitionMetadata()

    loadSegments()

    leaderEpochCache.foreach(_.truncateFromEnd(localLog.logEndOffset))

    updateLogStartOffset(math.max(logStartOffset, localLog.firstSegmentBaseOffset.get))

    // The earliest leader epoch may not be flushed during a hard failure. Recover it here.
    leaderEpochCache.foreach(_.truncateFromStart(logStartOffset))

    // Any segment loading or recovery code must not use producerStateManager, so that we can build the full state here
    // from scratch.
    if (!producerStateManager.isEmpty)
      throw new IllegalStateException("Producer state must be empty during log initialization")

    // Reload all snapshots into the ProducerStateManager cache, the intermediate ProducerStateManager used
    // during log recovery may have deleted some files without the Log.producerStateManager instance witnessing the
    // deletion.
    producerStateManager.removeStraySnapshots(localLog.logSegments.map(_.baseOffset).toSeq)
    loadProducerState(logEndOffset, reloadFromCleanShutdown = hadCleanShutdown)

    // Delete partition metadata file if the version does not support topic IDs.
    // Recover topic ID if present and topic IDs are supported
    if (partitionMetadataFile.exists()) {
        if (!keepPartitionMetadataFile)
          partitionMetadataFile.delete()
        else
          topicId = partitionMetadataFile.read().topicId
    }
  }

  def dir: File = localLog.dir

  def name  = localLog.name

  def parentDir: String = localLog.parentDir

  def parentDirFile: File = localLog.parentDirFile

  def recoveryPoint: Long = localLog.recoveryPoint

  def updateConfig(newConfig: LogConfig): Unit = {
    val oldConfig = this.config
    localLog.updateConfig(newConfig)
    this.config = newConfig
    val oldRecordVersion = oldConfig.messageFormatVersion.recordVersion
    val newRecordVersion = newConfig.messageFormatVersion.recordVersion
    if (newRecordVersion.value != oldRecordVersion.value)
      initializeLeaderEpochCache()
  }

  def highWatermark: Long = highWatermarkMetadata.messageOffset

  /**
   * Update the high watermark to a new offset. The new high watermark will be lower
   * bounded by the log start offset and upper bounded by the log end offset.
   *
   * This is intended to be called when initializing the high watermark or when updating
   * it on a follower after receiving a Fetch response from the leader.
   *
   * @param hw the suggested new value for the high watermark
   * @return the updated high watermark offset
   */
  def updateHighWatermark(hw: Long): Long = {
    updateHighWatermark(LogOffsetMetadata(hw))
  }

  /**
   * Update high watermark with offset metadata. The new high watermark will be lower
   * bounded by the log start offset and upper bounded by the log end offset.
   *
   * @param highWatermarkMetadata the suggested high watermark with offset metadata
   * @return the updated high watermark offset
   */
  def updateHighWatermark(highWatermarkMetadata: LogOffsetMetadata): Long = {
    val endOffsetMetadata = localLog.logEndOffsetMetadata
    val newHighWatermarkMetadata = if (highWatermarkMetadata.messageOffset < logStartOffset) {
      LogOffsetMetadata(logStartOffset)
    } else if (highWatermarkMetadata.messageOffset >= endOffsetMetadata.messageOffset) {
      endOffsetMetadata
    } else {
      highWatermarkMetadata
    }

    updateHighWatermarkMetadata(newHighWatermarkMetadata)
    newHighWatermarkMetadata.messageOffset
  }

  /**
   * Update the high watermark to a new value if and only if it is larger than the old value. It is
   * an error to update to a value which is larger than the log end offset.
   *
   * This method is intended to be used by the leader to update the high watermark after follower
   * fetch offsets have been updated.
   *
   * @return the old high watermark, if updated by the new value
   */
  def maybeIncrementHighWatermark(newHighWatermark: LogOffsetMetadata): Option[LogOffsetMetadata] = {
    if (newHighWatermark.messageOffset > logEndOffset)
      throw new IllegalArgumentException(s"High watermark $newHighWatermark update exceeds current " +
        s"log end offset ${localLog.logEndOffsetMetadata}")

    lock.synchronized {
      val oldHighWatermark = fetchHighWatermarkMetadata

      // Ensure that the high watermark increases monotonically. We also update the high watermark when the new
      // offset metadata is on a newer segment, which occurs whenever the log is rolled to a new segment.
      if (oldHighWatermark.messageOffset < newHighWatermark.messageOffset ||
        (oldHighWatermark.messageOffset == newHighWatermark.messageOffset && oldHighWatermark.onOlderSegment(newHighWatermark))) {
        updateHighWatermarkMetadata(newHighWatermark)
        Some(oldHighWatermark)
      } else {
        None
      }
    }
  }

  /**
   * Get the offset and metadata for the current high watermark. If offset metadata is not
   * known, this will do a lookup in the index and cache the result.
   */
  private def fetchHighWatermarkMetadata: LogOffsetMetadata = {
    localLog.checkIfMemoryMappedBufferClosed()

    val offsetMetadata = highWatermarkMetadata
    if (offsetMetadata.messageOffsetOnly) {
      lock.synchronized {
        val fullOffset = convertToOffsetMetadataOrThrow(highWatermark)
        updateHighWatermarkMetadata(fullOffset)
        fullOffset
      }
    } else {
      offsetMetadata
    }
  }

  private def updateHighWatermarkMetadata(newHighWatermark: LogOffsetMetadata): Unit = {
    if (newHighWatermark.messageOffset < 0)
      throw new IllegalArgumentException("High watermark offset should be non-negative")

    lock synchronized {
      if (newHighWatermark.messageOffset < highWatermarkMetadata.messageOffset) {
        warn(s"Non-monotonic update of high watermark from $highWatermarkMetadata to $newHighWatermark")
      }

      highWatermarkMetadata = newHighWatermark
      producerStateManager.onHighWatermarkUpdated(newHighWatermark.messageOffset)
      maybeIncrementFirstUnstableOffset()
    }
    trace(s"Setting high watermark $newHighWatermark")
  }

  /**
   * Get the first unstable offset. Unlike the last stable offset, which is always defined,
   * the first unstable offset only exists if there are transactions in progress.
   *
   * @return the first unstable offset, if it exists
   */
  private[log] def firstUnstableOffset: Option[Long] = firstUnstableOffsetMetadata.map(_.messageOffset)

  private def fetchLastStableOffsetMetadata: LogOffsetMetadata = {
    localLog.checkIfMemoryMappedBufferClosed()

    // cache the current high watermark to avoid a concurrent update invalidating the range check
    val highWatermarkMetadata = fetchHighWatermarkMetadata

    firstUnstableOffsetMetadata match {
      case Some(offsetMetadata) if offsetMetadata.messageOffset < highWatermarkMetadata.messageOffset =>
        if (offsetMetadata.messageOffsetOnly) {
          lock synchronized {
            val fullOffset = convertToOffsetMetadataOrThrow(offsetMetadata.messageOffset)
            if (firstUnstableOffsetMetadata.contains(offsetMetadata))
              firstUnstableOffsetMetadata = Some(fullOffset)
            fullOffset
          }
        } else {
          offsetMetadata
        }
      case _ => highWatermarkMetadata
    }
  }

  /**
   * The last stable offset (LSO) is defined as the first offset such that all lower offsets have been "decided."
   * Non-transactional messages are considered decided immediately, but transactional messages are only decided when
   * the corresponding COMMIT or ABORT marker is written. This implies that the last stable offset will be equal
   * to the high watermark if there are no transactional messages in the log. Note also that the LSO cannot advance
   * beyond the high watermark.
   */
  def lastStableOffset: Long = {
    firstUnstableOffsetMetadata match {
      case Some(offsetMetadata) if offsetMetadata.messageOffset < highWatermark => offsetMetadata.messageOffset
      case _ => highWatermark
    }
  }

  def lastStableOffsetLag: Long = highWatermark - lastStableOffset

  /**
    * Fully materialize and return an offset snapshot including segment position info. This method will update
    * the LogOffsetMetadata for the high watermark and last stable offset if they are message-only. Throws an
    * offset out of range error if the segment info cannot be loaded.
    */
  def fetchOffsetSnapshot: LogOffsetSnapshot = {
    val lastStable = fetchLastStableOffsetMetadata
    val highWatermark = fetchHighWatermarkMetadata

    LogOffsetSnapshot(
      logStartOffset,
      localLog.logEndOffsetMetadata,
      highWatermark,
      lastStable
    )
  }

  private val tags = {
    val maybeFutureTag = if (isFuture) Map("is-future" -> "true") else Map.empty[String, String]
    Map("topic" -> topicPartition.topic, "partition" -> topicPartition.partition.toString) ++ maybeFutureTag
  }

  newGauge(LogMetricNames.NumLogSegments, () => numberOfSegments, tags)
  newGauge(LogMetricNames.LogStartOffset, () => logStartOffset, tags)
  newGauge(LogMetricNames.LogEndOffset, () => logEndOffset, tags)
  newGauge(LogMetricNames.Size, () => size, tags)

  val producerExpireCheck = scheduler.schedule(name = "PeriodicProducerExpirationCheck", fun = () => {
    lock synchronized {
      producerStateManager.removeExpiredProducers(time.milliseconds)
    }
  }, period = producerIdExpirationCheckIntervalMs, delay = producerIdExpirationCheckIntervalMs, unit = TimeUnit.MILLISECONDS)

  private def recordVersion: RecordVersion = config.messageFormatVersion.recordVersion

  private def initializePartitionMetadata(): Unit = lock synchronized {
    val partitionMetadata = PartitionMetadataFile.newFile(dir)
    partitionMetadataFile = new PartitionMetadataFile(partitionMetadata, logDirFailureChannel)
  }

  private def initializeLeaderEpochCache(): Unit = lock synchronized {
    val leaderEpochFile = LeaderEpochCheckpointFile.newFile(dir)

    def newLeaderEpochFileCache(): LeaderEpochFileCache = {
      val checkpointFile = new LeaderEpochCheckpointFile(leaderEpochFile, logDirFailureChannel)
      new LeaderEpochFileCache(topicPartition, () => logEndOffset, checkpointFile)
    }

    if (recordVersion.precedes(RecordVersion.V2)) {
      val currentCache = if (leaderEpochFile.exists())
        Some(newLeaderEpochFileCache())
      else
        None

      if (currentCache.exists(_.nonEmpty))
        warn(s"Deleting non-empty leader epoch cache due to incompatible message format $recordVersion")

      Files.deleteIfExists(leaderEpochFile.toPath)
      leaderEpochCache = None
    } else {
      leaderEpochCache = Some(newLeaderEpochFileCache())
    }
  }

  private def updateHighWatermarkWithLogEndOffset(): Unit = {
    // Update the high watermark in case it has gotten ahead of the log end offset following a truncation
    // or if a new segment has been rolled and the offset metadata needs to be updated.
    if (highWatermark >= localLog.logEndOffset) {
      updateHighWatermarkMetadata(localLog.logEndOffsetMetadata)
    }
  }

  private def updateLogEndOffset(offset: Long): Unit = {
    localLog.updateLogEndOffset(offset)
    updateHighWatermarkWithLogEndOffset()
  }

  private def updateLogStartOffset(offset: Long): Unit = {
    logStartOffset = offset

    if (highWatermark < offset) {
      updateHighWatermark(offset)
    }

    if (localLog.recoveryPoint < offset) {
      localLog.updateRecoveryPoint(offset)
    }
  }

  private def loadProducerState(lastOffset: Long, reloadFromCleanShutdown: Boolean): Unit = lock synchronized {
    localLog.rebuildProducerState(logStartOffset, lastOffset, reloadFromCleanShutdown, producerStateManager)
    maybeIncrementFirstUnstableOffset()
  }

  def activeProducers: Seq[DescribeProducersResponseData.ProducerState] = {
    lock synchronized {
      producerStateManager.activeProducers.map { case (producerId, state) =>
        new DescribeProducersResponseData.ProducerState()
          .setProducerId(producerId)
          .setProducerEpoch(state.producerEpoch)
          .setLastSequence(state.lastSeq)
          .setLastTimestamp(state.lastTimestamp)
          .setCoordinatorEpoch(state.coordinatorEpoch)
          .setCurrentTxnStartOffset(state.currentTxnFirstOffset.getOrElse(-1L))
      }
    }.toSeq
  }

  private[log] def activeProducersWithLastSequence: Map[Long, Int] = lock synchronized {
    producerStateManager.activeProducers.map { case (producerId, producerIdEntry) =>
      (producerId, producerIdEntry.lastSeq)
    }
  }

  private[log] def lastRecordsOfActiveProducers: Map[Long, LastRecord] = lock synchronized {
    producerStateManager.activeProducers.map { case (producerId, producerIdEntry) =>
      val lastDataOffset = if (producerIdEntry.lastDataOffset >= 0 ) Some(producerIdEntry.lastDataOffset) else None
      val lastRecord = LastRecord(lastDataOffset, producerIdEntry.producerEpoch)
      producerId -> lastRecord
    }
  }

  /**
   * The number of segments in the local log.
   * Take care! this is an O(n) operation.
   */
  def numberOfSegments: Int = localLog.numberOfSegments

  /**
   * Close this log.
   * The memory mapped buffer for index files of this log will be left open until the log is deleted.
   */
  def close(): Unit = {
    debug("Closing log")
    lock synchronized {
      localLog.checkIfMemoryMappedBufferClosed()
      producerExpireCheck.cancel(true)
      maybeHandleIOException(s"Error while renaming dir for $topicPartition in dir ${dir.getParent}") {
        // We take a snapshot at the last written offset to hopefully avoid the need to scan the log
        // after restarting and to ensure that we cannot inadvertently hit the upgrade optimization
        // (the clean shutdown file is written after the logs are all closed).
        producerStateManager.takeSnapshot()
      }
      localLog.close()
    }
  }

  /**
   * Rename the directory of the local log
   *
   * @throws KafkaStorageException if rename fails
   */
  def renameDir(name: String): Unit = {
    lock synchronized {
      maybeHandleIOException(s"Error while renaming dir for $topicPartition in log dir ${dir.getParent}") {
        if (localLog.renameDir(name)) {
          producerStateManager.updateParentDir(dir)
          // re-initialize leader epoch cache so that LeaderEpochCheckpointFile.checkpoint can correctly reference
          // the checkpoint file in renamed log directory
          initializeLeaderEpochCache()
          initializePartitionMetadata()
        }
      }
    }
  }

  /**
   * Close file handlers used by this log but don't write to disk. This is called if the log directory is offline.
   */
  def closeHandlers(): Unit = {
    debug("Closing handlers")
    lock synchronized {
      localLog.closeHandlers()
    }
  }

  /**
   * Append this message set to the active segment of the log, assigning offsets and Partition Leader Epochs
   *
   * @param records The records to append
   * @param origin Declares the origin of the append which affects required validations
   * @param interBrokerProtocolVersion Inter-broker message protocol version
   * @throws KafkaStorageException If the append fails due to an I/O error.
   * @return Information about the appended messages including the first and last offset.
   */
  def appendAsLeader(records: MemoryRecords,
                     leaderEpoch: Int,
                     origin: AppendOrigin = AppendOrigin.Client,
                     interBrokerProtocolVersion: ApiVersion = ApiVersion.latestVersion): LogAppendInfo = {
    val validateAndAssignOffsets = origin != AppendOrigin.RaftLeader
    append(records, origin, interBrokerProtocolVersion, validateAndAssignOffsets, leaderEpoch, ignoreRecordSize = false)
  }

  /**
   * Append this message set to the active segment of the local log without assigning offsets or Partition Leader Epochs
   *
   * @param records The records to append
   * @throws KafkaStorageException If the append fails due to an I/O error.
   * @return Information about the appended messages including the first and last offset.
   */
  def appendAsFollower(records: MemoryRecords): LogAppendInfo = {
    append(records,
      origin = AppendOrigin.Replication,
      interBrokerProtocolVersion = ApiVersion.latestVersion,
      validateAndAssignOffsets = false,
      leaderEpoch = -1,
      // disable to check the validation of record size since the record is already accepted by leader.
      ignoreRecordSize = true)
  }

  /**
   * Append this message set to the active segment of the local log, rolling over to a fresh segment if necessary.
   *
   * This method will generally be responsible for assigning offsets to the messages,
   * however if the assignOffsets=false flag is passed we will only check that the existing offsets are valid.
   *
   * @param records The log records to append
   * @param origin Declares the origin of the append which affects required validations
   * @param interBrokerProtocolVersion Inter-broker message protocol version
   * @param validateAndAssignOffsets Should the log assign offsets to this message set or blindly apply what it is given
   * @param leaderEpoch The partition's leader epoch which will be applied to messages when offsets are assigned on the leader
   * @param ignoreRecordSize true to skip validation of record size.
   * @throws KafkaStorageException If the append fails due to an I/O error.
   * @throws OffsetsOutOfOrderException If out of order offsets found in 'records'
   * @throws UnexpectedAppendOffsetException If the first or last offset in append is less than next offset
   * @return Information about the appended messages including the first and last offset.
   */
  private def append(records: MemoryRecords,
                     origin: AppendOrigin,
                     interBrokerProtocolVersion: ApiVersion,
                     validateAndAssignOffsets: Boolean,
                     leaderEpoch: Int,
                     ignoreRecordSize: Boolean): LogAppendInfo = {

    val appendInfo = analyzeAndValidateRecords(records, origin, ignoreRecordSize, leaderEpoch)

    // return if we have no valid messages or if this is a duplicate of the last appended entry
    if (appendInfo.shallowCount == 0) appendInfo
    else {

      // trim any invalid bytes or partial messages before appending it to the on-disk log
      var validRecords = trimInvalidBytes(records, appendInfo)

      // they are valid, insert them in the log
      lock synchronized {
        maybeHandleIOException(s"Error while appending records to $topicPartition in dir ${dir.getParent}") {
          localLog.checkIfMemoryMappedBufferClosed()
          if (validateAndAssignOffsets) {
            // assign offsets to the message set
            val offset = new LongRef(localLog.logEndOffset)
            appendInfo.firstOffset = Some(LogOffsetMetadata(offset.value))
            val now = time.milliseconds
            val validateAndOffsetAssignResult = try {
              LogValidator.validateMessagesAndAssignOffsets(validRecords,
                topicPartition,
                offset,
                time,
                now,
                appendInfo.sourceCodec,
                appendInfo.targetCodec,
                config.compact,
                config.messageFormatVersion.recordVersion.value,
                config.messageTimestampType,
                config.messageTimestampDifferenceMaxMs,
                leaderEpoch,
                origin,
                interBrokerProtocolVersion,
                brokerTopicStats)
            } catch {
              case e: IOException =>
                throw new KafkaException(s"Error validating messages while appending to log $name", e)
            }
            validRecords = validateAndOffsetAssignResult.validatedRecords
            appendInfo.maxTimestamp = validateAndOffsetAssignResult.maxTimestamp
            appendInfo.offsetOfMaxTimestamp = validateAndOffsetAssignResult.shallowOffsetOfMaxTimestamp
            appendInfo.lastOffset = offset.value - 1
            appendInfo.recordConversionStats = validateAndOffsetAssignResult.recordConversionStats
            if (config.messageTimestampType == TimestampType.LOG_APPEND_TIME)
              appendInfo.logAppendTime = now

            // re-validate message sizes if there's a possibility that they have changed (due to re-compression or message
            // format conversion)
            if (!ignoreRecordSize && validateAndOffsetAssignResult.messageSizeMaybeChanged) {
              validRecords.batches.forEach { batch =>
                if (batch.sizeInBytes > config.maxMessageSize) {
                  // we record the original message set size instead of the trimmed size
                  // to be consistent with pre-compression bytesRejectedRate recording
                  brokerTopicStats.topicStats(topicPartition.topic).bytesRejectedRate.mark(records.sizeInBytes)
                  brokerTopicStats.allTopicsStats.bytesRejectedRate.mark(records.sizeInBytes)
                  throw new RecordTooLargeException(s"Message batch size is ${batch.sizeInBytes} bytes in append to" +
                    s"partition $topicPartition which exceeds the maximum configured size of ${config.maxMessageSize}.")
                }
              }
            }
          } else {
            // we are taking the offsets we are given
            if (!appendInfo.offsetsMonotonic)
              throw new OffsetsOutOfOrderException(s"Out of order offsets found in append to $topicPartition: " +
                records.records.asScala.map(_.offset))

            if (appendInfo.firstOrLastOffsetOfFirstBatch < localLog.logEndOffset) {
              // we may still be able to recover if the log is empty
              // one example: fetching from log start offset on the leader which is not batch aligned,
              // which may happen as a result of AdminClient#deleteRecords()
              val firstOffset = appendInfo.firstOffset match {
                case Some(offsetMetadata) => offsetMetadata.messageOffset
                case None => records.batches.asScala.head.baseOffset()
              }

              val firstOrLast = if (appendInfo.firstOffset.isDefined) "First offset" else "Last offset of the first batch"
              throw new UnexpectedAppendOffsetException(
                s"Unexpected offset in append to $topicPartition. $firstOrLast " +
                  s"${appendInfo.firstOrLastOffsetOfFirstBatch} is less than the next offset ${localLog.logEndOffset}. " +
                  s"First 10 offsets in append: ${records.records.asScala.take(10).map(_.offset)}, last offset in" +
                  s" append: ${appendInfo.lastOffset}. Log start offset = $logStartOffset",
                firstOffset, appendInfo.lastOffset)
            }
          }

          // update the epoch cache with the epoch stamped onto the message by the leader
          validRecords.batches.forEach { batch =>
            if (batch.magic >= RecordBatch.MAGIC_VALUE_V2) {
              maybeAssignEpochStartOffset(batch.partitionLeaderEpoch, batch.baseOffset)
            } else {
              // In partial upgrade scenarios, we may get a temporary regression to the message format. In
              // order to ensure the safety of leader election, we clear the epoch cache so that we revert
              // to truncation by high watermark after the next leader election.
              leaderEpochCache.filter(_.nonEmpty).foreach { cache =>
                warn(s"Clearing leader epoch cache after unexpected append with message format v${batch.magic}")
                cache.clearAndFlush()
              }
            }
          }

          // check messages set size may be exceed config.segmentSize
          if (validRecords.sizeInBytes > config.segmentSize) {
            throw new RecordBatchTooLargeException(s"Message batch size is ${validRecords.sizeInBytes} bytes in append " +
              s"to partition $topicPartition, which exceeds the maximum configured segment size of ${config.segmentSize}.")
          }

          // maybe roll the log if this segment is full
          val segment = maybeRoll(validRecords.sizeInBytes, appendInfo)

          val logOffsetMetadata = LogOffsetMetadata(
            messageOffset = appendInfo.firstOrLastOffsetOfFirstBatch,
            segmentBaseOffset = segment.baseOffset,
            relativePositionInSegment = segment.size)

          // now that we have valid records, offsets assigned, and timestamps updated, we need to
          // validate the idempotent/transactional state of the producers and collect some metadata
          val (updatedProducers, completedTxns, maybeDuplicate) = analyzeAndValidateProducerState(
            logOffsetMetadata, validRecords, origin)

          maybeDuplicate match {
            case Some(duplicate) =>
              appendInfo.firstOffset = Some(LogOffsetMetadata(duplicate.firstOffset))
              appendInfo.lastOffset = duplicate.lastOffset
              appendInfo.logAppendTime = duplicate.timestamp
              appendInfo.logStartOffset = logStartOffset
            case None =>
              // Before appending update the first offset metadata to include segment information
              appendInfo.firstOffset = appendInfo.firstOffset.map { offsetMetadata =>
                offsetMetadata.copy(segmentBaseOffset = segment.baseOffset, relativePositionInSegment = segment.size)
              }

              segment.append(largestOffset = appendInfo.lastOffset,
                largestTimestamp = appendInfo.maxTimestamp,
                shallowOffsetOfMaxTimestamp = appendInfo.offsetOfMaxTimestamp,
                records = validRecords)

              // Increment the log end offset. We do this immediately after the append because a
              // write to the transaction index below may fail and we want to ensure that the offsets
              // of future appends still grow monotonically. The resulting transaction index inconsistency
              // will be cleaned up after the log directory is recovered. Note that the end offset of the
              // ProducerStateManager will not be updated and the last stable offset will not advance
              // if the append to the transaction index fails.
              updateLogEndOffset(appendInfo.lastOffset + 1)

              // update the producer state
              updatedProducers.values.foreach(producerAppendInfo => producerStateManager.update(producerAppendInfo))

              // update the transaction index with the true last stable offset. The last offset visible
              // to consumers using READ_COMMITTED will be limited by this value and the high watermark.
              completedTxns.foreach { completedTxn =>
                val lastStableOffset = producerStateManager.lastStableOffset(completedTxn)
                segment.updateTxnIndex(completedTxn, lastStableOffset)
                producerStateManager.completeTxn(completedTxn)
              }

              // always update the last producer id map offset so that the snapshot reflects the current offset
              // even if there isn't any idempotent data being written
              producerStateManager.updateMapEndOffset(appendInfo.lastOffset + 1)

              // update the first unstable offset (which is used to compute LSO)
              maybeIncrementFirstUnstableOffset()

              trace(s"Appended message set with last offset: ${appendInfo.lastOffset}, " +
                s"first offset: ${appendInfo.firstOffset}, " +
                s"next offset: ${localLog.logEndOffset}, " +
                s"and messages: $validRecords")

              if (unflushedMessages >= config.flushInterval) flush()
          }
          appendInfo
        }
      }
    }
  }

  def maybeAssignEpochStartOffset(leaderEpoch: Int, startOffset: Long): Unit = {
    leaderEpochCache.foreach { cache =>
      cache.assign(leaderEpoch, startOffset)
    }
  }

  def latestEpoch: Option[Int] = leaderEpochCache.flatMap(_.latestEpoch)

  def endOffsetForEpoch(leaderEpoch: Int): Option[OffsetAndEpoch] = {
    leaderEpochCache.flatMap { cache =>
      val (foundEpoch, foundOffset) = cache.endOffsetFor(leaderEpoch)
      if (foundOffset == UNDEFINED_EPOCH_OFFSET)
        None
      else
        Some(OffsetAndEpoch(foundOffset, foundEpoch))
    }
  }

  private def maybeIncrementFirstUnstableOffset(): Unit = lock synchronized {
    localLog.checkIfMemoryMappedBufferClosed()

    val updatedFirstStableOffset = producerStateManager.firstUnstableOffset match {
      case Some(logOffsetMetadata) if logOffsetMetadata.messageOffsetOnly || logOffsetMetadata.messageOffset < logStartOffset =>
        val offset = math.max(logOffsetMetadata.messageOffset, logStartOffset)
        Some(convertToOffsetMetadataOrThrow(offset))
      case other => other
    }

    if (updatedFirstStableOffset != this.firstUnstableOffsetMetadata) {
      debug(s"First unstable offset updated to $updatedFirstStableOffset")
      this.firstUnstableOffsetMetadata = updatedFirstStableOffset
    }
  }

  /**
   * Increment the log start offset if the provided offset is larger.
   *
   * If the log start offset changed, then this method also update a few key offset such that
   * `logStartOffset <= logStableOffset <= highWatermark`. The leader epoch cache is also updated
   * such that all of offsets referenced in that component point to valid offset in this log.
   *
   * @throws OffsetOutOfRangeException if the log start offset is greater than the high watermark
   * @return true if the log start offset was updated; otherwise false
   */
  def maybeIncrementLogStartOffset(newLogStartOffset: Long, reason: LogStartOffsetIncrementReason): Boolean = {
    // We don't have to write the log start offset to log-start-offset-checkpoint immediately.
    // The deleteRecordsOffset may be lost only if all in-sync replicas of this broker are shutdown
    // in an unclean manner within log.flush.start.offset.checkpoint.interval.ms. The chance of this happening is low.
    var updatedLogStartOffset = false
    maybeHandleIOException(s"Exception while increasing log start offset for $topicPartition to $newLogStartOffset in dir ${dir.getParent}") {
      lock synchronized {
        if (newLogStartOffset > highWatermark)
          throw new OffsetOutOfRangeException(s"Cannot increment the log start offset to $newLogStartOffset of partition $topicPartition " +
            s"since it is larger than the high watermark $highWatermark")

        localLog.checkIfMemoryMappedBufferClosed()
        if (newLogStartOffset > logStartOffset) {
          updatedLogStartOffset = true
          updateLogStartOffset(newLogStartOffset)
          info(s"Incremented log start offset to $newLogStartOffset due to $reason")
          leaderEpochCache.foreach(_.truncateFromStart(logStartOffset))
          producerStateManager.onLogStartOffsetIncremented(newLogStartOffset)
          maybeIncrementFirstUnstableOffset()
        }
      }
    }

    updatedLogStartOffset
  }

  private def analyzeAndValidateProducerState(appendOffsetMetadata: LogOffsetMetadata,
                                              records: MemoryRecords,
                                              origin: AppendOrigin):
  (mutable.Map[Long, ProducerAppendInfo], List[CompletedTxn], Option[BatchMetadata]) = {
    val updatedProducers = mutable.Map.empty[Long, ProducerAppendInfo]
    val completedTxns = ListBuffer.empty[CompletedTxn]
    var relativePositionInSegment = appendOffsetMetadata.relativePositionInSegment

    records.batches.forEach { batch =>
      if (batch.hasProducerId) {
        // if this is a client produce request, there will be up to 5 batches which could have been duplicated.
        // If we find a duplicate, we return the metadata of the appended batch to the client.
        if (origin == AppendOrigin.Client) {
          val maybeLastEntry = producerStateManager.lastEntry(batch.producerId)

          maybeLastEntry.flatMap(_.findDuplicateBatch(batch)).foreach { duplicate =>
            return (updatedProducers, completedTxns.toList, Some(duplicate))
          }
        }

        // We cache offset metadata for the start of each transaction. This allows us to
        // compute the last stable offset without relying on additional index lookups.
        val firstOffsetMetadata = if (batch.isTransactional)
          Some(LogOffsetMetadata(batch.baseOffset, appendOffsetMetadata.segmentBaseOffset, relativePositionInSegment))
        else
          None

        val maybeCompletedTxn = LocalLog.updateProducers(producerStateManager, batch, updatedProducers, firstOffsetMetadata, origin)
        maybeCompletedTxn.foreach(completedTxns += _)
      }

      relativePositionInSegment += batch.sizeInBytes
    }
    (updatedProducers, completedTxns.toList, None)
  }

  /**
   * Validate the following:
   * <ol>
   * <li> each message matches its CRC
   * <li> each message size is valid (if ignoreRecordSize is false)
   * <li> that the sequence numbers of the incoming record batches are consistent with the existing state and with each other.
   * </ol>
   *
   * Also compute the following quantities:
   * <ol>
   * <li> First offset in the message set
   * <li> Last offset in the message set
   * <li> Number of messages
   * <li> Number of valid bytes
   * <li> Whether the offsets are monotonically increasing
   * <li> Whether any compression codec is used (if many are used, then the last one is given)
   * </ol>
   */
  private def analyzeAndValidateRecords(records: MemoryRecords,
                                        origin: AppendOrigin,
                                        ignoreRecordSize: Boolean,
                                        leaderEpoch: Int): LogAppendInfo = {
    var shallowMessageCount = 0
    var validBytesCount = 0
    var firstOffset: Option[LogOffsetMetadata] = None
    var lastOffset = -1L
    var lastLeaderEpoch = RecordBatch.NO_PARTITION_LEADER_EPOCH
    var sourceCodec: CompressionCodec = NoCompressionCodec
    var monotonic = true
    var maxTimestamp = RecordBatch.NO_TIMESTAMP
    var offsetOfMaxTimestamp = -1L
    var readFirstMessage = false
    var lastOffsetOfFirstBatch = -1L

    records.batches.forEach { batch =>
      if (origin == RaftLeader && batch.partitionLeaderEpoch != leaderEpoch) {
        throw new InvalidRecordException("Append from Raft leader did not set the batch epoch correctly")
      }
      // we only validate V2 and higher to avoid potential compatibility issues with older clients
      if (batch.magic >= RecordBatch.MAGIC_VALUE_V2 && origin == AppendOrigin.Client && batch.baseOffset != 0)
        throw new InvalidRecordException(s"The baseOffset of the record batch in the append to $topicPartition should " +
          s"be 0, but it is ${batch.baseOffset}")

      // update the first offset if on the first message. For magic versions older than 2, we use the last offset
      // to avoid the need to decompress the data (the last offset can be obtained directly from the wrapper message).
      // For magic version 2, we can get the first offset directly from the batch header.
      // When appending to the leader, we will update LogAppendInfo.baseOffset with the correct value. In the follower
      // case, validation will be more lenient.
      // Also indicate whether we have the accurate first offset or not
      if (!readFirstMessage) {
        if (batch.magic >= RecordBatch.MAGIC_VALUE_V2)
          firstOffset = Some(LogOffsetMetadata(batch.baseOffset))
        lastOffsetOfFirstBatch = batch.lastOffset
        readFirstMessage = true
      }

      // check that offsets are monotonically increasing
      if (lastOffset >= batch.lastOffset)
        monotonic = false

      // update the last offset seen
      lastOffset = batch.lastOffset
      lastLeaderEpoch = batch.partitionLeaderEpoch

      // Check if the message sizes are valid.
      val batchSize = batch.sizeInBytes
      if (!ignoreRecordSize && batchSize > config.maxMessageSize) {
        brokerTopicStats.topicStats(topicPartition.topic).bytesRejectedRate.mark(records.sizeInBytes)
        brokerTopicStats.allTopicsStats.bytesRejectedRate.mark(records.sizeInBytes)
        throw new RecordTooLargeException(s"The record batch size in the append to $topicPartition is $batchSize bytes " +
          s"which exceeds the maximum configured value of ${config.maxMessageSize}.")
      }

      // check the validity of the message by checking CRC
      if (!batch.isValid) {
        brokerTopicStats.allTopicsStats.invalidMessageCrcRecordsPerSec.mark()
        throw new CorruptRecordException(s"Record is corrupt (stored crc = ${batch.checksum()}) in topic partition $topicPartition.")
      }

      if (batch.maxTimestamp > maxTimestamp) {
        maxTimestamp = batch.maxTimestamp
        offsetOfMaxTimestamp = lastOffset
      }

      shallowMessageCount += 1
      validBytesCount += batchSize

      val messageCodec = CompressionCodec.getCompressionCodec(batch.compressionType.id)
      if (messageCodec != NoCompressionCodec)
        sourceCodec = messageCodec
    }

    // Apply broker-side compression if any
    val targetCodec = BrokerCompressionCodec.getTargetCompressionCodec(config.compressionType, sourceCodec)
    val lastLeaderEpochOpt: Option[Int] = if (lastLeaderEpoch != RecordBatch.NO_PARTITION_LEADER_EPOCH)
      Some(lastLeaderEpoch)
    else
      None
    LogAppendInfo(firstOffset, lastOffset, lastLeaderEpochOpt, maxTimestamp, offsetOfMaxTimestamp, RecordBatch.NO_TIMESTAMP, logStartOffset,
      RecordConversionStats.EMPTY, sourceCodec, targetCodec, shallowMessageCount, validBytesCount, monotonic, lastOffsetOfFirstBatch)
  }

  /**
   * Trim any invalid bytes from the end of this message set (if there are any)
   *
   * @param records The records to trim
   * @param info The general information of the message set
   * @return A trimmed message set. This may be the same as what was passed in or it may not.
   */
  private def trimInvalidBytes(records: MemoryRecords, info: LogAppendInfo): MemoryRecords = {
    val validBytes = info.validBytes
    if (validBytes < 0)
      throw new CorruptRecordException(s"Cannot append record batch with illegal length $validBytes to " +
        s"log for $topicPartition. A possible cause is a corrupted produce request.")
    if (validBytes == records.sizeInBytes) {
      records
    } else {
      // trim invalid bytes
      val validByteBuffer = records.buffer.duplicate()
      validByteBuffer.limit(validBytes)
      MemoryRecords.readableRecords(validByteBuffer)
    }
  }

  private def checkLogStartOffset(offset: Long): Unit = {
    if (offset < logStartOffset)
      throw new OffsetOutOfRangeException(s"Received request for offset $offset for partition $topicPartition, " +
        s"but we only have log segments starting from offset: $logStartOffset.")
  }

  /**
   * Read messages from the local log.
   *
   * @param startOffset The offset to begin reading at
   * @param maxLength The maximum number of bytes to read
   * @param isolation The fetch isolation, which controls the maximum offset we are allowed to read
   * @param minOneMessage If this is true, the first message will be returned even if it exceeds `maxLength` (if one exists)
   * @throws OffsetOutOfRangeException If startOffset is beyond the log end offset or before the log start offset
   * @return The fetch data information including fetch starting offset metadata and messages read.
   */
  def read(startOffset: Long,
           maxLength: Int,
           isolation: FetchIsolation,
           minOneMessage: Boolean): FetchDataInfo = {
    checkLogStartOffset(startOffset)

    val maxOffsetMetadata = isolation match {
      case FetchLogEnd => localLog.logEndOffsetMetadata
      case FetchHighWatermark => fetchHighWatermarkMetadata
      case FetchTxnCommitted => fetchLastStableOffsetMetadata
    }

    localLog.read(startOffset, maxLength, minOneMessage, maxOffsetMetadata, isolation == FetchTxnCommitted)
  }

  private[log] def collectAbortedTransactions(startOffset: Long, upperBoundOffset: Long): List[AbortedTxn] = {
    localLog.collectAbortedTransactions(logStartOffset, startOffset, upperBoundOffset)
  }

  /**
   * Get an offset based on the given timestamp
   * The offset returned is the offset of the first message whose timestamp is greater than or equals to the
   * given timestamp.
   *
   * If no such message is found, the log end offset is returned.
   *
   * `NOTE:` OffsetRequest V0 does not use this method, the behavior of OffsetRequest V0 remains the same as before
   * , i.e. it only gives back the timestamp based on the last modification time of the log segments.
   *
   * @param targetTimestamp The given timestamp for offset fetching.
   * @return The offset of the first message whose timestamp is greater than or equals to the given timestamp.
   *         None if no such message is found.
   */
  def fetchOffsetByTimestamp(targetTimestamp: Long): Option[TimestampAndOffset] = {
    maybeHandleIOException(s"Error while fetching offset by timestamp for $topicPartition in dir ${dir.getParent}") {
      debug(s"Searching offset for timestamp $targetTimestamp")

      if (config.messageFormatVersion < KAFKA_0_10_0_IV0 &&
        targetTimestamp != ListOffsetsRequest.EARLIEST_TIMESTAMP &&
        targetTimestamp != ListOffsetsRequest.LATEST_TIMESTAMP)
        throw new UnsupportedForMessageFormatException(s"Cannot search offsets based on timestamp because message format version " +
          s"for partition $topicPartition is ${config.messageFormatVersion} which is earlier than the minimum " +
          s"required version $KAFKA_0_10_0_IV0")

      // For the earliest and latest, we do not need to return the timestamp.
      if (targetTimestamp == ListOffsetsRequest.EARLIEST_TIMESTAMP) {
        // The first cached epoch usually corresponds to the log start offset, but we have to verify this since
        // it may not be true following a message format version bump as the epoch will not be available for
        // log entries written in the older format.
        val earliestEpochEntry = leaderEpochCache.flatMap(_.earliestEntry)
        val epochOpt = earliestEpochEntry match {
          case Some(entry) if entry.startOffset <= logStartOffset => Optional.of[Integer](entry.epoch)
          case _ => Optional.empty[Integer]()
        }
        Some(new TimestampAndOffset(RecordBatch.NO_TIMESTAMP, logStartOffset, epochOpt))
      } else if (targetTimestamp == ListOffsetsRequest.LATEST_TIMESTAMP) {
        val latestEpochOpt = leaderEpochCache.flatMap(_.latestEpoch).map(_.asInstanceOf[Integer])
        val epochOptional = Optional.ofNullable(latestEpochOpt.orNull)
        Some(new TimestampAndOffset(RecordBatch.NO_TIMESTAMP, logEndOffset, epochOptional))
      } else {
        // Cache to avoid race conditions. `toBuffer` is faster than most alternatives and provides
        // constant time access while being safe to use with concurrent collections unlike `toArray`.
        val segmentsCopy = logSegments.toBuffer
        // We need to search the first segment whose largest timestamp is >= the target timestamp if there is one.
        val targetSeg = segmentsCopy.find(_.largestTimestamp >= targetTimestamp)
        targetSeg.flatMap(_.findOffsetByTimestamp(targetTimestamp, logStartOffset))
      }
    }
  }

  def legacyFetchOffsetsBefore(timestamp: Long, maxNumOffsets: Int): Seq[Long] = {
    // Cache to avoid race conditions. `toBuffer` is faster than most alternatives and provides
    // constant time access while being safe to use with concurrent collections unlike `toArray`.
    val segments = logSegments.toBuffer
    val lastSegmentHasSize = segments.last.size > 0

    val offsetTimeArray =
      if (lastSegmentHasSize)
        new Array[(Long, Long)](segments.length + 1)
      else
        new Array[(Long, Long)](segments.length)

    for (i <- segments.indices)
      offsetTimeArray(i) = (math.max(segments(i).baseOffset, logStartOffset), segments(i).lastModified)
    if (lastSegmentHasSize)
      offsetTimeArray(segments.length) = (logEndOffset, time.milliseconds)

    var startIndex = -1
    timestamp match {
      case ListOffsetsRequest.LATEST_TIMESTAMP =>
        startIndex = offsetTimeArray.length - 1
      case ListOffsetsRequest.EARLIEST_TIMESTAMP =>
        startIndex = 0
      case _ =>
        var isFound = false
        debug("Offset time array = " + offsetTimeArray.foreach(o => "%d, %d".format(o._1, o._2)))
        startIndex = offsetTimeArray.length - 1
        while (startIndex >= 0 && !isFound) {
          if (offsetTimeArray(startIndex)._2 <= timestamp)
            isFound = true
          else
            startIndex -= 1
        }
    }

    val retSize = maxNumOffsets.min(startIndex + 1)
    val ret = new Array[Long](retSize)
    for (j <- 0 until retSize) {
      ret(j) = offsetTimeArray(startIndex)._1
      startIndex -= 1
    }
    // ensure that the returned seq is in descending order of offsets
    ret.toSeq.sortBy(-_)
  }

  def convertToOffsetMetadataOrThrow(offset: Long): LogOffsetMetadata = {
    checkLogStartOffset(offset)
    localLog.convertToOffsetMetadataOrThrow(offset)
  }

  /**
   * Delete any local log segments matching the given predicate function,
   * starting with the oldest segment and moving forward until a segment doesn't match.
   *
   * @param predicate A function that takes in a candidate log segment and the next higher segment
   *                  (if there is one) and returns true iff it is deletable
   * @return The number of segments deleted
   */
  private def deleteOldSegments(predicate: (LogSegment, Option[LogSegment]) => Boolean,
                                reason: SegmentDeletionReason): Int = {
    def shouldDelete(segment: LogSegment, nextSegmentOpt: Option[LogSegment], logEndOffset: Long): Boolean = {
      highWatermark >= nextSegmentOpt.map(_.baseOffset).getOrElse(logEndOffset) &&
        predicate(segment, nextSegmentOpt)
    }
    lock synchronized {
      val deletable = localLog.deletableSegments(shouldDelete)
      if (deletable.nonEmpty)
        deleteSegments(deletable, reason)
      else
        0
    }
  }

  private def deleteSegments(deletable: Iterable[LogSegment], reason: SegmentDeletionReason): Int = {
    maybeHandleIOException(s"Error while deleting segments for $topicPartition in dir ${dir.getParent}") {
      val numToDelete = deletable.size
      if (numToDelete > 0) {
        // we must always have at least one segment, so if we are going to delete all the segments, create a new one first
        if (localLog.numberOfSegments == numToDelete)
          roll()
        lock synchronized {
          localLog.checkIfMemoryMappedBufferClosed()
          // remove the segments for lookups
          localLog.removeAndDeleteSegments(deletable, asyncDelete = true, reason)
          scheduleProducerSnapshotDeletion(deletable.toSeq)
          maybeIncrementLogStartOffset(localLog.firstSegmentBaseOffset.get, SegmentDeletion)
        }
      }
      numToDelete
    }
  }

  /**
   * If topic deletion is enabled, delete any local log segments that have either expired due to time based retention
   * or because the log size is > retentionSize.
   *
   * Whether or not deletion is enabled, delete any local log segments that are before the log start offset
   */
  def deleteOldSegments(): Int = {
    if (config.delete) {
      deleteLogStartOffsetBreachedSegments() +
        deleteRetentionSizeBreachedSegments() +
        deleteRetentionMsBreachedSegments()
    } else {
      deleteLogStartOffsetBreachedSegments()
    }
  }

  private def deleteRetentionMsBreachedSegments(): Int = {
    if (config.retentionMs < 0) return 0
    val startMs = time.milliseconds

    def shouldDelete(segment: LogSegment, nextSegmentOpt: Option[LogSegment]): Boolean = {
      startMs - segment.largestTimestamp > config.retentionMs
    }

    deleteOldSegments(shouldDelete, RetentionMsBreach)
  }

  private def deleteRetentionSizeBreachedSegments(): Int = {
    if (config.retentionSize < 0 || size < config.retentionSize) return 0
    var diff = size - config.retentionSize
    def shouldDelete(segment: LogSegment, nextSegmentOpt: Option[LogSegment]): Boolean = {
      if (diff - segment.size >= 0) {
        diff -= segment.size
        true
      } else {
        false
      }
    }

    deleteOldSegments(shouldDelete, RetentionSizeBreach)
  }

  private def deleteLogStartOffsetBreachedSegments(): Int = {
    def shouldDelete(segment: LogSegment, nextSegmentOpt: Option[LogSegment]): Boolean = {
      nextSegmentOpt.exists(_.baseOffset <= logStartOffset)
    }

    deleteOldSegments(shouldDelete, StartOffsetBreach)
  }

  def isFuture: Boolean = localLog.isFuture

  /**
   * The size of the local log in bytes
   */
  def size: Long = localLog.size

  /**
   * The offset of the next message that will be appended to the local log
   */
  def logEndOffset: Long = localLog.logEndOffset

  /**
   * The offset metadata of the next message that will be appended to the local log
   */
  def logEndOffsetMetadata: LogOffsetMetadata = localLog.logEndOffsetMetadata

  private val rollAction = RollAction(
      (newOffset: Long) => {
        // take a snapshot of the producer state to facilitate recovery. It is useful to have the snapshot
        // offset align with the new segment offset since this ensures we can recover the segment by beginning
        // with the corresponding snapshot file and scanning the segment data. Because the segment base offset
        // may actually be ahead of the current producer state end offset (which corresponds to the log end offset),
        // we manually override the state offset here prior to taking the snapshot.
        producerStateManager.updateMapEndOffset(newOffset)
        producerStateManager.takeSnapshot()
      },
      (newSegment: LogSegment, deletedSegment: Option[LogSegment]) => {
        deletedSegment.foreach(segment => scheduleProducerSnapshotDeletion(Seq(segment)))
        updateHighWatermarkWithLogEndOffset()
        // schedule an asynchronous flush of the old segment
        scheduler.schedule("flush-log", () => flush(newSegment.baseOffset))
      }
    )

  private def maybeRoll(messagesSize: Int, appendInfo: LogAppendInfo): LogSegment = {
    lock synchronized {
      localLog.maybeRoll(messagesSize, appendInfo, rollAction)
    }
  }

  /**
   * Roll the local log over to a new active segment starting with the current logEndOffset.
   * This will trim the index to the exact size of the number of entries it currently contains.
   *
   * @return The newly rolled segment
   */
  def roll(expectedNextOffset: Option[Long] = None): LogSegment = {
    lock synchronized {
      localLog.roll(expectedNextOffset, rollAction)
    }
  }

  /**
   * The number of messages appended to the local log since the last flush
   */
  private def unflushedMessages: Long = logEndOffset - localLog.recoveryPoint

  /**
   * Flush all local log segments
   */
  def flush(): Unit = flush(logEndOffset)

  /**
   * Flush local log segments for all offsets up to offset-1
   *
   * @param offset The offset to flush up to (non-inclusive); the new recovery point
   */
  def flush(offset: Long): Unit = {
    maybeHandleIOException(s"Error while flushing log for $topicPartition in dir ${dir.getParent} with offset $offset") {
      if (offset > localLog.recoveryPoint) {
        info(s"Flushing log up to offset $offset, last flushed: $lastFlushTime,  current time: ${time.milliseconds()}, " +
          s"unflushed: $unflushedMessages")
        logSegments(localLog.recoveryPoint, offset).foreach(_.flush())

        lock synchronized {
          localLog.markFlushed(offset)
        }
      }
    }
  }

  /**
   * Completely delete the local log directory and all contents from the file system with no delay
   */
  private[log] def delete(): Unit = {
    maybeHandleIOException(s"Error while deleting log for $topicPartition in dir ${dir.getParent}") {
      lock synchronized {
        producerExpireCheck.cancel(true)
        leaderEpochCache.foreach(_.clear())
        val deletedSegments = localLog.delete()
        scheduleProducerSnapshotDeletion(deletedSegments)
      }
    }
  }

  // visible for testing
  private[log] def takeProducerSnapshot(): Unit = lock synchronized {
    localLog.checkIfMemoryMappedBufferClosed()
    producerStateManager.takeSnapshot()
  }

  // visible for testing
  private[log] def latestProducerSnapshotOffset: Option[Long] = lock synchronized {
    producerStateManager.latestSnapshotOffset
  }

  // visible for testing
  private[log] def oldestProducerSnapshotOffset: Option[Long] = lock synchronized {
    producerStateManager.oldestSnapshotOffset
  }

  // visible for testing
  private[log] def latestProducerStateEndOffset: Long = lock synchronized {
    producerStateManager.mapEndOffset
  }

  /**
   * Truncate this log so that it ends with the greatest offset < targetOffset.
   *
   * @param targetOffset The offset to truncate to, an upper bound on all offsets in the log after truncation is complete.
   * @return True iff targetOffset < logEndOffset
   */
  private[kafka] def truncateTo(targetOffset: Long): Boolean = {
    maybeHandleIOException(s"Error while truncating log to offset $targetOffset for $topicPartition in dir ${dir.getParent}") {
      if (targetOffset < 0)
        throw new IllegalArgumentException(s"Cannot truncate partition $topicPartition to a negative offset (%d).".format(targetOffset))
      if (targetOffset >= localLog.logEndOffset) {
        info(s"Truncating to $targetOffset has no effect as the largest offset in the log is ${localLog.logEndOffset - 1}")

        // Always truncate epoch cache since we may have a conflicting epoch entry at the
        // end of the log from the leader. This could happen if this broker was a leader
        // and inserted the first start offset entry, but then failed to append any entries
        // before another leader was elected.
        lock synchronized {
          leaderEpochCache.foreach(_.truncateFromEnd(logEndOffset))
        }

        false
      } else {
        info(s"Truncating to offset $targetOffset")
        lock synchronized {
          localLog.checkIfMemoryMappedBufferClosed()
          if (localLog.firstSegmentBaseOffset.get > targetOffset) {
            truncateFullyAndStartAt(targetOffset)
          } else {
            val deletedSegments = localLog.truncateTo(targetOffset)
            scheduleProducerSnapshotDeletion(deletedSegments)
            leaderEpochCache.foreach(_.truncateFromEnd(targetOffset))
            completeTruncation(
              startOffset = math.min(targetOffset, logStartOffset),
              endOffset = targetOffset
            )
          }
          true
        }
      }
    }
  }

  /**
   *  Delete all data in the log and start at the new offset
   *
   *  @param newOffset The new offset to start the log with
   */
  def truncateFullyAndStartAt(newOffset: Long): Unit = {
    maybeHandleIOException(s"Error while truncating the entire log for $topicPartition in dir ${dir.getParent}") {
      debug(s"Truncate and start at offset $newOffset")
      lock synchronized {
        val deletedSegments = localLog.truncateFullyAndStartAt(newOffset)
        scheduleProducerSnapshotDeletion(deletedSegments)
        leaderEpochCache.foreach(_.clearAndFlush())
        producerStateManager.truncateFullyAndStartAt(newOffset)
        completeTruncation(
          startOffset = newOffset,
          endOffset = newOffset
        )
      }
    }
  }

  private def completeTruncation(
    startOffset: Long,
    endOffset: Long
  ): Unit = {
    logStartOffset = startOffset
    localLog.updateLogEndOffset(endOffset)
    localLog.updateRecoveryPoint(math.min(recoveryPoint, endOffset))
    lock synchronized {
      localLog.rebuildProducerState(logStartOffset, endOffset, reloadFromCleanShutdown = false, producerStateManager)
    }
    updateHighWatermark(math.min(highWatermark, endOffset))
  }

  /**
   * The time this log is last known to have been fully flushed to disk
   */
  def lastFlushTime: Long = localLog.lastFlushTime

  /**
   * The active segment that is currently taking appends
   */
  def activeSegment = localLog.activeSegment

  /**
   * All the local log segments in this log ordered from oldest to newest
   */
  def logSegments: Iterable[LogSegment] = localLog.logSegments

  /**
   * Get all local segments beginning with the segment that includes "from" and ending with the segment
   * that includes up to "to-1" or the end of the log (if to > logEndOffset).
   */
  def logSegments(from: Long, to: Long): Iterable[LogSegment] = {
    lock synchronized {
      localLog.logSegments(from, to)
    }
  }

  def nonActiveLogSegmentsFrom(from: Long): Iterable[LogSegment] = {
    lock synchronized {
      localLog.nonActiveLogSegmentsFrom(from)
    }
  }

  override def toString: String = {
    val logString = new StringBuilder
    logString.append(s"Log(dir=$dir")
    logString.append(s", topic=${topicPartition.topic}")
    logString.append(s", partition=${topicPartition.partition}")
    logString.append(s", highWatermark=$highWatermark")
    logString.append(s", lastStableOffset=$lastStableOffset")
    logString.append(s", logStartOffset=$logStartOffset")
    logString.append(s", logEndOffset=$logEndOffset")
    logString.append(")")
    logString.toString
  }

  private[log] def loadSegments(): Unit = {
    lock synchronized {
      val deletedSegments = localLog.loadSegments(logStartOffset, maxProducerIdExpirationMs, producerStateManager, leaderEpochCache)
      scheduleProducerSnapshotDeletion(deletedSegments)
    }
  }

  /**
   * Swap one or more new local segment in place and delete one or more existing local segments in a crash-safe manner.
   * The old segments will be asynchronously deleted.
   *
   * This method does not need to convert IOException to KafkaStorageException because it is either called before all logs are loaded
   * or the caller will catch and handle IOException
   *
   * The sequence of operations is:
   * <ol>
   *   <li> Cleaner creates one or more new segments with suffix .cleaned and invokes replaceSegments().
   *        If broker crashes at this point, the clean-and-swap operation is aborted and
   *        the .cleaned files are deleted on recovery in loadSegments().
   *   <li> New segments are renamed .swap. If the broker crashes before all segments were renamed to .swap, the
   *        clean-and-swap operation is aborted - .cleaned as well as .swap files are deleted on recovery in
   *        loadSegments(). We detect this situation by maintaining a specific order in which files are renamed from
   *        .cleaned to .swap. Basically, files are renamed in descending order of offsets. On recovery, all .swap files
   *        whose offset is greater than the minimum-offset .clean file are deleted.
   *   <li> If the broker crashes after all new segments were renamed to .swap, the operation is completed, the swap
   *        operation is resumed on recovery as described in the next step.
   *   <li> Old segment files are renamed to .deleted and asynchronous delete is scheduled.
   *        If the broker crashes, any .deleted files left behind are deleted on recovery in loadSegments().
   *        replaceSegments() is then invoked to complete the swap with newSegment recreated from
   *        the .swap file and oldSegments containing segments which were not renamed before the crash.
   *   <li> Swap segment(s) are renamed to replace the existing segments, completing this operation.
   *        If the broker crashes, any .deleted files which may be left behind are deleted
   *        on recovery in loadSegments().
   * </ol>
   *
   * @param newSegments The new log segment to add to the log
   * @param oldSegments The old log segments to delete from the log
   * @param isRecoveredSwapFile true if the new segment was created from a swap file during recovery after a crash
   */
  private[log] def replaceSegments(newSegments: Seq[LogSegment], oldSegments: Seq[LogSegment], isRecoveredSwapFile: Boolean = false): Unit = {
    lock synchronized {
      val deleted = localLog.replaceSegments(newSegments, oldSegments, isRecoveredSwapFile)
      scheduleProducerSnapshotDeletion(deleted)
    }
  }

  /**
    * This function does not acquire Log.lock. The caller has to make sure local log segments don't get deleted during
    * this call, and also protects against calling this function on the same segment in parallel.
    *
    * Currently, it is used by LogCleaner threads on log compact non-active segments only with LogCleanerManager's lock
    * to ensure no other logcleaner threads and retention thread can work on the same segment.
    */
  private[log] def getFirstBatchTimestampForSegments(segments: Iterable[LogSegment]): Iterable[Long] = {
    localLog.getFirstBatchTimestampForSegments(segments)
  }

  /**
   * remove deleted log metrics
   */
  private[log] def removeLogMetrics(): Unit = {
    removeMetric(LogMetricNames.NumLogSegments, tags)
    removeMetric(LogMetricNames.LogStartOffset, tags)
    removeMetric(LogMetricNames.LogEndOffset, tags)
    removeMetric(LogMetricNames.Size, tags)
  }

  /**
   * Split a local segment into one or more segments such that there is no offset overflow in any of them. The
   * resulting segments will contain the exact same messages that are present in the input segment. On successful
   * completion of this method, the input segment will be deleted and will be replaced by the resulting new segments.
   * See replaceSegments for recovery logic, in case the broker dies in the middle of this operation.
   * <p>Note that this method assumes we have already determined that the segment passed in contains records that cause
   * offset overflow.</p>
   * <p>The split logic overloads the use of .clean files that LogCleaner typically uses to make the process of replacing
   * the input segment with multiple new segments atomic and recoverable in the event of a crash. See replaceSegments
   * and completeSwapOperations for the implementation to make this operation recoverable on crashes.</p>
   * @param segment Segment to split
   * @return List of new segments that replace the input segment
   */
  private[log] def splitOverflowedSegment(segment: LogSegment): List[LogSegment] = {
    val result = localLog.splitOverflowedSegment(segment)
    scheduleProducerSnapshotDeletion(result.deletedSegments)
    result.newSegments.toList
  }

  /**
   * Add the given segment to the segments ƒin this local log. If this segment replaces an existing segment, delete it.
   * @param segment The segment to add
   */
  @threadsafe
  private[log] def addSegment(segment: LogSegment): LogSegment = localLog.addSegment(segment)

  private def maybeHandleIOException[T](msg: => String)(fun: => T): T = {
    try {
      localLog.checkForLogDirFailure()
      fun
    } catch {
      case e: IOException =>
        localLog.logDirOffline = true
        logDirFailureChannel.maybeAddOfflineLogDir(dir.getParent, msg, e)
        throw new KafkaStorageException(msg, e)
    }
  }

  def scheduleProducerSnapshotDeletion(segments: Seq[LogSegment]): Unit = {
    def deleteProducerSnapshots(segments: Seq[LogSegment]): Unit = {
      maybeHandleIOException(s"Error while deleting producer state snapshots for $topicPartition in dir ${dir.getParent}") {
        segments.foreach {
          segment => producerStateManager.removeAndDeleteSnapshot(segment.baseOffset)
        }
      }
    }
    scheduler.schedule("delete-producer-snapshot", () => deleteProducerSnapshots(segments), delay = config.fileDeleteDelayMs)
  }
}

/**
 * Helper functions for logs
 */
object Log {

  /** a log file */
  val LogFileSuffix = LocalLog.LogFileSuffix

  /** an index file */
  val IndexFileSuffix = LocalLog.IndexFileSuffix

  /** a time index file */
  val TimeIndexFileSuffix = LocalLog.TimeIndexFileSuffix

  val ProducerSnapshotFileSuffix = LocalLog.ProducerSnapshotFileSuffix

  /** an (aborted) txn index */
  val TxnIndexFileSuffix = LocalLog.TxnIndexFileSuffix

  /** a file that is scheduled to be deleted */
  val DeletedFileSuffix = LocalLog.DeletedFileSuffix

  /** A temporary file that is being used for log cleaning */
  val CleanedFileSuffix = LocalLog.CleanedFileSuffix

  /** A temporary file used when swapping files into the log */
  val SwapFileSuffix = LocalLog.SwapFileSuffix

  /** Clean shutdown file that indicates the broker was cleanly shutdown in 0.8 and higher.
   * This is used to avoid unnecessary recovery after a clean shutdown. In theory this could be
   * avoided by passing in the recovery point, however finding the correct position to do this
   * requires accessing the offset index which may not be safe in an unclean shutdown.
   * For more information see the discussion in PR#2104
   */
  val CleanShutdownFile = ".kafka_cleanshutdown"

  /** a directory that is scheduled to be deleted */
  val DeleteDirSuffix = LocalLog.DeleteDirSuffix

  /** a directory that is used for future partition */
  val FutureDirSuffix = LocalLog.FutureDirSuffix

  private[log] val DeleteDirPattern = LocalLog.DeleteDirPattern
  private[log] val FutureDirPattern = LocalLog.FutureDirPattern

  val UnknownOffset = LocalLog.UnknownOffset

  def apply(dir: File,
            config: LogConfig,
            logStartOffset: Long,
            recoveryPoint: Long,
            scheduler: Scheduler,
            brokerTopicStats: BrokerTopicStats,
            time: Time = Time.SYSTEM,
            maxProducerIdExpirationMs: Int,
            producerIdExpirationCheckIntervalMs: Int,
            logDirFailureChannel: LogDirFailureChannel,
            lastShutdownClean: Boolean = true,
            keepPartitionMetadataFile: Boolean = true): Log = {
    val topicPartition = Log.parseTopicPartitionName(dir)
    val localLog = new LocalLog(dir, config, recoveryPoint, scheduler, time, topicPartition, logDirFailureChannel, lastShutdownClean)
    val producerStateManager = new ProducerStateManager(topicPartition, dir, maxProducerIdExpirationMs)
    new Log(localLog, config, logStartOffset, scheduler, brokerTopicStats, time, maxProducerIdExpirationMs,
      producerIdExpirationCheckIntervalMs, topicPartition, producerStateManager, logDirFailureChannel, lastShutdownClean, keepPartitionMetadataFile)
  }

  /**
   * Make log segment file name from offset bytes. All this does is pad out the offset number with zeros
   * so that ls sorts the files numerically.
   *
   * @param offset The offset to use in the file name
   * @return The filename
   */
  def filenamePrefixFromOffset(offset: Long): String = {
    LocalLog.filenamePrefixFromOffset(offset)
  }

  /**
   * Construct a log file name in the given dir with the given base offset and the given suffix
   *
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   * @param suffix The suffix to be appended to the file name (e.g. "", ".deleted", ".cleaned", ".swap", etc.)
   */
  def logFile(dir: File, offset: Long, suffix: String = ""): File =
    LocalLog.logFile(dir, offset, suffix)

  /**
   * Return a directory name to rename the log directory to for async deletion.
   * The name will be in the following format: "topic-partitionId.uniqueId-delete".
   * If the topic name is too long, it will be truncated to prevent the total name
   * from exceeding 255 characters.
   */
  def logDeleteDirName(topicPartition: TopicPartition): String = {
    LocalLog.logDeleteDirName(topicPartition)
  }

  /**
   * Return a future directory name for the given topic partition. The name will be in the following
   * format: topic-partition.uniqueId-future where topic, partition and uniqueId are variables.
   */
  def logFutureDirName(topicPartition: TopicPartition): String = {
    LocalLog.logFutureDirName(topicPartition)
  }

  /**
   * Return a directory name for the given topic partition. The name will be in the following
   * format: topic-partition where topic, partition are variables.
   */
  def logDirName(topicPartition: TopicPartition): String = {
    LocalLog.logDirName(topicPartition)
  }

  /**
   * Construct an index file name in the given dir using the given base offset and the given suffix
   *
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   * @param suffix The suffix to be appended to the file name ("", ".deleted", ".cleaned", ".swap", etc.)
   */
  def offsetIndexFile(dir: File, offset: Long, suffix: String = ""): File =
    LocalLog.offsetIndexFile(dir, offset, suffix)

  /**
   * Construct a time index file name in the given dir using the given base offset and the given suffix
   *
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   * @param suffix The suffix to be appended to the file name ("", ".deleted", ".cleaned", ".swap", etc.)
   */
  def timeIndexFile(dir: File, offset: Long, suffix: String = ""): File =
    LocalLog.timeIndexFile(dir, offset, suffix)

  def deleteFileIfExists(file: File, suffix: String = ""): Unit =
    Files.deleteIfExists(new File(file.getPath + suffix).toPath)

  /**
   * Construct a producer id snapshot file using the given offset.
   *
   * @param dir The directory in which the log will reside
   * @param offset The last offset (exclusive) included in the snapshot
   */
  def producerSnapshotFile(dir: File, offset: Long): File =
    new File(dir, filenamePrefixFromOffset(offset) + ProducerSnapshotFileSuffix)

  /**
   * Construct a transaction index file name in the given dir using the given base offset and the given suffix
   *
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   * @param suffix The suffix to be appended to the file name ("", ".deleted", ".cleaned", ".swap", etc.)
   */
  def transactionIndexFile(dir: File, offset: Long, suffix: String = ""): File =
    LocalLog.transactionIndexFile(dir, offset, suffix)

  def offsetFromFileName(filename: String): Long = {
    LocalLog.offsetFromFileName(filename)
  }

  def offsetFromFile(file: File): Long = {
    LocalLog.offsetFromFileName(file.getName)
  }

  /**
   * Calculate a log's size (in bytes) based on its log segments
   *
   * @param segments The log segments to calculate the size of
   * @return Sum of the log segments' sizes (in bytes)
   */
  def sizeInBytes(segments: Iterable[LogSegment]): Long =
    LocalLog.sizeInBytes(segments)

  /**
   * Parse the topic and partition out of the directory name of a log
   */
  def parseTopicPartitionName(dir: File): TopicPartition = {
    LocalLog.parseTopicPartitionName(dir)
  }
}

object LogMetricNames {
  val NumLogSegments: String = "NumLogSegments"
  val LogStartOffset: String = "LogStartOffset"
  val LogEndOffset: String = "LogEndOffset"
  val Size: String = "Size"

  def allMetricNames: List[String] = {
    List(NumLogSegments, LogStartOffset, LogEndOffset, Size)
  }
}

case object RetentionMsBreach extends SegmentDeletionReason {
  override def logReason(log: LocalLog, toDelete: List[LogSegment]): Unit = {
    val retentionMs = log.config.retentionMs
    toDelete.foreach { segment =>
      segment.largestRecordTimestamp match {
        case Some(_) =>
          log.info(s"Deleting segment $segment due to retention time ${retentionMs}ms breach based on the largest " +
            s"record timestamp in the segment")
        case None =>
          log.info(s"Deleting segment $segment due to retention time ${retentionMs}ms breach based on the " +
            s"last modified time of the segment")
      }
    }
  }
}

case object RetentionSizeBreach extends SegmentDeletionReason {
  override def logReason(log: LocalLog, toDelete: List[LogSegment]): Unit = {
    var size = log.size
    toDelete.foreach { segment =>
      size -= segment.size
      log.info(s"Deleting segment $segment due to retention size ${log.config.retentionSize} breach. Log size " +
        s"after deletion will be $size.")
    }
  }
}

case object StartOffsetBreach extends SegmentDeletionReason {
  override def logReason(log: LocalLog, toDelete: List[LogSegment]): Unit = {
    log.info(s"Deleting segments due to log start offset breach: ${toDelete.mkString(",")}")
  }
}

case object LogRecovery extends SegmentDeletionReason {
  override def logReason(log: LocalLog, toDelete: List[LogSegment]): Unit = {
    log.info(s"Deleting segments as part of log recovery: ${toDelete.mkString(",")}")
  }
}

case object LogTruncation extends SegmentDeletionReason {
  override def logReason(log: LocalLog, toDelete: List[LogSegment]): Unit = {
    log.info(s"Deleting segments as part of log truncation: ${toDelete.mkString(",")}")
  }
}

case object LogRoll extends SegmentDeletionReason {
  override def logReason(log: LocalLog, toDelete: List[LogSegment]): Unit = {
    log.info(s"Deleting segments as part of log roll: ${toDelete.mkString(",")}")
  }
}

case object LogDeletion extends SegmentDeletionReason {
  override def logReason(log: LocalLog, toDelete: List[LogSegment]): Unit = {
    log.info(s"Deleting segments as the log has been deleted: ${toDelete.mkString(",")}")
  }
}
