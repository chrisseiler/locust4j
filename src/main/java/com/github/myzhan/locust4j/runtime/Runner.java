package com.github.myzhan.locust4j.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;

import java.util.HashMap;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.myzhan.locust4j.AbstractTask;
import com.github.myzhan.locust4j.Locust;
import com.github.myzhan.locust4j.message.Message;
import com.github.myzhan.locust4j.message.MessageListener;
import com.github.myzhan.locust4j.rpc.Client;
import com.github.myzhan.locust4j.stats.Stats;
import com.github.myzhan.locust4j.utils.Utils;
import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Runner} is a state machine that tells to the master, runs all tasks, collects test results
 * and reports to the master.
 *
 * @author myzhan
 */
public class Runner {

    private static final Logger logger = LoggerFactory.getLogger(Runner.class);

    /**
     * Every locust4j instance registers a unique nodeID to the master when it makes a connection.
     * NodeID is kept by Runner.
     */
    protected String nodeID;

    /**
     * Number of clients required by the master, locust4j use threads to simulate clients.
     */
    protected int numClients = 0;

    /**
     * Current state of runner.
     */
    private RunnerState state;

    /**
     * Task instances submitted by user.
     */
    private List<AbstractTask> tasks;


    /**
     * Stores reference's to each task's runnable. Allows us to scale down the number
     * of "users" running that task.
     */
    private HashMap<String, List<WeakReference<Future<?>>>> futures = new HashMap<>();

    /**
     * RPC Client.
     */
    private Client rpcClient;

    /**
     * We save user_class_count in spawn message and send it back to master without modification.
     */
    protected Map<String, Integer> userClassesCountFromMaster;

    /**
     * Remote params sent from the master, which is set before spawning begins.
     */
    private final Map<String, Object> remoteParams = new ConcurrentHashMap<>();

    /**
     * Thread pool used by runner, it will be re-created when runner starts spawning.
     */
    private ThreadPoolExecutor taskExecutor;

    /**
     * Thread pool used by runner to receive and send message
     */
    private ExecutorService executor;

    /**
     * Stats collect successes and failures.
     */
    private Stats stats;

    /**
     * Allows for custom handlers for messages received from master
     */
    private final Map<String, List<MessageListener>> messageListeners = new HashMap<>();

    /**
     * Use this for naming threads in the thread pool.
     */
    private final AtomicInteger threadNumber = new AtomicInteger();

    /**
     * Disable heartbeat request.
     */
    private final AtomicBoolean heartbeatStopped = new AtomicBoolean(false);

    protected void setHeartbeatStopped(boolean value) {
        heartbeatStopped.set(value);
    }

    protected boolean isHeartbeatStopped() {
        return heartbeatStopped.get();
    }

    public Runner() {
        this.nodeID = Utils.getNodeID();
    }

    public RunnerState getState() {
        return this.state;
    }

    public String getNodeID() {
        return this.nodeID;
    }

    public void setRPCClient(Client client) {
        this.rpcClient = client;
    }

    public Map<String, Object> getRemoteParams() {
        return this.remoteParams;
    }

    public void setStats(Stats stats) {
        this.stats = stats;
    }

    public void setTasks(List<AbstractTask> tasks) {
        this.tasks = tasks;
    }

    protected void setTaskExecutor(ThreadPoolExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    private void spawnWorkers(int spawnCount) {
        logger.debug("Required {} clients. Currently running {}.", spawnCount, this.taskExecutor.getActiveCount());

        float weightSum = 0;
        for (AbstractTask task : this.tasks) {
            weightSum += task.getWeight();
        }

        this.numClients = 0;

        for (AbstractTask task : this.tasks) {
            int amount;
            if (0 == weightSum) {
                amount = spawnCount / this.tasks.size();
            } else {
                float percent = task.getWeight() / weightSum;
                amount = Math.round(spawnCount * percent);
            }

            List<WeakReference<Future<?>>> runningTasks = futures.get(task.getName());
            if ( runningTasks == null ) {
                runningTasks = new ArrayList<WeakReference<Future<?>>>();
            }

            // Clean up any tasks that may have completed
            Iterator<WeakReference<Future<?>>> itr = runningTasks.iterator();
            while (itr.hasNext()) {
                Future<?> future = itr.next().get();
                if (future == null || future.isDone()) {
                    itr.remove();
                }
            }

            while (runningTasks.size() < amount) {
                runningTasks.add(new WeakReference(this.taskExecutor.submit(task)));
                logger.debug("Adding thread to task, which name is {}", task.getName());
            }

            while (runningTasks.size() > amount) {
                Future<?> future = runningTasks.remove(0).get();
                if (future != null) {
                    future.cancel(true);
                }
                logger.debug("Removing thread from task, which name is {}", task.getName());
            }

            futures.put(task.getName(), runningTasks);

            logger.debug("Allocated {} threads to task, which name is {}", amount, task.getName());

            this.numClients += runningTasks.size();
        }
    }

    protected void startSpawning(int spawnCount) {
        Stats.getInstance().wakeMeUp();
        if(this.taskExecutor == null) {
            this.setTaskExecutor(new ThreadPoolExecutor(spawnCount, spawnCount, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>(),
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread thread = new Thread(r);
                            thread.setName("locust4j-worker#" + threadNumber.getAndIncrement());
                            return thread;
                        }
                    }));
        } else if (spawnCount > this.taskExecutor.getMaximumPoolSize()){
            this.taskExecutor.setMaximumPoolSize(spawnCount);
            this.taskExecutor.setCorePoolSize(spawnCount);
        } else {
            this.taskExecutor.setCorePoolSize(spawnCount);
            this.taskExecutor.setMaximumPoolSize(spawnCount);
        }

        this.spawnWorkers(spawnCount);
    }

    protected void spawnComplete() {
        Map<String, Object> data = new HashMap<>(1);
        data.put("count", this.numClients);
        data.put("user_classes_count", this.userClassesCountFromMaster);
        try {
            this.rpcClient.send((new Message("spawning_complete", data, -1, this.nodeID)));
        } catch (IOException ex) {
            logger.error("Error while sending a message about the completed spawn", ex);
        }
    }

    public void quit() {
        try {
            this.rpcClient.send(new Message("quit", null, -1, this.nodeID));
            this.executor.shutdownNow();
        } catch (IOException ex) {
            logger.error("Error while sending a message about quiting", ex);
        }
    }

    private void shutdownThreadPool() {
        this.taskExecutor.shutdownNow();
        try {
            this.taskExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            logger.error("Error while waiting for termination", ex);
        }
        this.taskExecutor = null;
    }

    protected void stop() {
        this.shutdownThreadPool();
    }

    private boolean spawnMessageIsValid(Message message) {
        Map<String, Object> data = message.getData();
        if (!data.containsKey("user_classes_count")) {
            logger.debug("Invalid spawn message without user_classes_count, you may use a newer but incompatible version of locust.");
            return false;
        }
        return true;
    }

    private int sumUsersAmount(Message message) {
        Map<String, Integer> userClassesCount = (Map<String, Integer>)message.getData().get("user_classes_count");
        int amount = 0;
        for (Map.Entry<String, Integer> entry: userClassesCount.entrySet()) {
            amount = amount + entry.getValue();
        }
        this.userClassesCountFromMaster = userClassesCount;
        return amount;
    }

    private void onSpawnMessage(Message message) {
        Map<String, Object> data = message.getData();
        int numUsers = sumUsersAmount(message);

        try {
            this.rpcClient.send(new Message("spawning", null, -1, this.nodeID));
        } catch (IOException ex) {
            logger.error("Error while sending a message about spawning", ex);
        }

        this.remoteParams.put("user_classes_count", this.userClassesCountFromMaster);
        if (data.get("host") != null) {
            this.remoteParams.put("host", data.get("host").toString());
        }

        this.startSpawning(numUsers);
        this.spawnComplete();
    }

    public void addMessageListener(String messageType, MessageListener messageListener) {
        synchronized(messageListeners) {
            List<MessageListener> listeners = messageListeners.get(messageType);
            if(listeners == null) {
                listeners = new ArrayList<>();
            }
            listeners.add(messageListener);
            messageListeners.put(messageType, listeners);
        }
    }

    private void onMessage(Message message) {
        String type = message.getType();

        boolean hasListeners;
        synchronized(messageListeners) {
            hasListeners = messageListeners.containsKey(type);
            if(hasListeners) {
                for (MessageListener listener : messageListeners.get(type)) {
                    listener.accept(message);
                }
            }
        }

        if (!"spawn".equals(type) && !"stop".equals(type) && !"quit".equals(type)) {
            if(!hasListeners) {
                logger.error("Got {} message from master, which is not supported, please report an issue to locust4j.", type);
            }
            return;
        }

        if ("quit".equals(type)) {
            logger.debug("Got quit message from master, shutting down...");
            System.exit(0);
        }

        if (this.state == RunnerState.Ready) {
            if ("spawn".equals(type) && spawnMessageIsValid(message)) {
                this.state = RunnerState.Spawning;
                this.onSpawnMessage(message);

                if (null != Locust.getInstance().getRateLimiter()) {
                    Locust.getInstance().getRateLimiter().start();
                }

                this.state = RunnerState.Running;
            }
        } else if (this.state == RunnerState.Spawning || this.state == RunnerState.Running) {
            if ("spawn".equals(type) && spawnMessageIsValid(message)) {
                this.state = RunnerState.Spawning;
                this.onSpawnMessage(message);
                this.state = RunnerState.Running;
            } else if ("stop".equals(type)) {
                this.stop();

                if (null != Locust.getInstance().getRateLimiter()) {
                    Locust.getInstance().getRateLimiter().stop();
                }

                this.state = RunnerState.Stopped;
                logger.debug("Recv stop message from master, all the workers are stopped");
                try {
                    this.rpcClient.send(new Message("client_stopped", null, -1, this.nodeID));
                    this.rpcClient.send(new Message("client_ready", null, -1, this.nodeID));
                    this.state = RunnerState.Ready;
                } catch (IOException ex) {
                    logger.error("Error while switching from the state stopped to ready", ex);
                }
            }
        } else if (this.state == RunnerState.Stopped) {
            if ("spawn".equals(type) && spawnMessageIsValid(message)) {
                this.state = RunnerState.Spawning;
                this.onSpawnMessage(message);

                if (null != Locust.getInstance().getRateLimiter()) {
                    Locust.getInstance().getRateLimiter().start();
                }

                this.state = RunnerState.Running;
            }
        }
    }

    public void getReady() {
        this.executor = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r);
            }
        });
        this.state = RunnerState.Ready;
        try {
            this.rpcClient.send(new Message("client_ready", null, -1, this.nodeID));
        } catch (IOException ex) {
            logger.error("Error while sending a message that the system is ready", ex);
        }

        this.executor.submit(new Receiver(this));
        this.executor.submit(new Sender(this));
        this.executor.submit(new Heartbeat(this));
    }

    private static class Receiver implements Runnable {
        private final Runner runner;

        private Receiver(Runner runner) {
            this.runner = runner;
        }

        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            Thread.currentThread().setName(name + "receive-from-client");
            while (true) {
                try {
                    Message message = runner.rpcClient.recv();
                    runner.onMessage(message);
                } catch (Exception ex) {
                    logger.error("Error while receiving a message", ex);
                }
            }
        }
    }

    private static class Sender implements Runnable {
        private final Runner runner;

        private Sender(Runner runner) {
            this.runner = runner;
        }

        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            Thread.currentThread().setName(name + "send-to-client");
            while (true) {
                try {
                    Map<String, Object> data = runner.stats.getMessageToRunnerQueue().take();
                    if (data.containsKey("current_cpu_usage")) {
                        // It's heartbeat message, moved to here to avoid race condition of zmq socket.
                        runner.rpcClient.send(new Message("heartbeat", data, -1, runner.nodeID));
                        continue;
                    }
                    if (runner.state == RunnerState.Ready || runner.state == RunnerState.Stopped) {
                        continue;
                    }
                    data.put("user_count", runner.numClients);
                    data.put("user_classes_count", runner.userClassesCountFromMaster);
                    runner.rpcClient.send(new Message("stats", data, -1, runner.nodeID));
                } catch (InterruptedException ex) {
                    return;
                } catch (Exception ex) {
                    logger.error("Error in running the sender", ex);
                }
            }
        }
    }

    private static class Heartbeat implements Runnable {
        private static final int HEARTBEAT_INTERVAL = 1000;
        private final Runner runner;

        private final OperatingSystemMXBean osBean = getOsBean();

        private Heartbeat(Runner runner) {
            this.runner = runner;
        }

        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            Thread.currentThread().setName(name + "heartbeat");
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                    if (runner.isHeartbeatStopped()) {
                        continue;
                    }
                    Map<String, Object> data = new HashMap<>(2);
                    data.put("state", runner.state.name().toLowerCase());
                    data.put("current_cpu_usage", getCpuUsage());
                    boolean success = runner.stats.getMessageToRunnerQueue().offer(data);
                    if (!success) {
                        logger.error("Failed to insert heartbeat message to the queue");
                    }
                } catch (InterruptedException ex) {
                    return;
                } catch (Exception ex) {
                    logger.error("Error in running the heartbeat", ex);
                }
            }
        }

        private double getCpuUsage() {
            return osBean.getSystemCpuLoad() * 100;
        }

        private OperatingSystemMXBean getOsBean() {
            return (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        }
    }

}
