package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Synchronous AVTransport control; call only from the cast-session worker. */
public final class DlnaControl {
    private static final String SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    private final DlnaDevice device; private final DlnaNetwork.Transport transport;
    // Fixed bounded receipt/failure fields; no media URI, title, token or response body is retained.
    private String setMediaReceipt="尚未请求", playReceipt="尚未请求", lastFailureAction="", lastFailureCode="", lastFailureReason="";
    private int lastFailureHttp;
    private String startupFailure="无", lastFailureExchange="无", exchange="未取得", exchangeAction="";
    private final String endpointSummary;
    private static String fingerprint(byte[] data) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder value=new StringBuilder(12);
            for(int i=0;i<6;i++)value.append(String.format(Locale.ROOT,"%02x",hash[i]&255));return value.toString();
        } catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String responseType(byte[] body) {
        if(body.length==0)return "EMPTY";
        int start=body.length>=3&&(body[0]&255)==239&&(body[1]&255)==187&&(body[2]&255)==191?3:0;
        boolean bom=start==3;
        while(start<body.length&&(body[start]==32||body[start]==9||body[start]==13||body[start]==10))start++;
        String prefix=new String(body,start,Math.min(80,body.length-start),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if(prefix.startsWith("<!doctype html")||prefix.startsWith("<html"))return "HTML";
        if(prefix.startsWith("<"))return bom?"XML_BOM":"XML";
        for(int i=0;i<Math.min(body.length,256);i++){int b=body[i]&255;if(b==0||b<9||(b>13&&b<32))return "BINARY";}
        return "TEXT";
    }
    private synchronized void requestObserved(String action,byte[] payload) {
        exchangeAction=action;exchange="requestBytes="+payload.length+" requestHash="+fingerprint(payload)+" responseBytes=未取得 responseHash=未取得 responseType=未取得";
    }
    private synchronized void responseObserved(DlnaNetwork.Response response) {
        int boundary=exchange.indexOf(" responseBytes=");String request=boundary<0?"":exchange.substring(0,boundary);
        exchange=request+" responseBytes="+response.body.length+" responseHash="+fingerprint(response.body)+" responseType="+responseType(response.body);
    }
    public static final class ActionException extends IOException {
        public final String action, upnpCode, reason; public final int httpStatus;
        ActionException(String action,int httpStatus,String upnpCode,String reason,IOException cause) {
            super("投屏 "+action+" 未完成"+(httpStatus>0?"（HTTP "+httpStatus+"）":"")+(upnpCode.isEmpty()?"":"（UPnP "+upnpCode+"）")+"："+reason,cause);
            this.action=action;this.httpStatus=httpStatus;this.upnpCode=upnpCode;this.reason=reason;
        }
    }
    /** A command acknowledgement is not evidence that the renderer fetched or played media. */
    public synchronized String diagnostics() {
        return "SetAVTransportURI="+setMediaReceipt+"；Play="+playReceipt+"；成功应答仅表示命令已受理，不代表电视已取流或播放；最近失败="+
            (lastFailureAction.isEmpty()?"无":lastFailureAction+"，HTTP="+(lastFailureHttp>0?Integer.toString(lastFailureHttp):"未取得")+"，UPnP="+(lastFailureCode.isEmpty()?"未报告":lastFailureCode)+"，"+lastFailureReason)
            +"\n"+endpointSummary+"\n启动失败="+startupFailure+"\n最近失败交换="+lastFailureExchange;
    }
    private synchronized void requested(String action) {
        if(action.equals("SetAVTransportURI")){setMediaReceipt="请求中";playReceipt="尚未请求";startupFailure="无";}
        exchangeAction=action;exchange="未取得";
        if(action.equals("Play"))playReceipt="请求中";
    }
    private synchronized void accepted(String action) {
        if(action.equals("SetAVTransportURI"))setMediaReceipt="已受理";
        else if(action.equals("Play"))playReceipt="已受理";
    }
    private synchronized ActionException failed(ActionException failure) {
        if(failure.action.equals("SetAVTransportURI"))setMediaReceipt="失败";
        else if(failure.action.equals("Play"))playReceipt="失败";
        lastFailureAction=failure.action;lastFailureHttp=failure.httpStatus;lastFailureCode=failure.upnpCode;lastFailureReason=failure.reason;
        lastFailureExchange=failure.action.equals(exchangeAction)?exchange:"未取得";
        if(startupFailure.equals("无")&&(failure.action.equals("SetAVTransportURI")||failure.action.equals("Play")))
            startupFailure=failure.action+" HTTP="+(failure.httpStatus>0?failure.httpStatus:"未取得")+" UPnP="+(failure.upnpCode.isEmpty()?"未报告":failure.upnpCode)+" "+lastFailureExchange;
        return failure;
    }
    private static String transportReason(IOException failure) {
        // SocketTimeoutException is an InterruptedIOException, but is not user cancellation.
        if(failure instanceof SocketTimeoutException)return "请求超时";
        if(failure instanceof InterruptedIOException)return "请求中断";
        if(failure instanceof ConnectException)return "连接失败";
        if(failure instanceof SocketException)return "连接中断";
        return "请求失败";
    }
    private static String faultReason(String code) {
        // Controlled labels only: remote errorDescription can contain URLs or opaque credentials.
        switch(code){case "401":return "SOAP Fault：Invalid Action";case "402":return "SOAP Fault：Invalid Args";case "501":return "SOAP Fault：Action Failed";case "701":return "SOAP Fault：Transition not available";case "718":return "SOAP Fault：Invalid InstanceID";default:return "设备返回 SOAP Fault";}
    }
    public DlnaControl(DlnaDevice device) { this(device, DlnaNetwork.HTTP); }
    DlnaControl(DlnaDevice device, DlnaNetwork.Transport transport) {
        if (device == null || transport == null) throw new IllegalArgumentException("投屏设备未选择");
        this.device = device; this.transport = transport;
        int port=device.controlUri.getPort()<0?80:device.controlUri.getPort();String path=device.controlUri.getRawPath();
        endpointSummary="控制="+device.host+":"+port+"；AVTransport="+device.serviceType.substring(device.serviceType.lastIndexOf(':')+1)
            +"；路径指纹="+fingerprint((path==null||path.isEmpty()?"/":path).getBytes(StandardCharsets.UTF_8));
    }
    public static final class Status {
        public final String state, mediaUri; public final long positionMs, durationMs;
        Status(String state, String mediaUri, long positionMs, long durationMs) { this.state = state; this.mediaUri = mediaUri; this.positionMs = positionMs; this.durationMs = durationMs; }
    }
    public void setMedia(String url, String title) throws IOException {
        String uri = DlnaNetwork.mediaUri(url).toASCIIString();
        if (title == null || title.length() > 512) throw new IOException("投屏标题无效");
        String metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + DlnaXml.escape(title) + "</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01\">" + DlnaXml.escape(uri) + "</res></item></DIDL-Lite>";
        LinkedHashMap<String, String> args = args(); args.put("CurrentURI", uri); args.put("CurrentURIMetaData", metadata); action("SetAVTransportURI", args);
    }
    public void play() throws IOException { LinkedHashMap<String, String> args = args(); args.put("Speed", "1"); action("Play", args); }
    public void pause() throws IOException { action("Pause", args()); }
    public void stop() throws IOException { action("Stop", args()); }
    public void seek(long ms) throws IOException {
        if (ms < 0 || ms > 3599999999999L) throw new IOException("投屏定位时间无效");
        LinkedHashMap<String, String> args = args(); args.put("Unit", "REL_TIME");
        args.put("Target", String.format(Locale.ROOT, "%02d:%02d:%02d", ms / 3600000L, ms / 60000L % 60, ms / 1000L % 60)); action("Seek", args);
    }
    public Status status() throws IOException {
        DlnaXml.Node transportInfo = action("GetTransportInfo", args());
        String state;
        try { state=transportInfo.value("CurrentTransportState"); }
        catch(IOException failure){throw failed(new ActionException("GetTransportInfo",200,"","状态字段无效",failure));}
        if (!Arrays.asList("PLAYING", "STOPPED", "PAUSED_PLAYBACK", "PAUSED_RECORDING", "RECORDING", "TRANSITIONING", "NO_MEDIA_PRESENT").contains(state)) state = "UNKNOWN";
        DlnaXml.Node position = action("GetPositionInfo", args());
        try {
            String uri = position.value("TrackURI"); if (uri.length() > 4096 || uri.matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw new IOException("投屏设备返回媒体地址无效");
            return new Status(state, uri, timeMs(position.value("RelTime")), timeMs(position.value("TrackDuration")));
        } catch(IOException failure){throw failed(new ActionException("GetPositionInfo",200,"","状态字段无效",failure));}
    }
    private static LinkedHashMap<String, String> args() { LinkedHashMap<String, String> args = new LinkedHashMap<>(); args.put("InstanceID", "0"); return args; }
    private DlnaXml.Node action(String action, LinkedHashMap<String, String> args) throws IOException {
        requested(action);int httpStatus=0;String phase="请求参数无效";
        try {
            DlnaNetwork.endpoint(device.controlUri, device.host);
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"").append(SOAP).append("\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:")
                .append(action).append(" xmlns:u=\"").append(device.serviceType).append("\">");
            for (Map.Entry<String, String> arg : args.entrySet()) xml.append('<').append(arg.getKey()).append('>').append(DlnaXml.escape(arg.getValue())).append("</").append(arg.getKey()).append('>');
            xml.append("</u:").append(action).append("></s:Body></s:Envelope>");
            Map<String, String> headers = new LinkedHashMap<>(); headers.put("Content-Type", "text/xml; charset=\"utf-8\""); headers.put("SOAPAction", "\"" + device.serviceType + "#" + action + "\"");
            phase="网络请求失败";
            byte[] payload=xml.toString().getBytes(StandardCharsets.UTF_8);requestObserved(action,payload);
            DlnaNetwork.Response response = transport.exchange(device.controlUri, "POST", headers, payload, 4000);responseObserved(response);
            httpStatus=response.status;phase=response.status==200?"SOAP/XML 响应无效":"HTTP 响应异常";
            DlnaXml.Node envelope;
            try { envelope = DlnaXml.parse(response.body); }
            catch (IOException invalid) { if (response.status != 200) throw new IOException("投屏设备返回 HTTP " + response.status, invalid); throw invalid; }
            if (!envelope.name.equals("Envelope") || !envelope.ns.equals(SOAP)) throw new IOException("投屏 SOAP 响应无效");
            DlnaXml.Node body = envelope.child("Body");
            if (body == null || !body.ns.equals(SOAP) || body.children.size() != 1) throw new IOException("投屏 SOAP 内容无效");
            DlnaXml.Node value = body.children.get(0);
            if (value.name.equals("Fault")) {
                DlnaXml.Node detail = value.child("detail"); DlnaXml.Node upnp = detail == null ? null : detail.child("UPnPError");
                String code = upnp == null ? "未知" : upnp.value("errorCode"), description = upnp == null ? value.value("faultstring") : upnp.value("errorDescription");
                if (!code.matches("[0-9]{1,5}")) code = "未知";
                description = description.replaceAll("[\\x00-\\x1f\\x7f]", " "); if (description.length() > 160) description = description.substring(0, 160);
                IOException cause=new IOException("投屏设备拒绝 " + action + "（" + code + (description.isEmpty() ? "" : "：" + description) + "）");
                String reported=value.ns.equals(SOAP)&&upnp!=null&&upnp.ns.equals("urn:schemas-upnp-org:control-1-0")&&!code.equals("未知")?code:"";
                throw new ActionException(action,httpStatus,reported,faultReason(reported),cause);
            }
            if (response.status != 200 || !value.name.equals(action + "Response") || !value.ns.equals(device.serviceType)) throw new IOException("投屏设备响应不匹配（HTTP " + response.status + "）");
            accepted(action);return value;
        } catch(IOException failure) {
            if(failure instanceof ActionException)throw failed((ActionException)failure);
            throw failed(new ActionException(action,httpStatus,"",phase.equals("网络请求失败")?transportReason(failure):phase,failure));
        }
    }
    static long timeMs(String value) throws IOException {
        if (value.isEmpty() || value.equals("NOT_IMPLEMENTED")) return 0;
        if (!value.matches("[0-9]{1,6}:[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,3})?")) throw new IOException("投屏设备时间格式无效");
        String[] parts = value.split(":"); String[] seconds = parts[2].split("\\.");
        long ms = (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(seconds[0])) * 1000;
        if (seconds.length == 2) ms += Integer.parseInt((seconds[1] + "00").substring(0, 3)); return ms;
    }
}
