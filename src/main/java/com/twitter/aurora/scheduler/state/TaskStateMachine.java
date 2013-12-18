/*
 * Copyright 2013 Twitter, Inc.
 *
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
package com.twitter.aurora.scheduler.state;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;

import org.apache.commons.lang.builder.HashCodeBuilder;

import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.TaskEvent;
import com.twitter.aurora.scheduler.storage.entities.IScheduledTask;
import com.twitter.common.base.Closure;
import com.twitter.common.base.Closures;
import com.twitter.common.base.Command;
import com.twitter.common.base.MorePreconditions;
import com.twitter.common.stats.Stats;
import com.twitter.common.util.Clock;
import com.twitter.common.util.StateMachine;
import com.twitter.common.util.StateMachine.Rule;
import com.twitter.common.util.StateMachine.Transition;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * State machine for a task.
 * <p>
 * This enforces the lifecycle of a task, and triggers the actions that should be taken in response
 * to different state transitions.  These responses are externally communicated by populating a
 * provided work queue.
 * <p>
 * TODO(William Farner): Introduce an interface to allow state machines to be dealt with
 *     abstractly from the consumption side.
 */
class TaskStateMachine {
  private static final Logger LOG = Logger.getLogger(TaskStateMachine.class.getName());

  private static final AtomicLong ILLEGAL_TRANSITIONS =
      Stats.exportLong("scheduler_illegal_task_state_transitions");

  // Re-declarations of statuses as wrapped state objects.
  private static final State ASSIGNED = State.create(ScheduleStatus.ASSIGNED);
  private static final State FAILED = State.create(ScheduleStatus.FAILED);
  private static final State FINISHED = State.create(ScheduleStatus.FINISHED);
  private static final State INIT = State.create(ScheduleStatus.INIT);
  private static final State KILLED = State.create(ScheduleStatus.KILLED);
  private static final State KILLING = State.create(ScheduleStatus.KILLING);
  private static final State LOST = State.create(ScheduleStatus.LOST);
  private static final State PENDING = State.create(ScheduleStatus.PENDING);
  private static final State PREEMPTING = State.create(ScheduleStatus.PREEMPTING);
  private static final State RESTARTING = State.create(ScheduleStatus.RESTARTING);
  private static final State RUNNING = State.create(ScheduleStatus.RUNNING);
  private static final State STARTING = State.create(ScheduleStatus.STARTING);
  private static final State THROTTLED = State.create(ScheduleStatus.THROTTLED);
  private static final State UNKNOWN = State.create(ScheduleStatus.UNKNOWN);

  @VisibleForTesting
  static final Supplier<String> LOCAL_HOST_SUPPLIER = Suppliers.memoize(
      new Supplier<String>() {
        @Override public String get() {
          try {
            return InetAddress.getLocalHost().getHostName();
          } catch (UnknownHostException e) {
            LOG.log(Level.SEVERE, "Failed to get self hostname.");
            throw Throwables.propagate(e);
          }
        }
      });

  private final String taskId;
  private final WorkSink workSink;
  private final StateMachine<State> stateMachine;
  private ScheduleStatus previousState = null;
  private final Clock clock;

  /**
   * Composes a schedule status and a state change argument.  Only the ScheduleStatuses in two
   * States must be equal for them to be considered equal.
   */
  private static class State {
    private final ScheduleStatus state;
    private final Function<IScheduledTask, IScheduledTask> mutation;

    State(ScheduleStatus state, Function<IScheduledTask, IScheduledTask> mutation) {
      this.state = state;
      this.mutation = mutation;
    }

    static State create(ScheduleStatus status) {
      return create(status, Functions.<IScheduledTask>identity());
    }

    static State create(
        ScheduleStatus status,
        Function<IScheduledTask, IScheduledTask> mutation) {

      return new State(status, mutation);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof State)) {
        return false;
      }

      if (o == this) {
        return true;
      }

      State other = (State) o;
      return state == other.state;
    }

    @Override
    public int hashCode() {
      return new HashCodeBuilder()
          .append(state)
          .toHashCode();
    }

    @Override
    public String toString() {
      return state.toString();
    }

    private ScheduleStatus getState() {
      return state;
    }

    private Function<IScheduledTask, IScheduledTask> getMutation() {
      return mutation;
    }
  }

  /**
   * A write-only work acceptor.
   */
  public interface WorkSink {
    /**
     * Appends external work that must be performed for a state machine transition to be fully
     * complete.
     *
     * @param work Description of the work to be performed.
     * @param stateMachine The state machine that the work is associated with.
     * @param mutation Mutate operation to perform along with the state transition.
     */
    void addWork(
        WorkCommand work,
        TaskStateMachine stateMachine,
        Function<IScheduledTask, IScheduledTask> mutation);
  }

  /**
   * Creates a new task state machine.
   *
   * @param taskId ID of the task managed by this state machine.
   * @param task Read-only task that this state machine manages.
   * @param workSink Work sink to receive transition response actions
   * @param clock Clock to use for reading the current time.
   * @param initialState The state to begin the state machine at.  All legal transitions will be
   *     added, but this allows the state machine to 'skip' states, for instance when a task is
   *     loaded from a persistent store.
   */
  public TaskStateMachine(
      final String taskId,
      final IScheduledTask task,
      final WorkSink workSink,
      final Clock clock,
      final ScheduleStatus initialState) {

    this.taskId = MorePreconditions.checkNotBlank(taskId);
    this.workSink = checkNotNull(workSink);
    this.clock = checkNotNull(clock);
    checkNotNull(initialState);

    @SuppressWarnings("unchecked")
    Closure<Transition<State>> manageTerminatedTasks = Closures.combine(
        /* Kill a task that we believe to be terminated when an attempt is made to revive. */
        Closures.filter(Transition.to(ASSIGNED, STARTING, RUNNING),
            addWorkClosure(WorkCommand.KILL)),
        /* Remove a terminated task that is remotely removed. */
        Closures.filter(Transition.to(UNKNOWN), addWorkClosure(WorkCommand.DELETE)));

    final Closure<Transition<State>> manageRestartingTask = new Closure<Transition<State>>() {
      @SuppressWarnings("fallthrough")
      @Override public void execute(Transition<State> transition) {
        switch (transition.getTo().getState()) {
          case ASSIGNED:
          case STARTING:
          case RUNNING:
            addWork(WorkCommand.KILL);
            break;

          case LOST:
            addWork(WorkCommand.KILL);
            // fall through

          case FINISHED:
          case FAILED:
          case KILLED:
            addWork(WorkCommand.RESCHEDULE, transition.getTo().getMutation());
            break;

          case UNKNOWN:
            updateState(ScheduleStatus.LOST);
            break;

          default:
            // No-op.
        }
      }
    };

    // To be called on a task transitioning into the FINISHED state.
    final Command rescheduleIfService = new Command() {
      @Override public void execute() {
        if (task.getAssignedTask().getTask().isIsService()) {
          addWork(WorkCommand.RESCHEDULE);
        }
      }
    };

    // To be called on a task transitioning into the FAILED state.
    final Command incrementFailuresMaybeReschedule = new Command() {
      @Override public void execute() {
        addWork(WorkCommand.INCREMENT_FAILURES);

        // Max failures is ignored for service task.
        boolean isService = task.getAssignedTask().getTask().isIsService();

        // Max failures is ignored when set to -1.
        int maxFailures = task.getAssignedTask().getTask().getMaxTaskFailures();
        if (isService || (maxFailures == -1) || (task.getFailureCount() < (maxFailures - 1))) {
          addWork(WorkCommand.RESCHEDULE);
        } else {
          LOG.info("Task " + getTaskId() + " reached failure limit, not rescheduling");
        }
      }
    };

    final Closure<Transition<State>> deleteIfKilling =
        Closures.filter(Transition.to(KILLING), addWorkClosure(WorkCommand.DELETE));

    stateMachine = StateMachine.<State>builder(taskId)
        .logTransitions()
        .initialState(State.create(initialState))
        .addState(
            Rule.from(INIT)
                .to(PENDING, THROTTLED, UNKNOWN))
        .addState(
            Rule.from(PENDING)
                .to(ASSIGNED, KILLING)
                .withCallback(deleteIfKilling))
        .addState(
            Rule.from(THROTTLED)
            .to(PENDING, KILLING)
            .withCallback(deleteIfKilling))
        .addState(
            Rule.from(ASSIGNED)
                .to(STARTING, RUNNING, FINISHED, FAILED, RESTARTING, KILLED,
                    KILLING, LOST, PREEMPTING)
                .withCallback(
                    new Closure<Transition<State>>() {
                      @SuppressWarnings("fallthrough")
                      @Override public void execute(Transition<State> transition) {
                        switch (transition.getTo().getState()) {
                          case FINISHED:
                            rescheduleIfService.execute();
                            break;

                          case PREEMPTING:
                            addWork(WorkCommand.KILL);
                            break;

                          case FAILED:
                            incrementFailuresMaybeReschedule.execute();
                            break;

                          case RESTARTING:
                            addWork(WorkCommand.KILL);
                            break;

                          case KILLED:
                            addWork(WorkCommand.RESCHEDULE);
                            break;

                          case LOST:
                            addWork(WorkCommand.RESCHEDULE);
                            // fall through
                          case KILLING:
                            addWork(WorkCommand.KILL);
                            break;

                          case UNKNOWN:
                            break;

                           default:
                             // No-op.
                        }
                      }
                    }
                ))
        .addState(
            Rule.from(STARTING)
                .to(RUNNING, FINISHED, FAILED, RESTARTING, KILLING, KILLED, LOST, PREEMPTING)
                .withCallback(
                    new Closure<Transition<State>>() {
                      @SuppressWarnings("fallthrough")
                      @Override public void execute(Transition<State> transition) {
                        switch (transition.getTo().getState()) {
                          case FINISHED:
                            rescheduleIfService.execute();
                            break;

                          case RESTARTING:
                            addWork(WorkCommand.KILL);
                            break;

                          case PREEMPTING:
                            addWork(WorkCommand.KILL);
                            break;

                          case FAILED:
                            incrementFailuresMaybeReschedule.execute();
                            break;

                          case KILLED:
                            addWork(WorkCommand.RESCHEDULE);
                            break;

                          case KILLING:
                            addWork(WorkCommand.KILL);
                            break;

                          case LOST:
                            addWork(WorkCommand.RESCHEDULE);
                            break;

                          case UNKNOWN:
                            // The slave previously acknowledged that it had the task, and now
                            // stopped reporting it.
                            updateState(ScheduleStatus.LOST);
                            break;

                           default:
                             // No-op.
                        }
                      }
                    }
                ))
        .addState(
            Rule.from(RUNNING)
                .to(FINISHED, RESTARTING, FAILED, KILLING, KILLED, LOST, PREEMPTING)
                .withCallback(
                    new Closure<Transition<State>>() {
                      @SuppressWarnings("fallthrough")
                      @Override public void execute(Transition<State> transition) {
                        switch (transition.getTo().getState()) {
                          case FINISHED:
                            rescheduleIfService.execute();
                            break;

                          case PREEMPTING:
                            addWork(WorkCommand.KILL);
                            break;

                          case RESTARTING:
                            addWork(WorkCommand.KILL);
                            break;

                          case FAILED:
                            incrementFailuresMaybeReschedule.execute();
                            break;

                          case KILLED:
                            addWork(WorkCommand.RESCHEDULE);
                            break;

                          case KILLING:
                            addWork(WorkCommand.KILL);
                            break;

                          case LOST:
                            addWork(WorkCommand.RESCHEDULE);
                            break;

                          case UNKNOWN:
                            updateState(ScheduleStatus.LOST);
                            break;

                           default:
                             // No-op.
                        }
                      }
                    }
                ))
        .addState(
            Rule.from(FINISHED)
                .to(UNKNOWN)
                .withCallback(manageTerminatedTasks))
        .addState(
            Rule.from(PREEMPTING)
                .to(FINISHED, FAILED, KILLING, KILLED, LOST)
                .withCallback(manageRestartingTask))
        .addState(
            Rule.from(RESTARTING)
                .to(FINISHED, FAILED, KILLING, KILLED, LOST)
                .withCallback(manageRestartingTask))
        .addState(
            Rule.from(FAILED)
                .to(UNKNOWN)
                .withCallback(manageTerminatedTasks))
        .addState(
            Rule.from(KILLED)
                .to(UNKNOWN)
                .withCallback(manageTerminatedTasks))
        .addState(
            Rule.from(KILLING)
                .to(FINISHED, FAILED, KILLED, LOST, UNKNOWN)
                .withCallback(manageTerminatedTasks))
        .addState(
            Rule.from(LOST)
                .to(UNKNOWN)
                .withCallback(manageTerminatedTasks))
        .addState(
            Rule.from(UNKNOWN)
                .noTransitions()
                .withCallback(manageTerminatedTasks))
        // Since we want this action to be performed last in the transition sequence, the callback
        // must be the last chained transition callback.
        .onAnyTransition(
            new Closure<Transition<State>>() {
              @Override public void execute(final Transition<State> transition) {
                ScheduleStatus from = transition.getFrom().getState();
                ScheduleStatus to = transition.getTo().getState();

                if (transition.isValidStateChange() && (to != ScheduleStatus.UNKNOWN)
                    // Prevent an update when killing a pending task, since the task is deleted
                    // prior to the update.
                    && !((from == ScheduleStatus.PENDING) && (to == ScheduleStatus.KILLING))) {
                  addWork(WorkCommand.UPDATE_STATE, transition.getTo().getMutation());
                } else if (!transition.isAllowed()) {
                  LOG.log(Level.SEVERE, "Illegal state transition attempted: " + transition);
                  ILLEGAL_TRANSITIONS.incrementAndGet();
                }

                if (transition.isValidStateChange()) {
                  previousState = from;
                }
              }
            }
        )
        // TODO(wfarner): Consider alternatives to allow exceptions to surface.  This would allow
        // the state machine to surface illegal state transitions and propagate better information
        // to the caller.  As it stands, the caller must implement logic that really belongs in
        // the state machine.  For example, preventing RESTARTING->UPDATING transitions
        // (or for that matter, almost any user-initiated state transition) is awkward.
        .throwOnBadTransition(false)
        .build();
  }

  private Closure<Transition<State>> addWorkClosure(final WorkCommand work) {
    return new Closure<Transition<State>>() {
      @Override public void execute(Transition<State> item) {
        addWork(work);
      }
    };
  }

  private void addWork(WorkCommand work) {
    addWork(work, Functions.<IScheduledTask>identity());
  }

  private void addWork(WorkCommand work, Function<IScheduledTask, IScheduledTask> mutation) {
    LOG.info("Adding work command " + work + " for " + this);
    workSink.addWork(work, TaskStateMachine.this, mutation);
  }

  /**
   * Same as {@link #updateState(ScheduleStatus, Function)}, but uses a noop mutation.
   *
   * @param status Status to apply to the task.
   * @return {@code true} if the state change was allowed, {@code false} otherwise.
   */
  public synchronized boolean updateState(ScheduleStatus status) {
    return updateState(status, Functions.<IScheduledTask>identity());
  }

  /**
   * Same as {@link #updateState(ScheduleStatus, Function, Optional)}, but uses a noop mutation.
   *
   * @param status Status to apply to the task.
   * @param auditMessage The (optional) audit message to associate with the transition.
   * @return {@code true} if the state change was allowed, {@code false} otherwise.
   */
  public synchronized boolean updateState(ScheduleStatus status, Optional<String> auditMessage) {
    return updateState(status, Functions.<IScheduledTask>identity(), auditMessage);
  }

  /**
   * Same as {@link #updateState(ScheduleStatus, Function, Optional)}, but omits the audit message.
   *
   * @param status Status to apply to the task.
   * @param mutation Mutate operation to perform while updating the task.
   * @return {@code true} if the state change was allowed, {@code false} otherwise.
   */
  public synchronized boolean updateState(
      ScheduleStatus status,
      Function<IScheduledTask, IScheduledTask> mutation) {

    return updateState(status, mutation, Optional.<String>absent());
  }

  /**
   * Attempt to transition the state machine to the provided state.
   * At the time this method returns, any work commands required to satisfy the state transition
   * will be appended to the work queue.
   *
   * @param status Status to apply to the task.
   * @param auditMessage The audit message to associate with the transition.
   * @param mutation Mutate operation to perform while updating the task.
   * @return {@code true} if the state change was allowed, {@code false} otherwise.
   */
  private synchronized boolean updateState(
      final ScheduleStatus status,
      Function<IScheduledTask, IScheduledTask> mutation,
      final Optional<String> auditMessage) {

    checkNotNull(status);
    checkNotNull(mutation);
    checkNotNull(auditMessage);

    /**
     * Don't bother applying noop state changes.  If we end up modifying task state without a
     * state transition (e.g. storing resource consumption of a running task), we need to find
     * a different way to suppress noop transitions.
     */
    if (stateMachine.getState().getState() != status) {
      Function<IScheduledTask, IScheduledTask> operation = Functions.compose(mutation,
          new Function<IScheduledTask, IScheduledTask>() {
            @Override public IScheduledTask apply(IScheduledTask task) {
              ScheduledTask builder = task.newBuilder();
              builder.addToTaskEvents(new TaskEvent()
                  .setTimestamp(clock.nowMillis())
                  .setStatus(status)
                  .setMessage(auditMessage.orNull())
                  .setScheduler(LOCAL_HOST_SUPPLIER.get()));
              return IScheduledTask.build(builder);
            }
          });
      return stateMachine.transition(State.create(status, operation));
    }

    return false;
  }

  /**
   * Fetch the current state from the state machine.
   *
   * @return The current state.
   */
  public synchronized ScheduleStatus getState() {
    return stateMachine.getState().getState();
  }

  /**
   * Gets the ID for the task that this state machine manages.
   *
   * @return The state machine's task ID.
   */
  public String getTaskId() {
    return taskId;
  }

  /**
   * Gets the previous state of this state machine.
   *
   * @return The state machine's previous state, or {@code null} if the state machine has not
   *     transitioned since being created.
   */
  @Nullable
  ScheduleStatus getPreviousState() {
    return previousState;
  }

  @Override
  public String toString() {
    return getTaskId();
  }
}
