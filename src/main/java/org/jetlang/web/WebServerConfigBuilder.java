package org.jetlang.web;

import org.jetlang.fibers.NioFiber;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class WebServerConfigBuilder<S> {

    private final SessionFactory<S> factory;
    private Charset websocketCharset = Charset.forName("UTF-8");
    private List<Consumer<Map<String, Handler<S>>>> events = new ArrayList<>();
    private int readBufferSizeInBytes = 1024;
    private int maxReadLoops = 50;
    private RequestDecorator<S> decorator = new RequestDecorator<S>() {
        @Override
        public HttpRequestHandler<S> decorate(HttpRequestHandler<S> handler) {
            return handler;
        }
    };
    private Handler<S> defaultHandler = new HttpHandler<S>() {
        @Override
        public void handle(NioFiber readFiber, HttpRequest headers, HttpResponseWriter writer, S sessionState) {
            writer.sendResponse(404, "Not Found", "text/plain", headers.getRequestUri() + " Not Found", HeaderReader.ascii);
        }
    };

    private SessionDispatcherFactory<S> dispatcher = new SessionDispatcherFactory.OnReadThreadDispatcher<S>();

    public WebServerConfigBuilder(SessionFactory<S> factory) {
        this.factory = factory;
    }

    public SessionDispatcherFactory<S> getDispatcher() {
        return dispatcher;
    }

    public void setDispatcher(SessionDispatcherFactory<S> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public RequestDecorator<S> getDecorator() {
        return decorator;
    }

    public void setDecorator(RequestDecorator<S> decorator) {
        this.decorator = decorator;
    }

    public Handler<S> getDefaultHandler() {
        return defaultHandler;
    }

    public void setDefaultHandler(Handler<S> defaultHandler) {
        this.defaultHandler = defaultHandler;
    }

    public int getMaxReadLoops() {
        return maxReadLoops;
    }

    public void setMaxReadLoops(int maxReadLoops) {
        this.maxReadLoops = maxReadLoops;
    }

    public int getReadBufferSizeInBytes() {
        return readBufferSizeInBytes;
    }

    public void setReadBufferSizeInBytes(int readBufferSizeInBytes) {
        this.readBufferSizeInBytes = readBufferSizeInBytes;
    }

    public Charset getWebsocketCharset() {
        return websocketCharset;
    }

    public WebServerConfigBuilder setWebsocketCharset(Charset websocketCharset) {
        this.websocketCharset = websocketCharset;
        return this;
    }

    public <T> WebServerConfigBuilder<S> add(String path, WebSocketHandler<S, T> handler) {
        return add(path, handler, WebSocketSecurity.none());
    }

    public <T> WebServerConfigBuilder<S> add(String path, WebSocketHandler<S, T> handler, WebSocketSecurity<S> security) {
        events.add((map) -> {
            map.put(path, new WebSocketRequestHandler<>(websocketCharset, handler, security));
        });
        return this;
    }


    public WebServerConfigBuilder<S> add(String path, HttpHandler<S> rs) {
        events.add((map) -> {
            map.put(path, rs);
        });
        return this;
    }

    public interface RequestDecorator<S> {

        HttpRequestHandler<S> decorate(HttpRequestHandler<S> handler);
    }

    public WebDispatcher<S> create(NioFiber readFiber) {
        Map<String, Handler<S>> handlerMap = new HashMap<>();
        for (Consumer<Map<String, Handler<S>>> event : events) {
            event.accept(handlerMap);
        }
        HttpRequestHandler<S> handler = decorator.decorate(createHandler(handlerMap));
        return new WebDispatcher<>(readFiber, handler, readBufferSizeInBytes, maxReadLoops, factory, dispatcher);
    }

    protected HttpRequestHandler<S> createHandler(final Map<String, Handler<S>> handlerMap) {
        return new HttpRequestHandler.Default<>(handlerMap, defaultHandler);
    }
}
