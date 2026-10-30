"""Release guards. Signing material is never an input to this module."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile
import urllib.request
import urllib.parse

CERTIFICATE = '76e72e760843107a1640ad8544fe46b5772e28fa00b8708ba414d5173ea3c9fa'

def unit_summary(folder):
    suites = [ET.parse(p).getroot() for p in Path(folder).glob('TEST-*.xml')]
    counts = {k: sum(int(s.get(k, '0')) for s in suites) for k in ('tests','failures','errors','skipped')}
    if counts['tests'] <= 0 or any(counts[k] for k in ('failures','errors','skipped')):
        raise ValueError(f'Unit reports are missing or not green: {counts}')
    return counts

def validate_payload(data, folder):
    fields = {'tag','sourceSha','buildRunId','apk','apkSha256','certificateSha256','versionName','versionCode'}
    if set(data) != fields:
        raise ValueError('Unexpected or missing manifest fields')
    def match(key, pattern):
        if not isinstance(data[key], str) or not re.fullmatch(pattern, data[key]):
            raise ValueError(f'Invalid {key}')
    match('sourceSha',r'[0-9a-f]{40}')
    match('tag',r'v\d+\.\d+\.\d+')
    match('versionName',r'\d+\.\d+\.\d+-android14-(?:accounts|transitions)')
    match('apkSha256',r'[0-9a-f]{64}')
    if data['tag'] != 'v'+data['versionName'].split('-')[0] or data['certificateSha256'] != CERTIFICATE:
        raise ValueError('Version tag or signing certificate mismatch')
    if any(type(data[k]) is not int or not 0 < data[k] < 2**63 for k in ('buildRunId','versionCode')):
        raise ValueError('Invalid numeric metadata')
    if data['apk'] != 'hongguotv-'+data['versionName']+'.apk':
        raise ValueError('APK must use the expected basename')
    folder=Path(folder).resolve();apk=folder/data['apk']
    if apk.is_symlink() or apk.resolve().parent != folder or not apk.is_file():
        raise ValueError('APK is missing or outside payload folder')
    if hashlib.sha256(apk.read_bytes()).hexdigest() != data['apkSha256']:
        raise ValueError('APK content hash mismatch')
    return data

def verify_ci_binding(payload_report, actual_report, unsigned_apk, signed_apk):
    if payload_report != actual_report:
        raise ValueError('Payload report differs from the actual build artifact')
    if hashlib.sha256(Path(unsigned_apk).read_bytes()).hexdigest() != actual_report['unsignedApkSha256']:
        raise ValueError('Unsigned APK differs from its actual build report')
    def contents(path):
        result={}
        with zipfile.ZipFile(path) as archive:
            names=archive.namelist()
            if len(names)!=len(set(names)):raise ValueError('Duplicate APK ZIP entries')
            for name in names:
                if name.endswith('/') or re.fullmatch(r'META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))',name,re.I):continue
                result[name]=hashlib.sha256(archive.read(name)).hexdigest()
        return result
    if contents(unsigned_apk)!=contents(signed_apk):
        raise ValueError('Signed APK does not contain the actual CI-built application')

def pick_draft(releases, tag, source):
    candidates=[r for r in releases if r['tag_name']==tag or
                (r.get('draft') and r['tag_name'].startswith('untagged-') and
                 r.get('target_commitish')==source and tag[1:] in r.get('name',''))]
    if not candidates:return None
    if len(candidates)!=1:raise ValueError('Ambiguous release drafts')
    draft=candidates[0]
    if draft.get('draft') is not True or draft.get('target_commitish')!=source:
        raise ValueError('Existing published release or different-source draft cannot be overwritten')
    return draft

def missing_assets(assets, files):
    present={x['name']:x for x in assets};expected={p.name:p for p in files}
    if len(present)!=len(assets) or set(present)-set(expected):raise ValueError('Unexpected release assets')
    for name,asset in present.items():
        data=expected[name].read_bytes()
        if asset['size']!=len(data) or asset.get('digest')!='sha256:'+hashlib.sha256(data).hexdigest():
            raise ValueError('Existing draft asset does not match verified payload')
    return [p for name,p in expected.items() if name not in present]

def publish_release(folder):
    repo=os.environ['GITHUB_REPOSITORY'];tag=os.environ['RELEASE_TAG'];source=os.environ['SOURCE_SHA']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',repo):raise ValueError('Invalid repository')
    def api(method,path,body=None,binary=None):
        url='https://api.github.com/'+path if not path.startswith('https://') else path
        if urllib.parse.urlparse(url).hostname not in {'api.github.com','uploads.github.com'}:raise ValueError('Unexpected API host')
        headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'User-Agent':'hongguotv-release','Accept':'application/vnd.github+json'}
        data=json.dumps(body).encode() if body is not None else binary
        if data is not None:headers['Content-Type']='application/json' if body is not None else 'application/octet-stream'
        with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers,method=method),timeout=120) as response:return json.load(response)
    endpoint='repos/'+repo+'/releases';folder=Path(folder)
    files=[p for p in folder.iterdir() if p.name!='NOTES.md' and p.is_file()]
    draft=pick_draft(api('GET',endpoint+'?per_page=100'),tag,source)
    if draft is None:
        draft=api('POST',endpoint,dict(tag_name=tag,target_commitish=source,name='红果 TV '+os.environ['VERSION_NAME'],body=(folder/'NOTES.md').read_text(encoding='utf8'),draft=True))
    for file in missing_assets(draft['assets'],files):
        upload=draft['upload_url'].split('{')[0]+'?name='+urllib.parse.quote(file.name,safe='')
        api('POST',upload,binary=file.read_bytes())
    current=api('GET',endpoint+'/'+str(draft['id']))
    if current.get('draft') is not True or missing_assets(current['assets'],files):raise ValueError('Draft verification incomplete')
    published=api('PATCH',endpoint+'/'+str(draft['id']),dict(tag_name=tag,draft=False,make_latest='true'))
    if published['draft'] or missing_assets(published['assets'],files):raise ValueError('Publication verification failed')
    (folder/'published-release.json').write_text(json.dumps(published,indent=2),encoding='utf8')
    print(published['html_url'])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'],'a',encoding='utf8') as stream:stream.write('Published: '+published['html_url']+'\n')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['summarize','payload','compare','publish']);parser.add_argument('--artifact',type=Path);parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    if args.command=='publish':
        if not args.output:raise ValueError('Release output directory required')
        publish_release(args.output)
    elif args.command=='summarize':
        core=unit_summary('kotlin-tv/core/build/test-results/test')
        app=unit_summary('kotlin-tv/app/build/test-results/testDebugUnitTest')
        issues=ET.parse('kotlin-tv/app/build/reports/lint-results-debug.xml').getroot().findall('issue')
        errors=[i.get('id') for i in issues if i.get('severity') in {'Error','Fatal'} or i.get('id')=='NewApi']
        if errors:raise ValueError(f'Lint errors: {errors}')
        apk=Path('kotlin-tv/app/build/outputs/apk/release/app-release-unsigned.apk')
        report=dict(schemaVersion=1,repository=os.environ['GITHUB_REPOSITORY'],sourceSha=os.environ['GITHUB_SHA'],
                    buildRunId=int(os.environ['GITHUB_RUN_ID']),core=core,app=app,lintErrors=errors,
                    unsignedApkSha256=hashlib.sha256(apk.read_bytes()).hexdigest())
        out=Path('release-output');out.mkdir(exist_ok=True)
        (out/'build-verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
    elif args.command=='compare':
        if not args.artifact:raise ValueError('Actual build artifact directory required')
        unsigned=list(args.artifact.rglob('*unsigned.apk'));reports=list(args.artifact.rglob('build-verification.json'))
        if len(unsigned)!=1 or len(reports)!=1:raise ValueError('Build artifact must contain one APK and one report')
        data=validate_payload(json.loads(Path('.release/manifest.json').read_text()),Path('.release'))
        report=json.loads(Path('.release/build-verification.json').read_text())
        verify_ci_binding(report,json.loads(reports[0].read_text()),unsigned[0],Path('.release')/data['apk'])
    else:
        folder=Path('.release');data=validate_payload(json.loads((folder/'manifest.json').read_text()),folder)
        report=json.loads((folder/'build-verification.json').read_text())
        if report['sourceSha']!=data['sourceSha'] or report['buildRunId']!=data['buildRunId'] or report['repository']!=os.environ['GITHUB_REPOSITORY']:
            raise ValueError('Build report does not match the signed payload')
        for module in ('core','app'):
            counts=report[module]
            if counts['tests']<=0 or any(counts.get(k,0) for k in ('failures','errors','skipped')):raise ValueError('Build tests failed')
        if report['lintErrors']:raise ValueError('Build lint failed')
        exports=dict(SOURCE_SHA=data['sourceSha'],RELEASE_TAG=data['tag'],APK_NAME=data['apk'],
                     APK_FILE='.release/'+data['apk'],CERT_SHA256=data['certificateSha256'],
                     VERSION_NAME=data['versionName'],VERSION_CODE=str(data['versionCode']),BUILD_RUN_ID=str(data['buildRunId']))
        with open(os.environ['GITHUB_ENV'],'a',encoding='utf8') as stream:
            for key,value in exports.items():stream.write(f'{key}={value}\n')

if __name__=='__main__':main()
