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

package org.apache.spark

import org.apache.spark.storage.BlockId

import scala.reflect.ClassTag

import org.apache.spark.annotation.DeveloperApi

case object IteratorFormat extends PartitionFormat

@DeveloperApi
class IteratorPartitionData[T](
    val iter: Iterator[T]
  ) extends PartitionData[T] {

  override def wrapIterator(f: (Iterator[T] => Iterator[T])): PartitionData[T] =
    IteratorPartitionData(f(iter))

  override def iterator: Iterator[T] = iter

  override def convert(format: PartitionFormat, blockId : Option[BlockId] = None)
    (implicit ct: ClassTag[T]): PartitionData[T] = {
    format match {
      // We already have iterator format. Note that we do not need to iterate over elements, so this
      // is a no-op.
      case IteratorFormat => this

      // Converting from iterator-based format to column-based format.
      case ColumnFormat =>
        { val c = ColumnPartitionDataBuilder.build(iter); c.blockId = blockId; c}
    }
  }

}

@DeveloperApi
object IteratorPartitionData {

  /**
   * Wraps an iterator in IteratorPartitionData object.
   */
  def apply[T](iter: Iterator[T]): IteratorPartitionData[T] =
    new IteratorPartitionData(iter)

  /**
   * Extracts iterator from IteratorPartitionData.
   * Usage:
   * {{{
   * partitionData match {
   *   case IteratorPartitionData(iter) =>
   *     do_iterated_stuff_with(iter)
   *   case col: ColumnPartitionData[_] =>
   *     do_column_stuff_with(col)
   * }
   * }}}
   */
  def unapply[T](it: IteratorPartitionData[T]): Option[Iterator[T]] =
    Some(it.iter)

}
