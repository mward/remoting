package org.jetlang.web;

import org.jetlang.core.Disposable;
import org.jetlang.fibers.NioFiber;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class WebSocketConnectionImpl implements WebSocketConnection {

    public static final byte OPCODE_CONT = 0x0;
    public static final byte OPCODE_TEXT = 0x1;
    public static final byte OPCODE_BINARY = 0x2;
    public static final byte OPCODE_CLOSE = 0x8;
    public static final byte OPCODE_PING = 0x9;
    public static final byte OPCODE_PONG = 0xA;
    public static final byte[] empty = new byte[0];
    private static final Charset charset = Charset.forName("UTF-8");
    private final NioWriter writer;
    private final byte[] maskingBytes;
    private NioFiber readFiber;
    private HttpRequest request;
    private static final SizeType[] sizes = SizeType.values();
    private boolean closed;
    private final List<Disposable> disposables = new ArrayList<>();

    public WebSocketConnectionImpl(NioWriter writer, byte[] maskingBytes, NioFiber readFiber, HttpRequest request) {
        this.writer = writer;
        this.maskingBytes = maskingBytes;
        this.readFiber = readFiber;
        this.request = request;
    }

    @Override
    public HttpRequest getRequest() {
        return request;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return writer.getRemoteAddress();
    }

    @Override
    public SendResult send(String msg) {
        final byte[] bytes = msg.getBytes(charset);
        return sendText(bytes, 0, bytes.length);
    }

    @Override
    public SendResult sendText(byte[] bytes, int offset, int length) {
        return send(OPCODE_TEXT, bytes, offset, length);
    }

    @Override
    public SendResult sendPong(byte[] bytes, int offset, int length) {
        return send(OPCODE_PONG, bytes, offset, length);
    }

    @Override
    public SendResult sendPing(byte[] bytes, int offset, int length) {
        return send(OPCODE_PING, bytes, offset, length);
    }

    void onClose() {
        synchronized (disposables) {
            closed = true;
            //clone to prevent concurrent mod
            new ArrayList<>(disposables).forEach(Disposable::dispose);
        }
    }


    enum SizeType {
        Small(125, 1) {
            @Override
            void write(ByteBuffer bb, int length, boolean mask) {
                bb.put(setMask((byte) length, mask));
            }
        },
        Medium(65535, 3) {
            @Override
            void write(ByteBuffer bb, int length, boolean mask) {
                bb.put(setMask((byte) 126, mask));
                bb.put((byte) (length >>> 8));
                bb.put((byte) length);
            }
        },
        Large(Integer.MAX_VALUE, 9) {
            @Override
            void write(ByteBuffer bb, int length, boolean mask) {
                bb.put(setMask((byte) 127, mask));
                bb.putLong(length);
            }
        };

        public int max;
        final int bytes;

        SizeType(int max, int bytes) {
            this.max = max;
            this.bytes = bytes;
        }

        private static byte setMask(byte b, boolean mask) {
            if (mask) {
                b |= 1 << 7;
            }
            return b;
        }

        abstract void write(ByteBuffer bb, int length, boolean mask);
    }

    @Override
    public SendResult sendBinary(byte[] buffer, int offset, int length) {
        return send(OPCODE_BINARY, buffer, offset, length);
    }

    private SendResult send(byte opCode, byte[] bytes, int offset, int length) {
        return writer.sendWsMsg(opCode, bytes, offset, length, maskingBytes);
    }

    public static SizeType findSize(int length) {
        for (SizeType size : sizes) {
            if (length <= size.max) {
                return size;
            }
        }
        throw new RuntimeException(length + " invalid ");
    }

    void sendClose() {
        send(OPCODE_CLOSE, empty, 0, 0);
    }

    public void closeChannelOnReadThread() {
        writer.close();
    }

    @Override
    public void close() {
        writer.close();
        readFiber.execute(nioControls -> {
            nioControls.close(writer.getChannel());
        });
    }

    @Override
    public Disposable schedule(Runnable runnable, long l, TimeUnit timeUnit) {
        return readFiber.schedule(runIfActive(runnable), l, timeUnit);
    }

    private Runnable runIfActive(Runnable runnable) {
        return () -> {
            if (!closed) {
                runnable.run();
            }
        };
    }

    @Override
    public Disposable scheduleWithFixedDelay(Runnable runnable, long initialDelay, long delay, TimeUnit timeUnit) {
        return register(readFiber.scheduleWithFixedDelay(runIfActive(runnable), initialDelay, delay, timeUnit));
    }

    private Disposable register(Disposable disposable) {
        add(disposable);
        return () -> {
            disposable.dispose();
            remove(disposable);
        };
    }

    @Override
    public Disposable scheduleAtFixedRate(Runnable runnable, long initialDelay, long delay, TimeUnit timeUnit) {
        return register(readFiber.scheduleAtFixedRate(runIfActive(runnable), initialDelay, delay, timeUnit));
    }

    @Override
    public void add(Disposable disposable) {
        synchronized (disposables) {
            if (!closed) {
                disposables.add(disposable);
            } else {
                disposable.dispose();
            }
        }
    }

    @Override
    public boolean remove(Disposable disposable) {
        synchronized (disposables) {
            return disposables.remove(disposable);
        }
    }

    @Override
    public int size() {
        synchronized (disposables) {
            return disposables.size();
        }
    }

    @Override
    public void execute(Runnable command) {
        readFiber.execute(() -> {
            if (!closed) {
                command.run();
            }
        });
    }

    @Override
    public void dispose() {
        close();
    }
}
