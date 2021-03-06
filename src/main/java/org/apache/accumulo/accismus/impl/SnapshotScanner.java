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

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import org.apache.accumulo.accismus.api.Column;
import org.apache.accumulo.accismus.api.ScannerConfiguration;
import org.apache.accumulo.accismus.api.exceptions.StaleScanException;
import org.apache.accumulo.accismus.impl.iterators.PrewriteIterator;
import org.apache.accumulo.accismus.impl.iterators.SnapshotIterator;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.ConditionalWriter;
import org.apache.accumulo.core.client.ConditionalWriter.Status;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Condition;
import org.apache.accumulo.core.data.ConditionalMutation;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.commons.lang.mutable.MutableLong;


/**
 * 
 */
public class SnapshotScanner implements Iterator<Entry<Key,Value>> {

  private long startTs;
  private Iterator<Entry<Key,Value>> iterator;
  private Entry<Key,Value> next;
  private ScannerConfiguration config;

  private Configuration aconfig;

  private static final long INITIAL_WAIT_TIME = 50;
  // TODO make configurable
  private static final long ROLLBACK_TIME = 5000;
  private static final long MAX_WAIT_TIME = 60000;

  public SnapshotScanner(Configuration aconfig, ScannerConfiguration config, long startTs) {
    this.aconfig = aconfig;
    this.config = config;
    this.startTs = startTs;
    
    setUpIterator();
  }
  
  private void setUpIterator() {
    Scanner scanner;
    try {
      scanner = aconfig.getConnector().createScanner(aconfig.getTable(), aconfig.getAuthorizations());
    } catch (TableNotFoundException e) {
      throw new RuntimeException(e);
    }
    config.configure(scanner);
    
    IteratorSetting iterConf = new IteratorSetting(10, SnapshotIterator.class);
    SnapshotIterator.setSnaptime(iterConf, startTs);
    scanner.addScanIterator(iterConf);
    
    this.iterator = scanner.iterator();
  }
  
  public boolean hasNext() {
    if (next == null) {
      next = getNext();
    }
    
    return next != null;
  }
  
  public Entry<Key,Value> next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    
    Entry<Key,Value> tmp = next;
    next = null;
    return tmp;
  }
  
  public Entry<Key,Value> getNext() {
    
    long waitTime = INITIAL_WAIT_TIME;
    long firstSeen = -1;

    mloop: while (true) {
      // its possible a next could exist then be rolled back
      if (!iterator.hasNext())
        return null;

      Entry<Key,Value> entry = iterator.next();

      byte[] cf = entry.getKey().getColumnFamilyData().toArray();
      byte[] cq = entry.getKey().getColumnQualifierData().toArray();
      long colType = entry.getKey().getTimestamp() & ColumnUtil.PREFIX_MASK;

      if (colType == ColumnUtil.LOCK_PREFIX) {
        // TODO do read ahead while waiting for the lock
        
        boolean resolvedLock = false;

        if (firstSeen == -1) {
          firstSeen = System.currentTimeMillis();
          
          // the first time a lock is seen, try to resolve in case the transaction is complete, but this column is still locked.
          resolvedLock = resolveLock(entry, false);
        }

        if (!resolvedLock) {
          UtilWaitThread.sleep(waitTime);
          waitTime = Math.min(MAX_WAIT_TIME, waitTime * 2);
        
          if (System.currentTimeMillis() - firstSeen > ROLLBACK_TIME) {
            // try to abort the transaction
            resolveLock(entry, true);
          }
        }

        Key k = entry.getKey();
        Key start = new Key(k.getRowData().toArray(), cf, cq, k.getColumnVisibilityData().toArray(), Long.MAX_VALUE);
        
        try {
          config = (ScannerConfiguration) config.clone();
        } catch (CloneNotSupportedException e) {
          throw new RuntimeException(e);
        }
        config.setRange(new Range(start, true, config.getRange().getEndKey(), config.getRange().isEndKeyInclusive()));
        setUpIterator();

        continue mloop;
      } else if (colType == ColumnUtil.DATA_PREFIX) {
        waitTime = INITIAL_WAIT_TIME;
        firstSeen = -1;
        return entry;
      } else if (colType == ColumnUtil.WRITE_PREFIX) {
        if (WriteValue.isTruncated(entry.getValue().get())) {
          throw new StaleScanException();
        } else {
          throw new IllegalArgumentException();
        }
      } else {
        throw new IllegalArgumentException();
      }
    }
  }
  
  private boolean resolveLock(Entry<Key,Value> entry, boolean abort) {
    List<ByteSequence> primary = ByteUtil.split(new ArrayByteSequence(entry.getValue().get()));
    
    ByteSequence prow = primary.get(0);
    ByteSequence pfam = primary.get(1);
    ByteSequence pqual = primary.get(2);
    ByteSequence pvis = primary.get(3);

    boolean isPrimary = entry.getKey().getRowData().equals(prow) && entry.getKey().getColumnFamilyData().equals(pfam)
        && entry.getKey().getColumnQualifierData().equals(pqual) && entry.getKey().getColumnVisibilityData().equals(pvis);

    long lockTs = entry.getKey().getTimestamp() & ColumnUtil.TIMESTAMP_MASK;
    
    boolean resolvedLock = false;

    if (isPrimary) {
      if (abort) {
        try {
          rollbackPrimary(prow, pfam, pqual, pvis, lockTs, entry.getValue().get());
        } catch (AccumuloException e) {
          throw new RuntimeException(e);
        } catch (AccumuloSecurityException e) {
          throw new RuntimeException(e);
        }
        resolvedLock = true;
      }
    } else {

      // TODO ensure primary is visible
      // TODO reususe scanner?
      try {
        
        Value lockVal = new Value();
        MutableLong commitTs = new MutableLong(-1);
        // TODO use cached CV
        TxStatus txStatus = TxStatus.getTransactionStatus(aconfig, prow, new Column(pfam, pqual).setVisibility(new ColumnVisibility(pvis.toArray())), lockTs,
            commitTs, lockVal);
        
        switch (txStatus) {
          case COMMITTED:
            if (commitTs.longValue() < lockTs) {
              throw new IllegalStateException("bad commitTs : " + prow + " " + pfam + " " + pqual + " " + pvis + " (" + commitTs.longValue() + "<" + lockTs
                  + ")");
            }
            commitColumn(entry, lockTs, commitTs.longValue());
            resolvedLock = true;
            break;
          case LOCKED:
            if (abort) {
              if (rollbackPrimary(prow, pfam, pqual, pvis, lockTs, lockVal.get())) {
                rollback(entry.getKey(), lockTs);
                resolvedLock = true;
              }
            }
            break;
          case ROLLED_BACK:
            // TODO ensure this if ok if there concurrent rollback
            rollback(entry.getKey(), lockTs);
            resolvedLock = true;
            break;
          case UNKNOWN:
            if (abort) {
              throw new IllegalStateException("can not abort : " + prow + " " + pfam + " " + pqual + " " + pvis + " (" + txStatus + ")");
            }
            break;
        }

      } catch (Exception e) {
        // TODO proper exception handling
        throw new RuntimeException(e);
      }
    }
    
    return resolvedLock;
  }

  private void commitColumn(Entry<Key,Value> entry, long lockTs, long commitTs) {
    LockValue lv = new LockValue(entry.getValue().get());
    boolean isTrigger = lv.getObserver().length() > 0;
    // TODO cache col vis
    Column col = new Column(entry.getKey().getColumnFamilyData(), entry.getKey().getColumnQualifierData()).setVisibility(entry.getKey()
        .getColumnVisibilityParsed());
    Mutation m = new Mutation(entry.getKey().getRowData().toArray());
    
    ColumnUtil.commitColumn(isTrigger, false, col, lv.isWrite(), lockTs, commitTs, aconfig.getObservers().keySet(), m);
    
    try {
      // TODO use conditional writer?
      // TODO use shared batch writer
      BatchWriter bw = aconfig.getConnector().createBatchWriter(aconfig.getTable(), new BatchWriterConfig());
      bw.addMutation(m);
      bw.close();
    } catch (TableNotFoundException e) {
      throw new RuntimeException(e);
    } catch (MutationsRejectedException e) {
      throw new RuntimeException(e);
    }
  }

  private void rollback(Key k, long lockTs) {
    Mutation mut = new Mutation(k.getRowData().toArray());
    mut.put(k.getColumnFamilyData().toArray(), k.getColumnQualifierData().toArray(), k.getColumnVisibilityParsed(), ColumnUtil.DEL_LOCK_PREFIX | startTs,
        DelLockValue.encode(lockTs, false, true));
    
    try {
      // TODO use conditional writer?
      // TODO use shared batch writer
      BatchWriter bw = aconfig.getConnector().createBatchWriter(aconfig.getTable(), new BatchWriterConfig());
      bw.addMutation(mut);
      bw.close();
    } catch (TableNotFoundException e) {
      throw new RuntimeException(e);
    } catch (MutationsRejectedException e) {
      throw new RuntimeException(e);
    }
  }

  boolean rollbackPrimary(ByteSequence prow, ByteSequence pfam, ByteSequence pqual, ByteSequence pvis, long lockTs, byte[] val) throws AccumuloException,
      AccumuloSecurityException {
    // TODO use cached CV
    ColumnVisibility cv = new ColumnVisibility(pvis.toArray());
    
    // TODO avoid conversions to arrays
    // TODO review use of PrewriteIter here

    IteratorSetting iterConf = new IteratorSetting(10, PrewriteIterator.class);
    PrewriteIterator.setSnaptime(iterConf, startTs);
    // TODO cache col vis?
    ConditionalMutation delLockMutation = new ConditionalMutation(prow, new Condition(pfam, pqual).setIterators(iterConf).setVisibility(cv).setValue(val));
    
    // TODO sanity check on lockTs vs startTs
    
    delLockMutation.put(pfam.toArray(), pqual.toArray(), cv, ColumnUtil.DEL_LOCK_PREFIX | startTs, DelLockValue.encode(lockTs, true, true));
    
    ConditionalWriter cw = null;
    try {
      cw = aconfig.createConditionalWriter();
      
      // TODO handle other conditional writer cases
      return cw.write(delLockMutation).getStatus() == Status.ACCEPTED;
    } catch (TableNotFoundException e) {
      // TODO Auto-generated catch block
      throw new RuntimeException(e);
    } finally {
      if (cw != null)
        cw.close();
    }
    

  }

  public void remove() {
    iterator.remove();
  }
}
