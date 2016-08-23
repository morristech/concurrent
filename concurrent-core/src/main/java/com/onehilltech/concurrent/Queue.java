package com.onehilltech.concurrent;

import java.util.Collection;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class Queue
{
  private final Executor executor_;

  private final java.util.Queue <PendingTask> pendingTasks_;

  private AtomicInteger remaining_;

  private int concurrency_;

  private boolean isCancelled_ = false;

  /**
   * Initialing constructor.
   *
   * @param executor
   * @param concurrency
   */
  public Queue (Executor executor, int concurrency)
  {
    this (new LinkedList<PendingTask> (), executor, concurrency);
  }

  /**
   * Initializing constructor.
   *
   * @param queueImpl
   * @param executor
   * @param concurrency
   */
  protected Queue (java.util.Queue <PendingTask> queueImpl, Executor executor, int concurrency)
  {
    this.pendingTasks_ = queueImpl;
    this.executor_ = executor;
    this.concurrency_ = concurrency;
    this.remaining_ = new AtomicInteger (concurrency);
  }

  /**
   * Add a new task to the queue.
   *
   * @param task
   */
  public synchronized void push (Task task, CompletionCallback callback)
  {
    this.pendingTasks_.add (new PendingTask (task, callback));

    if (this.remaining_.get () > 0)
      this.addTaskRunnerToQueue ();
  }

  /**
   * Push a collection of tasks onto the queue.
   *
   * @param tasks
   */
  public void push (Collection<Task> tasks, CompletionCallback callback)
  {
    for (Task task: tasks)
      this.push (task, callback);
  }

  /**
   * Get the concurrency value for the queue.
   *
   * @return
   */
  public int getConcurrency ()
  {
    return this.concurrency_;
  }

  /**
   * Add a new TaskRunner object to the executor's queue.
   */
  private synchronized void addTaskRunnerToQueue ()
  {
    if (!this.isCancelled_)
    {
      // Put a new task runner on the executor queue.
      this.executor_.execute (new TaskRunner ());

      // Decrement the number of remaining slots.
      this.remaining_.decrementAndGet ();
    }
  }

  /**
   * Helper notification method for completion of a task.
   *
   * @param callback
   * @param result
   */
  private void onTaskComplete (CompletionCallback callback, final Object result)
  {
    // First, queue up the result callback.
    this.executor_.execute (new OnComplete (callback, result));

    // Increment the number of slots remaining.
    this.remaining_.incrementAndGet ();

    // Run the next task in the queue if not empty.
    if (!this.pendingTasks_.isEmpty ())
      this.addTaskRunnerToQueue ();
  }

  /**
   * Helper notification method for a task failing.
   *
   * @param callback      Target callback to call
   * @param reason        Reason for the failure
   */
  private void onTaskFail (CompletionCallback callback, Throwable reason)
  {
    this.executor_.execute (new OnFail (callback, reason));
  }

  private void onTaskCancel (CompletionCallback callback)
  {
    this.executor_.execute (new OnCancel (callback));
  }

  /**
   * Cancel execution on the queue. This method does not close the contained Executor
   * object.
   */
  public void cancel ()
  {
    this.isCancelled_ = true;
  }

  /**
   * CompletionCallback for the PendingTask. Its responsibility is to keep association
   * between the task on the queue and its CompletionCallback.
   */
  private class PendingTaskCompletionCallback extends CompletionCallback
  {
    private final CompletionCallback callback_;

    PendingTaskCompletionCallback (CompletionCallback callback)
    {
      this.callback_ = callback;
    }

    @Override
    protected void onCancel ()
    {
      onTaskCancel (this.callback_);
    }

    @Override
    protected void onFail (Throwable e)
    {
      onTaskFail (this.callback_, e);
    }

    @Override
    protected void onComplete (Object result)
    {
      onTaskComplete (this.callback_, result);
    }
  }

  /**
   * Wrapper class for a PendingTask.
   */
  private class PendingTask
  {
    private final Task task;
    private final CompletionCallback callback;

    PendingTask (Task task, CompletionCallback callback)
    {
      this.task = task;
      this.callback = callback;
    }

    @SuppressWarnings ("unchecked")
    public void run (Object obj)
    {
      this.task.run (obj, new PendingTaskCompletionCallback (this.callback));
    }
  }

  /**
   * Runnable object that dequeue the next task in the queue, and executes it. We
   * do not bind to the PendingTask at creation time because we do not want to remove
   * the task until we are sure we are going to run it.
   */
  private class TaskRunner implements Runnable
  {
    @Override
    public void run ()
    {
      if (isCancelled_)
        return;

      try
      {
        // Remove the next pending task in the queue.
        PendingTask pendingTask = pendingTasks_.remove ();

        // Execute the task.
        pendingTask.run (null);
      }
      catch (NoSuchElementException e)
      {
        // Do nothing...
      }
    }
  }
}