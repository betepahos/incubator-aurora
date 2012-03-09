package com.twitter.mesos.scheduler.storage;

import com.twitter.mesos.scheduler.SchedulerException;

/**
 * Manages scheduler storage operations providing an interface to perform atomic changes.
 *
 * @author John Sirois
 */
public interface Storage {

  interface StoreProvider {
    SchedulerStore getSchedulerStore();
    JobStore getJobStore();
    TaskStore getTaskStore();
    UpdateStore getUpdateStore();
    QuotaStore getQuotaStore();
    AttributeStore getAttributeStore();
  }

  /**
   * Encapsulates a storage transaction unit of work.
   *
   * @param <T> The type of result this unit of work produces.
   * @param <E> The type of exception this unit of work can throw.
   */
  interface Work<T, E extends Exception> {

    NoResult.Quiet NOOP = new NoResult.Quiet() {
      @Override protected void execute(Storage.StoreProvider storeProvider) {
        // No-op.
      }
    };

    /**
     * Abstracts a unit of work that should either commit a set of changes to storage as a side
     * effect of successful completion or else commit no changes at all when an exception is thrown.
     *
     * @param storeProvider A provider to give access to different available stores.
     * @return the result of the successfully completed unit of work
     * @throws E if the unit of work could not be completed
     */
    T apply(StoreProvider storeProvider) throws E;

    /**
     * A convenient typedef for Work that throws no checked exceptions - it runs quitely.
     *
     * @param <T> The type of result this unit of work produces.
     */
    interface Quiet<T> extends Work<T, RuntimeException> {
      // typedef
    }

    /**
     * Encapsulates work that returns no result.
     *
     * @param <E> The type of exception this unit of work can throw.
     */
    abstract class NoResult<E extends Exception> implements Work<Void, E> {

      @Override public final Void apply(StoreProvider storeProvider) throws E {
        execute(storeProvider);
        return null;
      }

      /**
       * Similar to {@link #apply(StoreProvider)} except that no result is
       * returned.
       *
       * @param storeProvider A provider to give access to different available stores.
       * @throws E if the unit of work could not be completed
       */
      protected abstract void execute(StoreProvider storeProvider) throws E;

      /**
       * A convenient typedef for Work with no result that throws no checked exceptions - it runs
       * quitely.
       */
      public abstract static class Quiet extends Work.NoResult<RuntimeException> {
        // typedef
      }
    }
  }

  /**
   * Indicates a problem reading from or writing to stable storage.
   */
  class StorageException extends SchedulerException {
    public StorageException(String message, Throwable cause) {
      super(message, cause);
    }

    public StorageException(String message) {
      super(message);
    }
  }

  /**
   * Requests the underlying storage prepare its data set; ie: initialize schemas, begin syncing out
   * of date data, etc.  This method should not block.
   */
  void prepare();

  /**
   * Prepares the underlying storage for serving traffic.
   *
   * @param initilizationLogic work to perform after this storage system is ready but before
   *     allowing general use of
   *     {@link #doInTransaction(com.twitter.mesos.scheduler.storage.Storage.Work)}.
   */
  void start(Work.NoResult.Quiet initilizationLogic);

  /**
   * Executes the unit of {@code work} in a transaction such that any storage write operations
   * requested are either all committed if the unit of work does not throw or else none of the
   * requested storage operations commit when it does throw.
   *
   * <p>TODO(John Sirois): Audit usages and handle StorageException appropriately.
   *
   * @param work The unit of work to execute in a transaction.
   * @param <T> The type of result this unit of work produces.
   * @param <E> The type of exception this unit of work can throw.
   * @return the result when the unit of work completes successfully
   * @throws StorageException if there was a problem reading from or writing to stable storage.
   * @throws E bubbled transparently when the unit of work throws
   */
  <T, E extends Exception> T doInTransaction(Work<T, E> work) throws StorageException, E;

  /**
   * Prepares the underlying storage system for clean shutdown.
   */
  void stop();
}
