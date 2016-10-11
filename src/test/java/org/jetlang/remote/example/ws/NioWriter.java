package org.jetlang.remote.example.ws;

import org.jetlang.fibers.NioControls;
import org.jetlang.fibers.NioFiber;
import org.jetlang.fibers.NioFiberImpl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

public class NioWriter {

    private final SocketChannel channel;
    private final NioFiber fiber;
    private final Object writeLock = new Object();
    private NioFiberImpl.BufferedWrite<SocketChannel> bufferedWrite;

    public NioWriter(SocketChannel channel, NioFiber fiber) {
        this.channel = channel;
        this.fiber = fiber;
    }

    public SendResult send(ByteBuffer bb) {
        synchronized (writeLock) {
            if (bufferedWrite != null) {
                if (channel.isOpen() && channel.isRegistered()) {
                    int toBuffer = bb.remaining();
                    bufferedWrite.buffer(bb);
                    return new SendResult.Buffered(toBuffer, toBuffer);
                } else {
                    return SendResult.Closed;
                }
            }
            try {
                writeAll(channel, bb);
            } catch (IOException e) {
                fiber.execute((c) -> c.close(channel));
                return new SendResult.FailedOnError(e);
            }
            if (!bb.hasRemaining()) {
                //System.out.println("sent : " + bytes.length);
                return SendResult.SUCCESS;
            }
            bufferedWrite = new NioFiberImpl.BufferedWrite<SocketChannel>(channel, new NioFiberImpl.WriteFailure() {
                @Override
                public <T extends SelectableChannel & WritableByteChannel> void onFailure(IOException e, T t, ByteBuffer byteBuffer) {
                }
            }, new NioFiberImpl.OnBuffer() {
                @Override
                public <T extends SelectableChannel & WritableByteChannel> void onBufferEnd(T t) {
                    bufferedWrite = null;
                }

                @Override
                public <T extends SelectableChannel & WritableByteChannel> void onBuffer(T t, ByteBuffer byteBuffer) {
                }
            }) {
                @Override
                public boolean onSelect(NioFiber nioFiber, NioControls controls, SelectionKey key) {
                    synchronized (writeLock) {
                        return super.onSelect(nioFiber, controls, key);
                    }
                }

                @Override
                public void onEnd() {
                }
            };
            int remaining = bb.remaining();
            bufferedWrite.buffer(bb);
            fiber.execute((c) -> {
                if (channel.isOpen() && channel.isRegistered()) {
                    c.addHandler(bufferedWrite);
                }
            });
            return new SendResult.Buffered(remaining, remaining);
        }
    }

    public static void writeAll(WritableByteChannel channel, ByteBuffer data) throws IOException {
        int write;
        do {
            write = channel.write(data);
        } while (write != 0 && data.remaining() > 0);
    }


    public void close() throws IOException {
        channel.close();
    }
}