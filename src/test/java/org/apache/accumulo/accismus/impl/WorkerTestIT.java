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

import java.util.HashMap;
import java.util.Map;

import org.apache.accumulo.accismus.api.Column;
import org.apache.accumulo.accismus.api.ColumnIterator;
import org.apache.accumulo.accismus.api.Observer;
import org.apache.accumulo.accismus.api.RowIterator;
import org.apache.accumulo.accismus.api.ScannerConfiguration;
import org.apache.accumulo.accismus.api.Transaction;
import org.apache.accumulo.accismus.impl.TransactionImpl.CommitData;
import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Range;
import org.apache.hadoop.io.Text;
import org.junit.Assert;
import org.junit.Test;

/**
 * A simple test that added links between nodes in a graph.  There is an observer
 * that updates an index of node degree.
 */
public class WorkerTestIT extends Base {
  
  private static final ByteSequence NODE_CF = new ArrayByteSequence("node");

  protected Map<Column,String> getObservers() {
    Map<Column,String> observed = new HashMap<Column,String>();
    observed.put(new Column("attr", "lastupdate"), DegreeIndexer.class.getName());
    return observed;
  }

  static class DegreeIndexer implements Observer {
    
    public void process(Transaction tx, ByteSequence row, Column col) throws Exception {
      // get previously calculated degree
      
      ByteSequence degree = tx.get(row, new Column("attr", "degree"));

      // calculate new degree
      int count = 0;
      RowIterator riter = tx.get(new ScannerConfiguration().setRange(Range.exact(new Text(row.toArray()), new Text("link"))));
      while (riter.hasNext()) {
        ColumnIterator citer = riter.next().getValue();
        while (citer.hasNext()) {
          citer.next();
          count++;
        }
      }
      String degree2 = "" + count;
      
      if (degree == null || !degree.toString().equals(degree2)) {
        tx.set(row, new Column("attr", "degree"), new ArrayByteSequence(degree2));
        
        // put new entry in degree index
        tx.set("IDEG" + degree2, new Column(NODE_CF, row), "");
      }
      
      if (degree != null) {
        // delete old degree in index
        tx.delete("IDEG" + degree, new Column(NODE_CF, row));
      }
    }
  }
  
  
  
  @Test
  public void test1() throws Exception {
    
    TransactionImpl tx1 = new TransactionImpl(config);

    //add a link between two nodes in a graph    
    tx1.set("N0003", new Column("link", "N0040"), "");
    tx1.set("N0003", new Column("attr", "lastupdate"), System.currentTimeMillis() + "");
    
    tx1.commit();
    
    TransactionImpl tx2 = new TransactionImpl(config);
    
    //add a link between two nodes in a graph    
    tx2.set("N0003", new Column("link", "N0020"), "");
    tx2.set("N0003", new Column("attr", "lastupdate"), System.currentTimeMillis() + "");
    
    tx2.commit();
    
    runWorker();
   
    //verify observer updated degree index 
    TransactionImpl tx3 = new TransactionImpl(config);
    Assert.assertEquals("2", tx3.get("N0003", new Column("attr", "degree")).toString());
    Assert.assertEquals("", tx3.get("IDEG2", new Column("node", "N0003")).toString());
    
    //add a link between two nodes in a graph    
    tx3.set("N0003", new Column("link", "N0010"), "");
    tx3.set("N0003", new Column("attr", "lastupdate"), System.currentTimeMillis() + "");
    tx3.commit();
    
    runWorker();
    
    //verify observer updated degree index.  Should have deleted old index entry 
    //and added a new one 
    TransactionImpl tx4 = new TransactionImpl(config);
    Assert.assertEquals("3", tx4.get("N0003", new Column("attr", "degree")).toString());
    Assert.assertNull("", tx4.get("IDEG2", new Column("node", "N0003")));
    Assert.assertEquals("", tx4.get("IDEG3", new Column("node", "N0003")).toString());
    
    // test rollback
    TransactionImpl tx5 = new TransactionImpl(config);
    tx5.set("N0003", new Column("link", "N0030"), "");
    tx5.set("N0003", new Column("attr", "lastupdate"), System.currentTimeMillis() + "");
    tx5.commit();
    
    TransactionImpl tx6 = new TransactionImpl(config);
    tx6.set("N0003", new Column("link", "N0050"), "");
    tx6.set("N0003", new Column("attr", "lastupdate"), System.currentTimeMillis() + "");
    CommitData cd = tx6.createCommitData();
    tx6.preCommit(cd, new ArrayByteSequence("N0003"), new Column("attr", "lastupdate"));

    runWorker();
    
    TransactionImpl tx7 = new TransactionImpl(config);
    Assert.assertEquals("4", tx7.get("N0003", new Column("attr", "degree")).toString());
    Assert.assertNull("", tx7.get("IDEG3", new Column("node", "N0003")));
    Assert.assertEquals("", tx7.get("IDEG4", new Column("node", "N0003")).toString());
  }
  
  // TODO test that observers trigger on delete
}
