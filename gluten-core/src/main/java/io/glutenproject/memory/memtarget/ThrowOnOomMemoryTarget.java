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
package io.glutenproject.memory.memtarget;

import io.glutenproject.GlutenConfig$;
import io.glutenproject.proto.MemoryUsageStats;

import org.apache.spark.memory.SparkMemoryUtil;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.util.Utils;

public class ThrowOnOomMemoryTarget implements MemoryTarget {
  private final TaskManagedMemoryTarget target;

  public ThrowOnOomMemoryTarget(TaskManagedMemoryTarget target) {
    this.target = target;
  }

  @Override
  public long borrow(long size) {
    long granted = target.borrow(size);
    if (granted >= size) {
      return granted;
    }
    // OOM happens.
    // Note if the target is a Spark memory consumer, spilling should already be requested but
    // failed to reclaim enough memory.
    if (granted != 0L) {
      target.repay(granted);
    }
    // Log memory usage
    target.getTaskMemoryManager().showMemoryUsage();
    // Build error message, then throw
    StringBuilder errorBuilder = new StringBuilder();
    errorBuilder
        .append(
            String.format(
                "Not enough spark off-heap execution memory. Acquired: %d, granted: %d. "
                    + "Try tweaking config option spark.memory.offHeap.size to get larger space "
                    + "to run this application. %n",
                size, granted))
        .append("Current config settings: ")
        .append(System.lineSeparator())
        .append(
            String.format(
                "\t%s=%s",
                GlutenConfig$.MODULE$.GLUTEN_OFFHEAP_SIZE_IN_BYTES_KEY(),
                reformatBytes(
                    SQLConf.get()
                        .getConfString(GlutenConfig$.MODULE$.GLUTEN_OFFHEAP_SIZE_IN_BYTES_KEY()))))
        .append(System.lineSeparator())
        .append(
            String.format(
                "\t%s=%s",
                GlutenConfig$.MODULE$.GLUTEN_TASK_OFFHEAP_SIZE_IN_BYTES_KEY(),
                reformatBytes(
                    SQLConf.get()
                        .getConfString(
                            GlutenConfig$.MODULE$.GLUTEN_TASK_OFFHEAP_SIZE_IN_BYTES_KEY()))))
        .append(System.lineSeparator());
    // Dump all consumer usages to exception body
    errorBuilder.append(SparkMemoryUtil.dumpMemoryConsumerStats(target.getTaskMemoryManager()));
    errorBuilder.append(System.lineSeparator());
    throw new OutOfMemoryException(errorBuilder.toString());
  }

  private static String reformatBytes(String in) {
    return Utils.bytesToString(Utils.byteStringAsBytes(in));
  }

  @Override
  public long repay(long size) {
    return target.repay(size);
  }

  @Override
  public String name() {
    return target.name();
  }

  @Override
  public long usedBytes() {
    return target.usedBytes();
  }

  @Override
  public MemoryUsageStats stats() {
    return target.stats();
  }

  public static class OutOfMemoryException extends RuntimeException {
    public OutOfMemoryException(String message) {
      super(message);
    }
  }
}
