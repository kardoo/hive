/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.llap.daemon.impl;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hive.llap.daemon.rpc.LlapDaemonProtocolProtos.FragmentSpecProto;
import org.apache.hadoop.hive.llap.daemon.rpc.LlapDaemonProtocolProtos.SourceStateProto;
import org.apache.hadoop.hive.llap.shufflehandler.ShuffleHandler;
import org.apache.hadoop.service.CompositeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * Tracks queries running within a daemon
 */
public class QueryTracker extends CompositeService {

  private static final Logger LOG = LoggerFactory.getLogger(QueryTracker.class);
  private final QueryFileCleaner queryFileCleaner;

  // TODO Make use if the query id for cachin when this is available.
  private final ConcurrentHashMap<String, QueryInfo> queryInfoMap = new ConcurrentHashMap<>();

  private final String[] localDirsBase;
  private final FileSystem localFs;

  // TODO At the moment there's no way of knowing whether a query is running or not.
  // A race is possible between dagComplete and registerFragment - where the registerFragment
  // is processed after a dagCompletes.
  // May need to keep track of completed dags for a certain time duration to avoid this.
  // Alternately - send in an explicit dag start message before any other message is processed.
  // Multiple threads communicating from a single AM gets in the way of this.

  // Keeps track of completed dags. Assumes dag names are unique across AMs.
  private final Set<String> completedDagMap = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());


  private final Lock lock = new ReentrantLock();
  private final ConcurrentMap<String, ReadWriteLock> dagSpecificLocks = new ConcurrentHashMap<>();

  // Tracks various maps for dagCompletions. This is setup here since stateChange messages
  // may be processed by a thread which ends up executing before a task.
  private final ConcurrentMap<String, ConcurrentMap<String, SourceStateProto>> sourceCompletionMap = new ConcurrentHashMap<>();

  public QueryTracker(Configuration conf, String[] localDirsBase) {
    super("QueryTracker");
    this.localDirsBase = localDirsBase;
    try {
      localFs = FileSystem.getLocal(conf);
    } catch (IOException e) {
      throw new RuntimeException("Failed to setup local filesystem instance", e);
    }

    queryFileCleaner = new QueryFileCleaner(conf, localFs);
    addService(queryFileCleaner);
  }



  /**
   * Register a new fragment for a specific query
   * @param queryId
   * @param appIdString
   * @param dagName
   * @param dagIdentifier
   * @param vertexName
   * @param fragmentNumber
   * @param attemptNumber
   * @param user
   * @throws IOException
   */
  QueryFragmentInfo registerFragment(String queryId, String appIdString, String dagName, int dagIdentifier,
                        String vertexName, int fragmentNumber, int attemptNumber,
                        String user, FragmentSpecProto fragmentSpec) throws
      IOException {
    ReadWriteLock dagLock = getDagLock(dagName);
    dagLock.readLock().lock();
    try {
      if (!completedDagMap.contains(dagName)) {
        QueryInfo queryInfo = queryInfoMap.get(dagName);
        if (queryInfo == null) {
          queryInfo = new QueryInfo(queryId, appIdString, dagName, dagIdentifier, user,
              getSourceCompletionMap(dagName), localDirsBase, localFs);
          queryInfoMap.putIfAbsent(dagName, queryInfo);
        }
        return queryInfo.registerFragment(vertexName, fragmentNumber, attemptNumber, fragmentSpec);
      } else {
        // Cleanup the dag lock here, since it may have been created after the query completed
        dagSpecificLocks.remove(dagName);
        throw new RuntimeException(
            "Dag " + dagName + " already complete. Rejecting fragment [" + vertexName + ", " + fragmentNumber +
                ", " + attemptNumber);
      }
    } finally {
      dagLock.readLock().unlock();
    }
  }

  /**
   * Indicate to the tracker that a fragment is complete. This is from internal execution within the daemon
   * @param fragmentInfo
   */
  void fragmentComplete(QueryFragmentInfo fragmentInfo) {
    String dagName = fragmentInfo.getQueryInfo().getDagName();
    QueryInfo queryInfo = queryInfoMap.get(dagName);
    if (queryInfo == null) {
      // Possible because a queryComplete message from the AM can come in first - KILL / SUCCESSFUL,
      // before the fragmentComplete is reported
      LOG.info("Ignoring fragmentComplete message for unknown query");
    } else {
      queryInfo.unregisterFragment(fragmentInfo);
    }
  }

  /**
   * Register completion for a query
   * @param queryId
   * @param dagName
   * @param deleteDelay
   */
  void queryComplete(String queryId, String dagName, long deleteDelay) {
    ReadWriteLock dagLock = getDagLock(dagName);
    dagLock.writeLock().lock();
    try {
      completedDagMap.add(dagName);
      LOG.info("Processing queryComplete for dagName={} with deleteDelay={} seconds", dagName,
          deleteDelay);
      completedDagMap.add(dagName);
      QueryInfo queryInfo = queryInfoMap.remove(dagName);
      if (queryInfo == null) {
        LOG.warn("Ignoring query complete for unknown dag: {}", dagName);
      }
      String[] localDirs = queryInfo.getLocalDirsNoCreate();
      if (localDirs != null) {
        for (String localDir : localDirs) {
          queryFileCleaner.cleanupDir(localDir, deleteDelay);
          ShuffleHandler.get().unregisterDag(localDir, dagName, queryInfo.getDagIdentifier());
        }
      }
      sourceCompletionMap.remove(dagName);
      dagSpecificLocks.remove(dagName);
      // TODO HIVE-10762 Issue a kill message to all running fragments for this container.
      // TODO HIVE-10535 Cleanup map join cache
    } finally {
      dagLock.writeLock().unlock();
    }
  }

  /**
   * Register an update to a source within an executing dag
   * @param dagName
   * @param sourceName
   * @param sourceState
   */
  void registerSourceStateChange(String dagName, String sourceName, SourceStateProto sourceState) {
    getSourceCompletionMap(dagName).put(sourceName, sourceState);
    QueryInfo queryInfo = queryInfoMap.get(dagName);
    if (queryInfo != null) {
      queryInfo.sourceStateUpdated(sourceName);
    } else {
      // Could be null if there's a race between the threads processing requests, with a
      // dag finish processed earlier.
    }
  }


  private ReadWriteLock getDagLock(String dagName) {
    lock.lock();
    try {
      ReadWriteLock dagLock = dagSpecificLocks.get(dagName);
      if (dagLock == null) {
        dagLock = new ReentrantReadWriteLock();
        dagSpecificLocks.put(dagName, dagLock);
      }
      return dagLock;
    } finally {
      lock.unlock();
    }
  }

  private ConcurrentMap<String, SourceStateProto> getSourceCompletionMap(String dagName) {
    ConcurrentMap<String, SourceStateProto> dagMap = sourceCompletionMap.get(dagName);
    if (dagMap == null) {
      dagMap = new ConcurrentHashMap<>();
      ConcurrentMap<String, SourceStateProto> old =
          sourceCompletionMap.putIfAbsent(dagName, dagMap);
      dagMap = (old != null) ? old : dagMap;
    }
    return dagMap;
  }
}