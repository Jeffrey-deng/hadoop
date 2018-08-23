/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapreduce.task;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.mapred.BackupStore;
import org.apache.hadoop.mapred.RawKeyValueIterator;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.ReduceContext;
import org.apache.hadoop.mapreduce.StatusReporter;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.util.Progressable;

/**
 * The context passed to the {@link Reducer}.
 * @param <KEYIN> the class of the input keys
 * @param <VALUEIN> the class of the input values
 * @param <KEYOUT> the class of the output keys
 * @param <VALUEOUT> the class of the output values
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class ReduceContextImpl<KEYIN,VALUEIN,KEYOUT,VALUEOUT>
    extends TaskInputOutputContextImpl<KEYIN,VALUEIN,KEYOUT,VALUEOUT> 
    implements ReduceContext<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {
  private RawKeyValueIterator input;
  private Counter inputValueCounter;
  private Counter inputKeyCounter;
  private RawComparator<KEYIN> comparator;
  private KEYIN key;                                  // current key 下一行的key
  private VALUEIN value;                              // current value  下一行的值
  private boolean firstValue = false;                 // first value in key
  private boolean nextKeyIsSame = false;              // more w/ this key 下一个key是不是还是一个组的
  private boolean hasMore;                            // more in file 是否还有输入
  protected Progressable reporter;
  private Deserializer<KEYIN> keyDeserializer;
  private Deserializer<VALUEIN> valueDeserializer;
  private DataInputBuffer buffer = new DataInputBuffer();
  private BytesWritable currentRawKey = new BytesWritable();
  private ValueIterable iterable = new ValueIterable(); // 一个组的values迭代器，既reduce方法中传入的values
  private boolean isMarked = false;
  private BackupStore<KEYIN,VALUEIN> backupStore;
  private final SerializationFactory serializationFactory;
  private final Class<KEYIN> keyClass;
  private final Class<VALUEIN> valueClass;
  private final Configuration conf;
  private final TaskAttemptID taskid;
  private int currentKeyLength = -1;
  private int currentValueLength = -1;
  
  public ReduceContextImpl(Configuration conf, TaskAttemptID taskid,
                           RawKeyValueIterator input, 
                           Counter inputKeyCounter,
                           Counter inputValueCounter,
                           RecordWriter<KEYOUT,VALUEOUT> output,
                           OutputCommitter committer,
                           StatusReporter reporter,
                           RawComparator<KEYIN> comparator,
                           Class<KEYIN> keyClass,
                           Class<VALUEIN> valueClass
                          ) throws InterruptedException, IOException{
    super(conf, taskid, output, committer, reporter);
    this.input = input;
    this.inputKeyCounter = inputKeyCounter;
    this.inputValueCounter = inputValueCounter;
    this.comparator = comparator;
    this.serializationFactory = new SerializationFactory(conf);
    this.keyDeserializer = serializationFactory.getDeserializer(keyClass);
    this.keyDeserializer.open(buffer); // 这里传入的buffer
    this.valueDeserializer = serializationFactory.getDeserializer(valueClass);
    this.valueDeserializer.open(buffer); // 这里传入的buffer
    hasMore = input.next();
    this.keyClass = keyClass;
    this.valueClass = valueClass;
    this.conf = conf;
    this.taskid = taskid;
  }

  /**
   * Start processing next unique key.
   * 返回是否有新的行, 新的行既新的组
   * 因为：
   *  1、因为已经排序的，所以每个组的数据连在一块，这里也说明分组比较器强依赖于排序比较器;
   *  2、里面调用 nextKeyValue() 来判断是否有新行;
   *  3、nextKeyIsSame（标记下一行是否同一个组）的更新则是在：用户在遍历values时更新;
   *  4、当用户在reduce方法时未迭代完value时，此方法会清除掉这个组剩下的key;
   */
  public boolean nextKey() throws IOException,InterruptedException {
    // 这个循环是假设用户的reducer中未迭代完一个组的values的数据，这个迭代完（清除）这个组剩下的
    // nextKeyIsSame默认值为false, 所以第一次不会运行
    while (hasMore && nextKeyIsSame) {
      nextKeyValue();
    }
    if (hasMore) {  // 如果还有输入
      if (inputKeyCounter != null) {
        inputKeyCounter.increment(1);
      }
      return nextKeyValue(); // 返回是否有新行，而 是不是 一个组在用户执行迭代器时更新
    } else {
      return false;
    }
  }

  /**
   * Advance to the next key/value pair.
   * 判断有没有新行
   * 同时更新currentKey，currentValue引用的值
   * 再更新firstValue：是否是一个新组
   * 再更新nextKeyIsSame：下一行是否是同一个组的
   */
  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    if (!hasMore) { // 没有新行返回false
      key = null;
      value = null;
      return false;
    }
    firstValue = !nextKeyIsSame; // 当nextKeyIsSame为false时，标记为新的一组
    DataInputBuffer nextKey = input.getKey(); //从内存或磁盘中去到先前执行input.next()读取的一行的key
    currentRawKey.set(nextKey.getData(), nextKey.getPosition(), 
                      nextKey.getLength() - nextKey.getPosition());
    buffer.reset(currentRawKey.getBytes(), 0, currentRawKey.getLength()); // 添加序列化数据到buffer中
    /**
     * 反序列出key
     * 这里只传入key有点奇怪，其实是：context初始化时keyDeserializer.open(buffer)传入了buffer，所以这是从buffer中反序列化出key
     * keyDeserializer为用户设置的序列化器，默认为JavaSerializationDeserializer，还有AvroSerializer
     */
    key = keyDeserializer.deserialize(key);
    DataInputBuffer nextVal = input.getValue(); //从内存或磁盘中去到先前执行input.next()读取的一行的value
    buffer.reset(nextVal.getData(), nextVal.getPosition(), nextVal.getLength()
        - nextVal.getPosition());
    // 反序列出value
    value = valueDeserializer.deserialize(value);

    currentKeyLength = nextKey.getLength() - nextKey.getPosition();
    currentValueLength = nextVal.getLength() - nextVal.getPosition();

    if (isMarked) {
      backupStore.write(nextKey, nextVal);
    }

    hasMore = input.next(); // 读取一行, 放心，这一行的数据可以通过下次运行时执行input.getKey()，input.getValue()得到
    if (hasMore) { //如果还有一行
      nextKey = input.getKey();
      // 通过设置的组比较器判断这个key是不是同一个组的
      nextKeyIsSame = comparator.compare(currentRawKey.getBytes(), 0, 
                                     currentRawKey.getLength(),
                                     nextKey.getData(),
                                     nextKey.getPosition(),
                                     nextKey.getLength() - nextKey.getPosition()
                                         ) == 0;
    } else {
      nextKeyIsSame = false; // 没有新行了，nextKeyIsSame赋值为false
    }
    inputValueCounter.increment(1);
    return true;  // 有新行直接放回true
  }

  public KEYIN getCurrentKey() {
    return key;
  }

  @Override
  public VALUEIN getCurrentValue() {
    return value;
  }
  
  BackupStore<KEYIN,VALUEIN> getBackupStore() {
    return backupStore;
  }

  // 一个组的values迭代器，既reduce方法中传入的values
  protected class ValueIterator implements ReduceContext.ValueIterator<VALUEIN> {

    private boolean inReset = false;
    private boolean clearMarkFlag = false;

    @Override
    public boolean hasNext() {  // 判断这个组还有没有值
      try {
        if (inReset && backupStore.hasNext()) {
          return true;
        } 
      } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException("hasNext failed", e);
      }
      // 如果这是这个组的第一个值，firstValue的会在外部调用nextKey(), 然后nextKey()里调用nextKeyValue()时更新为true,
      // 而nextKeyIsSame也是在调用nextKeyValue()时更新;
      return firstValue || nextKeyIsSame; // 如果是这是一个新组或下一行也是这个组的，返回true
    }

    /**
     * // 得到这个组当前一行的key,value，注意currentKey也会更新
     * 如果是firstValue，就直接返回currentValue，原因是在调用nextKey()时，执行了一次nextKeyValue()，已经更新了currentKey,currentValue;
     * 如果不是，调用nextKeyValue()更新currentKey,currentValue，同时更新nextKeyIsSame;
     */
    @Override
    public VALUEIN next() {
      if (inReset) {  // 如果用户不是第一遍迭代
        try {
          if (backupStore.hasNext()) {  // 从backupStore中缓存的取
            backupStore.next();
            DataInputBuffer next = backupStore.nextValue();
            buffer.reset(next.getData(), next.getPosition(), next.getLength()
                - next.getPosition());
            value = valueDeserializer.deserialize(value);
            return value;
          } else {
            inReset = false;
            backupStore.exitResetMode();
            if (clearMarkFlag) {
              clearMarkFlag = false;
              isMarked = false;
            }
          }
        } catch (IOException e) {
          e.printStackTrace();
          throw new RuntimeException("next value iterator failed", e);
        }
      } 

      // if this is the first record, we don't need to advance
      if (firstValue) { // 如果是第一行，在执行context.nextKey时就保存了这一行的key, value，直接返回
        firstValue = false;
        return value;
      }
      // if this isn't the first record and the next key is different, they
      // can't advance it here.
      if (!nextKeyIsSame) { // 如果不是第一行，但是下一行不是这个组的话，报错
        throw new NoSuchElementException("iterate past last value");
      }
      // otherwise, go to the next key/value pair
      try {
        nextKeyValue(); // 调用nextKeyValue更新currentKey,currentValue
        return value; // 返回currentValue
      } catch (IOException ie) {
        throw new RuntimeException("next value iterator failed", ie);
      } catch (InterruptedException ie) {
        // this is bad, but we can't modify the exception list of java.util
        throw new RuntimeException("next value iterator interrupted", ie);        
      }
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("remove not implemented");
    }

    @Override
    public void mark() throws IOException {
      if (getBackupStore() == null) {
        backupStore = new BackupStore<KEYIN,VALUEIN>(conf, taskid);
      }
      isMarked = true;
      if (!inReset) {
        backupStore.reinitialize();
        if (currentKeyLength == -1) {
          // The user has not called next() for this iterator yet, so
          // there is no current record to mark and copy to backup store.
          return;
        }
        assert (currentValueLength != -1);
        int requestedSize = currentKeyLength + currentValueLength + 
          WritableUtils.getVIntSize(currentKeyLength) +
          WritableUtils.getVIntSize(currentValueLength);
        DataOutputStream out = backupStore.getOutputStream(requestedSize);
        writeFirstKeyValueBytes(out);
        backupStore.updateCounters(requestedSize);
      } else {
        backupStore.mark();
      }
    }

    @Override
    public void reset() throws IOException {
      // We reached the end of an iteration and user calls a 
      // reset, but a clearMark was called before, just throw
      // an exception
      if (clearMarkFlag) {
        clearMarkFlag = false;
        backupStore.clearMark();
        throw new IOException("Reset called without a previous mark");
      }
      
      if (!isMarked) {
        throw new IOException("Reset called without a previous mark");
      }
      inReset = true;
      backupStore.reset();
    }

    @Override
    public void clearMark() throws IOException {
      if (getBackupStore() == null) {
        return;
      }
      if (inReset) {
        clearMarkFlag = true;
        backupStore.clearMark();
      } else {
        inReset = isMarked = false;
        backupStore.reinitialize();
      }
    }
    
    /**
     * This method is called when the reducer moves from one key to 
     * another.
     * @throws IOException
     */
    public void resetBackupStore() throws IOException { // 重置values的备份
      if (getBackupStore() == null) {
        return;
      }
      inReset = isMarked = false;
      backupStore.reinitialize();
      currentKeyLength = -1;
    }

    /**
     * This method is called to write the record that was most recently
     * served (before a call to the mark). Since the framework reads one
     * record in advance, to get this record, we serialize the current key
     * and value
     * @param out
     * @throws IOException
     */
    private void writeFirstKeyValueBytes(DataOutputStream out) 
    throws IOException {
      assert (getCurrentKey() != null && getCurrentValue() != null);
      WritableUtils.writeVInt(out, currentKeyLength);
      WritableUtils.writeVInt(out, currentValueLength);
      Serializer<KEYIN> keySerializer = 
        serializationFactory.getSerializer(keyClass);
      keySerializer.open(out);
      keySerializer.serialize(getCurrentKey());

      Serializer<VALUEIN> valueSerializer = 
        serializationFactory.getSerializer(valueClass);
      valueSerializer.open(out);
      valueSerializer.serialize(getCurrentValue());
    }
  }

  protected class ValueIterable implements Iterable<VALUEIN> {
    private ValueIterator iterator = new ValueIterator(); // 一个组的values迭代器，既reduce方法中传入的values
    @Override
    public Iterator<VALUEIN> iterator() {
      return iterator;
    } 
  }
  
  /**
   * Iterate through the values for the current key, reusing the same value 
   * object, which is stored in the context.
   * @return the series of values associated with the current key. All of the 
   * objects returned directly and indirectly from this method are reused.
   */
  public 
  Iterable<VALUEIN> getValues() throws IOException, InterruptedException {
    return iterable;
  }
}
