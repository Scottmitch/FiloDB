package filodb.core.memstore

import com.typesafe.scalalogging.StrictLogging

import filodb.core.metadata.Dataset
import filodb.memory.MemFactory
import filodb.memory.format.BinaryAppendableVector

object WriteBufferPool {
  // The number of partition write buffers to allocate at one time
  val DefaultAllocStepSize = 1000
}

/**
 * A WriteBufferPool pre-allocates/creates a pool of WriteBuffers for sharing amongst many MemStore Partitions.
 * For efficiency it creates a whole set of BinaryAppendableVectors for all columns, so that
 * at flush time, the partitions can easily obtain a new one from the pool and rapidly swap out a new set of buffers.
 *
 * NOTE that the pool dynamically resizes.  It allocates allocationStepSize write buffers at a time as needed.
 * Also, if a large number of write buffers are returned, it might return some writebuffers and release its memory.
 *
 * The lifecycle is as follows:
 * 1. Partition gets data - obtains new set of initial buffers
 * 2. End of flush()     - original buffers, now encoded, are released, reset, and can be made available to others
 *
 * @param maxChunkSize the max size of the write buffer in elements.
 * @param allocationStepSize the number of partition write buffers to allocate at a time.
 *                           Smaller=better use of memory; Bigger=more efficient allocation
 *
 * TODO: Use MemoryManager etc. and allocate memory from a fixed block instead of specifying max # partitions
 */
class WriteBufferPool(memFactory: MemFactory,
                      dataset: Dataset,
                      maxChunkSize: Int,
                      allocationStepSize: Int = WriteBufferPool.DefaultAllocStepSize) extends StrictLogging {
  val queue = new collection.mutable.Queue[Array[BinaryAppendableVector[_]]]

  private def allocateBuffers(): Unit = {
    logger.debug(s"Allocating $allocationStepSize WriteBuffers....")
    // Fill queue up
    (0 until allocationStepSize).foreach { n =>
      val builders = MemStore.getAppendables(memFactory, dataset, maxChunkSize)
      queue.enqueue(builders)
    }
  }

  /**
   * Returns the number of allocatable sets of buffers in the pool
   */
  def poolSize: Int = queue.length

  /**
   * Obtains a new set of AppendableVectors from the pool, creating additional buffers if there is memory available.
   *
   * @return Array of AppendableVectors
   * Throws NoSuchElementException if the Queue is empty and unable to create more buffers/out of memory.
   */
  def obtain(): Array[BinaryAppendableVector[_]] = {
    // If queue is empty, try and allocate more buffers depending on if memFactory has more memory
    // If that fails, return queue empty
    if (queue.isEmpty) try {
      allocateBuffers()
    } catch {
      case e: Exception =>
        logger.error(s"Could not allocate more WriteBuffers", e)
        throw new NoSuchElementException
    }
    queue.dequeue
  }

  /**
   * Releases a set of AppendableVectors back to the pool, henceforth someone else can obtain it.
   * The state of the appenders are reset.
   */
  def release(appenders: Array[BinaryAppendableVector[_]]): Unit = {
    appenders.foreach(_.reset())
    queue.enqueue(appenders)
    // TODO: check number of buffers in queue, and release baack to free memory.
    //  NOTE: no point to this until the pool shares a single MemFactory amongst multiple shards.  In that case
    //        we have to decide (w/ concurrency a concern): share a single MemFactory or a single WriteBufferPool?
  }
}