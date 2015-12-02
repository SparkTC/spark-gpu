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

package org.apache.spark.cuda

import org.apache.spark.storage.BlockId

import scala.reflect.ClassTag

import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}

import jcuda.Pointer
import jcuda.driver.CUfunction
import jcuda.driver.CUmodule
import jcuda.driver.CUstream
import jcuda.driver.JCudaDriver
import jcuda.runtime.cudaStream_t
import jcuda.runtime.cudaMemcpyKind
import jcuda.runtime.JCuda

import org.apache.commons.io.IOUtils
import org.apache.spark.rdd.ExternalFunction
import org.apache.spark.{PartitionData, ColumnPartitionData, ColumnPartitionSchema, SparkEnv,
  SparkException}
import org.apache.spark.util.Utils

/**
 * A CUDA kernel wrapper. Contains CUDA module, information how to extract CUDA kernel from it and
 * how to order input/output columns for the kernel invocation.
 * The kernel should take pointers to C arrays with the input data, then long size parameter, then
 * optionally all constant parameters of respective types, e.g.
 * `void identity(const int *input, int *output, long size, short unusedParam)`. The kernel should
 * work when the total number of threads is larger than size, since number of threads will have to
 * be aligned at least to multiples of 32.
 *
 * @param kernelSignature The C++-style signature of the kernel function. To figure it out you might
 * want to compile the cuda file with nvcc's `-Xptxas="-v"` option.
 * @param inputColumnsOrder Order in which columns of the input partition should be passed to the
 * kernel, e.g. `Array("this")` for `RDD[Int]` or `Array("this.x", "this.y")` for `RDD[Point]`,
 * where `Point` is `case class Point(x: Int, y: Int)`. The name is string with how you could access
 * given property in the object.
 * @param outputColumnsOrder Order in which columns of the output partition should be passed to the
 * kernel. See inputColumnsOrder for format details.
 * @param moduleBinaryData Binary data of a compiled CUDA module in cubin, PTX or fatbin format.
 * @param constArgs Optional list of constant arguments supplied to the kernel.
 * @param dimensions Optional function to compute thread dimensions for running the kernel. By
 * default it is assumed that each thread computes one value with its index (blockSize * blockId +
 * threadId) and dimensions are automatically computed to maximize block size.
 */
// TODO allow kernel to use some of input memory in-place, i.e. reuse input as output and put those
// buffers later to output ColumnPartitionData - can do it by special
// inputColumnOrder/outputColumnsOrder syntax
// TODO improve the way constant arguments are passed - especially duplication of kernels

class CUDAFunction(
    val kernelSignature: String,
    val inputColumnsOrder: Seq[String],
    val outputColumnsOrder: Seq[String],
    val resourceURL: URL,
    val constArgs: Seq[AnyVal] = Seq(),
    val stagesCount: Option[Long => Int] = None,
    val dimensions: Option[(Long, Int) => (Int, Int)] = None) extends ExternalFunction {

  /**
   * Runs the kernel on input data. Output size should be specified if kernel's result is of size
   * different than input size.
   */
  private[spark] def run[T, U: ClassTag](in: ColumnPartitionData[T],
      outputSize: Option[Long] = None,
      outputArraySizes: Seq[Long] = null,
      inputFreeVariables: Seq[Any] = null,
      blockId : Option[BlockId] = None,
      gpuCache : Boolean = false): ColumnPartitionData[U] = {
    val outputSchema = ColumnPartitionSchema.schemaFor[U]

    // TODO add array size
    val memoryUsage = (if (in.gpuCached) 0 else in.memoryUsage) + outputSchema.memoryUsage(in.size)

    val streamDevIx = SparkEnv.get.cudaManager.getStream(memoryUsage, in.gpuDevIx)
    val stream = streamDevIx._1
    if (in.gpuCache) {
      in.gpuDevIx = streamDevIx._2
    }

    // TODO cache the function if there is a chance that after a deserialization kernel gets called
    // multiple times - but only if no synchronization is needed for that
    val module = SparkEnv.get.cudaManager.cachedLoadModule(resourceURL)
    val function = new CUfunction
    JCudaDriver.cuModuleGetFunction(function, module, kernelSignature)

    val actualOutputSize = outputSize.getOrElse(in.size)
    val out = if (outputArraySizes == null) {
      new ColumnPartitionData[U](outputSchema, actualOutputSize)
    } else {
      val outColumns = outputSchema.orderedColumns(outputColumnsOrder)
        .filter(p => p.columnType.isArray)
      val outputArrayInfo = outputArraySizes zip outColumns
      new ColumnPartitionData[U](outputSchema, actualOutputSize, Some(outputArrayInfo))
    }
    try {
      var gpuOutputPtrs = Vector[Pointer]()
      var gpuOutputBlobs = Vector[Pointer]()
      var cpuInputFreeVars = Vector[(Pointer, Int)]()
      Utils.tryWithSafeFinally {

        val outColumns = out.schema.orderedColumns(outputColumnsOrder)
        for (col <- outColumns) {
          gpuOutputPtrs = gpuOutputPtrs :+
            SparkEnv.get.cudaManager.allocGPUMemory(col.memoryUsage(out.size))
        }

        val inputFreeVarPtrs = if (inputFreeVariables == null) { Seq() } else {
          inputFreeVariables.map {
            case v: Byte => Pointer.to(Array(v))
            case v: Char => Pointer.to(Array(v))
            case v: Short => Pointer.to(Array(v))
            case v: Int => Pointer.to(Array(v))
            case v: Long => Pointer.to(Array(v))
            case v: Float => Pointer.to(Array(v))
            case v: Double => Pointer.to(Array(v))
            case v: Array[Byte] =>
              val len = v.length
              cpuInputFreeVars = cpuInputFreeVars :+ (Pointer.to(v), len)
              SparkEnv.get.cudaManager.allocGPUMemory(len)
            case v: Array[Char] =>
              val len = v.length * 2
              cpuInputFreeVars = cpuInputFreeVars :+ (Pointer.to(v), len)
              SparkEnv.get.cudaManager.allocGPUMemory(len)
            case v: Array[Short] =>
              val len = v.length * 2
              cpuInputFreeVars = cpuInputFreeVars :+ (Pointer.to(v), len)
              SparkEnv.get.cudaManager.allocGPUMemory(len)
            case v: Array[Int] =>
              val len = v.length * 4
              cpuInputFreeVars = cpuInputFreeVars :+ (Pointer.to(v), len)
              SparkEnv.get.cudaManager.allocGPUMemory(len)
            case v: Array[Long] =>
              val len = v.length * 8
              cpuInputFreeVars = cpuInputFreeVars :+ (Pointer.to(v), len)
              SparkEnv.get.cudaManager.allocGPUMemory(len)
            case v: Array[Float] =>
              val len = v.length * 4
              cpuInputFreeVars = cpuInputFreeVars :+ (Pointer.to(v), len)
              SparkEnv.get.cudaManager.allocGPUMemory(len)
            case v: Array[Double] =>
              val len = v.length * 8
              cpuInputFreeVars = cpuInputFreeVars :+ (Pointer.to(v), len)
              SparkEnv.get.cudaManager.allocGPUMemory(len)
            case _ => throw new SparkException("Unsupported type passed to kernel "
            + "as a free variable argument")
          }
        }

        // TODO support more than one blobs
        val outBlobs = if (out.blobs != null) {out.blobs} else {Array[Pointer]()}
        val outBlobBuffers =
           if (out.blobBuffers != null) {out.blobBuffers} else {Array[ByteBuffer]()}
        for (blob <- outBlobBuffers) {
          gpuOutputBlobs = gpuOutputBlobs :+
            SparkEnv.get.cudaManager.allocGPUMemory(blob.capacity())
        }

        // perform allocGPUMemory and cudaMemcpyAsync
        val gpuInputPtrs = in.orderedGPUPointers(inputColumnsOrder, stream)

        for (((cpuPtr, size), gpuPtr) <- (cpuInputFreeVars zip inputFreeVarPtrs)) {
          JCuda.cudaMemcpyAsync(gpuPtr, cpuPtr, size,
            cudaMemcpyKind.cudaMemcpyHostToDevice, stream)
        }

        val gpuPtrParams = (gpuInputPtrs ++
                            gpuOutputPtrs ++ gpuOutputBlobs).map(Pointer.to(_))
        val sizeParam = List(Pointer.to(Array(in.size)))
        val constArgParams = constArgs.map {
          case v: Byte => Pointer.to(Array(v))
          case v: Char => Pointer.to(Array(v))
          case v: Short => Pointer.to(Array(v))
          case v: Int => Pointer.to(Array(v))
          case v: Long => Pointer.to(Array(v))
          case v: Float => Pointer.to(Array(v))
          case v: Double => Pointer.to(Array(v))
          case _ => throw new SparkException("Unsupported type passed to kernel as a constant "
            + "argument")
        }

        val wrappedStream = new CUstream(stream)

        stagesCount match {
          // normal launch, no stages, suitable for map
          case None =>
            val params = gpuPtrParams ++ sizeParam ++
                           inputFreeVarPtrs.map(Pointer.to(_)) ++
                           constArgParams
            val kernelParameters = Pointer.to(params: _*)

            val (gpuGridSize, gpuBlockSize) = dimensions match {
              case Some(computeDim) => computeDim(in.size, 1)
              case None => SparkEnv.get.cudaManager.computeDimensions(in.size)
            }

            JCudaDriver.cuLaunchKernel(
              function,
              gpuGridSize, 1, 1,
              gpuBlockSize, 1, 1,
              0,
              wrappedStream,
              kernelParameters, null)

          // launch kernel multiple times (multiple stages), suitable for reduce
          case Some(totalStagesFun) =>
            val totalStages = totalStagesFun(in.size)
            if (totalStages <= 0) {
              throw new SparkException("Number of stages in a kernel launch must be positive")
            }
            (0 to totalStages - 1).foreach { stageNumber =>
              val stageParams =
                List(Pointer.to(Array[Int](stageNumber)), Pointer.to(Array[Int](totalStages)))
              val params = gpuPtrParams ++ sizeParam ++
                             inputFreeVarPtrs.map(Pointer.to(_)) ++
                             constArgParams ++ stageParams
              val kernelParameters = Pointer.to(params: _*)

              val (gpuGridSize, gpuBlockSize) = dimensions match {
                case Some(computeDim) => computeDim(in.size, stageNumber)
                case None =>
                  // TODO it can be automatized if we say that by default we reduce exactly one
                  // warp size amount of data, though it'll become complex for the user
                  throw new SparkException("Dimensions must be provided for multi-stage kernels")
              }

              JCudaDriver.cuLaunchKernel(
                function,
                gpuGridSize, 1, 1,
                gpuBlockSize, 1, 1,
                0,
                wrappedStream,
                kernelParameters, null)
            }
        }

        val outPointers = out.orderedPointers(outputColumnsOrder)
        for ((cpuPtr, gpuPtr, col) <- (outPointers, gpuOutputPtrs, outColumns).zipped) {
          JCuda.cudaMemcpyAsync(cpuPtr, gpuPtr, col.memoryUsage(out.size),
            cudaMemcpyKind.cudaMemcpyDeviceToHost, stream)
        }

        for ((cpuPtr, gpuPtr, blob) <- (outBlobs, gpuOutputBlobs, outBlobBuffers).zipped) {
          JCuda.cudaMemcpyAsync(cpuPtr, gpuPtr, blob.capacity(),
            cudaMemcpyKind.cudaMemcpyDeviceToHost, stream)
        }

        if (!in.gpuCache || ((gpuOutputPtrs.size + gpuOutputBlobs.size) > 0)) {
          JCuda.cudaStreamSynchronize(stream)
        }
        out.gpuCache = gpuCache
        out.blockId = blockId
        out
      }  {
        in.freeGPUPointers()
        for (ptr <- gpuOutputPtrs) {
          SparkEnv.get.cudaManager.freeGPUMemory(ptr)
        }
        for (ptr <- gpuOutputBlobs) {
          SparkEnv.get.cudaManager.freeGPUMemory(ptr)
        }
      }
    } catch {
      case ex: Exception =>
        out.free
        throw ex
    }
  }
}
