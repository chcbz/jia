/*
 * Copyright (C) 2010 The MobileSecurePay Project
 * All right reserved.
 * author: shiqun.shi@alipay.com
 */

package cn.jia.core.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * @author chc
 */
@Slf4j
public final class Base64Util {

    static private final int BASE_LENGTH = 128;
    static private final int LOOKUP_LENGTH = 64;
    static private final int TWENTY_FOUR_BIT_GROUP = 24;
    static private final int EIGHT_BIT = 8;
    static private final int SIXTEEN_BIT = 16;
    static private final int FOUR_BYTE = 4;
    static private final int     SIGN                 = -128;
    static private final char    PAD                  = '=';
    static private final boolean F_DEBUG = false;
    static final private byte[] BASE64_ALPHABET = new byte[BASE_LENGTH];
    static final private char[] LOOK_UP_BASE64_ALPHABET = new char[LOOKUP_LENGTH];

    static {
        for (int i = 0; i < BASE_LENGTH; ++i) {
            BASE64_ALPHABET[i] = -1;
        }
        for (int i = 'Z'; i >= 'A'; i--) {
            BASE64_ALPHABET[i] = (byte) (i - 'A');
        }
        for (int i = 'z'; i >= 'a'; i--) {
            BASE64_ALPHABET[i] = (byte) (i - 'a' + 26);
        }

        for (int i = '9'; i >= '0'; i--) {
            BASE64_ALPHABET[i] = (byte) (i - '0' + 52);
        }

        BASE64_ALPHABET['+'] = 62;
        BASE64_ALPHABET['/'] = 63;

        for (int i = 0; i <= 25; i++) {
            LOOK_UP_BASE64_ALPHABET[i] = (char) ('A' + i);
        }

        for (int i = 26, j = 0; i <= 51; i++, j++) {
            LOOK_UP_BASE64_ALPHABET[i] = (char) ('a' + j);
        }

        for (int i = 52, j = 0; i <= 61; i++, j++) {
            LOOK_UP_BASE64_ALPHABET[i] = (char) ('0' + j);
        }
        LOOK_UP_BASE64_ALPHABET[62] = '+';
        LOOK_UP_BASE64_ALPHABET[63] = '/';

    }

    private static boolean isWhiteSpace(char octect) {
        return (octect == 0x20 || octect == 0xd || octect == 0xa || octect == 0x9);
    }

    private static boolean isPad(char octect) {
        return (octect == PAD);
    }

    private static boolean isData(char octect) {
        return (octect < BASE_LENGTH && BASE64_ALPHABET[octect] != -1);
    }

    /**
     * Base64加密
     *
     * @param rawData 原数据
     * @return 加密后数据
     */
    public static String encode(String rawData) {
        return encode(rawData.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Encodes hex octects into Base64
     *
     * @param binaryData Array containing binaryData
     * @return Encoded Base64 array
     */
    public static String encode(byte[] binaryData) {

        if (binaryData == null) {
            return null;
        }

        int lengthDataBits = binaryData.length * EIGHT_BIT;
        if (lengthDataBits == 0) {
            return "";
        }

        int fewerThan24bits = lengthDataBits % TWENTY_FOUR_BIT_GROUP;
        int numberTriplets = lengthDataBits / TWENTY_FOUR_BIT_GROUP;
        int numberQuartet = fewerThan24bits != 0 ? numberTriplets + 1 : numberTriplets;
        char[] encodedData = new char[numberQuartet * 4];

        byte k = 0, l = 0, b1 = 0, b2 = 0, b3 = 0;

        int encodedIndex = 0;
        int dataIndex = 0;
        if (F_DEBUG) {
            log.info("number of triplets = " + numberTriplets);
        }

        for (int i = 0; i < numberTriplets; i++) {
            b1 = binaryData[dataIndex++];
            b2 = binaryData[dataIndex++];
            b3 = binaryData[dataIndex++];

            if (F_DEBUG) {
                log.info("b1= " + b1 + ", b2= " + b2 + ", b3= " + b3);
            }

            l = (byte) (b2 & 0x0f);
            k = (byte) (b1 & 0x03);

            byte val1 = ((b1 & SIGN) == 0) ? (byte) (b1 >> 2) : (byte) ((b1) >> 2 ^ 0xc0);
            byte val2 = ((b2 & SIGN) == 0) ? (byte) (b2 >> 4) : (byte) ((b2) >> 4 ^ 0xf0);
            byte val3 = ((b3 & SIGN) == 0) ? (byte) (b3 >> 6) : (byte) ((b3) >> 6 ^ 0xfc);

            if (F_DEBUG) {
                log.info("val2 = " + val2);
                log.info("k4   = " + (k << 4));
                log.info("vak  = " + (val2 | (k << 4)));
            }

            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[val1];
            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[val2 | (k << 4)];
            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[(l << 2) | val3];
            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[b3 & 0x3f];
        }

        // form integral number of 6-bit groups
        if (fewerThan24bits == EIGHT_BIT) {
            b1 = binaryData[dataIndex];
            k = (byte) (b1 & 0x03);
            if (F_DEBUG) {
                log.info("b1=" + b1);
                log.info("b1<<2 = " + (b1 >> 2));
            }
            byte val1 = ((b1 & SIGN) == 0) ? (byte) (b1 >> 2) : (byte) ((b1) >> 2 ^ 0xc0);
            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[val1];
            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[k << 4];
            encodedData[encodedIndex++] = PAD;
            encodedData[encodedIndex++] = PAD;
        } else if (fewerThan24bits == SIXTEEN_BIT) {
            b1 = binaryData[dataIndex];
            b2 = binaryData[dataIndex + 1];
            l = (byte) (b2 & 0x0f);
            k = (byte) (b1 & 0x03);

            byte val1 = ((b1 & SIGN) == 0) ? (byte) (b1 >> 2) : (byte) ((b1) >> 2 ^ 0xc0);
            byte val2 = ((b2 & SIGN) == 0) ? (byte) (b2 >> 4) : (byte) ((b2) >> 4 ^ 0xf0);

            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[val1];
            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[val2 | (k << 4)];
            encodedData[encodedIndex++] = LOOK_UP_BASE64_ALPHABET[l << 2];
            encodedData[encodedIndex++] = PAD;
        }

        return new String(encodedData);
    }

    /**
     * Decodes Base64 data into octects
     *
     * @param encoded string containing Base64 data
     * @return Array containind decoded data.
     */
    public static byte[] decode(String encoded) {

        if (encoded == null) {
            return null;
        }

        char[] base64Data = encoded.toCharArray();
        // remove white spaces
        int len = removeWhiteSpace(base64Data);

        if (len % FOUR_BYTE != 0) {
            //should be divisible by four
            return null;
        }

        int numberQuadruple = (len / FOUR_BYTE);

        if (numberQuadruple == 0) {
            return new byte[0];
        }

        byte[] decodedData = null;
        byte b1 = 0, b2 = 0, b3 = 0, b4 = 0;
        char d1 = 0, d2 = 0, d3 = 0, d4 = 0;

        int i = 0;
        int encodedIndex = 0;
        int dataIndex = 0;
        decodedData = new byte[(numberQuadruple) * 3];

        for (; i < numberQuadruple - 1; i++) {

            if (!isData((d1 = base64Data[dataIndex++])) || !isData((d2 = base64Data[dataIndex++]))
                || !isData((d3 = base64Data[dataIndex++]))
                || !isData((d4 = base64Data[dataIndex++]))) {
                return null;
            }//if found "no data" just return null

            b1 = BASE64_ALPHABET[d1];
            b2 = BASE64_ALPHABET[d2];
            b3 = BASE64_ALPHABET[d3];
            b4 = BASE64_ALPHABET[d4];

            decodedData[encodedIndex++] = (byte) (b1 << 2 | b2 >> 4);
            decodedData[encodedIndex++] = (byte) (((b2 & 0xf) << 4) | ((b3 >> 2) & 0xf));
            decodedData[encodedIndex++] = (byte) (b3 << 6 | b4);
        }

        if (!isData((d1 = base64Data[dataIndex++])) || !isData((d2 = base64Data[dataIndex++]))) {
            //if found "no data" just return null
            return null;
        }

        b1 = BASE64_ALPHABET[d1];
        b2 = BASE64_ALPHABET[d2];

        d3 = base64Data[dataIndex++];
        d4 = base64Data[dataIndex++];
        //Check if they are PAD characters
        if (!isData((d3)) || !isData((d4))) {
            if (isPad(d3) && isPad(d4)) {
                // last 4 bits should be zero
                if ((b2 & 0xf) != 0)
                {
                    return null;
                }
                byte[] tmp = new byte[i * 3 + 1];
                System.arraycopy(decodedData, 0, tmp, 0, i * 3);
                tmp[encodedIndex] = (byte) (b1 << 2 | b2 >> 4);
                return tmp;
            } else if (!isPad(d3) && isPad(d4)) {
                b3 = BASE64_ALPHABET[d3];
                // last 2 bits should be zero
                if ((b3 & 0x3) != 0)
                {
                    return null;
                }
                byte[] tmp = new byte[i * 3 + 2];
                System.arraycopy(decodedData, 0, tmp, 0, i * 3);
                tmp[encodedIndex++] = (byte) (b1 << 2 | b2 >> 4);
                tmp[encodedIndex] = (byte) (((b2 & 0xf) << 4) | ((b3 >> 2) & 0xf));
                return tmp;
            } else {
                return null;
            }
        } else { //No PAD e.g 3cQl
            b3 = BASE64_ALPHABET[d3];
            b4 = BASE64_ALPHABET[d4];
            decodedData[encodedIndex++] = (byte) (b1 << 2 | b2 >> 4);
            decodedData[encodedIndex++] = (byte) (((b2 & 0xf) << 4) | ((b3 >> 2) & 0xf));
            decodedData[encodedIndex++] = (byte) (b3 << 6 | b4);

        }

        return decodedData;
    }

    /**
     * remove WhiteSpace from MIME containing encoded Base64 data.
     *
     * @param data  the byte array of base64 data (with WS)
     * @return      the new length
     */
    private static int removeWhiteSpace(char[] data) {
        if (data == null) {
            return 0;
        }

        // count characters that's not whitespace
        int newSize = 0;
        int len = data.length;
        for (int i = 0; i < len; i++) {
            if (!isWhiteSpace(data[i])) {
                data[newSize++] = data[i];
            }
        }
        return newSize;
    }
    
    /**
     * Decodes Base64 data into octects
     *
     * @param base64Data Byte array containing Base64 data
     * @return Array containing decoded data.
     */
    public static byte[] decodeBase64(byte[] base64Data) {
        // RFC 2045 requires that we discard ALL non-Base64 characters
        base64Data = discardNonBase64(base64Data);

        // handle the edge case, so we don't have to worry about it later
        if (base64Data.length == 0) {
            return new byte[0];
        }

        int numberQuadruple = base64Data.length / FOUR_BYTE;
        byte[] decodedData = null;
        byte b1 = 0, b2 = 0, b3 = 0, b4 = 0, marker0 = 0, marker1 = 0;

        // Throw away anything not in base64Data

        int encodedIndex = 0;
        int dataIndex = 0;
        {
            // this sizes the output array properly - rlw
            int lastData = base64Data.length;
            // ignore the '=' padding
            while (base64Data[lastData - 1] == PAD) {
                if (--lastData == 0) {
                    return new byte[0];
                }
            }
            decodedData = new byte[lastData - numberQuadruple];
        }
        
        for (int i = 0; i < numberQuadruple; i++) {
            dataIndex = i * 4;
            marker0 = base64Data[dataIndex + 2];
            marker1 = base64Data[dataIndex + 3];
            
            b1 = BASE64_ALPHABET[base64Data[dataIndex]];
            b2 = BASE64_ALPHABET[base64Data[dataIndex + 1]];
            
            if (marker0 != PAD && marker1 != PAD) {
                //No PAD e.g 3cQl
                b3 = BASE64_ALPHABET[marker0];
                b4 = BASE64_ALPHABET[marker1];
                
                decodedData[encodedIndex] = (byte) (b1 << 2 | b2 >> 4);
                decodedData[encodedIndex + 1] =
                    (byte) (((b2 & 0xf) << 4) | ((b3 >> 2) & 0xf));
                decodedData[encodedIndex + 2] = (byte) (b3 << 6 | b4);
            } else if (marker0 == PAD) {
                //Two PAD e.g. 3c[Pad][Pad]
                decodedData[encodedIndex] = (byte) (b1 << 2 | b2 >> 4);
            } else if (marker1 == PAD) {
                //One PAD e.g. 3cQ[Pad]
                b3 = BASE64_ALPHABET[marker0];
                
                decodedData[encodedIndex] = (byte) (b1 << 2 | b2 >> 4);
                decodedData[encodedIndex + 1] =
                    (byte) (((b2 & 0xf) << 4) | ((b3 >> 2) & 0xf));
            }
            encodedIndex += 3;
        }
        return decodedData;
    }
    
    /**
     * Discards any characters outside of the base64 alphabet, per
     * the requirements on page 25 of RFC 2045 - "Any characters
     * outside of the base64 alphabet are to be ignored in base64
     * encoded data."
     *
     * @param data The base-64 encoded data to groom
     * @return The data, less non-base64 characters (see RFC 2045).
     */
    static byte[] discardNonBase64(byte[] data) {
        byte[] groomedData = new byte[data.length];
        int bytesCopied = 0;

        for (byte datum : data) {
            if (isBase64(datum)) {
                groomedData[bytesCopied++] = datum;
            }
        }

        byte[] packedData = new byte[bytesCopied];

        System.arraycopy(groomedData, 0, packedData, 0, bytesCopied);

        return packedData;
    }
    
    private static boolean isBase64(byte octect) {
        if (octect == PAD) {
            return true;
        } else {
            return BASE64_ALPHABET[octect] != -1;
        }
    }
}
