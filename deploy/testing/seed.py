#!/usr/bin/env python3
"""
Test patients of https://stage.testing.eu (see README.md).

  python seed.py export [--source http://127.0.0.1:7799/fhir]
      Reads the test patients from a FHIR server without authorization (e.g. a temporary repository on a copy of a local
      database) and writes them as batch bundles into local/seed/<repository>.json (gitignored).
  python seed.py load
      Writes local/seed/*.json into the repositories through nginx (PUT, so it can be repeated), with a token of the
      stage-services client of realm smart (setup.sh, local/stage-services.secret).

STAGE gets the patients of the realm's patient users (Patient id = the user's patient id); users without a record in
the source get a copy of another source patient's record. EHR gets the other patients of the source (clinicians select
them): no record of a STAGE patient is in EHR, so it can be imported from STAGE (xShare) in a demo.

STAGE also gets the WP6/WP7 feature extraction patients of the fhir-path-feature-extraction checkout
(FEATURE_EXTRACTION_DIR, default ../../../fhir-path-feature-extraction), as their transaction bundles (fixed ids, PUT):
the storyboard patients sb-p1..sb-p4 (storyboard_patients/baseline), wp6-test-complete (test_data) and the profiles
123456..523456 (tests/test_data). Loading them again overwrites their baseline resources; to also remove what a demo
run added (saved forms, care plans, ...) use storyboard_patients/reset_patients.py with a token.
"""
import os
import argparse
import base64
import json
import pathlib
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
SEED_DIR = HERE / 'local' / 'seed'
HOST = 'https://stage.testing.eu'
REPOSITORIES = {'stage': HOST + '/stage-fhir', 'ehr': HOST + '/ehr'}
TOKEN_ENDPOINT = HOST + '/stage-onauth/api/smart/token'

# The patients' clinical data (the SMART Health Card documents of xShare are created by the apps)
TYPES = ['Observation', 'Condition', 'MedicationStatement', 'FamilyMemberHistory', 'Goal', 'CarePlan']

# Patient users of realm smart (stage-onauth/conf/realms/smart/users): (source patient id, patient id, name, gender)
STAGE_PATIENTS = [
    ('6fd322a8-9cb6-4cb2-b672-7fa5a35ff741', '6fd322a8-9cb6-4cb2-b672-7fa5a35ff741', None, None),  # helen_wright
    ('49d47569-6e48-49fa-b709-bfe13cffe084', 'pa01', ('Ahmet', 'Kilic'), 'male'),                  # ahmet_kilic
    ('295e1a06-5b7b-4176-9ecf-c482e4190f39', 'pa-demo-01', ('John', 'Wick'), 'male'),              # john_wick
    ('b403943f-76f7-47e0-9687-28aa93504fbe', 'b403943f-76f7-47e0-9687-28aa93504fbe', None, None),  # susan_smith (xShare demo: IPS exported from STAGE, imported into EHR)
]
# Source patients whose records are in STAGE: not in EHR
STAGE_SOURCE_PATIENTS = {source_id for source_id, _, _, _ in STAGE_PATIENTS}


def in_ehr(bundle):
    return bundle['entry'][0]['resource']['id'] not in STAGE_SOURCE_PATIENTS


# Transaction bundles of the fhir-path-feature-extraction checkout, loaded into STAGE
FEATURE_EXTRACTION_DIR = pathlib.Path(os.environ.get('FEATURE_EXTRACTION_DIR', HERE / '../../../fhir-path-feature-extraction'))
FEATURE_EXTRACTION_BUNDLES = ['storyboard_patients/baseline/*-bundle.json', 'test_data/wp6-complete-patient-bundle.json',
                              'tests/test_data/profile*.json']


def feature_extraction_bundles():
    if not FEATURE_EXTRACTION_DIR.is_dir():
        print(f'{FEATURE_EXTRACTION_DIR} not found: the feature extraction patients are not loaded')
        return []
    return [p for pattern in FEATURE_EXTRACTION_BUNDLES for p in sorted(FEATURE_EXTRACTION_DIR.glob(pattern))]


# Practitioner users of realm smart (the apps read the user's own record), in both repositories
PRACTITIONERS = [
    {'resourceType': 'Practitioner', 'id': '3c670b96-8657-4fe7-bc48-b4352a2b5284',  # anna_svensson
     'identifier': [{'system': 'http://www.inera.se/TJANSTER--PROJEKT/HSA/', 'value': '3c670b96-8657-4fe7-bc48-b4352a2b5284'}],
     'name': [{'family': 'Svensson', 'given': ['Anna']}]},
]


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'Accept': 'application/fhir+json'})) as r:
        return json.load(r)


def search_all(url):
    resources = []
    while url:
        bundle = get(url)
        resources += [e['resource'] for e in bundle.get('entry', []) if e.get('search', {}).get('mode', 'match') == 'match']
        url = next((l['url'] for l in bundle.get('link', []) if l['relation'] == 'next'), None)
    return resources


def patient_record(source, patient_id):
    record = [get(f'{source}/Patient/{patient_id}')]
    for t in TYPES:
        record += search_all(f'{source}/{t}?patient=Patient/{patient_id}&_count=500')
    return record


def renamed(record, patient_id, name, gender):
    """The record as the patient patient_id: references, Patient id, name and gender"""
    source = record[0]
    text = json.dumps(record).replace(f'Patient/{source["id"]}', f'Patient/{patient_id}')
    if name:
        old = source.get('name', [{}])[0]
        old_name = ' '.join(old.get('given', []) + [old.get('family', '')]).strip()
        if old_name:
            text = text.replace(old_name, ' '.join(name))
    record = json.loads(text)
    record[0]['id'] = patient_id
    if name:
        record[0]['name'] = [{'use': 'official', 'family': name[1], 'given': [name[0]]}]
    if gender:
        record[0]['gender'] = gender
    return record


def batch(resources):
    entries = []
    for r in resources:
        r = dict(r)
        meta = {k: v for k, v in r.pop('meta', {}).items() if k not in ('versionId', 'lastUpdated')}
        if meta:
            r['meta'] = meta
        entries.append({'resource': r, 'request': {'method': 'PUT', 'url': f'{r["resourceType"]}/{r["id"]}'}})
    return {'resourceType': 'Bundle', 'type': 'batch', 'entry': entries}


def export(source):
    SEED_DIR.mkdir(parents=True, exist_ok=True)
    stage = []
    for source_id, patient_id, name, gender in STAGE_PATIENTS:
        stage.append(batch(renamed(patient_record(source, source_id), patient_id, name, gender)))
    ehr = [batch(patient_record(source, p['id'])) for p in search_all(f'{source}/Patient?_count=500')
           if p['id'] not in STAGE_SOURCE_PATIENTS]
    for repository, bundles in (('stage', stage), ('ehr', ehr)):
        (SEED_DIR / f'{repository}.json').write_text(json.dumps(bundles, indent=1), encoding='utf-8')
        print(f'{repository}: {len(bundles)} patients, {sum(len(b["entry"]) for b in bundles)} resources')


def ssl_context():
    ca_root = subprocess.check_output(['mkcert', '-CAROOT'], text=True).strip()
    return ssl.create_default_context(cafile=str(pathlib.Path(ca_root) / 'rootCA.pem'))


def token(context):
    secret = (HERE / 'local' / 'stage-services.secret').read_text().strip()
    request = urllib.request.Request(
        TOKEN_ENDPOINT,
        data=urllib.parse.urlencode({'grant_type': 'client_credentials', 'scope': 'user/*.*', 'aud': 'fhir-repo'}).encode(),
        headers={'Authorization': 'Basic ' + base64.b64encode(f'stage-services:{secret}'.encode()).decode(),
                 'Content-Type': 'application/x-www-form-urlencoded'})
    with urllib.request.urlopen(request, context=context) as r:
        return json.load(r)['access_token']


def load():
    context = ssl_context()
    access_token = token(context)
    failed = 0
    for repository, base in REPOSITORIES.items():
        bundles = [batch(PRACTITIONERS)] + json.loads((SEED_DIR / f'{repository}.json').read_text(encoding='utf-8'))
        if repository == 'ehr':
            bundles = [b for b in bundles if in_ehr(b)]
        else:
            bundles += [json.loads(p.read_text(encoding='utf-8')) for p in feature_extraction_bundles()]
        for bundle in bundles:
            request = urllib.request.Request(base, data=json.dumps(bundle).encode(), method='POST', headers={
                'Authorization': 'Bearer ' + access_token, 'Content-Type': 'application/fhir+json',
                'Accept': 'application/fhir+json'})
            with urllib.request.urlopen(request, context=context) as r:
                response = json.load(r)
            statuses = [e.get('response', {}).get('status', '') for e in response.get('entry', [])]
            errors = [s for s in statuses if not s.startswith('2')]
            failed += len(errors)
            first = bundle['entry'][0]['resource']
            print(f'{repository} {first["resourceType"]}/{first["id"]}: {len(statuses) - len(errors)} written'
                  + (f', {len(errors)} failed ({", ".join(sorted(set(errors)))})' if errors else ''))
    return failed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('command', choices=['export', 'load'])
    parser.add_argument('--source', default='http://127.0.0.1:7799/fhir', help='FHIR server of export (no authorization)')
    args = parser.parse_args()
    try:
        if args.command == 'export':
            export(args.source.rstrip('/'))
        else:
            sys.exit(1 if load() else 0)
    except urllib.error.HTTPError as e:
        sys.exit(f'{e.code} {e.url}: {e.read()[:500].decode(errors="replace")}')
