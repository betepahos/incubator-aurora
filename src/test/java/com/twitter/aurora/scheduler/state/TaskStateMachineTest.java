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

import java.util.Set;

import com.google.common.base.Function;

import org.easymock.EasyMock;
import org.easymock.IExpectationSetters;
import org.junit.Before;
import org.junit.Test;

import com.twitter.aurora.gen.AssignedTask;
import com.twitter.aurora.gen.Identity;
import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.TaskConfig;
import com.twitter.aurora.gen.TaskEvent;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.state.TaskStateMachine.WorkSink;
import com.twitter.aurora.scheduler.storage.entities.IScheduledTask;
import com.twitter.common.testing.easymock.EasyMockTest;
import com.twitter.common.util.testing.FakeClock;

import static com.twitter.aurora.gen.ScheduleStatus.THROTTLED;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import static com.twitter.aurora.gen.ScheduleStatus.ASSIGNED;
import static com.twitter.aurora.gen.ScheduleStatus.FAILED;
import static com.twitter.aurora.gen.ScheduleStatus.FINISHED;
import static com.twitter.aurora.gen.ScheduleStatus.INIT;
import static com.twitter.aurora.gen.ScheduleStatus.KILLED;
import static com.twitter.aurora.gen.ScheduleStatus.KILLING;
import static com.twitter.aurora.gen.ScheduleStatus.LOST;
import static com.twitter.aurora.gen.ScheduleStatus.PENDING;
import static com.twitter.aurora.gen.ScheduleStatus.RESTARTING;
import static com.twitter.aurora.gen.ScheduleStatus.RUNNING;
import static com.twitter.aurora.gen.ScheduleStatus.STARTING;
import static com.twitter.aurora.gen.ScheduleStatus.UNKNOWN;
import static com.twitter.aurora.scheduler.state.WorkCommand.DELETE;
import static com.twitter.aurora.scheduler.state.WorkCommand.INCREMENT_FAILURES;
import static com.twitter.aurora.scheduler.state.WorkCommand.KILL;
import static com.twitter.aurora.scheduler.state.WorkCommand.RESCHEDULE;
import static com.twitter.aurora.scheduler.state.WorkCommand.UPDATE_STATE;

public class TaskStateMachineTest extends EasyMockTest {

  private WorkSink workSink;
  private FakeClock clock;
  private TaskStateMachine stateMachine;

  @Before
  public void setUp() {
    workSink = createMock(WorkSink.class);
    clock = new FakeClock();
    stateMachine = makeStateMachine("test", makeTask(false));
  }

  private TaskStateMachine makeStateMachine(String taskId, ScheduledTask builder) {
    return new TaskStateMachine(
        taskId,
        IScheduledTask.build(builder),
        workSink,
        clock,
        INIT);
  }

  @Test
  public void testSimpleTransition() {
    expectWork(UPDATE_STATE).times(5);
    expectWork(DELETE);

    control.replay();

    transition(stateMachine, PENDING);
    assertEquals(INIT, stateMachine.getPreviousState());
    transition(stateMachine, ASSIGNED);
    assertEquals(PENDING, stateMachine.getPreviousState());
    transition(stateMachine, STARTING);
    assertEquals(ASSIGNED, stateMachine.getPreviousState());
    transition(stateMachine, RUNNING);
    assertEquals(STARTING, stateMachine.getPreviousState());
    transition(stateMachine, FINISHED);
    assertEquals(RUNNING, stateMachine.getPreviousState());
    transition(stateMachine, UNKNOWN);
    assertEquals(FINISHED, stateMachine.getPreviousState());
  }

  @Test
  public void testServiceRescheduled() {
    stateMachine = makeStateMachine("test", makeTask(true));
    expectWork(UPDATE_STATE).times(5);
    expectWork(RESCHEDULE);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, FINISHED);
  }

  @Test
  public void testPostTerminalTransitionDenied() {
    Set<ScheduleStatus> terminalStates = Tasks.TERMINAL_STATES;

    for (ScheduleStatus endState : terminalStates) {
      stateMachine = makeStateMachine("test", makeTask(false));
      expectWork(UPDATE_STATE).times(5);

      switch (endState) {
        case FAILED:
          expectWork(INCREMENT_FAILURES);
          break;

        case FINISHED:
          break;

        case KILLED:
        case LOST:
          expectWork(RESCHEDULE);
          break;

        case KILLING:
          expectWork(KILL);
          break;

        default:
          fail("Unknown state " + endState);
      }

      control.replay();

      transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, endState);

      for (ScheduleStatus badTransition : terminalStates) {
        transition(stateMachine, badTransition);
      }

      control.verify();
      control.reset();
    }

    control.replay();  // Needed so the teardown verify doesn't break.
  }

  @Test
  public void testUnknownTask() {
    expectWork(KILL);

    control.replay();

    transition(stateMachine, UNKNOWN, RUNNING);
  }

  @Test
  public void testLostTask() {
    expectWork(UPDATE_STATE).times(5);
    expectWork(RESCHEDULE);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, LOST);
  }

  @Test
  public void testKilledPending() {
    expectWork(UPDATE_STATE);
    expectWork(DELETE);

    control.replay();

    transition(stateMachine, PENDING, KILLING);
  }

  @Test
  public void testMissingStartingRescheduledImmediately() {
    ScheduledTask task = makeTask(false);
    task.addToTaskEvents(new TaskEvent(clock.nowMillis(), ScheduleStatus.PENDING));
    stateMachine = makeStateMachine("test", task);

    expectWork(UPDATE_STATE).times(4);
    expectWork(RESCHEDULE);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, UNKNOWN);
    assertThat(stateMachine.getState(), is(ScheduleStatus.LOST));
  }

  @Test
  public void testMissingRunningRescheduledImmediately() {
    ScheduledTask task = makeTask(false);
    task.addToTaskEvents(new TaskEvent(clock.nowMillis(), ScheduleStatus.PENDING));
    stateMachine = makeStateMachine("test", task);

    expectWork(UPDATE_STATE).times(5);
    expectWork(RESCHEDULE);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, UNKNOWN);
    assertThat(stateMachine.getState(), is(ScheduleStatus.LOST));
  }

  @Test
  public void testRestartedTask() {
    expectWork(UPDATE_STATE).times(6);
    expectWork(KILL);
    expectWork(RESCHEDULE);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, RESTARTING, FINISHED);
  }

  @Test
  public void testRogueRestartedTask() {
    expectWork(UPDATE_STATE).times(5);
    expectWork(KILL).times(2);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, RESTARTING, RUNNING);
  }

  @Test
  public void testPendingRestartedTask() {
    expectWork(UPDATE_STATE).times(1);

    control.replay();

    // PENDING -> RESTARTING should not be allowed.
    transition(stateMachine, PENDING, RESTARTING);
  }

  @Test
  public void testAllowsSkipStartingAndRunning() {
    expectWork(UPDATE_STATE).times(3);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, FINISHED);
  }

  @Test
  public void testAllowsSkipRunning() {
    expectWork(UPDATE_STATE).times(4);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, FINISHED);
  }

  @Test
  public void testHonorsMaxFailures() {
    ScheduledTask task = makeTask(false);
    task.getAssignedTask().getTask().setMaxTaskFailures(10);
    task.setFailureCount(8);
    stateMachine = makeStateMachine("test", task);

    expectWork(UPDATE_STATE).times(5);
    expectWork(RESCHEDULE);
    expectWork(INCREMENT_FAILURES);

    ScheduledTask rescheduled = task.deepCopy();
    rescheduled.setFailureCount(9);
    TaskStateMachine rescheduledMachine = makeStateMachine("test2", rescheduled);
    expectWork(UPDATE_STATE, rescheduledMachine).times(5);
    expectWork(INCREMENT_FAILURES, rescheduledMachine);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, FAILED);

    transition(rescheduledMachine, PENDING, ASSIGNED, STARTING, RUNNING, FAILED);
  }

  @Test
  public void testHonorsUnlimitedFailures() {
    ScheduledTask task = makeTask(false);
    task.getAssignedTask().getTask().setMaxTaskFailures(-1);
    task.setFailureCount(1000);
    stateMachine = makeStateMachine("test", task);

    expectWork(UPDATE_STATE).times(5);
    expectWork(RESCHEDULE);
    expectWork(INCREMENT_FAILURES);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, FAILED);
  }

  @Test
  public void testKillingRequest() {
    expectWork(UPDATE_STATE).times(6);
    expectWork(KILL);

    control.replay();

    transition(stateMachine, PENDING, ASSIGNED, STARTING, RUNNING, KILLING, KILLED);
  }

  @Test
  public void testThrottledTask() {
    expectWork(UPDATE_STATE).times(2);

    control.replay();

    transition(stateMachine, THROTTLED, PENDING);
  }

  private static void transition(TaskStateMachine stateMachine, ScheduleStatus... states) {
    for (ScheduleStatus status : states) {
      stateMachine.updateState(status);
    }
  }

  private IExpectationSetters<Void> expectWork(WorkCommand work) {
    return expectWork(work, stateMachine);
  }

  private IExpectationSetters<Void> expectWork(WorkCommand work, TaskStateMachine machine) {
    workSink.addWork(
        eq(work),
        eq(machine),
        EasyMock.<Function<IScheduledTask, IScheduledTask>>anyObject());
    return expectLastCall();
  }

  private static ScheduledTask makeTask(boolean service) {
    return new ScheduledTask()
        .setAssignedTask(
            new AssignedTask()
                .setTask(
                    new TaskConfig()
                        .setOwner(new Identity().setRole("roleA"))
                        .setJobName("jobA")
                        .setIsService(service)));
  }
}
