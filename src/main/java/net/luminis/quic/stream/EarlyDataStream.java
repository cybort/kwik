/*
 * Copyright © 2020 Peter Doornbosch
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
package net.luminis.quic.stream;

import net.luminis.quic.QuicConnectionImpl;
import net.luminis.quic.Version;
import net.luminis.quic.frame.QuicFrame;
import net.luminis.quic.frame.StreamFrame;
import net.luminis.quic.log.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * A quic stream that is capable of sending early data. When early data is offered but cannot be send as early data,
 * the data will be cached until it can be send.
 */
public class EarlyDataStream extends QuicStream {

    private volatile boolean sendingEarlyData = true;
    private boolean earlyDataIsFinalInStream;
    private byte[] earlyData = new byte[0];
    private byte[] remainingData = new byte[0];

    public EarlyDataStream(Version quicVersion, int streamId, QuicConnectionImpl connection, FlowControl flowController, Logger log) {
        super(quicVersion, streamId, connection, flowController, log);
    }

    /**
     * Write early data, assuming the provided data is complete and fits into one StreamFrame.
     * @param earlyData
     * @param earlyDataSizeLeft
     */
    public void writeEarlyData(byte[] earlyData, boolean fin, long earlyDataSizeLeft) throws IOException {
        this.earlyData = earlyData;
        earlyDataIsFinalInStream = fin;
        long flowControlLimit = flowController.getFlowControlLimit(this);
        int earlyDataLength = (int) Long.min(earlyData.length, Long.min(earlyDataSizeLeft, flowControlLimit));
        if (earlyDataLength > 0) {
            log.info(String.format("Sending %d bytes of early data on %s", earlyDataLength, this));
        }
        else {
            log.error("Sending no early data because: fc limit is " + flowControlLimit + "; early data size left is " + earlyDataSizeLeft + " and early data length is " + earlyData.length);
        }

        getOutputStream().write(earlyData, 0, earlyDataLength);
        if (earlyDataLength == earlyData.length && earlyDataIsFinalInStream) {
            getOutputStream().close();
        }
        sendingEarlyData = false;
        remainingData = Arrays.copyOfRange(earlyData, earlyDataLength, earlyData.length);
    }

    public void writeRemaining(boolean earlyDataWasAccepted) throws IOException {
        if (earlyDataWasAccepted) {
            if (remainingData.length > 0) {
                getOutputStream().write(remainingData);
                getOutputStream().close();
            }
        }
        else {
            resetOutputStream();
            getOutputStream().write(earlyData);
            if (earlyDataIsFinalInStream) {
                getOutputStream().close();
            }
        }
    }

    @Override
    protected void send(StreamFrame frame, Consumer<QuicFrame> lostFrameCallback) {
        if (sendingEarlyData) {
            connection.sendZeroRtt(frame, f -> {});
        }
        else {
            connection.send(frame, lostFrameCallback);
        }
    }

}

