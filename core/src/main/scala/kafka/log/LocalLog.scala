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

import java.lang.{Long => JLong}
import java.io.{File, IOException}
import java.nio.file.Files
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicLong
import java.util.Map.{Entry => JEntry}
import java.util.regex.Pattern

import kafka.metrics.KafkaMetricsGroup
import kafka.server.{FetchDataInfo, LogDirFailureChannel, LogOffsetMetadata}
import kafka.utils.{Logging, Scheduler}
import org.apache.kafka.common.{KafkaException, TopicPartition}
import org.apache.kafka.common.errors.{KafkaStorageException, OffsetOutOfRangeException}
import org.apache.kafka.common.message.FetchResponseData
import org.apache.kafka.common.record.MemoryRecords
import org.apache.kafka.common.utils.{Time, Utils}

import scala.jdk.CollectionConverters._
import scala.collection.Seq
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

/**
 * Defines the actions to be performed before and after rolling the active segment. Please refer
 * to its usage in LocalLog#roll.
 *
 * @param preRollAction A function that accepts as parameter the new segment being created and
 *                      performs the required activities just prior to when the current active segment
 *                      is being rolled.
 * @param postRollAction A function that accepts as parameters (1) the latest active segment (after the roll)
 *                       and (2) the segment (if any) that was deleted prior to the roll. The function
 *                       performs the required activities just after the active segment was rolled.
 */
case class RollAction(preRollAction: LogSegment => Unit,
                      postRollAction: (LogSegment, Option[LogSegment]) => Unit)

// Used to hold the result of splitting a segment into one or more segments, see LocalLog#splitOverflowedSegment
case class SplitSegmentResult(deletedSegments: Iterable[LogSegment], newSegments: Iterable[LogSegment])

/**
 * An append-only log for storing messages locally. The log is a sequence of LogSegments, each with a base offset.
 * New log segments are created according to a configurable policy that controls the size in bytes or time interval
 * for a given segment.
 *
 * NOTE: this class is not thread-safe, and it relies on the thread safety provided by the Log class.
 *
 * @param _dir The directory in which log segments are created.
 * @param config The log configuration settings
 * @param segments The non-empty log segments recovered from disk
 * @param recoveryPoint The offset at which to begin the next recovery i.e. the first offset which has not been flushed to disk
 * @param nextOffsetMetadata The offset where the next message could be appended
 * @param scheduler The thread pool scheduler used for background actions
 * @param time The time instance used for checking the clock
 * @param topicPartition The topic partition associated with this log
 * @param logDirFailureChannel The LogDirFailureChannel instance to asynchronously handle Log dir failure
 */
class LocalLog(@volatile private var _dir: File,
               @volatile var config: LogConfig,
               val segments: LogSegments,
               @volatile var recoveryPoint: Long,
               @volatile private var nextOffsetMetadata: LogOffsetMetadata,
               val scheduler: Scheduler,
               val time: Time,
               val topicPartition: TopicPartition,
               val logDirFailureChannel: LogDirFailureChannel) extends Logging with KafkaMetricsGroup {

  import kafka.log.LocalLog._

  this.logIdent = s"[LocalLog partition=$topicPartition, dir=${dir.getParent}] "

  // The memory mapped buffer for index files of this log will be closed with either delete() or closeHandlers()
  // After memory mapped buffer is closed, no disk IO operation should be performed for this log
  @volatile private var isMemoryMappedBufferClosed = false

  // Cache value of parent directory to avoid allocations in hot paths like ReplicaManager.checkpointHighWatermarks
  @volatile private var _parentDir: String = dir.getParent

  /* last time the log was flushed */
  private val lastFlushedTime = new AtomicLong(time.milliseconds)

  private[log] def dir: File = _dir

  private[log] def name: String = dir.getName()

  private[log] def parentDir: String = _parentDir

  private[log] def parentDirFile: File = new File(_parentDir)

  private[log] def isFuture: Boolean = dir.getName.endsWith(LocalLog.FutureDirSuffix)

  private def maybeHandleIOException[T](msg: => String)(fun: => T): T = {
    LocalLog.maybeHandleIOException(logDirFailureChannel, parentDir, msg) {
      fun
    }
  }

  /**
   * Rename the directory of the log
   * @param name the new dir name
   * @throws KafkaStorageException if rename fails
   */
  private[log] def renameDir(name: String): Boolean = {
    maybeHandleIOException(s"Error while renaming dir for $topicPartition in log dir ${dir.getParent}") {
      val renamedDir = new File(dir.getParent, name)
      Utils.atomicMoveWithFallback(dir.toPath, renamedDir.toPath)
      if (renamedDir != dir) {
        _dir = renamedDir
        _parentDir = renamedDir.getParent
        segments.updateParentDir(renamedDir)
        true
      } else {
        false
      }
    }
  }

  private[log] def updateConfig(newConfig: LogConfig): Unit = {
    val oldConfig = config
    config = newConfig
    val oldRecordVersion = oldConfig.messageFormatVersion.recordVersion
    val newRecordVersion = newConfig.messageFormatVersion.recordVersion
    if (newRecordVersion.precedes(oldRecordVersion))
      warn(s"Record format version has been downgraded from $oldRecordVersion to $newRecordVersion.")
  }

  private[log] def checkIfMemoryMappedBufferClosed(): Unit = {
    if (isMemoryMappedBufferClosed)
      throw new KafkaStorageException(s"The memory mapped buffer for log of $topicPartition is already closed")
  }

  private[log] def updateRecoveryPoint(newRecoveryPoint: Long): Unit = {
    recoveryPoint = newRecoveryPoint
  }

  /**
   * Update recoveryPoint to provided offset and mark the log as flushed, if the offset is greater
   * than the existing recoveryPoint.
   *
   * @param offset the offset to be updated
   */
  private[log] def markFlushed(offset: Long): Unit = {
    checkIfMemoryMappedBufferClosed()
    if (offset > recoveryPoint) {
      updateRecoveryPoint(offset)
      lastFlushedTime.set(time.milliseconds)
    }
  }

  /**
   * The time this log is last known to have been fully flushed to disk
   */
  private[log] def lastFlushTime: Long = lastFlushedTime.get

  /**
   * The offset metadata of the next message that will be appended to the log
   */
  private[log] def logEndOffsetMetadata: LogOffsetMetadata = nextOffsetMetadata

  /**
   * The offset of the next message that will be appended to the log
   */
  private[log] def logEndOffset: Long = nextOffsetMetadata.messageOffset

  /**
   * Update end offset of the log, and the recoveryPoint.
   *
   * @param endOffset the new end offset of the log
   */
  private[log] def updateLogEndOffset(endOffset: Long): Unit = {
    nextOffsetMetadata = LogOffsetMetadata(endOffset, segments.activeSegment.baseOffset, segments.activeSegment.size)
    if (recoveryPoint > endOffset) {
      updateRecoveryPoint(endOffset)
    }
  }

  /**
   * Close file handlers used by log but don't write to disk.
   * This is called if the log directory is offline.
   */
  private[log] def closeHandlers(): Unit = {
    segments.closeHandlers()
    isMemoryMappedBufferClosed = true
  }

  /**
   * Closes the log.
   */
  private[log] def close(): Unit = {
    maybeHandleIOException(s"Error while deleting log for $topicPartition in dir ${dir.getParent}") {
      checkIfMemoryMappedBufferClosed()
      segments.close()
    }
  }

  /**
   * Completely delete this log directory and all contents from the file system with no delay
   */
  private[log] def delete(): Iterable[LogSegment] = {
    maybeHandleIOException(s"Error while deleting log for $topicPartition in dir ${dir.getParent}") {
      checkIfMemoryMappedBufferClosed()
      val deletableSegments = segments.values
      removeAndDeleteSegments(
        deletableSegments,
        asyncDelete = false,
        () => {
          info(s"Deleting segments as the log has been deleted: ${deletableSegments.mkString(",")}")
        })
      Utils.delete(dir)
      // File handlers will be closed if this log is deleted
      isMemoryMappedBufferClosed = true
      deletableSegments
    }
  }

  /**
   * Find segments starting from the oldest until the user-supplied predicate is false.
   * A final segment that is empty will never be returned (since we would just end up re-creating it).
   *
   * @param predicate A function that takes in a candidate log segment and the next higher segment
   *                  (if there is one) and returns true iff it is deletable
   * @return the segments ready to be deleted
   */
  private[log] def deletableSegments(predicate: (LogSegment, Option[LogSegment], Long) => Boolean): Iterable[LogSegment] = {
    if (segments.isEmpty) {
      Seq.empty
    } else {
      val deletable = ArrayBuffer.empty[LogSegment]
      var segmentEntryOpt = segments.firstEntry
      while (segmentEntryOpt.isDefined) {
        val segmentEntry = segmentEntryOpt.get
        val segment = segmentEntry.getValue
        val nextSegmentEntryOpt = segments.higherEntry(segmentEntry.getKey)
        val (nextSegment, isLastSegmentAndEmpty) =
          nextSegmentEntryOpt.map {
            entry => (entry.getValue, false)
          }.getOrElse {
            (null, segment.size == 0)
          }

        if (predicate(segment, Option(nextSegment), logEndOffset) && !isLastSegmentAndEmpty) {
          deletable += segment
          segmentEntryOpt = nextSegmentEntryOpt
        } else {
          segmentEntryOpt = Option.empty
        }
      }
      deletable
    }
  }

  /**
   * This method deletes the given log segments by doing the following for each of them:
   * <ol>
   *   <li>It removes the segment from the segment map so that it will no longer be used for reads.
   *   <li>It renames the index and log files by appending .deleted to the respective file name
   *   <li>It can either schedule an asynchronous delete operation to occur in the future or perform the deletion synchronously
   * </ol>
   * Asynchronous deletion allows reads to happen concurrently without synchronization and without the possibility of
   * physically deleting a file while it is being read.
   *
   * This method does not need to convert IOException to KafkaStorageException because it is either called before all logs are loaded
   * or the immediate caller will catch and handle IOException
   *
   * @param segments The log segments to schedule for deletion
   * @param asyncDelete Whether the segment files should be deleted asynchronously
   * @param reason A function that accepts the list of log segments to delete and returns the reason for the segment deletion
   */
  private[log] def removeAndDeleteSegments(segments: Iterable[LogSegment],
                                           asyncDelete: Boolean,
                                           reason: () => Unit): Unit = {
    if (segments.nonEmpty) {
      // As most callers hold an iterator into the `segments` collection and `removeAndDeleteSegment` mutates it by
      // removing the deleted segment, we should force materialization of the iterator here, so that results of the
      // iteration remain valid and deterministic.
      val toDelete = segments.toList
      reason()
      toDelete.foreach { segment =>
        this.segments.remove(segment.baseOffset)
      }
      LocalLog.deleteSegmentFiles(toDelete, asyncDelete, dir, topicPartition, config, scheduler, logDirFailureChannel)
    }
  }

  /**
   * Given a message offset, find its corresponding offset metadata in the log.
   * If the message offset is out of range, throw an OffsetOutOfRangeException
   */
  private[log] def convertToOffsetMetadataOrThrow(offset: Long): LogOffsetMetadata = {
    val fetchDataInfo = read(offset,
      maxLength = 1,
      minOneMessage = false,
      maxOffsetMetadata = nextOffsetMetadata,
      includeAbortedTxns = false)
    fetchDataInfo.fetchOffsetMetadata
  }

  /**
   * Read messages from the log.
   *
   * @param startOffset The offset to begin reading at
   * @param maxLength The maximum number of bytes to read
   * @param minOneMessage If this is true, the first message will be returned even if it exceeds `maxLength` (if one exists)
   * @param maxOffsetMetadata The metadata of the maximum offset to be fetched
   * @throws OffsetOutOfRangeException If startOffset is beyond the log end offset or before the log start offset
   * @return The fetch data information including fetch starting offset metadata and messages read.
   */
  def read(startOffset: Long,
           maxLength: Int,
           minOneMessage: Boolean,
           maxOffsetMetadata: LogOffsetMetadata,
           includeAbortedTxns: Boolean): FetchDataInfo = {
    maybeHandleIOException(s"Exception while reading from $topicPartition in dir ${dir.getParent}") {
      trace(s"Reading maximum $maxLength bytes at offset $startOffset from log with " +
        s"total length ${segments.sizeInBytes} bytes")

      // Because we don't use the lock for reading, the synchronization is a little bit tricky.
      // We create the local variables to avoid race conditions with updates to the log.
      val endOffsetMetadata = nextOffsetMetadata
      val endOffset = endOffsetMetadata.messageOffset
      var segmentEntryOpt = segments.floorEntry(startOffset)

      // return error on attempt to read beyond the log end offset or read below log start offset
      if (startOffset > endOffset || segmentEntryOpt.isEmpty)
        throw new OffsetOutOfRangeException(s"Received request for offset $startOffset for partition $topicPartition, " +
          s"but we only have log segments upto $endOffset.")

      if (startOffset == maxOffsetMetadata.messageOffset)
        emptyFetchDataInfo(maxOffsetMetadata, includeAbortedTxns)
      else if (startOffset > maxOffsetMetadata.messageOffset)
        emptyFetchDataInfo(convertToOffsetMetadataOrThrow(startOffset), includeAbortedTxns)
      else {
        // Do the read on the segment with a base offset less than the target offset
        // but if that segment doesn't contain any messages with an offset greater than that
        // continue to read from successive segments until we get some messages or we reach the end of the log
        var done = segmentEntryOpt.isEmpty
        var fetchDataInfo: FetchDataInfo = null
        while (!done) {
          val segmentEntry = segmentEntryOpt.get
          val baseOffset = segmentEntry.getKey
          val segment = segmentEntry.getValue

          val maxPosition =
          // Use the max offset position if it is on this segment; otherwise, the segment size is the limit.
            if (maxOffsetMetadata.segmentBaseOffset == segment.baseOffset) maxOffsetMetadata.relativePositionInSegment
            else segment.size

          fetchDataInfo = segment.read(startOffset, maxLength, maxPosition, minOneMessage)
          if (fetchDataInfo != null) {
            if (includeAbortedTxns)
              fetchDataInfo = addAbortedTransactions(startOffset, segmentEntry, fetchDataInfo)
          } else segmentEntryOpt = segments.higherEntry(baseOffset)

          done = fetchDataInfo != null || segmentEntryOpt.isEmpty
        }

        if (fetchDataInfo != null) fetchDataInfo
        else {
          // okay we are beyond the end of the last segment with no data fetched although the start offset is in range,
          // this can happen when all messages with offset larger than start offsets have been deleted.
          // In this case, we will return the empty set with log end offset metadata
          FetchDataInfo(nextOffsetMetadata, MemoryRecords.EMPTY)
        }
      }
    }
  }

  private def addAbortedTransactions(startOffset: Long, segmentEntry: JEntry[JLong, LogSegment],
                                     fetchInfo: FetchDataInfo): FetchDataInfo = {
    val fetchSize = fetchInfo.records.sizeInBytes
    val startOffsetPosition = OffsetPosition(fetchInfo.fetchOffsetMetadata.messageOffset,
      fetchInfo.fetchOffsetMetadata.relativePositionInSegment)
    val upperBoundOffset = segmentEntry.getValue.fetchUpperBoundOffset(startOffsetPosition, fetchSize).getOrElse {
      segments.higherSegment(segmentEntry.getKey).map(_.baseOffset).getOrElse(logEndOffset)
    }

    val abortedTransactions = ListBuffer.empty[FetchResponseData.AbortedTransaction]
    def accumulator(abortedTxns: List[AbortedTxn]): Unit = abortedTransactions ++= abortedTxns.map(_.asAbortedTransaction)
    collectAbortedTransactions(startOffset, upperBoundOffset, segmentEntry, accumulator)

    FetchDataInfo(fetchOffsetMetadata = fetchInfo.fetchOffsetMetadata,
      records = fetchInfo.records,
      firstEntryIncomplete = fetchInfo.firstEntryIncomplete,
      abortedTransactions = Some(abortedTransactions.toList))
  }

  private def collectAbortedTransactions(startOffset: Long, upperBoundOffset: Long,
                                         startingSegmentEntry: JEntry[JLong, LogSegment],
                                         accumulator: List[AbortedTxn] => Unit): Unit = {
    var segmentEntryOpt = Option(startingSegmentEntry)
    while (segmentEntryOpt.isDefined) {
      val baseOffset = segmentEntryOpt.get.getKey
      val segment = segmentEntryOpt.get.getValue
      val searchResult = segment.collectAbortedTxns(startOffset, upperBoundOffset)
      accumulator(searchResult.abortedTransactions)
      if (searchResult.isComplete)
        return
      segmentEntryOpt = segments.higherEntry(baseOffset)
    }
  }

  private[log] def collectAbortedTransactions(logStartOffset: Long, baseOffset: Long, upperBoundOffset: Long): List[AbortedTxn] = {
    val segmentEntryOpt = segments.floorEntry(baseOffset)
    val allAbortedTxns = ListBuffer.empty[AbortedTxn]
    def accumulator(abortedTxns: List[AbortedTxn]): Unit = allAbortedTxns ++= abortedTxns
    collectAbortedTransactions(logStartOffset, upperBoundOffset, segmentEntryOpt.get, accumulator)
    allAbortedTxns.toList
  }

  /**
   * Roll the log over to a new empty log segment if necessary.
   * The segment will be rolled if one of the following conditions met:
   * 1. The logSegment is full
   * 2. The maxTime has elapsed since the timestamp of first message in the segment (or since the
   *    create time if the first message does not have a timestamp)
   * 3. The index is full
   *
   * @param messagesSize The messages set size in bytes.
   * @param appendInfo log append information
   * @param rollAction The pre/post roll actions to be performed
   *
   * @return  The currently active segment after (perhaps) rolling to a new segment
   */
  private[log] def maybeRoll(messagesSize: Int, appendInfo: LogAppendInfo, rollAction: RollAction): LogSegment = {
    val segment = segments.activeSegment
    val now = time.milliseconds

    val maxTimestampInMessages = appendInfo.maxTimestamp
    val maxOffsetInMessages = appendInfo.lastOffset

    if (segment.shouldRoll(RollParams(config, appendInfo, messagesSize, now))) {
      debug(s"Rolling new log segment (log_size = ${segment.size}/${config.segmentSize}}, " +
        s"offset_index_size = ${segment.offsetIndex.entries}/${segment.offsetIndex.maxEntries}, " +
        s"time_index_size = ${segment.timeIndex.entries}/${segment.timeIndex.maxEntries}, " +
        s"inactive_time_ms = ${segment.timeWaitedForRoll(now, maxTimestampInMessages)}/${config.segmentMs - segment.rollJitterMs}).")

      /*
        maxOffsetInMessages - Integer.MAX_VALUE is a heuristic value for the first offset in the set of messages.
        Since the offset in messages will not differ by more than Integer.MAX_VALUE, this is guaranteed <= the real
        first offset in the set. Determining the true first offset in the set requires decompression, which the follower
        is trying to avoid during log append. Prior behavior assigned new baseOffset = logEndOffset from old segment.
        This was problematic in the case that two consecutive messages differed in offset by
        Integer.MAX_VALUE.toLong + 2 or more.  In this case, the prior behavior would roll a new log segment whose
        base offset was too low to contain the next message.  This edge case is possible when a replica is recovering a
        highly compacted topic from scratch.
        Note that this is only required for pre-V2 message formats because these do not store the first message offset
        in the header.
      */
      val rollOffset = appendInfo
        .firstOffset
        .map(_.messageOffset)
        .getOrElse(maxOffsetInMessages - Integer.MAX_VALUE)

      roll(Some(rollOffset), rollAction)
    } else {
      segment
    }
  }

  private def logTruncationSegmentDeletionReason(toDelete: Iterable[LogSegment]): () => Unit = {
    () => {
      info(s"Deleting segments as part of log truncation: ${toDelete.mkString(",")}")
    }
  }

  /**
   * Roll the log over to a new active segment starting with the current logEndOffset.
   * This will trim the index to the exact size of the number of entries it currently contains.
   *
   * @return The newly rolled segment
   */
  private[log] def roll(expectedNextOffset: Option[Long] = None, rollAction: RollAction): LogSegment = {
    maybeHandleIOException(s"Error while rolling log segment for $topicPartition in dir ${dir.getParent}") {
      val start = time.hiResClockMs()
      checkIfMemoryMappedBufferClosed()
      val newOffset = math.max(expectedNextOffset.getOrElse(0L), logEndOffset)
      val logFile = Log.logFile(dir, newOffset)
      val activeSegment = segments.activeSegment
      val deletedSegmentOpt: Option[LogSegment] = {
        if (segments.contains(newOffset)) {
          // segment with the same base offset already exists and loaded
          if (activeSegment.baseOffset == newOffset && activeSegment.size == 0) {
            // We have seen this happen (see KAFKA-6388) after shouldRoll() returns true for an
            // active segment of size zero because of one of the indexes is "full" (due to _maxEntries == 0).
            warn(s"Trying to roll a new log segment with start offset $newOffset " +
              s"=max(provided offset = $expectedNextOffset, LEO = $logEndOffset) while it already " +
              s"exists and is active with size 0. Size of time index: ${activeSegment.timeIndex.entries}," +
              s" size of offset index: ${activeSegment.offsetIndex.entries}.")
            val toDelete = Seq(activeSegment)
            removeAndDeleteSegments(
              toDelete,
              asyncDelete = true,
              () => {
                info(s"Deleting segments as part of log roll: ${toDelete.mkString(",")}")
              })
            Some(toDelete.head)
          } else {
            throw new KafkaException(s"Trying to roll a new log segment for topic partition $topicPartition with start offset $newOffset" +
              s" =max(provided offset = $expectedNextOffset, LEO = $logEndOffset) while it already exists. Existing " +
              s"segment is ${segments.get(newOffset)}.")
          }
        } else if (!segments.isEmpty && newOffset < activeSegment.baseOffset) {
          throw new KafkaException(
            s"Trying to roll a new log segment for topic partition $topicPartition with " +
              s"start offset $newOffset =max(provided offset = $expectedNextOffset, LEO = $logEndOffset) lower than start offset of the active segment $activeSegment")
        } else {
          val offsetIdxFile = offsetIndexFile(dir, newOffset)
          val timeIdxFile = timeIndexFile(dir, newOffset)
          val txnIdxFile = transactionIndexFile(dir, newOffset)

          for (file <- List(logFile, offsetIdxFile, timeIdxFile, txnIdxFile) if file.exists) {
            warn(s"Newly rolled segment file ${file.getAbsolutePath} already exists; deleting it first")
            Files.delete(file.toPath)
          }

          segments.lastSegment.foreach(_.onBecomeInactiveSegment())
          Option.empty
        }
      }

      val segment = LogSegment.open(dir,
        baseOffset = newOffset,
        config,
        time = time,
        initFileSize = config.initFileSize,
        preallocate = config.preallocate)
      rollAction.preRollAction(segment)
      segments.add(segment)

      // We need to update the segment base offset and append position data of the metadata when log rolls.
      // The next offset should not change.
      updateLogEndOffset(nextOffsetMetadata.messageOffset)

      info(s"Rolled new log segment at offset $newOffset in ${time.hiResClockMs() - start} ms.")

      rollAction.postRollAction(segment, deletedSegmentOpt)

      segment
    }
  }

  /**
   *  Delete all data in the log and start at the new offset.
   *
   *  @param newOffset The new offset to start the log with
   *  @return the list of segments that were scheduled for deletion
   */
  private[log] def truncateFullyAndStartAt(newOffset: Long): Iterable[LogSegment] = {
    maybeHandleIOException(s"Error while truncating the entire log for $topicPartition in dir ${dir.getParent}") {
      debug(s"Truncate and start at offset $newOffset")
      checkIfMemoryMappedBufferClosed()
      val segmentsToDelete = segments.values
      removeAndDeleteSegments(segmentsToDelete, asyncDelete = true, logTruncationSegmentDeletionReason(segmentsToDelete))
      segments.add(LogSegment.open(dir,
        baseOffset = newOffset,
        config = config,
        time = time,
        initFileSize = config.initFileSize,
        preallocate = config.preallocate))
      segmentsToDelete
    }
  }

  /**
   * Truncate this log so that it ends with the greatest offset < targetOffset.
   *
   * @param targetOffset The offset to truncate to, an upper bound on all offsets in the log after truncation is complete.
   */
  private[log] def truncateTo(targetOffset: Long): Iterable[LogSegment] = {
    val deletableSegments = segments.filter(segment => segment.baseOffset > targetOffset)
    removeAndDeleteSegments(deletableSegments, asyncDelete = true, logTruncationSegmentDeletionReason(deletableSegments))
    segments.activeSegment.truncateTo(targetOffset)
    deletableSegments
  }
}

object LocalLog extends Logging {

  /** a log file */
  private[log] val LogFileSuffix = ".log"

  /** an index file */
  private[log] val IndexFileSuffix = ".index"

  /** a time index file */
  private[log] val TimeIndexFileSuffix = ".timeindex"

  /** an (aborted) txn index */
  private[log] val TxnIndexFileSuffix = ".txnindex"

  /** a file that is scheduled to be deleted */
  private[log] val DeletedFileSuffix = ".deleted"

  /** A temporary file that is being used for log cleaning */
  private[log] val CleanedFileSuffix = ".cleaned"

  /** A temporary file used when swapping files into the log */
  private[log] val SwapFileSuffix = ".swap"

  /** a directory that is scheduled to be deleted */
  private[log] val DeleteDirSuffix = "-delete"

  /** a directory that is used for future partition */
  private[log] val FutureDirSuffix = "-future"

  private[log] val DeleteDirPattern = Pattern.compile(s"^(\\S+)-(\\S+)\\.(\\S+)$DeleteDirSuffix")
  private[log] val FutureDirPattern = Pattern.compile(s"^(\\S+)-(\\S+)\\.(\\S+)$FutureDirSuffix")

  private[log] val UnknownOffset = -1L

  /**
   * Make log segment file name from offset bytes. All this does is pad out the offset number with zeros
   * so that ls sorts the files numerically.
   *
   * @param offset The offset to use in the file name
   * @return The filename
   */
  private[log] def filenamePrefixFromOffset(offset: Long): String = {
    val nf = NumberFormat.getInstance()
    nf.setMinimumIntegerDigits(20)
    nf.setMaximumFractionDigits(0)
    nf.setGroupingUsed(false)
    nf.format(offset)
  }

  private[log] def logFile(dir: File, offset: Long, suffix: String = ""): File =
    new File(dir, filenamePrefixFromOffset(offset) + LogFileSuffix + suffix)

  private[log] def logDeleteDirName(topicPartition: TopicPartition): String = {
    val uniqueId = java.util.UUID.randomUUID.toString.replaceAll("-", "")
    val suffix = s"-${topicPartition.partition()}.${uniqueId}${DeleteDirSuffix}"
    val prefixLength = Math.min(topicPartition.topic().size, 255 - suffix.size)
    s"${topicPartition.topic().substring(0, prefixLength)}${suffix}"
  }

  private[log] def logFutureDirName(topicPartition: TopicPartition): String = {
    logDirNameWithSuffix(topicPartition, FutureDirSuffix)
  }

  private[log] def logDirNameWithSuffix(topicPartition: TopicPartition, suffix: String): String = {
    val uniqueId = java.util.UUID.randomUUID.toString.replaceAll("-", "")
    s"${logDirName(topicPartition)}.$uniqueId$suffix"
  }

  private[log] def logDirName(topicPartition: TopicPartition): String = {
    s"${topicPartition.topic}-${topicPartition.partition}"
  }

  private[log] def offsetIndexFile(dir: File, offset: Long, suffix: String = ""): File =
    new File(dir, filenamePrefixFromOffset(offset) + IndexFileSuffix + suffix)

  private[log] def timeIndexFile(dir: File, offset: Long, suffix: String = ""): File =
    new File(dir, filenamePrefixFromOffset(offset) + TimeIndexFileSuffix + suffix)

  private[log] def transactionIndexFile(dir: File, offset: Long, suffix: String = ""): File =
    new File(dir, filenamePrefixFromOffset(offset) + TxnIndexFileSuffix + suffix)

  private[log] def offsetFromFileName(filename: String): Long = {
    filename.substring(0, filename.indexOf('.')).toLong
  }

  private[log] def offsetFromFile(file: File): Long = {
    offsetFromFileName(file.getName)
  }

  private[log] def parseTopicPartitionName(dir: File): TopicPartition = {
    if (dir == null)
      throw new KafkaException("dir should not be null")

    def exception(dir: File): KafkaException = {
      new KafkaException(s"Found directory ${dir.getCanonicalPath}, '${dir.getName}' is not in the form of " +
        "topic-partition or topic-partition.uniqueId-delete (if marked for deletion).\n" +
        "Kafka's log directories (and children) should only contain Kafka topic data.")
    }

    val dirName = dir.getName
    if (dirName == null || dirName.isEmpty || !dirName.contains('-'))
      throw exception(dir)
    if (dirName.endsWith(DeleteDirSuffix) && !DeleteDirPattern.matcher(dirName).matches ||
      dirName.endsWith(FutureDirSuffix) && !FutureDirPattern.matcher(dirName).matches)
      throw exception(dir)

    val name: String =
      if (dirName.endsWith(DeleteDirSuffix) || dirName.endsWith(FutureDirSuffix)) dirName.substring(0, dirName.lastIndexOf('.'))
      else dirName

    val index = name.lastIndexOf('-')
    val topic = name.substring(0, index)
    val partitionString = name.substring(index + 1)
    if (topic.isEmpty || partitionString.isEmpty)
      throw exception(dir)

    val partition =
      try partitionString.toInt
      catch { case _: NumberFormatException => throw exception(dir) }

    new TopicPartition(topic, partition)
  }

  private[log] def isIndexFile(file: File): Boolean = {
    val filename = file.getName
    filename.endsWith(IndexFileSuffix) || filename.endsWith(TimeIndexFileSuffix) || filename.endsWith(TxnIndexFileSuffix)
  }

  private[log] def isLogFile(file: File): Boolean =
    file.getPath.endsWith(LogFileSuffix)

  /**
   * Invokes the provided function and handles any IOException raised by the function by marking the
   * provided directory offline.
   *
   * @param logDirFailureChannel Used to asynchronously handle log directory failure.
   * @param logDir The log directory to be marked offline during an IOException.
   * @param errorMsg The error message to be used when marking the log directory offline.
   * @param fun The function to be executed.
   * @return The value returned by the function after a successful invocation
   */
  private[log] def maybeHandleIOException[T](logDirFailureChannel: LogDirFailureChannel,
                                        logDir: String,
                                        errorMsg: => String)(fun: => T): T = {
    if (logDirFailureChannel.hasOfflineLogDir(logDir)) {
      throw new KafkaStorageException(s"The log dir $logDir is already offline due to a previous IO exception.")
    }
    try {
      fun
    } catch {
      case e: IOException =>
        logDirFailureChannel.maybeAddOfflineLogDir(logDir, errorMsg, e)
        throw new KafkaStorageException(errorMsg, e)
    }
  }

  /**
   * Split a segment into one or more segments such that there is no offset overflow in any of them. The
   * resulting segments will contain the exact same messages that are present in the input segment. On successful
   * completion of this method, the input segment will be deleted and will be replaced by the resulting new segments.
   * See replaceSegments for recovery logic, in case the broker dies in the middle of this operation.
   *
   * Note that this method assumes we have already determined that the segment passed in contains records that cause
   * offset overflow.
   *
   * The split logic overloads the use of .clean files that LogCleaner typically uses to make the process of replacing
   * the input segment with multiple new segments atomic and recoverable in the event of a crash. See replaceSegments
   * and completeSwapOperations for the implementation to make this operation recoverable on crashes.</p>
   *
   * @param segment Segment to split
   * @param existingSegments The existing segments of the log
   * @param dir The directory in which the log will reside
   * @param topicPartition The topic
   * @param config The log configuration settings
   * @param scheduler The thread pool scheduler used for background actions
   * @param logDirFailureChannel The LogDirFailureChannel to asynchronously handle log dir failure
   * @param producerStateManager The ProducerStateManager instance (if any) containing state associated
   *                             with the existingSegments
   * @return List of new segments that replace the input segment
   */
  private[log] def splitOverflowedSegment(segment: LogSegment,
                                          existingSegments: LogSegments,
                                          dir: File,
                                          topicPartition: TopicPartition,
                                          config: LogConfig,
                                          scheduler: Scheduler,
                                          logDirFailureChannel: LogDirFailureChannel,
                                          producerStateManager: ProducerStateManager): SplitSegmentResult = {
    require(Log.isLogFile(segment.log.file), s"Cannot split file ${segment.log.file.getAbsoluteFile}")
    require(segment.hasOverflow, "Split operation is only permitted for segments with overflow")

    info(s"Splitting overflowed segment $segment")

    val newSegments = ListBuffer[LogSegment]()
    try {
      var position = 0
      val sourceRecords = segment.log

      while (position < sourceRecords.sizeInBytes) {
        val firstBatch = sourceRecords.batchesFrom(position).asScala.head
        val newSegment = LogCleaner.createNewCleanedSegment(dir, config, firstBatch.baseOffset)
        newSegments += newSegment

        val bytesAppended = newSegment.appendFromFile(sourceRecords, position)
        if (bytesAppended == 0)
          throw new IllegalStateException(s"Failed to append records from position $position in $segment")

        position += bytesAppended
      }

      // prepare new segments
      var totalSizeOfNewSegments = 0
      newSegments.foreach { splitSegment =>
        splitSegment.onBecomeInactiveSegment()
        splitSegment.flush()
        splitSegment.lastModified = segment.lastModified
        totalSizeOfNewSegments += splitSegment.log.sizeInBytes
      }
      // size of all the new segments combined must equal size of the original segment
      if (totalSizeOfNewSegments != segment.log.sizeInBytes)
        throw new IllegalStateException("Inconsistent segment sizes after split" +
          s" before: ${segment.log.sizeInBytes} after: $totalSizeOfNewSegments")

      // replace old segment with new ones
      info(s"Replacing overflowed segment $segment with split segments $newSegments")
      val newSegmentsToAdd = newSegments.toSeq
      val deletedSegments = LocalLog.replaceSegments(existingSegments, newSegmentsToAdd, List(segment),
        dir, topicPartition, config, scheduler, logDirFailureChannel)
      SplitSegmentResult(deletedSegments.toSeq, newSegmentsToAdd)
    } catch {
      case e: Exception =>
        newSegments.foreach { splitSegment =>
          splitSegment.close()
          splitSegment.deleteIfExists()
        }
        throw e
    }
  }

  /**
   * Swap one or more new segment in place and delete one or more existing segments in a crash-safe
   * manner. The old segments will be asynchronously deleted.
   *
   * This method does not need to convert IOException to KafkaStorageException because it is either
   * called before all logs are loaded or the caller will catch and handle IOException
   *
   * The sequence of operations is:
   *
   * - Cleaner creates one or more new segments with suffix .cleaned and invokes replaceSegments() on
   *   the Log instance. If broker crashes at this point, the clean-and-swap operation is aborted and
   *   the .cleaned files are deleted on recovery in LogLoader.
   * - New segments are renamed .swap. If the broker crashes before all segments were renamed to .swap, the
   *   clean-and-swap operation is aborted - .cleaned as well as .swap files are deleted on recovery in
   *   in LogLoader. We detect this situation by maintaining a specific order in which files are renamed
   *   from .cleaned to .swap. Basically, files are renamed in descending order of offsets. On recovery,
   *   all .swap files whose offset is greater than the minimum-offset .clean file are deleted.
   * - If the broker crashes after all new segments were renamed to .swap, the operation is completed,
   *   the swap operation is resumed on recovery as described in the next step.
   * - Old segment files are renamed to .deleted and asynchronous delete is scheduled. If the broker
   *   crashes, any .deleted files left behind are deleted on recovery in LogLoader.
   *   replaceSegments() is then invoked to complete the swap with newSegment recreated from the
   *   .swap file and oldSegments containing segments which were not renamed before the crash.
   * - Swap segment(s) are renamed to replace the existing segments, completing this operation.
   *   If the broker crashes, any .deleted files which may be left behind are deleted
   *   on recovery in LogLoader.
   *
   * @param existingSegments The existing segments of the log
   * @param newSegments The new log segment to add to the log
   * @param oldSegments The old log segments to delete from the log
   * @param dir The directory in which the log will reside
   * @param topicPartition The topic
   * @param config The log configuration settings
   * @param scheduler The thread pool scheduler used for background actions
   * @param logDirFailureChannel The LogDirFailureChannel to asynchronously handle log dir failure
   * @param isRecoveredSwapFile true if the new segment was created from a swap file during recovery after a crash
   */
  private[log] def replaceSegments(existingSegments: LogSegments,
                                   newSegments: Seq[LogSegment],
                                   oldSegments: Seq[LogSegment],
                                   dir: File,
                                   topicPartition: TopicPartition,
                                   config: LogConfig,
                                   scheduler: Scheduler,
                                   logDirFailureChannel: LogDirFailureChannel,
                                   isRecoveredSwapFile: Boolean = false): Iterable[LogSegment] = {
    val sortedNewSegments = newSegments.sortBy(_.baseOffset)
    // Some old segments may have been removed from index and scheduled for async deletion after the caller reads segments
    // but before this method is executed. We want to filter out those segments to avoid calling asyncDeleteSegment()
    // multiple times for the same segment.
    val sortedOldSegments = oldSegments.filter(seg => existingSegments.contains(seg.baseOffset)).sortBy(_.baseOffset)

    // need to do this in two phases to be crash safe AND do the delete asynchronously
    // if we crash in the middle of this we complete the swap in loadSegments()
    if (!isRecoveredSwapFile)
      sortedNewSegments.reverse.foreach(_.changeFileSuffixes(Log.CleanedFileSuffix, Log.SwapFileSuffix, needsFlushParentDir = false))
    sortedNewSegments.reverse.foreach(existingSegments.add(_))
    val newSegmentBaseOffsets = sortedNewSegments.map(_.baseOffset).toSet

    // delete the old files
    val deletedNotReplaced = sortedOldSegments.map { seg =>
      // remove the index entry
      if (seg.baseOffset != sortedNewSegments.head.baseOffset)
        existingSegments.remove(seg.baseOffset)
      deleteSegmentFiles(
        List(seg),
        asyncDelete = true,
        dir,
        topicPartition,
        config,
        scheduler,
        logDirFailureChannel)
      if (newSegmentBaseOffsets.contains(seg.baseOffset)) Option.empty else Some(seg)
    }.filter(item => item.isDefined).map(item => item.get)

    // okay we are safe now, remove the swap suffix
    sortedNewSegments.foreach(_.changeFileSuffixes(Log.SwapFileSuffix, ""))
    deletedNotReplaced
  }

  /**
   * Perform physical deletion of the index and log files for the given segment.
   * Prior to the deletion, the index and log files are renamed by appending .deleted to the
   * respective file name. Allows these files to be optionally deleted asynchronously.
   *
   * This method assumes that the file exists. It does not need to convert IOException
   * (thrown from changeFileSuffixes) to KafkaStorageException because it is either called before
   * all logs are loaded or the caller will catch and handle IOException.
   *
   * @param segmentsToDelete The segments to be deleted
   * @param asyncDelete If true, the deletion of the segments is done asynchronously
   * @param dir The directory in which the log will reside
   * @param topicPartition The topic
   * @param config The log configuration settings
   * @param scheduler The thread pool scheduler used for background actions
   * @param logDirFailureChannel The LogDirFailureChannel to asynchronously handle log dir failure
   *
   * @throws IOException if the file can't be renamed and still exists
   */
  private[log] def deleteSegmentFiles(segmentsToDelete: Iterable[LogSegment],
                                      asyncDelete: Boolean,
                                      dir: File,
                                      topicPartition: TopicPartition,
                                      config: LogConfig,
                                      scheduler: Scheduler,
                                      logDirFailureChannel: LogDirFailureChannel): Unit = {
    segmentsToDelete.foreach(_.changeFileSuffixes("", Log.DeletedFileSuffix, needsFlushParentDir = false))

    def deleteSegments(): Unit = {
      info(s"Deleting segment files ${segmentsToDelete.mkString(",")}")
      val parentDir = dir.getParent
      maybeHandleIOException(logDirFailureChannel, parentDir, s"Error while deleting segments for $topicPartition in dir $parentDir") {
        segmentsToDelete.foreach { segment =>
          segment.deleteIfExists()
        }
      }
    }

    if (asyncDelete)
      scheduler.schedule("delete-file", () => deleteSegments(), delay = config.fileDeleteDelayMs)
    else
      deleteSegments()
  }

  private[log] def emptyFetchDataInfo(fetchOffsetMetadata: LogOffsetMetadata,
                                      includeAbortedTxns: Boolean): FetchDataInfo = {
    val abortedTransactions =
      if (includeAbortedTxns) Some(List.empty[FetchResponseData.AbortedTransaction])
      else None
    FetchDataInfo(fetchOffsetMetadata,
      MemoryRecords.EMPTY,
      abortedTransactions = abortedTransactions)
  }
}
