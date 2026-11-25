//* Top Secret (TSS) setup for hbproxy: the equivalent of config/racf-setup.txt on a Top Secret system.
//* Derived from the member that was run on the reference LPAR (PROD001.JCL(TSSHBJS)). TEMPLATE: replace every <PLACEHOLDER>, review with
//* your TSS administrator against your Top Secret release, run under a TSS administrator ID. Submit as a job or paste in TSO.
//*
//*   <CICS_APPLID>   APPLID of the CICS region (the PassTicket application name)
//*   <SESSION_KEY>   16 hex digits you generate (the PassTicket secured sign-on key). A SECRET: never store it in a repository, a ticket or a chat.
//*   <REGION_ACID>   ACID the CICS region runs under (the user of the Liberty server)
//*   <SERVICE_ACID>  ACID a caller is mapped to (a dedicated service ID, not a person)
//*   <SUB_CLAIM>     the "sub" claim of the caller's token (the identity the token issuer gives that client)
//*   <MAP_LABEL>     a label for the mapping, for example ENTRA-CALLER
//*   <DEPT>          department or division ACID that owns the SERVER resources
//*   <REGION_PROC>   the started procedure name of the CICS region
//*   <KEYRING>       key ring of the region ACID (the one server.xml points at)
//*   <KEYRING_LABEL> LABLRING: the ring name as Liberty sees it, if it differs from <KEYRING>
//*   <SERVER_CERT>   label of the server certificate, <CA_...> the digital certificate names of the CAs to trust
//*
//* The commands below are run in order. Notes per command (comments cannot be inside SYSTSIN: a line starting with /* ends the data):
//* 1. PassTicket application and secured sign-on key (SIGNMULTI = the ticket may be used more than once in its validity window).
//* 2. The region ACID may generate PassTickets (resource IRRPTAUTH.<APPLID>.<id>).
//*    As run on the reference LPAR: one prefix permit, which lets Liberty ticket ANY id.
//*    Recommended for production: one permit per service ACID (the full resource name) and no prefix permit.
//* TSS PERMIT(<REGION_ACID>) PTKTDATA(IRRPTAUTH.<CICS_APPLID>.<SERVICE_ACID>) ACCESS(UPDATE)
//* 3. Identity mapping: token "sub" claim -> ACID (the equivalent of RACMAP). IDMAPRN('*') = any registry.
//* 4. Liberty server prefix profile (without it: 403 and CWWKS2909E "could not create SAF credential").
//* 5. Certificate services Liberty/System SSL call (R_datalib: list, generate, list ring).
//* 6. Liberty angel and authorized modules. These SERVER resources must be owned (TSS ADD(<DEPT>) SERVER(BBG.AUTHMOD.) / (BBG.ANGEL.))
//*    if they are not already; on the reference LPAR they were. <ANGEL_NAME> = angelName in the JVM profile.
//* 7. Key ring of the region ACID: the CAs to trust (CERTAUTH) and the server certificate (default PERSONAL).
//* 8. The region started procedure runs under the region ACID.
//STEP1  EXEC PGM=IKJEFT01,DYNAMNBR=99
//SYSPRINT DD SYSOUT=*
//SYSTSPRT DD SYSOUT=*
//SYSTSIN  DD *
TSS ADD(NDT) PSTKAPPL(<CICS_APPLID>) SESSKEY(<SESSION_KEY>) SIGNMULTI
TSS PERMIT(<REGION_ACID>) PTKTDATA(IRRPTAUTH.<CICS_APPLID>.) ACCESS(UPDATE)
TSS ADDTO(<SERVICE_ACID>) IDMAP(ENTRAID) -
IDMAPDN('<SUB_CLAIM>') -
IDMAPRN('*') -
IDLABEL('<MAP_LABEL>')
TSS ADD(<DEPT>) SERVER(BBG.SECPFX.BBGZDFLT)
TSS PERMIT(<REGION_ACID>) SERVER(BBG.SECPFX.BBGZDFLT) ACCESS(READ)
TSS PERMIT(<REGION_ACID>) IBMFAC(IRR.DIGTCERT.LIST) ACCESS(READ)
TSS PERMIT(<REGION_ACID>) IBMFAC(IRR.DIGTCERT.GENCERT) ACCESS(CONTROL)
TSS PERMIT(<REGION_ACID>) IBMFAC(IRR.DIGTCERT.LISTRING) ACCESS(UPDATE)
TSS PERMIT(<REGION_ACID>) SERVER(BBG.AUTHMOD.BBGZSCFM) ACCESS(READ)
TSS PERMIT(<REGION_ACID>) SERVER(BBG.AUTHMOD.BBGZSAFM) ACCESS(READ)
TSS PERMIT(<REGION_ACID>) SERVER(BBG.ANGEL.<ANGEL_NAME>) ACCESS(READ)
TSS ADD(<REGION_ACID>) KEYRING(<KEYRING>) RINGDATA(CERTAUTH,<CA_ROOT>) USAGE(CERTAUTH)
TSS ADD(<REGION_ACID>) KEYRING(<KEYRING>) RINGDATA(CERTAUTH,<CA_INTERMEDIATE>) USAGE(CERTAUTH)
TSS ADD(<REGION_ACID>) KEYRING(<KEYRING>) RINGDATA(CERTSITE,<SERVER_CERT>) USAGE(PERSONAL) DEFAULT
TSS ADD(<REGION_ACID>) KEYRING(<KEYRING>) LABLRING('<KEYRING_LABEL>')
TSS REFRESH(<REGION_ACID>) JOBNAME(*)
TSS ADDTO(STC) PROCNAME(<REGION_PROC>) ACID(<REGION_ACID>)
/*
//*
//* After the job, from the operator console (NOT inside the TSO step above: F is an MVS command), refresh the identity map table:
//*     F TSS,REFRESH(IDMAPTAB)
//* Then check:
//*     TSS LIST(<SERVICE_ACID>) DATA(ALL)       the IDMAP entry is shown
//*     TSS WHOHAS PTKTDATA(IRRPTAUTH.<CICS_APPLID>.)
//*     TSS LIST(<REGION_ACID>) KEYRING(<KEYRING>)
//* Not covered here (same as racf-setup.txt): CICS transaction security for <SERVICE_ACID> (transaction CWXN and what the scripts call),
//* and ICSF CSFSERV access if TLS 1.3 to HB.js is wanted.
