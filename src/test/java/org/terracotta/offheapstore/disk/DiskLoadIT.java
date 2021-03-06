/* 
 * Copyright 2015 Terracotta, Inc., a Software AG company.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.offheapstore.disk;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Ignore;
import org.junit.Test;

import org.terracotta.offheapstore.concurrent.ConcurrentOffHeapHashMap;
import org.terracotta.offheapstore.disk.paging.MappedPageSource;
import org.terracotta.offheapstore.disk.storage.FileBackedStorageEngine;
import org.terracotta.offheapstore.disk.storage.portability.PersistentByteArrayPortability;
import org.terracotta.offheapstore.disk.storage.portability.PersistentSerializablePortability;
import org.terracotta.offheapstore.storage.StorageEngine;
import org.terracotta.offheapstore.util.Factory;
import org.terracotta.offheapstore.util.MemoryUnit;

/**
 *
 * @author cdennis
 */
@Ignore("performance test")
public class DiskLoadIT {

  private static final int BATCH = 50000;
  private static final int THREADS = 1;
  private static final int PAYLOAD = 750;
  private static final int SIZE = 1000000;
  private static final int SEGMENTS = 1;
  
  @Test
  public void testConcurrentMapLoading() throws IOException, InterruptedException, ExecutionException {
    File dataFile = new File("loadtest.data");
    dataFile.deleteOnExit();
    final MappedPageSource source = new MappedPageSource(dataFile);
    final ConcurrentOffHeapHashMap<Integer, byte[]> map = new ConcurrentOffHeapHashMap<Integer, byte[]>(source, new Factory<StorageEngine<Integer, byte[]>>() {
      @Override
      public StorageEngine<Integer, byte[]> newInstance() {
        ThreadPoolExecutor e = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1000), new RejectedExecutionHandler() {

          @Override
          public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            boolean interrupted = false;
            try {
              while (true) {
                try {
                  executor.getQueue().put(r);
                  return;
                } catch (InterruptedException e) {
                  interrupted = true;
                }
              }
            } finally {
              if (interrupted) {
                Thread.currentThread().interrupt();
              }
            }
          }
        });
        return new FileBackedStorageEngine<Integer, byte[]>(source, Long.MAX_VALUE, MemoryUnit.BYTES, new PersistentSerializablePortability(), PersistentByteArrayPortability.INSTANCE, e);
      }
    }, 1, SEGMENTS);
    try {
      final Thread[] threads = new Thread[THREADS];

      for (int i = 0; i < threads.length; i++) {
        final int current = i;
        threads[current] = new Thread() {
          @Override
          public void run() {
            int start = (SIZE / threads.length) * current;
            int end = (SIZE / threads.length) * (current + 1);

            for (int i = start; i < end; ) {
              long startTime = System.nanoTime();
              for (int c = 0; c < BATCH; c++, i++) {
                map.put(i, new byte[PAYLOAD]);
              }
              long endTime = System.nanoTime();
              System.err.println(map.size() +"," + (endTime - startTime));
            }
          }
        };
      }

      for (Thread t : threads) {
        t.start();
      }

      for (Thread t : threads) {
        t.join();
      }
    } finally {
      source.close();
    }
  }
}
