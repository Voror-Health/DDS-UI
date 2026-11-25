-- VHP DDS UI: the message formats the platform can process (config DB, app_id 'global', config_id 'message_formats').
--
-- Run against the config database (the one CONFIG_JDBC_URL points at):
--   mysql -h <host> -u <user> -p config < 2026-11-25_message_formats.sql
--
-- What it's for
--   * DDS UI System Config: the Message Format drop-down offers the entries with "transform": true.
--   * DDS UI SFTP Reader Add / Edit Feed: the Software content type drop-down offers, and the server only accepts,
--     the entries with "sftpReader": true.
--   A System's messageFormat must equal the sender's SourceSystem header (for SFTP feeds, the feed's
--   software_content_type), and its messageFormatVersion is a regex that must match the whole software_version.
--
-- Fields
--   format       upper case; the value stored in the System XML and in configuration.software_content_type
--   description  shown next to the format in the drop-downs
--   sftpReader   vhp-sftpreader's ImplementationActivator has an implementation for it
--   transform    eds-messaging-core's MessageTransformInbound has an inbound transform for it
--
-- Source (2026-11-25): ImplementationActivator (vhp-sftpreader) and MessageTransformInbound (endeavour/EDS).
-- Left out on purpose: the internal BULK_DELETE_DATA / BULK_TRANSFORM_TO_SUBSCRIBER and the MessageFormat constants
-- with no inbound transform (FHIRJSON, FHIRXML, VITRUCARE, EDWXML, TABLEAU, ENTERPRISE_CSV, JSON_API, PCR_CSV,
-- SUBSCRIBER_CSV). Keep in step with MessageFormats.DEFAULTS in vhp-dds-ui (MessageFormatsTest checks they match).
--
-- Safe to re-run: it only inserts the row if there isn't one, so formats added later through the DDS UI (New Format on
-- the System page) or the Config Manager page are kept. To reset to this list, delete the row first:
--   DELETE FROM config WHERE app_id = 'global' AND config_id = 'message_formats';
-- The config table's triggers record every change in config_history.

INSERT INTO config (app_id, config_id, config_data)
SELECT 'global', 'message_formats', '[
  {"format": "EMISCSV", "description": "EMIS Web CSV extract (version CUSTOM = EMIS custom extracts)", "sftpReader": true, "transform": true},
  {"format": "TPPCSV", "description": "TPP SystmOne CSV extract", "sftpReader": true, "transform": true},
  {"format": "VISIONCSV", "description": "Vision CSV extract", "sftpReader": true, "transform": true},
  {"format": "BARTSCSV", "description": "Barts (Cerner Millennium) CSV extract", "sftpReader": true, "transform": true},
  {"format": "HOMERTONCSV", "description": "Homerton HI CSV extract", "sftpReader": true, "transform": true},
  {"format": "ADASTRACSV", "description": "Adastra CSV extract", "sftpReader": true, "transform": true},
  {"format": "BHRUTCSV", "description": "BHRUT CSV extract", "sftpReader": true, "transform": true},
  {"format": "IMPERIALCSV", "description": "Imperial / NWL extracts CSV", "sftpReader": true, "transform": true},
  {"format": "NEMSXML", "description": "NEMS XML event messages", "sftpReader": true, "transform": true},
  {"format": "EMISOPEN", "description": "EMIS Open XML (not via SFTP Reader)", "sftpReader": false, "transform": true},
  {"format": "OPENHR", "description": "EMIS Open HR XML (not via SFTP Reader)", "sftpReader": false, "transform": true},
  {"format": "TPPXML", "description": "TPP XML (not via SFTP Reader)", "sftpReader": false, "transform": true},
  {"format": "HL7V2", "description": "HL7 v2 via the HL7 Receiver (not via SFTP Reader)", "sftpReader": false, "transform": true},
  {"format": "ACUTEHL7V2", "description": "Acute HL7 v2 (not via SFTP Reader)", "sftpReader": false, "transform": true},
  {"format": "ADASTRA_XML", "description": "Adastra XML (not via SFTP Reader)", "sftpReader": false, "transform": true}
]'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM config WHERE app_id = 'global' AND config_id = 'message_formats');

-- Check:
--   SELECT config_data FROM config WHERE app_id = 'global' AND config_id = 'message_formats';
-- and, to find Systems or feeds whose format is not in the list, compare with:
--   admin DB:          SELECT i.title, ExtractValue(i.xml_content, '//technicalInterface/messageFormat') AS format,
--                             ExtractValue(i.xml_content, '//technicalInterface/messageFormatVersion') AS version
--                      FROM active_item a JOIN item i ON i.id = a.item_id AND i.audit_id = a.audit_id
--                      WHERE a.item_type_id = 9 AND NOT a.is_deleted;
--   SFTP Reader DB:    SELECT configuration_id, software_content_type, software_version FROM configuration;
