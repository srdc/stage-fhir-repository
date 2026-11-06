"""
Generates the STAGE ValueSets of fhir-conf/valueset:

- the ValueSets of the "ValueSets" sheet of FHIR_profiles.xlsx (WP7 profiling)
- the LIVA goal ValueSet of liva-goal-valueset.xlsx (WP7 profiling)
- one "shc-<service>" ValueSet per CDS service (cds-conf, formerly smart-cds): the codes of the data the service fetches, used as the
  credentialValueSet of $health-cards-issue when a Smart Health Card is made for that service (the same codes the
  smart-apps health card profiles compute: prefetch code filters resolved from the onfhir.cds configuration, or the
  concept definition codes when a filter has no configured codes)

The ValueSets of cds-conf/valueset are copied as they are.

Usage (from the STAGE folder, needs openpyxl):
  python stage-fhir-repository/scripts/generate_valuesets.py
"""
import json
import os
import re
import shutil
from datetime import date
from urllib.parse import unquote

import openpyxl

STAGE = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PROFILING = os.path.join(STAGE, 'Documents', 'WP7', 'profiling')
REPOSITORY = os.path.join(STAGE, 'stage-fhir-repository')
CDS = os.path.join(REPOSITORY, 'cds-conf')
OUT = os.path.join(REPOSITORY, 'fhir-conf', 'valueset')

CANONICAL_BASE = 'http://stage-healthyaging.eu/fhir/ValueSet/'
TODAY = date.today().isoformat()
# Health card profiles do not use these services (infrastructure, or no clinical data)
UTILITY_SERVICES = {'definition', 'valueset', 'in-memory-search'}
CODE_PARAMS = {'Immunization': 'vaccine-code'}


def value_set(vs_id, url, title, description, include):
    return {
        'resourceType': 'ValueSet',
        'id': vs_id,
        'url': url,
        'name': re.sub(r'(^|[^A-Za-z0-9])([A-Za-z0-9])', lambda m: m.group(2).upper(), vs_id),
        'title': title,
        'status': 'active',
        'date': TODAY,
        'publisher': 'STAGE',
        'description': description,
        'compose': {'include': include}
    }


def write(resource):
    with open(os.path.join(OUT, resource['id'] + '.json'), 'w', encoding='utf-8') as f:
        json.dump(resource, f, indent=2, ensure_ascii=False)
        f.write('\n')


def text(value):
    return None if value is None else str(value).strip() or None


def profile_sheet_value_sets():
    """Blocks of the ValueSets sheet: a row with the ValueSet URL, a header row, then (system, code, display en, display fi) rows;
    an empty system repeats the previous one"""
    sheet = openpyxl.load_workbook(os.path.join(PROFILING, 'FHIR_profiles.xlsx'), read_only=True, data_only=True)['ValueSets']
    value_sets, current, system = [], None, None
    for row in sheet.iter_rows(values_only=True):
        cells = [text(c) for c in row[:4]] + [None] * (4 - len(row[:4]))
        first, code, display, display_fi = cells
        if first and first.startswith('http') and '/ValueSet/' in first and not code:
            current = {'url': first, 'concepts': []}
            value_sets.append(current)
            system = None
        elif current is None or first == 'Code System' or not code:
            continue
        else:
            system = first or system
            concept = {'code': code, 'display': display}
            if display_fi and display_fi != display:
                concept['designation'] = [{'language': 'fi', 'value': display_fi}]
            current['concepts'].append((system, concept))
    for vs in value_sets:
        vs_id = vs['url'].rstrip('/').split('/')[-1]
        include = {}
        for system, concept in vs['concepts']:
            include.setdefault(system, []).append(concept)
        write(value_set(vs_id, vs['url'], vs_id.replace('-', ' ').title(), 'STAGE ValueSet from the ValueSets sheet of FHIR_profiles.xlsx (WP7 profiling).',
                        [{'system': s, 'concept': c} for s, c in include.items()]))
    return [vs['url'] for vs in value_sets]


def liva_goal_value_set():
    """Interventions of liva-goal-valueset.xlsx: the clinical concept a LIVA goal targets (the intervention name is a designation)"""
    sheet = openpyxl.load_workbook(os.path.join(PROFILING, 'liva-goal-valueset.xlsx'), read_only=True, data_only=True).worksheets[0]
    include, system = {}, None
    for i, row in enumerate(sheet.iter_rows(values_only=True)):
        if i < 2:
            continue  # two header rows
        intervention, row_system, code, display = [text(c) for c in row[:4]]
        if not code:
            continue
        system = row_system or system
        concept = {'code': code, 'display': display}
        if intervention:
            concept['designation'] = [{'language': 'en', 'use': {'system': 'http://snomed.info/sct', 'code': '900000000000013009', 'display': 'Synonym'}, 'value': intervention}]
        include.setdefault(system, []).append(concept)
    write(value_set('liva-goal', CANONICAL_BASE + 'liva-goal', 'LIVA Goal Targets',
                    'Clinical concepts targeted by LIVA goals (liva-goal-valueset.xlsx, WP7 profiling); the intervention is given as a designation.',
                    [{'system': s, 'concept': c} for s, c in include.items()]))


def parse_services_config(path):
    """onfhir.cds.services of the repository application.conf: {service: {concept: ["system|code", ...]}} (the HOCON subset it uses)"""
    def strip_comment(line):
        in_quotes = False
        for i, ch in enumerate(line):
            if ch == '"':
                in_quotes = not in_quotes
            elif not in_quotes and (ch == '#' or line.startswith('//', i)):
                return line[:i]
        return line

    with open(path, encoding='utf-8') as f:
        lines = [strip_comment(line).rstrip() for line in f]
    services, stack, collecting = {}, [], None
    for line in lines:
        stripped = line.strip()
        if not stripped:
            continue
        if collecting is not None:
            collecting['values'] += re.findall(r'"([^"]*)"', stripped)
            if ']' in stripped:
                collecting = None
            continue
        m = re.match(r'^([\w.\-"]+)\s*=?\s*\{$', stripped)
        if m:
            stack.append(m.group(1).strip('"'))
            continue
        if stripped == '}':
            if stack:
                stack.pop()
            continue
        m = re.match(r'^([\w\-"]+)\s*=\s*\[(.*)$', stripped)
        if m and len(stack) >= 3 and stack[0] == 'onfhir.cds' and stack[1] == 'services':
            entry = {'values': re.findall(r'"([^"]*)"', m.group(2))}
            services.setdefault(stack[2], {})[m.group(1).strip('"')] = entry
            if ']' not in m.group(2):
                collecting = entry
    return {svc: {k: v['values'] for k, v in concepts.items()} for svc, concepts in services.items()}


def query_codes(query, resource_type, config):
    """Codes of a prefetch query: 'all' without a code filter, 'unresolved' when a placeholder has no configured codes"""
    param = CODE_PARAMS.get(resource_type, 'code')
    values = [unquote(p.split('=', 1)[1]) if '=' in p else '' for p in (query.split('?', 1) + [''])[1].split('&') if p.split('=')[0] == param]
    if not values:
        return 'all'
    codes = []
    for value in values:
        for placeholder in re.findall(r'\{\{config\.([\w\-]+)\}\}', value):
            if not config.get(placeholder):
                return 'unresolved'
            value = value.replace('{{config.%s}}' % placeholder, ','.join(config[placeholder]))
        if '{{' in value or not value.strip():
            return 'unresolved'
        for token in value.split(','):
            parts = token.split('|')
            if len(parts) == 2 and parts[0] and parts[1]:
                codes.append({'system': parts[0], 'code': parts[1]})
    return codes or 'unresolved'


def definition_codes(concept):
    candidates = [concept.get('code')] + (concept.get('select') if concept.get('type') != 'CodeableConcept' and isinstance(concept.get('select'), list) else [])
    return [{'system': c['system'], 'code': c['code'], **({'display': c['display']} if c.get('display') else {})}
            for c in candidates if isinstance(c, dict) and c.get('system') and c.get('code')]


def service_value_sets():
    config = parse_services_config(os.path.join(REPOSITORY, 'src', 'main', 'resources', 'application.conf'))
    definitions = os.path.join(CDS, 'definitions')
    written = []
    for file in sorted(os.listdir(os.path.join(definitions, 'services'))):
        service = json.load(open(os.path.join(definitions, 'services', file), encoding='utf-8'))
        service_id = service.get('id') or file[:-5]
        concepts_file = os.path.join(definitions, 'concepts', service_id + '.json')
        if service_id in UTILITY_SERVICES or not os.path.exists(concepts_file):
            continue
        concepts = json.load(open(concepts_file, encoding='utf-8')).get('concepts', {})
        prefetch = service.get('prefetch', {})
        by_code = {}
        for concept_id, concept in concepts.items():
            query = prefetch.get(concept_id)
            resource_type = concept.get('resourceType') or (query.split('?')[0].split('/')[0] if query else None)
            if not resource_type or resource_type == 'Patient':
                continue
            codes = query_codes(query, resource_type, config.get(service_id, {})) if query else 'unresolved'
            if codes == 'unresolved':
                codes = definition_codes(concept)
            if codes == 'all':
                continue  # every resource of the type: no code filter needed
            for coding in codes:
                by_code.setdefault((coding['system'], coding['code']), coding)
        if not by_code:
            continue
        include = {}
        for coding in by_code.values():
            include.setdefault(coding['system'], []).append({k: v for k, v in coding.items() if k != 'system'})
        vs_id = ('shc-' + service_id).lower().replace('_', '-')
        title = service.get('title') or service_id
        write(value_set(vs_id, CANONICAL_BASE + vs_id, f'{title} - codes for Smart Health Cards',
                        f'Codes of the data the "{service_id}" CDS service uses: the credentialValueSet of $health-cards-issue for its Smart Health Card.',
                        [{'system': s, 'concept': c} for s, c in include.items()]))
        written.append(vs_id)
    return written


def copy_cds_value_sets():
    folder = os.path.join(CDS, 'valueset')
    copied = []
    for file in sorted(os.listdir(folder)):
        if file.endswith('.json'):
            shutil.copyfile(os.path.join(folder, file), os.path.join(OUT, file))
            copied.append(file)
    return copied


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    print('FHIR_profiles.xlsx:', profile_sheet_value_sets())
    liva_goal_value_set()
    print('liva-goal-valueset.xlsx: liva-goal')
    print('CDS services:', service_value_sets())
    print('cds-conf/valueset:', copy_cds_value_sets())
