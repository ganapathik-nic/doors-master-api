-- Apply explicitly to each approved GePNIC target, never to the Master registry DB.
-- Run as a dedicated schema owner. Runtime users must NOT own these objects.
BEGIN;
CREATE SCHEMA doors_inbound;
REVOKE ALL ON SCHEMA doors_inbound FROM PUBLIC;

CREATE TABLE doors_inbound.tender_write_grant (
 instance_id text NOT NULL, client_id bigint NOT NULL, tender_id text NOT NULL,
 enabled boolean NOT NULL DEFAULT false,
 PRIMARY KEY(instance_id,client_id,tender_id)
);
CREATE TABLE doors_inbound.agreement_head (
 instance_id text NOT NULL, tender_id text NOT NULL, agreement_no text NOT NULL,
 current_version bigint,
 PRIMARY KEY(instance_id,tender_id,agreement_no)
);
CREATE TABLE doors_inbound.agreement_version (
 instance_id text NOT NULL, tender_id text NOT NULL, agreement_no text NOT NULL,
 record_version bigint NOT NULL CHECK(record_version>0),
 schema_version integer NOT NULL CHECK(schema_version=1),
 agreement_date date NOT NULL, vendor_id text, department_user_name text,
 tender_nit_ref_no text, loa_ref_no text,
 payload json NOT NULL, payload_sha256 char(64) NOT NULL,
 submitted_by bigint NOT NULL, request_id uuid NOT NULL,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(instance_id,tender_id,agreement_no,record_version),
 FOREIGN KEY(instance_id,tender_id,agreement_no) REFERENCES doors_inbound.agreement_head,
 CHECK(json_typeof(payload)='object'),
 CHECK(payload->>'tender_id' IS NOT NULL AND payload->>'tender_id'=tender_id),
 CHECK(payload->>'agreement_no' IS NOT NULL AND payload->>'agreement_no'=agreement_no)
);
ALTER TABLE doors_inbound.agreement_head ADD CONSTRAINT agreement_current_fk
 FOREIGN KEY(instance_id,tender_id,agreement_no,current_version)
 REFERENCES doors_inbound.agreement_version(instance_id,tender_id,agreement_no,record_version);
CREATE TABLE doors_inbound.write_receipt (
 instance_id text NOT NULL, client_id bigint NOT NULL, service_code text NOT NULL,
 request_id uuid NOT NULL, request_sha256 char(64) NOT NULL, response_json jsonb NOT NULL,
 committed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(instance_id,client_id,service_code,request_id)
);
CREATE VIEW doors_inbound.agreement_latest AS
 SELECT v.* FROM doors_inbound.agreement_head h
 JOIN doors_inbound.agreement_version v USING(instance_id,tender_id,agreement_no)
 WHERE v.record_version=h.current_version;

CREATE FUNCTION doors_inbound.store_agreement(
 p_instance text,p_client bigint,p_request uuid,p_schema integer,p_expected bigint,
 p_fingerprint text,p_payload json,p_hash text) RETURNS jsonb
 LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,doors_inbound SET lock_timeout='5s' AS $$
DECLARE
 tender text:=p_payload->>'tender_id'; agreement text:=p_payload->>'agreement_no';
 previous bigint; revision bigint; receipt doors_inbound.write_receipt%ROWTYPE;
 answer jsonb; stored_at timestamptz:=clock_timestamp();
BEGIN
 IF p_schema IS DISTINCT FROM 1 OR p_expected IS NULL OR p_expected<0
    OR p_expected=9223372036854775807 OR p_request IS NULL OR p_client IS NULL
    OR p_instance IS NULL OR p_instance !~ '^[A-Za-z0-9_-]{1,80}$'
    OR p_fingerprint IS NULL OR p_fingerprint !~ '^[a-f0-9]{64}$'
    OR p_hash IS NULL OR p_hash !~ '^[a-f0-9]{64}$'
    OR tender IS NULL OR tender='' OR tender<>btrim(tender) OR length(tender)>100
    OR agreement IS NULL OR agreement='' OR agreement<>btrim(agreement) OR length(agreement)>200
    OR p_payload->>'agreement_date' IS NULL THEN
   RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invalid agreement metadata';
 END IF;
 -- Grant rows are provisioned only after GePNIC confirms tender existence and ownership.
 IF NOT EXISTS(SELECT 1 FROM doors_inbound.tender_write_grant
   WHERE instance_id=p_instance AND client_id=p_client AND tender_id=tender AND enabled) THEN
   RAISE EXCEPTION USING ERRCODE='DW003',MESSAGE='Tender write access denied';
 END IF;
 -- Lock a request before checking its receipt, including concurrent first submissions.
 PERFORM pg_advisory_xact_lock(hashtextextended(
   jsonb_build_array('agreements',p_instance,p_client,p_request)::text,0));
 SELECT * INTO receipt FROM doors_inbound.write_receipt
   WHERE instance_id=p_instance AND client_id=p_client AND service_code='agreements'
     AND request_id=p_request;
 IF FOUND THEN
   IF receipt.request_sha256<>p_fingerprint THEN
     RAISE EXCEPTION USING ERRCODE='DW002',MESSAGE='Request ID already used';
   END IF;
   RETURN receipt.response_json || '{"replayed":true}'::jsonb;
 END IF;
 INSERT INTO doors_inbound.agreement_head(instance_id,tender_id,agreement_no)
   VALUES(p_instance,tender,agreement) ON CONFLICT DO NOTHING;
 SELECT current_version INTO previous FROM doors_inbound.agreement_head
   WHERE instance_id=p_instance AND tender_id=tender AND agreement_no=agreement FOR UPDATE;
 IF p_expected<>coalesce(previous,0) THEN
   RAISE EXCEPTION USING ERRCODE='DW001',MESSAGE='Expected version does not match current';
 END IF;
 revision:=coalesce(previous,0)+1;
 INSERT INTO doors_inbound.agreement_version(instance_id,tender_id,agreement_no,
   record_version,schema_version,agreement_date,vendor_id,department_user_name,
   tender_nit_ref_no,loa_ref_no,payload,payload_sha256,submitted_by,request_id,received_at)
 VALUES(p_instance,tender,agreement,revision,p_schema,(p_payload->>'agreement_date')::date,
   p_payload->'party_1'->>'vendor_id',p_payload->'party_2'->>'user_name',
   p_payload->>'tender_nit_ref_no',p_payload->>'loa_ref_no',p_payload,p_hash,p_client,p_request,stored_at);
 UPDATE doors_inbound.agreement_head SET current_version=revision
   WHERE instance_id=p_instance AND tender_id=tender AND agreement_no=agreement;
 answer:=jsonb_build_object('requestId',p_request,'instanceId',p_instance,'service','agreements',
   'tenderId',tender,'agreementNo',agreement,'recordVersion',revision,'schemaVersion',p_schema,
   'outcome','STORED','replayed',false,'storedAt',stored_at,'payloadSha256',p_hash);
 INSERT INTO doors_inbound.write_receipt(instance_id,client_id,service_code,request_id,request_sha256,response_json)
 VALUES(p_instance,p_client,'agreements',p_request,p_fingerprint,answer);
 RETURN answer;
END $$;
REVOKE ALL ON ALL TABLES IN SCHEMA doors_inbound FROM PUBLIC;
REVOKE ALL ON FUNCTION doors_inbound.store_agreement(text,bigint,uuid,integer,bigint,text,json,text) FROM PUBLIC;
-- After creating dedicated non-owner roles:
-- GRANT USAGE ON SCHEMA doors_inbound TO doors_inbound_writer, gepnic_inbound_reader;
-- GRANT EXECUTE ON FUNCTION doors_inbound.store_agreement(text,bigint,uuid,integer,bigint,text,json,text) TO doors_inbound_writer;
-- GRANT SELECT ON doors_inbound.agreement_latest, doors_inbound.agreement_version TO gepnic_inbound_reader;
COMMIT;
