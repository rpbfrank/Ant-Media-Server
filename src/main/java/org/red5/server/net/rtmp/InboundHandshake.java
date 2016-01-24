/*
 * RED5 Open Source Flash Server - https://github.com/Red5/
 * 
 * Copyright 2006-2016 by respective authors (see below). All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.red5.server.net.rtmp;

import java.security.KeyPair;
import java.util.Arrays;

import org.apache.commons.codec.binary.Hex;
import org.apache.mina.core.buffer.IoBuffer;
import org.red5.server.api.Red5;
import org.red5.server.net.rtmp.message.Constants;
import org.slf4j.LoggerFactory;

/**
 * Performs handshaking for server connections.
 * 
 * @author Paul Gregoire
 */
public class InboundHandshake extends RTMPHandshake {

    // server initial response S1
    private byte[] s1;

    // client initial request C1
    private byte[] c1 = null;

    // position for the server digest in S1
    private int digestPosServer;

    public InboundHandshake() {
        super(RTMPConnection.RTMP_NON_ENCRYPTED);
        log = LoggerFactory.getLogger(InboundHandshake.class);
    }

    public InboundHandshake(byte handshakeType) {
        super(handshakeType);
        log = LoggerFactory.getLogger(InboundHandshake.class);
    }

    public InboundHandshake(byte handshakeType, int algorithm) {
        this(handshakeType);
        this.algorithm = algorithm;
    }

    /**
     * Generates response for versioned connections.
     * 
     * @param input incoming RTMP handshake bytes
     * @return outgoing handshake
     */
    public IoBuffer doHandshake(IoBuffer in) {
        if (log.isTraceEnabled()) {
            log.trace("doHandshake: {}", in);
        }
        return decodeClientRequest1(in);
    }

    /**
     * Decodes the first client request (C1) and returns a server response (S0S1).
     * <pre>
     * C1 = 1536 bytes from the client
     * S0 = 0x03 (server handshake type - 0x03, 0x06, 0x08, or 0x09)
     * S1 = 1536 bytes from server
     * </pre>
     * @param in incoming handshake C1
     * @return server response S0+S1
     */
    public IoBuffer decodeClientRequest1(IoBuffer in) {
        if (log.isTraceEnabled()) {
            log.debug("decodeClientRequest1: {}", Hex.encodeHexString(in.array()));
        }
        if (in.hasArray()) {
            c1 = in.array();
        } else {
            c1 = new byte[Constants.HANDSHAKE_SIZE];
            in.get(c1);
        }
        //if (log.isTraceEnabled()) {
        //    log.trace("C1: {}", Hex.encodeHexString(c1));
        //}
        if (log.isDebugEnabled()) {
            log.debug("Flash player version {}", Hex.encodeHexString(Arrays.copyOfRange(c1, 4, 8)));
        }
        // check for un-versioned handshake
        fp9Handshake = (c1[4] & 0xff) != 0;
        if (!fp9Handshake) {
            return generateUnversionedHandshake(c1);
        }
        // make sure this is a client we can communicate with
        //if (validate(c1)) {
        //    log.debug("Valid RTMP client detected, algorithm: {}", algorithm);
        //} else {
        //    log.info("Invalid RTMP connection data detected, you may experience errors");
        //}
        // handle encryption setup
        if (useEncryption()) {
            // configure based on type and fp version
            if (handshakeType == 6 || handshakeType == 8) {
                // start off with algorithm 1 if we're type 6 or 8
                algorithm = 1;
                // set to xtea type 8 if client is fp10 capable
                //if (clientVersionByte == 128) {
                //    handshakeType = 8;
                //}
            }
            // get the DH offset in the handshake bytes, generates DH keypair, and adds the public key to handshake bytes
            int clientDHOffset = getDHOffset(algorithm, c1, 0);
            log.trace("Incoming DH offset: {}", clientDHOffset);
            // get the clients public key
            outgoingPublicKey = new byte[KEY_LENGTH];
            System.arraycopy(c1, clientDHOffset, outgoingPublicKey, 0, KEY_LENGTH);
            log.debug("Client public key: {}", Hex.encodeHexString(outgoingPublicKey));
            // get the servers dh offset
            int serverDHOffset = getDHOffset(algorithm, handshakeBytes, 0);
            log.trace("Outgoing DH offset: {}", serverDHOffset);
            // create keypair
            KeyPair keys = generateKeyPair();
            // get public key
            incomingPublicKey = getPublicKey(keys);
            log.debug("Server public key: {}", Hex.encodeHexString(incomingPublicKey));
            // add to handshake bytes
            System.arraycopy(incomingPublicKey, 0, handshakeBytes, serverDHOffset, KEY_LENGTH);
            // create the RC4 ciphers
            initRC4Encryption(getSharedSecret(outgoingPublicKey, keyAgreement));
            switch (handshakeType) {
                case RTMPConnection.RTMP_ENCRYPTED:
                    
                    break;
                case RTMPConnection.RTMP_ENCRYPTED_XTEA:
                    
                    break;
                case RTMPConnection.RTMP_ENCRYPTED_BLOWFISH:
                    
                    break;
            }
        }
        // create the server digest
        digestPosServer = getDigestOffset(algorithm, handshakeBytes, 0);
        log.debug("Server digest position offset: {} algorithm: {}", digestPosServer, algorithm);
        s1 = new byte[Constants.HANDSHAKE_SIZE];
        System.arraycopy(handshakeBytes, 0, s1, 0, Constants.HANDSHAKE_SIZE);
        // calculate the server hash and add to the handshake bytes (S1)
        calculateDigest(digestPosServer, handshakeBytes, 0, GENUINE_FMS_KEY, 36, s1, digestPosServer);
        log.debug("Server digest: {}", Hex.encodeHexString(Arrays.copyOfRange(s1, digestPosServer, digestPosServer + DIGEST_LENGTH)));
        // S1 is ready to be sent to the client, copy it before we proceed, since swfhash generation may overwrite the server digest
//        byte[] s1 = new byte[Constants.HANDSHAKE_SIZE];
//        System.arraycopy(handshakeBytes, 0, s1, 0, Constants.HANDSHAKE_SIZE);
        // get the client digest
        log.trace("Trying algorithm: {}", algorithm);
        int digestPosClient = getDigestOffset(algorithm, c1, 0);
        log.debug("Client digest position offset: {}", digestPosClient);
        if (!verifyDigest(digestPosClient, c1, GENUINE_FP_KEY, 30)) {
            // try a different position
            algorithm ^= 1;
            log.trace("Trying algorithm: {}", algorithm);
            digestPosClient = getDigestOffset(algorithm, c1, 0);
            log.debug("Client digest position offset: {}", digestPosClient);
            if (!verifyDigest(digestPosClient, c1, GENUINE_FP_KEY, 30)) {
                log.warn("Client digest verification failed");
                return null;
            }
        }
        // digest verification passed
        log.debug("Client digest: {}", Hex.encodeHexString(Arrays.copyOfRange(c1, digestPosClient, digestPosClient + DIGEST_LENGTH)));
        // swfVerification bytes are the sha256 hmac hash of the decompressed swf, the key is the last 32 bytes of the server handshake
        if (swfSize > 0) {
            // how in the heck do we generate a hash for a swf when we dont know which one is requested
            byte[] swfHash = new byte[DIGEST_LENGTH];
            calculateSwfVerification(s1, swfHash, swfSize);
        }
        // calculate the response
        byte[] digestResp = new byte[DIGEST_LENGTH];
        byte[] signatureResponse = new byte[DIGEST_LENGTH];
        // compute digest key
        calculateHMAC_SHA256(c1, digestPosClient, DIGEST_LENGTH, GENUINE_FMS_KEY, GENUINE_FMS_KEY.length, digestResp, 0);
        log.debug("Digest response (key): {}", Hex.encodeHexString(digestResp));
        calculateHMAC_SHA256(c1, Constants.HANDSHAKE_SIZE - DIGEST_LENGTH, DIGEST_LENGTH, digestResp, DIGEST_LENGTH, signatureResponse, 0);
        log.debug("Signature response: {}", Hex.encodeHexString(signatureResponse));
        if (useEncryption()) {
            switch (handshakeType) {
                case RTMPConnection.RTMP_ENCRYPTED_XTEA:
                    log.debug("RTMPE type 8 XTEA");
                    // encrypt signatureResp
                    for (int i = 0; i < DIGEST_LENGTH; i += 8) {
                        //encryptXtea(signatureResp, i, digestResp[i] % 15);
                    }
                    break;
                case RTMPConnection.RTMP_ENCRYPTED_BLOWFISH:
                    log.debug("RTMPE type 9 Blowfish");
                    // encrypt signatureResp
                    for (int i = 0; i < DIGEST_LENGTH; i += 8) {
                        //encryptBlowfish(signatureResp, i, digestResp[i] % 15);
                    }
                    break;
            }
        }
        // create output buffer for S0+S1+S2
        IoBuffer s0s1s2 = IoBuffer.allocate(Constants.HANDSHAKE_SIZE * 2 + 1); // 3073
        // set handshake with encryption type 
        s0s1s2.put(handshakeType); // 1
        s0s1s2.put(s1); // 1536
        s0s1s2.put(c1); // 1536
        s0s1s2.flip();
        if (log.isTraceEnabled()) {
            log.trace("S0+S1+S2 size: {}", s0s1s2.limit());
        }
        return s0s1s2;
    }

    /**
     * Decodes the second client request (C2) and returns a server response (S2).
     * <pre>
     * C2 = Copy of S1 bytes
     * S2 = Copy of C1 bytes
     * </pre>
     * @param in incoming handshake C2
     * @return true if C2 was processed successfully and false otherwise
     */
    public boolean decodeClientRequest2(IoBuffer in) {
        if (log.isTraceEnabled()) {
            log.debug("decodeClientRequest2: {}", Hex.encodeHexString(in.array()));
        }
        byte[] c2;
        if (in.hasArray()) {
            c2 = in.array();
        } else {
            c2 = new byte[Constants.HANDSHAKE_SIZE];
            in.get(c2);
        }
        if (fp9Handshake) {
            // client signature c2[HANDSHAKE_SIZE - DIGEST_LENGTH]
            byte[] digest = new byte[DIGEST_LENGTH];
            byte[] signature = new byte[DIGEST_LENGTH];
            log.debug("Client sent signature: {}", Hex.encodeHexString(Arrays.copyOfRange(c2, (Constants.HANDSHAKE_SIZE - DIGEST_LENGTH), (Constants.HANDSHAKE_SIZE - DIGEST_LENGTH) + DIGEST_LENGTH)));
            // verify client response
            calculateHMAC_SHA256(s1, digestPosServer, DIGEST_LENGTH, GENUINE_FP_KEY, GENUINE_FP_KEY.length, digest, 0);
            calculateHMAC_SHA256(c2, Constants.HANDSHAKE_SIZE - DIGEST_LENGTH, DIGEST_LENGTH, digest, DIGEST_LENGTH, signature, 0);
            if (useEncryption()) {
                switch (handshakeType) {
                    case RTMPConnection.RTMP_ENCRYPTED_XTEA:
                        log.debug("RTMPE type 8 XTEA");
                        // encrypt signature
                        for (int i = 0; i < DIGEST_LENGTH; i += 8) {
                            //encryptXtea(signature, i, digest[i] % 15);
                        }
                        break;
                    case RTMPConnection.RTMP_ENCRYPTED_BLOWFISH:
                        log.debug("RTMPE type 9 Blowfish");
                        // encrypt signature
                        for (int i = 0; i < DIGEST_LENGTH; i += 8) {
                            //encryptBlowfish(signature, i, digest[i] % 15);
                        }
                        break;
                }
                // update 'encoder / decoder state' for the RC4 keys both parties *pretend* as if handshake part 2 (1536 bytes) was encrypted
                // effectively this hides / discards the first few bytes of encrypted session which is known to increase the secure-ness of RC4
                // RC4 state is just a function of number of bytes processed so far that's why we just run 1536 arbitrary bytes through the keys below
                byte[] dummyBytes = new byte[Constants.HANDSHAKE_SIZE];
                cipherIn.update(dummyBytes);
                cipherOut.update(dummyBytes);
            }
            // show some information
            log.debug("Digest key: {}", Hex.encodeHexString(digest));
            log.debug("Signature calculated: {}", Hex.encodeHexString(signature));
            //if (memcmp(signature, c2[Constants.HANDSHAKE_SIZE - DIGEST_LENGTH], DIGEST_LENGTH) != 0) {
            if (!Arrays.equals(signature, Arrays.copyOfRange(c2, (Constants.HANDSHAKE_SIZE - DIGEST_LENGTH), (Constants.HANDSHAKE_SIZE - DIGEST_LENGTH) + DIGEST_LENGTH))) {
              log.debug("Client not genuine Adobe!");
              //return false;
            } else {
              log.debug("Genuine Adobe Flash Player");
            }
       } else {
           //if (memcmp(serversig, clientsig, RTMP_SIG_SIZE) != 0)
           for (int i = 0; i < Constants.HANDSHAKE_SIZE; i++) {
               if (c2[i] != handshakeBytes[i]) {
                   log.info("Client signature doesn't match!");
                   break;
               }
           }
       }
       return true;
    }

    /**
     * Generates response for non-versioned connections, such as those before FP9.
     * 
     * @param input incoming RTMP bytes
     * @return outgoing handshake
     */
    private IoBuffer generateUnversionedHandshake(byte[] input) {
        log.debug("Using old style (un-versioned) handshake");
        // save resource by only doing this after the first request
        if (HANDSHAKE_PAD_BYTES == null) {
            HANDSHAKE_PAD_BYTES = new byte[Constants.HANDSHAKE_SIZE - 4];
            // fill pad bytes
            Arrays.fill(HANDSHAKE_PAD_BYTES, (byte) 0x00);
        }
        IoBuffer output = IoBuffer.allocate(HANDSHAKE_SIZE_SERVER);
        // non-encrypted
        output.put(RTMPConnection.RTMP_NON_ENCRYPTED);
        // set server uptime in seconds
        output.putInt((int) Red5.getUpTime() / 1000); //0x01
        output.put(RTMPHandshake.HANDSHAKE_PAD_BYTES);
        output.put(input);
        output.flip();
        return output;
    }

    /**
     * Creates the servers handshake bytes
     */
    @Override
    protected void createHandshakeBytes() {
        handshakeBytes = new byte[Constants.HANDSHAKE_SIZE];
        // timestamp
        int time = (int) (Red5.getUpTime() / 1000);
        handshakeBytes[0] = (byte) (time >>> 24);
        handshakeBytes[1] = (byte) (time >>> 16);
        handshakeBytes[2] = (byte) (time >>> 8);
        handshakeBytes[3] = (byte) time;
        // version 4
        handshakeBytes[4] = 4;
        handshakeBytes[5] = 0;
        handshakeBytes[6] = 0;
        handshakeBytes[7] = 1;
        // fill the rest with random bytes
        byte[] rndBytes = new byte[Constants.HANDSHAKE_SIZE - 8];
        random.nextBytes(rndBytes);
        // copy random bytes into our handshake array
        System.arraycopy(rndBytes, 0, handshakeBytes, 8, (Constants.HANDSHAKE_SIZE - 8));
    }

    /**
     * Determines the validation scheme for given input.
     * 
     * @param input handshake bytes from the client
     * @return true if client used a supported validation scheme, false if unsupported
     */
    @Override
    public boolean validate(byte[] handshake) {
        if (validateScheme(handshake, 0)) {
            algorithm = 0;
            return true;
        }
        if (validateScheme(handshake, 1)) {
            algorithm = 1;
            return true;
        }
        log.error("Unable to validate client");
        return false;
    }

    private boolean validateScheme(byte[] handshake, int scheme) {
        int digestOffset = -1;
        switch (scheme) {
            case 0:
                digestOffset = getDigestOffset1(handshake, 0);
                break;
            case 1:
                digestOffset = getDigestOffset2(handshake, 0);
                break;
            default:
                log.error("Unknown algorithm: {}", scheme);
        }
        log.debug("Algorithm: {} digest offset: {}", scheme, digestOffset);
        byte[] tempBuffer = new byte[Constants.HANDSHAKE_SIZE - DIGEST_LENGTH];
        System.arraycopy(handshake, 0, tempBuffer, 0, digestOffset);
        System.arraycopy(handshake, digestOffset + DIGEST_LENGTH, tempBuffer, digestOffset, Constants.HANDSHAKE_SIZE - digestOffset - DIGEST_LENGTH);
        byte[] tempHash = new byte[DIGEST_LENGTH];
        calculateHMAC_SHA256(tempBuffer, 0, tempBuffer.length, GENUINE_FP_KEY, 30, tempHash, 0);
        log.debug("Hash: {}", Hex.encodeHexString(tempHash));
        boolean result = true;
        for (int i = 0; i < DIGEST_LENGTH; i++) {
            if (handshake[digestOffset + i] != tempHash[i]) {
                result = false;
                break;
            }
        }
        return result;
    }

}