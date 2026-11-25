# Build and deploy hbproxy

Names used below: `<SERVER_DIR>` = `<WORK_DIR>/<APPLID>/<JVMSERVER>/wlp/usr/servers/defaultServer`. Replace the other `<PLACEHOLDERS>` with your values.

## 0. Before you start (one time, by the owners of each area)

1. **Security product**: on RACF run the relevant parts of `config/racf-setup.txt`; on Top Secret edit and run `config/tss-setup.jcl` (generate the PassTicket session key yourself; it is a secret). Both cover: the identity mapping for the caller, PassTicket for the APPLID and the service ID, the Liberty angel and `SERVER` profiles, the key ring.
   RACF only: do not run the region as `PRIVILEGED` in the STARTED class (see the note in `racf-setup.txt`). On Top Secret, give the region ACID only the permits in the job; the PassTicket permit is per service ACID in production.
2. **CICS**: JVM server and HB.js service as in `config/cics-definitions.txt`.
3. **JVM profile**: apply the settings of `config/DFHWLP.jvmprofile.settings` to the profile the JVMSERVER uses (`JVMPROFILEDIR`).
4. **Token issuer**: note its issuer URL, JWKS URL, the audience and the client ID your callers' tokens carry; they go into `server.xml`.

## 1. Build

Pick one.

**On a workstation** (JDK 11+; copy `<WLP_INSTALL_DIR>/dev/api/spec/com.ibm.websphere.javaee.servlet.3.0_*.jar` from the server, or use any servlet 3.0/3.1 API jar):
```
cd delivery
SERVLET_API=/path/to/servlet-api.jar ./build.sh
```

**On z/OS USS** (copy the `delivery` directory there, in binary or as text with the default conversion; `javac` and `jar` from the Java the JVM server uses):
```
export JAVA_HOME=<JAVA_HOME>; export PATH=$JAVA_HOME/bin:$PATH
export WLP_INSTALL_DIR=<WLP_INSTALL_DIR>
cd delivery && ./build.sh
```
The result is `delivery/hbproxy.war`. Check it: `jar tf hbproxy.war` lists `WEB-INF/web.xml` and `WEB-INF/classes/hbproxy/PassTicketProxy.class`.

## 2. Configure Liberty

1. Edit `config/server.xml`: set the token validation (`<OIDC_CLIENT_ID>`, `<ISSUER_URL>`, `<JWKS_URL>`, `<AUDIENCE>`) and the key ring (`<REGION_USERID>/<KEYRING>`, in the `keyStore` element). Keep the ports in line with the JVM profile.
2. Edit `config/configDropins/overrides/hbproxy-app.xml`: set `hbproxy/target` (HB.js base URL) and `hbproxy/applid` (the region APPLID, as in PTKTDATA). Review the optional settings; set `allowedPrefix` for production.
3. Upload both as text and tag them ISO-8859-1 (the JVM profile has `-Dfile.encoding=ISO-8859-1`):
   ```
   zowe zos-files upload file-to-uss config/server.xml <SERVER_DIR>/server.xml --zosmf-profile <PROFILE>
   zowe zos-files upload file-to-uss config/configDropins/overrides/hbproxy-app.xml <SERVER_DIR>/configDropins/overrides/hbproxy-app.xml --zosmf-profile <PROFILE>
   ```
   then on USS: `chtag -tc ISO8859-1 <SERVER_DIR>/server.xml <SERVER_DIR>/configDropins/overrides/hbproxy-app.xml`.
   Keep a copy of the previous `server.xml` first: `cp <SERVER_DIR>/server.xml <SERVER_DIR>/server.xml.bk`.
   If your server already has a `server.xml` in use, merge instead of replacing: the parts hbproxy needs are `openidConnectClient`, `safCredentials`, `safRegistry`, `ssl`/`keyStore`, and the features listed.

## 3. Deploy the war

```
scripts/deploy-war.sh <PROFILE> <SERVER_DIR>/apps
```
(the script uploads `hbproxy.war` in binary mode through z/OSMF using the Zowe profile; extra Zowe options go after the path, for example `scripts/deploy-war.sh <PROFILE> <SERVER_DIR>/apps --port 1443` when z/OSMF is not on the profile's port.)

Liberty does not watch the application directory here (`applicationMonitor updateTrigger="disabled"`), so an **update of an existing war is not reloaded by itself**. Either:
- upload the war under a **new file name** (for example `hbproxy-2.war`) and change `location` in `hbproxy-app.xml` to it (the config monitor sees the change within 5 seconds), or
- change the override file (re-upload it, or `touch` it) after the new war is in place.

Start the JVM server if it is not running (`CEMT SET JVMSERVER(<JVMSERVER>) ENABLED`; `CEMT INQUIRE JVMSERVER` shows ENABLED), or let the config monitor reload a running one.

## 4. Check

Liberty log: `<SERVER_DIR>/logs/messages.log` (and the JVM server's `dfhjvmout` / `dfhjvmerr` under `<WORK_DIR>/<APPLID>/<JVMSERVER>`).

| Look for | Meaning |
|---|---|
| `CWWKZ0001I` / `CWWKZ0003I` ... `hbproxy` | application started / updated |
| `hbproxy: target=... applid=...` | settings read (a missing required setting logs `hbproxy: ...` and the app is unavailable) |
| `CWWKZ0002E` | application failed to start: read the next lines |
| `UnsatisfiedLinkError` | IRRRacf JNI library not found: add its directory to `LIBPATH_SUFFIX` in the JVM profile |
| `CWWKS2909E`, `could not create SAF credential` | RACF side: `SERVER BBG.SECPFX.BBGZDFLT` READ, angel profiles, or no RACMAP for the token `sub` |

Then run the smoke test with a valid token from your issuer:
```
scripts/smoke-test.sh https://<liberty-host>:7443 <token> /hbscript/<A_SCRIPT_THAT_RETURNS_THE_USER>
```
Expected: no token and bad token give 401; `/_diag` shows `step 2 cics task user` = the RACF ID mapped to your token and `step 3 passticket = generated`; the real call gives 200.

| Symptom | Likely cause |
|---|---|
| 401 with a good token | issuer, audience, JWKS URL or signature algorithm in `openidConnectClient` do not match the token |
| 403 | no RACMAP for the token `sub`, user revoked, or the user may not run the HB.js transaction |
| `/_diag` step 3 FAILED, `IRRPassTicketGenerationException` | the region user has no UPDATE on `IRRPTAUTH.<APPLID>.<ID>`, or PTKTDATA for the APPLID is missing / wrong APPLID in `hbproxy/applid` |
| 502 | HB.js not reachable from Liberty (host, port, firewall, TLS trust: the CA of the HB.js certificate must be in the key ring) |
| HB.js answers 401 | the TCPIPSERVICE is not `AUTHENTICATE(BASIC)`, the PassTicket was refused (APPLID mismatch, clock skew > 10 min) or the ID is revoked |

## 5. Rollback

- Application only: set `autoStart="false"` in `hbproxy-app.xml` (or move the override file out of `configDropins/overrides`); the server keeps running.
- Configuration: put back `server.xml.bk`.
- RACF: remove the `RACMAP` entries and the `IRRPTAUTH` profiles you added.

## Security notes for the operator

- `hbproxy` can generate a PassTicket for any ID the region user is permitted on `IRRPTAUTH.<APPLID>.<ID>`. Keep those profiles per ID, `UACC(NONE)`, with no generic permit, and protect `hbproxy.war` and `<SERVER_DIR>` from update.
- HB.js on the Basic-authentication port also accepts real RACF passwords. Close that port to everything except Liberty (loopback binding, AT-TLS or a firewall), or password guessing against real IDs is possible from the network.
- Map each caller to a dedicated service ID with only the CICS transactions it needs, not to a person's ID.
- Set `allowedPrefix` to the HB.js paths that callers really use.
