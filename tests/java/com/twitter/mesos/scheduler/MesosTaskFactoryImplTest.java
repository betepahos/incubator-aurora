package com.twitter.mesos.scheduler;

import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.CommandInfo.URI;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.ExecutorInfo;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.Value.Scalar;
import org.apache.mesos.Protos.Value.Type;
import org.junit.Before;
import org.junit.Test;

import com.twitter.mesos.gen.AssignedTask;
import com.twitter.mesos.gen.Identity;
import com.twitter.mesos.gen.TwitterTaskInfo;
import com.twitter.mesos.scheduler.MesosTaskFactory.MesosTaskFactoryImpl;

import static org.junit.Assert.assertEquals;

public class MesosTaskFactoryImplTest {

  private static final String EXECUTOR_PATH = "/twitter/fake/executor.sh";
  private static final AssignedTask TASK = new AssignedTask()
      .setTaskId("task-id")
      .setTask(new TwitterTaskInfo()
          .setOwner(new Identity("role", "user"))
          .setJobName("job-name")
          .setDiskMb(10)
          .setRamMb(100)
          .setNumCpus(5));
  private static final SlaveID SLAVE = SlaveID.newBuilder().setValue("slave-id").build();

  private MesosTaskFactory taskFactory;

  @Before
  public void setUp() {
    taskFactory = new MesosTaskFactoryImpl(EXECUTOR_PATH);
  }

  @Test
  public void testExecutorInfoUnchanged() {
    // Tests against regression of MESOS-911.
    TaskInfo task = taskFactory.createFrom(TASK, SLAVE);

    ExecutorInfo expected = ExecutorInfo.newBuilder()
        .setExecutorId(ExecutorID.newBuilder().setValue("twitter"))
        .addResources(Resource.newBuilder()
            .setName("cpus")
            .setType(Type.SCALAR)
            .setScalar(Scalar.newBuilder().setValue(0.25)))
        .addResources(Resource.newBuilder()
            .setName("mem")
            .setType(Type.SCALAR)
            .setScalar(Scalar.newBuilder().setValue(3072.0)))
        .setCommand(CommandInfo.newBuilder()
            .setValue("./executor.sh")
            .addUris(URI.newBuilder().setValue(EXECUTOR_PATH).setExecutable(true)))
        .build();

    assertEquals(expected, task.getExecutor());
  }
}