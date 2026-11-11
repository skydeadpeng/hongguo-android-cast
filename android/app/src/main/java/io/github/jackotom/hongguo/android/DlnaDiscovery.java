package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Bounded SSDP MediaRenderer search. No actual discovery runs until explicitly called. */
public final class DlnaDiscovery {
    static final String TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private DlnaDiscovery() { }
    public static List<DlnaDevice> discover(int timeoutMs, Consumer<DlnaDevice> onDevice, BooleanSupplier cancelled) throws IOException {
        if (cancelled != null && cancelled.getAsBoolean()) return new ArrayList<>();
        return discover(DlnaNetwork.localAddress(), timeoutMs, onDevice, cancelled);
    }
    /** Android callers supply the IPv4 of their selected TRANSPORT_WIFI network,
     * never the first private cellular/VPN interface from system enumeration. */
    public static List<DlnaDevice> discover(String localIpv4, int timeoutMs, Consumer<DlnaDevice> onDevice, BooleanSupplier cancelled) throws IOException {
        final NetworkInterface[] selected = new NetworkInterface[1];
        validateLocalIpv4(localIpv4, address -> {
            try {
                NetworkInterface network = NetworkInterface.getByInetAddress(literal(address));
                if (network == null || !network.isUp() || network.isLoopback()) return false;
                selected[0] = network; return true;
            } catch (IOException unavailable) { return false; }
        });
        if (cancelled != null && cancelled.getAsBoolean()) return new ArrayList<>();
        try (LiveScan scan = new LiveScan(localIpv4, selected[0])) { return discover(timeoutMs, onDevice, cancelled, scan, DlnaNetwork.HTTP); }
    }
    // The injected predicate represents local OS interface ownership, not an
    // HTTP/DNS authority. It permits deterministic tests without network scans.
    static void validateLocalIpv4(String address, Predicate<String> ownedActiveInterface) throws IOException {
        if (!DlnaNetwork.isPrivateIpv4(address)) throw new IOException("投屏需要所选 Wi-Fi 网络的局域网 IPv4 地址");
        if (!ownedActiveInterface.test(address)) throw new IOException("所选 Wi-Fi IPv4 地址已不属于本机活跃接口，请重新连接 Wi-Fi 后搜索");
    }
    interface Scan extends Closeable {
        void send(byte[] request) throws IOException;
        Advertisement receive(int timeoutMs) throws IOException;
    }
    static final class Advertisement {
        final byte[] data; final int length; final String peer;
        Advertisement(byte[] data, int length, String peer) { this.data = data; this.length = length; this.peer = peer; }
    }
    private static final class LiveScan implements Scan {
        final MulticastSocket socket;
        LiveScan(String local, NetworkInterface network) throws IOException {
            if (network == null) throw new IOException("投屏 Wi-Fi 接口未准备");
            // Bind the actual local address as well as the multicast interface:
            // a cellular/VPN default route must never choose another source IP.
            socket = new MulticastSocket(new InetSocketAddress(literal(local), 0));
            try { socket.setNetworkInterface(network); socket.setTimeToLive(2); }
            catch (IOException failure) { socket.close(); throw failure; }
        }
        @Override public void send(byte[] bytes) throws IOException { socket.send(new DatagramPacket(bytes, bytes.length, literal("239.255.255.250"), 1900)); }
        @Override public Advertisement receive(int timeoutMs) throws IOException {
            socket.setSoTimeout(timeoutMs); byte[] buffer = new byte[8192]; DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet); return new Advertisement(packet.getData(), packet.getLength(), packet.getAddress().getHostAddress());
        }
        @Override public void close() { socket.close(); }
    }
    static List<DlnaDevice> discover(int timeoutMs, Consumer<DlnaDevice> onDevice, BooleanSupplier cancelled, Scan scan, DlnaNetwork.Transport http) throws IOException {
        int duration = Math.max(500, Math.min(8000, timeoutMs)); long deadline = System.nanoTime() + duration * 1000000L;
        LinkedHashMap<String, DlnaDevice> devices = new LinkedHashMap<>(); Set<String> descriptions = new HashSet<>(); Map<String,Integer> attempts = new HashMap<>();
        if (cancelled != null && cancelled.getAsBoolean()) return new ArrayList<>();
        scan.send(searchRequest(Math.max(1, Math.min(3, duration / 1000)))); int received = 0, descriptionRequests = 0;
        while (System.nanoTime() < deadline && received < 128 && descriptionRequests < 24 && devices.size() < 24) {
            if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) break;
            Advertisement packet;
            try { packet = scan.receive((int) Math.max(1, Math.min(250, (deadline - System.nanoTime()) / 1000000L))); }
            catch (SocketTimeoutException idle) { continue; }
            received++;
            try {
                String peer = packet.peer; URI location = parseResponse(packet.data, packet.length, peer);
                String description = location.toASCIIString();
                int attempted = attempts.getOrDefault(description, 0);
                if (descriptions.contains(description) || attempted >= 2) continue;
                long remaining = (deadline - System.nanoTime()) / 1000000L;
                if (remaining < 100 || cancelled != null && cancelled.getAsBoolean()) break;
                // A transient description failure must not blacklist the receiver for this search.
                // Keep both a per-URL retry ceiling and the original global 24-request ceiling.
                attempts.put(description, attempted + 1); descriptionRequests++;
                DlnaNetwork.Response response = http.exchange(location, "GET", Collections.emptyMap(), new byte[0], (int) Math.min(1500, remaining));
                if (response.status != 200) continue;
                DlnaDevice device = parseDescription(response.body, location, peer);
                descriptions.add(description);
                String key = device.host + "|" + device.id;
                if (!devices.containsKey(key)) { devices.put(key, device); if (onDevice != null) onDevice.accept(device); }
            } catch (IOException | IllegalArgumentException invalidDevice) { /* Ignore malformed/unreachable untrusted announcements. */ }
        }
        return new ArrayList<>(devices.values());
    }
    private static InetAddress literal(String value) throws IOException {
        String[] parts = value.split("\\."); byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) bytes[i] = (byte) Integer.parseInt(parts[i]); return InetAddress.getByAddress(bytes);
    }
    static byte[] searchRequest(int mx) {
        return ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: " + Math.max(1, Math.min(3, mx)) + "\r\nST: " + TARGET + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }
    static URI parseResponse(byte[] bytes, int length, String peer) throws IOException {
        if (!DlnaNetwork.isPrivateIpv4(peer) || length < 20 || length > 8192 || length > bytes.length) throw new IOException("投屏发现报文无效");
        ByteArrayInputStream input = new ByteArrayInputStream(bytes, 0, length); int[] budget = {8192};
        if (!DlnaNetwork.line(input, budget).matches("HTTP/1\\.[01] 200(?: [\\x20-\\x7e]*)?")) throw new IOException("投屏发现状态无效");
        Map<String, String> headers = new HashMap<>();
        while (true) {
            String row = DlnaNetwork.line(input, budget); if (row.isEmpty()) break;
            int colon = row.indexOf(':'); if (colon < 1 || headers.size() >= 40) throw new IOException("投屏发现响应头无效");
            String key = row.substring(0, colon).toLowerCase(Locale.ROOT), value = row.substring(colon + 1).trim();
            if (!key.matches("[a-z0-9.-]{1,80}") || headers.containsKey(key)) throw new IOException("投屏发现响应头重复"); headers.put(key, value);
        }
        if (input.available() != 0 || !TARGET.equals(headers.get("st")) || !headers.containsKey("usn") || headers.get("usn").isEmpty() || headers.get("usn").length() > 256) throw new IOException("投屏发现目标不匹配");
        try { return DlnaNetwork.endpoint(new URI(headers.getOrDefault("location", "")), peer); }
        catch (URISyntaxException invalid) { throw new IOException("投屏发现地址无效", invalid); }
    }
    static DlnaDevice parseDescription(byte[] bytes, URI location, String peer) throws IOException {
        DlnaNetwork.endpoint(location, peer);
        DlnaXml.Node root = DlnaXml.parse(bytes);
        if (!root.name.equals("root") || !root.ns.equals("urn:schemas-upnp-org:device-1-0")) throw new IOException("投屏设备描述无效");
        URI base = location; String value = root.value("URLBase");
        if (!value.isEmpty()) { try { base = DlnaNetwork.endpoint(new URI(value), peer); } catch (URISyntaxException invalid) { throw new IOException("投屏设备基地址无效", invalid); } }
        DlnaXml.Node device = renderer(root.child("device")); if (device == null) throw new IOException("设备不是可用的 MediaRenderer");
        DlnaXml.Node services = device.child("serviceList"); if (services == null) throw new IOException("设备未提供 AVTransport");
        for (DlnaXml.Node service : services.children) {
            if (!service.name.equals("service")) continue;
            String type = service.value("serviceType");
            if (!type.matches("urn:schemas-upnp-org:service:AVTransport:[1-9][0-9]{0,2}")) continue;
            try {
                String control = service.value("controlURL"); if (control.isEmpty()) throw new IOException("设备控制地址缺失");
                URI uri = DlnaNetwork.endpoint(controlBase(base, peer).resolve(new URI(control)), peer);
                return new DlnaDevice(device.value("UDN"), device.value("friendlyName"), peer, uri, type);
            } catch (URISyntaxException | IllegalArgumentException invalid) { throw new IOException("投屏控制地址无效", invalid); }
        }
        throw new IOException("设备未提供 AVTransport");
    }
    /** Android URI.resolve can concatenate a relative path directly onto an empty authority path. */
    static URI controlBase(URI base, String peer) throws IOException {
        URI checked = DlnaNetwork.endpoint(base, peer);
        String path = checked.getRawPath();
        if (path == null || path.isEmpty()) {
            // Preserve the encoded query; do not rebuild raw components through an escaping constructor.
            return URI.create(checked.getScheme() + "://" + checked.getRawAuthority() + "/" +
                (checked.getRawQuery() == null ? "" : "?" + checked.getRawQuery()));
        }
        return checked;
    }
    private static DlnaXml.Node renderer(DlnaXml.Node device) throws IOException {
        if (device == null) return null;
        if (device.value("deviceType").matches("urn:schemas-upnp-org:device:MediaRenderer:[1-9][0-9]{0,2}")) return device;
        DlnaXml.Node list = device.child("deviceList");
        if (list != null) for (DlnaXml.Node child : list.children) if (child.name.equals("device")) { DlnaXml.Node found = renderer(child); if (found != null) return found; }
        return null;
    }
}
