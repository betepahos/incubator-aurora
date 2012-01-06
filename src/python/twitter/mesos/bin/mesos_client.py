#!/usr/bin/env python2.6

"""Command-line client for managing jobs with the Twitter mesos scheduler.
"""

# TODO(vinod): Use twitter.common.app subcommands instead of cmd
import cmd
import sys
import zookeeper

from twitter.common import app, log
from twitter.common.log.options import LogOptions

from twitter.mesos.client_wrapper import MesosClientAPI, MesosHelper
from gen.twitter.mesos.ttypes import *

__authors__ = ['William Farner',
               'Travis Crawford',
               'Alex Roetter']

def _die(msg):
  log.fatal(msg)
  sys.exit(1)


def check_and_log_response(resp):
  log.info('Response from scheduler: %s (message: %s)'
      % (ResponseCode._VALUES_TO_NAMES[resp.responseCode], resp.message))
  if resp.responseCode != ResponseCode.OK:
    sys.exit(1)


def check_and_log_update_response(resp):
  log.info('Update response from scheduler: %s (message: %s)'
      % (UpdateResponseCode._VALUES_TO_NAMES[resp.responseCode], resp.message))
  if resp.responseCode != UpdateResponseCode.OK:
    sys.exit(1)


class requires_arguments(object):
  def __init__(self, *args):
    self.expected_args = args

  def __call__(self, fn):
    def wrapped_fn(obj, line):
      sline = line.split()
      if len(sline) != len(self.expected_args):
        _die('Expected %d args, got %d for: %s' % (
            len(self.expected_args), len(sline), line))
      return fn(obj, *sline)
    wrapped_fn.__doc__ = fn.__doc__
    return wrapped_fn


class MesosCLI(cmd.Cmd):
  def __init__(self, opts):
    cmd.Cmd.__init__(self)
    zookeeper.set_debug_level(zookeeper.LOG_LEVEL_WARN)
    self.options = opts
    self.api = MesosClientAPI(cluster=self.options.cluster, verbose=self.options.verbose)

  @requires_arguments('job', 'config')
  def do_create(self, *line):
    """create job config"""
    (jobname, config_file) = line
    config = MesosHelper.get_config(jobname, config_file, self.options.force_thermos)

    resp = self.api.create_job(jobname, config, self.options.copy_app_from)
    check_and_log_response(resp)

  @requires_arguments('job', 'config')
  def do_inspect(self, *line):
    """inspect job config"""
    (jobname, config_file) = line
    config = MesosHelper.get_config(jobname, config_file, self.options.force_thermos)

    self.api.inspect(jobname, config, self.options.copy_app_from)

  @requires_arguments('role', 'job')
  def do_start_cron(self, *line):
    """start_cron role job"""
    (role, jobname) = line

    resp = self.api.start_cronjob(role, jobname)
    check_and_log_response(resp)

  @requires_arguments('role', 'job')
  def do_kill(self, *line):
    """kill role job"""
    (role, jobname) = line

    resp = self.api.kill_job(role, jobname)
    check_and_log_response(resp)

  @requires_arguments('role', 'job')
  def do_status(self, *line):
    """status role job"""

    ACTIVE_STATES = set([ScheduleStatus.PENDING, ScheduleStatus.STARTING, ScheduleStatus.RUNNING])
    def is_active(task):
      return task.status in ACTIVE_STATES

    def print_task(scheduled_task):
      assigned_task = scheduled_task.assignedTask
      taskInfo = assigned_task.task
      taskString = ''
      if taskInfo:
        taskString += '''\tHDFS path: %s
\tcpus: %s, ram: %s MB, disk: %s MB''' % (taskInfo.hdfsPath,
                                          taskInfo.numCpus,
                                          taskInfo.ramMb,
                                          taskInfo.diskMb)
      if assigned_task.assignedPorts:
        taskString += '\n\tports: %s' % assigned_task.assignedPorts
      taskString += '\n\tfailure count: %s (max %s)' % (scheduled_task.failureCount,
                                                        taskInfo.maxTaskFailures)
      return taskString

    def print_tasks(tasks):
      for task in tasks:
        taskString = print_task(task)

        log.info('shard: %s, status: %s on %s\n%s' %
               (task.assignedTask.task.shardId,
                ScheduleStatus._VALUES_TO_NAMES[task.status],
                task.assignedTask.slaveHost,
                taskString))

    (role, jobname) = line

    resp = self.api.check_status(role, jobname)
    check_and_log_response(resp)

    if resp.tasks:
      active_tasks = filter(is_active, resp.tasks)
      log.info('Active Tasks (%s)' % len(active_tasks))
      print_tasks(active_tasks)
      inactive_tasks = filter(lambda x: not is_active(x), resp.tasks)
      log.info('Inactive Tasks (%s)' % len(inactive_tasks))
      print_tasks(inactive_tasks)
    else:
      log.info('No tasks found.')

  @requires_arguments('job', 'config')
  def do_update(self, *line):
    """update job config"""
    (jobname, config_file) = line
    config = MesosHelper.get_config(jobname, config_file, self.options.force_thermos)

    resp = self.api.update_job(jobname, config, self.options.copy_app_from)
    check_and_log_update_response(resp)

  @requires_arguments('role', 'job')
  def do_cancel_update(self, *line):
    """cancel_update role job"""
    (role, jobname) = line

    resp = self.api.cancel_update(role, jobname)
    check_and_log_update_response(resp)

  @requires_arguments('role')
  def do_get_quota(self, *line):
    """get_quota role"""
    role = line[0]
    resp = self.api.get_quota(role)
    quota = resp.quota

    quota_fields = [
      ('CPU', quota.numCpus),
      ('RAM', '%f GB' % (float(quota.ramMb) / 1024)),
      ('Disk', '%f GB' % (float(quota.diskMb) / 1024))
    ]
    log.info('Quota for %s:\n\t%s' %
             (role, '\n\t'.join(['%s\t%s' % (k, v) for (k, v) in quota_fields])))

  # TODO(wfarner): Revisit this once we have a better way to add named args per sub-cmmand
  @requires_arguments('role', 'cpu', 'ramMb', 'diskMb')
  def do_set_quota(self, *line):
    """set_quota role cpu ramMb diskMb"""
    (role, cpu_str, ram_mb_str, disk_mb_str) = line

    try:
      cpu = float(cpu_str)
      ram_mb = int(ram_mb_str)
      disk_mb = int(disk_mb_str)
    except ValueError:
      log.error('Invalid value')

    resp = self.api.set_quota(role, cpu, ram_mb, disk_mb)
    check_and_log_response(resp)


def initialize_options():
  usage = """Mesos command-line interface.

For more information contact mesos-users@twitter.com, or explore the
documentation at https://sites.google.com/a/twitter.com/mesos/

Usage generally involves running a subcommand with positional arguments.
The subcommands and their arguments are:
"""

  for method in sorted(dir(MesosCLI)):
    if method.startswith('do_') and getattr(MesosCLI, method).__doc__:
      usage = usage + '\n    ' + getattr(MesosCLI, method).__doc__

  app.set_usage(usage)
  app.interspersed_args(True)
  app.add_option(
    '-v',
    dest='verbose',
    default=False,
    action='store_true',
    help='Verbose logging. (default: %default)')
  app.add_option(
    '-q',
    dest='quiet',
    default=False,
    action='store_true',
    help='Minimum logging. (default: %default)')
  app.add_option(
    '--cluster',
    dest='cluster',
    default=None,
    help="Cluster to launch the job in (e.g. sjc1, smf1-prod, smf1-nonprod)." + \
         " NOTE: If specified, this option overrides the cluster specified in the config file")
  app.add_option(
    '--copy_app_from',
    dest='copy_app_from',
    default=None,
    help="Local path to use for the app (will copy to the cluster)" + \
          " (default: %default)")
  app.add_option(
    '--tunnel_as',
    default=None,
    help='User to tunnel as (defaults to job role)')
  app.add_option(
    '-t',
    '--force_thermos',
    default=False,
    action='store_true',
    help="Run jobs configured in mesos format as thermos jobs.")


def main(args, options):
  if not args:
    app.help()
    sys.exit(1)

  if options.quiet:
    LogOptions.set_stdout_log_level('NONE')
  else:
    if options.verbose:
      LogOptions.set_stdout_log_level('DEBUG')
    else:
      LogOptions.set_stdout_log_level('INFO')

  cli = MesosCLI(options)

  try:
    cli.onecmd(' '.join(args))
  except AssertionError as e:
    _die('\n%s' % e)

initialize_options()
app.main()
