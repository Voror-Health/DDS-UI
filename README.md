# vhp-dds-ui

The DDS UI ("Data Service Manager") on **Java 21 / Tomcat 11 (Jakarta EE) / Casdoor / MySQL 8.4**, with a new
**Vue 3 + PrimeVue 4** front end.

- Back end: the original Endeavour `EDS/src/eds-ui` Java (`D:\GithubBackup2\EDS\src\eds-ui`, branch `vor-sync`),
  ported as in AGENTS.md §4, plus the server additions from the later dds-ui Vue port
  (`D:\OpenCode\EDS-master\src\dds-ui`): queue server admin, queue reader instances / memory settings and the
  filtered Rabbit queue list. Package names are unchanged (`org.endeavourhealth.ui.*`).
- Front end: `frontend/`, written from the original Angular pages (every menu page and its sub-pages) with the
  dds-ui port's Queue Server Admin page added. One WAR serves both.

```
mvn -B package                       # JDK 21; builds frontend/ (Node 20.17 via frontend-maven-plugin) and the WAR
mvn -B package -Dskip.frontend=true  # reuse an already-built frontend/dist
→ target/vhp-dds-ui.war              # deploy as webapps/vhp-dds-ui.war → https://<host>/vhp-dds-ui/
```

Front-end development: `cd frontend && npm ci && npm run dev` serves on :3200 and proxies `/vhp-dds-ui/api` and
`/vhp-dds-ui/public` to a Tomcat on :8080.

## Pages

| Menu | Route | From |
|---|---|---|
| Publishers (+ editor, exchange audit) | `/services`, `/serviceEdit/...`, `/exchangeAudit/:serviceId/:systemId` | eds-ui (editor and exchange audit were missing from dds-ui) |
| Publisher Errors | `/transformErrors` | eds-ui |
| Subscribers (+ detail, publisher status) | `/subscribers`, `/subscribers/:name` | eds-ui |
| Remote Filing | `/remoteFiling` | eds-ui |
| Queue Readers | `/queueReaders` | eds-ui, plus dds-ui's "Deploy All" |
| Queue Server Admin | `/queueServerAdmin` | dds-ui port (rebuilt, see below) |
| Subscriber API, HL7 Receiver, HL7v2 API, SFTP Reader, Scheduled Tasks, Statistics | as named | eds-ui |
| Audit | `/audit` | eds-common-js (common/api AuditEndpoint) |
| Monitoring (logging) | `/monitoring` | eds-ui |
| Routing Config | `/routingConfig` | eds-ui |
| System Config (+ editor) | `/systems`, `/systemEdit/...` | eds-ui |
| Config Manager | `/configManager` | eds-ui |

Session-only state (filters, display options) is kept per page for the browser session, as the Angular services did.

### SFTP Reader page: Add Feed and Interface Types (VHP, 2026-11-24)

The SFTP Reader page can set up a new feed, which the deployment guide's "SFTP Reader instance" step did with raw SQL.
It reads and writes the reader's own database (the `database` row under app_id `sftpreader`; see Configuration).
Plan and design notes: `docs/dds-ui-sftp-upgrade.md`.

- **Interface Types** card (below the feed table): lists every `interface_type` row with its `interface_file_type`
  identifiers. A type with no file types is flagged; feeds can't use it, because the reader checks received files
  against them. Visible to any UI user, with a **Feeds** column (how many configurations use the type).
  `vhp_ui_admin` users also get an **Add Interface Type** form (name, data frequency in days, file type identifiers one
  per line, optional id) and a **Delete** button per row. Delete asks for confirmation, removes the type and its file
  types, and is disabled while any feed uses the type.
- **Add Feed** button (`vhp_ui_admin` only) opens a dialog with the Feed, SFTP, PGP and optional SharingAgreementGuid
  fields. Pick an existing instance, or tick **New instance**; the name must match a deployed
  `vhp-sftpreader#<instance>` webapp, or nothing runs the feed. The content type is one of the SFTP Reader formats
  in the message format list (see "Message formats" below). After adding a feed, **restart that reader webapp**: it only reads its
  configurations at start-up. Keys and passwords are cleared when the dialog closes and are never sent back.

| Endpoint | Role | Does |
|---|---|---|
| `GET /api/sftpReader/interfaceTypes` | any UI user | `[{interfaceTypeId, interfaceTypeName, dataFrequencyDays, fileTypes: [...], configurationCount}]`, by id |
| `DELETE /api/sftpReader/interfaceTypes/{id}` | `vhp_ui_admin` | deletes the type's `interface_file_type` rows and the `interface_type` row in one transaction. 404 if there is no such type. 409 while any `configuration` uses it; the message names the feeds. A foreign-key failure in a race (MySQL 1451) is also a 409. Audit "Delete Interface Type". |
| `GET /api/sftpReader/instanceNames` | any UI user | every `instance` row (including ones with no feed yet) |
| `POST /api/sftpReader/interfaceTypes` | `vhp_ui_admin` | `{interfaceTypeName, dataFrequencyDays, fileTypes, interfaceTypeId?}`; adds the type and its file types in one transaction (id = max + 1 if left out). 200 with the created row. |
| `POST /api/sftpReader/addFeed` | `vhp_ui_admin` | `JsonAddFeedRequest`; in one transaction inserts `instance` (only if `createInstance`), `configuration`, `instance_configuration`, `configuration_sftp`, `configuration_pgp` and `configuration_kvp` (`SharingAgreementGuid`, if given). 200 with `{instanceName, configurationId, restartRequired: true}`. |

Validation (`sftpreader/FeedValidator`, `InterfaceTypeValidator`) runs before any database work, and the limits come
from the reader's `schema.sql`:
- Lengths: 53 characters for the configuration id. The reader locks `SftpReader-<id>`, and MySQL lock names are at
  most 64 characters, so a longer id would make every polling run fail. 100 for the instance, friendly name, version,
  hostname, username and extension filter. 1000 for paths, passwords and the GUID. 64 KB for keys.
- The configuration id and instance may contain only letters, digits, `.`, `_` and `-`. A new feed can't be put on
  the `NOT_USED` instance (that is where retired feeds are kept).
- Port is 1–65535; poll frequency and data frequency are at least 1.
- The client public key is optional (often blank in test). vhp-sftpreader never reads it, and the column is
  `NOT NULL`, so a blank key is stored as `''`.

Then `sftpreader/SftpReaderFeedWriter` checks the database inside the transaction. It answers **409** when the
configuration id, friendly name or interface type name/id is already in use (or MySQL error 1062 in a race), or when a
"new" instance already exists. It answers **400** for an unknown instance, an unknown interface type, or a type with
no file types. Errors come back as `{"message": ...}`; `BaseExceptionMapper` would hide the message, so these
endpoints build their own responses. Any other failure rolls the whole transaction back.
Audit: "Add Interface Type" and "Add Feed" (`AuditAction.Save`, with the name/id, never keys or passwords).

The page's other writes are admin-only too (2026-11-24, changed from the original, where any UI user could call
them): `POST /api/sftpReader/ignore`, `togglePause` and `togglePauseAll` have `@RolesAllowed(vhp_ui_admin)`. For a
plain `vhp_ui_user` the page hides Toggle Pause All, Toggle Pause and the organisation list's ignore controls; the
page is otherwise read-only for them.

### Message formats (VHP, 2026-11-25)

A System's technical interface `messageFormat` must equal the sender's `SourceSystem` header (for SFTP feeds, the
feed's `software_content_type`), and `messageFormatVersion` is a regex that must match the whole `software_version`.
The Messaging API uses them to match the sender to a Service endpoint (`OpenEnvelope` / `SystemHelper`), and the
inbound transform is chosen from `SourceSystem` (`MessageTransformInbound`). `messageType`, `frequency`, `name` and
`description` are display only. eds-ui let the format be any text; a format with no transform can never be processed.

The formats that can be processed are now a list in the config DB row **`global` / `message_formats`**: a JSON array
of `{"format", "description", "sftpReader", "transform"}` (`messageformats/MessageFormats`). Create it with
`db/migrations/2026-11-25_message_formats.sql` (15 formats; safe to re-run, it never overwrites the row).

- **System Config**: the Message Format drop-down offers only `transform` formats. A System whose stored format is not
  listed is shown with an error, and can't be saved until a listed format is chosen. **New Format** (`vhp_ui_admin`)
  adds a format to the row (format, description, the two flags). Only add one once its transform (and, for SFTP feeds,
  its SFTP Reader implementation) has been deployed. Edit or remove entries on the Config Manager page.
- **SFTP Reader Add / Edit Feed**: the content type drop-down offers the `sftpReader` formats, and `FeedValidator`
  accepts only those. That adds **`NEMSXML`**, which the reader supports but the old hard-coded list left out.
- **No row** (or the config DB can't be read, or the row isn't a valid list): the built-in list (`MessageFormats.DEFAULTS`,
  the same 15 as the script) is used, and the System page shows a warning saying so. Adding a format then creates the
  row. If the row exists but is invalid, adding is refused (409), so a broken hand edit isn't overwritten.
- The row is read from the database on every call, not through ConfigManager's cache, so changes show at once.
- Only the UI enforces the list for Systems: `POST /api/library/saveLibraryItem` doesn't check `messageFormat`.

| Endpoint | Role | Does |
|---|---|---|
| `GET /api/messageFormats` | any UI user | `{"source": "config"\|"default", "warning", "formats": [...]}` (not audited: reference data) |
| `POST /api/messageFormats` | `vhp_ui_admin` | adds one format; 400 bad format (upper case, digits, `_`; max 100), 409 already listed / invalid row; audited |

### Edit, retire, reinstate and delete feeds (VHP, 2026-11-24)

Design and decisions: `docs/dds-ui-sftp-feed-edit-delete.md`. All admin-only (`vhp_ui_admin`). The feed table's hover
actions are **Edit**, **Retire** or **Reinstate**, and **Delete** (only for feeds that never received a batch). A
**Show Retired Feeds** filter lists retired feeds with a "Retired" tag.

- **Edit** (the Add Feed dialog in edit mode). Everything except the configuration id can change. Notes:
  - **Locked once the feed has batches:** the interface type and local root path. Batches reference the
    configuration with its interface type, and their stored files are found through the root path.
  - **Private keys and passwords are never sent to the browser.** The form shows "set - leave blank to keep";
    a blank field keeps the stored value.
  - **Clearing the SharingAgreementGuid** removes its kvp row.
  - **Moving to another instance** needs both instances restarted.
  - **Conflicts:** the edit is checked against a `version` hash taken when the form was loaded, so a concurrent change
    gives 409.
- **Retire** moves the feed to the `NOT_USED` instance (the original's convention) and pauses notifying. The reader
  reads the pause flag on every run, so nothing more is posted even before the old instance is restarted. History is
  kept. **Reinstate** moves it to a chosen instance and leaves it paused.
- **Delete** asks the admin to type the configuration id. It removes, children first: `configuration_polling_attempt`,
  `unknown_file`, `adastra_organisation_map`, `configuration_paused_notifying`, `configuration_kvp`,
  `configuration_pgp`, `configuration_sftp`, `instance_configuration` and `configuration`. A feed with batches gets 409
  ("retire it instead"). The `instance` row and any stored files are left alone. Purging history is deliberately not
  offered.
- Every write is one transaction and first takes the reader's own per-feed lock, `GET_LOCK('SftpReader-<id>', 0)`.
  While a polling run holds it, the answer is **409** "polling right now, try again". The reader reads feeds only at
  start-up, so every response says which `vhp-sftpreader#<instance>` webapp(s) to restart, and the page shows that.

| Endpoint | Does |
|---|---|
| `GET /api/sftpReader/instances?includeRetired=true` | the existing list, now with `hasBatches` and `retired`; `NOT_USED` feeds only with the flag |
| `GET /api/sftpReader/feeds/{id}` | the feed without secrets (`clientPrivateKeySet`... flags), plus `instanceName`, `retired`, `paused`, `hasBatches`, `version`. 404 if unknown. |
| `PUT /api/sftpReader/feeds/{id}` | edit (`JsonEditFeedRequest` = the Add Feed fields, secrets optional, plus `version`). 200 `{changedFields, restartInstances, restartRequired}`. |
| `POST /api/sftpReader/feeds/{id}/retire` | 200 `{previousInstanceName, restartRequired}`; 409 if already retired |
| `POST /api/sftpReader/feeds/{id}/reinstate` | body `{instanceName}`; 409 if not retired, 400 for an unknown instance or `NOT_USED` |
| `DELETE /api/sftpReader/feeds/{id}` | 200 `{deletedRows: {table: n}}`; 409 with batches |

Code: `sftpreader/SftpReaderFeedEditor` (and `FeedValidator.validateEdit`). Audit: "Get SFTP Reader Feed", "Edit Feed"
(the names of the changed fields only, never values), "Retire Feed", "Reinstate Feed", "Delete Feed" (row counts).
These four writes are audited after the change commits, so if the audit database fails, the change has still been
made even though the page reports an error.

Not done: purging a retired feed's history, renaming a configuration id, editing interface types or adding file types
to an existing type, restarting the reader from the UI, and checking the content type/version against the admin
library item.

**Not carried over**, on purpose:
- the query library pages (`/library`, query / data set / code set / count report / test editors). They were not in
  the eds-ui menu and only reachable by typing the URL. The library endpoints remain (System Config uses them).
- user administration (eds-common-js `AdminModule`, `Security*Endpoint`, `UserEndpoint`): these drove the Keycloak
  admin API. Users and roles are managed in Casdoor now.
- Database Stats: its endpoint was deleted from eds-ui in January 2020 ("Sorted out all the Tomcat endpoint
  metrics"); the dds-ui port kept a page that called the missing endpoint.
- Swagger UI (`/api-docs`): needed the javax-only `swagger-jersey2-jaxrs`. The annotations are still on the endpoints.
- `ExternalDashboardEndpoint` (all commented out in eds-ui).

## What changed in the back end

- `javax.ws.rs` / `javax.servlet` → `jakarta.*`; Jersey 3.1.10; common/api and common/security replaced by
  `framework/` (AbstractEndpoint, StartupConfig, JerseyApplication, MetricsContextListener) and `security/`
  (SecurityUtils, Roles), as in vhp-messaging-api.
- **Login**: `CasdoorAuthenticatorValve` (META-INF/context.xml) protects `/api/*` and `/machine-api/*`. The SPA
  logs in with the OIDC authorization-code flow, using the same contract as vhp-datasteward's SPA:
  `GET /public/system/auth-config` (client id etc., never the secret) and `POST /public/system/auth-token`
  (code / refresh-token exchange done on the server with the client secret). Tokens are kept in sessionStorage.
- **Roles**: every API call needs `vhp_ui_user` or `vhp_ui_admin` (web.xml `required_roles`). `vhp_ui_admin` is
  needed for the actions eds-ui marked `@RequiresAdmin` (config save/delete, post to exchange, re-queue, exchange
  notes, make non-queueable, library save/delete, Rabbit sync) and for every queue server / queue reader action
  added by the dds-ui port. In eds-ui `@RequiresAdmin` never took effect (Endeavour's CustomRolesAllowedFeature
  looked for it in the wrong package); here Jersey's `RolesAllowedDynamicFeature` enforces it. The UI hides
  admin-only buttons from other users.
- **Config Manager is admin-only**, reads included: it returns every config row, with database passwords and the
  Casdoor client secret. eds-ui let any UI user read it.
- **Audit page**: the user list comes from Casdoor (`GET /api/get-users?owner=<org>`, HTTP Basic with the app's
  client id/secret) instead of the Keycloak admin API. User IDs in the audit table are the name-based UUIDs
  `SecurityUtils` derives from the Casdoor user name, so the filter matches what this app writes. (The eds-ui page
  never loaded events: the call was commented out. This one does.)
- **Publisher "DSM details"** (DPA, publisher DSAs, distribution projects / current subscribers) now come from the
  vhp-datasteward API (`hasDPA`, `organisationsByOds`, `dsasPublishing`, `distributionProjects`, `dsasSubscribing`)
  with a Casdoor service token, not from the DSM tables. `subscriberDSAs` now lists the DSAs the organisation
  subscribes to (DataSteward has no projects-by-subscriber lookup), and `publisherOdsCodes` is gone.
- **Subscriber lookups** (Exchange Audit's protocol list, the Subscribers pages) go through `SubscriberHelper` →
  `ProjectCache.getValidDistributionProjectsForPublisher`, which read the DSM tables. The app has vhp-queuereader's
  `ProjectCache` override (`src/main/java/org/endeavourhealth/core/.../caching/ProjectCache.java`, same name, so
  WEB-INF/classes wins over the core jar) that asks DataSteward `GET /organisation/distributionProjects` instead.
  StartupConfig logs an error if the jar's copy is ever loaded; the public API matches core 1.787 (javap).
- **Post to exchange with the VHP exchange names** (2026-11-18): the library's `QueueHelper` hard-codes the RabbitMQ
  exchanges as `EdsInbound` / `EdsProtocol` / `EdsTransform` / `EdsSubscriber`, and only accepts a
  `PostMessageToExchange` config whose `<Exchange>` is exactly that name. The VHP configs post to `VhpInbound` etc., so
  Exchange Audit's "Post to exchange" (and the queue reader's EMIS missing-code fix, which calls it) failed with
  `Failed to find PostMessageToExchange config details for exchange INBOUND` (seen on demo3). The app has a
  `QueueHelper` override (`src/main/java/org/endeavourhealth/core/queueing/QueueHelper.java`, copied from
  eds-messaging-core, identical bytecode to the jar in use) where each exchange also matches its `Vhp*` name, ignoring
  case; the message is posted to the exchange the matched config names. `getName()` still returns the `Eds*` name,
  because the exchange event text ("Manually pushed into EdsInbound exchange") is matched on elsewhere. Public API
  matches the library (javap); StartupConfig logs an error if the jar's copy is ever loaded. The Queue Readers and
  Queue Server Admin pages also recognise `VhpInbound-A` and unprefixed (`inbound-a`) queue names, not just
  `EdsInbound-A` (`endpoints/QueueTypes`).
- **Remote filing**: the SFTP key password (`clientPrivateKeyPassword`) is no longer sent to the browser. The old
  page hid it but the API returned it.
- **Rabbit**: the `address` parameter of the Rabbit endpoints must be one of the configured management nodes. The
  server sends the Rabbit credentials to that address, so before this it could be pointed at any host.
- **javax JAX-RS in the libraries**: some Endeavour library code throws `javax.ws.rs` exceptions (e.g.
  `QueueHelper.findExchangeConfig`'s BadRequestException). Creating one used to fail with
  `LinkageError: ClassCastException ... RuntimeDelegate`, because javax fell back to Jersey 3's jakarta delegate.
  `framework/javaxrs/JavaxRuntimeDelegate` (registered in `META-INF/services/javax.ws.rs.ext.RuntimeDelegate`) is a
  minimal javax delegate that only builds responses, so those exceptions now work and `BaseExceptionMapper` passes
  on their status. It is not a javax client: library code using the javax JAX-RS client (PostToRest, UPRN) still
  can't run here.
- Exchange Audit shows "N/A" for a queue whose `PostMessageToExchange` config can't be found (in the
  messaging-api `api-configuration` or any queuereader config) instead of failing the whole exchange list, and
  logs a warning once per queue.
- `BaseExceptionMapper` always answers JSON (text/plain endpoints turned a 403 into a 500).
- Systems (2026-11-18): `QueryDocument.xsd` requires `<folderUuid>`, but Systems have no folder, so saving a new
  System failed schema validation (in the original too). `saveLibraryItem` now stores `""` for a missing folder.
  `getLibraryItem` answers 404 for an unknown UUID (was an NPE/500, e.g. a stale `/systemEdit/edit/<uuid>` link after
  the admin DB was emptied) and logs the stored XML when it can't be read. The System page goes back to the list with
  "System not found" on a 404, and disables Save when loading failed (it used to save the blank form as a new System).
  The Systems list says "No systems yet" when the admin DB has none. Checked on a blank MySQL 8.4 admin DB
  (`SystemLibraryMySqlIntegrationTest`): create the first System, reload it, edit it, list it.
- SFTP Reader page (2026-11-24): it answered 500 on load (seen on demo3). `SftpReaderEndpoint` got its connections from
  core's `ConnectionManager`, which reads the `db_sftp_reader` row; the ported reader uses the `database` row under
  app_id `sftpreader` instead, and deployments don't have `db_sftp_reader`. The endpoint now uses
  `sftpreader/SftpReaderDb` (a copy of vhp-sftpreader's, reading app_id `sftpreader` explicitly), plain JDBC, and
  MySQL-only SQL (the PostgreSQL `log.`/`configuration.` branches and the Hibernate session shim were removed). Its
  pool is closed in `StartupConfig.contextDestroyed`. Endpoint paths and responses are unchanged.
- Services list (2026-11-25): `GET /api/service` answered 500 for everyone (seen on demo3 after adding a publisher)
  when any service had an interface with no System (`systemUuid: null`; "Add" on the Service page creates one, and
  nothing stopped it being saved) or one whose System had been deleted: `LibraryRepositoryHelper` NPEs on both, so the
  original's `MISSING SYSTEM` branch was never reached. The list now shows `NO SYSTEM SET` / `MISSING SYSTEM` in that
  row, `findServicesInError` and `systemsForService` skip such interfaces, and `validateService` refuses to save an
  interface with no System ("Select a System for every interface..."). To repair an affected service, open it, pick
  the System (or remove the interface) and save. `ServiceEndpointSystemsTest`; the deleted-System case is in the
  opt-in `SystemLibraryMySqlIntegrationTest`.

### Queue Server Admin (from the dds-ui port) - fixes

The dds-ui version could not have worked and was unsafe:
- SSH connected with no key (JSch doesn't read `~/.ssh` by itself) and dropped the last character of the user name
  (`substring(0, ix - 1)`). Now `utility/QueueServerSsh` loads the key from the `queue-server-ssh` config row,
  checks host keys against known_hosts, and uploads JARs over SFTP.
- Request values were pasted into `sudo` shell commands. Queue names, memory sizes, JAR names, AWS instance IDs,
  hosts and users are now checked against strict patterns first.
- EC2 start/stop used the **first** server's AWS instance ID whichever server was chosen.
- `/queueReader/stop` and `/restart` read the IDs from a field the page didn't send, treated "none" as "all
  queue readers", and matched config IDs against server host names. They now take `configIds`, refuse an empty
  list, and find each reader's server from its heartbeat.
- "Use queue type defaults" called `DELETE /queueReader/config/{id}`, which deletes the queue reader's
  **whole configuration row**. The page now saves an empty memory override instead.
- Per-instance "Deploy" deployed to every server (the page sent `configIds`, the endpoint reads `hostNames`).
- JAR upload sent a `data:` URL that the server could not base64-decode, and the file name in the wrong field.
- Queue assignment sent the wrong field names, and could only add a queue to servers, never remove it. The
  server list sent is now the complete set.
- Memory defaults and overrides were written under app_id `queuereader` but read from `dds-ui`.
- The dds-ui logging "fallback" queried a made-up table with invalid SQL; the original LoggingEndpoint is used.

## Configuration (config DB, app_id `dds-ui`, falling back to `global`)

Set `CONFIG_JDBC_URL`, `CONFIG_JDBC_USERNAME`, `CONFIG_JDBC_PASSWORD` and `CONFIG_JDBC_CLASS=com.mysql.cj.jdbc.Driver`.
All the databases are MySQL. The PostgreSQL driver (42.7.4, `runtime`) is in the WAR only because core's
`ConnectionManager.isPostgreSQL()` does `instanceof org.postgresql.jdbc.PgConnection`; without it the HL7 Receiver
page fails with `NoClassDefFoundError` (2026-11-18). (The SFTP Reader page no longer goes through `ConnectionManager`.)

| config_id | Used for |
|---|---|
| `auth` | Casdoor (`casdoor-endpoint`, `org-name`, `app-name`, `client-id`, `client-secret`): read by the valve, the login endpoints and the Audit user list. The Casdoor application must allow the redirect URL `https://<host>/vhp-dds-ui/callback`, and should be a confidential client (the secret stays on the server). |
| `datasteward` | `{"url": "https://<host>/vhp-datasteward/machine-api", "username": ..., "password": ...}`, the service user (see vhp-messaging-api). **Needed** for subscriber lookups and DSM details; without it the app starts but those calls fail. DataSteward needs `vhp-datasteward/deploy/migrations/2026-11-17_project_distribution.sql`. |
| `queue-server-ssh` | optional `{"private-key-file": "/home/tomcat/.ssh/id_rsa", "known-hosts-file": "/home/tomcat/.ssh/known_hosts", "strict-host-key-checking": "yes"}`; defaults are the Tomcat user's `~/.ssh/id_rsa` and `known_hosts`. Only Queue Server Admin, the Queue Readers deploy and queue reader stop/restart use SSH. |
| `server_ips` (app_id `queuereader`) | the queue reader servers (`hostName`, `ipAddress`, `userAndIp`, `queues`, `awsInstanceId`), maintained from Queue Server Admin |
| `queuereader-memory-defaults-<type>`, `queuereader-memory-override-<configId>` (app_id `queuereader`) | written by Queue Server Admin. The defaults give the heap passed to `runNohup.sh` when a queue is started without one; nothing in this workspace reads the per-reader override except the page itself (as in the dds-ui port). |
| `database` (app_id **`sftpreader`**, falling back to `global`) | the SFTP Reader page's database: the same row vhp-sftpreader reads, `{"url": "jdbc:mysql://<host>:3306/sftpreader", "username": ..., "password": ..., "pool": {"min-idle": 1, "max-pool-size": 3}}` (`pool` optional, MySQL only). The old `db_sftp_reader` row is not used. If `sftpreader` has no such row, a `global` `database` row is used, whatever database it points at. |
| `message_formats` (app_id **`global`** only) | the message formats that can be processed (see "Message formats"). Optional: without it the built-in list is used. Create with `db/migrations/2026-11-25_message_formats.sql`. |
| `rabbit` and the Endeavour database rows (`db_admin`, `db_audit`, `db_publisher_*`, `db_subscriber`, `logback`, `metrics`, ...) | as for the original eds-ui |

Roles in Casdoor: `vhp_ui_user` (use the UI) and `vhp_ui_admin` (admin actions above, Config Manager).
`vhp_service` is accepted only for `POST /machine-api/exchangeAudit/postToExchange` (`web/ServiceAccountScopeFilter`):
that is the call the queue reader's EMIS missing-code fix (`EmisCsvHelper`) makes with its Casdoor service token.
Point the queue reader's `emis_config` → `missing_code_fix` → `dds-ui-url` at `https://<host>/vhp-dds-ui/`.

## Tests

`mvn package` runs 101 tests (14 skipped without the opt-in MySQL tests):
- `Tomcat11SmokeTest`: deploys `src/main/webapp` (real web.xml / context.xml) on embedded Tomcat 11 with a test
  stand-in for the Casdoor valve (user and roles from `X-Test-*` headers) and a DataSteward HTTP stub. Checks:
  the valve challenge, `/public/*` without a token, deep links get index.html with `<base>`, admin-only actions
  answer 403 to `vhp_ui_user`, `vhp_service` tokens only reach the queue reader's machine call, queue reader stop
  refuses empty or unsafe IDs, DSM details come from DataSteward
  with the service token, and every page's endpoints are routed.
- `SystemLibraryItemRoundTripTest`: a System posted by the page goes through `QueryDocument.xsd` on save and load.
- `SystemLibraryMySqlIntegrationTest` (opt-in): the System Config calls against a real MySQL with an empty admin DB.
  Needs a throwaway server with databases `config` (table `config(app_id, config_id, config_data)` with `global` rows
  `db_admin` and `db_audit`: `{"url": "jdbc:mysql://.../admin", "username": ..., "password": ..., "class": "com.mysql.cj.jdbc.Driver"}`),
  `admin` (tables up to `active_item` from `endeavour/EDS/src/database/mySQL/admin/create_tables.sql`) and `audit`
  (`user_event`). It adds Systems and never removes them.
  `mvn test -Dtest='SystemLibrary*' -Ddds.test.mysql=true "-Ddds.test.config.url=jdbc:mysql://127.0.0.1:33086/config?useSSL=false" -Ddds.test.config.username=... -Ddds.test.config.password=...`
- `CasdoorApiTest` (token exchange, error mapping, user list), `QueueServerSshTest` (input checks, user@host),
  `JsonRemoteFilerTest` (password not serialised), `DataStewardClientTest` (`getJson`, 401 retry),
  `ProjectCacheOverrideTest` (the override is the loaded copy and answers from DataSteward),
  `QueueHelperOverrideTest` (the override is the loaded copy; `VhpInbound` in demo3's messaging-api config is found
  for INBOUND, `EdsInbound` still is), `QueueTypesTest` (queue type from `Eds*`/`Vhp*`/unprefixed queue names),
  `JavaxRuntimeDelegateTest` (library-style javax exceptions can be created),
  `PostgresDriverOnClasspathTest` (core's `ConnectionManager.isPostgreSQL()` works on a MySQL connection),
  `SftpReaderDbTest` (the `sftpreader`/`database` row format, MySQL only, pool settings, no password in `toString`,
  and `SftpReaderEndpoint` no longer uses `ConnectionManager`),
  `FeedValidatorTest` (every Add Feed / interface type check and length limit, the edit mode, content types from the
  message format list, and that only MySQL 1062/1451 map to 409),
  `MessageFormatsTest` (the row, the fallbacks, adding formats, an invalid row is never overwritten, and the seed
  script holds exactly `MessageFormats.DEFAULTS`), `SftpReaderFeedEditorTest` (version hash, no secrets in GET, lock results, delete order).
- `Tomcat11SmokeTest` also checks:
  - `addFeed` and `POST interfaceTypes` answer 403 to `vhp_ui_user`;
  - for an admin, bad bodies answer 400 with the validator's message before any database is touched;
  - the new GETs are routed;
  - `GET /api/messageFormats` falls back to the built-in list without a config DB, and `POST` is admin-only and
    validated.
- `SftpReaderFeedMySqlIntegrationTest` (opt-in, 12 tests) runs against a real MySQL.
  - It **drops and recreates** a database called `dds_ui_test_sftpreader` from `vhp-sftpreader`'s `schema.sql` for each
    test, so only point it at a throwaway server.
  - It covers: interface types with and without an id; 409s on duplicates; the six rows of Add Feed (new and existing
    instance, with and without kvp); every refusal; and a forced failure after `instance_configuration` that must roll
    back everything. Edit, retire, reinstate and delete: secrets are kept unless sent; a stale version gives 409; the
    interface type and root path are locked once a batch exists; moving to another instance; retire/reinstate
    (pause kept, history kept); delete only without batches; and a lock held by a second session (standing in for a
    polling run) gives 409.
  - Run: `mvn test -Dtest='SftpReaderFeed*' "-Ddds.test.sftpreader.url=jdbc:mysql://127.0.0.1:33124/?useSSL=false&allowPublicKeyRetrieval=true" -Ddds.test.sftpreader.username=root -Ddds.test.sftpreader.password=`
  - Passed on MySQL 8.4 (2026-11-24).

There is no config DB in tests, so endpoints that need the Endeavour databases are only checked up to the point
where they would use them.

## Not verified

- Against real databases, a real Casdoor, RabbitMQ, the queue reader servers (SSH/EC2) or a real Tomcat 11. The
  UI pages were checked in a browser against a mock API only (every page renders, no console errors).
- The SFTP Reader page's status/history queries against a real `sftpreader` database. They are the original's MySQL
  branches, unchanged, and were not run here; the page loads on demo3 since the 2026-11-24 fix. Add Feed's SQL was
  run against MySQL 8.4 with the reader's `schema.sql`. A real reader instance has not been restarted to poll a feed
  the UI added. The dialog was checked in a browser against a mock API only.
- The Casdoor logout URL (`/api/logout?redirect_uri=...&client_id=...`) is the one vhp-datasteward's SPA uses.
- Queue Server Admin's shell scripts (`runNohup.sh`, `kill.sh`, `restart.sh`, ...) and EC2-via-`ubuntu@localhost`
  are as the dds-ui port had them; they were never seen working.
- JSON for library items (System Config, JAXB-generated classes) under Jersey 3's Jackson provider: field names
  match the getters, so the JSON should be unchanged, but it was not compared against the old app.
