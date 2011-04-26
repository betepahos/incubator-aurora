package com.twitter.mesos.scheduler.httphandlers;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import org.antlr.stringtemplate.StringTemplate;

import com.twitter.common.base.Closure;
import com.twitter.common.net.http.handlers.StringTemplateServlet;
import com.twitter.mesos.Tasks;
import com.twitter.mesos.gen.JobConfiguration;
import com.twitter.mesos.scheduler.ClusterName;
import com.twitter.mesos.scheduler.CronJobManager;
import com.twitter.mesos.scheduler.Query;
import com.twitter.mesos.scheduler.SchedulerCore;
import com.twitter.mesos.scheduler.TaskState;

import it.sauronsoftware.cron4j.Predictor;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.twitter.common.base.MorePreconditions.checkNotBlank;

/**
 * HTTP interface to provide information about jobs for a specific mesos role.
 *
 * @author William Farner
 */
public class SchedulerzRole extends StringTemplateServlet {

  private static Logger LOG = Logger.getLogger(SchedulerzRole.class.getName());

  private static final String ROLE_PARAM = "role";
  private static final String START_CRON_PARAM = "start_cron";

  private final SchedulerCore scheduler;
  private final CronJobManager cronScheduler;
  private final String clusterName;

  @Inject
  public SchedulerzRole(@CacheTemplates boolean cacheTemplates,
      SchedulerCore scheduler,
      CronJobManager cronScheduler,
      @ClusterName String clusterName) {
    super("schedulerzrole", cacheTemplates);
    this.scheduler = checkNotNull(scheduler);
    this.cronScheduler = checkNotNull(cronScheduler);
    this.clusterName = checkNotBlank(clusterName);
  }

  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {

    final String role = req.getParameter(ROLE_PARAM);
    final String cronJobLaunched = req.getParameter(START_CRON_PARAM);

    final AtomicReference<String> cronLaunchException = new AtomicReference<String>();
    if (cronJobLaunched != null) {
      if (!cronScheduler.hasJob(Tasks.jobKey(role, cronJobLaunched))) {
        cronLaunchException.set("Unrecognized cron job " + cronJobLaunched);
      } else {
        LOG.info("Received web request to launch cron job " + role + "/" + cronJobLaunched);
        cronScheduler.startJobNow(Tasks.jobKey(role, cronJobLaunched));
        resp.sendRedirect("/scheduler/role?role=" + role);
      }
    }

    writeTemplate(resp, new Closure<StringTemplate>() {
      @Override public void execute(StringTemplate template) {
        template.setAttribute("cluster_name", clusterName);

        if (role == null) {
          template.setAttribute("exception", "Please specify a user.");
          return;
        }
        template.setAttribute("role", role);

        if (cronLaunchException.get() != null) {
          template.setAttribute("exception", cronLaunchException);
          return;
        }

        Map<String, Job> jobs = Maps.newHashMap();

        for (TaskState state : scheduler.getTasks(Query.byRole(role))) {
          Job job = jobs.get(state.task.getAssignedTask().getTask().getJobName());
          if (job == null) {
            job = new Job();
            job.name = state.task.getAssignedTask().getTask().getJobName();
            jobs.put(job.name, job);
          }

          switch (state.task.getStatus()) {
            case PENDING:
              job.pendingTaskCount++;
              break;

            case STARTING:
            case RUNNING:
              job.activeTaskCount++;
              break;

            case KILLED:
            case KILLED_BY_CLIENT:
            case FINISHED:
              job.finishedTaskCount++;
              break;

            case LOST:
            case NOT_FOUND:
            case FAILED:
              job.failedTaskCount++;
              break;

            default:
              throw new IllegalArgumentException("Unsupported status: " + state.task.getStatus());
          }
        }

        template.setAttribute("jobs",
            DisplayUtils.sort(jobs.values(), DisplayUtils.SORT_JOB_BY_NAME));

        Iterable<JobConfiguration> cronJobs = Iterables.filter(
            cronScheduler.getJobs(), new Predicate<JobConfiguration>() {
              @Override public boolean apply(JobConfiguration job) {
                return job.getOwner().getRole().equals(role);
              }
            });
        cronJobs = DisplayUtils.sort(cronJobs, DisplayUtils.SORT_JOB_CONFIG_BY_NAME);
        Iterable<CronJob> cronJobObjs = Iterables.transform(cronJobs,
            new Function<JobConfiguration, CronJob>() {
              @Override public CronJob apply(JobConfiguration job) {
                CronJob cronJob = new CronJob();
                cronJob.name = job.getName();
                cronJob.pendingTaskCount = job.getTaskConfigsSize();
                cronJob.cronSchedule = job.getCronSchedule();
                cronJob.nextRun = new Predictor(cronJob.cronSchedule).nextMatchingDate().toString();
                return cronJob;
              }
            });

        template.setAttribute("cronJobs", Lists.newArrayList(cronJobObjs));
      }
    });
  }

  static class Job {
    String name;
    int pendingTaskCount = 0;
    int activeTaskCount = 0;
    int finishedTaskCount = 0;
    int failedTaskCount = 0;

    public String getName() {
      return name;
    }

    public int getPendingTaskCount() {
      return pendingTaskCount;
    }

    public int getActiveTaskCount() {
      return activeTaskCount;
    }

    public int getFinishedTaskCount() {
      return finishedTaskCount;
    }

    public int getFailedTaskCount() {
      return failedTaskCount;
    }
  }

  class CronJob extends Job {
    String cronSchedule;
    String nextRun;

    public String getCronSchedule() {
      return cronSchedule;
    }

    public String getNextRun() {
      return nextRun;
    }
  }
}