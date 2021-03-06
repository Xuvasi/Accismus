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
package org.apache.accumulo.accismus.api;

import java.util.Properties;

import org.apache.accumulo.accismus.api.config.AccismusProperties;
import org.apache.accumulo.accismus.impl.Configuration;
import org.apache.accumulo.accismus.impl.TransactionImpl;

/**
 * 
 */

// TODO determine better way for users to get snapshots and LoaderExecturs... defering until multiple tables are supported

public class SnapshotFactory {
  
  private Configuration config;

  /**
   * 
   * @param props
   *          see {@link AccismusProperties}
   */
  
  public SnapshotFactory(Properties props) {
    try {
      this.config = new Configuration(props);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public Snapshot createSnapshot(){
    try {
      return new TransactionImpl(config);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
