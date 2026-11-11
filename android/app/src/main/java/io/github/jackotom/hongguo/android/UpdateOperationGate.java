package io.github.jackotom.hongguo.android;

import java.io.IOException;

/** Serializes exclusive update/deletion work against source preparation and manual downloads. */
final class UpdateOperationGate {
    private int operations;
    private Object installOwner;
    synchronized Lease operation() throws IOException {
        if (installOwner != null) throw new IOException("正在处理更新或本地下载文件，请稍后再播放或下载");
        operations++;
        return new Lease();
    }
    synchronized void install(Object owner) throws IOException {
        if (owner == null) throw new IllegalArgumentException("installation owner is required");
        if (installOwner != null || operations != 0) throw new IOException("请等待当前播放准备或下载任务完成后重试");
        installOwner = owner;
    }
    synchronized void release(Object owner) { if (installOwner == owner) installOwner = null; }
    final class Lease implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            synchronized (UpdateOperationGate.this) {
                if (!closed) { closed = true; operations--; }
            }
        }
    }
}
