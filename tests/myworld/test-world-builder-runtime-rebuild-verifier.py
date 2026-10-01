#!/usr/bin/env python3
"""Bounded re-verification of independently rebuilt target archives."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('integration', Path(__file__).with_name('test-world-builder-target-map-integration.py'))
integration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(integration)
integration.HARNESS = integration.HARNESS.replace('if ("archive".equals(args[0])) {', '''if ("reverify".equals(args[0])) {
   Path baseline=Paths.get(args[3]); Map<String,Path> retained=new TreeMap<>();
   try(java.util.stream.Stream<Path> walk=Files.walk(baseline)) {
    walk.filter(Files::isRegularFile).forEach(p -> retained.put(baseline.relativize(p).toString().replace('\\\\','/'),p));
   }
   WorldBuilderTargetMapIntegration.Result checked=WorldBuilderTargetMapIntegration.reverifyPayload(project,target,"client",
    Files.readAllBytes(baseline.resolve(WorldBuilderTargetMapIntegration.INSTALLED)),retained);
   if(checked.outputs.size()!=1 || !checked.outputs.containsKey(WorldBuilderTargetMapIntegration.INSTALLED)) throw new AssertionError("Reverification may only write proof");
   Files.write(baseline.resolveSibling("reverified.json"),checked.outputs.get(WorldBuilderTargetMapIntegration.INSTALLED));
  } else if ("archive".equals(args[0])) {''')

class RuntimeRebuildVerifierTest(unittest.TestCase):
    setUpClass = classmethod(integration.TargetMapIntegrationTest.setUpClass.__func__)
    fixture = integration.TargetMapIntegrationTest.fixture
    probe = integration.TargetMapIntegrationTest.probe

    def integrated(self, dependency=False):
        root,target,project,contract=self.fixture()
        if dependency:
            dep=target/'server/lib/dependency.jar'; dep.parent.mkdir()
            with zipfile.ZipFile(dep,'w') as z: z.writestr('kept.txt',b'fixed dependency')
            contract['adapters'][0]['compilation'][0]['dependencyDirectories']=['server/lib']
            (project/'working/runtime/server/conf/world-builder/target-map-integration-v1.json').write_text(json.dumps(contract))
        baseline=root/'baseline'
        self.probe('prepare',project,target,baseline)
        for p in baseline.rglob('*'):
            if p.is_file() and p.name!='actions.json':
                dest=target/p.relative_to(baseline); dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
        return root,target,project,baseline

    def rewrite(self, archive, change=None, debug=None, target=None):
        with zipfile.ZipFile(archive) as z: entries={n:z.read(n) for n in z.namelist()}
        if debug is not None:
            classes=archive.parent.parent/(archive.parent.name+'-normal-classes');classes.mkdir(exist_ok=True)
            sources=list((archive.parent/'src').rglob('*.java'))
            subprocess.run(['javac','-source','8','-target','8',debug,'-proc:none','-d',str(classes),*map(str,sources)],check=True,capture_output=True)
            for p in classes.rglob('*.class'): entries[p.relative_to(classes).as_posix()]=p.read_bytes()
        if change: change(entries)
        with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as z:
            for name,content in reversed(list(entries.items())):
                info=zipfile.ZipInfo(name,(2024,2,3,4,5,6));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,content)

    def state(self,root): return {p.relative_to(root).as_posix():p.read_bytes() for p in root.rglob('*') if p.is_file()}

    def test_timestamp_rebuild_keeps_archive_bytes_and_custom_behavior(self):
        root,target,project,baseline=self.integrated()
        for path in [target/'server/core.jar',target/'client/Open_RSC_Client.jar']: self.rewrite(path)
        before=self.state(target)
        self.probe('reverify',project,target,baseline)
        self.assertEqual(before,self.state(target))
        proof=json.loads((root/'reverified.json').read_text())
        for item in proof['archives']:
            self.assertEqual(integration.sha((target/item['relativePath']).read_bytes()),item['sha256'])
        run=subprocess.run(['java','-cp',str(target/'server/core.jar'),'fixture.Engine'],check=True,capture_output=True,text=True)
        self.assertEqual('2|custom dialogue|custom serpent art|937|custom gate',run.stdout)

    def test_supported_debug_output_and_ant_manifest_metadata(self):
        root,target,project,baseline=self.integrated()
        def manifest(entries):
            value=entries['META-INF/MANIFEST.MF'].decode().replace('\r\n','\n')
            value='\n'.join(line for line in value.split('\n') if not line.startswith('World-Builder-Map-Integration:'))
            entries['META-INF/MANIFEST.MF']=(value.rstrip()+'\nCreated-By: normal JDK\nAnt-Version: Apache Ant\n\n').encode()
        for debug in ['-g:none','-g','-g:lines,source']:
            with self.subTest(debug=debug):
                for path in [target/'server/core.jar',target/'client/Open_RSC_Client.jar']: self.rewrite(path,manifest,debug)
                before=self.state(target);self.probe('reverify',project,target,baseline)
                self.assertEqual(before,self.state(target))

    def test_rejects_changed_gameplay_resources_and_runtime_manifest(self):
        root,target,project,baseline=self.integrated();archive=target/'server/core.jar';original=archive.read_bytes()
        cases=[lambda e:e.__setitem__('custom/art.bin',b'other art'),
               lambda e:e.__setitem__('fixture/Engine.class',e['fixture/Engine.class'].replace(b'custom dialogue',b'broken dialogue')),
               lambda e:e.__setitem__('META-INF/MANIFEST.MF',e['META-INF/MANIFEST.MF'].replace(b'fixture.Engine',b'fixture.Danger')),
               lambda e:e.__setitem__('new-resource',b'added'),
               lambda e:e.__setitem__('META-INF/MANIFEST.MF',e['META-INF/MANIFEST.MF'].replace(b'target-owned-layered-map-v1',b'incompatible-map-contract'))]
        for change in cases:
            archive.write_bytes(original);self.rewrite(archive,change)
            before=self.state(target);result=self.probe('reverify',project,target,baseline,ok=False)
            self.assertIn('Rebuilt archive',result.stderr) if 'incompatible map integration marker' not in result.stderr else None
            self.assertEqual(before,self.state(target))

    def test_rejects_changed_sources_and_dependencies(self):
        root,target,project,baseline=self.integrated(dependency=True)
        source=target/'server/src/fixture/Engine.java';original=source.read_bytes()
        source.write_bytes(original.replace(b'return 2;',b'return 3;'))
        self.assertIn('unchanged integrated sources',self.probe('reverify',project,target,baseline,ok=False).stderr)
        source.write_bytes(original)
        dep=target/'server/lib/dependency.jar'
        with zipfile.ZipFile(dep,'a') as z:z.writestr('changed.txt',b'changed dependency')
        self.assertIn('unchanged integrated sources',self.probe('reverify',project,target,baseline,ok=False).stderr)

    def test_rejects_added_source_inventory(self):
        root,target,project,baseline=self.integrated()
        (target/'server/src/fixture/Added.java').write_text('package fixture; public class Added {}')
        self.assertIn('inventory differs',self.probe('reverify',project,target,baseline,ok=False).stderr)

    def test_rejects_forged_or_missing_project_archive_baseline(self):
        root,target,project,baseline=self.integrated()
        self.rewrite(baseline/'server/core.jar')
        self.assertIn('exact project-retained integrated archive',self.probe('reverify',project,target,baseline,ok=False).stderr)

if __name__=='__main__':unittest.main()
