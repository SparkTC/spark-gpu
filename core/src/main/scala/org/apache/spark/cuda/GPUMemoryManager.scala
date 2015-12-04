package org.apache.spark.cuda

import jcuda.Pointer
import org.apache.spark.{SparkEnv, SparkException}
import org.apache.spark.rpc.{RpcEndpointRef, RpcCallContext, RpcEnv, ThreadSafeRpcEndpoint}

import scala.collection.mutable.{ListBuffer, HashMap}

/**
 * Created by kmadhu on 2/12/15.
 */

case class RegisterGPUMemoryManager(val id : String,slaveEndPointerRef: RpcEndpointRef)
case class UncacheGPU(val id : Int)
case class CacheGPU(val id : Int)

class GPUMemoryManagerMasterEndPoint(override val rpcEnv: RpcEnv) extends ThreadSafeRpcEndpoint {

  val GPUMemoryManagerSlaves = new HashMap[String, RpcEndpointRef]()

  def registerGPUMemoryManager(id : String, slaveEndpointRef: RpcEndpointRef): Unit = {
    GPUMemoryManagerSlaves += id -> slaveEndpointRef
  }

  def unCacheGPU(rddId : Int): Unit = {
    for(slaveRef <- GPUMemoryManagerSlaves.values){
      tell(slaveRef,UncacheGPU(rddId))
    }
  }

  def cacheGPU(rddId : Int): Unit = {
    for(slaveRef <- GPUMemoryManagerSlaves.values){
      tell(slaveRef,CacheGPU(rddId))
    }
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case RegisterGPUMemoryManager(id,slaveEndPointRef) =>
      registerGPUMemoryManager(id,slaveEndPointRef)
      context.reply (true)
    case UncacheGPU(rddId : Int) =>
      unCacheGPU(rddId)
      context.reply (true)
    case CacheGPU(rddId : Int) =>
      cacheGPU(rddId)
      context.reply (true)
  }

  private def tell(slaveEndPointRef: RpcEndpointRef, message: Any) {
    if (!slaveEndPointRef.askWithRetry[Boolean](message)) {
      throw new SparkException("GPUMemoryManagerSlaveEndPoint returned false, expected true.")
    }
  }
}

class GPUMemoryManagerSlaveEndPoint( override val rpcEnv: RpcEnv,
                                     val master : GPUMemoryManager)  extends ThreadSafeRpcEndpoint {

  def unCacheGPU(rddId : Int): Unit = {
    master.unCacheGPU(rddId)
  }

  def cacheGPU(rddId : Int): Unit = {
    master.cacheGPU(rddId)
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case UncacheGPU(rddId : Int) =>
      unCacheGPU(rddId)
      context.reply (true)
    case CacheGPU(rddId : Int) =>
      cacheGPU(rddId)
      context.reply (true)
    case id : String =>
      context.reply (true)
  }
}

class GPUMemoryManager(
                       val executorId : String,
                       val rpcEnv : RpcEnv,
                       val driverEndpoint: RpcEndpointRef,
                       val isDriver : Boolean,
                       val isLocal : Boolean) {

  val cachedGPUPointers = new HashMap[String, Pointer]()
  val cachedGPURDDs = new ListBuffer[Int]()

  def getCachedGPUPointers = cachedGPUPointers

  if(!isDriver || isLocal) {
    val slaveEndpoint = rpcEnv.setupEndpoint(
      "GPUMemoryManagerSlaveEndpoint_" + executorId,
      new GPUMemoryManagerSlaveEndPoint(rpcEnv, this))
    tell(RegisterGPUMemoryManager(executorId, slaveEndpoint))
  }



  def unCacheGPU(rddId : Int): Unit = {
    cachedGPURDDs -= rddId
    for ((name, ptr) <- cachedGPUPointers) {
      if (name.startsWith("rdd_" + rddId)) {
        SparkEnv.get.cudaManager.freeGPUMemory(ptr)
        cachedGPUPointers.remove(name)
      }
    }
  }

  def cacheGPU(rddId : Int): Unit = {
    if(!cachedGPURDDs.contains(rddId))
      cachedGPURDDs += rddId
  }

  def unCacheGPUSlaves(rddId : Int): Unit = {
    tell(UncacheGPU(rddId))
  }

  def cacheGPUSlaves(rddId : Int): Unit = {
    tell(CacheGPU(rddId))
  }

  /** Send a one-way message to the master endpoint, to which we expect it to reply with true. */
  private def tell(message: Any) {
    if (!driverEndpoint.askWithRetry[Boolean](message)) {
      throw new SparkException("GPUMemoryManagerMasterEndPoint returned false, expected true.")
    }
  }

}

private[spark] object GPUMemoryManager {
  val DRIVER_ENDPOINT_NAME = "GPUMemoryManager"
}
