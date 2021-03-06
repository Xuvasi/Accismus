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

import org.apache.accumulo.core.data.ByteSequence;

/**
 * 
 */

public interface Transaction extends Snapshot {
  
  public abstract void set(String row, Column col, String value);
  
  public abstract void set(byte[] row, Column col, byte[] value);
  
  public abstract void set(ByteSequence row, Column col, ByteSequence value);
  
  public abstract void delete(String row, Column col);
  
  public abstract void delete(byte[] row, Column col);
  
  public abstract void delete(ByteSequence row, Column col);
  
}
