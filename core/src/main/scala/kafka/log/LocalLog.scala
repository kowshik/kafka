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
import java.lang.{Long => JLong}
import java.nio.file.{Files, NoSuchFileException}
import java.text.NumberFormat
import java.util.concurrent.{ConcurrentNavigableMap, ConcurrentSkipListMap}
import java.util.concurrent.atomic.AtomicLong
import java.util.Map.{Entry => JEntry}
import java.util.regex.Pattern

import kafka.common.LogSegmentOffsetOverflowException
import kafka.metrics.KafkaMetricsGroup
import kafka.server.epoch.LeaderEpochFileCache
import kafka.server.{FetchDataInfo, LogDirFailureChannel, LogOffsetMetadata}
import kafka.utils.{CoreUtils, Logging, Scheduler, threadsafe}
import org.apache.kafka.common.{KafkaException, TopicPartition}
import org.apache.kafka.common.errors.{InvalidOffsetException, KafkaStorageException, OffsetOutOfRangeException}
import org.apache.kafka.common.message.FetchResponseData
import org.apache.kafka.common.record.{MemoryRecords, RecordBatch, RecordVersion, Records}
import org.apache.kafka.common.utils.{Time, Utils}

import scala.jdk.CollectionConverters._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.collection.{Seq, Set, mutable}

// Used to define pre/post roll actions to be performed.
case class RollAction(preRollAction: Long => Unit, postRollAction: (LogSegment, Option[LogSegment]) => Unit)

// Used to hold the result of splitting a segment into one or more segments, see LocalLog#splitOverflowedSegment
case class SplitSegmentResult(deletedSegments: Seq[LogSegment], newSegments: Seq[LogSegment])

/**
 * An append-only log for storing messages locally. The log is a sequence of LogSegments, each with a base offset.
 * New log segments are created according to a configurable policy that controls the size in bytes or time interval
 * for a given segment.
 *
 * NOTE: this class is not thread-safe, and it relies on the thread safety provided by the Log class.
 *
 * @param _dir The directory in which log segments are created.
 * @param config The log configuration settings
 * @param recoveryPoint The offset at which to begin recovery i.e. the first offset which has not been flushed to disk
 * @param scheduler The thread pool scheduler used for background actions
 * @param time The time instance used for checking the clock
 * @param topicPartition The topic partition associated with this log
 * @param logDirFailureChannel The LogDirFailureChannel instance to asynchronously handle Log dir failure
 * @param hadCleanShutdown boolean flag to indicate if the Log had a clean/graceful shutdown last time. true means
 *                         clean shutdown whereas false means a crash.
 */
class LocalLog(@volatile private var _dir: File,
               @volatile var config: LogConfig,
               @volatile var recoveryPoint: Long,
               scheduler: Scheduler,
               val time: Time,
               val topicPartition: TopicPartition,
               logDirFailureChannel: LogDirFailureChannel,
               private val hadCleanShutdown: Boolean = true) extends Logging with KafkaMetricsGroup {

  import kafka.log.LocalLog._

  this.logIdent = s"[Log partition=$topicPartition, dir=${dir.getParent}] "

  // The memory mapped buffer for index files of this log will be closed with either delete() or closeHandlers()
  // After memory mapped buffer is closed, no disk IO operation should be performed for this log
  @volatile private var isMemoryMappedBufferClosed = false

  // Cache value of parent directory to avoid allocations in hot paths like ReplicaManager.checkpointHighWatermarks
  @volatile private var _parentDir: String = dir.getParent

  /* last time the log was flushed */
  private val lastFlushedTime = new AtomicLong(time.milliseconds)

  // The offset where the next message could be appended
  @volatile private var nextOffsetMetadata: LogOffsetMetadata = _

  // Log dir failure is handled asynchronously we need to prevent threads
  // from reading inconsistent state caused by a failure in another thread
  @volatile private[log] var logDirOffline = false

  // The actual segments of the log
  private val segments: ConcurrentNavigableMap[java.lang.Long, LogSegment] = new ConcurrentSkipListMap[java.lang.Long, LogSegment]

  locally {
    // Create the log directory if it doesn't exist
    Files.createDirectories(dir.toPath)
  }

  private[log] def dir: File = _dir

  private[log] def name = dir.getName()

  private[log] def parentDir: String = _parentDir

  private[log] def parentDirFile: File = new File(_parentDir)

  private[log] def isFuture: Boolean = dir.getName.endsWith(FutureDirSuffix)

  private[log] def initFileSize: Int = {
    if (config.preallocate)
      config.segmentSize
    else
      0
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
        logSegments.foreach(_.updateParentDir(renamedDir))
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

  private[log] def checkForLogDirFailure(): Unit = {
    if (logDirOffline) {
      throw new KafkaStorageException(s"The log dir $parentDir is offline due to a previous IO exception.")
    }
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
    nextOffsetMetadata = LogOffsetMetadata(endOffset, activeSegment.baseOffset, activeSegment.size)
    if (recoveryPoint > endOffset) {
      updateRecoveryPoint(endOffset)
    }
  }

  /**
   * @return the base offset of the first local segment, if it exists
   */
  private[log] def firstSegmentBaseOffset: Option[Long] = Option(segments.firstEntry).map(_.getValue.baseOffset)

  /**
   * The active segment that is currently taking appends
   */
  private[log] def activeSegment = segments.lastEntry.getValue

  /**
   * The number of segments in the log.
   * Take care! this is an O(n) operation.
   */
  private[log] def numberOfSegments: Int = segments.size

  /**
   * The size of the log in bytes
   */
  private[log] def size: Long = LocalLog.sizeInBytes(logSegments)

  /**
   * All the log segments in this log ordered from oldest to newest
   */
  private[log] def logSegments: Iterable[LogSegment] = segments.values.asScala

  /**
   * Get all segments beginning with the segment that includes "from" and ending with the segment
   * that includes up to "to-1" or the end of the log (if to > logEndOffset).
   */
  private[log] def logSegments(from: Long, to: Long): Iterable[LogSegment] = {
    if (from == to) {
      // Handle non-segment-aligned empty sets
      List.empty[LogSegment]
    } else if (to < from) {
      throw new IllegalArgumentException(s"Invalid log segment range: requested segments in $topicPartition " +
        s"from offset $from which is greater than limit offset $to")
    } else {
      val view = Option(segments.floorKey(from)).map { floor =>
        segments.subMap(floor, to)
      }.getOrElse(segments.headMap(to))
      view.values.asScala
    }
  }

  /**
   * Return all non-active log segments beginning with the segment that includes "from".
   *
   * @param from the from offset
   */
  private[log] def nonActiveLogSegmentsFrom(from: Long): Iterable[LogSegment] = {
    if (from > activeSegment.baseOffset)
      Seq.empty
    else
      logSegments(from, activeSegment.baseOffset)
  }

  private  def recordVersion: RecordVersion = config.messageFormatVersion.recordVersion

  private  def lowerSegment(offset: Long): Option[LogSegment] =
    Option(segments.lowerEntry(offset)).map(_.getValue)

  /**
   * Get the largest log segment with a base offset less than or equal to the given offset, if one exists.
   * @return the optional log segment
   */
  private def floorLogSegment(offset: Long): Option[LogSegment] = {
    Option(segments.floorEntry(offset)).map(_.getValue)
  }

  /**
   * Add the given segment to the segments in this log.
   * @param segment The segment to add
   */
  @threadsafe
  private[log] def addSegment(segment: LogSegment): LogSegment = segments.put(segment.baseOffset, segment)

  /**
   * Close file handlers used by log but don't write to disk. This is called if the log directory is offline
   */
  private[log] def closeHandlers(): Unit = {
    logSegments.foreach(_.closeHandlers())
    isMemoryMappedBufferClosed = true
  }

  /**
   * Closes the log.
   */
  private[log] def close(): Unit = {
    maybeHandleIOException(s"Error while deleting log for $topicPartition in dir ${dir.getParent}") {
      checkIfMemoryMappedBufferClosed()
      logSegments.foreach(_.close())
    }
  }

  /**
   * Completely delete this log directory and all contents from the file system with no delay
   */
  private[log] def delete(): Seq[LogSegment] = {
    maybeHandleIOException(s"Error while deleting log for $topicPartition in dir ${dir.getParent}") {
      checkIfMemoryMappedBufferClosed()
      val deleted = logSegments.toSeq
      removeAndDeleteSegments(logSegments, asyncDelete = false, LogDeletion)
      Utils.delete(dir)
      // File handlers will be closed if this log is deleted
      isMemoryMappedBufferClosed = true
      deleted
    }
  }

  /**
   * Load the log segments from the log files on disk and update the next offset.
   * This method does not need to convert IOException to KafkaStorageException because it is usually called before all logs
   * are loaded.
   *
   * @param logStartOffset the log start offset
   * @param maxProducerIdExpirationMs The maximum amount of time to wait before a producer id is considered expired
   * @param producerStateManager The ProducerStateManager instance
   * @param leaderEpochCache The LeaderEpochFileCache instance
   *
   * @return the list of deleted segments
   *
   * @throws LogSegmentOffsetOverflowException if we encounter a .swap file with messages that overflow index offset; or when
   *                                           we find an unexpected number of .log files with overflow
   */
  private[log] def loadSegments(logStartOffset: Long,
                                maxProducerIdExpirationMs: Int,
                                producerStateManager: ProducerStateManager,
                                leaderEpochCache: Option[LeaderEpochFileCache]): Seq[LogSegment] = {
    // first do a pass through the files in the log directory and remove any temporary files
    // and find any interrupted swap operations
    val swapFiles = removeTempFilesAndCollectSwapFiles()

    // Now do a second pass and load all the log and index files.
    // We might encounter legacy log segments with offset overflow (KAFKA-6264). We need to split such segments. When
    // this happens, restart loading segment files from scratch.
    retryOnOffsetOverflow({
      // In case we encounter a segment with offset overflow, the retry logic will split it after which we need to retry
      // loading of segments. In that case, we also need to close all segments that could have been left open in previous
      // call to loadSegmentFiles().
      logSegments.foreach(_.close())
      segments.clear()
      loadSegmentFiles(logStartOffset, maxProducerIdExpirationMs)
    })

    val deletedSegments = ListBuffer[LogSegment]()

    // Finally, complete any interrupted swap operations. To be crash-safe,
    // log files that are replaced by the swap segment should be renamed to .deleted
    // before the swap file is restored as the new segment file.
    deletedSegments ++= completeSwapOperations(swapFiles, logStartOffset, maxProducerIdExpirationMs)

    if (!dir.getAbsolutePath.endsWith(DeleteDirSuffix)) {
      val (deleted, nextOffset) = retryOnOffsetOverflow(
        {
          recoverLog(logStartOffset,
                     maxProducerIdExpirationMs,
                     producerStateManager,
                     leaderEpochCache)
        })
      deletedSegments ++= deleted

      // reset the index size of the currently active log segment to allow more entries
      activeSegment.resizeIndexes(config.maxIndexSize)
      updateLogEndOffset(nextOffset)
    } else {
      if (logSegments.isEmpty) {
        addSegment(LogSegment.open(dir = dir,
          baseOffset = 0,
          config,
          time = time,
          initFileSize = this.initFileSize))
      }
      updateLogEndOffset(0)
    }
    deletedSegments.toSeq
  }

  /**
   * Recovers the log segments, and returns the segments deleted along with the next offset after
   * recovery. This method does not need to convert IOException to KafkaStorageException because it
   * is usually called before all logs are loaded.
   *
   * @param logStartOffset the log start offset
   * @param maxProducerIdExpirationMs The maximum amount of time to wait before a producer id is considered expired
   * @param producerStateManager The ProducerStateManager instance
   * @param leaderEpochCache The LeaderEpochFileCache instance
   *
   * @return the list of deleted segments and the next offset
   *
   * @throws LogSegmentOffsetOverflowException if we encountered a legacy segment with offset overflow
   */
  private def recoverLog(logStartOffset: Long,
                              maxProducerIdExpirationMs: Int,
                              producerStateManager: ProducerStateManager,
                              leaderEpochCache: Option[LeaderEpochFileCache]): (List[LogSegment], Long) = {
    val deleted = scala.collection.mutable.ListBuffer[LogSegment]()
    /** return the log end offset if valid */
    def deleteSegmentsIfLogStartGreaterThanLogEnd(): Option[Long] = {
      if (logSegments.nonEmpty) {
        val logEndOffset = activeSegment.readNextOffset
        if (logEndOffset >= logStartOffset)
          Some(logEndOffset)
        else {
          warn(s"Deleting all segments because logEndOffset ($logEndOffset) is smaller than logStartOffset ($logStartOffset). " +
            "This could happen if segment files were deleted from the file system.")
          val toDelete = logSegments.toList
          removeAndDeleteSegments(logSegments, asyncDelete = true, LogRecovery)
          deleted ++= toDelete
          leaderEpochCache.foreach(_.clearAndFlush())
          producerStateManager.truncateFullyAndStartAt(logStartOffset)
          None
        }
      } else None
    }

    // if we have the clean shutdown marker, skip recovery
    if (!hadCleanShutdown) {
      val unflushed = logSegments(recoveryPoint, Long.MaxValue).iterator
      var truncated = false

      while (unflushed.hasNext && !truncated) {
        val segment = unflushed.next()
        info(s"Recovering unflushed segment ${segment.baseOffset}")
        val truncatedBytes =
          try {
            recoverSegment(logStartOffset, segment, maxProducerIdExpirationMs, leaderEpochCache)
          } catch {
            case _: InvalidOffsetException =>
              val startOffset = segment.baseOffset
              warn("Found invalid offset during recovery. Deleting the corrupt segment and " +
                s"creating an empty one with starting offset $startOffset")
              segment.truncateTo(startOffset)
          }
        if (truncatedBytes > 0) {
          // we had an invalid message, delete all remaining log
          warn(s"Corruption found in segment ${segment.baseOffset}, truncating to offset ${segment.readNextOffset}")
          val toDelete = unflushed.toList
          removeAndDeleteSegments(toDelete,
                                  asyncDelete = true,
                                  reason = LogRecovery)
          deleted ++= toDelete
          truncated = true
        }
      }
    }

    val logEndOffsetOption = deleteSegmentsIfLogStartGreaterThanLogEnd()

    if (logSegments.isEmpty) {
      // no existing segments, create a new mutable segment beginning at logStartOffset
      addSegment(LogSegment.open(dir = dir,
        baseOffset = logStartOffset,
        config,
        time = time,
        initFileSize = initFileSize,
        preallocate = config.preallocate))
    }

    // Update the recovery point if there was a clean shutdown and did not perform any changes to
    // the segment. Otherwise, we just ensure that the recovery point is not ahead of the log end
    // offset. To ensure correctness and to make it easier to reason about, it's best to only advance
    // the recovery point in flush(Long). If we advanced the recovery point here, we could skip recovery for
    // unflushed segments if the broker crashed after we checkpoint the recovery point and before we flush the
    // segment.
    (hadCleanShutdown, logEndOffsetOption) match {
      case (true, Some(logEndOffset)) =>
        updateRecoveryPoint(logEndOffset)
        (deleted.toList, logEndOffset)
      case _ =>
        val logEndOffset = logEndOffsetOption.getOrElse(activeSegment.readNextOffset)
        updateRecoveryPoint(Math.min(recoveryPoint, logEndOffset))
        (deleted.toList, logEndOffset)
    }
  }

  /**
   * This method does not need to convert IOException to KafkaStorageException because it is only called before all logs are loaded
   * It is possible that we encounter a segment with index offset overflow in which case the LogSegmentOffsetOverflowException
   * will be thrown. Note that any segments that were opened before we encountered the exception will remain open and the
   * caller is responsible for closing them appropriately, if needed.
   *
   * @param logStartOffset the log start offset
   * @param maxProducerIdExpirationMs The maximum amount of time to wait before a producer id is considered expired
   *
   * @throws LogSegmentOffsetOverflowException if the log directory contains a segment with messages that overflow the index offset
   */
  private def loadSegmentFiles(logStartOffset: Long, maxProducerIdExpirationMs: Int): Unit = {
    // load segments in ascending order because transactional data from one segment may depend on the
    // segments that come before it
    for (file <- dir.listFiles.sortBy(_.getName) if file.isFile) {
      if (isIndexFile(file)) {
        // if it is an index file, make sure it has a corresponding .log file
        val offset = offsetFromFile(file)
        val logFile = LocalLog.logFile(dir, offset)
        if (!logFile.exists) {
          warn(s"Found an orphaned index file ${file.getAbsolutePath}, with no corresponding log file.")
          Files.deleteIfExists(file.toPath)
        }
      } else if (isLogFile(file)) {
        // if it's a log file, load the corresponding log segment
        val baseOffset = offsetFromFile(file)
        val timeIndexFileNewlyCreated = !Log.timeIndexFile(dir, baseOffset).exists()
        val segment = LogSegment.open(dir = dir,
          baseOffset = baseOffset,
          config,
          time = time,
          fileAlreadyExists = true)

        try segment.sanityCheck(timeIndexFileNewlyCreated)
        catch {
          case _: NoSuchFileException =>
            error(s"Could not find offset index file corresponding to log file ${segment.log.file.getAbsolutePath}, " +
              "recovering segment and rebuilding index files...")
            recoverSegment(logStartOffset, segment, maxProducerIdExpirationMs)
          case e: CorruptIndexException =>
            warn(s"Found a corrupted index file corresponding to log file ${segment.log.file.getAbsolutePath} due " +
              s"to ${e.getMessage}}, recovering segment and rebuilding index files...")
            recoverSegment(logStartOffset, segment, maxProducerIdExpirationMs)
        }
        addSegment(segment)
      }
    }
  }

  /**
   * Recover the given segment.
   *
   * @param logStartOffset the log start offset
   * @param segment Segment to recover
   * @param maxProducerIdExpirationMs The maximum amount of time to wait before a producer id is considered expired
   * @param leaderEpochCache Optional cache for updating the leader epoch during recovery
   *
   * @return The number of bytes truncated from the segment
   *
   * @throws LogSegmentOffsetOverflowException if the segment contains messages that cause index offset overflow
   */
  private def recoverSegment(logStartOffset: Long,
                                  segment: LogSegment,
                                  maxProducerIdExpirationMs: Int,
                                  leaderEpochCache: Option[LeaderEpochFileCache] = None): Int = {
    val producerStateManager = new ProducerStateManager(topicPartition, dir, maxProducerIdExpirationMs)
    rebuildProducerState(logStartOffset, segment.baseOffset, reloadFromCleanShutdown = false, producerStateManager)
    val bytesTruncated = segment.recover(producerStateManager, leaderEpochCache)
    // once we have recovered the segment's data, take a snapshot to ensure that we won't
    // need to reload the same segment again while recovering another segment.
    producerStateManager.takeSnapshot()
    bytesTruncated
  }

  /**
   * This method does not need to convert IOException to KafkaStorageException because it is only called before all logs
   * are loaded.
   * @throws LogSegmentOffsetOverflowException if the swap file contains messages that cause the log segment offset to
   *                                           overflow. Note that this is currently a fatal exception as we do not have
   *                                           a way to deal with it. The exception is propagated all the way up to
   *                                           KafkaServer#startup which will cause the broker to shut down if we are in
   *                                           this situation. This is expected to be an extremely rare scenario in practice,
   *                                           and manual intervention might be required to get out of it.
   */
  private def completeSwapOperations(swapFiles: Set[File],
                                          logStartOffset: Long,
                                          maxProducerIdExpirationMs: Int): Seq[LogSegment] = {
    val deletedSegments = ListBuffer[LogSegment]()
    for (swapFile <- swapFiles) {
      val logFile = new File(CoreUtils.replaceSuffix(swapFile.getPath, SwapFileSuffix, ""))
      val baseOffset = offsetFromFile(logFile)
      val swapSegment = LogSegment.open(swapFile.getParentFile,
        baseOffset = baseOffset,
        config,
        time = time,
        fileSuffix = SwapFileSuffix)
      info(s"Found log file ${swapFile.getPath} from interrupted swap operation, repairing.")
      recoverSegment(logStartOffset, swapSegment, maxProducerIdExpirationMs)

      // We create swap files for two cases:
      // (1) Log cleaning where multiple segments are merged into one, and
      // (2) Log splitting where one segment is split into multiple.
      //
      // Both of these mean that the resultant swap segments be composed of the original set, i.e. the swap segment
      // must fall within the range of existing segment(s). If we cannot find such a segment, it means the deletion
      // of that segment was successful. In such an event, we should simply rename the .swap to .log without having to
      // do a replace with an existing segment.
      val oldSegments = logSegments(swapSegment.baseOffset, swapSegment.readNextOffset).filter { segment =>
        segment.readNextOffset > swapSegment.baseOffset
      }
      val deleted = replaceSegments(Seq(swapSegment), oldSegments.toSeq, isRecoveredSwapFile = true)
      deletedSegments ++= deleted
    }
    deletedSegments.toSeq
  }

  /**
   * Removes any temporary files found in log directory, and creates a list of all .swap files which could be swapped
   * in place of existing segment(s). For log splitting, we know that any .swap file whose base offset is higher than
   * the smallest offset .clean file could be part of an incomplete split operation. Such .swap files are also deleted
   * by this method.
   *
   * @return Set of .swap files that are valid to be swapped in as segment files
   */
  private def removeTempFilesAndCollectSwapFiles(): Set[File] = {

    def deleteIndicesIfExist(baseFile: File, suffix: String = ""): Unit = {
      info(s"Deleting index files with suffix $suffix for baseFile $baseFile")
      val offset = offsetFromFile(baseFile)
      Files.deleteIfExists(Log.offsetIndexFile(dir, offset, suffix).toPath)
      Files.deleteIfExists(Log.timeIndexFile(dir, offset, suffix).toPath)
      Files.deleteIfExists(Log.transactionIndexFile(dir, offset, suffix).toPath)
    }

    val swapFiles = mutable.Set[File]()
    val cleanFiles = mutable.Set[File]()
    var minCleanedFileOffset = Long.MaxValue

    for (file <- dir.listFiles if file.isFile) {
      if (!file.canRead)
        throw new IOException(s"Could not read file $file")
      val filename = file.getName
      if (filename.endsWith(DeletedFileSuffix)) {
        debug(s"Deleting stray temporary file ${file.getAbsolutePath}")
        Files.deleteIfExists(file.toPath)
      } else if (filename.endsWith(CleanedFileSuffix)) {
        minCleanedFileOffset = Math.min(offsetFromFileName(filename), minCleanedFileOffset)
        cleanFiles += file
      } else if (filename.endsWith(SwapFileSuffix)) {
        // we crashed in the middle of a swap operation, to recover:
        // if a log, delete the index files, complete the swap operation later
        // if an index just delete the index files, they will be rebuilt
        val baseFile = new File(CoreUtils.replaceSuffix(file.getPath, SwapFileSuffix, ""))
        info(s"Found file ${file.getAbsolutePath} from interrupted swap operation.")
        if (isIndexFile(baseFile)) {
          deleteIndicesIfExist(baseFile)
        } else if (isLogFile(baseFile)) {
          deleteIndicesIfExist(baseFile)
          swapFiles += file
        }
      }
    }

    // KAFKA-6264: Delete all .swap files whose base offset is greater than the minimum .cleaned segment offset. Such .swap
    // files could be part of an incomplete split operation that could not complete. See LocalLog#splitOverflowedSegment
    // for more details about the split operation.
    val (invalidSwapFiles, validSwapFiles) = swapFiles.partition(file => offsetFromFile(file) >= minCleanedFileOffset)
    invalidSwapFiles.foreach { file =>
      debug(s"Deleting invalid swap file ${file.getAbsoluteFile} minCleanedFileOffset: $minCleanedFileOffset")
      val baseFile = new File(CoreUtils.replaceSuffix(file.getPath, SwapFileSuffix, ""))
      deleteIndicesIfExist(baseFile, SwapFileSuffix)
      Files.deleteIfExists(file.toPath)
    }

    // Now that we have deleted all .swap files that constitute an incomplete split operation, let's delete all .clean files
    cleanFiles.foreach { file =>
      debug(s"Deleting stray .clean file ${file.getAbsolutePath}")
      Files.deleteIfExists(file.toPath)
    }

    validSwapFiles
  }

  private def retryOnOffsetOverflow[T](fn: => T): T = {
    while (true) {
      try {
        return fn
      } catch {
        case e: LogSegmentOffsetOverflowException =>
          info(s"Caught segment overflow error: ${e.getMessage}. Split segment and retry.")
          splitOverflowedSegment(e.segment)
      }
    }
    throw new IllegalStateException()
  }

  private def maybeHandleIOException[T](msg: => String)(fun: => T): T = {
    try {
      checkForLogDirFailure()
      fun
    } catch {
      case e: IOException =>
        logDirOffline = true
        logDirFailureChannel.maybeAddOfflineLogDir(dir.getParent, msg, e)
        throw new KafkaStorageException(msg, e)
    }
  }

  /**
   * Split a segment into one or more segments such that there is no offset overflow in any of them. The
   * resulting segments will contain the exact same messages that are present in the input segment. On successful
   * completion of this method, the input segment will be deleted and will be replaced by the resulting new segments.
   * See replaceSegments for recovery logic, in case the broker dies in the middle of this operation.
   * <p>Note that this method assumes we have already determined that the segment passed in contains records that cause
   * offset overflow.</p>
   * <p>The split logic overloads the use of .clean files that LogCleaner typically uses to make the process of replacing
   * the input segment with multiple new segments atomic and recoverable in the event of a crash. See replaceSegments
   * and completeSwapOperations for the implementation to make this operation recoverable on crashes.</p>
   *
   * @param segment Segment to split
   *
   * @return a result instance containing list of new segments that replace the input segment and deleted segments (if any)
   */
  private[log] def splitOverflowedSegment(segment: LogSegment): SplitSegmentResult = {
    require(isLogFile(segment.log.file), s"Cannot split file ${segment.log.file.getAbsoluteFile}")
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
      val toAdd = newSegments.toSeq
      val deletedSegments = replaceSegments(newSegments.toSeq, List(segment))
      SplitSegmentResult(deletedSegments.toSeq, toAdd)
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
   * Swap one or more new segment in place and delete one or more existing segments in a crash-safe manner. The old
   * segments will be asynchronously deleted.
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
   *
   * @return segments which were deleted but not replaced
   */
  private[log] def replaceSegments(newSegments: Seq[LogSegment], oldSegments: Seq[LogSegment], isRecoveredSwapFile: Boolean = false): Seq[LogSegment] = {
    val sortedNewSegments = newSegments.sortBy(_.baseOffset)
    // Some old segments may have been removed from index and scheduled for async deletion after the caller reads segments
    // but before this method is executed. We want to filter out those segments to avoid calling asyncDeleteSegment()
    // multiple times for the same segment.
    val sortedOldSegments = oldSegments.filter(seg => segments.containsKey(seg.baseOffset)).sortBy(_.baseOffset)

    checkIfMemoryMappedBufferClosed()
    // need to do this in two phases to be crash safe AND do the delete asynchronously
    // if we crash in the middle of this we complete the swap in loadSegments()
    if (!isRecoveredSwapFile)
      sortedNewSegments.reverse.foreach(_.changeFileSuffixes(Log.CleanedFileSuffix, Log.SwapFileSuffix))
    sortedNewSegments.reverse.foreach(addSegment(_))
    val newSegmentBaseOffsets = sortedNewSegments.map(_.baseOffset).toSet

    // delete the old files
    val deletedNotReplaced = sortedOldSegments.map { seg =>
      // remove the index entry
      if (seg.baseOffset != sortedNewSegments.head.baseOffset)
        segments.remove(seg.baseOffset)
      deleteSegmentFiles(List(seg), asyncDelete = true)
      if (newSegmentBaseOffsets.contains(seg.baseOffset)) Option.empty else Some(seg)
    }.filter(item => item.isDefined).map(item => item.get)
    // okay we are safe now, remove the swap suffix
    sortedNewSegments.foreach(_.changeFileSuffixes(Log.SwapFileSuffix, ""))
    deletedNotReplaced
  }

  /**
   * Find segments starting from the oldest until the user-supplied predicate is false or the segment
   * containing the current high watermark is reached. We do not delete segments with offsets at or beyond
   * the high watermark to ensure that the log start offset can never exceed it. If the high watermark
   * has not yet been initialized, no segments are eligible for deletion.
   *
   * A final segment that is empty will never be returned (since we would just end up re-creating it).
   *
   * @param predicate A function that takes in a candidate log segment and the next higher segment
   *                  (if there is one), logEndOffset and returns true iff it is deletable
   * @return the segments ready to be deleted
   */
  private[log] def deletableSegments(predicate: (LogSegment, Option[LogSegment], Long) => Boolean): Iterable[LogSegment] = {
    if (segments.isEmpty) {
      Seq.empty
    } else {
      val deletable = ArrayBuffer.empty[LogSegment]
      var segmentEntry = segments.firstEntry
      while (segmentEntry != null) {
        val segment = segmentEntry.getValue
        val nextSegmentEntry = segments.higherEntry(segmentEntry.getKey)
        val (nextSegment, isLastSegmentAndEmpty) = if (nextSegmentEntry != null)
          (nextSegmentEntry.getValue, false)
        else
          (null, segment.size == 0)

        if (predicate(segment, Option(nextSegment), logEndOffset) && !isLastSegmentAndEmpty) {
          deletable += segment
          segmentEntry = nextSegmentEntry
        } else {
          segmentEntry = null
        }
      }
      deletable
    }
  }

  /**
   * Perform physical deletion for the given segments. Allows the segments to be deleted asynchronously or synchronously.
   * This method assumes that the segment exists.
   * This method does not need to convert IOException (thrown from changeFileSuffixes) to KafkaStorageException because
   * it is either called before all logs are loaded or the caller will catch and handle IOException
   *
   * @throws IOException if the segment files can't be renamed and still exists
   *         KafkaStorageException if the segment files can't be deleted synchronously (when asyncDelete = false)
   */
  private[log] def deleteSegmentFiles(segments: Iterable[LogSegment],
                                      asyncDelete: Boolean): Unit = {
    segments.foreach(_.changeFileSuffixes("", Log.DeletedFileSuffix))

    def deleteSegments(): Unit = {
      info(s"Deleting segment files ${segments.mkString(",")}")
      maybeHandleIOException(s"Error while deleting segments for $topicPartition in dir ${dir.getParent}") {
        segments.foreach { segment =>
          segment.deleteIfExists()
        }
      }
    }

    if (asyncDelete)
      scheduler.schedule("delete-file", () => deleteSegments(), delay = config.fileDeleteDelayMs)
    else
      deleteSegments()
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
   * This method does not need to convert IOException (thrown when renaming segment files) to KafkaStorageException because
   * it is either called before all logs are loaded or the immediate caller will catch and handle IOException.
   *
   * @param segments The log segments to schedule for deletion
   * @param asyncDelete Whether the segment files should be deleted asynchronously
   *
   * @throws IOException if the segment files can't be renamed and still exists
   *         KafkaStorageException if the segment files can't be deleted synchronously (when asyncDelete = false)
   */
  private[log] def removeAndDeleteSegments(segments: Iterable[LogSegment],
                                           asyncDelete: Boolean,
                                           reason: SegmentDeletionReason): Unit = {
    if (segments.nonEmpty) {
      // As most callers hold an iterator into the `segments` collection and `removeAndDeleteSegment` mutates it by
      // removing the deleted segment, we should force materialization of the iterator here, so that results of the
      // iteration remain valid and deterministic.
      val toDelete = segments.toList
      reason.logReason(this, toDelete)
      toDelete.foreach { segment =>
        this.segments.remove(segment.baseOffset)
      }
      deleteSegmentFiles(toDelete, asyncDelete)
    }
  }

  private def emptyFetchDataInfo(fetchOffsetMetadata: LogOffsetMetadata,
                                      includeAbortedTxns: Boolean): FetchDataInfo = {
    val abortedTransactions =
      if (includeAbortedTxns) Some(List.empty[FetchResponseData.AbortedTransaction])
      else None
    FetchDataInfo(fetchOffsetMetadata,
      MemoryRecords.EMPTY,
      abortedTransactions = abortedTransactions)
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
   * @param includeAbortedTxns If this is true, aborted transactions are included in the fetch data information
   * @throws OffsetOutOfRangeException If startOffset is beyond the log end offset
   *
   * @return The fetch data information including fetch starting offset metadata and messages read.
   */
  private[log] def read(startOffset: Long,
                        maxLength: Int,
                        minOneMessage: Boolean,
                        maxOffsetMetadata: LogOffsetMetadata,
                        includeAbortedTxns: Boolean): FetchDataInfo = {
    maybeHandleIOException(s"Exception while reading from $topicPartition in dir ${dir.getParent}") {
      trace(s"Reading maximum $maxLength bytes at offset $startOffset from log with " +
        s"total length $size bytes")

      // Because we don't use the lock for reading, the synchronization is a little bit tricky.
      // We create the local variables to avoid race conditions with updates to the log.
      val endOffsetMetadata = nextOffsetMetadata
      val endOffset = endOffsetMetadata.messageOffset
      var segmentEntry = segments.floorEntry(startOffset)

      // return error on attempt to read beyond the log end offset or read below log start offset
      if (startOffset > endOffset || segmentEntry == null)
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
        var done = segmentEntry == null
        var fetchDataInfo: FetchDataInfo = null
        while (!done) {
          val segment = segmentEntry.getValue

          val maxPosition =
          // Use the max offset position if it is on this segment; otherwise, the segment size is the limit.
            if (maxOffsetMetadata.segmentBaseOffset == segment.baseOffset) maxOffsetMetadata.relativePositionInSegment
            else segment.size

          fetchDataInfo = segment.read(startOffset, maxLength, maxPosition, minOneMessage)
          if (fetchDataInfo != null) {
            if (includeAbortedTxns)
              fetchDataInfo = addAbortedTransactions(startOffset, segmentEntry, fetchDataInfo)
          } else segmentEntry = segments.higherEntry(segmentEntry.getKey)

          done = fetchDataInfo != null || segmentEntry == null
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
      val nextSegmentEntry = segments.higherEntry(segmentEntry.getKey)
      if (nextSegmentEntry != null)
        nextSegmentEntry.getValue.baseOffset
      else
        logEndOffset
    }

    val abortedTransactions = ListBuffer.empty[FetchResponseData.AbortedTransaction]
    def accumulator(abortedTxns: List[AbortedTxn]): Unit = abortedTransactions ++= abortedTxns.map(_.asAbortedTransaction)
    collectAbortedTransactions(startOffset, upperBoundOffset, segmentEntry, accumulator)

    FetchDataInfo(fetchOffsetMetadata = fetchInfo.fetchOffsetMetadata,
      records = fetchInfo.records,
      firstEntryIncomplete = fetchInfo.firstEntryIncomplete,
      abortedTransactions = Some(abortedTransactions.toList))
  }

  private[log] def collectAbortedTransactions(logStartOffset: Long, baseOffset: Long, upperBoundOffset: Long): List[AbortedTxn] = {
    val segmentEntry = segments.floorEntry(baseOffset)
    val allAbortedTxns = ListBuffer.empty[AbortedTxn]
    def accumulator(abortedTxns: List[AbortedTxn]): Unit = allAbortedTxns ++= abortedTxns
    collectAbortedTransactions(logStartOffset, upperBoundOffset, segmentEntry, accumulator)
    allAbortedTxns.toList
  }

  private def collectAbortedTransactions(startOffset: Long, upperBoundOffset: Long,
                                         startingSegmentEntry: JEntry[JLong, LogSegment],
                                         accumulator: List[AbortedTxn] => Unit): Unit = {
    var segmentEntry = startingSegmentEntry
    while (segmentEntry != null) {
      val searchResult = segmentEntry.getValue.collectAbortedTxns(startOffset, upperBoundOffset)
      accumulator(searchResult.abortedTransactions)
      if (searchResult.isComplete)
        return
      segmentEntry = segments.higherEntry(segmentEntry.getKey)
    }
  }

  /**
   * The caller has to make sure log segments don't get deleted during this call, and also protects against calling
   * this function on the same segment in parallel.
   *
   * Currently, it is used by LogCleaner threads on log compact non-active segments only with LogCleanerManager's lock
   * to ensure no other LogCleaner threads and retention thread can work on the same segment.
   */
  private[log] def getFirstBatchTimestampForSegments(segments: Iterable[LogSegment]): Iterable[Long] = {
    segments.map {
      segment =>
        segment.getFirstBatchTimestamp()
    }
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
    val segment = activeSegment
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

  /**
   * Roll the log over to a new active segment starting with the current logEndOffset.
   * This will trim the index to the exact size of the number of entries it currently contains.
   *
   * @return The newly rolled segment
   */
  def roll(expectedNextOffset: Option[Long] = None, rollAction: RollAction): LogSegment = {
    maybeHandleIOException(s"Error while rolling log segment for $topicPartition in dir ${dir.getParent}") {
      val start = time.hiResClockMs()
      checkIfMemoryMappedBufferClosed()
      val newOffset = math.max(expectedNextOffset.getOrElse(0L), logEndOffset)
      val logFile = Log.logFile(dir, newOffset)

      val deleted: Option[LogSegment] = if (segments.containsKey(newOffset)) {
        // segment with the same base offset already exists and loaded
        if (activeSegment.baseOffset == newOffset && activeSegment.size == 0) {
          // We have seen this happen (see KAFKA-6388) after shouldRoll() returns true for an
          // active segment of size zero because of one of the indexes is "full" (due to _maxEntries == 0).
          warn(s"Trying to roll a new log segment with start offset $newOffset " +
            s"=max(provided offset = $expectedNextOffset, LEO = $logEndOffset) while it already " +
            s"exists and is active with size 0. Size of time index: ${activeSegment.timeIndex.entries}," +
            s" size of offset index: ${activeSegment.offsetIndex.entries}.")
          val toDelete = activeSegment
          removeAndDeleteSegments(Seq(toDelete), asyncDelete = true, LogRoll)
          Some(toDelete)
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

        Option(segments.lastEntry).foreach(_.getValue.onBecomeInactiveSegment())
        Option.empty
      }

      rollAction.preRollAction(newOffset)

      val segment = LogSegment.open(dir,
        baseOffset = newOffset,
        config,
        time = time,
        initFileSize = initFileSize,
        preallocate = config.preallocate)
      addSegment(segment)

      // We need to update the segment base offset and append position data of the metadata when log rolls.
      // The next offset should not change.
      updateLogEndOffset(nextOffsetMetadata.messageOffset)

      info(s"Rolled new log segment at offset $newOffset in ${time.hiResClockMs() - start} ms.")

      rollAction.postRollAction(segment, deleted)

      segment
    }
  }

  /**
   *  Delete all data in the log and start at the new offset
   *
   * @param newOffset The new offset to start the log with
   */
  private[log] def truncateFullyAndStartAt(newOffset: Long): Seq[LogSegment] = {
    checkIfMemoryMappedBufferClosed()
    removeAndDeleteSegments(logSegments, asyncDelete = true, LogTruncation)
    val deleted = logSegments.toSeq
    addSegment(LogSegment.open(dir,
      baseOffset = newOffset,
      config = config,
      time = time,
      initFileSize = initFileSize,
      preallocate = config.preallocate))
    deleted
  }

  /**
   * Truncate this log so that it ends with the greatest offset < targetOffset.
   *
   * @param targetOffset The offset to truncate to, an upper bound on all offsets in the log after truncation is complete.
   */
  private[log] def truncateTo(targetOffset: Long): Seq[LogSegment] = {
    val deletable = logSegments.filter(segment => segment.baseOffset > targetOffset)
    removeAndDeleteSegments(deletable, asyncDelete = true, LogTruncation)
    activeSegment.truncateTo(targetOffset)
    deletable.toSeq
  }

  // Rebuild producer state until lastOffset. This method may be called from the recovery code path, and thus must be
  // free of all side-effects, i.e. it must not update any log-specific state.
  def rebuildProducerState(logStartOffset: Long,
                           lastOffset: Long,
                           reloadFromCleanShutdown: Boolean,
                           producerStateManager: ProducerStateManager): Unit = {
    checkIfMemoryMappedBufferClosed()
    val segments = logSegments
    val offsetsToSnapshot =
      if (segments.nonEmpty) {
        val nextLatestSegmentBaseOffset = lowerSegment(segments.last.baseOffset).map(_.baseOffset)
        Seq(nextLatestSegmentBaseOffset, Some(segments.last.baseOffset), Some(lastOffset))
      } else {
        Seq(Some(lastOffset))
      }
    info(s"Loading producer state till offset $lastOffset with message format version ${recordVersion.value}")

    // We want to avoid unnecessary scanning of the log to build the producer state when the broker is being
    // upgraded. The basic idea is to use the absence of producer snapshot files to detect the upgrade case,
    // but we have to be careful not to assume too much in the presence of broker failures. The two most common
    // upgrade cases in which we expect to find no snapshots are the following:
    //
    // 1. The broker has been upgraded, but the topic is still on the old message format.
    // 2. The broker has been upgraded, the topic is on the new message format, and we had a clean shutdown.
    //
    // If we hit either of these cases, we skip producer state loading and write a new snapshot at the log end
    // offset (see below). The next time the log is reloaded, we will load producer state using this snapshot
    // (or later snapshots). Otherwise, if there is no snapshot file, then we have to rebuild producer state
    // from the first segment.
    if (recordVersion.value < RecordBatch.MAGIC_VALUE_V2 ||
      (producerStateManager.latestSnapshotOffset.isEmpty && reloadFromCleanShutdown)) {
      // To avoid an expensive scan through all of the segments, we take empty snapshots from the start of the
      // last two segments and the last offset. This should avoid the full scan in the case that the log needs
      // truncation.
      offsetsToSnapshot.flatten.foreach { offset =>
        producerStateManager.updateMapEndOffset(offset)
        producerStateManager.takeSnapshot()
      }
    } else {
      info(s"Reloading from producer snapshot and rebuilding producer state from offset $lastOffset")
      val isEmptyBeforeTruncation = producerStateManager.isEmpty && producerStateManager.mapEndOffset >= lastOffset
      val producerStateLoadStart = time.milliseconds()
      producerStateManager.truncateAndReload(logStartOffset, lastOffset, time.milliseconds())
      val segmentRecoveryStart = time.milliseconds()

      // Only do the potentially expensive reloading if the last snapshot offset is lower than the log end
      // offset (which would be the case on first startup) and there were active producers prior to truncation
      // (which could be the case if truncating after initial loading). If there weren't, then truncating
      // shouldn't change that fact (although it could cause a producerId to expire earlier than expected),
      // and we can skip the loading. This is an optimization for users which are not yet using
      // idempotent/transactional features yet.
      if (lastOffset > producerStateManager.mapEndOffset && !isEmptyBeforeTruncation) {
        val segmentOfLastOffset = floorLogSegment(lastOffset)

        logSegments(producerStateManager.mapEndOffset, lastOffset).foreach { segment =>
          val startOffset = Utils.max(segment.baseOffset, producerStateManager.mapEndOffset, logStartOffset)
          producerStateManager.updateMapEndOffset(startOffset)

          if (offsetsToSnapshot.contains(Some(segment.baseOffset)))
            producerStateManager.takeSnapshot()

          val maxPosition = if (segmentOfLastOffset.contains(segment)) {
            Option(segment.translateOffset(lastOffset))
              .map(_.position)
              .getOrElse(segment.size)
          } else {
            segment.size
          }

          val fetchDataInfo = segment.read(startOffset,
            maxSize = Int.MaxValue,
            maxPosition = maxPosition,
            minOneMessage = false)
          if (fetchDataInfo != null)
            loadProducersFromRecords(producerStateManager, fetchDataInfo.records)
        }
      }
      producerStateManager.updateMapEndOffset(lastOffset)
      producerStateManager.takeSnapshot()
      info(s"Producer state recovery took ${producerStateLoadStart - segmentRecoveryStart}ms for snapshot load " +
        s"and ${time.milliseconds() - segmentRecoveryStart}ms for segment recovery from offset $lastOffset")
    }
  }
}

/**
 * Helper functions for logs
 */
object LocalLog {

  /** a log file */
  val LogFileSuffix = ".log"

  /** an index file */
  val IndexFileSuffix = ".index"

  /** a time index file */
  val TimeIndexFileSuffix = ".timeindex"

  val ProducerSnapshotFileSuffix = ".snapshot"

  /** an (aborted) txn index */
  val TxnIndexFileSuffix = ".txnindex"

  /** a file that is scheduled to be deleted */
  val DeletedFileSuffix = ".deleted"

  /** A temporary file that is being used for log cleaning */
  val CleanedFileSuffix = ".cleaned"

  /** A temporary file used when swapping files into the log */
  val SwapFileSuffix = ".swap"

  /** a directory that is scheduled to be deleted */
  val DeleteDirSuffix = "-delete"

  /** a directory that is used for future partition */
  val FutureDirSuffix = "-future"

  private[log] val DeleteDirPattern = Pattern.compile(s"^(\\S+)-(\\S+)\\.(\\S+)$DeleteDirSuffix")
  private[log] val FutureDirPattern = Pattern.compile(s"^(\\S+)-(\\S+)\\.(\\S+)$FutureDirSuffix")

  val UnknownOffset = -1L

  /**
   * Make log segment file name from offset bytes. All this does is pad out the offset number with zeros
   * so that ls sorts the files numerically.
   *
   * @param offset The offset to use in the file name
   * @return The filename
   */
  def filenamePrefixFromOffset(offset: Long): String = {
    val nf = NumberFormat.getInstance()
    nf.setMinimumIntegerDigits(20)
    nf.setMaximumFractionDigits(0)
    nf.setGroupingUsed(false)
    nf.format(offset)
  }

  /**
   * Construct a log file name in the given dir with the given base offset and the given suffix
   *
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   * @param suffix The suffix to be appended to the file name (e.g. "", ".deleted", ".cleaned", ".swap", etc.)
   */
  def logFile(dir: File, offset: Long, suffix: String = ""): File =
    new File(dir, filenamePrefixFromOffset(offset) + LogFileSuffix + suffix)

  /**
   * Return a directory name to rename the log directory to for async deletion.
   * The name will be in the following format: "topic-partitionId.uniqueId-delete".
   * If the topic name is too long, it will be truncated to prevent the total name
   * from exceeding 255 characters.
   */
  def logDeleteDirName(topicPartition: TopicPartition): String = {
    val uniqueId = java.util.UUID.randomUUID.toString.replaceAll("-", "")
    val suffix = s"-${topicPartition.partition()}.${uniqueId}${DeleteDirSuffix}"
    val prefixLength = Math.min(topicPartition.topic().size, 255 - suffix.size)
    s"${topicPartition.topic().substring(0, prefixLength)}${suffix}"
  }

  /**
   * Return a future directory name for the given topic partition. The name will be in the following
   * format: topic-partition.uniqueId-future where topic, partition and uniqueId are variables.
   */
  def logFutureDirName(topicPartition: TopicPartition): String = {
    logDirNameWithSuffix(topicPartition, FutureDirSuffix)
  }

  private def logDirNameWithSuffix(topicPartition: TopicPartition, suffix: String): String = {
    val uniqueId = java.util.UUID.randomUUID.toString.replaceAll("-", "")
    s"${logDirName(topicPartition)}.$uniqueId$suffix"
  }

  /**
   * Return a directory name for the given topic partition. The name will be in the following
   * format: topic-partition where topic, partition are variables.
   */
  def logDirName(topicPartition: TopicPartition): String = {
    s"${topicPartition.topic}-${topicPartition.partition}"
  }

  /**
   * Construct an index file name in the given dir using the given base offset and the given suffix
   *
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   * @param suffix The suffix to be appended to the file name ("", ".deleted", ".cleaned", ".swap", etc.)
   */
  def offsetIndexFile(dir: File, offset: Long, suffix: String = ""): File =
    new File(dir, filenamePrefixFromOffset(offset) + IndexFileSuffix + suffix)

  /**
   * Construct a time index file name in the given dir using the given base offset and the given suffix
   *
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   * @param suffix The suffix to be appended to the file name ("", ".deleted", ".cleaned", ".swap", etc.)
   */
  def timeIndexFile(dir: File, offset: Long, suffix: String = ""): File =
    new File(dir, filenamePrefixFromOffset(offset) + TimeIndexFileSuffix + suffix)

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
    new File(dir, filenamePrefixFromOffset(offset) + TxnIndexFileSuffix + suffix)

  def offsetFromFileName(filename: String): Long = {
    filename.substring(0, filename.indexOf('.')).toLong
  }

  def offsetFromFile(file: File): Long = {
    offsetFromFileName(file.getName)
  }

  /**
   * Calculate a log's size (in bytes) based on its log segments
   *
   * @param segments The log segments to calculate the size of
   * @return Sum of the log segments' sizes (in bytes)
   */
  def sizeInBytes(segments: Iterable[LogSegment]): Long =
    segments.map(_.size.toLong).sum

  /**
   * Parse the topic and partition out of the directory name of a log
   */
  def parseTopicPartitionName(dir: File): TopicPartition = {
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

  private def isIndexFile(file: File): Boolean = {
    val filename = file.getName
    filename.endsWith(IndexFileSuffix) || filename.endsWith(TimeIndexFileSuffix) || filename.endsWith(TxnIndexFileSuffix)
  }

  private def isLogFile(file: File): Boolean =
    file.getPath.endsWith(LogFileSuffix)

  private def loadProducersFromRecords(producerStateManager: ProducerStateManager, records: Records): Unit = {
    val loadedProducers = mutable.Map.empty[Long, ProducerAppendInfo]
    val completedTxns = ListBuffer.empty[CompletedTxn]
    records.batches.forEach { batch =>
      if (batch.hasProducerId) {
        val maybeCompletedTxn = updateProducers(
          producerStateManager,
          batch,
          loadedProducers,
          firstOffsetMetadata = None,
          origin = AppendOrigin.Replication)
        maybeCompletedTxn.foreach(completedTxns += _)
      }
    }
    loadedProducers.values.foreach(producerStateManager.update)
    completedTxns.foreach(producerStateManager.completeTxn)
  }

  def updateProducers(producerStateManager: ProducerStateManager,
                      batch: RecordBatch,
                      producers: mutable.Map[Long, ProducerAppendInfo],
                      firstOffsetMetadata: Option[LogOffsetMetadata],
                      origin: AppendOrigin): Option[CompletedTxn] = {
    val producerId = batch.producerId
    val appendInfo = producers.getOrElseUpdate(producerId, producerStateManager.prepareUpdate(producerId, origin))
    appendInfo.append(batch, firstOffsetMetadata)
  }
}

trait SegmentDeletionReason {
  def logReason(log: LocalLog, toDelete: List[LogSegment]): Unit
}
