package com.hubspot.singularity.scheduler;

import java.text.ParseException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.ExtendedTaskState;
import com.hubspot.singularity.RequestState;
import com.hubspot.singularity.SingularityCreateResult;
import com.hubspot.singularity.SingularityDeployStatistics;
import com.hubspot.singularity.SingularityDeployStatisticsBuilder;
import com.hubspot.singularity.SingularityPendingRequest;
import com.hubspot.singularity.SingularityPendingRequest.PendingType;
import com.hubspot.singularity.SingularityPendingTask;
import com.hubspot.singularity.SingularityPendingTaskId;
import com.hubspot.singularity.SingularityRack;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestDeployState;
import com.hubspot.singularity.SingularityRequestWithState;
import com.hubspot.singularity.SingularitySlave;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskCleanup;
import com.hubspot.singularity.SingularityTaskCleanup.TaskCleanupType;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.SingularityTaskRequest;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.DeployManager;
import com.hubspot.singularity.data.RackManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.SlaveManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.TaskRequestManager;
import com.hubspot.singularity.smtp.SingularityMailer;

public class SingularityScheduler {

  private final static Logger LOG = LoggerFactory.getLogger(SingularityScheduler.class);
  
  private final SingularityConfiguration configuration;
  
  private final TaskManager taskManager;
  private final RequestManager requestManager;
  private final TaskRequestManager taskRequestManager;
  private final DeployManager deployManager;
  
  private final SlaveManager slaveManager;
  private final RackManager rackManager;
  
  private final SingularityMailer mailer;
  
  @Inject
  public SingularityScheduler(TaskRequestManager taskRequestManager, SingularityConfiguration configuration, DeployManager deployManager, TaskManager taskManager, RequestManager requestManager, SlaveManager slaveManager, RackManager rackManager, SingularityMailer mailer) {
    this.taskRequestManager = taskRequestManager;
    this.configuration = configuration;
    this.deployManager = deployManager;
    this.taskManager = taskManager;
    this.requestManager = requestManager;
    this.slaveManager = slaveManager;
    this.rackManager = rackManager;
    this.mailer = mailer;
  }
  
  private void checkTaskForDecomissionCleanup(final Set<String> requestIdsToReschedule, final Set<SingularityTaskId> matchingTaskIds, SingularityTask task, String decomissioningObject) {
    requestIdsToReschedule.add(task.getTaskRequest().getRequest().getId());
    
    matchingTaskIds.add(task.getTaskId());

    if (!task.getTaskRequest().getRequest().isScheduled()) {
      LOG.trace("Scheduling a cleanup task for {} due to decomissioning {}", task.getTaskId(), decomissioningObject);
      
      taskManager.createCleanupTask(new SingularityTaskCleanup(Optional.<String> absent(), TaskCleanupType.DECOMISSIONING, System.currentTimeMillis(), task.getTaskId()));
    } else {
      LOG.trace("Not adding scheduled task {} to cleanup queue", task.getTaskId());
    }
  }
  
  public void checkForDecomissions(SingularitySchedulerStateCache stateCache) {
    final long start = System.currentTimeMillis();
    
    final Set<String> requestIdsToReschedule = Sets.newHashSet();
    final Set<SingularityTaskId> matchingTaskIds = Sets.newHashSet();
    
    final List<SingularityTaskId> activeTaskIds = stateCache.getActiveTaskIds();
    
    final List<SingularitySlave> slaves = slaveManager.getDecomissioningObjectsFiltered(stateCache.getDecomissioningSlaves());
    
    for (SingularitySlave slave : slaves) {
      for (SingularityTask activeTask : taskManager.getTasksOnSlave(activeTaskIds, slave)) {
        checkTaskForDecomissionCleanup(requestIdsToReschedule, matchingTaskIds, activeTask, slave.toString());
      }
    }
    
    final List<SingularityRack> racks = rackManager.getDecomissioningObjectsFiltered(stateCache.getDecomissioningRacks());
    
    for (SingularityRack rack : racks) {
      for (SingularityTaskId activeTaskId : activeTaskIds) {
        if (matchingTaskIds.contains(activeTaskId)) {
          continue;
        }
    
        if (rack.getId().equals(activeTaskId.getRackId())) {
          Optional<SingularityTask> maybeTask = taskManager.getActiveTask(activeTaskId.getId());
          checkTaskForDecomissionCleanup(requestIdsToReschedule, matchingTaskIds, maybeTask.get(), rack.toString());
        }
      }
    }
    
    for (String requestId : requestIdsToReschedule) {
      LOG.trace("Rescheduling request {} due to decomissions", requestId);
      
      Optional<String> maybeDeployId = deployManager.getInUseDeployId(requestId);
      
      if (maybeDeployId.isPresent()) {
        requestManager.addToPendingQueue(new SingularityPendingRequest(requestId, maybeDeployId.get(), PendingType.DECOMISSIONED_SLAVE_OR_RACK));
      } else {
        LOG.warn("Not rescheduling a request ({}) because of no active deploy", requestId);
      }
    }
    
    for (SingularitySlave slave : slaves) {
      LOG.debug("Marking slave {} as decomissioned", slave);
      slaveManager.markAsDecomissioned(slave);
    }
    
    for (SingularityRack rack : racks) {
      LOG.debug("Marking rack {} as decomissioned", rack);
      rackManager.markAsDecomissioned(rack);
    }

    LOG.info("Found {} decomissioning slaves, {} decomissioning racks, rescheduling {} requests and scheduling {} tasks for cleanup in {}", slaves.size(), racks.size(), requestIdsToReschedule.size(), matchingTaskIds.size(), JavaUtils.duration(start));
  }
  
  public void drainPendingQueue(final SingularitySchedulerStateCache stateCache) {
    final long start = System.currentTimeMillis();
    
    final List<SingularityPendingRequest> pendingRequests = requestManager.getPendingRequests();
    
    LOG.info("Pending queue had {} requests", pendingRequests.size());
    
    if (pendingRequests.isEmpty()) {
      return;
    }
    
    int totalNewScheduledTasks = 0;
    int obsoleteRequests = 0;
    
    for (SingularityPendingRequest pendingRequest : pendingRequests) {
      Optional<SingularityRequestWithState> maybeRequest = requestManager.getRequest(pendingRequest.getRequestId());
      
      if (shouldScheduleTasks(maybeRequest)) {
        checkForBounceAndAddToCleaningTasks(pendingRequest, stateCache.getActiveTaskIds(), stateCache.getCleaningTasks());
        
        int numScheduledTasks = scheduleTasks(stateCache, maybeRequest.get().getRequest(), maybeRequest.get().getState(), getDeployStatitics(pendingRequest.getRequestId(), pendingRequest.getDeployId()), pendingRequest);
      
        LOG.debug("Pending request {} resulted in {} new scheduled tasks", pendingRequest, numScheduledTasks);
      
        totalNewScheduledTasks += numScheduledTasks;
      } else {
        LOG.debug("Pending request {} was obsolete (request {})", pendingRequest, SingularityRequestWithState.getRequestState(maybeRequest));
        
        obsoleteRequests++;
      }
      
      requestManager.deletePendingRequest(pendingRequest);
    }
    
    LOG.info("Scheduled {} new tasks ({} obsolete requests) in {}", totalNewScheduledTasks, obsoleteRequests, JavaUtils.duration(start));
  }
  
  private void checkForBounceAndAddToCleaningTasks(SingularityPendingRequest pendingRequest, final List<SingularityTaskId> activeTaskIds, final List<SingularityTaskId> cleaningTasks) {
    if (pendingRequest.getPendingType() != PendingType.BOUNCE) {
      return;
    }
    
    final long now = System.currentTimeMillis(); 
    
    final List<SingularityTaskId> matchingTaskIds = SingularityTaskId.matchingAndNotIn(activeTaskIds, pendingRequest.getRequestId(), pendingRequest.getDeployId(), cleaningTasks);
    
    for (SingularityTaskId matchingTaskId : matchingTaskIds) {
      LOG.debug("Adding task {} to cleanup (bounce)", matchingTaskId.getId());
      
      taskManager.createCleanupTask(new SingularityTaskCleanup(pendingRequest.getUser(), TaskCleanupType.BOUNCING, now, matchingTaskId));
      cleaningTasks.add(matchingTaskId);
    }
    
    LOG.info("Added {} tasks for request {} to cleanup bounce queue in {}", matchingTaskIds.size(), pendingRequest.getRequestId(), JavaUtils.duration(now));
  }
    
  public List<SingularityTaskRequest> getDueTasks() {
    final List<SingularityPendingTask> tasks = taskManager.getScheduledTasks();
      
    final long now = System.currentTimeMillis();
    
    final List<SingularityPendingTask> dueTasks = Lists.newArrayListWithCapacity(tasks.size());
    
    for (SingularityPendingTask task : tasks) {
      if (task.getPendingTaskId().getNextRunAt() <= now) {
        dueTasks.add(task);
      }
    }
    
    final List<SingularityTaskRequest> dueTaskRequests = taskRequestManager.getTaskRequests(dueTasks);
    Collections.sort(dueTaskRequests);
    
    checkForStaleScheduledTasks(dueTasks, dueTaskRequests);
    
    return dueTaskRequests;
  }
  
  private void checkForStaleScheduledTasks(List<SingularityPendingTask> pendingTasks, List<SingularityTaskRequest> taskRequests) {
    final Set<String> foundRequestIds = Sets.newHashSetWithExpectedSize(taskRequests.size());
    for (SingularityTaskRequest taskRequest : taskRequests) {
      foundRequestIds.add(taskRequest.getRequest().getId());
    }
    for (SingularityPendingTask pendingTask : pendingTasks) {
      if (!foundRequestIds.contains(pendingTask.getPendingTaskId().getRequestId())) {
        LOG.info("Removing stale pending task {} because there was no found request id", pendingTask.getPendingTaskId());
        taskManager.deleteScheduledTask(pendingTask.getPendingTaskId().getId());
      }
    }
  }
  
  private void deleteScheduledTasks(final List<SingularityPendingTask> scheduledTasks, String requestId) {
    for (SingularityPendingTask task : Iterables.filter(scheduledTasks, SingularityPendingTask.matching(requestId))) {
      taskManager.deleteScheduledTask(task.getPendingTaskId().getId());
    }
  }
  
  private int scheduleTasks(SingularitySchedulerStateCache stateCache, SingularityRequest request, RequestState state, SingularityDeployStatistics deployStatistics, SingularityPendingRequest pendingRequest) {
    deleteScheduledTasks(stateCache.getScheduledTasks(), request.getId());
    
    final List<SingularityTaskId> matchingTaskIds = SingularityTaskId.matchingAndNotIn(stateCache.getActiveTaskIds(), request.getId(), pendingRequest.getDeployId(), stateCache.getCleaningTasks());
    
    final int numMissingInstances = getNumMissingInstances(matchingTaskIds, request);

    if (numMissingInstances > 0) {
      LOG.debug("Missing {} instances of request {} (matching tasks: {}), pending request: {}", numMissingInstances, request.getId(), matchingTaskIds, pendingRequest);
      
      final List<SingularityPendingTask> scheduledTasks = getScheduledTaskIds(numMissingInstances, matchingTaskIds, request, state, deployStatistics, pendingRequest.getDeployId(), pendingRequest);
      
      LOG.trace("Scheduling tasks: {}", scheduledTasks);
      
      taskManager.persistScheduleTasks(scheduledTasks);
    } else if (numMissingInstances < 0) {
      LOG.debug("Missing instances is negative: {}, request {}, matching tasks: {}", numMissingInstances, request, matchingTaskIds);
      
      final long now = System.currentTimeMillis();
      
      for (int i = 0; i < Math.abs(numMissingInstances); i++) {
        final SingularityTaskId toCleanup = matchingTaskIds.get(i);
        
        LOG.info("Cleaning up task {} due to new request {} - scaling down to {} instances", toCleanup.getId(), request.getId(), request.getInstancesSafe());
    
        taskManager.createCleanupTask(new SingularityTaskCleanup(Optional.<String> absent(), TaskCleanupType.SCALING_DOWN, now, toCleanup));
      }
    }
    
    return numMissingInstances;
  }
  
  private boolean wasDecomissioning(SingularityTaskId taskId, Optional<SingularityTask> maybeActiveTask, SingularitySchedulerStateCache stateCache) {
    if (!maybeActiveTask.isPresent()) {
      return false;
    }
    
    return stateCache.isSlaveDecomissioning(maybeActiveTask.get().getMesosTask().getSlaveId().getValue()) || stateCache.isRackDecomissioning(taskId.getRackId());
  }
  
  private boolean shouldScheduleTasks(Optional<SingularityRequestWithState> maybeRequestWithState) {
    return SingularityRequestWithState.isActive(maybeRequestWithState);
  }
  
  private Optional<PendingType> handleCompletedTaskWithStatistics(Optional<SingularityTask> maybeActiveTask, SingularityTaskId taskId, ExtendedTaskState state, SingularityDeployStatistics deployStatistics, long failTime, SingularityCreateResult taskHistoryUpdateCreateResult, SingularitySchedulerStateCache stateCache) {
    final Optional<SingularityRequestWithState> maybeRequestWithState = requestManager.getRequest(taskId.getRequestId());
    
    if (!shouldScheduleTasks(maybeRequestWithState)) {
      LOG.warn("Not scheduling a new task, due to {} request for {}", SingularityRequestWithState.getRequestState(maybeRequestWithState), taskId.getRequestId());
      return Optional.absent();
    }
    
    RequestState requestState = maybeRequestWithState.get().getState();
    final SingularityRequest request = maybeRequestWithState.get().getRequest();
    
    final Optional<SingularityRequestDeployState> requestDeployState = deployManager.getRequestDeployState(request.getId());
    
    if (!requestDeployState.isPresent() || !requestDeployState.get().getActiveDeploy().isPresent() || !requestDeployState.get().getActiveDeploy().get().getDeployId().equals(taskId.getDeployId())) {
      LOG.debug("Task {} completed, but it didn't match active deploy state - ignoring", taskId.getId(), requestDeployState);
      return Optional.absent();
    }
    
    PendingType pendingType = PendingType.TASK_DONE;
    
    if (state.isFailed()) {
      boolean wasDecomissioning = wasDecomissioning(taskId, maybeActiveTask, stateCache);
      
      if (!wasDecomissioning && taskHistoryUpdateCreateResult == SingularityCreateResult.CREATED && requestState != RequestState.SYSTEM_COOLDOWN) {
        mailer.sendTaskFailedMail(taskId, request, state);
      } else {
        if (wasDecomissioning) {
          LOG.debug("Not sending a task failure email because task {} was on a decomissioning slave/rack", taskId);
        } else if (requestState == RequestState.SYSTEM_COOLDOWN) {
          LOG.debug("Not sending a task failure email because task {} is in SYSTEM_COOLDOWN", taskId);
        } else {
          LOG.debug("Not sending a task failure email because task {} already recieved an failure update", taskId);
        }
      }
      
      if (taskHistoryUpdateCreateResult == SingularityCreateResult.CREATED && shouldEnterCooldown(request, deployStatistics)) {
        LOG.info("Request {} is entering cooldown due to failed task {}", request.getId(), taskId);
        requestState = RequestState.SYSTEM_COOLDOWN;
        requestManager.cooldown(request);
        mailer.sendRequestInCooldownMail(request);
      }
    }
    
    if (state.isSuccess()) {
      if (requestState == RequestState.SYSTEM_COOLDOWN) {
        // TODO send not cooldown anymore email
        LOG.info("Request {} succeeded a task, removing from cooldown", request.getId());
        requestState = RequestState.ACTIVE;
        requestManager.makeActive(request);
      }
    } else if (request.isScheduled()) {
      if (state.isFailed()) {
        if (shouldRetryImmediately(request, deployStatistics)) {
          pendingType = PendingType.RETRY;
        } 
      } else {
        LOG.debug("Setting pendingType to retry for request {}, because it failed due to {}", request.getId(), state);
        pendingType = PendingType.RETRY;
      }
    }
    
    if (!request.isOneOff()) {
      scheduleTasks(stateCache, request, requestState, deployStatistics, new SingularityPendingRequest(request.getId(), requestDeployState.get().getActiveDeploy().get().getDeployId(), pendingType));
      return Optional.of(pendingType);
    }
    
    return Optional.absent();
  }
  
  private SingularityDeployStatistics getDeployStatitics(String requestId, String deployId) {
    final Optional<SingularityDeployStatistics> maybeDeployStatistics = deployManager.getDeployStatistics(requestId, deployId);
    
    if (maybeDeployStatistics.isPresent()) {
      return maybeDeployStatistics.get();
    }
    
    return new SingularityDeployStatisticsBuilder(requestId, deployId).build();
  }
  
  public void handleCompletedTask(Optional<SingularityTask> maybeActiveTask, SingularityTaskId taskId, ExtendedTaskState state, SingularityCreateResult taskHistoryUpdateCreateResult, SingularitySchedulerStateCache stateCache) {
    final long failTime = System.currentTimeMillis();
    
    final SingularityDeployStatistics deployStatistics = getDeployStatitics(taskId.getRequestId(), taskId.getDeployId());
    
    if (maybeActiveTask.isPresent()) {
      taskManager.deleteActiveTask(taskId.getId());
    }

    taskManager.createLBCleanupTask(taskId);
    
    final Optional<PendingType> scheduleResult = handleCompletedTaskWithStatistics(maybeActiveTask, taskId, state, deployStatistics, failTime, taskHistoryUpdateCreateResult, stateCache);
    
    if (taskHistoryUpdateCreateResult == SingularityCreateResult.EXISTED) {
      return;
    }
    
    SingularityDeployStatisticsBuilder bldr = deployStatistics.toBuilder();
    
    bldr.setLastFinishAt(Optional.of(failTime));
    bldr.setLastTaskState(Optional.of(state));
    
    if (state.isFailed()) {
      bldr.setNumSequentialFailures(bldr.getNumSequentialFailures() + 1);
      bldr.setNumSequentialSuccess(0);
      bldr.setNumFailures(bldr.getNumFailures() + 1);
    } else if (state.isSuccess()) {
      bldr.setNumSequentialFailures(0);
      bldr.setNumSequentialSuccess(bldr.getNumSequentialSuccess() + 1);
      bldr.setNumSuccess(bldr.getNumSuccess() + 1);
    }

    if (scheduleResult.isPresent() && scheduleResult.get() == PendingType.RETRY) {
      bldr.setNumSequentialRetries(bldr.getNumSequentialRetries() + 1);
    } else {
      bldr.setNumSequentialRetries(0);
    }
    
    final SingularityDeployStatistics newStatistics = bldr.build();
    
    LOG.trace("Saving new deploy statistics {}", newStatistics);
    
    deployManager.saveDeployStatistics(newStatistics);
  }
  
  private boolean shouldRetryImmediately(SingularityRequest request, SingularityDeployStatistics deployStatistics) {
    if (!request.getNumRetriesOnFailure().isPresent()) {
      return false;
    }
    
    final int numRetriesInARow = deployStatistics.getNumSequentialRetries();
    
    if (numRetriesInARow >= request.getNumRetriesOnFailure().get()) {
      LOG.debug("Request {} had {} retries in a row, not retrying again (num retries on failure: {})", request.getId(), numRetriesInARow, request.getNumRetriesOnFailure());
      return false;
    }
    
    LOG.debug("Request {} had {} retries in a row - retrying again (num retries on failure: {})", request.getId(), numRetriesInARow, request.getNumRetriesOnFailure());
    
    return true;
  } 
  
  private boolean shouldEnterCooldown(SingularityRequest request, SingularityDeployStatistics deployStatistics) {
    if (configuration.getCooldownAfterFailures() < 1) {
      return false;
    }
    
    final int numSequentialFailures = deployStatistics.getNumSequentialFailures() + 1;
    final boolean failedTooManyTimes = numSequentialFailures >= configuration.getCooldownAfterFailures();
    
    if (failedTooManyTimes) {
      LOG.trace("Request {} failed {} times, which is over the cooldown threshold of {}", request.getId(), numSequentialFailures, configuration.getCooldownAfterFailures());
    }

    if (!deployStatistics.getLastFinishAt().isPresent()) {
      LOG.trace("Can't factor finish time into cooldown state for request {} because there wasn't a previous task finish time", request.getId());
      return failedTooManyTimes;
    }
    
    if (hasCooldownExpired(deployStatistics)) {
      return false;
    }
    
    return failedTooManyTimes;
  }
  
  private final int getNumMissingInstances(List<SingularityTaskId> matchingTaskIds, SingularityRequest request) {
    final int numInstances = request.getInstancesSafe();
    
    return numInstances - matchingTaskIds.size();
  }
  
  private List<SingularityPendingTask> getScheduledTaskIds(int numMissingInstances, List<SingularityTaskId> matchingTaskIds, SingularityRequest request, RequestState state, SingularityDeployStatistics deployStatistics, String deployId, SingularityPendingRequest pendingRequest) {
    final long nextRunAt = getNextRunAt(request, state, deployStatistics, pendingRequest.getPendingType());
  
    int highestInstanceNo = 0;

    for (SingularityTaskId matchingTaskId : matchingTaskIds) {
      if (matchingTaskId.getInstanceNo() > highestInstanceNo) {
        highestInstanceNo = matchingTaskId.getInstanceNo();
      }
    }
    
    final List<SingularityPendingTask> newTasks = Lists.newArrayListWithCapacity(numMissingInstances);
    
    for (int i = 0; i < numMissingInstances; i++) {
      newTasks.add(new SingularityPendingTask(new SingularityPendingTaskId(request.getId(), deployId, nextRunAt, i + 1 + highestInstanceNo, pendingRequest.getPendingType()), pendingRequest.getCmdLineArgs()));
    }
    
    return newTasks;
  }
  
  private long getNextRunAt(SingularityRequest request, RequestState state, SingularityDeployStatistics deployStatistics, PendingType pendingType) {
    final long now = System.currentTimeMillis();
    
    long nextRunAt = now;
    
    if (request.isScheduled()) {
      if (pendingType == PendingType.IMMEDIATE || pendingType == PendingType.RETRY) {
        LOG.info("Scheduling requested immediate run of {}", request.getId());
      } else {
        try {
          Date scheduleFrom = new Date(now);

          CronExpression cronExpression = new CronExpression(request.getSchedule().get());

          final Date nextRunAtDate = cronExpression.getNextValidTimeAfter(scheduleFrom);

          LOG.trace("Calculating nextRunAtDate for {} (schedule: {}): {} (from: {})", request.getId(), request.getSchedule(), nextRunAtDate, scheduleFrom);

          nextRunAt = Math.max(nextRunAtDate.getTime(), now); // don't create a schedule that is overdue as this is used to indicate that singularity is not fulfilling requests.
          
          LOG.trace("Scheduling next run of {} (schedule: {}) at {} (from: {})", request.getId(), request.getSchedule(), nextRunAtDate, scheduleFrom);
        } catch (ParseException pe) {
          throw Throwables.propagate(pe);
        }
      }
    }
        
    if (state == RequestState.SYSTEM_COOLDOWN) {
      if (hasCooldownExpired(deployStatistics)) {
        requestManager.saveRequest(request);
      } else {
        final long prevNextRunAt = nextRunAt;
        nextRunAt = Math.max(nextRunAt, now + TimeUnit.SECONDS.toMillis(configuration.getCooldownMinScheduleSeconds()));
        LOG.trace("Adjusted next run of {} to {} (from: {}) due to cooldown", request.getId(), nextRunAt, prevNextRunAt);
      }
    }
    
    return nextRunAt;
  }
  
  private boolean hasCooldownExpired(SingularityDeployStatistics deployStatistics) {
    if (configuration.getCooldownExpiresAfterMinutes() < 1 || !deployStatistics.getLastFinishAt().isPresent()) {
      return false;
    }
    
    final long cooldownExpiresMillis = TimeUnit.MINUTES.toMillis(configuration.getCooldownExpiresAfterMinutes());
    
    final long lastFinishAt = deployStatistics.getLastFinishAt().get().longValue();
    final long timeSinceLastFinish = System.currentTimeMillis() - lastFinishAt;
    
    final boolean hasCooldownExpired = timeSinceLastFinish > cooldownExpiresMillis;
    
    if (hasCooldownExpired) {
      LOG.trace("Request {} cooldown has expired or is not valid because the last task finished {} ago (cooldowns expire after {})", deployStatistics.getRequestId(), JavaUtils.durationFromMillis(timeSinceLastFinish), JavaUtils.durationFromMillis(cooldownExpiresMillis));
    }
    
    return hasCooldownExpired;
  }
    
}
