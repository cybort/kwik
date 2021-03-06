/*
 * Copyright © 2019, 2020 Peter Doornbosch
 *
 * This file is part of Kwik, a QUIC client Java library
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.quic.recovery;

import net.luminis.quic.*;
import net.luminis.quic.concurrent.DaemonThreadFactory;
import net.luminis.quic.frame.AckFrame;
import net.luminis.quic.frame.Padding;
import net.luminis.quic.frame.PingFrame;
import net.luminis.quic.frame.QuicFrame;
import net.luminis.quic.log.Logger;
import net.luminis.quic.packet.QuicPacket;
import net.luminis.quic.recovery.LossDetector;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RecoveryManager implements HandshakeStateListener {

    private final RttEstimator rttEstimater;
    private final LossDetector[] lossDetectors = new LossDetector[PnSpace.values().length];
    private final ProbeSender sender;
    private final Logger log;
    private final ScheduledExecutorService scheduler;
    private int receiverMaxAckDelay;
    private volatile ScheduledFuture<?> lossDetectionTimer;
    private volatile int ptoCount;
    private volatile Instant timerExpiration;
    private volatile HandshakeState handshakeState = HandshakeState.Initial;
    private volatile boolean firstHandshakeSent = false;
    private volatile boolean hasBeenReset = false;

    public RecoveryManager(RttEstimator rttEstimater, CongestionController congestionController, ProbeSender sender, Logger logger) {
        this.rttEstimater = rttEstimater;
        for (PnSpace pnSpace: PnSpace.values()) {
            lossDetectors[pnSpace.ordinal()] = new LossDetector(this, rttEstimater, congestionController);
        }
        this.sender = sender;
        log = logger;

        scheduler = Executors.newScheduledThreadPool(1, new DaemonThreadFactory("loss-detection"));
        lossDetectionTimer = new NullScheduledFuture();
    }

    void setLossDetectionTimer() {
        PnSpaceTime earliestLossTime = getEarliestLossTime(LossDetector::getLossTime);
        Instant lossTime = earliestLossTime != null? earliestLossTime.lossTime: null;
        if (lossTime != null) {
            lossDetectionTimer.cancel(false);
            int timeout = (int) Duration.between(Instant.now(), lossTime).toMillis();
            lossDetectionTimer = reschedule(() -> lossDetectionTimeout(), timeout);
        }
        else {
            boolean ackElicitingInFlight = ackElicitingInFlight();
            boolean peerAwaitingAddressValidation = peerAwaitingAddressValidation();
            if (ackElicitingInFlight || peerAwaitingAddressValidation) {
                // https://tools.ietf.org/html/draft-ietf-quic-recovery-25#section-5.2
                // "As with loss detection, the probe timeout is per packet number space."
                PnSpaceTime earliestLastAckElicitingSentTime = getEarliestLossTime(LossDetector::getLastAckElicitingSent);
                if (earliestLastAckElicitingSentTime == null) {
                    if (!ackElicitingInFlight) {
                        log.error("Missing last ack eliciting sent time, probably caused by peer awaiting address validation and initial recovery state being discarded");
                        // This can happen when Initial pn space is already discarded, but no ack-eliciting handshake packet has been sent, and peer is still awaiting address validation
                        // Hack: use "now" as start time; must ask experts what to do here
                        earliestLastAckElicitingSentTime = new PnSpaceTime(PnSpace.Handshake, Instant.now());
                    }
                    else {
                        throw new IllegalStateException("Missing last ack eliciting sent time");
                    }
                }

                // https://tools.ietf.org/html/draft-ietf-quic-recovery-25#section-5.2.1
                // "When the PTO is armed for Initial or Handshake packet number spaces, the max_ack_delay is 0"
                int maxAckDelay = earliestLastAckElicitingSentTime.pnSpace == PnSpace.App? receiverMaxAckDelay: 0;
                int ptoTimeout = rttEstimater.getSmoothedRtt() + 4 * rttEstimater.getRttVar() + maxAckDelay;
                ptoTimeout *= (int) (Math.pow(2, ptoCount));

                int timeout = (int) Duration.between(Instant.now(), earliestLastAckElicitingSentTime.lossTime.plusMillis(ptoTimeout)).toMillis();
                if (timeout < 1) {
                    timeout = 0;
                }
                log.recovery("reschedule loss detection timer over " + timeout + " millis, "
                        + "based on %s/" + earliestLastAckElicitingSentTime.pnSpace + ", because "
                        + (peerAwaitingAddressValidation ? "peerAwaitingAddressValidation ": "")
                        + (ackElicitingInFlight ? "ackElicitingInFlight ": "")
                        + "| RTT:" + rttEstimater.getSmoothedRtt() + "/" + rttEstimater.getRttVar(), earliestLastAckElicitingSentTime.lossTime);

                lossDetectionTimer.cancel(false);
                lossDetectionTimer = reschedule(() -> lossDetectionTimeout(), timeout);
            }
            else {
                log.recovery("cancelling loss detection timer (no loss time set, no ack eliciting in flight, peer not awaiting address validation)");
                unschedule();
            }
        }
    }

    private boolean peerAwaitingAddressValidation() {
        // https://tools.ietf.org/html/draft-ietf-quic-recovery-26#section-5.3
        // "That is, the client MUST set the probe timer if the client has not received an
        //   acknowledgement for one of its Handshake or 1-RTT packets."
        // See also https://github.com/quicwg/base-drafts/issues/3502
        return handshakeState.isNotConfirmed() && lossDetectors[PnSpace.Handshake.ordinal()].noAckedReceived() && lossDetectors[PnSpace.App.ordinal()].noAckedReceived();
    }

    private void lossDetectionTimeout() {
        // Because cancelling the ScheduledExecutor task quite often fails, double check whether the timer should expire.
        Instant expiration = timerExpiration;
        if (expiration == null) {
            // Timer was cancelled, but it still fired; ignore
            log.warn("Loss detection timeout: Timer was cancelled.");
            return;
        }
        else if (Instant.now().isBefore(expiration)) {
            // Old timer task was cancelled, but it still fired; just ignore.
            log.warn("Scheduled task running early: " + Duration.between(Instant.now(), expiration) + "(" + expiration + ")");
            // Apparently, sleep is less precise than time measurement; and adding an extra ms is necessary to avoid that after the sleep, it's still too early
            long remainingWaitTime = Duration.between(Instant.now(), expiration).toMillis() + 1;
            if (remainingWaitTime > 0) {  // Time goes on, so remaining time could have become negative in the mean time
                try {
                    Thread.sleep(remainingWaitTime);
                } catch (InterruptedException e) {}
            }
            expiration = timerExpiration;
            if (expiration == null) {
                log.warn("Delayed task: timer expiration is now null, cancelled");
                return;
            }
            else if (Instant.now().isBefore(expiration)) {
                log.warn("Delayed task is now still before timer expiration, probably rescheduled in the meantime; " + Duration.between(Instant.now(), timerExpiration) + "(" + timerExpiration + ")");
                return;
            }
            else {
                log.warn("Delayed task running now");
            }
        }
        else {
            log.recovery("%s loss detection timeout handler running", Instant.now());
        }

        PnSpaceTime earliestLossTime = getEarliestLossTime(LossDetector::getLossTime);
        Instant lossTime = earliestLossTime != null? earliestLossTime.lossTime: null;
        if (lossTime != null) {
            lossDetectors[earliestLossTime.pnSpace.ordinal()].detectLostPackets();
            setLossDetectionTimer();
        }
        else {
            sendProbe();
            // Calling setLossDetectionTimer here not necessary, because the event of sending the probe will trigger it anyway.
            // And if done here, time of last-ack-eliciting might not be set yet (because packets are sent async), leading to trouble.
        }
    }

    private void sendProbe() {
        PnSpaceTime earliestLastAckElicitingSentTime = getEarliestLossTime(LossDetector::getLastAckElicitingSent);

        if (earliestLastAckElicitingSentTime != null) {
            log.recovery(String.format("Sending probe %d, because no ack since %%s. Current RTT: %d/%d.", ptoCount, rttEstimater.getSmoothedRtt(), rttEstimater.getRttVar()), earliestLastAckElicitingSentTime.lossTime);
        }
        else {
            log.recovery(String.format("Sending probe %d. Current RTT: %d/%d.", ptoCount, rttEstimater.getSmoothedRtt(), rttEstimater.getRttVar()));
        }
        ptoCount++;

        int nrOfProbes = ptoCount > 1? 2: 1;

        if (handshakeState.hasNoHandshakeKeys()) {
            // https://tools.ietf.org/html/draft-ietf-quic-recovery-26#appendix-A.9
            // "SendOneAckElicitingPaddedInitialPacket"
            List<QuicPacket> unAckedInitialPackets = lossDetectors[PnSpace.Initial.ordinal()].unAcked();
            if (!unAckedInitialPackets.isEmpty()) {
                // Client role: there can only be one (unique) initial, as the client sends only one Initial packet.
                // All frames need to be resent, because Initial packet wil contain padding.
                log.recovery("(Probe is an initial retransmit)");
                repeatSend(nrOfProbes, () ->
                        sender.sendProbe(unAckedInitialPackets.get(0).getFrames(), EncryptionLevel.Initial));
            }
            else {
                // This can happen, when the probe is sent because of peer awaiting address validation
                log.recovery("(Probe is Initial ping, because there is no Initial data to retransmit)");
                repeatSend(nrOfProbes, () ->
                        sender.sendProbe(List.of(new PingFrame(), new Padding(2)), EncryptionLevel.Initial));
            }
        }
        else if (handshakeState.hasOnlyHandshakeKeys()) {
            // https://tools.ietf.org/html/draft-ietf-quic-recovery-26#section-5.3
            // "If Handshake keys are available to the client, it MUST send a Handshake packet"
            // https://tools.ietf.org/html/draft-ietf-quic-recovery-26#appendix-A.9
            // "SendOneAckElicitingHandshakePacket"

            // Client role: find ack eliciting handshake packet that is not acked and retransmit its contents.
            List<QuicFrame> framesToRetransmit = getFramesToRetransmit(PnSpace.Handshake);
            if (!framesToRetransmit.isEmpty()) {
                log.recovery("(Probe is a handshake retransmit)");
                repeatSend(nrOfProbes, () ->
                        sender.sendProbe(framesToRetransmit, EncryptionLevel.Handshake));
            }
            else {
                // https://tools.ietf.org/html/draft-ietf-quic-transport-27#section-8.1
                // "In particular, receipt of a packet protected with
                //   Handshake keys confirms that the client received the Initial packet
                //   from the server.  Once the server has successfully processed a
                //   Handshake packet from the client, it can consider the client address
                //   to have been validated."
                // Hence, no padding needed.
                log.recovery("(Probe is a handshake ping)");
                repeatSend(nrOfProbes, () ->
                        sender.sendProbe(List.of(new PingFrame(), new Padding(2)), EncryptionLevel.Handshake));
            }
        }
        else if (earliestLastAckElicitingSentTime != null) {
            // SendOneOrTwoAckElicitingPackets(pn_space)
            EncryptionLevel probeLevel = earliestLastAckElicitingSentTime.pnSpace.relatedEncryptionLevel();
            List<QuicFrame> framesToRetransmit = getFramesToRetransmit(earliestLastAckElicitingSentTime.pnSpace);
            if (!framesToRetransmit.isEmpty()) {
                log.recovery(("(Probe is retransmit on level " + probeLevel + ")"));
                repeatSend(nrOfProbes, () ->
                        sender.sendProbe(framesToRetransmit, probeLevel));
            }
            else {
                log.recovery(("(Probe is ping on level " + probeLevel + ")"));
                repeatSend(nrOfProbes, () ->
                        sender.sendProbe(List.of(new PingFrame(), new Padding(2)), probeLevel));
            }
        }
        else {
            log.recovery("(Sending probe withdrawn; no last ack eliciting packet)");
        }
    }

    List<QuicFrame> getFramesToRetransmit(PnSpace pnSpace) {
        List<QuicPacket> unAckedPackets = lossDetectors[pnSpace.ordinal()].unAcked();
        Optional<QuicPacket> ackEliciting = unAckedPackets.stream()
                .filter(p -> p.isAckEliciting())
                // Filter out Ping packets, ie. packets consisting of PingFrame's, padding and AckFrame's only.
                .filter(p -> ! p.getFrames().stream().allMatch(frame -> frame instanceof PingFrame || frame instanceof Padding || frame instanceof AckFrame))
                .findFirst();
        if (ackEliciting.isPresent()) {
            List<QuicFrame> framesToRetransmit = ackEliciting.get().getFrames().stream()
                    .filter(frame -> !(frame instanceof AckFrame))
                    .collect(Collectors.toList());
            return framesToRetransmit;
        }
        else {
            return Collections.emptyList();
        }
    }

    PnSpaceTime getEarliestLossTime(Function<LossDetector, Instant> pnSpaceTimeFunction) {
        PnSpaceTime earliestLossTime = null;
        for (PnSpace pnSpace: PnSpace.values()) {
            Instant pnSpaceLossTime = pnSpaceTimeFunction.apply(lossDetectors[pnSpace.ordinal()]);
            if (pnSpaceLossTime != null) {
                if (earliestLossTime == null) {
                    earliestLossTime = new PnSpaceTime(pnSpace, pnSpaceLossTime);
                } else {
                    if (! earliestLossTime.lossTime.isBefore(pnSpaceLossTime)) {
                        earliestLossTime = new PnSpaceTime(pnSpace, pnSpaceLossTime);
                    }
                }
            }
        }
        return earliestLossTime;
    }

    ScheduledFuture<?> reschedule(Runnable runnable, int timeout) {
        if (! lossDetectionTimer.cancel(false)) {
            log.warn("Cancelling loss detection timer failed");
        }
        timerExpiration = Instant.now().plusMillis(timeout);
        return scheduler.schedule(() -> {
            try {
                runnable.run();
            } catch (Exception error) {
                log.error("Runtime exception occurred while processing scheduled task", error);
            }
        }, timeout, TimeUnit.MILLISECONDS);
    }

    void unschedule() {
        lossDetectionTimer.cancel(true);
        timerExpiration = null;
    }

    public void onAckReceived(AckFrame ackFrame, PnSpace pnSpace) {
        if (! hasBeenReset) {
            ptoCount = 0;
            lossDetectors[pnSpace.ordinal()].onAckReceived(ackFrame);
        }
    }

    public void packetSent(QuicPacket packet, Instant sent, Consumer<QuicPacket> packetLostCallback) {
        if (! hasBeenReset) {
            if (packet.getEncryptionLevel() == EncryptionLevel.Handshake && !firstHandshakeSent) {
                // https://tools.ietf.org/html/draft-ietf-quic-tls-27#section-4.10.1
                // "Thus, a client MUST discard Initial keys when it first sends a Handshake packet"
                // "This results in abandoning loss recovery state for the Initial
                //   encryption level and ignoring any outstanding Initial packets."
                log.recovery("Resetting Initial pn-space, because first Handshake message is sent");
                lossDetectors[PnSpace.Initial.ordinal()].reset();
                firstHandshakeSent = true;
            }
            if (packet.isInflightPacket()) {
                // Because it's just being sent, it's definitely in flight in the sense: not acknowledged, declared lost or abandoned.
                lossDetectors[packet.getPnSpace().ordinal()].packetSent(packet, sent, packetLostCallback);
                setLossDetectionTimer();
            }
        }
    }

    private boolean ackElicitingInFlight() {
        return Stream.of(lossDetectors).anyMatch(detector -> detector.ackElicitingInFlight());
    }

    public synchronized void setReceiverMaxAckDelay(int receiverMaxAckDelay) {
        this.receiverMaxAckDelay = receiverMaxAckDelay;
    }

    public void stopRecovery() {
        hasBeenReset = true;
        unschedule();
        for (PnSpace pnSpace: PnSpace.values()) {
            stopRecovery(pnSpace);
        }
    }

    public void stopRecovery(PnSpace pnSpace) {
        log.recovery("Resetting loss detector " + pnSpace);
        lossDetectors[pnSpace.ordinal()].reset();
    }

    public long getLost() {
        return Stream.of(lossDetectors).mapToLong(ld -> ld.getLost()).sum();
    }

    @Override
    public void handshakeStateChangedEvent(HandshakeState newState) {
        if (! hasBeenReset) {
            HandshakeState oldState = handshakeState;
            handshakeState = newState;
            if (newState == HandshakeState.Confirmed && oldState != HandshakeState.Confirmed) {
                setLossDetectionTimer();
            }
        }
    }

    private static class NullScheduledFuture implements ScheduledFuture<Void> {
        @Override
        public int compareTo(Delayed o) {
            return 0;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }

    private void repeatSend(int count, Runnable task) {
        for (int i = 0; i < count; i++) {
            task.run();
            try {
                Thread.sleep(1);  // Use a small delay when sending multiple packets
            } catch (InterruptedException e) {
            }
        }
    }

    String timeNow() {
        LocalTime localTimeNow = LocalTime.from(Instant.now().atZone(ZoneId.systemDefault()));
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("mm:ss.SSS");
        return timeFormatter.format(localTimeNow);
    }

    static class PnSpaceTime {
        public PnSpace pnSpace;
        public Instant lossTime;

        public PnSpaceTime(PnSpace pnSpace, Instant pnSpaceLossTime) {
            this.pnSpace = pnSpace;
            lossTime = pnSpaceLossTime;
        }

        @Override
        public String toString() {
            return lossTime.toString() + " (in " + pnSpace + ")";
        }
    }
}
