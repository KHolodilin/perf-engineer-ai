package com.kholodilin.perfengineer.runprofile.application;

import java.util.concurrent.locks.ReentrantLock;

public final class AdmissionGate {

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private int held;

    public AdmissionGate(int runningSlots, int queueCapacity) {
        if (runningSlots != 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Admission requires one worker and a positive queue");
        }
        this.capacity = runningSlots + queueCapacity;
    }

    public boolean tryAcquire() {
        lock.lock();
        try {
            if (held >= capacity) {
                return false;
            }
            held++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void release() {
        lock.lock();
        try {
            if (held > 0) {
                held--;
            }
        } finally {
            lock.unlock();
        }
    }

    public int held() {
        lock.lock();
        try {
            return held;
        } finally {
            lock.unlock();
        }
    }
}
