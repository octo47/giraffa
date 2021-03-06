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
package org.apache.giraffa.hbase;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.giraffa.FileField;
import org.apache.giraffa.GiraffaConfiguration;
import org.apache.giraffa.INode;
import org.apache.giraffa.RowKey;
import org.apache.giraffa.RowKeyBytes;
import org.apache.giraffa.RowKeyFactory;
import org.apache.giraffa.hbase.NamespaceAgent.BlockAction;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.util.Time;

import com.google.common.collect.Iterables;

/**
 * INodeManager maintains a mapping from Giraffa file INodes to HBase
 * table rows representing corresponding files.
 */
public class INodeManager implements Closeable {
  private final CoprocessorEnvironment env;
  private final String nsTableName;
  private final ThreadLocal<HTableInterface> nsTable =
      new ThreadLocal<HTableInterface>();

  private static final Log LOG = LogFactory.getLog(INodeManager.class);

  public INodeManager(Configuration conf, CoprocessorEnvironment env) {
    this.nsTableName = conf.get(GiraffaConfiguration.GRFA_TABLE_NAME_KEY,
        GiraffaConfiguration.GRFA_TABLE_NAME_DEFAULT);
    this.env = env;
  }

  @Override
  public void close() {
    HTableInterface client = nsTable.get();
    try {
      if(client != null) {
        client.close();
        nsTable.remove();
      }
    } catch (IOException e) {
      LOG.error("Cannot close table: ",e);
    }
  }

  /**
   * Fetch an INode by source path String
   * @param path the source path String
   * @return INode for the specified path
   */
  public INode getINode(String path) throws IOException {
    return getINode(RowKeyFactory.newInstance(path));
  }

  /**
   * Fetch an INode, by RowKey.
   * @param key the RowKey
   * @return INode with the specified RowKey
   */
  public INode getINode(RowKey key) throws IOException {
    Result nodeInfo = getNSTable().get(new Get(key.getKey()));
    if(nodeInfo.isEmpty()) {
      LOG.debug("File does not exist: " + key.getPath());
      return null;
    }
    return newINode(key.getPath(), nodeInfo);
  }

  /**
   * Commit the fields of the given INode into HBase.
   */
  public void updateINode(INode node) throws IOException {
    updateINode(node, null);
  }

  /**
   * Commit the fields of the give INode into HBase. Additional stores a
   * BlockAction for processing by the BlockManagementAgent.
   */
  public void updateINode(INode node, BlockAction ba)
      throws IOException {
    long ts = Time.now();
    RowKey key = node.getRowKey();
    byte[] family = FileField.getFileAttributes();
    Put put = new Put(node.getRowKey().getKey(), ts);
    put.add(family, FileField.getFileName(), ts,
            RowKeyBytes.toBytes(new Path(key.getPath()).getName()))
        .add(family, FileField.getUserName(), ts,
            RowKeyBytes.toBytes(node.getOwner()))
        .add(family, FileField.getGroupName(), ts,
            RowKeyBytes.toBytes(node.getGroup()))
        .add(family, FileField.getLength(), ts,
            Bytes.toBytes(node.getLen()))
        .add(family, FileField.getPermissions(), ts,
            Bytes.toBytes(node.getPermission().toShort()))
        .add(family, FileField.getMTime(), ts,
            Bytes.toBytes(node.getModificationTime()))
        .add(family, FileField.getATime(), ts,
            Bytes.toBytes(node.getAccessTime()))
        .add(family, FileField.getDsQuota(), ts,
            Bytes.toBytes(node.getDsQuota()))
        .add(family, FileField.getNsQuota(), ts,
            Bytes.toBytes(node.getNsQuota()))
        .add(family, FileField.getReplication(), ts,
            Bytes.toBytes(node.getReplication()))
        .add(family, FileField.getBlockSize(), ts,
            Bytes.toBytes(node.getBlockSize()))
        .add(family, FileField.getRenameState(), ts,
            node.getRenameStateBytes());

    // symlink
    if(node.getSymlink() != null) {
      put.add(family, FileField.getSymlink(), ts, node.getSymlink());
    }

    // file/directory specific columns
    if(node.isDir()) {
      put.add(family, FileField.getDirectory(), ts,
          Bytes.toBytes(node.isDir()));
    }
    else {
      put.add(family, FileField.getBlock(), ts, node.getBlocksBytes())
          .add(family, FileField.getLocations(), ts, node.getLocationsBytes())
          .add(family, FileField.getFileState(), ts,
              Bytes.toBytes(node.getFileState().toString()));
    }

    // block action
    if(ba != null) {
      put.add(family, FileField.getAction(), ts, Bytes.toBytes(ba.toString()));
    }

    getNSTable().put(put);
  }

  /**
   * Apply the given function to each child of the specified directory.
   * @param root the directory whose children to scan
   * @param f the function to apply to each child INode
   */
  public void map(INode root, Function f) throws IOException {
    map(root, HdfsFileStatus.EMPTY_NAME, Integer.MAX_VALUE, f);
  }

  /**
   * Apply the given function to each child of the specified directory.
   * @param root the directory whose children to scan
   * @param startAfter the name to start scanning after encoded in java UTF8
   * @param limit the maximum number of nodes to scan
   * @param f the function to apply to each child INode
   */
  public void map(INode root, byte[] startAfter, int limit, Function f)
      throws IOException {
    RowKey key = root.getRowKey();
    ResultScanner rs = getListingScanner(key, startAfter);
    try {
      for(Result result : Iterables.limit(rs, limit)) {
        f.apply(newINodeByParent(key.getPath(), result));
      }
    } finally {
      rs.close();
    }
  }

  /**
   * Get a partial listing of the indicated directory.
   * @param dir the directory to list
   * @param startAfter the name to start listing after encoded in java UTF8
   * @param limit the maximum number of nodes to list
   * @return a list of INodes representing the children of the given directory
   */
  public List<INode> getListing(INode dir, byte[] startAfter, int limit)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    final List<INode> nodes = new ArrayList<INode>();
    map(dir, startAfter, limit, new Function() {
      @Override
      public void apply(INode input) throws IOException {
        nodes.add(input);
      }
    });
    return nodes;
  }

  /**
   * Recursively generates a list containing the given node and all
   * subdirectories. The nodes are found and stored in breadth-first order.
   */
  public List<INode> getDirectories(INode root) throws IOException {
    List<INode> directories = new ArrayList<INode>();
    directories.add(root);

    // start loop descending the tree (breadth first, then depth)
    for(int i = 0; i < directories.size(); i++) {
      // get next directory INode in the list and it's Scanner
      RowKey key = directories.get(i).getRowKey();
      ResultScanner rs = getListingScanner(key);
      try {
        for (Result result : rs) {
          if (FileFieldDeserializer.getDirectory(result)) {
            directories.add(newINodeByParent(key.getPath(), result));
          }
        }
      } finally {
        rs.close();
      }
    }

    return directories;
  }

  /**
   * Returns whether the given directory has any children.
   */
  public boolean isEmptyDirectory(INode dir) throws IOException {
    ResultScanner rs = getListingScanner(dir.getRowKey());
    try {
      return rs.next() == null;
    } finally {
      rs.close();
    }
  }

  /**
   * Deletes the given node's row from HBase.
   */
  public void delete(INode node) throws IOException {
    getNSTable().delete(new Delete(node.getRowKey().getKey()));
  }

  /**
   * Batch deletes the given nodes' rows from HBase
   */
  public void delete(List<INode> nodes) throws IOException {
    List<Delete> deletes = new ArrayList<Delete>();
    for(INode node : nodes) {
      deletes.add(new Delete(node.getRowKey().getKey()));
    }
    getNSTable().delete(deletes);
  }

  /**
   * Gets the blocks and locations for the given INode from HBase and updates
   * the INode with the obtained information.
   */
  public void getBlocksAndLocations(INode node) throws IOException {
    Result result = getNSTable().get(new Get(node.getRowKey().getKey()));
    node.setBlocks(FileFieldDeserializer.getBlocks(result));
    node.setLocations(FileFieldDeserializer.getLocations(result));
  }

  private HTableInterface getNSTable() {
    openTable();
    return nsTable.get();
  }

  private void openTable() {
    HTableInterface client = nsTable.get();
    if(client != null)
      return;
    try {
      client = env.getTable(TableName.valueOf(nsTableName));
      nsTable.set(client);
    } catch (IOException e) {
      LOG.error("Cannot get table: " + nsTableName, e);
    }
  }

  private INode newINodeByParent(String parent, Result res) throws IOException {
    String fileName = FileFieldDeserializer.getFileName(res);
    return newINode(new Path(parent, fileName).toString(), res);
  }

  private INode newINode(String src, Result result) throws IOException {
    RowKey key = RowKeyFactory.newInstance(src, result.getRow());
    boolean directory = FileFieldDeserializer.getDirectory(result);
    return new INode(
        FileFieldDeserializer.getLength(result),
        directory,
        FileFieldDeserializer.getReplication(result),
        FileFieldDeserializer.getBlockSize(result),
        FileFieldDeserializer.getMTime(result),
        FileFieldDeserializer.getATime(result),
        FileFieldDeserializer.getPermissions(result),
        FileFieldDeserializer.getUserName(result),
        FileFieldDeserializer.getGroupName(result),
        FileFieldDeserializer.getSymlink(result),
        key,
        FileFieldDeserializer.getDsQuota(result),
        FileFieldDeserializer.getNsQuota(result),
        directory ? null : FileFieldDeserializer.getFileState(result),
        FileFieldDeserializer.getRenameState(result),
        directory ? null : FileFieldDeserializer.getBlocks(result),
        directory ? null : FileFieldDeserializer.getLocations(result));
  }

  private ResultScanner getListingScanner(RowKey key)
      throws IOException {
    return getListingScanner(key, HdfsFileStatus.EMPTY_NAME);
  }

  private ResultScanner getListingScanner(RowKey key, byte[] startAfter)
      throws IOException {
    byte[] start = key.getStartListingKey(startAfter);
    byte[] stop = key.getStopListingKey();
    return getNSTable().getScanner(new Scan(start, stop));
  }

  public interface Function {
    void apply(INode input) throws IOException;
  }
}
