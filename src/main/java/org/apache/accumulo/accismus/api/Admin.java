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

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.apache.accumulo.accismus.api.config.AccismusProperties;
import org.apache.accumulo.accismus.api.config.InitializationProperties;
import org.apache.accumulo.accismus.api.config.WorkerProperties;
import org.apache.accumulo.accismus.impl.Operations;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeMissingPolicy;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooKeeper;

/**
 * 
 */
public class Admin {

  public static class AlreadyInitializedException extends Exception {
    AlreadyInitializedException(Exception e) {
      super(e);
    }
  }

  /**
   * initialize an Accismus instance
   * 
   * @param props
   *          see {@link InitializationProperties}
   */

  public static void initialize(Properties props) throws AlreadyInitializedException {
    try {
      Connector conn = new ZooKeeperInstance(props.getProperty(AccismusProperties.ACCUMULO_INSTANCE_PROP),
          props.getProperty(AccismusProperties.ZOOKEEPER_CONNECT_PROP)).getConnector(props.getProperty(AccismusProperties.ACCUMULO_USER_PROP),
          new PasswordToken(props.getProperty(AccismusProperties.ACCUMULO_PASSWORD_PROP)));

      if (Boolean.valueOf(props.getProperty(InitializationProperties.CLEAR_ZOOKEEPER_PROP, "false"))) {
        ZooKeeper zk = new ZooKeeper(props.getProperty(AccismusProperties.ZOOKEEPER_CONNECT_PROP), 30000, null);
        ZooUtil.recursiveDelete(zk, props.getProperty(AccismusProperties.ZOOKEEPER_ROOT_PROP), NodeMissingPolicy.SKIP);
        zk.close();
      }


      Operations.initialize(conn, props.getProperty(AccismusProperties.ZOOKEEPER_ROOT_PROP), props.getProperty(InitializationProperties.TABLE_PROP));

      updateWorkerConfig(props);

      if (props.getProperty(InitializationProperties.CLASSPATH_PROP) != null) {
        // TODO add accismus version to context name to make it unique
        String contextName = "accismus";
        conn.instanceOperations().setProperty(Property.VFS_CONTEXT_CLASSPATH_PROPERTY.getKey() + "accismus",
            props.getProperty(InitializationProperties.CLASSPATH_PROP));
        conn.tableOperations().setProperty(props.getProperty(InitializationProperties.TABLE_PROP), Property.TABLE_CLASSPATH.getKey(), contextName);
      }
    } catch (NodeExistsException nee) {
      throw new AlreadyInitializedException(nee);
    } catch (Exception e) {
      if (e instanceof RuntimeException)
        throw (RuntimeException) e;
      throw new RuntimeException(e);
    }
  }

  /**
   * 
   * @param props
   *          see {@link WorkerProperties}
   */
  public static void updateWorkerConfig(Properties props) {
    try {
      Connector conn = new ZooKeeperInstance(props.getProperty(AccismusProperties.ACCUMULO_INSTANCE_PROP),
          props.getProperty(AccismusProperties.ZOOKEEPER_CONNECT_PROP)).getConnector(props.getProperty(AccismusProperties.ACCUMULO_USER_PROP),
          new PasswordToken(props.getProperty(AccismusProperties.ACCUMULO_PASSWORD_PROP)));

      Properties workerConfig = new Properties();

      Map<Column,String> colObservers = new HashMap<Column,String>();

      Set<Entry<Object,Object>> entries = props.entrySet();
      for (Entry<Object,Object> entry : entries) {
        String key = (String) entry.getKey();
        if (key.startsWith(WorkerProperties.OBSERVER_PREFIX_PROP)) {
          String val = (String) entry.getValue();
          String[] fields = val.split(",");
          Column col = new Column(fields[0], fields[1]).setVisibility(new ColumnVisibility(fields[2]));
          colObservers.put(col, fields[3]);
        } else if (key.startsWith("accismus.worker")) {
          workerConfig.setProperty((String) entry.getKey(), (String) entry.getValue());
        }
      }

      Operations.updateObservers(conn, props.getProperty(AccismusProperties.ZOOKEEPER_ROOT_PROP), colObservers);
      Operations.updateWorkerConfig(conn, props.getProperty(AccismusProperties.ZOOKEEPER_ROOT_PROP), workerConfig);
    } catch (Exception e) {
      if (e instanceof RuntimeException)
        throw (RuntimeException) e;
      throw new RuntimeException(e);
    }
  }
}
