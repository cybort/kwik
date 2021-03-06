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

import net.luminis.quic.frame.CryptoFrame;
import net.luminis.quic.log.Logger;
import net.luminis.tls.Message;
import net.luminis.tls.TlsState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CryptoStreamTest {

    public static final Version QUIC_VERSION = Version.getDefault();

    private CryptoStream cryptoStream;
    private TlsMessageParser messageParser;

    @BeforeEach
    void prepareObjectUnderTest() throws Exception {
        cryptoStream = new CryptoStream(QUIC_VERSION, null, EncryptionLevel.Handshake, null, new TlsState(), mock(Logger.class));
        messageParser = mock(TlsMessageParser.class);
        FieldSetter.setField(cryptoStream, cryptoStream.getClass().getDeclaredField("tlsMessageParser"), messageParser);

        setParseFunction(buffer -> {
            int length = buffer.getInt();
            byte[] stringBytes = new byte[length];
            buffer.get(stringBytes);
            return new MockTlsMessage(new String(stringBytes));
        });
    }

    @Test
    void parseSingleMessageInSingleFrame() throws Exception {
        cryptoStream.add(new CryptoFrame(QUIC_VERSION, convertToMsgBytes("first crypto frame")));

        assertThat(cryptoStream.getTlsMessages().isEmpty()).isFalse();
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("first crypto frame"));
    }

    @Test
    void parserWaitsForAllFramesNeededToParseWholeMessage() throws Exception {
        byte[] rawMessageBytes = convertToMsgBytes("first frame second frame last crypto frame");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOf(rawMessageBytes,4 + 12)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 16, "second frame ".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 29, "last crypto frame".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("first frame second frame last crypto frame"));
    }

    @Test
    void parserWaitsForAllOutOfOrderFramesNeededToParseWholeMessage() throws Exception {
        byte[] rawMessageBytes = convertToMsgBytes("first frame second frame last crypto frame");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 29, "last crypto frame".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOf(rawMessageBytes,4 + 12)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 16, "second frame ".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("first frame second frame last crypto frame"));
    }

    @Test
    void handleRetransmittedFramesWithDifferentSegmentation() throws Exception {
        byte[] rawMessageBytes = convertToMsgBytes("first frame second frame last crypto frame");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 29, "last crypto frame".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOf(rawMessageBytes,4 + 12)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        // Simulate second frame is never received, but all crypto content is retransmitted in different frames.
        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOf(rawMessageBytes,4 + 19)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 23, "frame last crypto frame".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("first frame second frame last crypto frame"));
    }

    @Test
    void handleOverlappingFrames() throws Exception {
        byte[] rawMessageBytes = convertToMsgBytes("abcdefghijklmnopqrstuvwxyz");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 2, "cdefghijk".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 4, "efghi".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 12, "mn".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 10, "klmnop".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOfRange(rawMessageBytes, 0, 8)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 8, "ijklmnopqrstuvwxyz".getBytes()));

        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void parseMultipleMessages() {
        byte[] rawMessageBytes1 = convertToMsgBytes("abcdefghijklmnopqrstuvwxyz");
        byte[] rawMessageBytes2 = convertToMsgBytes("0123456789");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 26, rawMessageBytes2));

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 8, "ijklmnopqrstuvwxyz".getBytes()));
        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 10, "klmnopqrstuvwxyz".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOfRange(rawMessageBytes1, 0, 18)));

        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcdefghijklmnopqrstuvwxyz"));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("0123456789"));
    }

    @Test
    void parseMessageSplitAccrossMultipleFrames() {
        byte[] rawMessageBytes = new byte[4 + 5 + 4 + 5];
        System.arraycopy(convertToMsgBytes("abcde"), 0, rawMessageBytes, 0, 4 + 5);
        System.arraycopy(convertToMsgBytes("12345"), 0, rawMessageBytes, 4 + 5, 4 + 5);

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOfRange(rawMessageBytes, 0, 11)));
        assertThat(cryptoStream.getTlsMessages().size()).isEqualTo(1);
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcde"));

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 11, Arrays.copyOfRange(rawMessageBytes, 11, 12)));
        assertThat(cryptoStream.getTlsMessages().size()).isEqualTo(1);
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcde"));

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 12, Arrays.copyOfRange(rawMessageBytes, 12, 14)));
        assertThat(cryptoStream.getTlsMessages().size()).isEqualTo(1);
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcde"));

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 14, Arrays.copyOfRange(rawMessageBytes, 14, 18)));
        assertThat(cryptoStream.getTlsMessages().size()).isEqualTo(2);
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcde"));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("12345"));
    }

    private void setParseFunction(Function<ByteBuffer, Message> parseFunction) throws Exception {
        when(messageParser.parse(any(ByteBuffer.class), any(TlsState.class))).thenAnswer(new Answer<Message>() {
            @Override
            public Message answer(InvocationOnMock invocation) throws Throwable {
                ByteBuffer buffer = invocation.getArgument(0);
                return parseFunction.apply(buffer);
            }
        });
    }

    private byte[] convertToMsgBytes(String content) {
        byte[] bytes = new byte[content.getBytes().length + 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(content.getBytes().length);
        buffer.put(content.getBytes());
        return bytes;
    }

    static class MockTlsMessage extends Message {
        String contents;

        public MockTlsMessage(String contents) {
            this.contents = contents;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MockTlsMessage that = (MockTlsMessage) o;
            return Objects.equals(contents, that.contents);
        }

        @Override
        public int hashCode() {
            return Objects.hash(contents);
        }

        @Override
        public String toString() {
            return "Message: " + contents;
        }
    }

}