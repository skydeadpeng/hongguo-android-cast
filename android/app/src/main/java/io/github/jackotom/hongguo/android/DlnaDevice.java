package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.net.URI;

/** Immutable, validated same-private-peer AVTransport endpoint. */
public final class DlnaDevice {
    public final String id, name, host, serviceType;
    public final URI controlUri;
    public DlnaDevice(String id, String name, String host, URI controlUri, String serviceType) throws IOException {
        if (id == null || id.isEmpty() || id.length() > 256 || name == null || name.isEmpty() || name.length() > 256 ||
                id.matches("(?s).*[\\x00-\\x1f\\x7f].*") || name.matches("(?s).*[\\x00-\\x1f\\x7f].*") ||
                serviceType == null || !serviceType.matches("urn:schemas-upnp-org:service:AVTransport:[1-9][0-9]{0,2}")) throw new IOException("投屏设备信息无效");
        this.controlUri = DlnaNetwork.endpoint(controlUri, host);
        this.id = id; this.name = name; this.host = host; this.serviceType = serviceType;
    }
    @Override public String toString() { return name; }
}
