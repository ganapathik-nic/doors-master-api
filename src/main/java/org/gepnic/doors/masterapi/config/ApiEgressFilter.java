package org.gepnic.doors.masterapi.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Counts written bytes without buffering documents or recording payloads. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@RequiredArgsConstructor
@Slf4j
public class ApiEgressFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || !MachineApiRoutes.matches(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CountingResponse wrapped = new CountingResponse(response);
        long started = System.nanoTime();
        AtomicBoolean recorded = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean(true);
        Runnable persist = () -> {
            if (!recorded.compareAndSet(false, true)) return;
            Object client = request.getAttribute(ApiSubscriptionInterceptor.CLIENT_ATTRIBUTE);
            if (!(client instanceof Number)) return; // Unidentified requests cannot be attributed to a subscriber.
            Object rows = request.getAttribute("TOTAL_RECORD_COUNT");
            try {
                jdbc.update("""
                        INSERT INTO api_egress_events(client_id, endpoint, status_code, response_bytes,
                          record_count, duration_ms, transfer_complete) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, client, request.getRequestURI(), wrapped.getStatus(), wrapped.count.get(),
                        rows instanceof Number n ? n.longValue() : 0L, (System.nanoTime()-started)/1_000_000,
                        completed.get());
            } catch (RuntimeException ex) {
                log.error("API egress accounting failed for client {}", client, ex);
            }
        };
        AsyncListener listener = new AsyncListener() {
            public void onComplete(AsyncEvent e) { wrapped.flushWriter(); persist.run(); }
            public void onTimeout(AsyncEvent e) { completed.set(false); }
            public void onError(AsyncEvent e) { completed.set(false); }
            public void onStartAsync(AsyncEvent e) { e.getAsyncContext().addListener(this); }
        };
        // Register at startAsync, before a fast streaming worker can complete or fail.
        HttpServletRequestWrapper countedRequest = new HttpServletRequestWrapper(request) {
            @Override public AsyncContext startAsync() { return startAsync(this, wrapped); }
            @Override public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
                AsyncContext context = super.startAsync(req, res);
                context.addListener(listener);
                return context;
            }
        };
        try {
            chain.doFilter(countedRequest, wrapped);
        } catch (IOException | ServletException | RuntimeException ex) {
            completed.set(false);
            throw ex;
        } finally {
            if (!request.isAsyncStarted()) {
                wrapped.flushWriter();
                persist.run();
            }
        }
    }

    static class CountingResponse extends HttpServletResponseWrapper {
        final AtomicLong count = new AtomicLong();
        private ServletOutputStream stream;
        private PrintWriter writer;
        CountingResponse(HttpServletResponse response) { super(response); }
        private ServletOutputStream countedStream() throws IOException {
            if (stream == null) {
                ServletOutputStream delegate = super.getOutputStream();
                stream = new ServletOutputStream() {
                    public void write(int b) throws IOException { delegate.write(b); count.incrementAndGet(); }
                    public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); count.addAndGet(len); }
                    public void flush() throws IOException { delegate.flush(); }
                    public void close() throws IOException { delegate.close(); }
                    public boolean isReady() { return delegate.isReady(); }
                    public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                };
            }
            return stream;
        }
        @Override public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) throw new IllegalStateException("Writer already obtained");
            return countedStream();
        }
        @Override public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                if (stream != null) throw new IllegalStateException("Output stream already obtained");
                writer = new PrintWriter(new OutputStreamWriter(countedStream(), getCharacterEncoding()));
            }
            return writer;
        }
        void flushWriter() { if (writer != null) writer.flush(); }
        @Override public void flushBuffer() throws IOException { flushWriter(); super.flushBuffer(); }
        @Override public void resetBuffer() { super.resetBuffer(); count.set(0); }
        @Override public void reset() { super.reset(); count.set(0); writer=null; stream=null; }
    }
}
