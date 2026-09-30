#!/usr/bin/env python3
"""Target-owned compilation, bounded map edits, active custom behavior and proofs."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HARNESS = r'''
package com.openrsc.worldbuilder;
import java.nio.file.*; import java.util.*;
public final class TargetMapIntegrationProbe {
 public static void main(String[] args) throws Exception {
  Path project=Paths.get(args[1]), target=Paths.get(args[2]);
  if ("prepare".equals(args[0])) {
   WorldBuilderTargetMapIntegration.Result result=WorldBuilderTargetMapIntegration.preparePayload(project,target,"client");
   Path out=Paths.get(args[3]); Files.createDirectories(out);
   for(Map.Entry<String,byte[]> e:result.outputs.entrySet()){Path p=out.resolve(e.getKey());Files.createDirectories(p.getParent());Files.write(p,e.getValue());}
   List<Object> actions=new ArrayList<>();int n=0;
   for(WorldBuilderAdaptiveMutationProfile.Action action:WorldBuilderTargetMapIntegration.actions(target,result)) actions.add(action.toJson(n++));
   Files.write(out.resolve("actions.json"),WorldBuilderJsonDocuments.pretty(Collections.singletonMap("actions",actions)).getBytes("UTF-8"));
  } else if ("restore".equals(args[0])) {
   Map<String,Object> action=WorldBuilderTargetMapIntegration.read(Paths.get(args[3]));
   WorldBuilderTargetMapIntegration.restoreAction(action,project,"test-transaction");
  } else if ("verify".equals(args[0])) WorldBuilderTargetMapIntegration.verifyInstalled(project,target,"client");
 }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


class TargetMapIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run([str(ROOT/'scripts/build-tools.sh')], check=True, stdout=subprocess.DEVNULL)
        cls.compiled = tempfile.TemporaryDirectory(prefix='target-map-probe-')
        cls.addClassCleanup(cls.compiled.cleanup)
        source = Path(cls.compiled.name)/'TargetMapIntegrationProbe.java'
        source.write_text(HARNESS)
        subprocess.run(['javac','-cp',str(ROOT/'output/world-builder-tools/classes'),'-d',cls.compiled.name,str(source)],check=True,capture_output=True)

    def fixture(self):
        temp = tempfile.TemporaryDirectory(prefix='target-map-fixture-'); self.addCleanup(temp.cleanup)
        root=Path(temp.name); target=root/'target'; project=root/'project'; contract_root=project/'working/runtime/server/conf/world-builder'; contract_root.mkdir(parents=True)
        transforms=[]; compilation=[]
        for role,archive in [('server','core.jar'),('client','Open_RSC_Client.jar')]:
            source=target/role/'src/fixture/Engine.java'; source.parent.mkdir(parents=True)
            source.write_text('package fixture; public final class Engine {\n'
                ' public static int mapVersion(){return 1;}\n'
                ' public static String talk(){return "custom dialogue";}\n'
                ' public static String appearance(){return "custom serpent art";}\n'
                ' public static int itemEffect(){return 937;}\n'
                ' public static String objectAction(){return "custom gate";}\n'
                ' public static void main(String[] args){System.out.print(mapVersion()+"|"+talk()+"|"+appearance()+"|"+itemEffect()+"|"+objectAction());}\n'
                '}\n')
            custom=source.with_name('Unrelated.java'); custom.write_text('package fixture; public class Unrelated { public static String plugin(){return "custom plugin";} }')
            classes=root/(role+'-classes'); classes.mkdir()
            subprocess.run(['javac','-source','8','-target','8','-d',str(classes),str(source),str(custom)],check=True,capture_output=True)
            with zipfile.ZipFile(target/role/archive,'w') as z:
                z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nMain-Class: fixture.Engine\nCustom-Setting: keep-me\n\n')
                z.writestr('custom/art.bin',b'unique target art')
                z.writestr('custom/dialogue.properties',b'new_npc=hello owner')
                for f in classes.rglob('*.class'): z.writestr(f.relative_to(classes).as_posix(),f.read_bytes())
            transforms.append({'scope':role,'targetRelativePath':'src/fixture/Engine.java','transformId':'map-version-v2','edits':[{'before':'public static int mapVersion(){return 1;}','after':'public static int mapVersion(){return 2;}','occurrences':1}]})
            compilation.append({'scope':role,'archiveRelativePath':archive,'sourceRoots':['src'],'dependencyDirectories':[],'sourceLevel':'8','targetLevel':'8','runtimeLevel':17,'compileAllSources':True,'abiChangedClasses':[],'manifestAttributes':{'World-Builder-Map-Integration':'target-owned-layered-map-v1'}})
        contract={'schemaVersion':1,'manifestType':'world-builder-target-map-integration','integrationId':'target-owned-layered-map-v1','loaderId':'generic-signed-layered-loader-v7-blocking-base-color','protocolId':'world-builder-native-layered-protocol-v2-u16-elevation','encodingVersions':[1,2,3,4,5],'adapters':[{'adapterId':'native-layered-v4-source-v1','sources':[],'transforms':transforms,'requirements':[],'requiredEntryProbes':[],'compilation':compilation}]}
        (contract_root/'target-map-integration-v1.json').write_text(json.dumps(contract))
        return root,target,project,contract

    def probe(self,operation,project,target,extra,ok=True):
        run=subprocess.run(['java','-cp',f'{ROOT}/output/world-builder-tools/classes:{self.compiled.name}','com.openrsc.worldbuilder.TargetMapIntegrationProbe',operation,str(project),str(target),str(extra)],capture_output=True,text=True)
        self.assertEqual(ok,run.returncode==0,run.stderr)
        return run

    def test_upgrade_keeps_active_custom_behavior_and_unrelated_entries(self):
        root,target,project,_=self.fixture(); out=root/'out'
        before={p.relative_to(target).as_posix():p.read_bytes() for p in target.rglob('*') if p.is_file()}
        self.probe('prepare',project,target,out)
        self.assertEqual(before,{p.relative_to(target).as_posix():p.read_bytes() for p in target.rglob('*') if p.is_file()},'preview must not mutate target')
        for role,archive in [('server','core.jar'),('client','Open_RSC_Client.jar')]:
            original=target/role/archive; rebuilt=out/role/archive
            run=subprocess.run(['java','-cp',str(rebuilt),'fixture.Engine'],check=True,capture_output=True,text=True)
            self.assertEqual('2|custom dialogue|custom serpent art|937|custom gate',run.stdout)
            with zipfile.ZipFile(original) as a,zipfile.ZipFile(rebuilt) as b:
                for name in ['fixture/Unrelated.class','custom/art.bin','custom/dialogue.properties']: self.assertEqual(a.read(name),b.read(name))
                self.assertIn(b'Custom-Setting: keep-me',b.read('META-INF/MANIFEST.MF'))
        actions=json.loads((out/'actions.json').read_text())['actions']
        self.assertTrue(all('content-bundle' not in a['destinationRelativePath'] for a in actions))
        self.assertFalse(any(a['destinationRelativePath'].endswith('build.xml') for a in actions))

    def test_stale_source_cannot_erase_active_binary_customization(self):
        root,target,project,_=self.fixture()
        p=target/'server/src/fixture/Engine.java';p.write_text(p.read_text().replace('custom dialogue','old dialogue'))
        refused=self.probe('prepare',project,target,root/'out',ok=False)
        self.assertIn('Active target bytecode differs from its source',refused.stderr)

    def test_unrecognized_hook_fails_before_target_mutation(self):
        root,target,project,_=self.fixture();p=target/'client/src/fixture/Engine.java'
        p.write_text(p.read_text().replace('return 1;','return 7;'))
        refused=self.probe('prepare',project,target,root/'out',ok=False)
        self.assertIn('Map hook differs or is ambiguous',refused.stderr)
        self.assertFalse((root/'out').exists())

    def test_comment_is_not_an_executable_upgrade_anchor(self):
        root,target,project,_=self.fixture();p=target/'client/src/fixture/Engine.java'
        p.write_text(p.read_text().replace('public static int mapVersion(){return 1;}','/*public static int mapVersion(){return 1;}*/ public static int mapVersion(){return 7;}'))
        refused=self.probe('prepare',project,target,root/'out',ok=False)
        self.assertIn('Map hook differs or is ambiguous',refused.stderr)

    def test_exact_generated_outputs_survive_recovery_without_compiling(self):
        root,target,project,_=self.fixture();out=root/'out';self.probe('prepare',project,target,out)
        actions=json.loads((out/'actions.json').read_text())['actions']
        for n,action in enumerate(actions):
            path=action['destinationRelativePath'];action['backupRelativePath']=f'backups/test-transaction/before/{path}' if action['before']['present'] else ''
            evidence=project/f"backups/test-transaction/content/targeted/{action['role']}.bin";evidence.parent.mkdir(parents=True,exist_ok=True);evidence.write_bytes((out/path).read_bytes())
            record=root/f'action-{n}.json';record.write_text(json.dumps(action))
            self.probe('restore',project,target,record)
            evidence.write_bytes(b'tampered')
            refused=self.probe('restore',project,target,record,ok=False)
            self.assertIn('Persisted targeted output differs',refused.stderr)


if __name__=='__main__': unittest.main()
