#!/usr/bin/env python3
"""Complete inert producer validation, with no target execution or mutation."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
import hashlib
import struct
from adaptive_project_test_support import load_discovery_fixtures
from npc_producer_v2_test_support import install_v2_fixture,write_json
ROOT=Path(__file__).resolve().parents[2]
CLASSES=ROOT/'output/world-builder-tools/classes'
HARNESS='''package com.openrsc.worldbuilder;
import java.nio.file.*;import java.util.*;
public class NpcProducerV2Harness {
 public static void main(String[] args)throws Exception {
  WorldBuilderNpcProducerV2.Capture capture=WorldBuilderNpcProducerV2.discover(WorldBuilderReadOnlyTarget.open(Paths.get(args[0])),WorldBuilderPackedSourceLayout.canonical("server/myworld.conf"));
  Map<String,Object> out=new TreeMap<>();out.put("npcs",capture.document.npcs.size());out.put("animations",capture.document.animations.size());
  List<Object> evidence=new ArrayList<>();for(WorldBuilderReadOnlyTarget.FileState state:capture.evidence)evidence.add(state.toJson());out.put("evidence",evidence);
  if(args.length>1){WorldBuilderNpcProducerFrames.Result result=WorldBuilderNpcProducerFrames.normalize(capture,WorldBuilderReadOnlyTarget.open(Paths.get(args[0])),new ArrayList<Object>(),null,Paths.get(args[1]),Paths.get(args[1]));out.put("normalizedAnimations",result.animations);out.put("overlays",result.overlays);out.put("limitations",result.limitations);out.put("normalizedArchiveSha256",WorldBuilderHashes.sha256(result.authenticArchive));}
  System.out.println(WorldBuilderJsonDocuments.pretty(out));
 }
}'''
class NpcProducerV2Test(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  subprocess.run([str(ROOT/'scripts/build-tools.sh')],check=True)
  cls.temp=tempfile.TemporaryDirectory(prefix='npc-v2-harness-');root=Path(cls.temp.name);(root/'NpcProducerV2Harness.java').write_text(HARNESS)
  subprocess.run(['javac','-cp',str(CLASSES),'-d',str(root),str(root/'NpcProducerV2Harness.java')],check=True)
 @classmethod
 def tearDownClass(cls):cls.temp.cleanup()
 def fixture(self,base,count=15):
  fixture=load_discovery_fixtures();target=fixture.legacy_fixture(str(base));defs=target/'server/conf/server/defs'
  write_json(defs/'NpcDefs.json',{'npcs':[{'id':0,'name':'Original authoritative NPC'}]})
  for file in ['NpcDefsCustom.json','NpcDefsPatch18.json','NpcDefsMyWorld.json']:write_json(defs/file,{'npcs':[]})
  manifest,document=install_v2_fixture(target,frame_count=count);return target,manifest,document
 def run_capture(self,target,success=True,runtime=None):
  run=subprocess.run(['java','-cp',os.pathsep.join([self.temp.name,str(CLASSES)]),'com.openrsc.worldbuilder.NpcProducerV2Harness',str(target),*([str(runtime)] if runtime else[])],text=True,capture_output=True)
  self.assertEqual(success,run.returncode==0,run.stdout+run.stderr);return json.loads(run.stdout) if success else run.stderr
 def test_complete_unplaced_capture_retains_absence_without_writes(self):
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,doc=self.fixture(Path(temp));before={p:p.read_bytes() for p in target.rglob('*') if p.is_file()};out=self.run_capture(target)
   self.assertEqual(1,out['npcs']);self.assertEqual(1,out['animations'])
   probes=[row for row in out['evidence'] if row['role']=='npc-producer-v2-probe'];self.assertEqual(2,len(probes));self.assertTrue(all(not row['present'] for row in probes))
   self.assertEqual(before,{p:p.read_bytes() for p in target.rglob('*') if p.is_file()})
 def test_twenty_one_source_frames_preserve_explicit_eighteen_frame_preview(self):
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,doc=self.fixture(Path(temp),21);self.run_capture(target)
   doc['animationDefinitions'][0]['hasSpecialCombatFrames']=True;write_json(manifest,doc);self.assertIn('through26',self.run_capture(target,False))
 def test_private_normalization_retains_identity_frames_masks_and_source_inventory(self):
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,doc=self.fixture(Path(temp),21);runtime=Path(temp)/'private-runtime.jar'
   with zipfile.ZipFile(runtime,'w') as jar:jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nWorld-Builder-Npc-Rgb: npc-rgb-frames-v1\nWorld-Builder-Npc-Mask-Policy: npc-mask-policy-v1\nWorld-Builder-Npc-Animation-Count: 1080\n\n')
   before={p:p.read_bytes() for p in target.rglob('*') if p.is_file()};out=self.run_capture(target,runtime=runtime)
   animation=out['normalizedAnimations'][0];self.assertEqual(1080,animation['animationId']);self.assertEqual(18,animation['requiredFrameCount']);self.assertFalse(animation['hasSpecialCombatFrames'])
   self.assertEqual('hair-and-skin',animation['npcMaskPolicy']);self.assertEqual(0,animation['sourceAnimationId']);self.assertFalse(animation['sourceCustomSprites'])
   self.assertEqual(doc['animationDefinitions'][0]['frames']['frameSha256s'][:18],animation['authenticFrameSha256s'])
   self.assertEqual(0,out['overlays'][0]['id']);self.assertNotIn('name',out['overlays'][0]);self.assertEqual(21,out['limitations'][0]['resolvedFrameCount'])
   self.assertEqual(before,{p:p.read_bytes() for p in target.rglob('*') if p.is_file()})
   with zipfile.ZipFile(runtime,'w') as jar:jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nWorld-Builder-Npc-Rgb: npc-rgb-frames-v1\nWorld-Builder-Npc-Animation-Count: 1080\n\n')
   self.assertIn('lacks verified',self.run_capture(target,False,runtime=runtime))
 def test_stale_missing_or_changed_evidence_and_identity_refuse(self):
  mutations={
   'stale-config':lambda d:d['provider']['configuration'].update(sha256='0'*64),
   'branch':lambda d:d['provider'].update(spriteBranch='custom'),
   'flag':lambda d:d['provider']['clientFlags'].update({'Config.S_ALLOW_BEARDED_LADIES':True}),
   'mask':lambda d:d['animationDefinitions'][0].update(npcMaskPolicy='literal-only'),
   'unknown-id':lambda d:(d['selection'].update(npcIds=[1]),d['npcDefinitions'][0].update(npcId=1)),
   'missing-closure':lambda d:d.update(animationDefinitions=[]),
   'missing-source':lambda d:d['provider']['sources'].pop(0),
   'unsafe-path':lambda d:d['provider']['sources'][0].update(relativePath='../Core-Framework/defs.json'),
   'fake-population':lambda d:d['selection'].update(placementCount=0),
   'zero-walk':lambda d:d['npcDefinitions'][0].update(walkModel=0),
  }
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,original=self.fixture(Path(temp))
   for label,mutate in mutations.items():
    with self.subTest(label=label):doc=copy.deepcopy(original);mutate(doc);write_json(manifest,doc);self.run_capture(target,False)
 def test_earlier_absent_png_appearance_invalidates_even_unchanged_frame_archive(self):
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,doc=self.fixture(Path(temp));self.run_capture(target)
   path=target/'Client_Base/dev/myworld/assets/npcs/optional.png';path.parent.mkdir(parents=True);path.write_bytes(b'new earlier candidate')
   self.assertIn('appeared',self.run_capture(target,False))
if __name__=='__main__':unittest.main()
