package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

final class SourceRuntimeSupport {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern JSON_NUMBER = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|(?<![\\w$])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private SourceRuntimeSupport() {}

    static String preserveIntegers(String json) throws IOException {
        if (json.length() > 8 * 1024 * 1024) throw new IOException("来源 JSON 内容过大");
        StringBuffer result = new StringBuffer(json.length());
        Matcher tokens = JSON_NUMBER.matcher(json);
        while (tokens.find()) {
            String value = tokens.group();
            if (value.charAt(0) != '"' && value.length() >= 16 && value.indexOf('.') < 0 && value.indexOf('e') < 0
                    && value.indexOf('E') < 0 && (value.length() > 17 || Math.abs(Double.parseDouble(value)) > 9007199254740991d))
                value = '"' + value + '"';
            tokens.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        tokens.appendTail(result);
        return result.toString();
    }

    static JSONObject htmlTree(String text, SourceModuleRuntime.Job job) throws Exception {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("来源网页内容过大");
        Node document = Jsoup.parse(text);
        JSONObject root = new JSONObject();
        Deque<HtmlEntry> stack = new ArrayDeque<>();
        stack.push(new HtmlEntry(document, root, 0));
        int nodes = 0;
        while (!stack.isEmpty()) {
            HtmlEntry entry = stack.pop();
            if (++nodes > 60000 || entry.depth >= 128) throw new IOException("来源网页结构过大");
            if ((nodes & 511) == 0) job.check();
            Node node = entry.node;
            if (node instanceof TextNode) {
                entry.value.put("text", ((TextNode)node).getWholeText());
                continue;
            }
            if (node instanceof DataNode) {
                entry.value.put("text", ((DataNode)node).getWholeData());
                continue;
            }
            if (node instanceof Element && node != document) {
                entry.value.put("tag", ((Element)node).normalName());
                JSONObject attrs = new JSONObject();
                for (Attribute field : node.attributes()) {
                    String key = field.getKey();
                    if (!key.equals("__proto__") && !key.equals("constructor") && !key.equals("prototype"))
                        attrs.put(key,field.getValue());
                }
                if (attrs.length() > 0) entry.value.put("attrs",attrs);
            }
            JSONArray children = new JSONArray();
            for (Node child : node.childNodes()) {
                if (!(child instanceof Element || child instanceof TextNode || child instanceof DataNode)) continue;
                JSONObject value = new JSONObject();
                children.put(value);
                stack.addLast(new HtmlEntry(child,value,entry.depth+1));
            }
            if (children.length() > 0) entry.value.put("children",children);
        }
        return root;
    }

    private static final class HtmlEntry {
        final Node node;
        final JSONObject value;
        final int depth;
        HtmlEntry(Node node, JSONObject value, int depth) { this.node=node; this.value=value; this.depth=depth; }
    }

    static String hex(byte[] value) {
        char[] out = new char[value.length*2];
        for (int i=0; i<value.length; i++) {
            out[i*2]=HEX[(value[i] & 255) >>> 4];
            out[i*2+1]=HEX[value[i] & 15];
        }
        return new String(out);
    }

    static byte[] unhex(String input) throws IOException {
        if (input.length() > MAX_BYTES*2 || (input.length()&1)!=0) throw new IOException("来源十六进制数据无效");
        byte[] out=new byte[input.length()/2];
        for (int i=0; i<out.length; i++) {
            int hi=Character.digit(input.charAt(i*2),16), lo=Character.digit(input.charAt(i*2+1),16);
            if (hi<0 || lo<0) throw new IOException("来源十六进制数据无效");
            out[i]=(byte)(hi*16+lo);
        }
        return out;
    }

    static String base64(byte[] value) { return ByteString.of(value).base64(); }
    static byte[] unbase64(String input) throws IOException {
        if (input.length()>MAX_BYTES*2 || input.length()%4==1 || !input.matches("[A-Za-z0-9+/]*={0,2}"))
            throw new IOException("来源 Base64 数据无效");
        ByteString decoded = ByteString.decodeBase64(input);
        if (decoded==null) throw new IOException("来源 Base64 数据无效");
        return decoded.toByteArray();
    }

    static Object typed(Context cx, Scriptable scope, byte[] bytes) throws IOException {
        if (bytes.length>MAX_BYTES) throw new IOException("来源二进制数据过大");
        Scriptable out = cx.newObject(scope,"Uint8Array",new Object[]{bytes.length});
        for (int at=0; at<bytes.length; at++) out.put(at,out,bytes[at]&255);
        return out;
    }

    static byte[] bytes(Object value) throws IOException {
        if (value instanceof CharSequence) {
            byte[] text=value.toString().getBytes(StandardCharsets.UTF_8);
            if (text.length>MAX_BYTES) throw new IOException("来源二进制数据过大");
            return text;
        }
        if (!(value instanceof Scriptable)) throw new IOException("来源二进制参数无效");
        Scriptable array=(Scriptable)value;
        int length=intValue(ScriptableObject.getProperty(array,"length"),MAX_BYTES);
        byte[] out=new byte[length];
        for (int at=0; at<out.length; at++) out[at]=(byte)intValue(ScriptableObject.getProperty(array,at),255);
        return out;
    }

    static int intValue(Object value, int max) throws IOException {
        if (!(value instanceof Number)) throw new IOException("来源整数参数无效");
        double number=((Number)value).doubleValue();
        if (!Double.isFinite(number) || number<0 || number>max || number!=(int)number) throw new IOException("来源整数参数无效");
        return (int)number;
    }

    static byte[] random(int length) {
        byte[] out=new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    static byte[] digest(String method, byte[] value) throws Exception { return MessageDigest.getInstance(method).digest(value); }
    static byte[] cbc(boolean encrypt, byte[] key, byte[] iv, byte[] data) throws Exception {
        if (iv.length!=16) throw new IOException("来源加密参数无效");
        Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }
    static byte[] gcm(boolean encrypt, byte[] key, byte[] iv, byte[] data, byte[] aad) throws Exception {
        if (iv.length!=12) throw new IOException("来源加密参数无效");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        if (aad.length>0) cipher.updateAAD(aad);
        return cipher.doFinal(data);
    }
    static byte[] fixed(BigInteger value) {
        byte[] raw=value.toByteArray();
        byte[] normalized=new byte[32];
        int length=Math.min(raw.length,32);
        System.arraycopy(raw,raw.length-length,normalized,32-length,length);
        return normalized;
    }
    static byte[][] ecdhPair() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"),RANDOM);
        KeyPair pair=generator.generateKeyPair();
        ECPoint point=((ECPublicKey)pair.getPublic()).getW();
        byte[] pub=new byte[65];
        pub[0]=4;
        System.arraycopy(fixed(point.getAffineX()),0,pub,1,32);
        System.arraycopy(fixed(point.getAffineY()),0,pub,33,32);
        return new byte[][]{fixed(((ECPrivateKey)pair.getPrivate()).getS()),pub};
    }
    static byte[] ecdh(byte[] prv, byte[] pub) throws Exception {
        if (prv.length!=32 || pub.length!=65 || pub[0]!=4) throw new IOException("来源握手参数无效");
        AlgorithmParameters parameters=AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ec=parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint point=new ECPoint(new BigInteger(1,Arrays.copyOfRange(pub,1,33)),new BigInteger(1,Arrays.copyOfRange(pub,33,65)));
        KeyFactory keys=KeyFactory.getInstance("EC");
        KeyAgreement agreement=KeyAgreement.getInstance("ECDH");
        agreement.init(keys.generatePrivate(new ECPrivateKeySpec(new BigInteger(1,prv),ec)));
        agreement.doPhase(keys.generatePublic(new ECPublicKeySpec(point,ec)),true);
        return agreement.generateSecret();
    }
    static byte[] hkdf(byte[] secret, byte[] salt, byte[] info, int length) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length==0?new byte[32]:salt,"HmacSHA256"));
        byte[] prk=mac.doFinal(secret), last=new byte[0], out=new byte[length];
        mac.init(new SecretKeySpec(prk,"HmacSHA256"));
        for (int offset=0,counter=1; offset<length; counter++) {
            mac.update(last);
            mac.update(info);
            last=mac.doFinal(new byte[]{(byte)counter});
            int count=Math.min(last.length,length-offset);
            System.arraycopy(last,0,out,offset,count);
            offset+=count;
        }
        return out;
    }
    static String origin(URI url) {
        String value="https://"+url.getHost().toLowerCase(java.util.Locale.ROOT);
        return url.getPort()>0 && url.getPort()!=443?value+":"+url.getPort():value;
    }
}
