package org.hellojavaer.ddal.sequence;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 
 * this class is created based on java.util.concurrent.LinkedBlockingQueue, and is just for inner use.
 * 
 * @author <a href="mailto:hellojavaer@gmail.com">Kaiming Zou</a>,created on 06/025/2017.
 */
class SummedBlockingQueue {

    static class Node {

        InnerIdRange item;
        Node         next;

        Node(InnerIdRange x) {
            item = x;
        }
    }

    private final AtomicInteger countForCapacity = new AtomicInteger(0);
    private transient Node      head;
    private transient Node      last;
    private final ReentrantLock takeLock         = new ReentrantLock();
    private final Condition     notEmpty         = takeLock.newCondition();
    private final ReentrantLock putLock          = new ReentrantLock();
    private final Condition     notFull          = putLock.newCondition();

    private final long          sum;
    private final AtomicLong    countForSum      = new AtomicLong(0);

    public SummedBlockingQueue(long sum) {
        this.sum = sum;
        last = head = new Node(null);
    }

    static class InnerIdRange {

        private final long       beginValue;
        private final long       endValue;
        private final AtomicLong counter;

        public InnerIdRange(long beginValue, long endValue) {
            this.beginValue = beginValue;
            this.endValue = endValue;
            this.counter = new AtomicLong(beginValue);
        }

        public long getBeginValue() {
            return beginValue;
        }

        public long getEndValue() {
            return endValue;
        }

        public AtomicLong getCounter() {
            return counter;
        }
    }

    public void put(IdRange idRange) throws InterruptedException {
        if (idRange.getEndValue() < idRange.getBeginValue()) {
            throw new IllegalArgumentException("end value must be greater than or equal to begin value");
        }
        Node node = new Node(new InnerIdRange(idRange.getBeginValue(), idRange.getEndValue()));
        final ReentrantLock putLock = this.putLock;
        final AtomicInteger count = this.countForCapacity;
        putLock.lockInterruptibly();
        long c = -1;
        try {
            while (countForSum.get() >= sum) {
                notFull.await();
            }
            enqueue(node);
            c = count.incrementAndGet();
            long s = countForSum.addAndGet(idRange.getEndValue() - idRange.getBeginValue() + 1);
            if (s < sum) {
                notFull.signal();
            }
        } finally {
            putLock.unlock();
        }
        if (c == 1) signalNotEmpty();
    }

    private ThreadLocal<InnerIdRange> threadLocal = new ThreadLocal();

    public long get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        InnerIdRange idRange = threadLocal.get();
        if (idRange != null) {
            long id = idRange.getCounter().getAndIncrement();
            if (id <= idRange.getEndValue()) {
                long c = countForSum.decrementAndGet();
                if (c == sum - 1) {
                    signalNotFull();
                }
                if (id == idRange.getEndValue()) {
                    remove(idRange);
                    threadLocal.set(null);
                }
                return id;
            } else {
                remove(idRange);
                threadLocal.set(null);
                return recursiveGetFromQueue(timeout, unit);
            }
        } else {
            return recursiveGetFromQueue(timeout, unit);
        }
    }

    private long recursiveGetFromQueue(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
        long nanoTimeout = unit.toNanos(timeout);
        while (true) {
            long now = System.nanoTime();
            InnerIdRange idRange = get(nanoTimeout);
            if (idRange == null) {
                throw new TimeoutException(unit.toMillis(timeout) + " ms");
            } else {
                long id = idRange.getCounter().getAndIncrement();
                if (id <= idRange.getEndValue()) {
                    long c = countForSum.decrementAndGet();
                    if (c == sum - 1) {
                        signalNotFull();
                    }
                    if (id == idRange.getEndValue()) {
                        remove(idRange);
                    } else {
                        threadLocal.set(idRange);
                    }
                    return id;
                } else {
                    remove(idRange);
                    nanoTimeout -= System.nanoTime() - now;
                    if (nanoTimeout <= 0) {
                        throw new TimeoutException(unit.toMillis(timeout) + " ms");
                    }
                }
            }
        }
    }

    private InnerIdRange get(long nanoTimeout) throws InterruptedException {
        final AtomicInteger count = this.countForCapacity;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();
        InnerIdRange x = null;
        try {
            while (count.get() == 0) {
                long now = System.nanoTime();
                if (nanoTimeout <= 0) {
                    return null;
                }
                if (notEmpty.awaitNanos(nanoTimeout) <= 0) {
                    return null;
                }
                nanoTimeout -= System.nanoTime() - now;
            }
            Node first = head.next;
            if (first == null) x = null;
            else x = first.item;

            if (count.get() > 0) {
                notEmpty.signal();
            }
        } finally {
            takeLock.unlock();
        }
        return x;
    }

    public boolean remove(Object o) {
        if (o == null) return false;
        fullyLock();
        try {
            for (Node trail = head, p = trail.next; p != null; trail = p, p = p.next) {
                if (o.equals(p.item)) {
                    unlink(p, trail);
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    void fullyLock() {
        putLock.lock();
        takeLock.lock();
    }

    void fullyUnlock() {
        takeLock.unlock();
        putLock.unlock();
    }

    void unlink(Node p, Node trail) {
        p.item = null;
        trail.next = p.next;
        if (last == p) last = trail;
        countForCapacity.getAndDecrement();
    }

    private void signalNotEmpty() {
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
    }

    private void signalNotFull() {
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            notFull.signal();
        } finally {
            putLock.unlock();
        }
    }

    private void enqueue(Node node) {
        last = last.next = node;
    }

    public long remainingSum() {
        return sum - countForSum.get();
    }
}
