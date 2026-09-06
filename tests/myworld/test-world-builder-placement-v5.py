#!/usr/bin/env python3
"""Editor-only placement v5 fixtures; no runtime or historical intake authority."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAIN = 'com.openrsc.worldbuilder.PlacementV5Probe'

HARNESS = r'''
package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
public final class PlacementV5Probe {
  static Object invoke(Class<?> type, String name, Class<?>[] params, Object... args) throws Exception {
    Method method = type.getDeclaredMethod(name, params); method.setAccessible(true);
    try { return method.invoke(null, args); }
    catch (InvocationTargetException ex) { throw new RuntimeException(ex.getCause()); }
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[1]); String mode = args[0];
    if (mode.equals("inspect")) {
      WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(root);
      WorldBuilderGenericLayeredPackage result = WorldBuilderGenericLayeredPackage.inspect(
        target, "package", "fixture", WorldBuilderCompatibilityEvidence.DefinitionCatalog.read(target, "definitions.json"));
      Map<String,Object> document = new LinkedHashMap<>();
      document.put("encodings", result.requiredEncodingVersions);
      document.put("semantics", result.placementSemantics);
      document.put("fingerprint", result.fingerprintSha256);
      System.out.println(WorldBuilderJsonDocuments.pretty(document)); return;
    }
    if (mode.equals("draft")) {
      WorldBuilderLayeredPackage.discoverDraft(root.resolve("package")); return;
    }
    if (mode.equals("new-level")) {
      invoke(WorldBuilderLayeredDraftWriter.class, "writeLevel",
        new Class<?>[]{Path.class,int.class,String.class,String.class,int.class,int.class,int.class,int.class,int.class,int.class},
        root.resolve("package"),1,"Upper","upper",0,0,0,0,0,0); return;
    }
    if (mode.equals("normalize")) {
      System.out.println(invoke(WorldBuilderAdaptiveExporter.class, "normalizedPackageFingerprint",
        new Class<?>[]{Path.class,String.class}, root,"package")); return;
    }
    if (mode.equals("compose")) {
      new WorldBuilderLayeredTerrainComposer().compose(root,"package","legacy","composed",
        WorldBuilderCompatibilityEvidence.DefinitionCatalog.read(WorldBuilderReadOnlyTarget.open(root),"definitions.json"));
      return;
    }
    if (mode.equals("compact")) {
      invoke(WorldBuilderAdaptiveExporter.class,"minimizePackageEncodings",new Class<?>[]{Path.class},root.resolve("package"));
      return;
    }
    if (mode.equals("region-coverage")) {
      Class<?> type=Class.forName("com.openrsc.worldbuilder.WorldBuilderRegionSnapshotService$PackageState");
      Object state=invoke(type,"read",new Class<?>[]{Path.class,String.class},root,"package");
      Method check=type.getDeclaredMethod("firstUnavailablePlacement",int.class,String.class,Map.class);
      check.setAccessible(true);
      Map<String,Object> body=WorldBuilderJsonDocuments.readObject(root.resolve("package/placements.json"));
      System.out.println(check.invoke(state,0,"npcs",((List<?>)body.get("npcs")).get(0))==null); return;
    }
    if (mode.equals("capability-versions")) {
      List<Integer> versions=new ArrayList<>();for(int i=1;i<=Integer.parseInt(args[2]);i++)versions.add(i);
      System.out.println(invoke(WorldBuilderRuntimeCompatibility.class,"currentEncodingSet",
        new Class<?>[]{List.class},versions));return;
    }
    if (mode.equals("v5-probes")) {
      Map<String,Object> document=WorldBuilderJsonDocuments.readObject(root.resolve("payload.json"));
      Object probes=invoke(WorldBuilderRuntimeCompatibility.class,"artifactProbes",
        new Class<?>[]{Object.class,String.class},document.get("probes"),"fixture");
      invoke(WorldBuilderRuntimeCompatibility.class,"requireBlockedVoidProbes",new Class<?>[]{List.class},probes);
      Class<?> type=Class.forName("com.openrsc.worldbuilder.WorldBuilderRuntimeCompatibility$HostIntegration");
      Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
      Map<Integer,Object> matrix=new LinkedHashMap<>();matrix.put(4,probes);matrix.put(5,probes);
      Object integration=constructor.newInstance("","","",Collections.emptyList(),"",Collections.emptyList(),matrix,Collections.emptyList());
      Method required=type.getDeclaredMethod("requiredProbes",List.class);required.setAccessible(true);
      if(((List<?>)required.invoke(integration,Arrays.asList(4))).size()!=4)throw new AssertionError("v4 omitted promised v5 probes");
      return;
    }
    Map<String,Object> payload = WorldBuilderJsonDocuments.readObject(root.resolve("payload.json"));
    Map<String,Object> declaration = new LinkedHashMap<>(); declaration.put("encoding",payload.get("encoding"));
    if (mode.equals("journal")) {
      invoke(WorldBuilderLayeredTerrainDraftJournal.class,"upgradeNpcPlacementPayload",
        new Class<?>[]{Map.class,List.class,Map.class},payload,payload.get("npcs"),declaration);
    } else if (mode.equals("region")) {
      invoke(Class.forName("com.openrsc.worldbuilder.WorldBuilderRegionSnapshotService$PackageState"),
        "upgradeNpcPlacementEncoding",new Class<?>[]{Map.class,Map.class},payload,declaration);
    } else if (mode.equals("promote")) {
      WorldBuilderPlacementEncoding.promote(payload,declaration,Integer.parseInt(args[2]));
    } else if (mode.equals("header")) {
      WorldBuilderPlacementEncoding.validateHeader(payload,args[2]);
    } else throw new IllegalArgumentException(mode);
    System.out.println(WorldBuilderJsonDocuments.pretty(payload));
  }
}
'''


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def payload(version=5, level=0):
    result = dict(schemaVersion=version, encoding=f'layered-world-placements-v{version}',
                  worldSpace='global', level=level, boundaries=[], groundItems=[], scenery=[], npcs=[
                      dict(npcId=30, placementId='npc-a', start=dict(x=3,y=3),
                           roamBounds=dict(minimum=dict(x=-48,y=2), maximum=dict(x=80,y=4)))])
    if version >= 4:
        result['npcs'][0]['respawnSeconds'] = 17
    if version == 5:
        result['npcRoamCoverage'] = 'blocked-void'
    return result


class PlacementV5Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='editor-placement-v5-classes-')
        cls.classes = Path(cls.temp.name)
        source = cls.classes / 'PlacementV5Probe.java'
        source.write_text(HARNESS)
        sources = sorted((ROOT / 'tools/world-builder/src').rglob('*.java'))
        result = subprocess.run(['javac', '-source', '8', '-target', '8', '-d', str(cls.classes),
                                 *map(str,sources), str(source)], capture_output=True,text=True,timeout=60)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_probe(self, mode, root, *args, good=True):
        result = subprocess.run(['java','-cp',str(self.classes),MAIN,mode,str(root),*map(str,args)],
                                capture_output=True,text=True,timeout=20)
        if good:
            self.assertEqual(0,result.returncode,result.stderr)
        else:
            self.assertNotEqual(0,result.returncode,result.stdout)
        return result.stdout

    def package(self, root, body=None):
        body = body or payload()
        package = root / 'package'
        write_json(root/'definitions.json',dict(schemaVersion=1,manifestType='world-builder-definition-catalog',
                   catalogId='fixture-v1',tiles=[0,1],boundaries=[0],scenery=[0],npcs=[30],groundItems=[0]))
        package.mkdir(exist_ok=True)
        (package/'terrain.raw').write_bytes(bytes(48*48*10))
        write_json(package/'placements.json',body)
        manifest = dict(schemaVersion=1,packageType='layered-world',
                        packageId='rsc-remastered.spoiled-milk-layered-world',packageVersion='0.5.0',
                        coordinateModel='signed-layered-v1',storage=dict(presentationChunkSize=24,sectorSize=48),
                        worldSpaces=[dict(id='global',kind='static')],
                        levels=[dict(level=0,name='Surface',role='surface',worldSpace='global')],
                        terrainSectors=[dict(encoding='raw-layered-sector-v1',level=0,path='terrain.raw',
                                             sectorX=0,sectorY=0,sha256=digest(package/'terrain.raw'),worldSpace='global')],
                        placementSets=[dict(encoding=body['encoding'],id='global-surface',level=0,
                                            path='placements.json',sha256=digest(package/'placements.json'),worldSpace='global')])
        write_json(package/'manifest.json',manifest)
        return manifest

    def test_closed_headers_and_lossless_writer_policy(self):
        with tempfile.TemporaryDirectory(prefix='editor-placement-v5-') as tmp:
            root=Path(tmp)
            for mode in ['journal','region','promote']:
                body=payload();write_json(root/'payload.json',body)
                result=json.loads(self.run_probe(mode,root,*([5] if mode=='promote' else [])))
                self.assertEqual(body,result)
            self.run_probe('promote',root,4,good=False)
            for version in [3,4]:
                body=payload(version);write_json(root/'payload.json',body)
                result=json.loads(self.run_probe('promote',root,5))
                self.assertEqual('blocked-void',result['npcRoamCoverage'])
                self.assertEqual(-1 if version==3 else 17,result['npcs'][0]['respawnSeconds'])
            for key,value in [('npcRoamCoverage','trusted-legacy'),('schemaVersion',4),('unknown',True)]:
                body=payload();body[key]=value;write_json(root/'payload.json',body)
                self.run_probe('header',root,'layered-world-placements-v5',good=False)
            body=payload();del body['npcRoamCoverage'];write_json(root/'payload.json',body)
            self.run_probe('header',root,'layered-world-placements-v5',good=False)
            body=payload(4);body['npcRoamCoverage']='blocked-void';write_json(root/'payload.json',body)
            self.run_probe('header',root,'layered-world-placements-v4',good=False)

    def test_bounded_void_anchors_duplicates_and_old_v4_refusal(self):
        with tempfile.TemporaryDirectory(prefix='editor-placement-v5-') as tmp:
            root=Path(tmp);body=payload()
            duplicate=copy.deepcopy(body['npcs'][0]);duplicate['placementId']='npc-b';body['npcs'].append(duplicate)
            self.package(root,body)
            inspected=json.loads(self.run_probe('inspect',root))
            self.assertIn(5,inspected['encodings'])
            self.assertEqual(2,len(inspected['semantics']))
            self.assertTrue(all('npcRoamCoverage=blocked-void' in row for row in inspected['semantics']))
            self.run_probe('draft',root)
            for mutate in [lambda p:p['npcs'][0]['start'].update(x=-1),
                           lambda p:p['npcs'][0]['roamBounds']['maximum'].update(x=81),
                           lambda p:p['npcs'][0]['roamBounds']['minimum'].update(x=4)]:
                invalid=payload();mutate(invalid);self.package(root,invalid)
                self.run_probe('inspect',root,good=False)
            self.package(root,payload(4));self.run_probe('inspect',root,good=False)

    def test_new_level_preserves_uniform_v5_and_mixed_empty_sets_refuse(self):
        with tempfile.TemporaryDirectory(prefix='editor-placement-v5-') as tmp:
            root=Path(tmp);self.package(root);self.run_probe('new-level',root)
            manifest=json.loads((root/'package/manifest.json').read_text())
            self.assertEqual(2,len(manifest['placementSets']))
            for row in manifest['placementSets']:
                self.assertEqual('layered-world-placements-v5',row['encoding'])
                self.assertEqual('blocked-void',json.loads((root/'package'/row['path']).read_text())['npcRoamCoverage'])
            self.run_probe('draft',root)
            row=manifest['placementSets'][1];file=root/'package'/row['path'];body=json.loads(file.read_text())
            body.update(schemaVersion=4,encoding='layered-world-placements-v4');del body['npcRoamCoverage']
            write_json(file,body);row.update(encoding=body['encoding'],sha256=digest(file))
            write_json(root/'package/manifest.json',manifest)
            self.run_probe('inspect',root,good=False);self.run_probe('draft',root,good=False)

    def test_normalization_binds_policy_and_default_v4_is_unchanged(self):
        with tempfile.TemporaryDirectory(prefix='editor-placement-v5-') as tmp:
            root=Path(tmp);body=payload();body['npcs']=[];self.package(root,body)
            first=self.run_probe('normalize',root)
            before=(root/'package/placements.json').read_bytes()
            self.run_probe('compact',root)
            self.assertEqual(before,(root/'package/placements.json').read_bytes())
            self.assertEqual(first,self.run_probe('normalize',root))
            body=payload(4);body['npcs']=[];self.package(root,body)
            self.assertNotEqual(first,self.run_probe('normalize',root))
            self.run_probe('new-level',root)
            manifest=json.loads((root/'package/manifest.json').read_text())
            self.assertTrue(all(row['encoding']=='layered-world-placements-v4' for row in manifest['placementSets']))

    def test_actual_composition_promotes_all_sets_without_changing_bounds(self):
        with tempfile.TemporaryDirectory(prefix='editor-placement-v5-') as tmp:
            root=Path(tmp);body=payload();self.package(root,body)
            original=(root/'package/placements.json').read_bytes()
            other=root/'other';other.mkdir();old=payload(4);old['npcs']=[];self.package(other,old)
            (other/'package').rename(root/'legacy')
            manifest=json.loads((root/'legacy/manifest.json').read_text())
            manifest['levels'][0]['level']=1
            manifest['placementSets'][0].update(level=1,id='global-upper')
            manifest['terrainSectors'][0]['level']=1
            (root/'legacy/terrain.raw').rename(root/'legacy/upper.raw')
            manifest['terrainSectors'][0]['path']='upper.raw'
            old['level']=1;write_json(root/'legacy/placements.json',old)
            manifest['placementSets'][0]['sha256']=digest(root/'legacy/placements.json')
            (root/'legacy/placements.json').rename(root/'legacy/upper.json')
            manifest['placementSets'][0]['path']='upper.json'
            write_json(root/'legacy/manifest.json',manifest)
            self.run_probe('compose',root)
            result=json.loads((root/'composed/manifest.json').read_text())
            self.assertEqual(2,len(result['placementSets']))
            for row in result['placementSets']:
                self.assertEqual('layered-world-placements-v5',row['encoding'])
                saved=json.loads((root/'composed'/row['path']).read_text())
                self.assertEqual('blocked-void',saved['npcRoamCoverage'])
                if row['level']==0:self.assertEqual(body['npcs'],saved['npcs'])
            self.assertEqual(original,(root/'package/placements.json').read_bytes())
            self.assertNotIn('npcRoamCoverage',json.loads((root/'legacy/upper.json').read_text()))

    def test_region_paste_requires_present_anchor_without_activating_void(self):
        with tempfile.TemporaryDirectory(prefix='editor-placement-v5-') as tmp:
            root=Path(tmp);self.package(root)
            original=digest(root/'package/manifest.json')
            self.assertEqual('true',self.run_probe('region-coverage',root).strip())
            self.assertEqual(original,digest(root/'package/manifest.json'))
            body=payload();body['npcs'][0]['start']['x']=-1;self.package(root,body)
            self.assertEqual('false',self.run_probe('region-coverage',root).strip())
            self.package(root,payload(4))
            self.assertEqual('false',self.run_probe('region-coverage',root).strip())

    def test_current_capability_removal_is_not_accepted(self):
        with tempfile.TemporaryDirectory(prefix='editor-placement-v5-') as tmp:
            self.assertEqual('true',self.run_probe('capability-versions',tmp,5).strip())
            for version in [3,4,6]:
                self.assertEqual('false',self.run_probe('capability-versions',tmp,version).strip())
            probes=[dict(archive='server-core',archiveEntryPath='com/openrsc/server/io/NativeLayeredWorldPackage.class',
                         requiredClassMarkers=['layered-world-placements-v5','npcRoamCoverage','blocked-void']),
                    dict(archive='client-runtime',archiveEntryPath='orsc/AdaptiveWorldBuilderClientSession.class',
                         requiredClassMarkers=['layered-world-placements-v5'])]
            write_json(Path(tmp)/'payload.json',dict(probes=probes));self.run_probe('v5-probes',tmp)
            for mutation in [probes[:1],list(reversed(probes)),copy.deepcopy(probes)]:
                if len(mutation)==2 and mutation[0]['archive']=='server-core':
                    mutation[0]['requiredClassMarkers'].remove('blocked-void')
                write_json(Path(tmp)/'payload.json',dict(probes=mutation))
                self.run_probe('v5-probes',tmp,good=False)


if __name__=='__main__':
    unittest.main()
