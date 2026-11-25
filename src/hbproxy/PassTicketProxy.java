package hbproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.InitialContext;
import javax.net.ssl.SSLParameters;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Identity-propagating reverse proxy to HB.js.
 *
 * Liberty has already authenticated the caller (OIDC JWT mapped to a RACF ID). For every request this servlet
 * takes the RACF ID the CICS task runs under, generates a PassTicket for it and forwards the request (any method,
 * any path, query, body) to the HB.js base URL with Basic auth userid:passticket. The HB.js response is returned as is.
 *
 * Settings (looked up in this order: JNDI "hbproxy/NAME" (server.xml jndiEntry), system property "hbproxy.NAME",
 * environment variable HBPROXY_NAME, default):
 *   target         base URL of HB.js, scheme://host:port, no path   (REQUIRED, no default)
 *   applid         PassTicket application name                      (REQUIRED, no default: the APPLID of the CICS region, as defined to RACF PTKTDATA)
 *   allowedPrefix  comma list of path prefixes that may be forwarded (default "/" = everything)
 *   connectTimeout seconds (default 10), readTimeout seconds (default 60), maxBody bytes (default 10485760)
 *
 * GET /hbproxy/_diag shows the settings and runs identity and PassTicket steps without calling HB.js.
 * The PassTicket and the caller's Authorization and Cookie headers are never forwarded, printed or logged.
 */
@WebServlet(urlPatterns = "/*")
public class PassTicketProxy extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(PassTicketProxy.class.getName());

    private static final Set<String> SKIP_REQUEST = lower("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            "expect", "authorization", "cookie");
    private static final Set<String> SKIP_RESPONSE = lower("connection", "keep-alive", "proxy-authenticate",
            "trailer", "transfer-encoding", "upgrade", "content-length");
    private static final Set<String> METHODS = lower("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private HttpClient client;   // built in init() because of the optional tlsProtocols setting

    private String target;
    private String applid;
    private List<String> allowedPrefixes;
    private Duration connectTimeout;
    private Duration readTimeout;
    private boolean timing;
    private Semaphore gate;           // null = no limit (default)
    private long queueTimeoutMs;
    private long maxBody;

    @Override
    public void init() throws ServletException {
        try {
            target = stripTrailingSlash(required("target"));
            applid = required("applid");
        } catch (IllegalStateException e) {
            LOG.severe("hbproxy: " + e.getMessage());
            throw new UnavailableException(e.getMessage());
        }
        allowedPrefixes = Arrays.asList(setting("allowedPrefix", "/").split("\\s*,\\s*"));
        connectTimeout = Duration.ofSeconds(Long.parseLong(setting("connectTimeout", "10")));
        readTimeout = Duration.ofSeconds(Long.parseLong(setting("readTimeout", "60")));
        timing = Boolean.parseBoolean(setting("timing", "false"));
        // Optional cap on concurrent calls to HB.js (jndiEntry hbproxy/maxConcurrent, 0 or absent = no cap). Callers beyond the cap
        // wait up to hbproxy/queueTimeout seconds (default 5) for a slot, then get 503 + Retry-After.
        int maxConcurrent = Integer.parseInt(setting("maxConcurrent", "0"));
        gate = maxConcurrent > 0 ? new Semaphore(maxConcurrent, true) : null;
        queueTimeoutMs = Long.parseLong(setting("queueTimeout", "5")) * 1000L;
        HttpClient.Builder cb = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER);
        // Optional (jndiEntry hbproxy/tlsProtocols, for example TLSv1.2): limit the TLS versions offered to HB.js on 9443.
        String tls = setting("tlsProtocols", "");
        if (!tls.isEmpty()) {
            SSLParameters sp = new SSLParameters();
            sp.setProtocols(tls.split("\\s*,\\s*"));
            cb.sslParameters(sp);
        }
        client = cb.build();
        LOG.info("hbproxy: maxConcurrent=" + maxConcurrent + " queueTimeout=" + queueTimeoutMs / 1000 + "s tlsProtocols="
                + (tls.isEmpty() ? "(JVM default)" : tls));
        maxBody = Long.parseLong(setting("maxBody", "10485760"));
        LOG.info("hbproxy: target=" + target + " applid=" + applid + " allowedPrefix=" + allowedPrefixes);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (path.equals("/_diag")) {
            diag(req, resp);
            return;
        }
        String method = req.getMethod().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method.toLowerCase(Locale.ROOT))) {
            sendError(resp, 405, "Method not allowed");
            return;
        }
        if (!pathAllowed(path)) {
            sendError(resp, 403, "Path not allowed");
            return;
        }

        long tStart = System.nanoTime();
        String userId = cicsTaskUser();
        if (userId == null) {
            LOG.severe("hbproxy: no CICS task user");
            sendError(resp, 502, "Identity not available");
            return;
        }
        long tQueue0 = System.nanoTime();
        boolean gated = false;
        if (gate != null) {
            try {
                gated = gate.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendError(resp, 504, "Interrupted");
                return;
            }
            if (!gated) {
                LOG.warning("hbproxy: busy, no slot within " + queueTimeoutMs + " ms (id=" + userId + ")");
                resp.setHeader("Retry-After", "1");
                sendError(resp, 503, "Busy");
                return;
            }
        }
        double queueMs = (System.nanoTime() - tQueue0) / 1e6;
        try {
            forward(req, resp, method, path, userId, tStart, queueMs);
        } finally {
            if (gated) {
                gate.release();
            }
        }
    }

    /** Ticket generation and the call to HB.js; runs while holding a gate slot when a cap is configured. */
    private void forward(HttpServletRequest req, HttpServletResponse resp, String method, String path, String userId,
            long tStart, double queueMs) throws IOException {
        String ticket;
        long tTicket0 = System.nanoTime();
        try {
            ticket = generatePassTicket(userId, applid);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "hbproxy: PassTicket generation failed for " + userId + ": " + describe(t));
            sendError(resp, 502, "PassTicket generation failed");
            return;
        }

        byte[] body = readBody(req);
        if (body == null) {
            sendError(resp, 413, "Request body too large");
            return;
        }

        String query = req.getQueryString();
        URI uri = URI.create(target + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(readTimeout);
        for (Enumeration<String> names = req.getHeaderNames(); names.hasMoreElements();) {
            String name = names.nextElement();
            if (SKIP_REQUEST.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (Enumeration<String> vals = req.getHeaders(name); vals.hasMoreElements();) {
                b.header(name, vals.nextElement());
            }
        }
        b.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((userId + ":" + ticket).getBytes(StandardCharsets.ISO_8859_1)));
        b.method(method, body.length == 0 && !method.equals("POST") && !method.equals("PUT") && !method.equals("PATCH")
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<InputStream> r;
        long tHb0 = System.nanoTime();
        try {
            r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(resp, 504, "Interrupted");
            return;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "hbproxy: call to HB.js failed: " + describe(e));
            sendError(resp, 502, "HB.js not reachable");
            return;
        }

        long tHb1 = System.nanoTime();
        double ticketMs = (tHb0 - tTicket0) / 1e6;
        double hbMs = (tHb1 - tHb0) / 1e6;
        if (timing) {
            // opt-in (jndiEntry hbproxy/timing=true): dur values in milliseconds, set before the body so the caller can read them
            resp.setHeader("Server-Timing", String.format(Locale.ROOT, "queue;dur=%.1f, ticket;dur=%.1f, hbjs;dur=%.1f", queueMs, ticketMs, hbMs));
        }
        resp.setStatus(r.statusCode());
        r.headers().map().forEach((name, values) -> {
            if (!SKIP_RESPONSE.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(v -> resp.addHeader(name, v));
            }
        });
        try (InputStream in = r.body()) {
            if (!method.equals("HEAD")) {
                OutputStream out = resp.getOutputStream();
                in.transferTo(out);
                out.flush();
            }
        }
    }

    private boolean pathAllowed(String path) {
        if (!path.startsWith("/") || path.contains("..") || path.contains("\\") || path.contains("//")
                || path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return false;
        }
        for (String p : allowedPrefixes) {
            if (p.equals("/") || path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private byte[] readBody(HttpServletRequest req) throws IOException {
        long len = req.getContentLength();
        if (len > maxBody) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        try (InputStream in = req.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBody) {
                    return null;
                }
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }

    private void diag(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");
        PrintWriter out = resp.getWriter();
        out.println("settings target        = " + target);
        out.println("settings applid        = " + applid);
        out.println("settings allowedPrefix = " + allowedPrefixes);
        out.println("step 1 principal       = " + (req.getUserPrincipal() == null ? null : req.getUserPrincipal().getName()));
        String user = cicsTaskUser();
        out.println("step 2 cics task user  = " + user);
        if (user == null) {
            return;
        }
        try {
            String t = generatePassTicket(user, applid);
            out.println("step 3 passticket      = generated (" + t.length() + " chars, not shown) for " + user + " / " + applid);
        } catch (Throwable t) {
            out.println("step 3 passticket      = FAILED " + describe(t));
        }
    }

    // ---- helpers ----

    /** Error response as JSON: {"error_description": "...", "error": 502}. */
    private static void sendError(HttpServletResponse resp, int status, String description) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        String escaped = description.replace("\\", "\\\\").replace("\"", "\\\"");
        resp.getWriter().write("{\n  \"error_description\": \"" + escaped + "\",\n  \"error\": " + status + "\n}\n");
    }

    private static String generatePassTicket(String userId, String applid) throws Exception {
        Class<?> cls = Class.forName("com.ibm.eserver.zos.racf.IRRPassTicket");
        Object o = cls.getDeclaredConstructor().newInstance();
        Method m = cls.getMethod("generate", String.class, String.class);
        try {
            return (String) m.invoke(o, userId, applid);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** The RACF ID the CICS task runs under: the ID the PassTicket must be generated for. */
    private static String cicsTaskUser() {
        try {
            Class<?> task = Class.forName("com.ibm.cics.server.Task");
            Object t = task.getMethod("getTask").invoke(null);
            String u = (String) task.getMethod("getUSERID").invoke(t);
            return u == null || u.trim().isEmpty() ? null : u.trim();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "hbproxy: cannot get CICS task user: " + describe(t));
            return null;
        }
    }

    private static String setting(String name, String def) {
        try {
            Object v = new InitialContext().lookup("hbproxy/" + name);
            if (v != null) {
                return v.toString().trim();
            }
        } catch (Exception ignored) {
            // not defined as a jndiEntry: try the next source
        }
        String p = System.getProperty("hbproxy." + name);
        if (p != null) {
            return p.trim();
        }
        String e = System.getenv("HBPROXY_" + name.toUpperCase(Locale.ROOT));
        return e != null ? e.trim() : def;
    }

    /** A setting with no safe default (it names a host or a CICS region): fail clearly instead of silently pointing at someone else's LPAR. */
    private static String required(String name) {
        String v = setting(name, "");
        if (v.isEmpty()) {
            throw new IllegalStateException("hbproxy/" + name + " is not set (jndiEntry hbproxy/" + name + ", system property hbproxy." + name
                    + " or environment HBPROXY_" + name.toUpperCase(Locale.ROOT) + ")");
        }
        return v;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static Set<String> lower(String... v) {
        Set<String> s = new HashSet<>();
        for (String x : v) {
            s.add(x.toLowerCase(Locale.ROOT));
        }
        return s;
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        for (int i = 0; c != null && i < 6; i++) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(returnCodes(c));
            c = c.getCause();
        }
        return sb.toString();
    }

    /** SAF/RACF return and reason codes of IRRPassTicketGenerationException (its message is null): every public int getter it declares. */
    private static String returnCodes(Throwable t) {
        if (!t.getClass().getName().startsWith("com.ibm.eserver.zos.racf.")) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : t.getClass().getMethods()) {
            if (m.getDeclaringClass() != Object.class && m.getDeclaringClass() != Throwable.class && m.getParameterCount() == 0
                    && m.getName().startsWith("get") && (m.getReturnType() == int.class || m.getReturnType() == String.class)
                    && !m.getName().equals("getMessage") && !m.getName().equals("getLocalizedMessage")) {
                try {
                    sb.append(sb.length() == 0 ? " [" : ", ").append(m.getName().substring(3)).append("=").append(m.invoke(t));
                } catch (Exception ignored) {
                    // not a plain getter: skip
                }
            }
        }
        return sb.length() == 0 ? "" : sb + "]";
    }
}
