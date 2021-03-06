/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.accismus.impl;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.accumulo.accismus.tools.WorkerTool;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.log4j.Logger;

/**
 * 
 */
public class WorkerTask implements Runnable {

  // TODO max sleep time should probably be a function of the total number of threads in the system
  private static long MAX_SLEEP_TIME = 5 * 60 * 1000;

  private static Logger log = Logger.getLogger(WorkerTool.class);
  private Configuration config;
  private AtomicBoolean shutdownFlag;

  public WorkerTask(Configuration config, AtomicBoolean shutdownFlag) {
    this.config = config;
    this.shutdownFlag = shutdownFlag;
  }

  @Override
  public void run() {
    // TODO handle worker dying
    Worker worker = null;
    try {
      worker = new Worker(config, new RandomTabletChooser(config));
    } catch (Exception e1) {
      log.error("Error while processing updates", e1);
      throw new RuntimeException(e1);
    }

    long sleepTime = 0;

    while (!shutdownFlag.get()) {
      long numProcessed = 0;
      try {
        numProcessed = worker.processUpdates();
      } catch (Exception e) {
        log.error("Error while processing updates", e);
      }

      if (numProcessed > 0)
        sleepTime = 0;
      else if (sleepTime == 0)
        sleepTime = 100;
      else if (sleepTime < MAX_SLEEP_TIME)
        sleepTime = sleepTime + (long) (sleepTime * Math.random());

      log.debug("thread id:" + Thread.currentThread().getId() + "  numProcessed:" + numProcessed + "  sleepTime:" + sleepTime);

      UtilWaitThread.sleep(sleepTime);
    }
  }

}
