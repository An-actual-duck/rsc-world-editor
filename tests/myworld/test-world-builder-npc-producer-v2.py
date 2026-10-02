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
import gzip
import struct
from adaptive_project_test_support import load_discovery_fixtures, declare_effective_content_sources
from npc_producer_v2_test_support import install_v2_fixture,write_json
ROOT=Path(__file__).resolve().parents[2]
CLASSES=ROOT/'output/world-builder-tools/classes'
HARNESS='''package com.openrsc.worldbuilder;
import java.nio.file.*;import java.util.*;
public class NpcProducerV2Harness {
 public static void main(String[] args)throws Exception {
  if("osar-parity".equals(args[0])){
   java.util.Map<String,WorldBuilderNpcProducerFrames.OsarEntry> decoded=WorldBuilderNpcProducerFrames.osar(Paths.get(args[1]));
   orsc.graphics.two.SpriteArchive.Workspace source=new orsc.graphics.two.SpriteArchive.Unpacker().unpackArchive(Paths.get(args[1]).toFile());int count=0;
   for(orsc.graphics.two.SpriteArchive.Subspace sub:source.getSubspaces())for(orsc.graphics.two.SpriteArchive.Entry entry:sub.getEntryList()){
    java.util.List<byte[]> frames=decoded.get(sub.getName()+"/"+entry.getID()).frames;int i=0;
    for(orsc.graphics.two.SpriteArchive.Frame frame:entry.getFrames()){
     java.nio.ByteBuffer expected=java.nio.ByteBuffer.allocate(25+frame.getPixels().length*4);expected.putInt(frame.getWidth()).putInt(frame.getHeight()).put((byte)(frame.getUseShift()?1:0)).putInt(frame.getOffsetX()).putInt(frame.getOffsetY()).putInt(frame.getBoundWidth()).putInt(frame.getBoundHeight());for(int pixel:frame.getPixels())expected.putInt(pixel);
     if(!Arrays.equals(expected.array(),frames.get(i++)))throw new AssertionError("Actual maintained OSAR decoder parity failed");count++;
    }
   }System.out.println(WorldBuilderJsonDocuments.pretty(Collections.singletonMap("frames",count)));return;
  }
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
  provider=ROOT/'.runtime-provider/Client_Base/src'
  sources=[provider/'com/openrsc/client/model/Sprite.java']+[provider/'orsc/graphics/two/SpriteArchive'/f'{name}.java' for name in ['Unpacker','Workspace','Subspace','Entry','Frame']]
  subprocess.run(['javac','-cp',str(CLASSES),'-d',str(root),*[str(p) for p in sources],str(root/'NpcProducerV2Harness.java')],check=True)
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
 def test_lossless_osar_conversion_matches_actual_locked_unpacker(self):
  with tempfile.TemporaryDirectory() as temp:
   path=Path(temp)/'frames.osar';payload=bytearray([0,15,3]);payload.extend(bytes.fromhex('000000 ff00ff 808080 804020'))
   for i in range(15):payload.extend(struct.pack('>HHBhhHH',2,2,i%2,-3,4,7,8)+bytes([0,1,2,3]))
   path.write_bytes(gzip.compress(b'\x01npc\0\x00\x01fixture\0'+payload,mtime=0))
   result=subprocess.run(['java','-cp',os.pathsep.join([self.temp.name,str(CLASSES)]),'com.openrsc.worldbuilder.NpcProducerV2Harness','osar-parity',str(path)],text=True,capture_output=True)
   self.assertEqual(0,result.returncode,result.stderr);self.assertEqual({'frames':15},json.loads(result.stdout))
 def test_only_literal_unambiguous_config_defaults_are_accepted(self):
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,original=self.fixture(Path(temp));config=target/'server/myworld.conf';config.write_text('\n'.join(line for line in config.read_text().splitlines() if not line.startswith(('custom_sprites:','allow_bearded_ladies:')))+'\n')
   declare_effective_content_sources(target,item_sources=())
   source=target/'server/src/com/openrsc/server/ServerConfiguration.java';source.parent.mkdir(parents=True,exist_ok=True)
   valid='WANT_CUSTOM_SPRITES = tryReadBool("custom_sprites").orElse(false);\nALLOW_BEARDED_LADIES = tryReadBool("allow_bearded_ladies").orElse(false);\n'
   for text,success in [(valid,True),('/*'+valid+'*/',False),(valid+'WANT_CUSTOM_SPRITES = true;',False),(valid+valid,False)]:
    source.write_text(text);doc=copy.deepcopy(original);doc['provider']['configuration']['sha256']=hashlib.sha256(config.read_bytes()).hexdigest();doc['provider']['sources'].append({'sourceId':'server-config','role':'server-npc-loader','relativePath':str(source.relative_to(target)),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()});write_json(manifest,doc);self.run_capture(target,success)
 def runtime(self,base):
  path=Path(base)/'private-runtime.jar'
  with zipfile.ZipFile(path,'w') as jar:jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nWorld-Builder-Npc-Rgb: npc-rgb-frames-v1\nWorld-Builder-Npc-Mask-Policy: npc-mask-policy-v1\nWorld-Builder-Npc-Animation-Count: 1080\n\n')
  return path
 def test_two_active_complete_producers_refuse(self):
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,doc=self.fixture(Path(temp));write_json(target/'server/conf/world-builder/npc-definitions-v2.json',doc)
   self.assertIn('two active',self.run_capture(target,False).lower())
 def test_disjoint_active_pack_is_bound_but_npc_override_refuses(self):
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,doc=self.fixture(Path(temp));config=target/'Client_Base/Cache/config.txt';config.write_text('Menus:1\n')
   pack=target/'Client_Base/Cache/video/spritepacks/Menus.osar';pack.parent.mkdir(parents=True,exist_ok=True)
   for category,name,success in [('gui','menu',True),('npc','fixture',False)]:
    payload=bytes([0,1,0,0x12,0x34,0x56])+struct.pack('>HHBhhHHB',1,1,0,0,0,1,1,0)
    pack.write_bytes(gzip.compress(b'\x01'+category.encode()+b'\0\x00\x01'+name.encode()+b'\0'+payload,mtime=0))
    updated=copy.deepcopy(doc)
    for identity,path,role in [('selector',config,'configuration-input'),('pack',pack,'sprite-input')]:updated['provider']['sources'].append({'sourceId':identity,'role':role,'relativePath':str(path.relative_to(target)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    probe=updated['provider']['resolutionProbes'][0];probe.update(present=True,sha256=hashlib.sha256(config.read_bytes()).hexdigest())
    for key in ['inputSourceIds','precedenceSourceIds']:updated['animationDefinitions'][0]['resolution'][key]+=['selector','pack']
    write_json(manifest,updated);result=self.run_capture(target,success)
    if not success:self.assertIn('overrides npc animation',result.lower())
 def test_malformed_or_undeclared_rgb_frames_are_rejected_before_publication(self):
  with tempfile.TemporaryDirectory() as temp:
   target,manifest,original=self.fixture(Path(temp));runtime=self.runtime(temp);archive=target/'world-builder-provider/npc-frames.zip'
   with zipfile.ZipFile(archive) as zipped:initial={name:zipped.read(name) for name in zipped.namelist()}
   variants={'bad-pixel':lambda entries:entries.update({'frames/0.dat':entries['frames/0.dat'][:-4]+bytes.fromhex('ff102030')}),'undeclared':lambda entries:entries.update({'undeclared.dat':initial['frames/0.dat']}),'duplicate-numeric':lambda entries:entries.update({'sprites/7':initial['frames/0.dat'],'7.dat':initial['frames/0.dat']})}
   for label,mutate in variants.items():
    with self.subTest(label=label):
     entries=dict(initial);mutate(entries)
     with zipfile.ZipFile(archive,'w') as zipped:
      for name,data in entries.items():zipped.writestr(name,data)
     (target/'server/conf/world-builder/npc-frames.zip').write_bytes(archive.read_bytes());doc=copy.deepcopy(original);digest=hashlib.sha256(archive.read_bytes()).hexdigest();doc['assetProviders'][0]['sha256']=digest
     for row in doc['provider']['sources']:
      if row['sourceId']=='frames':row['sha256']=digest
     doc['animationDefinitions'][0]['frames']['frameSha256s'][0]=hashlib.sha256(entries['frames/0.dat']).hexdigest();write_json(manifest,doc)
     self.run_capture(target);before={p:p.read_bytes() for p in target.rglob('*') if p.is_file()};self.run_capture(target,False,runtime);self.assertEqual(before,{p:p.read_bytes() for p in target.rglob('*') if p.is_file()})
if __name__=='__main__':unittest.main()
