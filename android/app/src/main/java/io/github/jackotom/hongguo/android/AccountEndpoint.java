package io.github.jackotom.hongguo.android;

import java.net.URI;
import java.util.Locale;

/** Independent account origin parser; never changes the media network policy. */
final class AccountEndpoint {
    static String normalize(String value) {
        try {
            if(value==null||value.length()>2048||!value.equals(value.trim())||value.indexOf('\\')>=0)throw new Exception();
            URI uri=new URI(value);String host=uri.getHost();int port=uri.getPort();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||host.isEmpty()||uri.getRawAuthority().endsWith(":")||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||port==0||port>65535||port< -1||!(uri.getRawPath().isEmpty()||"/".equals(uri.getRawPath())))throw new Exception();
            return new URI("https",null,host.toLowerCase(Locale.ROOT),port==443?-1:port,null,null,null).toASCIIString();
        } catch(Exception error) { throw new IllegalArgumentException("服务器地址请填写有效的 HTTPS 根地址（可带端口）"); }
    }
}
