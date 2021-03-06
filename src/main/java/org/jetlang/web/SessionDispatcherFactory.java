package org.jetlang.web;

import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.PoolFiberFactory;

import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.function.Supplier;

public interface SessionDispatcherFactory<S> {

    SessionDispatcher<S> createOnNewSession(S session, HttpRequest headers);

    class OnReadThreadDispatcher<S> implements SessionDispatcherFactory<S> {

        @Override
        public SessionDispatcher<S> createOnNewSession(S session, HttpRequest headers) {
            return new OnReadThread<>();
        }
    }

    class OnReadThread<S> implements SessionDispatcher<S> {

        @Override
        public <T> WebSocketHandler<S, T> createOnNewSession(WebSocketHandler<S, T> handler, HttpRequest headers, S sessionState) {
            return handler;
        }

        @Override
        public NioReader.State dispatch(HttpHandler<S> handler, HttpRequest headers, HttpResponse response, HeaderReader<S> headerReader, NioWriter writer, S sessionState) {
            handler.handle(headerReader.getReadFiber(), headers, response, sessionState);
            return headerReader.start();
        }

        @Override
        public void onClose(S session) {
        }
    }

    interface SessionDispatcher<S> {
        <T> WebSocketHandler<S, T> createOnNewSession(WebSocketHandler<S, T> handler, HttpRequest headers, S sessionState);

        NioReader.State dispatch(HttpHandler<S> handler, HttpRequest headers, HttpResponse response, HeaderReader<S> headerReader, NioWriter writer, S sessionState);

        void onClose(S session);
    }

    class FiberSessionFactory<S> implements SessionDispatcherFactory<S> {
        private final Supplier<Fiber> fiberFactory;
        private final boolean useForWebsocket;
        private final boolean useForHttp;

        public FiberSessionFactory(Supplier<Fiber> fiberFactory, boolean useForWebsockets, boolean useForHttp) {
            this.fiberFactory = fiberFactory;
            this.useForWebsocket = useForWebsockets;
            this.useForHttp = useForHttp;
        }

        public FiberSessionFactory(PoolFiberFactory poolFiberFactory, boolean useForWebsockets, boolean useForHttp) {
            this(poolFiberFactory::create, useForWebsockets, useForHttp);
        }

        @Override
        public SessionDispatcher<S> createOnNewSession(S session, HttpRequest headers) {
            Fiber fiber = fiberFactory.get();
            fiber.start();
            return new FiberSession<S>(fiber, useForHttp, useForWebsocket);
        }
    }

    class FiberSession<S> implements SessionDispatcher<S> {

        private final Fiber fiber;
        private final boolean useForHttp;
        private final boolean useForWebsocket;
        private boolean isWebsocket;
        private final OnReadThread<S> onReadThread = new OnReadThread<>();

        public FiberSession(Fiber fiber, boolean useForHttp, boolean useForWebsocket) {
            this.fiber = fiber;
            this.useForHttp = useForHttp;
            this.useForWebsocket = useForWebsocket;
        }

        @Override
        public <T> WebSocketHandler<S, T> createOnNewSession(WebSocketHandler<S, T> handler, HttpRequest headers, S sessionState) {
            if (!useForWebsocket) {
                return onReadThread.createOnNewSession(handler, headers, sessionState);
            }
            isWebsocket = true;
            return new WebSocketHandler<S, T>() {
                private WebFiberConnection fiberConn;
                private T threadState;

                @Override
                public T onOpen(WebSocketConnection connection, HttpRequest headers, S sessionState) {
                    fiberConn = new WebFiberConnection(fiber, connection);
                    fiber.execute(() -> {
                        threadState = handler.onOpen(fiberConn, headers, sessionState);
                    });
                    return null;
                }

                @Override
                public void onPing(WebSocketConnection connection, T state, byte[] result, int size, StringDecoder charset) {
                    final byte[] copy = Arrays.copyOf(result, size);
                    fiber.execute(() -> {
                        handler.onPing(fiberConn, threadState, copy, size, charset);
                    });
                }

                @Override
                public void onPong(WebSocketConnection connection, T state, byte[] result, int size) {
                    final byte[] copy = Arrays.copyOf(result, size);
                    fiber.execute(() -> {
                        handler.onPong(fiberConn, threadState, copy, size);
                    });
                }

                @Override
                public void onMessage(WebSocketConnection connection, T state, String msg) {
                    fiber.execute(() -> {
                        handler.onMessage(fiberConn, threadState, msg);
                    });
                }

                @Override
                public void onClose(WebSocketConnection connection, T state) {
                    fiber.execute(() -> {
                        handler.onClose(fiberConn, threadState);
                        fiber.dispose();
                    });
                }

                @Override
                public void onError(WebSocketConnection connection, T state, String msg) {
                    fiber.execute(() -> {
                        handler.onError(fiberConn, threadState, msg);
                    });
                }

                @Override
                public void onException(WebSocketConnection connection, T state, Exception failed) {
                    fiber.execute(() -> {
                        handler.onException(fiberConn, threadState, failed);
                    });
                }

                @Override
                public void onBinaryMessage(WebSocketConnection connection, T state, byte[] result, int size) {
                    final byte[] copy = Arrays.copyOf(result, size);
                    fiber.execute(() -> {
                        handler.onBinaryMessage(fiberConn, threadState, copy, size);
                    });
                }

                @Override
                public void onUnknownException(Throwable processingException, SocketChannel channel) {
                    fiber.execute(() -> {
                        handler.onUnknownException(processingException, channel);
                    });
                }
            };
        }

        @Override
        public NioReader.State dispatch(HttpHandler<S> handler, HttpRequest headers, HttpResponse response, HeaderReader<S> headerReader, NioWriter writer, S sessionState) {
            if (useForHttp) {
                fiber.execute(() -> {
                    handler.handle(fiber, headers, response, sessionState);
                });
                return headerReader.start();
            } else {
                return onReadThread.dispatch(handler, headers, response, headerReader, writer, sessionState);
            }
        }

        @Override
        public void onClose(S session) {
            if (!isWebsocket) {
                fiber.dispose();
            }
        }
    }
}
