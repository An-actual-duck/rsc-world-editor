#!/usr/bin/env python3
"""Target-owned compilation, bounded map edits, active custom behavior and proofs."""
import hashlib
import importlib.util
import json
import os
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

    def abi_fixture(self):
        root,target,project,contract=self.fixture()
        src=target/'server/src/fixture'
        (src/'TileValue.java').write_text('package fixture; public class TileValue { public byte elevation=7; }')
        (src/'CustomTile.java').write_text('package fixture; public class CustomTile extends TileValue { public String custom(){return "retained";} }')
        classes=root/'abi-core';classes.mkdir()
        subprocess.run(['javac','-source','8','-target','8','-d',str(classes),*map(str,src.glob('*.java'))],check=True,capture_output=True)
        archive=target/'server/core.jar'
        with zipfile.ZipFile(archive) as z: entries={n:z.read(n) for n in z.namelist()}
        for f in classes.rglob('*.class'):entries[f.relative_to(classes).as_posix()]=f.read_bytes()
        with zipfile.ZipFile(archive,'w') as z:
            for name,data in entries.items():z.writestr(name,data)
        plugin=target/'server/plugins/fixture/Plugin.java';plugin.parent.mkdir(parents=True)
        plugin.write_text('package fixture; public final class Plugin { public static void main(String[] a){System.out.print(new CustomTile().elevation+"|"+new CustomTile().custom());} }')
        classes=root/'abi-plugins';classes.mkdir()
        subprocess.run(['javac','-source','8','-target','8','-classpath',str(archive),'-d',str(classes),str(plugin)],check=True,capture_output=True)
        with zipfile.ZipFile(target/'server/plugins.jar','w') as z:
            z.writestr('plugin-registration.txt','fixture.Plugin')
            for f in classes.rglob('*.class'):z.writestr(f.relative_to(classes).as_posix(),f.read_bytes())
        adapter=contract['adapters'][0]
        adapter['transforms'].append({'scope':'server','targetRelativePath':'src/fixture/TileValue.java','transformId':'wide-map-elevation','edits':[{'before':'public byte elevation=7;','after':'public int elevation=700;','occurrences':1}]})
        adapter['compilation'][0]['abiChangedClasses']=['fixture/TileValue']
        adapter['compilation'].insert(1,{'scope':'server','archiveRelativePath':'plugins.jar','sourceRoots':['plugins'],'dependencyDirectories':[],'dependencyArchives':['server/core.jar'],'sourceLevel':'8','targetLevel':'8','runtimeLevel':17,'compileAllSources':True,'manifestAttributes':{}})
        (project/'working/runtime/server/conf/world-builder/target-map-integration-v1.json').write_text(json.dumps(contract))
        return root,target,project,contract

    def test_inherited_field_abi_recompiles_plugin_consumer_across_archives(self):
        root,target,project,_=self.abi_fixture();out=root/'out'
        self.probe('prepare',project,target,out)
        run=subprocess.run(['java','-cp',f'{out}/server/core.jar:{out}/server/plugins.jar','fixture.Plugin'],check=True,capture_output=True,text=True)
        self.assertEqual('700|retained',run.stdout)
        with zipfile.ZipFile(target/'server/core.jar') as a,zipfile.ZipFile(out/'server/core.jar') as b:
            self.assertEqual(a.read('fixture/Unrelated.class'),b.read('fixture/Unrelated.class'))
        with zipfile.ZipFile(out/'server/plugins.jar') as z:self.assertEqual(b'fixture.Plugin',z.read('plugin-registration.txt'))

    def test_binary_only_inherited_abi_consumer_refuses(self):
        root,target,project,_=self.abi_fixture()
        (target/'server/plugins/fixture/Plugin.java').unlink()
        refused=self.probe('prepare',project,target,root/'out',ok=False)
        self.assertTrue('no source for recompilation' in refused.stderr or 'requires reviewed sources' in refused.stderr,refused.stderr)

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


class TargetMapProviderConsumerTest(unittest.TestCase):
    setUpClass = classmethod(TargetMapIntegrationTest.setUpClass.__func__)
    probe = TargetMapIntegrationTest.probe
    @unittest.skipUnless(os.environ.get('WORLD_BUILDER_TARGET_MAP_PROVIDER'), 'exact provider source/build fixture not selected')
    def test_shipped_adapter_compiles_real_reconstructed_host(self):
        provider=Path(os.environ['WORLD_BUILDER_TARGET_MAP_PROVIDER']).resolve()
        spec=importlib.util.spec_from_file_location('provider_target_map_fixture',provider/'tests/myworld/test-target-map-adapter-compilation.py')
        fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
        with tempfile.TemporaryDirectory(prefix='target-map-provider-consumer-') as temporary:
            root=Path(temporary);contract=json.loads((provider/'server/conf/world-builder/target-map-integration-v1.json').read_text());adapter=contract['adapters'][0]
            host=fixture.reconstruct(root/'target',adapter)
            shutil.copytree(provider/'server/plugins',host/'server/plugins',dirs_exist_ok=True)
            for scope,base in [('server','server'),('client','Client_Base')]:
                destination=host/base/'lib';destination.mkdir(parents=True,exist_ok=True)
            shutil.copytree(provider/'server/lib',host/'server/lib',dirs_exist_ok=True)
            library=provider/'server/core.jar';clientlib=provider/'Client_Base/Open_RSC_Client.jar'
            fixture.compile_sources(host/'server/src',root/'before-server',[library])
            fixture.compile_sources(host/'server/plugins',root/'before-plugins',[root/'before-server',library])
            selected=root/'selected-client'
            client_sources=set()
            for row in adapter['sources']+adapter['transforms']+adapter['requirements']:
                if row['scope']=='client':client_sources.add(row['targetRelativePath'])
            for row in adapter['compilation']:
                if row['scope']=='client':client_sources.update(row.get('verificationSources',[]))
            for relative in client_sources:
                path=selected/relative;path.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(host/'Client_Base'/relative,path)
            fixture.compile_sources(selected,root/'before-client',[clientlib,library])
            for base,archive,compiled,original in [('server','core.jar',root/'before-server',library),('server','plugins.jar',root/'before-plugins',provider/'server/plugins.jar'),('Client_Base','Open_RSC_Client.jar',root/'before-client',clientlib)]:
                with zipfile.ZipFile(original) as z:entries={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
                for f in compiled.rglob('*.class'):entries[f.relative_to(compiled).as_posix()]=f.read_bytes()
                entries['synthetic/preserved-content.bin']=b'target-owned-art-and-dialogue'
                with zipfile.ZipFile(host/base/archive,'w') as z:
                    for name,data in entries.items():z.writestr(name,data)
            project=root/'project';payload=project/'working/runtime';payload.mkdir(parents=True)
            for row in adapter['sources']:
                source=provider/('server' if row['scope']=='server' else 'Client_Base')/row['targetRelativePath']
                target=payload/row['payloadRelativePath'];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read_bytes())
                before=host/('server' if row['scope']=='server' else 'Client_Base')/row['targetRelativePath']
                if row['policy']=='replace-reviewed-map-source':row['acceptedBeforeSha256'].append(sha(before.read_bytes()))
            for row in adapter['requirements']:
                if 'acceptedSourceSha256' in row:row['acceptedSourceSha256'].append(sha((host/('server' if row['scope']=='server' else 'Client_Base')/row['targetRelativePath']).read_bytes()))
            path=payload/'server/conf/world-builder/target-map-integration-v1.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(contract))
            # Probe uses conventional client root. Rename only this disposable fixture.
            (host/'Client_Base').rename(host/'client')
            out=root/'out';self.probe('prepare',project,host,out)
            # Exercise the runtime provider's real v5/wide map and custom callback harness.
            spec=importlib.util.spec_from_file_location('provider_map_package_fixture',provider/'tests/myworld/test-native-blocked-void-npc-roam.py')
            map_fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(map_fixture)
            package=root/'map';placements=map_fixture.package(package,5);placements['npcs'][0]['npcId']=1907;map_fixture.update(package,placements)
            terrain=dict(schemaVersion=2,encoding='uniform-layered-sector-v2-u16',size=48,tile=dict(elevation=65535,texture=0,overlay=0,roof=0,verticalWall=0,horizontalWall=0,diagonalWall=0))
            digest=map_fixture.write_json(package/'terrain.json',terrain);manifest=json.loads((package/'manifest.json').read_text());manifest['terrainSectors'][0].update(encoding=terrain['encoding'],path='terrain.json',sha256=digest);map_fixture.write_json(package/'manifest.json',manifest)
            fixture.write(root/'harness/TargetBehavior.java',fixture.HARNESS)
            fixture.compile_sources(root/'harness',root/'harness-classes',[out/'server/core.jar',out/'server/plugins.jar'])
            run=subprocess.run(['java','-cp',os.pathsep.join(map(str,[root/'harness-classes',out/'server/core.jar',out/'server/plugins.jar'])),'TargetBehavior','after',str(package)],capture_output=True,text=True)
            self.assertEqual(0,run.returncode,run.stderr)
            for base,archive in [('server','core.jar'),('server','plugins.jar'),('client','Open_RSC_Client.jar')]:
                with zipfile.ZipFile(out/base/archive) as z:self.assertEqual(b'target-owned-art-and-dialogue',z.read('synthetic/preserved-content.bin'))


class TargetMapTransactionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec=importlib.util.spec_from_file_location('targeted_existing_transactions',ROOT/'tests/myworld/test-world-builder-adaptive-transactions.py')
        cls.legacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(cls.legacy)
        cls.legacy.AdaptiveTransactionTest.setUpClass()
        cls.addClassCleanup(cls.legacy.AdaptiveTransactionTest.tearDownClass)

    def fixture(self):
        temporary=tempfile.TemporaryDirectory(prefix='target-map-transaction-');self.addCleanup(temporary.cleanup);root=Path(temporary.name)
        helper=self.legacy.AdaptiveTransactionTest('runTest')
        def customize(target):
            for role,archive in [('server','core.jar'),('client','Open_RSC_Client.jar')]:
                source=target/role/'src/fixture/Engine.java';source.parent.mkdir(parents=True,exist_ok=True)
                source.write_text('package fixture; public class Engine { public static int mapVersion(){return 1;} public static String talk(){return "custom-dialogue";} public static void main(String[] args){System.out.print(mapVersion()+"|"+talk());} }')
                classes=root/(role+'-classes');classes.mkdir()
                subprocess.run(['javac','-source','8','-target','8','-d',str(classes),str(source)],check=True,capture_output=True)
                helper.rewrite_runtime_entry(target/role/archive,'fixture/Engine.class',(classes/'fixture/Engine.class').read_bytes())
                helper.rewrite_runtime_entry(target/role/archive,'custom-art.bin',b'custom-art')
        target,installation,project,export=helper.target_project(root,target_mutator=customize)
        compilation=[];transforms=[]
        for role,archive in [('server','core.jar'),('client','Open_RSC_Client.jar')]:
            transforms.append({'scope':role,'targetRelativePath':'src/fixture/Engine.java','transformId':'fixture-map-v2','edits':[{'before':'public static int mapVersion(){return 1;}','after':'public static int mapVersion(){return 2;}','occurrences':1}]})
            compilation.append({'scope':role,'archiveRelativePath':archive,'sourceRoots':['src'],'dependencyDirectories':[],'sourceLevel':'8','targetLevel':'8','runtimeLevel':17,'compileAllSources':False,'manifestAttributes':{}})
        contract={'schemaVersion':1,'manifestType':'world-builder-target-map-integration','integrationId':'target-owned-layered-map-v1','loaderId':'generic-signed-layered-loader-v7-blocking-base-color','protocolId':'world-builder-native-layered-protocol-v2-u16-elevation','encodingVersions':[1,2,3,4,5],'adapters':[{'adapterId':'fixture-targeted','sources':[],'transforms':transforms,'requirements':[],'requiredEntryProbes':[],'compilation':compilation}]}
        resource=helper.classes/'com/openrsc/worldbuilder/target-map-integration/target-map-integration-v1.json';resource.parent.mkdir(parents=True,exist_ok=True);resource.write_text(json.dumps(contract))
        return helper,target,installation,project,export

    def test_upgrade_import_and_restart_retains_custom_content(self):
        helper,target,installation,project,export=self.fixture()
        before=self.legacy.project_support.tree_bytes(target,installation)
        upgraded=helper.run_reviewed_apply('upgrade-target-runtime','UPGRADE','--project',project,'--export',export,'--target-root',target)
        self.assertEqual(0,upgraded.returncode,upgraded.stderr)
        self.assertNotIn(b'world.builder.pinned.host.runtime',(target/'server/build.xml').read_bytes())
        after_upgrade=self.legacy.project_support.tree_bytes(target,installation)
        imported=helper.run_reviewed_apply('import-adaptive','IMPORT','--project',project,'--export',export,'--target-root',target)
        self.assertEqual(0,imported.returncode,imported.stderr)
        for role,archive in [('server','core.jar'),('client','Open_RSC_Client.jar')]:
            result=subprocess.run(['java','-cp',str(target/role/archive),'fixture.Engine'],check=True,capture_output=True,text=True)
            self.assertEqual('2|custom-dialogue',result.stdout)
            self.assertEqual(after_upgrade[f'{role}/{archive}'],self.legacy.project_support.tree_bytes(target,installation)[f'{role}/{archive}'])
        for path,data in before.items():
            if '/defs/' in path or path.endswith('plugins.jar'):self.assertEqual(data,self.legacy.project_support.tree_bytes(target,installation)[path])

    def test_failure_rolls_back_exact_target(self):
        helper,target,installation,project,export=self.fixture()
        before=self.legacy.project_support.tree_bytes(target,installation)
        failed=helper.run_failure('runtime-upgrade','before-success-receipt',project,target,export)
        self.assertEqual(3,failed.returncode,failed.stderr)
        self.assertEqual(before,self.legacy.project_support.tree_bytes(target,installation))
        receipts=[json.loads(p.read_text())['status'] for p in (project/'receipts').glob('*.json')]
        self.assertEqual(['rolled-back'],receipts)

    def test_interrupted_failure_recovers_exact_target(self):
        helper,target,installation,project,export=self.fixture()
        before=self.legacy.project_support.tree_bytes(target,installation)
        failed=helper.run_failure('runtime-upgrade','before-success-receipt,rollback-before-0000',project,target,export)
        self.assertEqual(3,failed.returncode,failed.stderr)
        self.assertIn('RECOVERY_REQUIRED',failed.stderr)
        recovered=helper.run_reviewed_apply('recover-adaptive','RECOVER','--project',project,'--target-root',target)
        self.assertEqual(0,recovered.returncode,recovered.stderr)
        self.assertEqual(before,self.legacy.project_support.tree_bytes(target,installation))


if __name__=='__main__': unittest.main()
