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
package net.luminis.quic.crypto;

import at.favre.lib.crypto.HKDF;
import net.luminis.quic.EncryptionLevel;
import net.luminis.quic.Version;
import net.luminis.quic.crypto.Keys;
import net.luminis.quic.log.Logger;
import net.luminis.tls.ByteUtils;
import net.luminis.tls.TlsConstants;
import net.luminis.tls.TlsState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class ConnectionSecrets {

    private TlsConstants.CipherSuite selectedCipherSuite;

    enum NodeRole {
        Client,
        Server
    }

    // https://tools.ietf.org/html/draft-ietf-quic-tls-23#section-5.2
    public static final byte[] STATIC_SALT_DRAFT_23 = new byte[] {
            (byte) 0xc3, (byte) 0xee, (byte) 0xf7, (byte) 0x12, (byte) 0xc7, (byte) 0x2e, (byte) 0xbb, (byte) 0x5a,
            (byte) 0x11, (byte) 0xa7, (byte) 0xd2, (byte) 0x43, (byte) 0x2b, (byte) 0xb4, (byte) 0x63, (byte) 0x65,
            (byte) 0xbe, (byte) 0xf9, (byte) 0xf5, (byte) 0x02 };

    // https://tools.ietf.org/html/draft-ietf-quic-tls-29#section-5.2
    public static final byte[] STATIC_SALT_DRAFT_29 = new byte[] {
            (byte) 0xaf, (byte) 0xbf, (byte) 0xec, (byte) 0x28, (byte) 0x99, (byte) 0x93, (byte) 0xd2, (byte) 0x4c,
            (byte) 0x9e, (byte) 0x97, (byte) 0x86, (byte) 0xf1, (byte) 0x9c, (byte) 0x61, (byte) 0x11, (byte) 0xe0,
            (byte) 0x43, (byte) 0x90, (byte) 0xa8, (byte) 0x99 };

    private final Version quicVersion;
    private Logger log;
    private byte[] clientRandom;
    private Keys[] clientSecrets = new Keys[EncryptionLevel.values().length];
    private Keys[] serverSecrets = new Keys[EncryptionLevel.values().length];
    private boolean writeSecretsToFile;
    private Path wiresharkSecretsFile;


    public ConnectionSecrets(Version quicVersion, Path wiresharksecrets, Logger log) {
        this.quicVersion = quicVersion;
        this.log = log;

        if (wiresharksecrets != null) {
            wiresharkSecretsFile = wiresharksecrets;
            try {
                Files.deleteIfExists(wiresharkSecretsFile);
                Files.createFile(wiresharkSecretsFile);
                writeSecretsToFile = true;
            } catch (IOException e) {
                log.error("Initializing (creating/truncating) secrets file '" + wiresharkSecretsFile + "' failed", e);
            }
        }
    }

    /**
     * Generate the initial secrets
     *
     * @param destConnectionId
     */
    public synchronized void computeInitialKeys(byte[] destConnectionId) {

        // From https://tools.ietf.org/html/draft-ietf-quic-tls-16#section-5.2:
        // "The hash function for HKDF when deriving initial secrets and keys is SHA-256"
        HKDF hkdf = HKDF.fromHmacSha256();

        byte[] initialSalt = quicVersion.before(Version.IETF_draft_29)? STATIC_SALT_DRAFT_23: STATIC_SALT_DRAFT_29;
        byte[] initialSecret = hkdf.extract(initialSalt, destConnectionId);

        log.secret("Initial secret", initialSecret);

        clientSecrets[EncryptionLevel.Initial.ordinal()] = new Keys(quicVersion, initialSecret, NodeRole.Client, log);
        serverSecrets[EncryptionLevel.Initial.ordinal()] = new Keys(quicVersion, initialSecret, NodeRole.Server, log);
    }

    public synchronized void computeEarlySecrets(TlsState tlsState) {
        Keys zeroRttSecrets = new Keys(quicVersion, NodeRole.Client, log);
        zeroRttSecrets.computeZeroRttKeys(tlsState);
        clientSecrets[EncryptionLevel.ZeroRTT.ordinal()] = zeroRttSecrets;
    }

    private void createKeys(EncryptionLevel level, TlsConstants.CipherSuite selectedCipherSuite) {
        Keys clientHandshakeSecrets;
        Keys serverHandshakeSecrets;
        if (selectedCipherSuite == TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256) {
            clientHandshakeSecrets = new Keys(quicVersion, NodeRole.Client, log);
            serverHandshakeSecrets = new Keys(quicVersion, NodeRole.Server, log);
        }
        else if (selectedCipherSuite == TlsConstants.CipherSuite.TLS_CHACHA20_POLY1305_SHA256) {
            clientHandshakeSecrets = new Chacha20Keys(quicVersion, NodeRole.Client, log);
            serverHandshakeSecrets = new Chacha20Keys(quicVersion, NodeRole.Server, log);
        }
        else {
            throw new IllegalStateException("unsupported cipher suite " + selectedCipherSuite);
        }
        clientSecrets[level.ordinal()] = clientHandshakeSecrets;
        serverSecrets[level.ordinal()] = serverHandshakeSecrets;
    }

    public synchronized void computeHandshakeSecrets(TlsState tlsState, TlsConstants.CipherSuite selectedCipherSuite) {
        this.selectedCipherSuite = selectedCipherSuite;
        createKeys(EncryptionLevel.Handshake, selectedCipherSuite);

        clientSecrets[EncryptionLevel.Handshake.ordinal()].computeHandshakeKeys(tlsState);
        serverSecrets[EncryptionLevel.Handshake.ordinal()].computeHandshakeKeys(tlsState);

        if (writeSecretsToFile) {
            appendToFile("HANDSHAKE_TRAFFIC_SECRET", EncryptionLevel.Handshake);
        }
    }

    public synchronized void computeApplicationSecrets(TlsState tlsState) {
        createKeys(EncryptionLevel.App, selectedCipherSuite);

        clientSecrets[EncryptionLevel.App.ordinal()].computeApplicationKeys(tlsState);
        serverSecrets[EncryptionLevel.App.ordinal()].computeApplicationKeys(tlsState);

        if (writeSecretsToFile) {
            appendToFile("TRAFFIC_SECRET_0", EncryptionLevel.App);
        }
    }

    private void appendToFile(String label, EncryptionLevel level) {
        List<String> content = new ArrayList<>();
        content.add("CLIENT_" + label + " "
                + ByteUtils.bytesToHex(clientRandom) + " "
                + ByteUtils.bytesToHex(clientSecrets[level.ordinal()].getTrafficSecret()));
        content.add("SERVER_" + label + " "
                + ByteUtils.bytesToHex(clientRandom) + " "
                + ByteUtils.bytesToHex(serverSecrets[level.ordinal()].getTrafficSecret()));

        try {
            Files.write(wiresharkSecretsFile, content, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Writing secrets to file '" + wiresharkSecretsFile + "' failed", e);
            writeSecretsToFile = false;
        }
    }

    public void setClientRandom(byte[] clientRandom) {
        this.clientRandom = clientRandom;
    }

    public synchronized Keys getClientSecrets(EncryptionLevel encryptionLevel) {
        return clientSecrets[encryptionLevel.ordinal()];
    }

    public synchronized Keys getServerSecrets(EncryptionLevel encryptionLevel) {
        return serverSecrets[encryptionLevel.ordinal()];
    }
}
