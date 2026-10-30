package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.math.BigInteger;
import org.json.*;

/** Strict, bounded JSON reader; large integer identities remain decimal strings, never rounded doubles. */
final class PassportJson {
    private final String input;private int at,values;
    private PassportJson(String input){this.input=input;}
    static JSONObject parse(byte[]data)throws IOException{
        if(data==null||data.length==0||data.length>OfficialApiClient.MAX_BYTES)throw new IOException("红果账号响应为空或过大");
        try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();PassportJson parser=new PassportJson(text);Object value=parser.value(0);parser.space();if(parser.at!=text.length()||!(value instanceof JSONObject))throw new IOException();return(JSONObject)value;}catch(Exception invalid){if(invalid instanceof java.io.InterruptedIOException)throw(java.io.InterruptedIOException)invalid;throw new IOException("红果账号响应不是有效JSON对象");}
    }
    private void space(){while(at<input.length()&&" \r\n\t".indexOf(input.charAt(at))>=0)at++;}
    private Object value(int depth)throws Exception{
        if(depth>64||++values>100000)throw new IOException();if((values&63)==0)PassportSigner.check();space();if(at>=input.length())throw new IOException();char c=input.charAt(at);
        if(c=='{'){at++;JSONObject out=new JSONObject();space();if(take('}'))return out;for(;;){space();if(at>=input.length()||input.charAt(at)!='"')throw new IOException();String key=string();if(key.length()>4096||out.has(key))throw new IOException();space();expect(':');out.put(key,value(depth+1));space();if(take('}'))return out;expect(',');}}
        if(c=='['){at++;JSONArray out=new JSONArray();space();if(take(']'))return out;for(;;){out.put(value(depth+1));space();if(take(']'))return out;expect(',');}}
        if(c=='"')return string();if(c=='t'&&literal("true"))return Boolean.TRUE;if(c=='f'&&literal("false"))return Boolean.FALSE;if(c=='n'&&literal("null"))return JSONObject.NULL;
        if(c=='-'||c>='0'&&c<='9')return number();throw new IOException();
    }
    private boolean literal(String value){if(input.startsWith(value,at)){at+=value.length();return true;}return false;}
    private String string()throws Exception{
        expect('"');StringBuilder out=new StringBuilder();while(at<input.length()){char c=input.charAt(at++);if(c=='"')return out.toString();if(c<32)throw new IOException();if(c=='\\'){if(at>=input.length())throw new IOException();c=input.charAt(at++);switch(c){case'"':case'\\':case'/':out.append(c);break;case'b':out.append('\b');break;case'f':out.append('\f');break;case'n':out.append('\n');break;case'r':out.append('\r');break;case't':out.append('\t');break;case'u':char u=unicode();if(Character.isHighSurrogate(u)){if(at+2>input.length()||input.charAt(at++)!='\\'||input.charAt(at++)!='u')throw new IOException();char low=unicode();if(!Character.isLowSurrogate(low))throw new IOException();out.append(u).append(low);}else if(Character.isLowSurrogate(u))throw new IOException();else out.append(u);break;default:throw new IOException();}}else out.append(c);if(out.length()>1024*1024)throw new IOException();}throw new IOException();
    }
    private char unicode()throws Exception{if(at+4>input.length())throw new IOException();String s=input.substring(at,at+4);if(!s.matches("[0-9a-fA-F]{4}"))throw new IOException();at+=4;return(char)Integer.parseInt(s,16);}
    private Object number()throws Exception{
        int start=at;if(take('-')&&at>=input.length())throw new IOException();if(take('0')){if(at<input.length()&&Character.isDigit(input.charAt(at)))throw new IOException();}else{if(at>=input.length()||input.charAt(at)<'1'||input.charAt(at)>'9')throw new IOException();digits();}
        boolean integer=true;if(take('.')){integer=false;int before=at;digits();if(at==before)throw new IOException();}if(at<input.length()&&(input.charAt(at)=='e'||input.charAt(at)=='E')){integer=false;at++;if(at<input.length()&&(input.charAt(at)=='+'||input.charAt(at)=='-'))at++;int before=at;digits();if(at==before)throw new IOException();}
        String raw=input.substring(start,at);if(raw.length()>128)throw new IOException();if(integer){BigInteger value=new BigInteger(raw);if(value.abs().compareTo(BigInteger.valueOf(9007199254740991L))>0)return raw;return value.longValue();}double value=Double.parseDouble(raw);if(!Double.isFinite(value))throw new IOException();return value;
    }
    private void digits(){while(at<input.length()&&input.charAt(at)>='0'&&input.charAt(at)<='9')at++;}private boolean take(char c){if(at<input.length()&&input.charAt(at)==c){at++;return true;}return false;}private void expect(char c)throws Exception{if(!take(c))throw new IOException();}
}
