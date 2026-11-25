# hbproxy: Liberty identity-propagating proxy for HB.js (delivery package)

`hbproxy` is a servlet that runs in the Liberty JVM server of a CICS region. A caller sends an OIDC bearer token to Liberty; Liberty validates it and
maps it to a RACF user ID (RACMAP); `hbproxy` generates a PassTicket for that user and forwards the request to HB.js (HostBridge) with Basic
authentication. HB.js therefore runs the CICS transaction under the caller's own mapped identity, and no password is stored anywhere.

```
Caller --Bearer JWT--> Liberty (CICS JVM server) :7443
                         1. openidConnectClient validates the token (signature, issuer, audience)
                         2. safCredentials / RACMAP: token "sub" claim -> RACF ID
                         3. hbproxy: IRRPassTicket for that ID and the region APPLID
                         4. HTTPS to HB.js with Basic  <RACF ID>:<PassTicket>  (any method, path, query, body; response returned as is)
                       --> HB.js (CICS TCPIPSERVICE, AUTHENTICATE(BASIC)) runs the script as the mapped RACF ID
```

## Contents

| Path | What |
|---|---|
| `src/hbproxy/PassTicketProxy.java` | the servlet (one class, no third-party dependency) |
| `src/WEB-INF/web.xml` | HTTPS only, role `users`, all paths |
| `build.sh` | builds `hbproxy.war` (workstation or z/OS USS) |
| `scripts/deploy-war.sh` | uploads the war to z/OS with Zowe CLI, binary |
| `scripts/smoke-test.sh` | curl checks after deployment |
| `config/server.xml` | Liberty server configuration (features, endpoint, token validation, key ring) |
| `config/configDropins/overrides/hbproxy-app.xml` | the application, its settings and the RACF Java library |
| `config/DFHWLP.jvmprofile.settings` | the JVM profile settings that matter |
| `config/racf-setup.txt` | RACF commands (RACMAP, PassTicket, Liberty services) |
| `config/cics-definitions.txt` | JVMSERVER and the HB.js TCPIPSERVICE requirements |
| `DEPLOY.md` | the procedure, in order, with checks and rollback |

The configuration files come from the live server of the reference system, with its site values (host names, APPLID, token issuer, user IDs, key ring)
replaced by `<PLACEHOLDERS>`. Nothing in this package is a secret; the PassTicket sign-on key and the token issuer's keys are never part of it.

## Requirements

- CICS TS 6.1 (or any release with the Liberty JVM server and the `cicsts:security-1.0` feature), Java 11 or later in the JVM server (the reference uses Java 21).
- Liberty features: `cicsts:core-1.0`, `cicsts:defaultApp-1.0`, `cicsts:security-1.0`, `transportSecurity-1.0`, `openidConnectClient-1.0` (and `jndi-1.0`, normally pulled in by the others).
- The Liberty angel started task, RACF (RACMAP, PTKTDATA) and a SAF key ring for the region user: all set up in `config/racf-setup.txt`.
- HB.js reachable over HTTPS with Basic authentication.
- To build: a JDK 11+ (`javac`, `jar`) and a servlet API 3.0 jar, which Liberty ships (`<WLP_INSTALL_DIR>/dev/api/spec/com.ibm.websphere.javaee.servlet.3.0_*.jar`).

## Behaviour to know

- **Settings** `target` and `applid` are required: the application refuses to start (and logs `hbproxy: ...`) without them. All others have defaults (see the override file).
- **Never forwarded**: the caller's `Authorization` and `Cookie` headers; the PassTicket is never logged or printed.
- **`GET /_diag`** (authenticated) prints the settings and the identity and PassTicket steps without calling HB.js. Use it to localise a failure.
- **Errors**: 401 no/invalid token; 403 the token maps to no RACF ID or the user may not run the transaction; 502 HB.js not reachable; 503 with `Retry-After` when the optional concurrency cap is full.
- **Only one application can own the root context `/`**. If you need another application on the same server, give it its own context root.
