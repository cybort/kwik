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
package net.luminis.quic;

import net.luminis.quic.log.Logger;

import java.time.Duration;
import java.time.Instant;


public class RttEstimator {

    private Logger log;
    // All intervals are in milliseconds (1/1000 second)
    private int initialRtt;
    private int minRtt = Integer.MAX_VALUE;
    private int smoothedRtt = 0;
    private int rttVar;
    private int latestRtt;


    public RttEstimator(Logger log) {
        this.log = log;

        // https://tools.ietf.org/html/draft-ietf-quic-recovery-20#section-6.2
        // "If no previous RTT is available, or if the network
        //   changes, the initial RTT SHOULD be set to 500ms"
        initialRtt = 500;
    }

    public RttEstimator(Logger log, int initialRtt) {
        this.log = log;
        this.initialRtt = initialRtt;
    }

    public void addSample(Instant timeReceived, Instant timeSent, int ackDelay) {
        if (timeReceived.isBefore(timeSent)) {
            // This sometimes happens in the Interop runner; reconsider solution after new sender is implemented.
            log.error("Receiving negative rtt estimate: sent=" + timeSent + ", received=" + timeReceived);
            return;
        }

        // TODO: if ackDelay > maxAckDelay, limit it to ackDelay.

        int previousSmoothed = smoothedRtt;

        int rttSample = Duration.between(timeSent, timeReceived).getNano() / 1_000_000 - ackDelay;
        latestRtt = rttSample;
        if (rttSample < minRtt)
            minRtt = rttSample;
        // Adjust for ack delay if it's plausible.
        if (rttSample > minRtt + ackDelay) {
            rttSample -= ackDelay;
        }

        if (smoothedRtt == 0) {
            // First time
            smoothedRtt = rttSample;
            rttVar = rttSample / 2;
        }
        else {
            int currentRttVar = Math.abs(smoothedRtt - rttSample);
            rttVar = 3 * rttVar / 4 + currentRttVar / 4;
            smoothedRtt = 7 * smoothedRtt / 8 + rttSample / 8;
        }

        log.debug("RTT: " + previousSmoothed + " + " + rttSample + " -> " + smoothedRtt);
    }

    public int getSmoothedRtt() {
        if (smoothedRtt == 0) {
            return initialRtt;
        }
        else {
            return smoothedRtt;
        }
    }

    public int getRttVar() {
        // Rtt-var is only used for computing PTO.
        // https://tools.ietf.org/html/draft-ietf-quic-recovery-23#section-5.3
        // "The initial probe timeout for a new connection or new path SHOULD be set to twice the initial RTT"
        // https://tools.ietf.org/html/draft-ietf-quic-recovery-23#section-5.2.1
        // "PTO = smoothed_rtt + max(4*rttvar, kGranularity) + max_ack_delay"
        // Hence, using an initial rtt-var of initial-rtt / 4, will result in an initial PTO of twice the initial RTT.
        // After the first packet is received, the rttVar will be computed from the real RTT sample.
        if (rttVar == 0) {
            return initialRtt / 4;
        }
        else {
            return rttVar;
        }
    }

    public int getLatestRtt() {
        return latestRtt;
    }
}
