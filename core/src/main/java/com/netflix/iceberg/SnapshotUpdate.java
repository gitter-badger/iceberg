/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg;

import com.google.common.collect.Sets;
import com.netflix.iceberg.exceptions.CommitFailedException;
import com.netflix.iceberg.exceptions.ValidationException;
import com.netflix.iceberg.io.OutputFile;
import com.netflix.iceberg.util.Exceptions;
import com.netflix.iceberg.util.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.netflix.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS;
import static com.netflix.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT;
import static com.netflix.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS;
import static com.netflix.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT;
import static com.netflix.iceberg.TableProperties.COMMIT_NUM_RETRIES;
import static com.netflix.iceberg.TableProperties.COMMIT_NUM_RETRIES_DEFAULT;
import static com.netflix.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS;
import static com.netflix.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT;

abstract class SnapshotUpdate implements PendingUpdate<Snapshot> {
  private static final Logger LOG = LoggerFactory.getLogger(SnapshotUpdate.class);
  private static final Set<String> EMPTY_SET = Sets.newHashSet();

  private final TableOperations ops;
  private final String commitUUID = UUID.randomUUID().toString();
  private Long snapshotId = null;
  private TableMetadata base = null;

  protected SnapshotUpdate(TableOperations ops) {
    this.ops = ops;
    this.base = ops.current();
  }

  /**
   * Apply the update's changes to the base table metadata and return the new manifest list.
   *
   * @param base the base table metadata to apply changes to
   * @return a manifest list for the new snapshot.
   */
  protected abstract List<String> apply(TableMetadata base);

  /**
   * Clean up any uncommitted manifests that were created.
   * <p>
   * Manifests may not be committed if apply is called more because a commit conflict has occurred.
   * Implementations may keep around manifests because the same changes will be made by both apply
   * calls. This method instructs the implementation to clean up those manifests and passes the
   * paths of the manifests that were actually committed.
   *
   * @param committed a set of manifest paths that were actually committed
   */
  protected abstract void cleanUncommitted(Set<String> committed);

  @Override
  public Snapshot apply() {
    this.base = ops.refresh();
    List<String> manifests = apply(base);
    return new BaseSnapshot(ops, snapshotId(), System.currentTimeMillis(), manifests);
  }

  @Override
  public void commit() {
    // this is always set to the latest commit attempt's snapshot id.
    AtomicLong newSnapshotId = new AtomicLong(-1L);
    try {
      Tasks.foreach(ops)
          .retry(base.propertyAsInt(COMMIT_NUM_RETRIES, COMMIT_NUM_RETRIES_DEFAULT))
          .exponentialBackoff(
              base.propertyAsInt(COMMIT_MIN_RETRY_WAIT_MS, COMMIT_MIN_RETRY_WAIT_MS_DEFAULT),
              base.propertyAsInt(COMMIT_MAX_RETRY_WAIT_MS, COMMIT_MAX_RETRY_WAIT_MS_DEFAULT),
              base.propertyAsInt(COMMIT_TOTAL_RETRY_TIME_MS, COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT),
              2.0 /* exponential */ )
          .onlyRetryOn(CommitFailedException.class)
          .run(ops -> {
            Snapshot newSnapshot = apply();
            newSnapshotId.set(newSnapshot.snapshotId());
            TableMetadata updated = base.addSnapshot(newSnapshot);
            ops.commit(base, updated);
          });

      // at this point, the commit and refresh must have succeeded and the snapshot should be in
      // the table's current metadata. the snapshot is loaded by id in case another commit was
      // added between this commit and the refresh
      Snapshot saved = ops.current().snapshot(newSnapshotId.get());
      if (saved != null) {
        cleanUncommitted(Sets.newHashSet(saved.manifests()));
      } else {
        // saved may not be present if the latest metadata couldn't be loaded due to eventual
        // consistency problems in refresh. in that case, don't clean up.
        LOG.info("Failed to load committed snapshot for clean-up, skipping manifest clean-up");
      }

    } catch (ValidationException | CommitFailedException e) {
      Exceptions.suppressAndThrow(e, this::cleanAll);

    } catch (RuntimeException e) {
      Exceptions.suppressAndThrow(e, () -> {
        Snapshot saved = ops.current().snapshot(newSnapshotId.get());
        if (saved != null) {
          LOG.info(String.format(
              "Failed during commit after commit %d was complete. Cleaning up unused manifests.",
              newSnapshotId.get()));
          // the snapshot was committed, so only clean up uncommitted manifests
          cleanUncommitted(Sets.newHashSet(saved.manifests()));
        } else {
          // the problem may be that the refresh is failing, so the commit may have succeeded.
          // don't clean up manifests because it is not safe.
          LOG.info("Failed during commit, skipping manifest clean-up", e);
        }
      });
    }
  }

  protected void cleanAll() {
    cleanUncommitted(EMPTY_SET);
  }

  protected void deleteFile(String path) {
    ops.deleteFile(path);
  }

  protected OutputFile manifestPath(int i) {
    return ops.newMetadataFile(FileFormat.AVRO.addExtension(commitUUID + "-m" + i));
  }

  protected long snapshotId() {
    if (snapshotId == null) {
      this.snapshotId = ops.newSnapshotId();
    }
    return snapshotId;
  }
}
