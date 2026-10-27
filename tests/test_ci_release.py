import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

path = Path(__file__).parents[1] / 'tools/ci_release.py'
spec = importlib.util.spec_from_file_location('ci_release', path)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class ReleaseGuardTest(unittest.TestCase):
    def payload(self, folder):
        apk = folder / 'hongguotv-0.11.1-android14-accounts.apk'
        apk.write_bytes(b'synthetic APK bytes')
        return dict(tag='v0.11.1', sourceSha='a'*40, buildRunId=123,
                    apk=apk.name, apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                    certificateSha256=release.CERTIFICATE, versionName='0.11.1-android14-accounts', versionCode=15)

    def test_valid_payload_is_bound_to_exact_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d); data=self.payload(folder)
            self.assertEqual(data, release.validate_payload(data,folder))

    def test_rejects_path_env_injection_and_wrong_certificate(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d); data=self.payload(folder)
            for key,value in [('apk','../outside.apk'),('sourceSha','main\nBAD=1'),('tag','v0.11.1\nBAD=1'),('certificateSha256','b'*64),('buildRunId',True),('versionCode',0)]:
                bad=copy.copy(data);bad[key]=value
                with self.subTest(key=key),self.assertRaises(ValueError):release.validate_payload(bad,folder)

    def test_rejects_modified_apk_and_unexpected_fields(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d); data=self.payload(folder); (folder/data['apk']).write_bytes(b'changed')
            with self.assertRaises(ValueError):release.validate_payload(data,folder)
            data=self.payload(folder);data['privateKey']='must never enter payload'
            with self.assertRaises(ValueError):release.validate_payload(data,folder)

    def test_unit_report_counts_failures_errors_and_skips(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d); report=folder/'TEST-one.xml'
            report.write_text('<testsuite tests="3" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(3,release.unit_summary(folder)['tests'])
            for attr in ['failures','errors','skipped']:
                report.write_text(f'<testsuite tests="3" {attr}="1"/>')
                with self.subTest(attr=attr),self.assertRaises(ValueError):release.unit_summary(folder)

    def test_rejects_missing_test_reports(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):release.unit_summary(Path(d))

    def test_signed_payload_matches_actual_build_zip_content(self):
        with tempfile.TemporaryDirectory() as d:
            unsigned=Path(d)/'unsigned.apk';signed=Path(d)/'signed.apk'
            for p in (unsigned,signed):
                with zipfile.ZipFile(p,'w') as z:z.writestr('classes.dex',b'actual CI classes');z.writestr('AndroidManifest.xml',b'manifest')
            with zipfile.ZipFile(signed,'a') as z:z.writestr('META-INF/CERT.SF',b'signature');z.writestr('META-INF/CERT.RSA',b'signature');z.writestr('META-INF/MANIFEST.MF',b'signed entry hashes')
            report={'sourceSha':'a'*40,'unsignedApkSha256':hashlib.sha256(unsigned.read_bytes()).hexdigest()}
            release.verify_ci_binding(report,report,unsigned,signed)
            with zipfile.ZipFile(signed,'w') as z:z.writestr('classes.dex',b'old same-version APK');z.writestr('AndroidManifest.xml',b'manifest')
            with self.assertRaises(ValueError):release.verify_ci_binding(report,report,unsigned,signed)

    def test_build_report_and_actual_unsigned_bytes_cannot_be_substituted(self):
        with tempfile.TemporaryDirectory() as d:
            apk=Path(d)/'app.apk'
            with zipfile.ZipFile(apk,'w') as z:z.writestr('classes.dex',b'CI classes')
            report={'sourceSha':'a'*40,'unsignedApkSha256':hashlib.sha256(apk.read_bytes()).hexdigest()}
            with self.assertRaises(ValueError):release.verify_ci_binding({**report,'sourceSha':'b'*40},report,apk,apk)
            with self.assertRaises(ValueError):release.verify_ci_binding({**report,'unsignedApkSha256':'0'*64},{**report,'unsignedApkSha256':'0'*64},apk,apk)

    def test_draft_is_selected_by_id_without_a_published_tag(self):
        draft={'id':7,'tag_name':'v0.11.1','target_commitish':'a'*40,'draft':True}
        self.assertEqual(7,release.pick_draft([draft],'v0.11.1','a'*40)['id'])
        for bad in [{**draft,'draft':False},{**draft,'target_commitish':'b'*40}]:
            with self.assertRaises(ValueError):release.pick_draft([bad],'v0.11.1','a'*40)
        with self.assertRaises(ValueError):release.pick_draft([draft,draft],'v0.11.1','a'*40)

    def test_existing_release_assets_must_match_before_resume(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'app.apk';p.write_bytes(b'verified')
            asset={'name':p.name,'size':p.stat().st_size,'digest':'sha256:'+hashlib.sha256(p.read_bytes()).hexdigest()}
            self.assertEqual([],release.missing_assets([asset],[p]))
            self.assertEqual([p],release.missing_assets([],[p]))
            with self.assertRaises(ValueError):release.missing_assets([{**asset,'digest':'sha256:'+'0'*64}],[p])
            with self.assertRaises(ValueError):release.missing_assets([{**asset,'name':'unrelated.apk'}],[p])

if __name__ == '__main__':unittest.main()
