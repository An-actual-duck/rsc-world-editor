#!/usr/bin/env python3
"""Content-only transition authority preserves map and runtime transaction checks."""
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
import adaptive_project_test_support as support

spec=importlib.util.spec_from_file_location('authority_transactions',Path(__file__).with_name('test-world-builder-adaptive-transactions.py'))
transactions=importlib.util.module_from_spec(spec);spec.loader.exec_module(transactions)
HARNESS='''package com.openrsc.worldbuilder;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
public final class ContentAuthorityFixture {
 public static void main(String[] args)throws Exception{
  if(args[0].equals("content-role")){
   System.out.print(WorldBuilderContentRefreshAuthority.contentRole(args[1],args[2]));return;
  }
  Path root=Paths.get(args[1]);WorldBuilderReadOnlyTarget target=WorldBuilderReadOnlyTarget.open(root);
  if(args[0].equals("floors")){
   WorldBuilderAdaptiveConfiguration configuration=WorldBuilderAdaptiveConfiguration.select(target,WorldBuilderTargetCapability.read(target),null).selected;
   byte[] tiles=Files.readAllBytes(root.resolve("server/conf/server/defs/TileDef.xml"));String client=WorldBuilderInstalledFloorContent.clientRoot(configuration);
   Files.write(root.resolve(client+"/"+WorldBuilderInstalledFloorContent.CLIENT_TILES),tiles);
   Files.write(root.resolve(client+"/"+WorldBuilderInstalledFloorContent.CLIENT_DESCRIPTOR),WorldBuilderInstalledFloorContent.descriptor(tiles));return;
  }
  if(args[0].equals("verify")){
   WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project=WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(Paths.get(args[2]),true);
   Map<String,Object> report=WorldBuilderJsonDocuments.readObject(new WorldBuilderAdaptiveDiscovery().discover(root,args.length>3?args[3]:null).toJson().getBytes(StandardCharsets.UTF_8),"discovery");
   System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderContentRefreshAuthority.verify(project,root,report)));return;
  }
  WorldBuilderTargetCapability capability=WorldBuilderTargetCapability.read(target);
  WorldBuilderAdaptiveConfiguration config=WorldBuilderAdaptiveConfiguration.select(target,capability,null).selected;
  Map<String,Object> old=target.readObject(config.serverDefinitionCatalogRelativePath);
  Map<String,Object> catalog=WorldBuilderProjectContentBundle.deriveTargetCatalog(target,WorldBuilderPackedSourceLayout.selectContentRoots(target),(String)old.get("catalogId"));
  catalog.remove("catalogSha256");byte[] bytes=WorldBuilderJsonDocuments.pretty(catalog).getBytes(StandardCharsets.UTF_8);
  Files.write(root.resolve(config.serverDefinitionCatalogRelativePath),bytes);Files.write(root.resolve(config.clientDefinitionCatalogRelativePath),bytes);String hash=WorldBuilderHashes.sha256(bytes);
  for(String path:Arrays.asList(config.serverRuntimeRelativePath,config.clientRuntimeRelativePath)){
   Map<String,Object> doc=target.readObject(path);doc.put("definitionCatalogSha256",hash);Files.write(root.resolve(path),WorldBuilderJsonDocuments.pretty(doc).getBytes(StandardCharsets.UTF_8));
  }
  Map<String,Object> doc=target.readObject(WorldBuilderTargetCapability.RELATIVE_PATH);
  ((Map<String,Object>)doc.get("definitions")).put("catalogSha256",hash);
  Files.write(root.resolve(WorldBuilderTargetCapability.RELATIVE_PATH),WorldBuilderJsonDocuments.pretty(doc).getBytes(StandardCharsets.UTF_8));
 }
}'''

class ContentRefreshAuthorityTest(transactions.AdaptiveTransactionTest):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        source=Path(cls.compile_temp.name)/'ContentAuthorityFixture.java';source.write_text(HARNESS)
        compiled=subprocess.run(['javac','-cp',str(cls.classes),'-d',str(cls.classes),str(source)],capture_output=True,text=True)
        if compiled.returncode: raise AssertionError(compiled.stderr)
    def probe(self,operation,target,project=None):
        return subprocess.run(['java','-cp',str(self.classes),'com.openrsc.worldbuilder.ContentAuthorityFixture',operation,str(target),str(project or '')],capture_output=True,text=True)
    def sync_catalogs(self,target):
        result=self.probe('catalog',target);self.assertEqual(0,result.returncode,result.stderr)
    def fixture(self,base):
        def complete(target):
            path=target/'server/conf/server/defs/NpcDefsPatch18.json';value=json.loads(path.read_text());value['npcs'].append({'id':35,'name':'placed-fixture-35'});support.write_json(path,value);self.sync_catalogs(target)
        return self.target_project(base,representation='packed',installed_standard_floors=True,target_mutator=complete)
    def add_content(self,target):
        path=target/'server/conf/server/defs/NpcDefsCustom.json';value=json.loads(path.read_text());value['npcs'].append({'name':'authority fixture addition','sprites':[0]*12});support.write_json(path,value);self.sync_catalogs(target)
    def test_producer_provenance_never_authorizes_runtime_or_configuration_changes(self):
        accepted=[
            ('manifest','server/conf/world-builder/npc-definitions-v2.json'),
            ('definition','server/conf/server/defs/NpcDefsCustom.json'),
            ('asset','server/conf/world-builder/npc-preview-rgb.zip'),
            ('probe','dev/myworld/assets/sprites/npcs/optional.png'),
            ('helper-source','tools/item-visual-provider/ExportEffectiveNpcVisuals.java'),
        ]
        rejected=[
            ('source','server/src/com/openrsc/server/external/EntityHandler.java'),
            ('source','Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java'),
            ('client-archive','Client_Base/Open_RSC_Client.jar'),
            ('config','server/myworld.conf'),
            ('asset','Client_Base/Open_RSC_Client.jar'),
            ('asset','Client_Base/src/Injected.java'),
            ('asset','server/classes/Injected.class'),
            ('helper-source','server/src/ExportEffectiveNpcVisuals.java'),
            ('helper-source','tools/item-visual-provider/nested/Export.java'),
            ('helper-source','tools/item-visual-provider/../Export.java'),
            ('helper-source','tools/item-visual-provider/Exporter.jar'),
        ]
        for expected,rows in [(True,accepted),(False,rejected)]:
            for role,path in rows:
                with self.subTest(role=role,path=path):
                    result=self.probe('content-role','npc-producer-v2-'+role,path)
                    self.assertEqual(0,result.returncode,result.stderr)
                    self.assertEqual(str(expected).lower(),result.stdout)
    def test_content_addition_after_import_is_verified_without_mutation(self):
        with tempfile.TemporaryDirectory(prefix='content-authority-chain-') as temp:
            target,install,project,export=self.fixture(Path(temp))
            imported=self.run_reviewed_apply('import-adaptive','IMPORT','--project',project,'--export',export,'--target-root',target)
            self.assertEqual(0,imported.returncode,imported.stderr)
            self.next_history_export(project);self.add_content(target)
            before=support.tree_bytes(target)
            verified=self.probe('verify',target,project);self.assertEqual(0,verified.returncode,verified.stderr)
            again=self.probe('verify',target,project);self.assertEqual(0,again.returncode,again.stderr)
            self.assertEqual(verified.stdout,again.stdout,'authority must bind deterministically')
            self.assertEqual(before,support.tree_bytes(target))
            proof=json.loads(verified.stdout);self.assertEqual(1,len(proof['history']))
    def test_unrelated_map_runtime_configuration_and_history_changes_refuse(self):
        with tempfile.TemporaryDirectory(prefix='content-authority-drift-') as temp:
            target,install,project,export=self.fixture(Path(temp))
            imported=self.run_reviewed_apply('import-adaptive','IMPORT','--project',project,'--export',export,'--target-root',target)
            self.assertEqual(0,imported.returncode,imported.stderr);self.add_content(target)
            candidates=[target/'server/core.jar',target/'server/world-builder-configs/primary.json',next((project/'receipts').glob('*.json'))]
            for path in candidates:
                original=path.read_bytes();path.write_bytes(original+b' changed')
                result=self.probe('verify',target,project);self.assertNotEqual(0,result.returncode,result.stdout)
                path.write_bytes(original)
    def test_runtime_upgrade_authority_and_live_source_inventory_are_retained(self):
        with tempfile.TemporaryDirectory(prefix='content-authority-runtime-') as temp:
            base=Path(temp)
            def content(target):
                path=target/'server/conf/server/defs/NpcDefsPatch18.json';value=json.loads(path.read_text());value['npcs'].append({'id':35,'name':'placed-fixture-35'});support.write_json(path,value)
                self.sync_catalogs(target)
                self.add_targeted_floor_fixture(base,target)
                self.add_snapshot_captured_history_source(base,target)
                descriptor=self.classes/'com/openrsc/worldbuilder/target-map-integration/target-map-integration-v1.json'
                contract=json.loads(descriptor.read_text())
                for row in contract['adapters'][0]['compilation']:
                    if row['scope']=='server':row['compileAllSources']=True
                self.use_targeted_fixture_descriptor(contract)
                classes=base/'initial-runtime-classes';classes.mkdir()
                subprocess.run(['javac','-source','8','-target','8','-d',str(classes),*map(str,(target/'server/src').rglob('*.java'))],check=True,capture_output=True)
                for file in classes.rglob('*.class'):self.rewrite_runtime_entry(target/'server/core.jar',file.relative_to(classes).as_posix(),file.read_bytes())
            target,install,project,export=self.target_project(base,representation='packed',target_mutator=content)
            upgraded=self.run_reviewed_apply('upgrade-target-runtime','UPGRADE','--project',project,'--export',export,'--target-root',target)
            self.assertEqual(0,upgraded.returncode,upgraded.stderr)
            imported=self.run_reviewed_apply('import-adaptive','IMPORT','--project',project,'--export',export,'--target-root',target)
            self.assertEqual(0,imported.returncode,imported.stderr)
            self.rebuild_fixture(base,target,'none',drop_markers=True)
            reverified=self.run_reviewed_apply('reverify-target-runtime','REVERIFY','--project',project,'--target-root',target)
            self.assertEqual(0,reverified.returncode,reverified.stderr)
            self.add_content(target)
            result=self.probe('verify',target,project);self.assertEqual(0,result.returncode,result.stderr)
            source='src/com/openrsc/client/entityhandling/EntityHandler.java'
            configuration=json.loads((target/'server/world-builder-configs/primary.json').read_text())
            client=Path(configuration['clientRuntimeRelativePath']).parts[0]
            for path in [target/'server/core.jar',target/client/'Open_RSC_Client.jar',target/'server'/source,target/client/source]:
                original=path.read_bytes();path.write_bytes(original+b' changed')
                result=self.probe('verify',target,project);self.assertNotEqual(0,result.returncode,result.stdout)
                path.write_bytes(original)
            extra=target/'server/src/fixture/Unexpected.java';extra.write_text('package fixture; public class Unexpected {}')
            result=self.probe('verify',target,project);self.assertNotEqual(0,result.returncode);self.assertIn('inventory changed',result.stderr)

    def test_material_append_requires_verified_pair_and_unchanged_prefix(self):
        with tempfile.TemporaryDirectory(prefix='content-authority-floor-') as temp:
            target,install,project,export=self.fixture(Path(temp))
            path=target/'server/conf/server/defs/TileDef.xml';original=path.read_text()
            path.write_text(original.replace('</TileDef-array>','<TileDef><colour>12345</colour><unknown>0</unknown><objectType>0</objectType></TileDef></TileDef-array>'))
            paired=self.probe('floors',target);self.assertEqual(0,paired.returncode,paired.stderr);self.sync_catalogs(target)
            result=self.probe('verify',target,project);self.assertEqual(0,result.returncode,result.stderr)
            changed=path.read_text().replace('<TileDef>','<TileDef> ',1);path.write_text(changed)
            paired=self.probe('floors',target);self.assertEqual(0,paired.returncode,paired.stderr);self.sync_catalogs(target)
            result=self.probe('verify',target,project);self.assertNotEqual(0,result.returncode);self.assertIn('append-only',result.stderr)

    def test_active_package_extra_file_and_empty_directory_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix='content-authority-inventory-') as temp:
            target,install,project,export=self.fixture(Path(temp))
            imported=self.run_reviewed_apply('import-adaptive','IMPORT','--project',project,'--export',export,'--target-root',target)
            self.assertEqual(0,imported.returncode,imported.stderr)
            config=json.loads((target/'server/world-builder-configs/primary.json').read_text())
            package=target/config['serverMapRelativePath']
            for directory in [False,True]:
                extra=package/('empty-untracked' if directory else 'untracked.bin')
                extra.mkdir() if directory else extra.write_bytes(b'untracked')
                result=self.probe('verify',target,project);self.assertNotEqual(0,result.returncode,result.stdout)
                extra.rmdir() if directory else extra.unlink()

    def test_packed_alias_refresh_rediscovery_uses_exact_selected_content_path(self):
        with tempfile.TemporaryDirectory(prefix='content-authority-alias-') as temp:
            base=Path(temp)
            def content(target):
                path=target/'server/conf/server/defs/NpcDefsPatch18.json';value=json.loads(path.read_text());value['npcs'].append({'id':35,'name':'placed-fixture-35'});support.write_json(path,value)
                config='client_version: 10046\nmember_world: true\nbased_map_data: 64\nbased_config_data: 18\nwant_myworld: true\ncustom_landscape: true\n'
                (target/'server/myworld.conf').write_text(config);(target/'myworld.conf').write_text(config);self.sync_catalogs(target)
            original_run=self.run_cli
            def selected(*args):
                return original_run(*(args+('--configuration-role','packed-map-2') if args[0]=='discover-adaptive' else args))
            self.run_cli=selected
            try:target,install,project,export=self.target_project(base,representation='packed',installed_standard_floors=True,target_mutator=content)
            finally:self.run_cli=original_run
            captured=json.loads((project/'discovery/report.json').read_text())
            self.assertEqual(['server/myworld.conf'],[row['relativePath']for row in captured['files']if row['role']=='server-runtime-config'])
            other=self.run_cli('create-project','--installation-root',install,'--runtime-root',base/'builder-runtime','--target-root',target,'--discovery-report',base/'discovery.json','--display-name','Other registered project','--port','43890','--confirm','CREATE')
            self.assertEqual(0,other.returncode,other.stderr)
            imported=self.run_reviewed_apply('import-adaptive','IMPORT','--project',project,'--export',export,'--target-root',target)
            self.assertEqual(0,imported.returncode,imported.stderr)
            result=subprocess.run(['java','-cp',str(self.classes),'com.openrsc.worldbuilder.ContentAuthorityFixture','verify',str(target),str(project),'server/myworld.conf'],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)

    def test_unimported_project_can_refresh_only_truthful_catalogs(self):
        with tempfile.TemporaryDirectory(prefix='content-authority-initial-') as temp:
            target,install,project,export=self.fixture(Path(temp));self.add_content(target)
            result=self.probe('verify',target,project);self.assertEqual(0,result.returncode,result.stderr)
            path=target/'server/conf/server/defs/NpcDefsCustom.json';value=json.loads(path.read_text());value['npcs'].append({'name':'missing catalog ID','sprites':[0]*12});support.write_json(path,value)
            result=self.probe('verify',target,project);self.assertNotEqual(0,result.returncode);self.assertIn('catalog',result.stderr.lower())

def load_tests(loader,tests,pattern):return unittest.TestSuite(ContentRefreshAuthorityTest(name)for name in ContentRefreshAuthorityTest.__dict__ if name.startswith('test_'))
if __name__=='__main__':unittest.main(verbosity=2)
