package org.fable.task;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TaskCancellationManager {

    private static final class WorkerEntry {
        final Thread thread;
        final AtomicBoolean interruptible = new AtomicBoolean(false);

        WorkerEntry(Thread thread) {
            this.thread = thread;
        }
    }

    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, WorkerEntry> activeWorkers = new ConcurrentHashMap<>();

    public void registerWorker(String taskId, Thread workerThread) {
        if (taskId != null && workerThread != null) {
            activeWorkers.put(taskId, new WorkerEntry(workerThread));
        }
    }

    public void setInterruptible(String taskId, boolean interruptible) {
        if (taskId == null) {
            return;
        }
        WorkerEntry entry = activeWorkers.get(taskId);
        if (entry != null) {
            entry.interruptible.set(interruptible);
        }
    }

    public void unregisterWorker(String taskId) {
        if (taskId != null) {
            activeWorkers.remove(taskId);
        }
    }

    public void cancelTask(String taskId) {
        cancelledTasks.add(taskId);
        WorkerEntry entry = activeWorkers.get(taskId);
        if (entry != null && entry.interruptible.get()) {
            entry.thread.interrupt();
        }
    }

    public boolean isTaskCancelled(String taskId) {
        return cancelledTasks.contains(taskId);
    }

    public void clearCancellation(String taskId) {
        cancelledTasks.remove(taskId);
        activeWorkers.remove(taskId);
    }
}
