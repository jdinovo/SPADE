package org.example.attacks;

import org.eclipse.mosaic.lib.math.RandomNumberGenerator;
import org.example.RsuSpatApp;
import org.eclipse.mosaic.lib.util.scheduling.Event;

import java.util.Random;
import java.util.function.Consumer;
import java.util.Set;
import java.util.HashSet;

public abstract class RsuAttackApp extends RsuSpatApp {

    // Interval between attack scheduling (uses SPAT send interval by default)
    public static final long ATTACK_INTERVAL = SPAT_SEND_INTERVAL;
    // Use block-randomized approach to guarantee exact 20% attacks per block
    public static final double ATTACK_PROBABILITY = 0.2;
    public static final int BLOCK_SIZE = (int) Math.round(1.0 / ATTACK_PROBABILITY); // 5

    /**
     * Optional inclusive attack window, expressed in scheduler ticks.
     * Return a negative value from either method to retain block-randomized scheduling.
     */
    protected long getAttackStartTick() {
        return -1;
    }

    protected long getAttackEndTick() {
        return -1;
    }

    private Scheduler scheduler;

    // Flag indicating an attack is in progress (set on the attack tick and cleared when control returns to benign)
    private volatile boolean attackInProgress = false;
    // events to suppress while attack is in progress
    private final Set<String> suppressedDuringAttack = new HashSet<>(Set.of("SPAT", "MAP"));

    @Override
    public void onStartup() {
        super.onStartup();
        // disable the RSU's auto-scheduler from scheduling SPAT/MAP events; RsuAttackApp controls schedule
        // by overriding isAutoSchedulingEnabled() we prevent RsuSpatApp from re-adding events.
        // initialize and start scheduler
        scheduler = new Scheduler(this, ATTACK_INTERVAL, BLOCK_SIZE,
                getAttackStartTick(), getAttackEndTick(), this::onScheduledTick, getRandom());
        scheduler.start();
    }

    @Override
    protected boolean isAutoSchedulingEnabled() {
        return false;
    }

    @Override
    public void processEvent(Event event) {
        Object resource = event.getResource();

        // If scheduler posted this event, forward to it
        if (resource instanceof Scheduler s && s == scheduler) {
            scheduler.handleEvent(event);
            return;
        }

        // If an attack is in progress, suppress delegated events listed in suppressedDuringAttack
        if (attackInProgress && resource instanceof String id && suppressedDuringAttack.contains(id)) {
            getLog().debug("Suppressing event '{}' because an attack is in progress", id);
            return; // drop the event while attack is in progress
        }

        // Delegate other events to parent
        super.processEvent(event);
    }

    @Override
    protected void broadcastMapMessage() {
        if (attackInProgress) {
            getLog().debug("Skipping MAP broadcast because attack is in progress");
            return;
        }
        super.broadcastMapMessage();
    }

    @Override
    protected void broadcastSpatMessage() {
        if (attackInProgress) {
            getLog().debug("Skipping SPAT broadcast because attack is in progress");
            return;
        }
        super.broadcastSpatMessage();
    }

    // Called by scheduler on every scheduled tick. Default behavior: perform attack or benign broadcast.
    protected void onScheduledTick(boolean isAttack) {
        long now = getOs().getSimulationTime();
        if (isAttack) {
            getLog().info("Scheduler triggered attack tick at {}", now);
            // mark that an attack has started; attackEnd() will be called on the next benign tick
            attackInProgress = true;
            attack();
        } else {
            // if we were in an attack previously, end it now before doing benign behavior
            if (attackInProgress) {
                getLog().info("Scheduler transitioning from attack to benign at {}", now);
                attackEnd();
                attackInProgress = false;
            }

            getLog().debug("Scheduler triggered benign tick at {}", now);
            super.broadcastMapMessage();
            super.broadcastSpatMessage();
        }
    }

    protected void stopAttackSchedule() {
        if (scheduler != null) scheduler.stop();
    }

    protected void startAttackSchedule() {
        if (scheduler == null) {
            scheduler = new Scheduler(this, ATTACK_INTERVAL, BLOCK_SIZE,
                    getAttackStartTick(), getAttackEndTick(), this::onScheduledTick, getRandom());
        }
        scheduler.start();
    }

    // Implemented by subclasses to perform the actual attack actions
    abstract void attack();

    // Called when an attack tick finishes and control returns to benign behavior
    protected abstract void attackEnd();

    // Static nested scheduler class so other apps in the same package can reuse it without a separate file.
    public static class Scheduler {
        private final org.eclipse.mosaic.fed.application.app.AbstractApplication<?> owner;
        private final long intervalNs;
        private final int blockSize;
        private final long attackStartTick;
        private final long attackEndTick;
        private final Consumer<Boolean> tickHandler;
        private final RandomNumberGenerator random;

        private long tickNumber = 0;
        private int currentTickInBlock = 0;
        private int attackIndexInBlock = 0;
        private Event scheduledEvent;
        private boolean running = false;

        public Scheduler(org.eclipse.mosaic.fed.application.app.AbstractApplication<?> owner,
                         long intervalNs,
                         int blockSize,
                         long attackStartTick,
                         long attackEndTick,
                         Consumer<Boolean> tickHandler,
                         RandomNumberGenerator random) {
            this.owner = owner;
            this.intervalNs = intervalNs;
            this.blockSize = Math.max(1, blockSize);
            if ((attackStartTick < 0) != (attackEndTick < 0)
                    || attackStartTick >= 0 && attackEndTick < attackStartTick) {
                throw new IllegalArgumentException("Attack start/end ticks must both be set and start <= end");
            }
            this.attackStartTick = attackStartTick;
            this.attackEndTick = attackEndTick;
            this.tickHandler = tickHandler;
            this.random = random;
        }

        public void start() {
            if (running) return;
            tickNumber = 0;
            currentTickInBlock = 0;
            attackIndexInBlock = random.nextInt(blockSize);
            long scheduledTime = owner.getOs().getSimulationTime() + intervalNs;
            scheduledEvent = new Event(scheduledTime, owner, this);
            owner.getOs().getEventManager().addEvent(scheduledEvent);
            running = true;
            owner.getLog().info("Scheduler started: interval={}ns blockSize={} attackIndex={}", intervalNs, blockSize, attackIndexInBlock);
        }

        public void stop() {
            if (!running) return;
            try {
//                owner.getOs().getEventManager().removeEvent(scheduledEvent);
            } catch (Throwable t) {
                // removeEvent may not be present; fall through and rely on running flag
            }
            scheduledEvent = null;
            running = false;
            owner.getLog().info("Scheduler stopped");
        }

        public void handleEvent(Event event) {
            if (!running) return;
            long now = owner.getOs().getSimulationTime();
            boolean isAttack = attackStartTick >= 0
                    ? tickNumber >= attackStartTick && tickNumber <= attackEndTick
                    : currentTickInBlock == attackIndexInBlock;

            // Debug logging to diagnose unexpected attack frequency
            owner.getLog().debug("Scheduler tick: now={} owner={} tick={} blockTick={} attackIndex={} isAttack={}",
                    now, owner.getOs().getId(), tickNumber, currentTickInBlock, attackIndexInBlock, isAttack);

            try {
                tickHandler.accept(isAttack);
            } catch (Exception e) {
                owner.getLog().error("Error in scheduler tick handler", e);
            }

            currentTickInBlock++;
            tickNumber++;
            if (currentTickInBlock >= blockSize) {
                currentTickInBlock = 0;
                attackIndexInBlock = random.nextInt(blockSize);
                owner.getLog().info("New attack index for next block: {}", attackIndexInBlock);
            }

            // schedule next at the exact interval
            scheduledEvent = new Event(now + intervalNs, owner, this);
            owner.getOs().getEventManager().addEvent(scheduledEvent);
        }
    }
}
