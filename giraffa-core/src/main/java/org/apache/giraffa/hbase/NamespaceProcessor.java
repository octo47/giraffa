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

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.FS_TRASH_INTERVAL_DEFAULT;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.FS_TRASH_INTERVAL_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IO_FILE_BUFFER_SIZE_DEFAULT;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IO_FILE_BUFFER_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_BLOCK_SIZE_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_BLOCK_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_BYTES_PER_CHECKSUM_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_BYTES_PER_CHECKSUM_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CHECKSUM_TYPE_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CHECKSUM_TYPE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CLIENT_WRITE_PACKET_SIZE_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CLIENT_WRITE_PACKET_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_ENCRYPT_DATA_TRANSFER_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_ENCRYPT_DATA_TRANSFER_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_REPLICATION_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_REPLICATION_KEY;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ListIterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.giraffa.GiraffaConfiguration;
import org.apache.giraffa.INode;
import org.apache.giraffa.RenameState;
import org.apache.giraffa.RowKey;
import org.apache.giraffa.RowKeyFactory;
import org.apache.giraffa.UnlocatedBlock;
import org.apache.giraffa.GiraffaConstants.FileState;
import org.apache.giraffa.hbase.INodeManager.Function;
import org.apache.giraffa.hbase.NamespaceAgent.BlockAction;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.Options.Rename;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hdfs.protocol.AlreadyBeingCreatedException;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.CorruptFileBlocks;
import org.apache.hadoop.hdfs.protocol.DSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.NSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.protocol.proto.ClientNamenodeProtocolProtos.ClientNamenodeProtocol;
import org.apache.hadoop.hdfs.security.token.block.DataEncryptionKey;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.namenode.NotReplicatedYetException;
import org.apache.hadoop.hdfs.server.namenode.SafeModeException;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.DataChecksum;

import com.google.protobuf.Service;

/**
  */
public class NamespaceProcessor implements ClientProtocol,
    Coprocessor, CoprocessorService {
  // RPC service fields
  ClientNamenodeProtocolServerSideCallbackTranslatorPB translator =
      new ClientNamenodeProtocolServerSideCallbackTranslatorPB(this);
  Service service = ClientNamenodeProtocol.newReflectiveService(translator);

  private INodeManager nodeManager;

  private FsServerDefaults serverDefaults;

  private int lsLimit;

  private static final Log LOG =
      LogFactory.getLog(NamespaceProcessor.class.getName());
   
  static final FsPermission UMASK = FsPermission.createImmutable((short)0111);
  
  public NamespaceProcessor() {}
  
  @Override // CoprocessorService
  public Service getService() {
    return service;
  }

  @Override // Coprocessor
  public void start(CoprocessorEnvironment env) throws IOException {    
    if (!(env instanceof RegionCoprocessorEnvironment)) {
      throw new CoprocessorException("Must be loaded on a table region!");
    }
    
    LOG.info("Start NamespaceProcessor...");
    Configuration conf = env.getConfiguration();
    RowKeyFactory.registerRowKey(conf);
    int configuredLimit = conf.getInt(
        GiraffaConfiguration.GRFA_LIST_LIMIT_KEY,
        GiraffaConfiguration.GRFA_LIST_LIMIT_DEFAULT);
    this.lsLimit = configuredLimit > 0 ?
        configuredLimit : GiraffaConfiguration.GRFA_LIST_LIMIT_DEFAULT;
    LOG.info("Caching is set to: " + RowKeyFactory.isCaching());
    LOG.info("RowKey is set to: " +
        RowKeyFactory.getRowKeyClass().getCanonicalName());
    
    // Get the checksum type from config
    String checksumTypeStr = conf.get(DFS_CHECKSUM_TYPE_KEY, DFS_CHECKSUM_TYPE_DEFAULT);
    DataChecksum.Type checksumType;
    try {
       checksumType = DataChecksum.Type.valueOf(checksumTypeStr);
    } catch (IllegalArgumentException iae) {
       throw new IOException("Invalid checksum type in "
          + DFS_CHECKSUM_TYPE_KEY + ": " + checksumTypeStr);
    }

    this.nodeManager = new INodeManager(conf, env);
    this.serverDefaults = new FsServerDefaults(
        conf.getLongBytes(DFS_BLOCK_SIZE_KEY, DFS_BLOCK_SIZE_DEFAULT),
        conf.getInt(DFS_BYTES_PER_CHECKSUM_KEY, DFS_BYTES_PER_CHECKSUM_DEFAULT),
        conf.getInt(DFS_CLIENT_WRITE_PACKET_SIZE_KEY,
            DFS_CLIENT_WRITE_PACKET_SIZE_DEFAULT),
        (short) conf.getInt(DFS_REPLICATION_KEY, DFS_REPLICATION_DEFAULT),
        conf.getInt(IO_FILE_BUFFER_SIZE_KEY, IO_FILE_BUFFER_SIZE_DEFAULT),
        conf.getBoolean(DFS_ENCRYPT_DATA_TRANSFER_KEY,
            DFS_ENCRYPT_DATA_TRANSFER_DEFAULT),
        conf.getLong(FS_TRASH_INTERVAL_KEY, FS_TRASH_INTERVAL_DEFAULT),
        checksumType);
  }

  @Override // Coprocessor
  public void stop(CoprocessorEnvironment env) {
    LOG.info("Stopping NamespaceProcessor...");
    nodeManager.close();
  }

  @Override // ClientProtocol
  public void abandonBlock(ExtendedBlock b, String src, String holder)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    throw new IOException("abandonBlock is not supported");
  }

  @Override // ClientProtocol
  public LocatedBlock addBlock(
      String src, String clientName, ExtendedBlock previous, DatanodeInfo[] excludeNodes)
      throws AccessControlException, FileNotFoundException,
      NotReplicatedYetException, SafeModeException, UnresolvedLinkException,
      IOException {
    INode iNode = nodeManager.getINode(src);

    if(iNode == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }

    // Calls addBlock on HDFS by putting another empty Block in HBase
    if(previous != null) {
      // we need to update in HBase the previous block
      iNode.setLastBlock(previous);
    }
    
    // add a Block and modify times
    // (if there was a previous block this call with add it in as well)
    long time = now();
    iNode.setTimes(time, time);
    nodeManager.updateINode(iNode, BlockAction.ALLOCATE);
    
    // grab blocks back from HBase and return the latest one added
    nodeManager.getBlocksAndLocations(iNode);
    List<UnlocatedBlock> al_blks = iNode.getBlocks();
    List<DatanodeInfo[]> al_locs = iNode.getLocations();

    if(al_blks.size() != al_locs.size()) {
      throw new IOException("Number of block infos (" + al_blks.size() +
          ") and number of location infos (" + al_locs.size() +
          ") do not match");
    }

    int last = al_blks.size() - 1;
    if(last < 0)
      throw new IOException("Number of block infos is 0");
    else
      return al_blks.get(last).toLocatedBlock(al_locs.get(last));
  }

  @Override // ClientProtocol
  public LocatedBlock append(String src, String clientName)
      throws AccessControlException, DSQuotaExceededException,
      FileNotFoundException, SafeModeException, UnresolvedLinkException,
      IOException {
    throw new IOException("append is not supported");
  }

  @Override // ClientProtocol
  public void cancelDelegationToken(Token<DelegationTokenIdentifier> token)
      throws IOException {
    throw new IOException("cancelDelegationToken is not supported");
  }

  @Override // ClientProtocol
  public boolean complete(String src, String clientName, ExtendedBlock last)
      throws AccessControlException, FileNotFoundException, SafeModeException,
      UnresolvedLinkException, IOException {
    if(last == null)
      return true;
    INode iNode = nodeManager.getINode(src);

    if(iNode == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }

    // set the state and replace the block, then put the iNode
    iNode.setState(FileState.CLOSED);
    iNode.setLastBlock(last);
    long time = now();
    iNode.setTimes(time, time);
    nodeManager.updateINode(iNode, BlockAction.CLOSE);
    LOG.info("Completed file: "+src+" | BlockID: "+last.getBlockId());
    return true;
  }

  @Override // ClientProtocol
  public void concat(String trg, String[] srcs) throws IOException,
      UnresolvedLinkException {
    throw new IOException("concat is not supported");
  }

  @Override // ClientProtocol
  public void create(
      String src, FsPermission masked, String clientName,
      EnumSetWritable<CreateFlag> createFlag, boolean createParent,
      short replication, long blockSize) throws AccessControlException,
      AlreadyBeingCreatedException, DSQuotaExceededException,
      NSQuotaExceededException, FileAlreadyExistsException,
      FileNotFoundException, ParentNotDirectoryException, SafeModeException,
      UnresolvedLinkException, IOException {
    EnumSet<CreateFlag> flag = createFlag.get();
    boolean overwrite = flag.contains(CreateFlag.OVERWRITE);
    boolean append = flag.contains(CreateFlag.APPEND);
    boolean create = flag.contains(CreateFlag.CREATE);

    if(append) {
      throw new IOException("Append is not supported.");
    }

    INode iFile = nodeManager.getINode(src);
    if(create && !overwrite && iFile != null) {
      throw new FileAlreadyExistsException("File already exists: " + src);
    }

    if(iFile != null && iFile.isDir()) {
      throw new FileAlreadyExistsException("File already exists as directory: "
          + src);
    }

    UserGroupInformation ugi = UserGroupInformation.getLoginUser();
    clientName = ugi.getShortUserName();
    String machineName = (ugi.getGroupNames().length == 0) ? "supergroup" : ugi.getGroupNames()[0];
    masked = new FsPermission((short) 0644);

    Path parentPath = new Path(src).getParent();
    assert parentPath != null : "File must have a parent";
    String parent = parentPath.toString();
    INode iParent = nodeManager.getINode(parent);
    if(!createParent && iParent == null) {
      throw new FileNotFoundException("Parent does not exist: " + src);
    }

    if(iParent == null) { // create parent directories
      if(!mkdirs(parent, masked, true)) {
        throw new IOException("Cannot create parent directories: " + src);
      }
    } else if(!iParent.isDir()) {
      throw new ParentNotDirectoryException("Parent path is not a directory: "
          + src);
    }

    if(overwrite && iFile != null) {
      if(!deleteFile(iFile, true)) {
        throw new IOException("Cannot override existing file: " + src);
      }
    }

    // if file did not exist, create its INode now
    if(iFile == null) {
      RowKey key = RowKeyFactory.newInstance(src);
      long time = now();
      iFile = new INode(0, false, replication, blockSize, time, time,
          masked, clientName, machineName, null,
          key, 0, 0, FileState.UNDER_CONSTRUCTION, null, null, null);
    }

    // add file to HBase (update if already exists)
    nodeManager.updateINode(iFile);
  }

  @Override // ClientProtocol
  public void createSymlink(
      String target, String link, FsPermission dirPerm, boolean createParent)
      throws AccessControlException, FileAlreadyExistsException,
      FileNotFoundException, ParentNotDirectoryException, SafeModeException,
      UnresolvedLinkException, IOException {
    throw new IOException("symlinks are not supported");
  }

  @Deprecated // ClientProtocol
  public boolean delete(String src) throws IOException {
    return delete(src, false);
  }

  /**
   * Delete file(s). If recursive, will start from the lowest subtree, and
   * working up the directory tree, breadth first. This is NOT atomic.
   * If any failure occurs along the way, the deletion process will stop.
   */
  @Override // ClientProtocol
  public boolean delete(String src, boolean recursive) throws IOException {
    //check parent path first
    Path parentPath = new Path(src).getParent();
    assert parentPath != null : "File must have a parent";

    INode node = nodeManager.getINode(src);
    if(node == null) return false;

    // then check parent inode
    INode parent = nodeManager.getINode(parentPath.toString());
    if(parent == null)
      throw new FileNotFoundException("Parent does not exist.");
    if(!parent.isDir())
      throw new ParentNotDirectoryException("Parent is not a directory.");

    if(node.isDir())
      return deleteDirectory(node, recursive, true);

    return deleteFile(node, true);
  }

  private boolean deleteFile(INode node, boolean deleteBlocks)
      throws IOException {
    if(deleteBlocks) {
      node.setState(FileState.DELETED);
      nodeManager.updateINode(node, BlockAction.DELETE);
    }

    // delete the child key atomically first
    nodeManager.delete(node);

    // delete time penalty (resolves timestamp milliseconds issue)
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      // do nothing
    }

    return true;
  }

  /** 
   * The psuedo-recursive function which first deletes all children within 
   * a directory, then the directory itself.
   * If any of the children cannot be deleted the directory itself will not 
   * be deleted as well.
   *
   * @param node the parent INode (a directory)
   * @param recursive whether to delete entire subtree
   * @return true if the directory was actually deleted
   * @throws AccessControlException
   * @throws FileNotFoundException
   * @throws UnresolvedLinkException
   * @throws IOException
   */
  private boolean deleteDirectory(INode node, boolean recursive,
                                  final boolean deleteBlocks)
      throws AccessControlException, FileNotFoundException, 
      UnresolvedLinkException, IOException {
    if(recursive) {
      List<INode> directories = nodeManager.getDirectories(node);
      // start ascending the tree (breadth first, then depth)
      // we do this by iterating through directories in reverse
      ListIterator<INode> it = directories.listIterator(directories.size());
      while (it.hasPrevious()) {
        final List<INode> dirsToDelete = new ArrayList<INode>();
        nodeManager.map(it.previous(), new Function() {
          @Override
          public void apply(INode input) throws IOException {
            if (!input.isDir())
              deleteFile(input, deleteBlocks);
            else
              dirsToDelete.add(input);
          }
        });

        // perform delete (if non-empty)
        if(!dirsToDelete.isEmpty()) {
          nodeManager.delete(dirsToDelete);
        }
      }
    }
    else if(!nodeManager.isEmptyDirectory(node)) {
      return false;
    }

    // delete source directory
    nodeManager.delete(node);
    return true;
  }

  @Override // ClientProtocol
  public void finalizeUpgrade() throws IOException {
    throw new IOException("upgrade is not supported");
  }

  @Override // ClientProtocol
  public void fsync(String src, String client, long lastBlockLength) throws
      AccessControlException, FileNotFoundException, UnresolvedLinkException,
      IOException {
    throw new IOException("fsync is not supported.");
  }

  @Override // ClientProtocol
  public LocatedBlocks getBlockLocations(String src, long offset, long length)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    INode iNode = nodeManager.getINode(src);
    if(iNode == null || iNode.isDir()) {
      throw new FileNotFoundException("File does not exist: " + src);
    }

    List<LocatedBlock> al = UnlocatedBlock.toLocatedBlocks(iNode.getBlocks(),
        iNode.getLocations());
    boolean underConstruction = (iNode.getFileState().equals(FileState.CLOSED));

    LocatedBlock lastBlock = al.size() == 0 ? null : al.get(al.size()-1);
    LocatedBlocks lbs = new LocatedBlocks(computeFileLength(al),
        underConstruction, al, lastBlock, underConstruction);
    return lbs;
  }

  private static long computeFileLength(List<LocatedBlock> al) {
    // does not matter if underConstruction or not so far.
    long n = 0;
    for(LocatedBlock bl : al) {
        n += bl.getBlockSize();
    }
    LOG.info("Block filesize sum is: " + n);
    return n;
  }

  @Override // ClientProtocol
  public ContentSummary getContentSummary(String path)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    INode node = nodeManager.getINode(path);
    if(node == null) {
      throw new FileNotFoundException("Path does not exist: " + path);
    }
    if(node.isDir()) {
      return new ContentSummary(0L, 0L, 1L, node.getNsQuota(), 
          0L, node.getDsQuota());
    }
    throw new IOException("Path is not a directory: " + path);
  }

  @Override // ClientProtocol
  public DatanodeInfo[] getDatanodeReport(DatanodeReportType type)
      throws IOException {
    throw new IOException("getDatanodeReport is not supported");
  }

  @Override // ClientProtocol
  public Token<DelegationTokenIdentifier> getDelegationToken(Text renewer)
      throws IOException {
    throw new IOException("getDelegationToken is not supported");
  }

  @Override // ClientProtocol
  public HdfsFileStatus getFileInfo(String src) throws AccessControlException,
      FileNotFoundException, UnresolvedLinkException, IOException {
    INode node = nodeManager.getINode(src);
    if(node == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    return node.getFileStatus();
  }

  @Override // ClientProtocol
  public HdfsFileStatus getFileLinkInfo(String src)
      throws AccessControlException, UnresolvedLinkException, IOException {
    throw new IOException("symlinks are not supported");
  }

  @Override // ClientProtocol
  public String getLinkTarget(String path) throws AccessControlException,
      FileNotFoundException, IOException {
    throw new IOException("symlinks are not supported");
  }

  @Override // ClientProtocol
  public DirectoryListing getListing(
      String src, byte[] startAfter, boolean needLocation)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    INode node = nodeManager.getINode(src);

    if(node == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }

    if(!node.isDir()) {
      return new DirectoryListing(new HdfsFileStatus[] { (needLocation) ?
          node.getLocatedFileStatus() : node.getFileStatus() }, 0);
    }

    List<INode> list = nodeManager.getListing(node, startAfter, lsLimit);

    HdfsFileStatus[] retVal = new HdfsFileStatus[list.size()];
    int i = 0;
    for(INode child : list)
      retVal[i++] = (needLocation) ? child.getLocatedFileStatus() :
          child.getFileStatus();
    // We can say there is no more entries if the lsLimit is exhausted,
    // otherwise we know only that there could be one more entry
    return new DirectoryListing(retVal, list.size() < lsLimit ? 0 : 1);
  }

  @Override // ClientProtocol
  public long getPreferredBlockSize(String src) throws IOException,
      UnresolvedLinkException {
    INode inode = nodeManager.getINode(src);
    if(inode == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    return inode.getBlockSize();
  }

  @Override // ClientProtocol
  public FsServerDefaults getServerDefaults() throws IOException {
    return this.serverDefaults;
  }

  @Override // ClientProtocol
  public long[] getStats() throws IOException {
    throw new IOException("getStats is not supported");
  }

  @Override // ClientProtocol
  public void metaSave(String filename) throws IOException {
    throw new IOException("metaSave is not supported");
  }

  @Override // ClientProtocol
  public boolean mkdirs(String src, FsPermission masked, boolean createParent)
      throws AccessControlException, FileAlreadyExistsException,
      FileNotFoundException, NSQuotaExceededException,
      ParentNotDirectoryException, SafeModeException, UnresolvedLinkException,
      IOException {
    Path parentPath = new Path(src).getParent();
    UserGroupInformation ugi = UserGroupInformation.getLoginUser();
    String clientName = ugi.getShortUserName();
    String machineName = (ugi.getGroupNames().length == 0) ? "supergroup" : ugi.getGroupNames()[0];

    RowKey key = RowKeyFactory.newInstance(src);
    INode inode = nodeManager.getINode(key);
    if(parentPath == null) {
      //generate root if doesn't exist
      if(inode == null) {
        long time = now();
        inode = new INode(0, true, (short) 0, 0, time, time,
            masked, clientName, machineName, null,
            key, 0, 0, null, null, null, null);
        nodeManager.updateINode(inode);
      }
      return true;
    }

    if(inode != null) {  // already exists
      return true;
    }

    // create parent directories if requested
    String parent = parentPath.toString();
    INode iParent = nodeManager.getINode(parent);
    if(!createParent && iParent == null) {
      throw new FileNotFoundException("Parent does not exist: "+parent);
    }
    if(iParent != null && !iParent.isDir()) {
      throw new ParentNotDirectoryException("Parent is not directory: "+parent);
    }
    if(createParent && iParent == null) {
      //make the parent directories
      mkdirs(parent, masked, true);
    } 

    long time = now();
    inode = new INode(0, true, (short) 0, 0, time, time,
        masked, clientName, machineName, null,
        key, 0, 0, null, null, null, null);

    // add directory to HBase
    nodeManager.updateINode(inode);
    return true;
  }

  @Override // ClientProtocol
  public boolean recoverLease(String src, String clientName) throws IOException {
    throw new IOException("recoverLease is not supported");
  }

  @Override // ClientProtocol
  public void refreshNodes() throws IOException {
    throw new IOException("refreshNodes is not supported");
  }

  @Override // ClientProtocol
  public boolean rename(String src, String dst) throws UnresolvedLinkException,
      IOException {
    try {
      rename2(src, dst);
    }catch(IOException e){
      LOG.warn(e.getMessage());
      return false;
    }
    return true;
  }

  @Override // ClientProtocol
  public void rename2(String src, String dst, Rename... options)
      throws AccessControlException, DSQuotaExceededException,
      FileAlreadyExistsException, FileNotFoundException,
      NSQuotaExceededException, ParentNotDirectoryException, SafeModeException,
      UnresolvedLinkException, IOException {
    LOG.info("Renaming " + src + " to " + dst);
    INode rootSrcNode = nodeManager.getINode(src);
    INode rootDstNode = nodeManager.getINode(dst);
    boolean overwrite = false;

    for(Rename option : options) {
      if(option.equals(Rename.OVERWRITE))
        overwrite = true;
    }

    checkCanRename(src, rootSrcNode, dst, rootDstNode, overwrite);

    boolean directoryRename = rootSrcNode != null && rootSrcNode.isDir() ||
        rootDstNode != null && rootDstNode.isDir();
    if(directoryRename)
      LOG.debug("Detected directory rename");

    if(rootDstNode != null && !rootDstNode.getRenameState().getFlag() &&
        overwrite) {
      LOG.debug("Overwriting "+dst);
      if(!delete(dst, false)) // will fail if dst is non-empty directory
        throw new IOException("rename cannot overwrite non empty destination "+
            "directory " + dst);
      rootDstNode = null;
    }

    // Stage 1: copy into new row with RenameState flag
    if(rootDstNode == null) {
      if(directoryRename) { // first do Stage 1 for all children
        final URI base = new Path(src).toUri();
        final URI newBase = URI.create(dst+Path.SEPARATOR);

        List<INode> directories = nodeManager.getDirectories(rootSrcNode);
        for (INode dir : directories) {
          // duplicate each INode in subdirectory
          nodeManager.map(dir, new Function() {
            @Override
            public void apply(INode srcNode) throws IOException {
              String iSrc = srcNode.getRowKey().getPath();
              String iDst = changeBase(iSrc, base, newBase);
              copyWithRenameFlag(srcNode, iDst);
            }
          });
        }
      }
      rootDstNode = copyWithRenameFlag(rootSrcNode, dst);
    }else {
      LOG.debug("Rename Recovery: Skipping Stage 1 because destination " + dst +
          " found");
    }

    // Stage 2: delete old rows
    if(rootSrcNode != null) {
      LOG.debug("Deleting "+src);
      if(directoryRename)
        deleteDirectory(rootSrcNode, true, false);
      else
        deleteFile(rootSrcNode, false);
    }else {
      LOG.debug("Rename Recovery: Skipping Stage 2 because "+src+" not found");
    }

    // Stage 3: remove RenameState flags
    if(directoryRename) { // first do Stage 3 for all children
      List<INode> directories = nodeManager.getDirectories(rootDstNode);
      for (INode dir : directories) {
        nodeManager.map(dir, new Function() {
          @Override
          public void apply(INode dstNode) throws IOException {
            removeRenameFlag(dstNode);
          }
        });
      }
    }
    removeRenameFlag(rootDstNode);
  }

  /**
   * Replaces the base prefix of src with newBase.
   */
  private static String changeBase(String src, URI base, URI newBase) {
    URI rel = base.relativize(URI.create(src));
    return newBase.resolve(rel).toString();
  }

  /**
   * Creates a duplicate of srcNode in the namespace named dst and sets the
   * rename flag on the duplicate.
   */
  private INode copyWithRenameFlag(INode srcNode, String dst)
      throws IOException {
    RowKey srcKey = srcNode.getRowKey();
    String src = srcKey.getPath();
    LOG.debug("Copying "+src+" to "+dst+" with rename flag");
    INode dstNode = srcNode.cloneWithNewRowKey(RowKeyFactory.newInstance(dst));
    dstNode.setRenameState(RenameState.TRUE(srcKey.getKey()));
    nodeManager.updateINode(dstNode);
    return dstNode;
  }

  /**
   * Unsets the rename flag from the given node and updates the namespace.
   */
  private void removeRenameFlag(INode dstNode) throws IOException {
    LOG.debug("Removing rename flag from "+dstNode.getRowKey().getPath());
    dstNode.setRenameState(RenameState.FALSE());
    nodeManager.updateINode(dstNode);
  }

  /**
   * Checks the rename arguments and their corresponding INodes to see if this
   * rename can proceed. Derived heavily from {@link
   * org.apache.hadoop.hdfs.server.namenode.FSDirectory#unprotectedRenameTo}
   * @throws IOException thrown if rename cannot proceed
   */
  private void checkCanRename(String src, INode srcNode, String dst,
                              INode dstNode, boolean overwrite) throws
      IOException {
    String error = null;
    String parent = new Path(dst).getParent().toString();
    INode parentNode = nodeManager.getINode(parent);

    boolean src_exists = (srcNode != null);
    boolean dst_exists = (dstNode != null);
    boolean parent_exists = (parentNode != null);
    boolean dst_setflag = (dst_exists && dstNode.getRenameState().getFlag());

    // validate source
    if (!src_exists && !dst_setflag) {
      error = "rename source " + src + " is not found.";
      throw new FileNotFoundException(error);
    }
    if (src.equals(Path.SEPARATOR)) {
      error = "rename source cannot be the root";
      throw new IOException(error);
    }

    // validate destination
    if (dst.equals(src)) {
      throw new FileAlreadyExistsException(
          "The source "+src+" and destination "+dst+" are the same");
    }
    if (dst.startsWith(src) && // dst cannot be a directory or a file under src
        dst.charAt(src.length()) == Path.SEPARATOR_CHAR) {
      error = "Rename destination " + dst
          + " is a directory or file under source " + src;
      throw new IOException(error);
    }
    if (dst.equals(Path.SEPARATOR)) {
      error = "rename destination cannot be the root";
      throw new IOException(error);
    }
    if (dst_exists && !dst_setflag) { // Destination exists
      if (dstNode.isDir() != srcNode.isDir()) {
        error = "Source " + src + " and destination " + dst
            + " must both be of same kind (file or directory)";
        throw new IOException(error);
      }
      if (!overwrite) { // If destination exists, overwrite flag must be true
        error = "rename destination " + dst + " already exists";
        throw new FileAlreadyExistsException(error);
      }
    }

    // validate parent
    if (!parent_exists) {
      error = "rename destination parent " + dst + " not found.";
      throw new FileNotFoundException(error);
    }
    if (!parentNode.isDir()) {
      error = "rename destination parent " + dst + " is a file.";
      throw new ParentNotDirectoryException(error);
    }
  }

  @Override // ClientProtocol
  public long renewDelegationToken(Token<DelegationTokenIdentifier> token)
      throws IOException {
    throw new IOException("renewDelegationToken is not supported");
  }

  @Override // ClientProtocol
  public void renewLease(String clientName) throws AccessControlException,
      IOException {
    throw new IOException("renewLease is not supported");
  }

  @Override // ClientProtocol
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    throw new IOException("reportBadBlocks is not supported");
  }

  @Override // ClientProtocol
  public boolean restoreFailedStorage(String arg) throws AccessControlException {
    return false;
  }

  @Override // ClientProtocol
  public void saveNamespace() throws AccessControlException, IOException {
    throw new IOException("saveNamespace is not supported");
  }

  @Override // ClientProtocol
  public void setOwner(String src, String username, String groupname)
      throws AccessControlException, FileNotFoundException, SafeModeException,
      UnresolvedLinkException, IOException {
    if(username == null && groupname == null)
      return;
    
    INode node = nodeManager.getINode(src);

    if(node == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }

    node.setOwner(username, groupname);
    nodeManager.updateINode(node);
  }

  @Override // ClientProtocol
  public void setPermission(String src, FsPermission permission)
      throws AccessControlException, FileNotFoundException, SafeModeException,
      UnresolvedLinkException, IOException {

    INode node = nodeManager.getINode(src);

    if(node == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    
    if(!node.isDir()) {
      permission = permission.applyUMask(UMASK);
    }
    
    node.setPermission(permission);
    nodeManager.updateINode(node);
  }

  @Override // ClientProtocol
  public void setQuota(String src, long namespaceQuota, long diskspaceQuota)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    
    INode node = nodeManager.getINode(src);

    if(node == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }

    //can only set Quota for directories
    if(!node.isDir()) {
      throw new FileNotFoundException("Directory does not exist: " + src);
    }
    
    // sanity check
    if ((namespaceQuota < 0 && namespaceQuota != HdfsConstants.QUOTA_DONT_SET && 
        namespaceQuota < HdfsConstants.QUOTA_RESET) || 
        (diskspaceQuota < 0 && diskspaceQuota != HdfsConstants.QUOTA_DONT_SET && 
        diskspaceQuota < HdfsConstants.QUOTA_RESET)) {
      throw new IllegalArgumentException("Illegal value for nsQuota or " +
          "dsQuota : " + namespaceQuota + " and " + diskspaceQuota);
    }

    node.setQuota(namespaceQuota, diskspaceQuota);
    nodeManager.updateINode(node);
  }

  @Override // ClientProtocol
  public boolean setReplication(String src, short replication)
      throws AccessControlException, DSQuotaExceededException,
      FileNotFoundException, SafeModeException, UnresolvedLinkException,
      IOException {
    INode node = nodeManager.getINode(src);

    if(node == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    if(node.isDir())
      return false;

    node.setReplication(replication);
    nodeManager.updateINode(node);
    return true;
  }

  @Override // ClientProtocol
  public boolean setSafeMode(SafeModeAction action, boolean isChecked)
      throws IOException {
    throw new IOException("setSafeMode is not supported");
  }

  @Override // ClientProtocol
  public void setTimes(String src, long mtime, long atime)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    INode node = nodeManager.getINode(src);

    if(node == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    if(node.isDir())
      return;

    node.setTimes(mtime, atime);
    nodeManager.updateINode(node);
  }

  @Override // ClientProtocol
  public LocatedBlock updateBlockForPipeline(ExtendedBlock block, String clientName)
      throws IOException {
    throw new IOException("updateBlockForPipeline is not supported");
  }

  @Override // ClientProtocol
  public void updatePipeline(
      String clientName, ExtendedBlock oldBlock, ExtendedBlock newBlock, DatanodeID[] newNodes)
      throws IOException {
    throw new IOException("updatePipeline is not supported");
  }
 
  private static long now() {
    return System.currentTimeMillis();
  }

  @Override
  public LocatedBlock getAdditionalDatanode(String arg0, ExtendedBlock arg1,
      DatanodeInfo[] arg2, DatanodeInfo[] arg3, int arg4, String arg5)
      throws AccessControlException, FileNotFoundException,
      SafeModeException, UnresolvedLinkException, IOException {
    throw new IOException("getAdditionalDatanode is not supported");
  }

  @Override
  public CorruptFileBlocks listCorruptFileBlocks(String arg0, String arg1)
      throws IOException {
    throw new IOException("corrupt file block listing is not supported");
  }

  @Override
  public void setBalancerBandwidth(long arg0) throws IOException {
    throw new IOException("bandwidth balancing is not supported");
  }

  @Override
  public long rollEdits() throws AccessControlException, IOException {
    throw new IOException("rollEdits is not supported");
  }

  @Override
  public DataEncryptionKey getDataEncryptionKey() throws IOException {
    throw new IOException("data encryption is not supported");
  }
}
