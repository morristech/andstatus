/* 
 * Copyright (c) 2011-2014 yvolk (Yuri Volkov), http://yurivolkov.com
 * Copyright (C) 2008 Torgny Bjers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andstatus.app.service;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

import org.andstatus.app.IntentExtra;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.context.MyPreferences;
import org.andstatus.app.util.MyLog;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.os.IBinder;
import android.os.PowerManager;

import net.jcip.annotations.GuardedBy;

/**
 * This is an application service that serves as a connection between this Android Device
 * and Microblogging system. Other applications can interact with it via IPC.
 */
public class MyService extends Service {
    private static final String TAG = MyService.class.getSimpleName();
    private static final String COMMANDS_QUEUE_FILENAME = TAG + "-commands-queue";
    private static final String RETRY_QUEUE_FILENAME = TAG + "-retry-queue";
    private static final String ERROR_QUEUE_FILENAME = TAG + "-error-queue";
    
    /**
     * Broadcast with this action is being sent by {@link MyService} to notify of its state.
     *  Actually {@link MyServiceManager} receives it.
     */
    public static final String ACTION_SERVICE_STATE = IntentExtra.MY_ACTION_PREFIX + "SERVICE_STATE";

    /**
     * This action is used in any intent sent to this service. Actual command to
     * perform by this service is in the {@link #EXTRA_MSGTYPE} extra of the
     * intent
     * 
     * @see CommandEnum
     */
    public static final String ACTION_GO = IntentExtra.MY_ACTION_PREFIX + "GO";

    private MyServiceState getServiceState() {
        MyServiceState state = MyServiceState.STOPPED; 
        synchronized (serviceStateLock) {
            if (mInitialized) {
                if (mIsStopping) {
                    state = MyServiceState.STOPPING;
                } else {
                    state = MyServiceState.RUNNING;
                }
            }
        }
        return state;
    }

    private final Object serviceStateLock = new Object();
    private boolean isStopping() {
        synchronized (serviceStateLock) {
            return mIsStopping;
        }
    }
    @GuardedBy("serviceStateLock")
    private boolean dontStop = false;
    /**
     * We are going to finish this service. The flag is being checked by background threads
     */
    @GuardedBy("serviceStateLock")
    private boolean mIsStopping = false;
    /**
     * Flag to control the Service state persistence
     */
    @GuardedBy("serviceStateLock")
    private boolean mInitialized = false;
    @GuardedBy("serviceStateLock")
    private int lastProcessedStartId = 0;
    /**
     * For now let's have only ONE working thread 
     * (it seems there is some problem in parallel execution...)
     */
    @GuardedBy("serviceStateLock")
    private QueueExecutor executor = null;

    private final Object wakeLockLock = new Object();
    /**
     * The reference to the wake lock used to keep the CPU from stopping during
     * background operations.
     */
    @GuardedBy("wakeLockLock")
    private PowerManager.WakeLock wakeLock = null;

    final Queue<CommandData> mainCommandQueue = new PriorityBlockingQueue<CommandData>(100);
    final Queue<CommandData> retryCommandQueue = new PriorityBlockingQueue<CommandData>(100);
    final Queue<CommandData> errorCommandQueue = new LinkedBlockingQueue<CommandData>(200);

    /**
     * Time when shared preferences where changed as this knows it.
     */
    private volatile long preferencesChangeTime = 0;
    /**
     * Time when shared preferences where analyzed
     */
    private volatile long preferencesExamineTime = 0;

    void setContext(Context baseContext) {
        attachBaseContext(baseContext);
    }
    
    /**
     * After this the service state remains "STOPPED": we didn't initialize the instance yet!
     */
    @Override
    public void onCreate() {
        preferencesChangeTime = MyContextHolder.initialize(this, this);
        preferencesExamineTime = getMyServicePreferences().getLong(MyPreferences.KEY_PREFERENCES_EXAMINE_TIME, 0);
        MyLog.d(this, "Service created, preferencesChangeTime=" + preferencesChangeTime + ", examined=" + preferencesExamineTime);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MyLog.d(this, "onStartCommand: startid=" + startId);
        receiveCommand(intent, startId);
        return START_NOT_STICKY;
    }

    @GuardedBy("serviceStateLock")
    private BroadcastReceiver intentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent intent) {
            MyLog.v(this, "onReceive " + intent.toString());
            receiveCommand(intent, 0);
        }
    };
    
    private void receiveCommand(Intent intent, int startId) {
        CommandData commandData = CommandData.fromIntent(intent);
        switch (commandData.getCommand()) {
            case STOP_SERVICE:
                MyLog.v(this, "Command " + commandData.getCommand() + " received");
                stopDelayed(false);
                break;
            case BROADCAST_SERVICE_STATE:
                MyServiceBroadcaster.newInstance(MyContextHolder.get(), getServiceState())
                        .broadcast();
                break;
            case UNKNOWN:
                MyLog.v(this, "Command " + commandData.getCommand() + " ignored");
                break;
            default:
                receiveOtherCommand(commandData, startId);
                break;
        }
    }

    private void receiveOtherCommand(CommandData commandData, int startId) {
        if (setDontStop(startId)) {
            try {
                initialize();
                if (mainCommandQueue.isEmpty()) {
                    moveCommandsFromRetryToMainQueue();
                }
                addToMainQueue(commandData);
            } finally {
                synchronized(serviceStateLock) {
                    dontStop = false;
                }
            }
            decideIfStopTheService(false);
        } else {
            addToTheQueueWhileStopping(commandData);
        }
    }

    private boolean setDontStop(int startId) {
        boolean ok = false;
        synchronized(serviceStateLock) {
            if (!isStopping()) {
                ok = true;
                dontStop = true;
                if (startId != 0) {
                    lastProcessedStartId = startId;
                }
            }
        }
        return ok;
    }
    
    private void addToTheQueueWhileStopping(CommandData commandData) {
        boolean ok = false;
        synchronized (serviceStateLock) {
            if (mInitialized) {
                ok = addToMainQueue(commandData);
            }
        }
        if (!ok) {
            MyLog.e(this, "The Service is stopping, the command was lost: "
                    + commandData.getCommand());
        }
    }

    private boolean addToMainQueue(CommandData commandData) {
        if ( commandData.getCommand() == CommandEnum.EMPTY) {
            return true;
        } 
        if (!checkAndMarkTheCommandInTheQueue("mainQueue", mainCommandQueue, commandData)
                && !checkAndMarkTheCommandInTheQueue("retryQueue", retryCommandQueue, commandData)) {
            CommandData commandData2 = checkInErrorQueue(commandData);
            if (commandData2 == null) {
                return true;
            }
            MyLog.v(this, "Adding to Main queue " + commandData);
            if (!mainCommandQueue.offer(commandData)) {
                MyLog.e(this, "Couldn't add to the main queue, size=" + mainCommandQueue.size());
                return false;
            }
        }
        return true;
    }

    private final static long MAX_MS_IN_ERROR_QUEUE = 3 * 24 * 60 * 60 * 1000; 
    private CommandData checkInErrorQueue(CommandData commandData) {
        CommandData found = commandData;
        if (errorCommandQueue.contains(commandData)) {
            for (CommandData cd : errorCommandQueue) {
                if (cd.equals(commandData)) {
                    if (System.currentTimeMillis() - cd.getResult().getLastExecutedDate() > MIN_RETRY_PERIOD_MS) {
                        found = cd;
                        cd.getResult().resetRetries(commandData.getCommand());
                        errorCommandQueue.remove(cd);
                    } else {
                        found = null;
                        MyLog.v(this, "Found in Error queue: " + commandData);
                    }
                    break;
                } else {
                    if (System.currentTimeMillis() - cd.getResult().getLastExecutedDate() > MAX_MS_IN_ERROR_QUEUE) {
                        MyLog.d(this, "Removed old from Error queue: " + commandData);
                    }
                }
            }
        }
        return found;
    }

    private boolean checkAndMarkTheCommandInTheQueue(String queueName, Queue<CommandData> queue,
            CommandData commandData) {
        boolean found = false;
        if (queue.contains(commandData)) {
            MyLog.d(this, "Duplicated in " + queueName + ": " + commandData);
            // Reset retries counter on receiving duplicated command
            for (CommandData cd : queue) {
                if (cd.equals(commandData)) {
                    found = true;
                    cd.getResult().resetRetries(commandData.getCommand());
                    break;
                }
            }
        }
        return found;
    }

    private final static long MIN_RETRY_PERIOD_MS = 180000; 
    private void moveCommandsFromRetryToMainQueue() {
        Queue<CommandData> tempQueue = new PriorityBlockingQueue<CommandData>(retryCommandQueue.size()+1);
        while (!retryCommandQueue.isEmpty()) {
            CommandData commandData = retryCommandQueue.poll();
            boolean added = false;
            if (System.currentTimeMillis() - commandData.getResult().getLastExecutedDate() > MIN_RETRY_PERIOD_MS) {
                added = addToMainQueue(commandData);
            }
            if (!added) {
                if (!tempQueue.add(commandData)) {
                    MyLog.e(this, "Couldn't add to temp Queue, size=" + tempQueue.size()
                            + " command=" + commandData);
                    break;
                }
            }
        }
        while (!tempQueue.isEmpty()) {
            CommandData cd = tempQueue.poll();
            if (!retryCommandQueue.add(cd)) {
                MyLog.e(this, "Couldn't return to retry Queue, size=" + retryCommandQueue.size()
                        + " command=" + cd);
                break;
            }
        }
    }
    
    /**
     * Initialize and restore the state if it was not restored yet
     */
    void initialize() {

        // Check when preferences were changed
        long preferencesChangeTimeNew = MyContextHolder.initialize(this, this);
        long preferencesExamineTimeNew = java.lang.System.currentTimeMillis();

        synchronized (serviceStateLock) {
            if (!mInitialized) {
                restoreState();
                registerReceiver(intentReceiver, new IntentFilter(ACTION_GO));
                mInitialized = true;
                MyServiceBroadcaster.newInstance(MyContextHolder.get(), getServiceState()).broadcast();
            }
        }
        
        if (preferencesChangeTime != preferencesChangeTimeNew
                || preferencesExamineTime < preferencesChangeTimeNew) {
            // Preferences changed...
            
            if (preferencesChangeTimeNew > preferencesExamineTime) {
                MyLog.d(this, "Examine at=" + preferencesExamineTimeNew + " Preferences changed at=" + preferencesChangeTimeNew);
            } else if (preferencesChangeTimeNew > preferencesChangeTime) {
                MyLog.d(this, "Preferences changed at=" + preferencesChangeTimeNew);
            } else if (preferencesChangeTimeNew == preferencesChangeTime) {
                MyLog.d(this, "Preferences didn't change, still at=" + preferencesChangeTimeNew);
            } else {
                MyLog.e(this, "Preferences change time error, time=" + preferencesChangeTimeNew);
            }
            preferencesChangeTime = preferencesChangeTimeNew;
            preferencesExamineTime = preferencesExamineTimeNew;
            getMyServicePreferences().edit().putLong(MyPreferences.KEY_PREFERENCES_EXAMINE_TIME, preferencesExamineTime).commit();
        }
    }

    private void restoreState() {
        int count = 0;
        count += CommandData.loadQueue(this, mainCommandQueue, COMMANDS_QUEUE_FILENAME);
        count += CommandData.loadQueue(this, retryCommandQueue, RETRY_QUEUE_FILENAME);
        int countError = CommandData.loadQueue(this, errorCommandQueue, ERROR_QUEUE_FILENAME);
        MyLog.d(this, "State restored, " + (count > 0 ? Integer.toString(count) : "no ")
                + " msg in the Queues, "
                + (countError > 0 ? Integer.toString(countError) + " in Error queue" : "")
                );
    }

    /**
     * The idea is to have SharePreferences, that are being edited by
     * the service process only (to avoid problems of concurrent access.
     * @return Single instance of SharedPreferences, specific to the class
     */
    private SharedPreferences getMyServicePreferences() {
        return MyPreferences.getSharedPreferences(TAG);
    }

    private void decideIfStopTheService(boolean calledFromExecutor) {
        synchronized(serviceStateLock) {
            if (!mInitialized) {
                return;
            }
            if (dontStop) {
                MyLog.v(this, "decideIfStopTheService: dontStop flag");
                return;
            }
            boolean isStopping = isStopping();
            if (!isStopping) {
                isStopping = mainCommandQueue.isEmpty()
                        || !MyContextHolder.get().isReady();
                if (isStopping && !calledFromExecutor && executor!= null) {
                    isStopping = (executor.getStatus() != Status.RUNNING);
                }
            }
            if (this.mIsStopping != isStopping) {
                if (isStopping) {
                    MyLog.v(this, "Decided to stop; startId=" + lastProcessedStartId 
                            + "; " + (totalQueuesSize() == 0 ? "queue is empty"  : "queueSize=" + totalQueuesSize())
                            );
                } else {
                    MyLog.v(this, "Decided to continue; startId=" + lastProcessedStartId);
                }
                this.mIsStopping = isStopping;
            }
            if (isStopping || calledFromExecutor
                    || (executor != null && (executor.getStatus() != Status.RUNNING))) {
                if (executor != null) {
                    MyLog.v(this, "Deleting executor " + executor);
                    executor = null;
                }
            }
            if (isStopping) {
                stopDelayed(true);
            } else {
                acquireWakeLock();
                if (executor == null) {
                    executor = new QueueExecutor();
                    MyLog.v(this, "Adding new executor " + executor);
                    executor.execute();
                } else {
                    MyLog.v(this, "There is an Executor already " + executor);
                }
            }
        }
    }

    boolean isOnline() {
        if (isOnlineNotLogged()) {
            return true;
        } else {
            MyLog.v(this, "Internet Connection Not Present");
            return false;
        }
    }

    /**
     * Based on http://stackoverflow.com/questions/1560788/how-to-check-internet-access-on-android-inetaddress-never-timeouts
     */
    private boolean isOnlineNotLogged() {
        boolean is = false;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        if (networkInfo == null) {
            return false;
        }
        is = networkInfo.isAvailable() && networkInfo.isConnected();
        return is;
    }
    
    private void acquireWakeLock() {
        synchronized(wakeLockLock) {
            if (wakeLock == null) {
                MyLog.d(this, "Acquiring wakelock");
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
                wakeLock.acquire();
            }
        }
    }
    
    private int totalQueuesSize() {
        return retryCommandQueue.size() + mainCommandQueue.size();
    }
    
    @Override
    public void onDestroy() {
        MyLog.v(this, "onDestroy");
        stopDelayed(true);
        MyLog.d(this, "Service destroyed");
    }
    
    /**
     * Notify background processes that the service is stopping.
     * Stop if background processes has finished.
     * Persist everything that we'll need on next Service creation and free resources
     * @param boolean forceNow 
     */
    private void stopDelayed(boolean forceNow) {
        String method = "stopDelayed";
        synchronized (serviceStateLock) {
            if (mInitialized) {
                if (dontStop) {
                    MyLog.d(this, method + ": dontStop flag");
                    return;
                }
                mIsStopping = true;
            } else {
                mIsStopping = false;
                return;
            }
            boolean mayStop = executor == null || executor.getStatus() != Status.RUNNING;
            if (!mayStop) {
                if (forceNow) {
                    MyLog.d(this, method + ": Forced to stop now, cancelling Executor");
                    executor.cancel(true);
                } else {
                    MyLog.v(this, method + ": Cannot stop now, executor is working");
                    MyServiceBroadcaster.newInstance(MyContextHolder.get(), getServiceState()).broadcast();
                    return;
                }
            }
            if( mInitialized) {
                try {
                    unregisterReceiver(intentReceiver);
                    CommandsQueueNotifier.newInstance(MyContextHolder.get()).update(
                            mainCommandQueue.size(), retryCommandQueue.size());
                    saveState();
                    relealeWakeLock();
                    stopSelfResult(lastProcessedStartId);
                } finally {
                    mInitialized = false;
                    mIsStopping = false;
                    lastProcessedStartId = 0;
                    dontStop = false;
                }
            }
        }
        MyServiceBroadcaster.newInstance(MyContextHolder.get(), getServiceState())
                .setEvent(MyServiceEvent.ON_STOP).broadcast();
    }

    private void saveState() {
        int count = 0;
        count += CommandData.saveQueue(this, mainCommandQueue, COMMANDS_QUEUE_FILENAME);
        count += CommandData.saveQueue(this, retryCommandQueue, RETRY_QUEUE_FILENAME);
        int countError = CommandData.saveQueue(this, errorCommandQueue, ERROR_QUEUE_FILENAME);
        MyLog.d(this, "State saved, " + (count > 0 ? Integer.toString(count) : "no ")
                + " msg in the Queues, "
                + (countError > 0 ? Integer.toString(countError) + " in Error queue" : "")
                );
    }

    void clearQueues() {
        mainCommandQueue.clear();
        retryCommandQueue.clear();
        errorCommandQueue.clear();
    }
    
    private void relealeWakeLock() {
        synchronized(wakeLockLock) {
            if (wakeLock != null) {
                MyLog.d(this, "Releasing wakelock");
                wakeLock.release();
                wakeLock = null;
            }
        }
    }
    
    /**
     * @return This intent will be received by MyService only if it's initialized
     * (and corresponding broadcast receiver registered)
     */
    public static Intent intentForThisInitialized() {
        return new Intent(MyService.ACTION_GO);
    }
    
    private class QueueExecutor extends AsyncTask<Void, Void, Boolean> implements CommandExecutorParent {
        
        @Override
        protected Boolean doInBackground(Void... arg0) {
            MyLog.d(this, "Started, " + mainCommandQueue.size() + " commands to process");
            do {
                if (isStopping()) {
                    break;
                }
                CommandData commandData = mainCommandQueue.poll();
                if (commandData == null) {
                    break;
                }
                MyServiceBroadcaster.newInstance(MyContextHolder.get(), getServiceState())
                .setCommandData(commandData).setEvent(MyServiceEvent.BEFORE_EXECUTING_COMMAND).broadcast();
                if ( !commandData.getCommand().isOnlineOnly() || isOnline()) {
                    CommandExecutorStrategy.executeCommand(commandData, this);
                }
                if (commandData.getResult().shouldWeRetry()) {
                    addToRetryQueue(commandData);        
                } else if (commandData.getResult().hasError()) {
                    addToErrorQueue(commandData);
                }
                MyServiceBroadcaster.newInstance(MyContextHolder.get(), getServiceState())
                .setCommandData(commandData).setEvent(MyServiceEvent.AFTER_EXECUTING_COMMAND).broadcast();
            } while (true);
            MyLog.d(this, "Ended, " + totalQueuesSize() + " commands left");
            return true;
        }

        private void addToRetryQueue(CommandData commandData) {
            if (!retryCommandQueue.contains(commandData) 
                    && !retryCommandQueue.offer(commandData)) {
                MyLog.e(this, "mRetryQueue is full?");
            }
        }

        private void addToErrorQueue(CommandData commandData) {
            if (!errorCommandQueue.contains(commandData)) {
                if (!errorCommandQueue.offer(commandData)) {
                    CommandData commandData2 = errorCommandQueue.poll();
                    MyLog.d(this, "Removed from overloaded Error queue: " + commandData2);
                }
                if (!errorCommandQueue.offer(commandData)) {
                    MyLog.e(this, "Error Queue is full?");
                }
            }
        }
        
        /**
         * This is in the UI thread, so we can mess with the UI
         */
        @Override
        protected void onPostExecute(Boolean notUsed) {
            MyLog.v(this, "onPostExecute");
            decideIfStopTheService(true);
        }

        @Override
        protected void onCancelled(Boolean result) {
            MyLog.v(this, "onCancelled, result=" + result);
            decideIfStopTheService(true);
        }

        @Override
        public boolean isStopping() {
            return MyService.this.isStopping();
        }

        @Override
        public String toString() {
            return "QueueExecutor:{" + "status:" + getStatus()
                    + (isCancelled() ? ", cancelled" : "")
                    + (isStopping() ? ", stopping" : "")
                    + "}";
        }
        
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
